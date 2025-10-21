package org.webrtc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Wrapper for Bitmap to store the position, and whether it should be drawn or not
 * Caches bitmaps in the filesystem, and only reads them into memory when it is required to update the overall overlay bitmap
 */
public class OverlayBitmap {
    private final File tempFileDir;
    private final int x;
    private final int y;
    private final int z_index;
    private String name;
    private boolean shouldDraw;

    public OverlayBitmap(String name, int x, int y, int z_index, Bitmap bitmap, File tempFileDir) throws IOException {
        this.x = x;
        this.y = y;
        this.z_index = z_index;
        this.shouldDraw = false;
        this.tempFileDir = tempFileDir;

        //Create a bitmap with name id
        this.setBitmap(name, bitmap);
    }

    public void setActive(boolean active) {
        this.shouldDraw = active;
    }

    /**
     * Saves a bitmap to the filesystem, and saves it's path
     * @param name
     * @param bitmap
     * @return whether it successfully saved the bitmap to the filesystem
     */
    public void setBitmap(String name, Bitmap bitmap) throws IOException {
        this.name = name;

        if (!tempFileDir.exists()) {
            throw new FileNotFoundException("Temporary File Directory Inaccessible");
        }

        OutputStream outStream;
        File file = new File(tempFileDir, name+".png");
        try {
            outStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, outStream);
            outStream.close();
        } catch (FileNotFoundException e) {
            throw new IOException("Could not create new file");
        } catch (IOException e) {
            throw new IOException("Failed to close File Output Stream");
        }
    }

    public Bitmap getBitmap() throws IOException {
        String path = "";
        if (this.name != null) {
            path = tempFileDir.getPath()+"/"+name+".png";
            Bitmap bitmap = BitmapFactory.decodeFile(path);

            if (bitmap != null) {
                return bitmap;
            }
        }

        throw new IOException("Could not get bitmap " + path);
    }

    public void drawToCanvas(Canvas canvas) throws IOException {
        if (this.shouldDraw) {
            Bitmap bitmap = this.getBitmap();
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, this.x, this.y, null);
                bitmap.recycle();
            }
        }
    }

    public Integer getZIndex() {
        return this.z_index;
    }
}