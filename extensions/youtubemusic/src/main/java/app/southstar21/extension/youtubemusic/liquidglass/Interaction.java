package app.southstar21.extension.youtubemusic.liquidglass;

import android.os.SystemClock;

/**
 * Whether what's behind the glass can be moving. Glass only needs redrawing then, and in YouTube
 * Music that's while a finger is down or content is scrolling (a fling included). Redrawing the
 * glass every frame instead (blur and refraction each time) kept the app drawing nonstop and made
 * the phone hot. A fixed window after each touch kept it drawing long after the content stopped.
 */
final class Interaction {
    /**
     * How long after the last scroll the glass still refreshes. The scroll listener runs while a
     * frame is drawn, after the glass has been prepared, so a scroll is seen one frame late: the
     * tail makes sure the final, settled frame is refreshed too.
     */
    private static final long SCROLL_TAIL_MS = 60;

    private static volatile long lastTouch;
    private static volatile long lastScroll;
    private static volatile boolean down;

    private Interaction() {
    }

    static void touched() {
        lastTouch = SystemClock.uptimeMillis();
    }

    /** A finger went down on or came up from the window. */
    static void fingerDown(boolean isDown) {
        down = isDown;
    }

    /** The content scrolled (the window's scroll listener). */
    static void scrolled() {
        lastScroll = SystemClock.uptimeMillis();
    }

    /** Whether content may be moving at {@code now} (uptime ms): a finger is down, or it scrolled just now. */
    static boolean moving(long now) {
        return down || now - lastScroll < SCROLL_TAIL_MS;
    }
}
