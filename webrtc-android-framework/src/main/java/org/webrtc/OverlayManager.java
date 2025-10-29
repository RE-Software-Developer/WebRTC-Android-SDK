package org.webrtc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class OverlayManager {

    public static boolean isReady = false;  //true once initialised
    public static boolean shouldDraw = true; //Override to stop all drawing. Has been made static so it can be called from other threads (e.g. currently used in camera thread)

    private static final HashMap<String, OverlayBitmap> overlayBitmaps = new HashMap<>(); // stores <"name", "bitmap">
    private static File tempFileDirectory;
    private static Bitmap currentBitmap;   //adding a bitmap will draw those bitmaps onto this one
    private static Canvas canvas;
    private static int textureId;
    private static boolean horizontalMirror;

    private static Handler renderThreadHandler;

    public static void setRenderThreadHandler(Handler handler) {
        OverlayManager.renderThreadHandler = handler;
    }

    public static void setHorizontalMirror(boolean value) {
        horizontalMirror = value;
    }

    public static void init(File tempFileDirectory, int viewportWidth, int viewportHeight) {
        OverlayManager.tempFileDirectory = tempFileDirectory;
        OverlayManager.currentBitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_4444);
        OverlayManager.canvas = new Canvas(currentBitmap);
        OverlayManager.createTexture();
        OverlayManager.isReady = true;
        OverlayManager.horizontalMirror = false;
    }

    /**
     * Prepares to add an overlay image to the stream and preview
     * Stores the values in memory, so they can be used when createTextures() is called
     * @param bitmap The bitmap image to overlay
     * @param x The x coordinate to place the upper left corner of the image
     * @param y The y coordinate to palce the upper left corner of the image
     */
    public static OverlayBitmap addOverlayBitmap(String id, Bitmap bitmap, int x, int y, int z_index) throws IOException {
        if (OverlayManager.tempFileDirectory == null) {
            throw new IOException("Could not get the temporary file directory");
        }

        OverlayBitmap overlayBitmap = new OverlayBitmap(id, x, y, z_index, bitmap, OverlayManager.tempFileDirectory);

        overlayBitmaps.put(id, overlayBitmap);
        if (OverlayManager.currentBitmap != null) {
            createTexture();
        }
        return overlayBitmap;
    }

    public static void setOverlayBitmapActive(String id, boolean active) {
        OverlayBitmap overlayBitmap = overlayBitmaps.get(id);
        if (overlayBitmap == null) {
            return;
        }

        overlayBitmap.setActive(active);
        if (OverlayManager.currentBitmap != null) {
            createTexture();
        }
    }

    /**
     * Loads the texture from resources into GLES and creates an CustomTextureRect object
     * Note: Must be called after GLES has been fully initialised
     */
    public static void createTexture() {
        Log.d("OverlayManager", "CreateTexture");

        //Create the overlay bitmap
        if (OverlayManager.canvas != null) {
            OverlayManager.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }

        List<OverlayBitmap> bitmapList = new ArrayList<>(OverlayManager.overlayBitmaps.values());
        Collections.sort(bitmapList, new Comparator<OverlayBitmap>() {
            @Override
            public int compare(OverlayBitmap a, OverlayBitmap b) {
                return a.getZIndex().compareTo(b.getZIndex());
            }
        });

        for (OverlayBitmap overlayBitmap : bitmapList) {
            //Attempt to create the overlay bitmap. This cannot fail, so if a bitmap can't be obtained, it is simply ignored
            try {
                overlayBitmap.drawToCanvas(OverlayManager.canvas);
            } catch (IOException e) {
                Log.e("OverlayManager", "Could not draw bitmap");
                e.printStackTrace();
            }
        }

        OverlayManager.renderThreadHandler.post(() -> {
            int oldTextureId = textureId;
            textureId = GlUtil.loadTexture(currentBitmap);
            if (oldTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[] { oldTextureId }, 0);
            }
        });
    }

    public static void release() {
        Log.d("OverlayManager", "Release");
        renderThreadHandler = null;
        isReady = false;
        if (currentBitmap != null) {
            currentBitmap.recycle();
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { textureId }, 0);
        }
    }

    /**
     * Prepares a buffer which is used to draw all overlay textures onto a SurfaceTexture
     * @param transformMatrix transformation matrix of the SurfaceTexture to draw on
     * @return a TextureBuffer
     */
    public static VideoFrame.TextureBuffer getBuffer(Matrix transformMatrix) {
        if (textureId == 0 || currentBitmap == null || currentBitmap.isRecycled()) {
            return null;
        }

        return new TextureBufferImpl(currentBitmap.getWidth(), currentBitmap.getHeight(), VideoFrame.TextureBuffer.Type.RGB, textureId, transformMatrix, null, null, () -> {});
    }

    /**
     * Returns a flipped matrix if we have set a horizontal mirror for the textures
     * @return
     */
    public static Matrix getDrawMatrix() {
        if (!horizontalMirror) {
            return new Matrix();
        } else {
            Matrix matrix = new Matrix();
            matrix.preScale(-1, 1);
            return matrix;
        }
    }
}
