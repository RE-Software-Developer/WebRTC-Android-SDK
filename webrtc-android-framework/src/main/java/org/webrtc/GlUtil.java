/*
 *  Copyright 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLException;
import android.opengl.GLUtils;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Some OpenGL static utility functions.
 */
public class GlUtil {
  private GlUtil() {}

  public static class GlOutOfMemoryException extends GLException {
    public GlOutOfMemoryException(int error, String msg) {
      super(error, msg);
    }
  }

  // Assert that no OpenGL ES 2.0 error has been raised.
  public static void checkNoGLES2Error(String msg) {
    int error = GLES20.glGetError();
    if (error != GLES20.GL_NO_ERROR) {
      throw error == GLES20.GL_OUT_OF_MEMORY
          ? new GlOutOfMemoryException(error, msg)
          : new GLException(error, msg + ": GLES20 error: " + error);
    }
  }

  public static FloatBuffer createFloatBuffer(float[] coords) {
    // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
    ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
    bb.order(ByteOrder.nativeOrder());
    FloatBuffer fb = bb.asFloatBuffer();
    fb.put(coords);
    fb.position(0);
    return fb;
  }

  /**
   * Generate texture with standard parameters.
   */
  public static int generateTexture(int target) {
    final int textureArray[] = new int[1];
    GLES20.glGenTextures(1, textureArray, 0);
    final int textureId = textureArray[0];
    GLES20.glBindTexture(target, textureId);
    GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    checkNoGLES2Error("generateTexture");
    return textureId;
  }

  /**
   * Loads a bitmap texture to OpenGL, returning the OpenGL ID for that texture.
   * Returns 0 if the load failed.
   * The caller is responsible for recycling the bitmap
   *
   * @param bmp The bitmap
   * @return The OpenGL texture ID
   */
  public static int loadTexture(Bitmap bmp) {
    if (bmp == null || bmp.isRecycled()) {
      Log.e(GlUtil.class.getSimpleName(),"Bitmap is Null. Can't generate a new OpenGL texture object!");
      return 0;
    }

    final int[] textureObjectIds = new int[1];
    GLES20.glGenTextures(1, textureObjectIds, 0);

    if(textureObjectIds[0] == 0){
      Log.e(GlUtil.class.getSimpleName(),"Could not generate a new OpenGL texture object!");
      return 0;
    }

    // Tell OpenGL which texture object the texture call should be applied to
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureObjectIds[0]);

    //Use mipmap three-thread filtering when setting shrinking (GL_TEXTURE_MIN_FILTER)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
    //Use dual-thread filtering when setting zoom (GL_TEXTURE_MAG_FILTER)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);

//    bmp.recycle();

    //Quickly generate mipmap texture
    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);

    //Unbind the texture operation
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

    return textureObjectIds[0];
  }
}
