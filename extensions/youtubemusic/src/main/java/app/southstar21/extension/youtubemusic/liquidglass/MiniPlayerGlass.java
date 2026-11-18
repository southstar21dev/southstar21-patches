package app.southstar21.extension.youtubemusic.liquidglass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/**
 * Turns YouTube Music's mini player into a floating glass card above the tab bar.
 * <p>
 * The mini player is the top strip of the player page ({@code player_page}), which slides up
 * from it to the full player. The page's own background (YouTube Music's gradient) paints both
 * the mini player's grey and the full player behind its controls, so it isn't thrown away: the
 * page gets a background that draws the glass card while the player is collapsed and fades
 * YouTube Music's background back in as the mini player fades out on the way up.
 */
final class MiniPlayerGlass implements ViewTreeObserver.OnPreDrawListener {
    /** Space between the card's top and the top of YouTube Music's mini player. */
    private static final int TOP_INSET_DP = 4;
    /**
     * Moves the thumbnail and the buttons in from the card's round ends (YouTube Music places
     * the thumbnail where the mini player's spacer is, so it follows the padding).
     */
    private static final int SIDE_PADDING_DP = 8;

    private final ViewGroup page;
    private final int miniPlayerId;
    private final int artId;
    /** The album art: a card YouTube Music grows from the mini player into the full player. */
    private View art;
    /** 1 for a round thumbnail, 0 for YouTube Music's own corners (the full player's art). */
    private float artRound = -1f;
    private final android.graphics.Outline artOutline = new android.graphics.Outline();
    private final android.view.ViewOutlineProvider roundArt = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline out) {
            int w = view.getWidth(), h = view.getHeight();
            // YouTube Music's own corner radius, from the card's background.
            artOutline.setEmpty();
            android.view.ViewOutlineProvider.BACKGROUND.getOutline(view, artOutline);
            float own = Math.max(0f, artOutline.getRadius());
            float radius = own + (Math.min(w, h) / 2f - own) * Math.max(0f, artRound);
            out.setRoundRect(0, 0, w, h, radius);
        }
    };
    private final float density;
    private final LiquidGlassDrawable glass;
    private final Background background = new Background();
    private View miniPlayer;
    /** When the glass was last redrawn for what's behind it (uptime ms). */
    private long lastRefresh;
    private boolean layoutChanged = true;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> layoutChanged = true;

    MiniPlayerGlass(ViewGroup page, int miniPlayerId, int artId) {
        this.page = page;
        this.miniPlayerId = miniPlayerId;
        this.artId = artId;
        this.density = page.getResources().getDisplayMetrics().density;
        this.glass = new LiquidGlassDrawable(page, density, NavbarStyle::miniPlayer);
        glass.setCallback(background);
    }

    void install() {
        glass.setDark(true);
        glass.setSouthstar21(NavbarStyle.miniPlayer().southstar21);
        adoptBackground();
        page.getViewTreeObserver().addOnPreDrawListener(this);
        page.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnPreDrawListener(MiniPlayerGlass.this);
                v.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnPreDrawListener(MiniPlayerGlass.this);
            }
        });
    }

    /** Wraps whatever background YouTube Music gave the page (it may set a new one). */
    private void adoptBackground() {
        Drawable current = page.getBackground();
        if (current == background) return;
        background.setOriginal(current);
        page.setBackground(background);
    }

    @Override
    public boolean onPreDraw() {
        try {
            adoptBackground();
            if (miniPlayer == null || !isInside(miniPlayer)) miniPlayer = page.findViewById(miniPlayerId);
            View mini = miniPlayer;
            float shown = mini == null || mini.getWidth() == 0 ? 0f : visibleAlpha(mini);
            background.setGlassAlpha(shown);
            roundArt(shown);
            if (shown == 0f) return true;

            int pad = Math.round(SIDE_PADDING_DP * density);
            if (mini.getPaddingLeft() != pad || mini.getPaddingRight() != pad) {
                mini.setPadding(pad, mini.getPaddingTop(), pad, mini.getPaddingBottom());
            }

            // The mini player's bounds in the page's coordinates.
            float left = 0, top = 0;
            View v = mini;
            while (v != page) {
                left += v.getLeft() + v.getTranslationX();
                top += v.getTop() + v.getTranslationY();
                ViewParent p = v.getParent();
                if (!(p instanceof View)) return true;
                v = (View) p;
            }
            NavbarStyle style = NavbarStyle.miniPlayer();
            int side = Math.round(style.sideMarginDp * density);
            int l = Math.round(left), t = Math.round(top);
            glass.setPillInsets(l + side, t + Math.round(TOP_INSET_DP * density),
                    page.getWidth() - (l + mini.getWidth()) + side,
                    page.getHeight() - (t + mini.getHeight()));

            // What's behind can only have moved during or just after a touch, or with a layout
            // pass; otherwise once a second. (Every frame kept the app drawing nonstop.)
            long now = SystemClock.uptimeMillis();
            if (layoutChanged || Interaction.recent(now) || now - lastRefresh > 1000) {
                layoutChanged = false;
                lastRefresh = now;
                background.invalidateSelf();
            }
        } catch (Throwable t) {
            page.getViewTreeObserver().removeOnPreDrawListener(this);
            Log.e(LiquidGlassNavBarPatch.TAG, "Disabling mini player glass", t);
        }
        return true;
    }

    /**
     * Makes the mini player's album art a circle, turning back into YouTube Music's own rounded
     * square as the full player opens ({@code round} follows the mini player's fade).
     */
    private void roundArt(float round) {
        if (artId == 0) return;
        if (art == null || !isInside(art)) {
            art = page.findViewById(artId);
            artRound = -1f;
            if (art == null) return;
        }
        if (art.getOutlineProvider() != roundArt) {
            art.setOutlineProvider(roundArt);
            art.setClipToOutline(true);
            artRound = -1f;
        }
        if (round != artRound) {
            artRound = round;
            art.invalidateOutline();
        }
    }

    /** The mini player's alpha with its parents' up to the page: it fades as the player opens. */
    private float visibleAlpha(View view) {
        float alpha = 1f;
        for (View v = view; v != page; v = (View) v.getParent()) {
            if (v.getVisibility() != View.VISIBLE) return 0f;
            alpha *= v.getAlpha();
            if (!(v.getParent() instanceof View)) return 0f;
        }
        return alpha;
    }

    private boolean isInside(View view) {
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == page) return true;
        }
        return false;
    }

    /** The page's background: the glass card over YouTube Music's own, cross-faded. */
    private final class Background extends Drawable implements Drawable.Callback {
        private Drawable original;
        /** 1 while the mini player shows, 0 once the full player is open. */
        private float glassAlpha = 1f;

        void setOriginal(Drawable drawable) {
            if (original != null) original.setCallback(null);
            original = drawable == null ? null : drawable.mutate();
            if (original != null) {
                original.setCallback(this);
                original.setBounds(getBounds());
            }
            invalidateSelf();
        }

        void setGlassAlpha(float alpha) {
            if (alpha == glassAlpha) return;
            glassAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (original != null && glassAlpha < 1f) {
                original.setAlpha(Math.round((1f - glassAlpha) * 255));
                original.draw(canvas);
            }
            if (glassAlpha <= 0f) return;
            if (glassAlpha >= 1f) {
                glass.draw(canvas);
            } else {
                int save = canvas.saveLayerAlpha(b.left, b.top, b.right, b.bottom, Math.round(glassAlpha * 255));
                glass.draw(canvas);
                canvas.restoreToCount(save);
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            glass.setBounds(bounds);
            if (original != null) original.setBounds(bounds);
        }

        @Override
        public boolean getPadding(Rect padding) {
            // Keep the page's own padding.
            padding.set(0, 0, 0, 0);
            return false;
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

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }
}
