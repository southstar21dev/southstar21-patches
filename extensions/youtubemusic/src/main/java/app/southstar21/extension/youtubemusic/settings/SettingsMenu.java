package app.southstar21.extension.youtubemusic.settings;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;

import app.southstar21.extension.youtubemusic.YouTubeMusicHooks;
import app.southstar21.extension.youtubemusic.liquidglass.LiquidGlassNavBarPatch;

/**
 * Full-screen southstar21 Patches menu, laid out like the Instagram patches' menu: the first
 * page lists the included patches, and tapping one slides in its own page. Always in the dark
 * palette, as YouTube Music is always dark. Built from plain views so it needs no resources.
 */
@SuppressWarnings("deprecation")
public final class SettingsMenu {
    private static final int ACCENT = 0xFF0095F6;
    private static final int GREEN = 0xFF34C759;
    private static final int RED = 0xFFFF3B30;
    private static final int PURPLE = 0xFF5E5CE6;
    private static final long PUSH_MS = 320;

    private static final int BACKGROUND = Color.BLACK;
    private static final int CARD = 0xFF1C1C1E;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_SECONDARY = 0xFF98989F;
    private static final int TEXT_TERTIARY = 0xFF636366;
    private static final int DIVIDER = 0xFF38383A;
    private static final int PRESSED = 0x33FFFFFF;

    private static final String GLASS_TITLE = "Liquid Glass";
    private static final String GLASS_DESCRIPTION = "iOS 26 style glass tab bar, mini player and player. "
            + "The mini player docks beside the tabs as round album art.";

    private final Activity activity;
    private final float density;

    private Dialog dialog;
    private FrameLayout pages;
    /** Open pages, the list at the bottom. */
    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    /** Set while a page slides, so a quick second tap or back press can't mix up the pages. */
    private boolean animating;

    /** A page and how to rebuild it, so the list can refresh when you come back to it. */
    private interface Page {
        View build();
    }

    public static void show(Activity activity) {
        try {
            Settings.init(activity);
            new SettingsMenu(activity).open();
        } catch (Throwable t) {
            Log.e(YouTubeMusicHooks.TAG, "Settings menu failed", t);
        }
    }


    private SettingsMenu(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    private void open() {
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                if (!pop()) super.onBackPressed();
            }
        };
        dialog.setContentView(buildRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
            window.setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        }
        push(this::buildPatchList, false);
        dialog.show();
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(BACKGROUND);
        pages = new FrameLayout(activity);
        root.addView(pages, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // The window is drawn edge to edge: keep the content clear of the status and nav bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        return root;
    }

    // region Navigation

    private void push(Page page, boolean animate) {
        if (animating) return;
        View previous = pages.getChildCount() > 0 ? pages.getChildAt(pages.getChildCount() - 1) : null;
        View view = page.build();
        stack.push(page);
        pages.addView(view);
        if (!animate || previous == null) return;

        float width = pages.getWidth() > 0 ? pages.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        view.setTranslationX(width);
        view.setElevation(dp(2));
        animating = true;
        view.animate().translationX(0f).setDuration(PUSH_MS).setInterpolator(ease())
                .withEndAction(() -> animating = false).start();
        previous.animate().translationX(-width * 0.3f).alpha(0.6f).setDuration(PUSH_MS).setInterpolator(ease())
                .withEndAction(() -> previous.setVisibility(View.INVISIBLE)).start();
    }

    /** @return false if already on the first page. */
    private boolean pop() {
        if (animating) return true;
        if (stack.size() <= 1 || pages.getChildCount() < 2) return false;
        stack.pop();
        animating = true;
        View top = pages.getChildAt(pages.getChildCount() - 1);
        View below = pages.getChildAt(pages.getChildCount() - 2);

        // Rebuild the page underneath so it shows what changed (the glass's style).
        int index = pages.indexOfChild(below);
        View fresh = stack.peek().build();
        fresh.setTranslationX(below.getTranslationX());
        fresh.setAlpha(below.getAlpha());
        pages.removeView(below);
        pages.addView(fresh, index);

        float width = pages.getWidth();
        fresh.animate().translationX(0f).alpha(1f).setDuration(PUSH_MS).setInterpolator(ease()).start();
        top.animate().translationX(width).setDuration(PUSH_MS).setInterpolator(ease())
                .withEndAction(() -> {
                    pages.removeView(top);
                    animating = false;
                }).start();
        return true;
    }

    /** Rebuilds the visible page in place, e.g. after a change or a reset. */
    private void replaceTop() {
        Page page = stack.peek();
        if (page == null) return;
        View old = pages.getChildAt(pages.getChildCount() - 1);
        View fresh = page.build();
        pages.removeView(old);
        pages.addView(fresh);
        ScrollView a = findScroll(old), b = findScroll(fresh);
        if (a != null && b != null) {
            int y = a.getScrollY();
            b.post(() -> b.scrollTo(0, y));
        }
    }

    private static ScrollView findScroll(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof ScrollView) return (ScrollView) group.getChildAt(i);
        }
        return null;
    }

    private static PathInterpolator ease() {
        return new PathInterpolator(0.2f, 0.9f, 0.3f, 1f);
    }

    // endregion

    // region Pages

    private View buildPatchList() {
        LinearLayout list = pageList();

        // southstar21.dev logo, centered, in the brand kit's cream for dark backgrounds.
        ImageView logo = new ImageView(activity);
        logo.setImageDrawable(new Logo(true));
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("southstar21.dev");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        logoParams.setMargins(dp(12), dp(8), dp(12), 0);
        list.addView(logo, logoParams);
        TextView subtitle = text("southstar21 Patches for YouTube Music", 15, TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(4), dp(10), dp(4), dp(8));
        list.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        list.addView(sectionHeader("Installed patches"));
        LinearLayout patchCard = cardView();
        patchCard.addView(navigationRow(Icons.Glyph.GLASS, PURPLE, GLASS_TITLE, shortDescription(GLASS_DESCRIPTION),
                Settings.lightweightGlass() ? "Lightweight" : "Full", () -> push(this::buildGlassPage, true)));
        list.addView(patchCard);
        list.addView(sectionFooter("Patches not selected in Morphe Manager aren't listed."));

        list.addView(sectionHeader("App"));
        LinearLayout app = cardView();
        app.addView(actionRow(Icons.Glyph.RESTART, GREEN, "Restart YouTube Music", TEXT_PRIMARY,
                () -> restartApp(activity)));
        app.addView(dividerView(true));
        app.addView(actionRow(Icons.Glyph.RESET, RED, "Reset all settings", RED, () -> confirm(
                "Reset all settings?",
                "Every patch goes back to its defaults.",
                "Reset", () -> {
                    setLightweight(true);
                    replaceTop();
                })));
        list.addView(app);

        return page(null, list);
    }

    private View buildGlassPage() {
        LinearLayout list = pageList();

        // Header: big icon, name and what the patch does.
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(Icons.tile(Icons.Glyph.GLASS, PURPLE, density));
        header.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView name = text(GLASS_TITLE, 24, TEXT_PRIMARY, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(12), 0, 0);
        header.addView(name);
        TextView description = text(GLASS_DESCRIPTION, 15, TEXT_SECONDARY, false);
        description.setGravity(Gravity.CENTER);
        description.setPadding(dp(8), dp(4), dp(8), 0);
        header.addView(description);
        list.addView(header);

        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsLp.topMargin = dp(18);
        list.addView(styleCards(), cardsLp);

        list.addView(sectionHeader(null));
        LinearLayout reset = cardView();
        TextView resetText = text("Reset to defaults", 16, ACCENT, false);
        resetText.setGravity(Gravity.CENTER);
        resetText.setMinHeight(dp(50));
        resetText.setBackground(ripple());
        resetText.setOnClickListener(v -> confirm("Reset " + GLASS_TITLE + "?",
                "It goes back to lightweight glass.", "Reset", () -> {
                    setLightweight(true);
                    replaceTop();
                }));
        reset.addView(resetText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(reset);

        return page("Patches", list);
    }

    /**
     * The choice of glass: two big cards, each a small bar drawn in its style, the chosen one
     * ringed, with a note on the chosen one underneath. Tapping the other switches to it.
     */
    private View styleCards() {
        boolean lightweight = Settings.lightweightGlass();
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(activity);
        String[] names = {"Lightweight", "Full Liquid Glass"};
        for (int i = 0; i < names.length; i++) {
            boolean isLightweight = i == 0;
            boolean chosen = isLightweight == lightweight;
            String badge = isLightweight ? "Smooth on any phone" : "For newer, faster phones";
            View card = styleCard(names[i], badge, isLightweight ? GREEN : PURPLE, !isLightweight, chosen);
            card.setContentDescription(names[i] + ", " + badge + (chosen ? ", chosen" : ""));
            card.setOnClickListener(v -> {
                if (Settings.lightweightGlass() == isLightweight) return;
                setLightweight(isLightweight);
                replaceTop();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(140), 1f);
            if (i > 0) lp.leftMargin = dp(12);
            row.addView(card, lp);
        }
        column.addView(row);

        TextView note = text(lightweight
                ? "One blur and simple shading, drawn the way Morphe Patches draws its glass. Smooth on every "
                + "phone. Android 14 and later blur what's behind the glass; older versions get a solid bar."
                : "Refraction along every edge, a blurred band along the bottom of the screen and liquid "
                + "buttons in the player. It's the heaviest look; on an older phone, Lightweight runs much "
                + "smoother.", 13, TEXT_SECONDARY, false);
        note.setPadding(dp(16), dp(8), dp(16), 0);
        column.addView(note);
        return column;
    }

    /** A style card: a small bar in that style on a colorful backdrop, its name and a badge. */
    private View styleCard(String title, String badge, int badgeColor, boolean full, boolean chosen) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(dp(16));
        shape.setStroke(chosen ? dp(2) : dp(1), chosen ? ACCENT : DIVIDER);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(16));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(PRESSED), shape, mask));
        card.setClickable(true);

        View preview = new BarPreview(activity, full);
        card.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView name = text(title, 15, TEXT_PRIMARY, true);
        name.setPadding(dp(2), dp(8), 0, 0);
        card.addView(name);
        TextView tag = text(badge, 12, badgeColor, false);
        tag.setPadding(dp(2), dp(1), 0, 0);
        tag.setSingleLine(true);
        tag.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(tag);
        return card;
    }

    private void setLightweight(boolean lightweight) {
        Settings.setLightweightGlass(lightweight);
        LiquidGlassNavBarPatch.onGlassModeChanged(activity);
    }

    /**
     * A page: a top bar with a back button, and the scrolling content. On the first page
     * the back button closes the menu.
     */
    private View page(String backLabel, LinearLayout content) {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BACKGROUND);
        page.setClickable(true); // Don't let touches fall through to the page underneath.

        FrameLayout bar = new FrameLayout(activity);
        LinearLayout back = new LinearLayout(activity);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(8), 0, dp(12), 0);
        ImageView chevron = new ImageView(activity);
        chevron.setImageDrawable(Icons.chevron(backLabel == null ? TEXT_PRIMARY : ACCENT, density, true));
        back.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        if (backLabel != null) {
            back.addView(text(backLabel, 17, ACCENT, false));
            back.setContentDescription("Back to " + backLabel);
        } else {
            back.setContentDescription("Close");
        }
        back.setBackground(ripple());
        back.setOnClickListener(v -> {
            if (!pop()) dialog.dismiss();
        });
        bar.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48),
                Gravity.START | Gravity.CENTER_VERTICAL));
        page.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private LinearLayout pageList() {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(32));
        return list;
    }

    // endregion

    // region Rows

    /** A patch in the list: icon, name, short description, status and a chevron. */
    private View navigationRow(Icons.Glyph glyph, int color, String title, String summary, String status,
                               Runnable onClick) {
        LinearLayout row = rowContainer(dp(60));
        row.addView(iconView(glyph, color, 32));

        LinearLayout labels = labels(title, summary);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsLp.leftMargin = dp(12);
        row.addView(labels, labelsLp);

        TextView value = text(status, 16, TEXT_SECONDARY, false);
        value.setPadding(dp(8), 0, dp(2), 0);
        row.addView(value);
        ImageView chevron = new ImageView(activity);
        chevron.setImageDrawable(Icons.chevron(TEXT_TERTIARY, density, false));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

        row.setBackground(ripple());
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private View actionRow(Icons.Glyph glyph, int color, String title, int titleColor, Runnable onClick) {
        LinearLayout row = rowContainer(dp(52));
        row.addView(iconView(glyph, color, 30));
        TextView label = text(title, 16, titleColor, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(12);
        row.addView(label, lp);
        row.setBackground(ripple());
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    // endregion

    // region Restart

    /** Relaunches YouTube Music: schedules the launcher intent, then ends the process. */
    static void restartApp(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pending = PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 300, pending);
        if (context instanceof Activity) ((Activity) context).finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // endregion

    // region Building blocks

    private void confirm(String title, String message, String action, Runnable onConfirm) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (d, w) -> onConfirm.run())
                .show();
    }

    private LinearLayout cardView() {
        LinearLayout cardView = new LinearLayout(activity);
        cardView.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(dp(14));
        cardView.setBackground(shape);
        cardView.setClipToOutline(true);
        return cardView;
    }

    private View sectionHeader(String title) {
        if (TextUtils.isEmpty(title)) {
            View space = new View(activity);
            space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(20)));
            return space;
        }
        TextView header = text(title.toUpperCase(), 13, TEXT_SECONDARY, false);
        header.setLetterSpacing(0.03f);
        header.setPadding(dp(16), dp(24), dp(16), dp(7));
        return header;
    }

    private View sectionFooter(String value) {
        TextView footer = text(value, 13, TEXT_SECONDARY, false);
        footer.setPadding(dp(16), dp(7), dp(16), 0);
        return footer;
    }

    private LinearLayout rowContainer(int minHeight) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(minHeight);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        return row;
    }

    private View iconView(Icons.Glyph glyph, int color, int sizeDp) {
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(Icons.tile(glyph, color, density));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return icon;
    }

    /** Title and a one-line summary. */
    private LinearLayout labels(String title, String summary) {
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, TEXT_PRIMARY, false));
        if (!TextUtils.isEmpty(summary)) {
            TextView s = text(summary, 13, TEXT_SECONDARY, false);
            s.setPadding(0, dp(2), dp(8), 0);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(s);
        }
        return labels;
    }

    /** Hairline between rows, indented past the icon when the rows have one. */
    private View dividerView(boolean pastIcon) {
        View line = new View(activity);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Math.round(0.5f * density)));
        lp.leftMargin = pastIcon ? dp(16 + 32 + 12) : dp(16);
        line.setLayoutParams(lp);
        return line;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(PRESSED), null, new ColorDrawable(Color.WHITE));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return view;
    }

    /** The first sentence of a description, for the list. */
    private static String shortDescription(String d) {
        if (TextUtils.isEmpty(d)) return null;
        int end = d.indexOf(". ");
        return end > 0 ? d.substring(0, end + 1) : d;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // endregion

    /**
     * A small tab bar over a colorful page, in one of the two styles: lightweight is a frosted
     * pill with a soft sheen, full glass adds a bright, bent rim with colored fringes.
     */
    private static final class BarPreview extends View {
        private final boolean full;
        private final float density;
        private final Paint page = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fringe = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        BarPreview(Context context, boolean full) {
            super(context);
            this.full = full;
            this.density = context.getResources().getDisplayMetrics().density;
            rim.setStyle(Paint.Style.STROKE);
            fringe.setStyle(Paint.Style.STROKE);
            dot.setColor(0xCCFFFFFF);
            setClipToOutline(true);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * density);
                }
            });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            // The page: album art colors.
            page.setShader(new LinearGradient(0, 0, w, h, 0xFF3A1C71, 0xFF0B0B0F, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, page);
            blob.setColor(0xFFD76D77);
            canvas.drawCircle(w * 0.28f, h * 0.42f, h * 0.26f, blob);
            blob.setColor(0xFFFFAF7B);
            canvas.drawCircle(w * 0.74f, h * 0.50f, h * 0.22f, blob);

            float margin = 8 * density;
            float barH = Math.min(h * 0.42f, 30 * density);
            rect.set(margin, h - margin - barH, w - margin, h - margin);
            float r = barH / 2f;
            // Frosted: the page shows through as a soft wash of its colors.
            fill.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                    new int[]{0xFF4A2A55, 0xFF2A2030, 0xFF4F3A3A}, null, Shader.TileMode.CLAMP));
            fill.setAlpha(full ? 0xC8 : 0xE6);
            canvas.drawRoundRect(rect, r, r, fill);
            fill.setShader(null);
            fill.setAlpha(0xFF);
            sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x1FFFFFFF, 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, sheen);
            if (full) {
                // Colored fringes just inside the rim, then a bright lit edge.
                fringe.setStrokeWidth(2.5f * density);
                fringe.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                        new int[]{0x99FF4D6D, 0x4D5EB0FF, 0x4D7CFFB2, 0x99FFC94D},
                        null, Shader.TileMode.CLAMP));
                RectF inner = new RectF(rect);
                inner.inset(2.5f * density, 2.5f * density);
                canvas.drawRoundRect(inner, r - 2.5f * density, r - 2.5f * density, fringe);
                rim.setStrokeWidth(1.2f * density);
                rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0xCCFFFFFF, 0x33FFFFFF,
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, r, r, rim);
            }
            // Three tabs, the first one selected.
            float cy = rect.centerY();
            float step = rect.width() / 3f;
            fill.setColor(0x33FFFFFF);
            RectF sel = new RectF(rect.left + 3 * density, rect.top + 3 * density,
                    rect.left + step, rect.bottom - 3 * density);
            float sr = sel.height() / 2f;
            canvas.drawRoundRect(sel, sr, sr, fill);
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(rect.left + step * (i + 0.5f), cy, 3.5f * density, dot);
            }
        }
    }
}
