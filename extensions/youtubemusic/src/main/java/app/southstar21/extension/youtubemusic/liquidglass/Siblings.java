package app.southstar21.extension.youtubemusic.liquidglass;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Draws the views under the glass into a recording, without drawing video SurfaceViews.
 * <p>
 * A SurfaceView's pixels live in a layer of their own, so in a recording of the view tree it is
 * a transparent hole anyway. But drawing its RenderNode into our recordings as well makes the
 * renderer see it at several positions per frame and move the surface twice every frame, two
 * more SurfaceFlinger transactions each time. So a subtree holding a visible SurfaceView is
 * drawn by hand, group by group, leaving the SurfaceView out. Every other subtree is drawn
 * with {@link View#draw}, which reuses its display lists.
 */
final class Siblings {
    private static final String TAG = LiquidGlassNavBarPatch.TAG;
    private static final long SCAN_MS = 500;

    /** The SurfaceViews found by the last scan, and the views above them (and themselves). */
    private static final List<WeakReference<SurfaceView>> surfaces = new ArrayList<>();
    private static final Set<View> holders = Collections.newSetFromMap(new WeakHashMap<>());
    private static long lastScan;
    private static boolean failureLogged;

    private Siblings() {}

    /**
     * Draws {@code child} (a child of the view being recorded) at its position, as
     * {@code translate(left + translationX, top + translationY)} and {@link View#draw} would.
     * The canvas is left as it was.
     */
    static void draw(Canvas canvas, View child) {
        int save = canvas.save();
        canvas.translate(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY());
        try {
            if (child instanceof ViewGroup && holdsSurface(child)) {
                drawContents(canvas, (ViewGroup) child);
                canvas.restoreToCount(save);
                return;
            }
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                Log.w(TAG, "Drawing around a video surface failed, drawing it whole", e);
            }
            canvas.restoreToCount(save);
            save = canvas.save();
            canvas.translate(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY());
        }
        child.draw(canvas);
        canvas.restoreToCount(save);
    }

    /** What {@code group} draws itself, as {@link View#draw} does, but with its SurfaceViews left out. */
    private static void drawContents(Canvas canvas, ViewGroup group) {
        Drawable background = group.getBackground();
        if (background != null) background.draw(canvas);

        int save = canvas.save();
        int sx = group.getScrollX();
        int sy = group.getScrollY();
        canvas.translate(-sx, -sy);
        if (group.getClipChildren()) {
            boolean pad = group.getClipToPadding();
            canvas.clipRect(sx + (pad ? group.getPaddingLeft() : 0), sy + (pad ? group.getPaddingTop() : 0),
                    sx + group.getWidth() - (pad ? group.getPaddingRight() : 0),
                    sy + group.getHeight() - (pad ? group.getPaddingBottom() : 0));
        }
        // In index order: the drawing order hook is protected.
        for (int i = 0; i < group.getChildCount(); i++) {
            drawChild(canvas, group.getChildAt(i));
        }
        canvas.restoreToCount(save);

        Drawable foreground = group.getForeground();
        if (foreground != null) foreground.draw(canvas);
    }

    /** One child of a group being drawn by hand, in the group's content coordinates. */
    private static void drawChild(Canvas canvas, View child) {
        if (child.getVisibility() != View.VISIBLE || child instanceof SurfaceView) return;
        float alpha = child.getAlpha();
        if (alpha == 0f) return;
        int save = canvas.save();
        // The matrix holds the pivot, scale, rotation and translation already.
        canvas.translate(child.getLeft(), child.getTop());
        Matrix matrix = child.getMatrix();
        if (!matrix.isIdentity()) canvas.concat(matrix);
        Rect clipBounds = child.getClipBounds();
        if (clipBounds != null) canvas.clipRect(clipBounds);
        if (alpha < 1f) {
            canvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), Math.round(alpha * 255));
        }
        if (child instanceof ViewGroup && holdsSurface(child)) {
            drawContents(canvas, (ViewGroup) child);
        } else {
            child.draw(canvas);
        }
        canvas.restoreToCount(save);
    }

    /** Whether a visible SurfaceView is somewhere in {@code view}'s subtree, from a scan at most {@link #SCAN_MS} old. */
    private static boolean holdsSurface(View view) {
        long now = SystemClock.uptimeMillis();
        boolean stale = now - lastScan > SCAN_MS;
        if (!stale) {
            for (WeakReference<SurfaceView> ref : surfaces) {
                SurfaceView surface = ref.get();
                if (surface == null || !surface.isAttachedToWindow()) {
                    stale = true;
                    break;
                }
            }
        }
        if (stale) {
            lastScan = now;
            surfaces.clear();
            holders.clear();
            collect(view.getRootView(), new ArrayList<>());
        }
        return holders.contains(view);
    }

    /** Fills the cache from {@code view} down; returns whether a SurfaceView is in it. */
    private static boolean collect(View view, List<View> path) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof SurfaceView) {
            surfaces.add(new WeakReference<>((SurfaceView) view));
            holders.add(view);
            holders.addAll(path);
            return true;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        path.add(view);
        boolean found = false;
        for (int i = 0; i < group.getChildCount(); i++) {
            found |= collect(group.getChildAt(i), path);
        }
        path.remove(path.size() - 1);
        return found;
    }
}
