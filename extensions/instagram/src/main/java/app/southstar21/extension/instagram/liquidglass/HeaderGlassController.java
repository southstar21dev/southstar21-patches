package app.southstar21.extension.instagram.liquidglass;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Glass treatment for the headers at the top of the feed and Explore tabs.
 * <p>
 * Each tab page has its own header ({@code main_feed_action_bar}, {@code explore_action_bar})
 * inside a wrapper that sits above the full height list. The wrapper is made see-through,
 * a top {@link EdgeBlurView} is slipped in behind it, the icon buttons get glass circles and
 * Explore's search field becomes a glass capsule.
 */
final class HeaderGlassController {
    private static final long SCAN_INTERVAL_MS = 500;
    /** iOS 26 header buttons are ~44pt glass circles. */
    private static final float BUTTON_DIAMETER_DP = 42f;

    private final View root;
    private final float density;
    private final int[] headerIds;
    private final int searchFieldId;
    private final int searchEditTextId;
    private final int leftButtonsId;
    private final int rightButtonsId;

    private final WeakHashMap<View, Header> headers = new WeakHashMap<>();
    private long lastScan;
    private boolean dark;

    private static final class Header {
        final ViewGroup wrapper;
        final EdgeBlurView blur;
        final ArrayList<View> styled = new ArrayList<>();
        /** The list scrolling under this header, found lazily. */
        WeakReference<View> scroller;
        /** Eased 0..1: header buttons only become glass once content is scrolled under them. */
        float reveal;

        Header(ViewGroup wrapper, EdgeBlurView blur) {
            this.wrapper = wrapper;
            this.blur = blur;
        }
    }

    HeaderGlassController(View root, float density) {
        this.root = root;
        this.density = density;
        Resources res = root.getResources();
        headerIds = new int[]{id(res, "main_feed_action_bar"), id(res, "explore_action_bar")};
        searchFieldId = id(res, "action_bar_search_hints_text_layout");
        searchEditTextId = id(res, "action_bar_search_edit_text");
        leftButtonsId = id(res, "action_bar_buttons_container_left");
        rightButtonsId = id(res, "action_bar_buttons_container_right");
    }

    private static int id(Resources res, String name) {
        return res.getIdentifier(name, "id", "com.instagram.android");
    }

    void setDark(boolean dark) {
        this.dark = dark;
    }

    /** Called every pre-draw: keeps installed headers in shape, looks for new ones now and then. */
    void onPreDraw() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScan > SCAN_INTERVAL_MS) {
            lastScan = now;
            for (int headerId : headerIds) {
                if (headerId == 0) continue;
                View header = root.findViewById(headerId);
                if (header != null && !headers.containsKey(header)) install(header);
            }
        }
        for (Header h : headers.values()) {
            if (h != null) enforce(h);
        }
    }

    private void install(View header) {
        if (!(header.getParent() instanceof ViewGroup)) return;
        ViewGroup wrapper = (ViewGroup) header.getParent();
        if (!(wrapper.getParent() instanceof FrameLayout)) {
            headers.put(header, null);
            return;
        }
        FrameLayout page = (FrameLayout) wrapper.getParent();

        EdgeBlurView blur = new EdgeBlurView(header.getContext(), density, true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, wrapper.getHeight()) + blur.extraTop, Gravity.TOP);
        page.addView(blur, page.indexOfChild(wrapper), lp);

        Header h = new Header(wrapper, blur);
        collectStyled(header, h);
        headers.put(header, h);
        enforce(h);
    }

    private void collectStyled(View header, Header h) {
        h.styled.add(header);
        for (int containerId : new int[]{leftButtonsId, rightButtonsId}) {
            View container = containerId == 0 ? null : header.findViewById(containerId);
            if (!(container instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                View button = group.getChildAt(i);
                if (button.isClickable()) {
                    setBackgroundKeepingPadding(button, GlassButtonDrawable.circle(density, BUTTON_DIAMETER_DP));
                    h.styled.add(button);
                }
            }
        }
        View field = searchFieldId == 0 ? null : header.findViewById(searchFieldId);
        if (field != null) {
            setBackgroundKeepingPadding(field, GlassButtonDrawable.capsule(density));
            h.styled.add(field);
            View edit = searchEditTextId == 0 ? null : field.findViewById(searchEditTextId);
            if (edit != null) setBackgroundKeepingPadding(edit, new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void enforce(Header h) {
        // Instagram paints the header white/black; keep it see-through so the blur shows.
        clearBackground(h.wrapper);

        float target = isScrolled(h) ? 1f : 0f;
        h.reveal += (target - h.reveal) * 0.25f;
        if (Math.abs(target - h.reveal) < 0.01f) h.reveal = target;

        for (int i = 0; i < h.styled.size(); i++) {
            View v = h.styled.get(i);
            if (v.getBackground() instanceof GlassButtonDrawable) {
                GlassButtonDrawable glass = (GlassButtonDrawable) v.getBackground();
                glass.setDark(dark);
                // The Explore search field is always a field; the icon circles come and go.
                if (v.getId() != searchFieldId) glass.setReveal(h.reveal);
            } else if (i == 0) {
                clearBackground(v);
            }
        }

        int height = h.wrapper.getHeight() + h.blur.extraTop;
        ViewGroup.LayoutParams lp = h.blur.getLayoutParams();
        if (h.wrapper.getHeight() > 0 && lp.height != height) {
            lp.height = height;
            h.blur.setLayoutParams(lp);
        }
        // Follow the header when Instagram slides it away while scrolling.
        float ty = h.wrapper.getTranslationY();
        if (h.blur.getTranslationY() != ty) h.blur.setTranslationY(ty);
        int visibility = h.wrapper.getVisibility() == View.VISIBLE && h.wrapper.isShown() ? View.VISIBLE : View.GONE;
        if (h.blur.getVisibility() != visibility) h.blur.setVisibility(visibility);
        h.blur.setDark(dark);
        if (visibility == View.VISIBLE) h.blur.invalidate();
    }

    private boolean isScrolled(Header h) {
        View scroller = h.scroller != null ? h.scroller.get() : null;
        if (scroller == null || !scroller.isAttachedToWindow()) {
            scroller = findScroller(h.wrapper);
            h.scroller = scroller != null ? new WeakReference<>(scroller) : null;
        }
        return scroller != null && scroller.canScrollVertically(-1);
    }

    /** The vertically scrolling list in the same page as the header, searched breadth first. */
    private static View findScroller(ViewGroup wrapper) {
        if (!(wrapper.getParent() instanceof ViewGroup)) return null;
        ViewGroup page = (ViewGroup) wrapper.getParent();
        ArrayDeque<View> queue = new ArrayDeque<>();
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child != wrapper && !(child instanceof EdgeBlurView)) queue.add(child);
        }
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 200) {
            View v = queue.poll();
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i));
            }
        }
        return null;
    }

    private static void clearBackground(View v) {
        Drawable bg = v.getBackground();
        if (bg == null) return;
        if (bg instanceof ColorDrawable && Color.alpha(((ColorDrawable) bg).getColor()) == 0) return;
        setBackgroundKeepingPadding(v, null);
    }

    /** setBackground() resets padding to the drawable's; keep the view's layout as it was. */
    private static void setBackgroundKeepingPadding(View v, Drawable background) {
        int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setBackground(background);
        v.setPadding(l, t, r, b);
    }
}
