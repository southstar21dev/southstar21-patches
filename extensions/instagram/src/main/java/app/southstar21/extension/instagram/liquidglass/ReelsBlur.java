package app.southstar21.extension.instagram.liquidglass;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * iOS 26's Reels look: each reel runs down behind the floating bar, and the strip of it under
 * the caption, behind the bar, is a frosted copy of its own video. The strip belongs to the
 * reel, not the screen, so it slides away with the reel while scrolling and the next reel comes
 * in sharp.
 * <p>
 * Each page's video layout gets a {@link Strip} in its overlay: drawn over the video but under
 * all of Instagram's UI (caption, buttons, seek bar, legibility gradient), so it can't cover any
 * of it, and moving with the page. It re-records the video each frame; Instagram's gradient then
 * darkens strip and page alike, so the two meet without a seam. The caption, the button column
 * and the seek bar are lifted together, just as far as it takes for them to end above the strip.
 * <p>
 * Needs the TextureView player (Blur videos): a SurfaceView is a layer of its own that can't be
 * recorded.
 */
@SuppressLint("NewApi")
final class ReelsBlur {
    private static final String PKG = "com.instagram.android";
    private static final long RESCAN_MS = 500;
    /** Over this much at its top the strip eases from sharp to fully frosted. */
    private static final float FEATHER_DP = 56f;
    /** Light to strong, each fading in a little further down the feather (see EdgeBlurView). */
    private static final float[] RADII_DP = {3f, 9f, 22f};
    private static final float[][] RAMPS = {{0f, 0.45f}, {0.2f, 0.75f}, {0.45f, 1f}};
    /** Recorded above the strip so the top of the blur averages real video, not a clamped row. */
    private static final float PAD_DP = 40f;

    private final View root;
    private final float density;
    private final int pagerId, videoId, containerId, infoId, scrubberId;
    /** Portrait videos up to this much shorter than the page are zoomed to fill it (wider ones keep their bars). */
    private static final float MAX_FILL_SCALE = 1.25f;
    /** Videos at least this tall for their width (9:16 is 1.78) count as portrait. */
    private static final float PORTRAIT_RATIO = 1.6f;

    private WeakReference<ViewGroup> pager;
    private long lastScan;
    private final WeakHashMap<View, Strip> strips = new WeakHashMap<>();
    /** Views scrolled to lift what they hold above the strip, put back when the effect stops. */
    private final WeakHashMap<View, Boolean> lifted = new WeakHashMap<>();
    /** Seek bar hosts moved up above the strip, put back when the effect stops. */
    private final WeakHashMap<View, Boolean> shifted = new WeakHashMap<>();
    /** Video containers zoomed to fill their page, put back when the effect stops. */
    private final WeakHashMap<View, Boolean> filled = new WeakHashMap<>();
    private final int[] location = new int[2];
    private final int[] other = new int[2];
    private final Rect visible = new Rect();
    private boolean active;
    private boolean wasOn;
    /** Until then (uptime ms) the pager is looked for every frame: Reels just came up. */
    private long searchUntil;
    private static final long EAGER_SEARCH_MS = 1500;

    ReelsBlur(View root, float density) {
        this.root = root;
        this.density = density;
        android.content.res.Resources res = root.getResources();
        pagerId = res.getIdentifier("clips_viewer_view_pager", "id", PKG);
        videoId = res.getIdentifier("clips_viewer_video_layout", "id", PKG);
        containerId = res.getIdentifier("clips_video_container", "id", PKG);
        infoId = res.getIdentifier("clips_media_info_component", "id", PKG);
        scrubberId = res.getIdentifier("scrubber", "id", PKG);
    }

    /** Whether the ids it needs exist in this Instagram build. */
    boolean usable() {
        return pagerId != 0 && videoId != 0 && infoId != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Once a frame.
     *
     * @param on        Reels is the page showing, with the bar over it
     * @param stripTop  where the strip starts, in window coordinates (just above the pill)
     */
    void onPreDraw(boolean on, int stripTop) {
        if (on && !wasOn) searchUntil = SystemClock.uptimeMillis() + EAGER_SEARCH_MS;
        wasOn = on;
        ViewGroup pager = on ? pager() : null;
        if (pager == null || pager.getChildCount() == 0 || !(pager.getChildAt(0) instanceof ViewGroup)) {
            stop();
            return;
        }
        active = true;
        pager.getLocationInWindow(location);
        int height = location[1] + pager.getHeight() - stripTop;
        if (height <= 0) {
            stop();
            return;
        }
        // The pages are the RecyclerView's children (ViewPager2's only child).
        ViewGroup list = (ViewGroup) pager.getChildAt(0);
        for (int i = 0; i < list.getChildCount(); i++) {
            View page = list.getChildAt(i);
            if (!(page instanceof ViewGroup)) continue;
            View video = page.findViewById(videoId);
            if (video != null) {
                Strip strip = strips.get(video);
                if (strip == null) {
                    strip = new Strip();
                    strips.put(video, strip);
                    video.getOverlay().add(strip);
                }
                fill(video);
                strip.update(page, video, height);
            }
            lift(page, page.getHeight() - height);
        }
    }

    /**
     * Instagram fits a 9:16 reel into the page, which now runs on behind the bar, so it got
     * black bars above and below. Zoom it to fill the page instead, cropping its sides, as
     * Instagram already did (a little) when the page stopped above the bar, and as iOS does.
     * <p>
     * Every layer of the video layout is zoomed, not just the player: Instagram moves its one
     * player to the incoming reel mid-scroll and shows a thumbnail in the outgoing one, which
     * would otherwise bring its black bars back (into the strip, too).
     */
    private void fill(View video) {
        if (!(video instanceof ViewGroup)) return;
        ViewGroup layout = (ViewGroup) video;
        int pageH = layout.getHeight();
        for (int i = 0; i < layout.getChildCount(); i++) {
            View layer = layout.getChildAt(i);
            int w = layer.getWidth();
            int h = layer.getHeight();
            float scale = 1f;
            if (layer.getVisibility() != View.GONE && w > 0 && h > 0 && pageH > h
                    && h / (float) w >= PORTRAIT_RATIO) {
                float want = pageH / (float) h;
                if (want <= MAX_FILL_SCALE) scale = want;
            }
            if (layer.getScaleX() != scale || layer.getScaleY() != scale) {
                layer.setPivotX(w / 2f);
                // Instagram centers the layers in the page, so this fills it top to bottom.
                layer.setPivotY(h / 2f);
                layer.setScaleX(scale);
                layer.setScaleY(scale);
            }
            if (scale != 1f) filled.put(layer, Boolean.TRUE);
        }
    }

    /**
     * Lifts the caption block (the host of the caption, the button column and any row Instagram
     * adds under them) and the seek bar together, by just enough for the lowest of them to end at
     * {@code stripTop} (page coordinates). Instagram lays some reels out with room to spare (a
     * "Not interested" row, no seek bar), so a fixed lift would push those far too high.
     * <p>
     * The hosts are scrolled, not translated: Litho animates rows (expanding the description)
     * by their position relative to the LithoView, which counts the ancestors' translation but
     * not their scroll. A translated host left the animated rows pushed back down by the lift,
     * out of the host's bounds and out of sight.
     */
    private void lift(View page, int stripTop) {
        page.getLocationInWindow(other);
        int lowest = Integer.MIN_VALUE;

        View info = infoId != 0 ? page.findViewById(infoId) : null;
        View infoHost = info != null && info.getParent() instanceof View && info.getParent() != page
                ? (View) info.getParent() : null;
        if (infoHost != null) {
            int bottom = contentBottom(infoHost);
            if (bottom != Integer.MIN_VALUE) {
                // A view's own scroll doesn't move it, only what it holds.
                infoHost.getLocationInWindow(location);
                lowest = location[1] - other[1] + bottom;
            }
        }

        View scrubber = scrubberId != 0 ? page.findViewById(scrubberId) : null;
        View scrubberHost = scrubber != null ? pageLevel(scrubber, page) : null;
        if (scrubberHost != null && scrubber.isShown() && scrubber.getHeight() > 0) {
            scrubber.getLocationInWindow(location);
            int bottom = location[1] - other[1] + scrubber.getHeight() - Math.round(scrubberHost.getTranslationY());
            lowest = Math.max(lowest, bottom);
        }

        int by = lowest == Integer.MIN_VALUE ? 0 : Math.max(0, lowest - stripTop);
        if (infoHost != null) {
            if (infoHost.getScrollY() != by) infoHost.setScrollY(by);
            lifted.put(infoHost, Boolean.TRUE);
        }
        // The seek bar sits in frames barely taller than itself (scrolling them would clip it),
        // so its page-sized host is moved instead. Nothing in it is animated by Litho.
        if (scrubberHost != null) {
            if (scrubberHost.getTranslationY() != -by) scrubberHost.setTranslationY(-by);
            shifted.put(scrubberHost, Boolean.TRUE);
        }
    }

    /**
     * The ancestor of {@code view} sitting in a page-sized container (or the page), which can be
     * moved up without being clipped. Null if there is none.
     */
    private static View pageLevel(View view, View page) {
        View v = view;
        while (v.getParent() instanceof View && v.getParent() != page) {
            View parent = (View) v.getParent();
            if (parent.getHeight() >= page.getHeight()) return v;
            v = parent;
        }
        return v.getParent() == page ? v : null;
    }

    /**
     * The bottom of what {@code host} shows, in its own coordinates: its lowest visible child
     * with a size (Litho keeps empty spacers at the bottom).
     */
    private static int contentBottom(View host) {
        if (!(host instanceof ViewGroup)) return host.getHeight();
        ViewGroup g = (ViewGroup) host;
        int bottom = Integer.MIN_VALUE;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getHeight() == 0 || c.getWidth() == 0) continue;
            bottom = Math.max(bottom, c.getBottom());
        }
        return bottom;
    }

    private void stop() {
        if (!active) return;
        active = false;
        for (View v : lifted.keySet()) {
            if (v != null) v.setScrollY(0);
        }
        lifted.clear();
        for (View v : shifted.keySet()) {
            if (v != null) v.setTranslationY(0f);
        }
        shifted.clear();
        for (View v : filled.keySet()) {
            if (v != null) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        }
        filled.clear();
        for (Strip strip : strips.values()) {
            if (strip != null) strip.hide();
        }
    }

    /** The Reels tab's pager, found again when Instagram replaces it. */
    private ViewGroup pager() {
        ViewGroup cached = pager != null ? pager.get() : null;
        if (cached != null && cached.isShown()) return cached;
        long now = SystemClock.uptimeMillis();
        // Right after Reels comes up the pager may not be laid out yet: look again next frame,
        // or the reel shows letterboxed for a moment before it fills the page.
        if (now >= searchUntil && now - lastScan < RESCAN_MS) return null;
        lastScan = now;
        List<View> found = new ArrayList<>();
        collect(root, found);
        for (View v : found) {
            if (v instanceof ViewGroup && v.isShown() && v.getHeight() > 0) {
                pager = new WeakReference<>((ViewGroup) v);
                return (ViewGroup) v;
            }
        }
        pager = null;
        return null;
    }

    private void collect(View view, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.getId() == pagerId) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** A page's frosted bottom strip, drawn in its video layout's overlay. */
    private final class Strip extends Drawable {
        private final RenderNode backdrop = new RenderNode("ReelsBlurBackdrop");
        private final RenderNode[] levels = new RenderNode[RADII_DP.length];
        private final Paint[] masks = new Paint[RADII_DP.length];
        private final Paint tint = new Paint();
        private WeakReference<View> video;
        private int maskFor = -1;
        private final int pad = Math.round(PAD_DP * density);

        Strip() {
            for (int i = 0; i < levels.length; i++) {
                levels[i] = new RenderNode("ReelsBlurLevel" + i);
                float r = RADII_DP[i] * density;
                levels[i].setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                masks[i] = new Paint();
                masks[i].setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            }
        }

        void update(View page, View video, int height) {
            this.video = new WeakReference<>(video);
            // The page's bottom `height`, in the video layout's coordinates.
            video.getLocationInWindow(location);
            page.getLocationInWindow(other);
            int bottom = other[1] + page.getHeight() - location[1];
            int top = bottom - height;
            Rect b = getBounds();
            if (b.left != 0 || b.top != top || b.right != video.getWidth() || b.bottom != bottom) {
                setBounds(0, top, video.getWidth(), bottom);
            }
            // The video changes every frame without the page knowing: record it again.
            if (video != null && page.getGlobalVisibleRect(visible)) invalidateSelf();
        }

        void hide() {
            video = null;
            setBounds(0, 0, 0, 0);
        }

        @Override
        public void draw(Canvas canvas) {
            View video = this.video != null ? this.video.get() : null;
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (video == null || w <= 0 || h <= 0 || !canvas.isHardwareAccelerated()) return;
            updateMasks(h);

            // The video layout, in the strip's coordinates, from `pad` above the strip down.
            int rh = h + pad;
            backdrop.setPosition(0, 0, w, rh);
            RecordingCanvas rc = backdrop.beginRecording(w, rh);
            try {
                rc.drawColor(Color.BLACK);
                rc.translate(0, -(b.top - pad));
                drawLayers(rc, video);
            } finally {
                backdrop.endRecording();
            }

            int save = canvas.save();
            canvas.translate(b.left, b.top);
            canvas.clipRect(0, 0, w, h);
            for (int i = 0; i < levels.length; i++) {
                RenderNode level = levels[i];
                level.setPosition(0, 0, w, rh);
                RecordingCanvas lc = level.beginRecording(w, rh);
                try {
                    lc.drawRenderNode(backdrop);
                } finally {
                    level.endRecording();
                }
                int layer = canvas.saveLayer(0, 0, w, h, null);
                canvas.translate(0, -pad);
                canvas.drawRenderNode(level);
                canvas.translate(0, pad);
                canvas.drawRect(0, 0, w, h, masks[i]);
                canvas.restoreToCount(layer);
            }
            canvas.drawRect(0, 0, w, h, tint);
            canvas.restoreToCount(save);
        }

        /**
         * The video layout's layers (player, thumbnails), each placed and zoomed by hand: drawn
         * through the layout itself, the outgoing reel's zoom went missing mid-scroll and the
         * strip blurred the black bar below the unzoomed video.
         */
        private void drawLayers(Canvas rc, View video) {
            if (!(video instanceof ViewGroup)) {
                video.draw(rc);
                return;
            }
            ViewGroup layout = (ViewGroup) video;
            for (int i = 0; i < layout.getChildCount(); i++) {
                View layer = layout.getChildAt(i);
                if (layer.getVisibility() != View.VISIBLE || layer.getAlpha() == 0f) continue;
                int save = rc.save();
                rc.translate(layer.getLeft() + layer.getTranslationX(), layer.getTop() + layer.getTranslationY());
                rc.scale(layer.getScaleX(), layer.getScaleY(), layer.getPivotX(), layer.getPivotY());
                layer.draw(rc);
                rc.restoreToCount(save);
            }
        }

        private void updateMasks(int h) {
            if (h == maskFor) return;
            maskFor = h;
            float feather = Math.min(h * 0.6f, FEATHER_DP * density) / h;
            for (int i = 0; i < masks.length; i++) {
                masks[i].setShader(eased(h, RAMPS[i][0] * feather, RAMPS[i][1] * feather, 0, 0xFF));
            }
            // iOS greys the strip a little, more towards the bottom edge.
            tint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0, 0x18000000, 0x38000000},
                    new float[]{0f, feather, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final int EASE_STOPS = 13;

    /** Black at {@code a0} above {@code start} (fractions of {@code h}), easing to {@code a1} at {@code end}. */
    private static LinearGradient eased(int h, float start, float end, int a0, int a1) {
        int[] colors = new int[EASE_STOPS + 2];
        float[] stops = new float[EASE_STOPS + 2];
        colors[0] = a0 << 24;
        stops[0] = 0f;
        for (int k = 0; k < EASE_STOPS; k++) {
            float t = k / (float) (EASE_STOPS - 1);
            stops[k + 1] = Math.max(stops[k], start + (end - start) * t);
            float s = t * t * t * (t * (t * 6f - 15f) + 10f);
            colors[k + 1] = Math.round(a0 + (a1 - a0) * s) << 24;
        }
        stops[EASE_STOPS + 1] = 1f;
        colors[EASE_STOPS + 1] = a1 << 24;
        return new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP);
    }
}
