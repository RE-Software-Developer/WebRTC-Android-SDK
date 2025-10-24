/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

@SuppressWarnings("deprecation")
class Camera1Session implements CameraSession {
  private static final String TAG = "Camera1Session";
  private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;
  private static final int zoomSpeed = 4;

  private static final Histogram camera1StartTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera1.StartTimeMs", 1, 10000, 50);
  private static final Histogram camera1StopTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera1.StopTimeMs", 1, 10000, 50);
  private static final Histogram camera1ResolutionHistogram = Histogram.createEnumeration(
      "WebRTC.Android.Camera1.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

  private static enum SessionState { RUNNING, STOPPED }

  private final Handler cameraThreadHandler;
  private final Events events;
  private final boolean captureToTexture;
  private final Context applicationContext;
  private final SurfaceTextureHelper surfaceTextureHelper;
  private final int cameraId;
  private final Camera camera;
  private final Camera.CameraInfo info;
  private final CaptureFormat captureFormat;
  // Used only for stats. Only used on the camera thread.
  private final long constructionTimeNs; // Construction time of this class.

  private SessionState state;
  private boolean firstFrameReported;

  // TODO(titovartem) make correct fix during webrtc:9175
  @SuppressWarnings("ByteBufferBackingArray")
  public static void create(final CreateSessionCallback callback, final Events events,
      final boolean captureToTexture, final Context applicationContext,
      final SurfaceTextureHelper surfaceTextureHelper, final String cameraName, final int width,
      final int height, final int framerate) {
    final long constructionTimeNs = System.nanoTime();
    Logging.d(TAG, "Open camera " + cameraName);
    events.onCameraOpening();

    final int cameraId;
    try {
      cameraId = Camera1Enumerator.getCameraIndex(cameraName);
    } catch (IllegalArgumentException e) {
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return;
    }

    final Camera camera;
    try {
      camera = Camera.open(cameraId);
    } catch (RuntimeException e) {
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return;
    }

    if (camera == null) {
      callback.onFailure(
          FailureType.ERROR, "Camera.open returned null for camera id = " + cameraId);
      return;
    }

    try {
      camera.setPreviewTexture(surfaceTextureHelper.getSurfaceTexture());
    } catch (IOException | RuntimeException e) {
      camera.release();
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return;
    }

    final Camera.CameraInfo info = new Camera.CameraInfo();
    Camera.getCameraInfo(cameraId, info);

    final CaptureFormat captureFormat;
    try {
      final Camera.Parameters parameters = camera.getParameters();
      captureFormat = findClosestCaptureFormat(parameters, width, height, framerate);
      final Size pictureSize = findClosestPictureSize(parameters, width, height);
      updateCameraParameters(camera, parameters, captureFormat, pictureSize, captureToTexture);
    } catch (RuntimeException e) {
      camera.release();
      callback.onFailure(FailureType.ERROR, e.getMessage());
      return;
    }

    if (!captureToTexture) {
      final int frameSize = captureFormat.frameSize();
      for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
        camera.addCallbackBuffer(buffer.array());
      }
    }

    // Calculate orientation manually and send it as CVO instead.
//    try {
//      camera.setDisplayOrientation(0 /* degrees */);
//    } catch (RuntimeException e) {
//      camera.release();
//      callback.onFailure(FailureType.ERROR, e.getMessage());
//      return;
//    }
    camera.setDisplayOrientation(0 /* degrees */);

    callback.onDone(new Camera1Session(events, captureToTexture, applicationContext,
        surfaceTextureHelper, cameraId, camera, info, captureFormat, constructionTimeNs));
  }

  private static void updateCameraParameters(Camera camera, Camera.Parameters parameters,
      CaptureFormat captureFormat, Size pictureSize, boolean captureToTexture) {
    final List<String> focusModes = parameters.getSupportedFocusModes();

    parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
    parameters.setPreviewSize(captureFormat.width, captureFormat.height);
    parameters.setPictureSize(pictureSize.width, pictureSize.height);
    if (!captureToTexture) {
      parameters.setPreviewFormat(captureFormat.imageFormat);
    }

    if (parameters.isVideoStabilizationSupported()) {
      parameters.setVideoStabilization(true);
    }
    //Here we set noncontinuous autofocus. This can changed to allow customisation if needed
    if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
      parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
    }
    camera.setParameters(parameters);
  }

  private static CaptureFormat findClosestCaptureFormat(
      Camera.Parameters parameters, int width, int height, int framerate) {
    // Find closest supported format for `width` x `height` @ `framerate`.
    final List<CaptureFormat.FramerateRange> supportedFramerates =
        Camera1Enumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
    Logging.d(TAG, "Available fps ranges: " + supportedFramerates);

    final CaptureFormat.FramerateRange fpsRange =
        CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);

    final Size previewSize = CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPreviewSizes()), width, height);
    CameraEnumerationAndroid.reportCameraResolution(camera1ResolutionHistogram, previewSize);

    return new CaptureFormat(previewSize.width, previewSize.height, fpsRange);
  }

  private static Size findClosestPictureSize(Camera.Parameters parameters, int width, int height) {
    return CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
  }

  private Camera1Session(Events events, boolean captureToTexture, Context applicationContext,
      SurfaceTextureHelper surfaceTextureHelper, int cameraId, Camera camera,
      Camera.CameraInfo info, CaptureFormat captureFormat, long constructionTimeNs) {
    Logging.d(TAG, "Create new camera1 session on camera " + cameraId);

    this.cameraThreadHandler = new Handler();
    this.events = events;
    this.captureToTexture = captureToTexture;
    this.applicationContext = applicationContext;
    this.surfaceTextureHelper = surfaceTextureHelper;
    this.cameraId = cameraId;
    this.camera = camera;
    this.info = info;
    this.captureFormat = captureFormat;
    this.constructionTimeNs = constructionTimeNs;

    surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);

    startCapturing();
  }

  @Override
  public void stop() {
    Logging.d(TAG, "Stop camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      final long stopStartTime = System.nanoTime();
      stopInternal();
      final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
      camera1StopTimeMsHistogram.addSample(stopTimeMs);
    }
  }

  @Override
  public void enableTorch() {
    Logging.d(TAG, "Enable torch camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      enableTorchInternal();
    }
  }

  @Override
  public void disableTorch() {
    Logging.d(TAG, "Disable torch camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      disableTorchInternal();
    }
  }

  @Override
  public void zoomIn() {
    Logging.d(TAG, "Zoom in camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      zoomInInternal();
    }
  }

  @Override
  public void zoomOut() {
    Logging.d(TAG, "Zoom out camera1 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      zoomOutInternal();
    }
  }

  @Override
  public boolean focus(float x, float y, int w, int h) {
    Logging.d(TAG, "Focus camera1 session on camera " + cameraId);
    if (state != SessionState.STOPPED) {
      return focusInternal(x, y, w, h);
    }
    return false;
  }

  private void startCapturing() {
    Logging.d(TAG, "Start capturing");
    checkIsOnCameraThread();

    state = SessionState.RUNNING;

    camera.setErrorCallback(new Camera.ErrorCallback() {
      @Override
      public void onError(int error, Camera camera) {
        String errorMessage;
        if (error == Camera.CAMERA_ERROR_SERVER_DIED) {
          errorMessage = "Camera server died!";
        } else {
          errorMessage = "Camera error: " + error;
        }
        Logging.e(TAG, errorMessage);
        stopInternal();
        if (error == Camera.CAMERA_ERROR_EVICTED) {
          events.onCameraDisconnected(Camera1Session.this);
        } else {
          events.onCameraError(Camera1Session.this, errorMessage);
        }
      }
    });

    if (captureToTexture) {
      listenForTextureFrames();
    } else {
      listenForBytebufferFrames();
    }
    try {
      camera.startPreview();
    } catch (RuntimeException e) {
      stopInternal();
      events.onCameraError(this, e.getMessage());
    }
  }

  private void stopInternal() {
    Logging.d(TAG, "Stop internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    state = SessionState.STOPPED;
    surfaceTextureHelper.stopListening();
    // Note: stopPreview or other driver code might deadlock. Deadlock in
    // Camera._stopPreview(Native Method) has been observed on
    // Nexus 5 (hammerhead), OS version LMY48I.
    camera.stopPreview();
    camera.release();
    events.onCameraClosed(this);
    Logging.d(TAG, "Stop done");
  }

  private void enableTorchInternal() {
    Logging.d(TAG, "Enable torch internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    try {
      Camera.Parameters parameters = camera.getParameters();
      parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
      camera.setParameters(parameters);
    } catch (Exception e) {
      e.printStackTrace();
      return;
    }

    Logging.d(TAG, "Enable torch done");
  }

  private void disableTorchInternal() {
    Logging.d(TAG, "Disable torch internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    try {
      Camera.Parameters parameters = camera.getParameters();
      parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
      camera.setParameters(parameters);
    } catch(Exception e) {
      e.printStackTrace();
      return;
    }

    Logging.d(TAG, "Disable torch done");
  }

  private void zoomInInternal() {
    Logging.d(TAG, "Zoom In internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    try {
      Camera.Parameters parameters = camera.getParameters();
      int currentZoomLevel = parameters.getZoom();
      int newZoomLevel = Math.min(currentZoomLevel + zoomSpeed, parameters.getMaxZoom());
      if (newZoomLevel == currentZoomLevel) {
        return;
      }

      parameters.setZoom(newZoomLevel);
      camera.setParameters(parameters);
    } catch(Exception e) {
      e.printStackTrace();
      return;
    }

    Logging.d(TAG, "Zoom In done");
  }

  private void zoomOutInternal() {
    Logging.d(TAG, "Zoom Out internal");
    checkIsOnCameraThread();
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return;
    }

    try {
      Camera.Parameters parameters = camera.getParameters();
      int currentZoomLevel = parameters.getZoom();
      int newZoomLevel = Math.max(0, currentZoomLevel - zoomSpeed);
      if (newZoomLevel == currentZoomLevel) {
        return;
      }

      parameters.setZoom(newZoomLevel);
      camera.setParameters(parameters);
    } catch(Exception e) {
      e.printStackTrace();
      return;
    }

    Logging.d(TAG, "Zoom Out done");
  }

  private boolean focusInternal(float x, float y, int w, int h) {
    Logging.d(TAG, "Focus internal");
    if (state == SessionState.STOPPED) {
      Logging.d(TAG, "Camera is already stopped");
      return false;
    }

    Rect focusRect = calculateTapArea(x, y, w, h);

    try {
      Camera.Parameters parameters = camera.getParameters();
      List<String> supportedFocusModes = parameters.getSupportedFocusModes();

      if (supportedFocusModes != null && supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO) && parameters.getFocusMode().equals(Camera.Parameters.FOCUS_MODE_AUTO)) {

        ArrayList<Camera.Area> focusAreaList = new ArrayList<>();
        focusAreaList.add(new Camera.Area(focusRect, 1000));

        if (parameters.getMaxNumFocusAreas() > 0) {
          parameters.setFocusAreas(focusAreaList);
        }

        if (parameters.getMaxNumMeteringAreas() > 0) {
          parameters.setMeteringAreas(focusAreaList);
        }

        camera.cancelAutoFocus();
        camera.setParameters(parameters);
        camera.autoFocus(new Camera.AutoFocusCallback() {
          @Override
          public void onAutoFocus(boolean b, Camera camera) {
            // currently set to auto-focus on single touch
          }
        });
      }

      Logging.d(TAG, "Focus done");
      return true;
    } catch(Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  private Rect calculateTapArea(float x, float y, int w, int h) {

    double xPos = (2000 * x / w) - 1000;
    double yPos = (2000 * y / h) - 1000;

    int areaSize = 250;

    int left = Math.clamp((int) (xPos - (areaSize/2)), -1000, 1000);
    int right = Math.clamp((int) (xPos + (areaSize/2)), -1000, 1000);
    int top = Math.clamp((int) (yPos - (areaSize/2)), -1000, 1000);
    int bottom = Math.clamp((int) (yPos + (areaSize/2)), -1000, 1000);

    return new Rect(left, top, right, bottom);
  }

  private void listenForTextureFrames() {
    surfaceTextureHelper.startListening((VideoFrame frame) -> {
      checkIsOnCameraThread();

      if (state != SessionState.RUNNING) {
        Logging.d(TAG, "Texture frame captured but camera is no longer running.");
        return;
      }

      if (!firstFrameReported) {
        final int startTimeMs =
            (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
        camera1StartTimeMsHistogram.addSample(startTimeMs);
        firstFrameReported = true;
      }

      //Note: I have removed this since the OS frame mirror and orientation seems fine
      // Undo the mirror that the OS "helps" us with.
      // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
//      final VideoFrame modifiedFrame = new VideoFrame(
//          CameraSession.createTextureBufferWithModifiedTransformMatrix(
//              (TextureBufferImpl) frame.getBuffer(),
//              /* mirror= */ info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT,
//              /* rotation= */ 0),
//          /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
//      events.onFrameCaptured(Camera1Session.this, modifiedFrame);
//      modifiedFrame.release();

      events.onFrameCaptured(Camera1Session.this, frame);
    });
  }

  private void listenForBytebufferFrames() {
    camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
      @Override
      public void onPreviewFrame(final byte[] data, Camera callbackCamera) {
        checkIsOnCameraThread();

        if (callbackCamera != camera) {
          Logging.e(TAG, "Callback from a different camera. This should never happen.");
          return;
        }

        if (state != SessionState.RUNNING) {
          Logging.d(TAG, "Bytebuffer frame captured but camera is no longer running.");
          return;
        }

        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

        if (!firstFrameReported) {
          final int startTimeMs =
              (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
          camera1StartTimeMsHistogram.addSample(startTimeMs);
          firstFrameReported = true;
        }

        VideoFrame.Buffer frameBuffer = new NV21Buffer(
            data, captureFormat.width, captureFormat.height, () -> cameraThreadHandler.post(() -> {
              if (state == SessionState.RUNNING) {
                camera.addCallbackBuffer(data);
              }
            }));
        final VideoFrame frame = new VideoFrame(frameBuffer, getFrameOrientation(), captureTimeNs);
        events.onFrameCaptured(Camera1Session.this, frame);
        frame.release();
      }
    });
  }

  private int getFrameOrientation() {
    int rotation = CameraSession.getDeviceOrientation(applicationContext);
    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
      rotation = 360 - rotation;
    }
    return (info.orientation + rotation) % 360;
  }

  private void checkIsOnCameraThread() {
    if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
      throw new IllegalStateException("Wrong thread");
    }
  }
}
