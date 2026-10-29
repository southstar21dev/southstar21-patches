package app.liquidglass.extension.instagram;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * Turns Instagram's full width tab bar into a floating pill and keeps it that way.
 * <p>
 * Instagram owns the tab bar and changes its background, height and padding at runtime
 * (Reels switches it to a dark bar, insets change on rotation). Instead of patching every
 * call site, the controller re-checks the bar on each pre-draw and re-applies its changes
 * whenever Instagram has overwritten them.
 */
final class LiquidGlassController implements ViewTreeObserver.OnPreDrawListener {
    /** Gap between the pill and the bottom of the screen / nav bar. */
    private static final int BOTTOM_GAP_DP = 8;
    /** Extra height so the pill matches iOS proportions (~62dp vs Instagram's 48dp). */
    private static final int GROW_DP = 14;
    /** Gap between the pill and the screen edges. */
    private static final int SIDE_GAP_DP = 16;
    /** Space between the pill edge and the outermost tab icons. */
    private static final int INNER_PADDING_DP = 6;
    /** On tablets and landscape the pill stops growing at this width. */
    private static final int MAX_PILL_WIDTH_DP = 520;

    private final ViewGroup bar;
    private final View shadow;
    private final boolean extendContent;
    private final LiquidGlassDrawable glass;
    private final LiquidGlassLens lens;
    private final BackdropSampler sampler;
    private final android.graphics.Rect sampleRect = new android.graphics.Rect();
    private final float density;

    // Last values we applied, used to notice when Instagram overwrites them.
    private int baseHeight = -1;
    private int appliedHeight = Integer.MIN_VALUE;
    private int appliedPadLeft = -1, appliedPadTop = -1, appliedPadRight = -1, appliedPadBottom = -1;
    private int basePadLeft, basePadTop, basePadRight, basePadBottom;
    private int appliedForWidth = -1;

    LiquidGlassController(ViewGroup bar, View shadow,
                          int blurRadiusDp, int refractionPercent, boolean extendContent) {
        this.bar = bar;
        this.shadow = shadow;
        this.extendContent = extendContent;
        this.density = bar.getResources().getDisplayMetrics().density;
        this.glass = new LiquidGlassDrawable(bar, density, blurRadiusDp, refractionPercent);
        this.lens = new LiquidGlassLens(bar.getContext(), bar, glass, density);
        this.sampler = new BackdropSampler(bar, glass::setBackdropLuminance);
    }

    void install() {
        basePadLeft = bar.getPaddingLeft();
        basePadTop = bar.getPaddingTop();
        basePadRight = bar.getPaddingRight();
        basePadBottom = bar.getPaddingBottom();

        captureForeignBackground();
        bar.setBackground(glass);
        bar.setClipToPadding(false);

        ViewGroup parent = (ViewGroup) bar.getParent();
        parent.addView(lens, parent.indexOfChild(bar) + 1, lensLayoutParams());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Soft system shadow cast by the pill outline. The glass is opaque on 12+, so the
            // shadow can't show through it (on older versions the tint is translucent: skip).
            bar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    android.graphics.RectF pill = glass.getPill();
                    outline.setRoundRect(Math.round(pill.left), Math.round(pill.top),
                            Math.round(pill.right), Math.round(pill.bottom), pill.height() / 2f);
                    outline.setAlpha(0.9f);
                }
            });
            bar.setElevation(dp(5));
            // Keep the lens above the now-elevated bar, without a shadow of its own.
            lens.setOutlineProvider(null);
            lens.setElevation(dp(5) + 0.1f);
        }

        android.view.Window window = BackdropSampler.windowOf(bar.getContext());
        if (window != null) TouchRouter.install(window, lens);

        bar.getViewTreeObserver().addOnPreDrawListener(this);
        bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnPreDrawListener(LiquidGlassController.this);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnPreDrawListener(LiquidGlassController.this);
            }
        });
        enforce();
    }

    private ViewGroup.LayoutParams lensLayoutParams() {
        ViewGroup.LayoutParams barLp = bar.getLayoutParams();
        int height = barLp.height > 0 ? barLp.height + lens.extraTop : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (barLp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) barLp);
            lp.height = height;
            return lp;
        }
        if (barLp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) barLp);
            lp.height = height;
            return lp;
        }
        return new ViewGroup.LayoutParams(barLp.width, height);
    }

    @Override
    public boolean onPreDraw() {
        try {
            enforce();
            if (bar.getVisibility() == View.VISIBLE) {
                // Content behind the bar may have changed this frame. Invalidating during
                // pre-draw folds the redraw into the current frame instead of scheduling a new one.
                glass.invalidateSelf();
            }
            lens.sync();
            // The popped-out lens covers the sample strip, so skip sampling while it's up.
            if (bar.getVisibility() == View.VISIBLE && !lens.isLensActive()) {
                glass.getPill().round(sampleRect);
                sampler.maybeSample(sampleRect, dp(20));
            }
        } catch (Throwable t) {
            bar.getViewTreeObserver().removeOnPreDrawListener(this);
            android.util.Log.e(LiquidGlassNavBarPatch.TAG, "Disabling liquid glass", t);
        }
        return true;
    }

    private void enforce() {
        if (bar.getBackground() != glass) {
            captureForeignBackground();
            bar.setBackground(glass);
        }

        glass.setDark(isDark());

        if (shadow != null && shadow.getAlpha() != 0f) shadow.setAlpha(0f);

        enforceGeometry();
        enforceContentMargins();
    }

    /**
     * Instagram swaps the bar background to signal its color scheme (e.g. black on Reels).
     * Keep the color as a hint for the glass tint, then put our drawable back.
     */
    private void captureForeignBackground() {
        Drawable bg = bar.getBackground();
        if (bg == null || bg == glass) return;
        glass.setHostColorHint(bg instanceof ColorDrawable ? ((ColorDrawable) bg).getColor() : null);
    }

    private boolean isDark() {
        Integer hint = glass.getHostColorHint();
        if (hint != null && Color.alpha(hint) > 0x80) {
            return luminance(hint) < 0.4f;
        }
        int mode = bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static float luminance(int color) {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color)
                + 0.0722f * Color.blue(color)) / 255f;
    }

    private void enforceGeometry() {
        int width = bar.getWidth();
        if (width <= 0) return;

        // Instagram changed the padding itself (e.g. new insets): take its values as the new base.
        if (bar.getPaddingLeft() != appliedPadLeft || bar.getPaddingTop() != appliedPadTop
                || bar.getPaddingRight() != appliedPadRight || bar.getPaddingBottom() != appliedPadBottom) {
            if (appliedPadLeft != -1) {
                basePadLeft = bar.getPaddingLeft();
                basePadTop = bar.getPaddingTop();
                basePadRight = bar.getPaddingRight();
                basePadBottom = bar.getPaddingBottom();
            }
            appliedForWidth = -1;
        }

        int gap = dp(BOTTOM_GAP_DP);
        int grow = dp(GROW_DP);
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp != null && lp.height > 0 && lp.height != appliedHeight) {
            baseHeight = lp.height;
            lp.height += gap + grow;
            appliedHeight = lp.height;
            bar.setLayoutParams(lp);

            ViewGroup.LayoutParams lensLp = lens.getLayoutParams();
            lensLp.height = appliedHeight + lens.extraTop;
            lens.setLayoutParams(lensLp);
        }

        if (appliedForWidth == width) return;

        int side = dp(SIDE_GAP_DP);
        int maxPill = dp(MAX_PILL_WIDTH_DP);
        int usable = width - basePadLeft - basePadRight;
        if (usable - 2 * side > maxPill) side = (usable - maxPill) / 2;

        int inner = dp(INNER_PADDING_DP);
        appliedPadLeft = basePadLeft + side + inner;
        appliedPadTop = basePadTop + grow / 2;
        appliedPadRight = basePadRight + side + inner;
        appliedPadBottom = basePadBottom + gap + (grow - grow / 2);
        appliedForWidth = width;

        glass.setPillInsets(basePadLeft + side, basePadRight + side, basePadBottom + gap);
        bar.setPadding(appliedPadLeft, appliedPadTop, appliedPadRight, appliedPadBottom);
        bar.invalidateOutline();
    }

    /**
     * Siblings under the bar (the main container, and the ViewPager2 holding the feed) stop
     * at the top of the tab bar with a bottom margin equal to its height, some set in XML and
     * some in code. Drop those margins so content scrolls underneath the glass.
     */
    private void enforceContentMargins() {
        if (!extendContent || baseHeight <= 0) return;
        ViewGroup parent = (ViewGroup) bar.getParent();
        int barIndex = parent.indexOfChild(bar);
        for (int i = 0; i < barIndex; i++) {
            View child = parent.getChildAt(i);
            if (child == shadow) continue;
            if (!(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            // Only undo the tab bar margin, leave other margins Instagram may set alone.
            if (lp.bottomMargin == baseHeight) {
                lp.bottomMargin = 0;
                child.setLayoutParams(lp);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
