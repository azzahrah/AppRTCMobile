package com.serenegiant.webrtc;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.glutils.IRendererHolder;
import com.serenegiant.glutils.RenderHolderCallback;
import com.serenegiant.glutils.RendererHolder;

import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;

import javax.annotation.Nullable;

public class SurfaceCaptureAndroid
	implements SurfaceVideoCapture {

	private static final boolean DEBUG = true; // set false on production
	private static final String TAG = CameraSurfaceCapture.class.getSimpleName();

	protected final Object stateLock = new Object();
	@NonNull
	private final EventsHandler eventsHandler;
	@Nullable
	private SurfaceTextureHelper surfaceHelper;
	protected Context applicationContext;
	@Nullable
	protected CapturerObserver capturerObserver;
	@Nullable
	private Handler captureThreadHandler;
	private long numCapturedFrames = 0L;
	private boolean isDisposed = false;
	private int width;
	private int height;
	private int framerate;
	@Nullable
	private IRendererHolder mRendererHolder;
	private int mCaptureSurfaceId;
	@Nullable
	private Statistics mStatistics;

	public SurfaceCaptureAndroid(@NonNull final EventsHandler eventsHandler) {
		this.eventsHandler = eventsHandler;
		mRendererHolder = createRendererHolder();
	}

	@Override
	public void initialize(final SurfaceTextureHelper surfaceTextureHelper,
		final Context applicationContext, final CapturerObserver capturerObserver) {

		synchronized (stateLock) {
			checkNotDisposed();
			if (capturerObserver == null) {
				throw new RuntimeException("capturerObserver not set.");
			} else {
				this.applicationContext = applicationContext;
				this.capturerObserver = capturerObserver;
				if (surfaceTextureHelper == null) {
					throw new RuntimeException("surfaceTextureHelper not set.");
				} else {
					this.surfaceHelper = surfaceTextureHelper;
					captureThreadHandler = surfaceTextureHelper.getHandler();
				}
			}
		}
	}
	
	@Override
	public void startCapture(final int width, final int height, final int framerate) {
		if (DEBUG) Log.v(TAG, "startCapture:");
		synchronized (stateLock) {
			checkNotDisposed();
			if (surfaceHelper != null) {
				mStatistics = new Statistics(surfaceHelper, eventsHandler);
			} else {
				throw new IllegalStateException("not initialized");
			}
			this.width = width;
			this.height = height;
			this.framerate = framerate;
			capturerObserver.onCapturerStarted(true);
			surfaceHelper.startListening(mOnTextureFrameAvailableListener);
			resize(width, height);
			setSurface();
		}
	}
	
	@Override
	public void stopCapture() {
		if (DEBUG) Log.v(TAG, "stopCapture:");
		synchronized (stateLock) {
			checkNotDisposed();
			if (mStatistics != null) {
				mStatistics.release();
				mStatistics = null;
			}
			ThreadUtils.invokeAtFrontUninterruptibly(this.surfaceHelper.getHandler(), new Runnable() {
				public void run() {
					if (mRendererHolder != null) {
						mRendererHolder.removeSurface(mCaptureSurfaceId);
					}
					mCaptureSurfaceId = 0;
					surfaceHelper.stopListening();
					capturerObserver.onCapturerStopped();
				}
			});
		}
	}
	
	@Override
	public void changeCaptureFormat(final int width, final int height, final int framerate) {
		if (DEBUG) Log.v(TAG, "changeCaptureFormat:");
		synchronized (stateLock) {
			checkNotDisposed();
			this.width = width;
			this.height = height;
			this.framerate = framerate;
			resize(width, height);
			setSurface();
		}
	}
	
	@Override
	public void dispose() {
		if (DEBUG) Log.v(TAG, "dispose:");
		stopCapture();
		synchronized (stateLock) {
			isDisposed = true;
			if (mRendererHolder != null) {
				mRendererHolder.release();
				mRendererHolder = null;
			}
		}
	}
	
	@Override
	public boolean isScreencast() {
		return false;
	}

	private final SurfaceTextureHelper.OnTextureFrameAvailableListener
		mOnTextureFrameAvailableListener = new SurfaceTextureHelper.OnTextureFrameAvailableListener() {
		@Override
		public void onTextureFrameAvailable(
			final int oesTextureId, final float[] transformMatrix,
			final long timestampNs) {
	
			++numCapturedFrames;
			if (DEBUG && ((numCapturedFrames % 100) == 0)) Log.v(TAG, "onTextureFrameAvailable:" + numCapturedFrames);
			final VideoFrame.Buffer buffer = surfaceHelper.createTextureBuffer(width, height,
				RendererCommon.convertMatrixToAndroidGraphicsMatrix(onUpdateTexMatrix(transformMatrix)));
			final VideoFrame frame = new VideoFrame(buffer, getFrameRotation(), timestampNs);
			try {
				capturerObserver.onFrameCaptured(frame);
			} finally {
				frame.release();
			}
		}
	};
	
	public long getNumCapturedFrames() {
		return numCapturedFrames;
	}

	@Override
	@Nullable
	public Surface getInputSurface() {
		synchronized (stateLock) {
			return mRendererHolder != null ? mRendererHolder.getSurface() : null;
		}
	}

	@Override
	@Nullable
	public SurfaceTexture getInputSurfaceTexture() {
		synchronized (stateLock) {
			return mRendererHolder != null ? mRendererHolder.getSurfaceTexture() : null;
		}
	}

	@Override
	public void addSurface(final int id, final Object surface, final boolean isRecordable) {
		synchronized (stateLock) {
			checkNotDisposed();
			final IRendererHolder rendererHolder = mRendererHolder;
			if (!isDisposed && rendererHolder != null) {
				rendererHolder.addSurface(id, surface, isRecordable);
			}
		}
	}
	
	@Override
	public void addSurface(final int id, final Object surface, final boolean isRecordable, final int maxFps) {
		synchronized (stateLock) {
			checkNotDisposed();
			final IRendererHolder rendererHolder = mRendererHolder;
			if (!isDisposed && rendererHolder != null) {
				rendererHolder.addSurface(id, surface, isRecordable, maxFps);
			}
		}
	}

	@Override
	public void removeSurface(final int id) {
		synchronized (stateLock) {
			final IRendererHolder rendererHolder = mRendererHolder;
			if (!isDisposed && rendererHolder != null) {
				rendererHolder.removeSurface(id);
			}
		}
	}

	@NonNull
	protected IRendererHolder createRendererHolder() {
		return new RendererHolder(width, height, mRenderHolderCallback);
	}

	@NonNull
	protected float[] onUpdateTexMatrix(@NonNull final float[] transformMatrix) {
		return transformMatrix;
	}
	
	protected int getFrameRotation() {
		return 0;
	}

	protected void checkNotDisposed() {
		if (isDisposed) {
			throw new RuntimeException("capturer is disposed.");
		}
	}

	private void resize(final int width, final int height) {
		if (mRendererHolder != null) {
			mRendererHolder.resize(width, height);
		}
	}
	
	private void setSurface() {
		if ((mCaptureSurfaceId != 0) && (mRendererHolder != null)) {
			mRendererHolder.removeSurface(mCaptureSurfaceId);
		}
		final SurfaceTexture st = surfaceHelper.getSurfaceTexture();
		st.setDefaultBufferSize(width, height);
		final Surface surface = new Surface(st);
		mCaptureSurfaceId = surface.hashCode();
		mRendererHolder.addSurface(mCaptureSurfaceId, surface, false);
	}
	
	public void printStackTrace() {
		Thread cameraThread = null;
		if (captureThreadHandler != null) {
			cameraThread = captureThreadHandler.getLooper().getThread();
		}
		
		if (cameraThread != null) {
			final StackTraceElement[] cameraStackTrace = cameraThread.getStackTrace();
			if (cameraStackTrace.length > 0) {
				Logging.d("CameraCapturer", "CameraCapturer stack trace:");
				final StackTraceElement[] elements = cameraStackTrace;
				final int n = cameraStackTrace.length;
				
				for (int i = 0; i < n; i++) {
					final StackTraceElement traceElem = elements[i];
					Logging.d("CameraCapturer", traceElem.toString());
				}
			}
		}
		
	}

	protected void checkIsOnCaptureThread() {
		if (Thread.currentThread() != captureThreadHandler.getLooper().getThread()) {
			Logging.e("CameraCapturer", "Check is on camera thread failed.");
			throw new RuntimeException("Not on camera thread.");
		}
	}
	

	protected void postDelayed(final Runnable task, final long delayMs) {
		captureThreadHandler.postDelayed(task, delayMs);
	}

	protected void post(final Runnable task) {
		captureThreadHandler.post(task);
	}
	
	protected Handler getCaptureHandler() {
		return captureThreadHandler;
	}
	
	@Nullable
	protected SurfaceTextureHelper getSurfaceHelper() {
		return surfaceHelper;
	}
	
	protected int width() {
		synchronized (stateLock) {
			return width;
		}
	}

	protected int height() {
		synchronized (stateLock) {
			return height;
		}
	}

	protected int framerate() {
		synchronized (stateLock) {
			return framerate;
		}
	}
	
	private final RenderHolderCallback mRenderHolderCallback
		= new RenderHolderCallback() {
		@Override
		public void onCreate(final Surface surface) {
			if (DEBUG) Log.v(TAG, "onCreate:");
		}
		
		@Override
		public void onFrameAvailable() {
			try {
				if (mStatistics != null) {
					mStatistics.addFrame();
				}
			} catch (final Exception e) {
				// ignore
			}
		}
		
		@Override
		public void onDestroy() {
			if (DEBUG) Log.v(TAG, "onDestroy:");
		}
	};
}
