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
import android.view.ViewParent;

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
    /** How soft the band behind the status bar goes while it holds still through a swipe. */
    private static final float BAND_BLUR_DP = 14f;
    /** The last part of a swipe (of a page's height) over which the band comes into focus on the arriving reel. */
    private static final float BAND_SETTLE = 0.3f;
    /** How often the band's picture is copied while a reel rests (for the next swipe to hold). */
    private static final long BAND_SNAPSHOT_MS = 250;
    /** Portrait videos needing up to this much zoom to fill the screen are zoomed (wider ones keep their bars). */
    private static final float MAX_FILL_SCALE = 1.4f;
    /** As above, when they only fill the page below the status bar (Full screen off). */
    private static final float MAX_PAGE_FILL_SCALE = 1.25f;
    /** Videos at least this tall for their width (9:16 is 1.78) count as portrait. */
    private static final float PORTRAIT_RATIO = 1.6f;

    private WeakReference<ViewGroup> pager;
    private long lastScan;
    private final WeakHashMap<View, Strip> strips = new WeakHashMap<>();
    /** The video behind the status bar, and the container whose overlay holds it. */
    private TopBand topBand;
    private WeakReference<View> topHost;
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
        boolean fullScreen = LiquidGlassNavBarPatch.REELS_FULL_SCREEN.get();
        pager.getLocationInWindow(location);
        int height = location[1] + pager.getHeight() - stripTop;
        if (height <= 0) {
            stop();
            return;
        }
        int pagerLeft = location[0];
        int pagerTop = location[1];
        // Where the pages rest. Pulling down on the first reel (to refresh) slides the whole pager
        // down under Instagram's spinner; measured from there, the zoom grew for a taller screen
        // and the status bar's band reached down into the gap. The pager's container stays put.
        if (pager.getParent() instanceof View) {
            ((View) pager.getParent()).getLocationInWindow(other);
            pagerTop = Math.min(pagerTop, other[1]);
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
                fill(video, page, pagerTop, fullScreen);
                strip.update(page, video, height);
            }
            lift(page, page.getHeight() - height);
        }
        // An open sheet (the comments, say) shows the reel in a small player of its own above it,
        // over the page; the band went on showing the full size reel underneath behind the clock.
        if (fullScreen && !sheetOpen()) {
            updateTopBand(pager, list, pagerLeft, pagerTop);
        } else if (topBand != null) {
            topBand.hide();
        }
    }

    /** Puts the band over the strip of screen above the pages (see {@link TopBand}). */
    private void updateTopBand(ViewGroup pager, ViewGroup list, int pagerLeft, int pagerTop) {
        View host = topHost != null ? topHost.get() : null;
        if (host == null || !host.isAttachedToWindow()) {
            host = null;
            for (ViewParent p = pager.getParent(); p instanceof View; p = p.getParent()) {
                View v = (View) p;
                v.getLocationInWindow(other);
                if (other[1] <= 0) {
                    host = v;
                    break;
                }
            }
            if (host == null) return;
            if (topBand == null) topBand = new TopBand();
            View old = topHost != null ? topHost.get() : null;
            if (old != null) old.getOverlay().remove(topBand);
            host.getOverlay().add(topBand);
            topHost = new WeakReference<>(host);
        }
        if (pagerTop <= 0) {
            topBand.hide();
            return;
        }
        topBand.update(host, list, pagerLeft, pagerTop, pager.getWidth());
    }

    /**
     * Full screen, as on iOS: Instagram fits a 9:16 reel into the page, which now runs on behind
     * the bar, so it got black bars above and below. Zoom it to fill the whole screen instead, from
     * behind the status bar down to the bottom edge, cropping its sides, as iOS does. The page
     * starts below the status bar and clips there; {@link TopBand} shows the part above it.
     * <p>
     * Every layer of the video layout is zoomed, not just the player: Instagram moves its one
     * player to the incoming reel mid-scroll and shows a thumbnail in the outgoing one, which
     * would otherwise bring its black bars back (into the strip, too).
     *
     * Full screen off, it fills the page only, below the status bar, zoomed from its middle.
     *
     * @param pagerTop where the pages rest, in window coordinates (the status bar's bottom)
     */
    private void fill(View video, View page, int pagerTop, boolean fullScreen) {
        if (!(video instanceof ViewGroup)) return;
        ViewGroup layout = (ViewGroup) video;
        int pageH = page.getHeight();
        // Where the video layout sits in the page (the same however far the page has scrolled).
        video.getLocationInWindow(location);
        page.getLocationInWindow(other);
        int layoutTop = location[1] - other[1];
        int screenH = pageH + (fullScreen ? Math.max(0, pagerTop) : 0);
        float maxScale = fullScreen ? MAX_FILL_SCALE : MAX_PAGE_FILL_SCALE;
        for (int i = 0; i < layout.getChildCount(); i++) {
            View layer = layout.getChildAt(i);
            int w = layer.getWidth();
            int h = layer.getHeight();
            float scale = 1f;
            float ty = 0f;
            if (layer.getVisibility() != View.GONE && w > 0 && h > 0 && h / (float) w >= PORTRAIT_RATIO) {
                float want = screenH / (float) h;
                if (want >= 1f && want <= maxScale) {
                    scale = want;
                    // Zoomed from its top, which then goes to the top of the screen (or the page).
                    ty = -(fullScreen ? pagerTop : 0) - (layoutTop + layer.getTop());
                }
            }
            if (layer.getScaleX() != scale || layer.getScaleY() != scale || layer.getPivotY() != 0f) {
                layer.setPivotX(w / 2f);
                layer.setPivotY(0f);
                layer.setScaleX(scale);
                layer.setScaleY(scale);
            }
            if (layer.getTranslationY() != ty) layer.setTranslationY(ty);
            if (scale != 1f || ty != 0f) filled.put(layer, Boolean.TRUE);
        }
    }

    private WeakReference<View> sheetPanel;

    /** Whether one of Instagram's bottom sheets is open (its panel holds a sheet). */
    private boolean sheetOpen() {
        View panel = sheetPanel != null ? sheetPanel.get() : null;
        if (panel == null || !panel.isAttachedToWindow()) {
            int id = root.getResources().getIdentifier("layout_container_bottom_sheet", "id", PKG);
            panel = id != 0 ? root.findViewById(id) : null;
            sheetPanel = panel != null ? new WeakReference<>(panel) : null;
        }
        return panel instanceof ViewGroup && panel.isShown() && ((ViewGroup) panel).getChildCount() > 0;
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
            // From the layout, not from where it shows. Coming back to Reels, Litho moves the seek
            // bar's frame back to the page bottom (see undoLithoOffset), where the host's lift no
            // longer moves it. Measured on screen, each frame's lift then read as the bar still
            // being that much too low, and the lift grew by as much every frame, scrolling the
            // caption and the buttons thousands of pixels out of sight.
            lowest = Math.max(lowest, layoutBottom(scrubber, page));
        }

        // Nothing can end below the page, so a lift past this would only push things away.
        int max = Math.max(0, page.getHeight() - stripTop);
        int by = lowest == Integer.MIN_VALUE ? 0 : Math.min(max, Math.max(0, lowest - stripTop));
        if (infoHost != null) {
            if (infoHost.getScrollY() != by) infoHost.setScrollY(by);
            lifted.put(infoHost, Boolean.TRUE);
        }
        // The seek bar sits in frames barely taller than itself (scrolling them would clip it,
        // and touches would miss it), so its page-sized host is moved instead.
        if (scrubberHost != null) {
            if (scrubberHost.getTranslationY() != -by) scrubberHost.setTranslationY(-by);
            shifted.put(scrubberHost, Boolean.TRUE);
            undoLithoOffset(scrubber, scrubberHost, by);
        }
    }

    /**
     * Instagram briefly lays the page out shorter on every tab switch, so coming back to Reels
     * Litho animates the seek bar's frame to its new place. Litho places animated views by their
     * ancestors' getY(), which counts the host's lift, so the frame ends its move translated down
     * by exactly the lift: the seek bar sits back at the page bottom, behind the strip and the
     * bar. Undo that leftover. A frame Litho is still animating only matches it at the very end.
     */
    private static void undoLithoOffset(View scrubber, View host, int by) {
        if (by == 0) return;
        for (View v = scrubber; v != host && v.getParent() instanceof View; v = (View) v.getParent()) {
            if (v.getTranslationY() != 0f && Math.abs(v.getTranslationY() - by) <= 2f) v.setTranslationY(0f);
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
     * Where {@code view} ends in {@code page}'s coordinates going by layout alone, leaving out
     * every translation and scroll on the way (the lift's own included).
     */
    private static int layoutBottom(View view, View page) {
        int y = view.getHeight();
        for (View v = view; v != page && v.getParent() instanceof View; v = (View) v.getParent()) {
            y += v.getTop();
        }
        return y;
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
                v.setTranslationY(0f);
            }
        }
        filled.clear();
        for (Strip strip : strips.values()) {
            if (strip != null) strip.hide();
        }
        if (topBand != null) topBand.hide();
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
                drawVideoLayers(rc, video);
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

    /**
     * The video behind the status bar. The pages start below it and clip there (Instagram hides
     * its header by sliding it out of the page, so they must keep clipping), so the zoomed video's
     * top is drawn again here, sharp, in the overlay of the first container that reaches the top
     * of the screen: the video of the page right under it, where it is on screen. Darkened a little, as Instagram's
     * gradient under its header darkens the page below it.
     */
    private final class TopBand extends Drawable {
        private final Paint tint = new Paint();
        private final int[] at = new int[2];
        private WeakReference<ViewGroup> list;
        private int tintFor = -1;
        /** The band's left edge and its bottom (the pages' top), in window coordinates. */
        private int originX, seam;
        /** How far a zoomed reel's video reaches above its page (the status bar's height). */
        private int ext;
        private WeakReference<View> host;
        private final RenderNode live = new RenderNode("ReelsTopBand");
        private final RenderNode held = new RenderNode("ReelsTopBandHeld");
        /**
         * What the band showed at rest, copied every {@link #BAND_SNAPSHOT_MS}: a swipe holds it
         * (blurred) instead of showing the reels sliding past, and it dissolves into the reel
         * that settles. Two bitmaps, so a copy in flight never tears the one shown.
         */
        private android.graphics.Bitmap snapshot, spare;
        private boolean copying;
        private long copiedAt;
        private final Rect copyRect = new Rect();
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        void update(View host, ViewGroup list, int pagerLeft, int pagerTop, int width) {
            this.list = new WeakReference<>(list);
            this.host = new WeakReference<>(host);
            host.getLocationInWindow(at);
            int left = pagerLeft - at[0];
            Rect b = getBounds();
            int bottom = pagerTop - at[1];
            if (b.left != left || b.top != -at[1] || b.right != left + width || b.bottom != bottom) {
                setBounds(left, -at[1], left + width, bottom);
            }
            originX = pagerLeft;
            seam = pagerTop;
            ext = pagerTop + at[1];
            invalidateSelf();
        }

        void hide() {
            list = null;
            setBounds(0, 0, 0, 0);
        }

        @Override
        public void draw(Canvas canvas) {
            ViewGroup list = this.list != null ? this.list.get() : null;
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (list == null || w <= 0 || h <= 0 || !canvas.isHardwareAccelerated()) return;
            if (tintFor != ext) {
                tintFor = ext;
                tint.setShader(new LinearGradient(0, ext, 0, 0, 0x4A000000, 0x60000000, Shader.TileMode.CLAMP));
            }
            int save = canvas.save();
            canvas.translate(b.left, b.top);
            canvas.clipRect(0, 0, w, h);
            // From here on, window coordinates (x from the band's left edge).
            canvas.translate(0, -(seam - ext));

            // The reel nearest to resting under the band: the one showing, or the one a swipe is
            // bringing in. It is drawn where it will rest, not where it is: the band shows where
            // the swipe is heading, never the reels sliding past.
            View target = null;
            int dist = Integer.MAX_VALUE;
            for (int i = 0; i < list.getChildCount(); i++) {
                View page = list.getChildAt(i);
                if (page.getVisibility() != View.VISIBLE) continue;
                page.getLocationInWindow(at);
                int d = Math.abs(at[1] - seam);
                if (d < dist) {
                    dist = d;
                    target = page;
                }
            }
            live.setPosition(0, 0, w, ext);
            RecordingCanvas rc = live.beginRecording(w, ext);
            try {
                rc.translate(0, -(seam - ext));
                rc.drawColor(Color.BLACK);
                if (target != null) {
                    target.getLocationInWindow(at);
                    rc.translate(0, seam - at[1]);
                    drawPage(rc, target);
                }
            } finally {
                live.endRecording();
            }

            // Hold and settle. Live, the band showed the reels sliding past the status bar, a run
            // of colors from two videos flicking by in a strip of their own. Now a swipe holds the
            // band on what it showed at rest (copied every so often, blurred), and over the last
            // stretch of the swipe the arriving reel comes into focus, tied to the swipe itself:
            // fully there as it lands, with nothing held after.
            int pageH = target != null ? target.getHeight() : 0;
            float p = target == null || pageH == 0 ? 0f
                    : Math.max(0f, Math.min(1f, 1f - dist / (BAND_SETTLE * pageH)));
            boolean atRest = target != null && dist <= 1;
            float e = p * p * (3f - 2f * p);

            int bandSave = canvas.save();
            canvas.translate(0, seam - ext);
            canvas.clipRect(0, 0, w, ext);
            if (atRest || snapshot == null) {
                live.setRenderEffect(null);
                canvas.drawRenderNode(live);
                canvas.drawRect(0, 0, w, ext, tint);
                if (atRest) maybeCopy(w);
            } else {
                float r = (1f - e) * BAND_BLUR_DP * density;
                live.setRenderEffect(r >= 0.5f ? RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) : null);
                canvas.drawRenderNode(live);
                canvas.drawRect(0, 0, w, ext, tint);
                // The snapshot already has the tint in it.
                if (e < 1f) {
                    recordHeld(w);
                    int layer = canvas.saveLayerAlpha(0, 0, w, ext, Math.round(255 * (1f - e)));
                    canvas.drawRenderNode(held);
                    canvas.restoreToCount(layer);
                }
            }
            canvas.restoreToCount(bandSave);

            canvas.restoreToCount(save);
        }

        /** The held snapshot, stretched over the band and blurred. */
        private void recordHeld(int w) {
            held.setPosition(0, 0, w, ext);
            float r = BAND_BLUR_DP * density;
            held.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            RecordingCanvas rc = held.beginRecording(w, ext);
            try {
                rc.drawBitmap(snapshot, null, new Rect(0, 0, w, ext), bitmapPaint);
            } finally {
                held.endRecording();
            }
        }

        /** Copies what the band shows now (at rest) into {@link #spare}, then swaps it in. */
        private void maybeCopy(int w) {
            long now = SystemClock.uptimeMillis();
            if (copying || now - copiedAt < BAND_SNAPSHOT_MS || ext <= 0) return;
            View host = this.host != null ? this.host.get() : null;
            android.view.Window window = host != null ? BackdropSampler.windowOf(host.getContext()) : null;
            if (window == null || !host.isAttachedToWindow()) return;
            int bw = Math.max(1, w / 4), bh = Math.max(1, ext / 4);
            if (spare == null || spare.getWidth() != bw || spare.getHeight() != bh) {
                spare = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            }
            copyRect.set(originX, seam - ext, originX + w, seam);
            copiedAt = now;
            copying = true;
            final android.graphics.Bitmap target = spare;
            try {
                android.view.PixelCopy.request(window, copyRect, target, result -> {
                    copying = false;
                    if (result != android.view.PixelCopy.SUCCESS) return;
                    spare = snapshot;
                    snapshot = target;
                }, handler);
            } catch (Throwable t) {
                copying = false;
            }
        }

        /** {@code page}'s video where it is on screen, from its zoomed top down (window coordinates). */
        private void drawPage(Canvas canvas, View page) {
            View video = page != null && videoId != 0 ? page.findViewById(videoId) : null;
            if (video == null) return;
            int save = canvas.save();
            page.getLocationInWindow(at);
            canvas.clipRect(at[0] - originX, at[1] - seam, at[0] - originX + page.getWidth(), at[1] + page.getHeight());
            video.getLocationInWindow(at);
            canvas.translate(at[0] - originX, at[1]);
            drawVideoLayers(canvas, video);
            canvas.restoreToCount(save);
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

    /**
     * The video layout's layers (player, thumbnails), each placed and zoomed by hand: drawn
     * through the layout itself, the outgoing reel's zoom went missing mid-scroll and the
     * strip blurred the black bar below the unzoomed video.
     */
    private static void drawVideoLayers(Canvas canvas, View video) {
        if (!(video instanceof ViewGroup)) {
            video.draw(canvas);
            return;
        }
        ViewGroup layout = (ViewGroup) video;
        for (int i = 0; i < layout.getChildCount(); i++) {
            View layer = layout.getChildAt(i);
            if (layer.getVisibility() != View.VISIBLE || layer.getAlpha() == 0f) continue;
            int save = canvas.save();
            canvas.translate(layer.getLeft() + layer.getTranslationX(), layer.getTop() + layer.getTranslationY());
            canvas.scale(layer.getScaleX(), layer.getScaleY(), layer.getPivotX(), layer.getPivotY());
            layer.draw(canvas);
            canvas.restoreToCount(save);
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
