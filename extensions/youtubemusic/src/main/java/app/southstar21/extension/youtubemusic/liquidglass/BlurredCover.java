package app.southstar21.extension.youtubemusic.liquidglass;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;

/**
 * Turns a small recorded cover into a finished bitmap, once. The full player's glass and its page
 * all show the blurred cover; a RenderEffect node drawn from several places is rendered again at
 * each of them, a bitmap is just drawn.
 */
@SuppressLint("NewApi")
final class BlurredCover {
    private static final String TAG = "BlurredCover";

    private BlurredCover() {
    }

    /** Whether {@link #render} can work on this device (the public renderer arrived with Android 14). */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /**
     * Renders {@code blurred} (a node of {@code w} x {@code h} with its blur effect set) into a
     * bitmap of that size. The picture is tiny (an eighth of the screen on each side), so this
     * takes a moment on the GPU, not long enough to hold up the UI thread. Null if anything
     * fails, so the caller can draw the node itself.
     */
    static Bitmap render(RenderNode blurred, int w, int h) {
        if (!supported()) return null;
        ImageReader reader = null;
        HardwareRenderer renderer = null;
        RenderNode root = null;
        Image image = null;
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            root = new RenderNode("FullPlayerCoverRoot");
            root.setPosition(0, 0, w, h);
            RecordingCanvas rc = root.beginRecording(w, h);
            try {
                rc.drawRenderNode(blurred);
            } finally {
                root.endRecording();
            }
            renderer = new HardwareRenderer();
            renderer.setContentRoot(root);
            renderer.setSurface(reader.getSurface());
            HardwareRenderer.FrameRenderRequest request = renderer.createRenderRequest();
            request.setWaitForPresent(true);
            request.syncAndDraw();
            image = reader.acquireLatestImage();
            if (image == null) return null;
            HardwareBuffer buffer = image.getHardwareBuffer();
            if (buffer == null) return null;
            try {
                Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, null);
                if (wrapped == null) return null;
                // A copy of its own: the buffer goes back to the reader.
                Bitmap copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                wrapped.recycle();
                return copy;
            } finally {
                buffer.close();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Rendering the cover failed", e);
            return null;
        } finally {
            if (image != null) image.close();
            if (renderer != null) renderer.destroy();
            if (root != null) root.discardDisplayList();
            if (reader != null) reader.close();
        }
    }
}
