package app.southstar21.extension.youtubemusic.liquidglass;

import android.app.Activity;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.WeakHashMap;

/**
 * Liquid Glass patch for YouTube Music: entry point. The patch options are baked into the
 * methods below; there's no settings menu in YouTube Music yet.
 */
@SuppressWarnings("unused")
public final class LiquidGlassNavBarPatch {
    static final String TAG = "LiquidGlass";

    private static final WeakHashMap<View, LiquidGlassController> installed = new WeakHashMap<>();

    /** Set once the glass bar is up: from then on the content runs on under the bar. */
    private static volatile boolean contentBehindBar;

    /** The patch makes this return true. */
    public static boolean isIncluded() {
        return false;
    }

    // The methods below are overwritten by the patch with the chosen option values.

    public static int blurRadiusDp() {
        return 4;
    }

    public static int refractionPercent() {
        return 100;
    }

    public static boolean southstar21Style() {
        return true;
    }

    /**
     * Called by the patch from WatchWhileLayout.onMeasure, which sizes the content (everything
     * but the player) to the screen less the tab bar. With the glass bar up the content keeps
     * the full height instead, so it scrolls on behind the glass.
     *
     * @param withoutBar the height YouTube Music wants: {@code full} less the bar's height
     * @param full       the whole layout's height
     */
    public static int contentHeight(int withoutBar, int full) {
        return contentBehindBar ? full : withoutBar;
    }

    /** Called by YouTubeMusicHooks whenever the main activity gains or loses focus. */
    public static void onMainActivityReady(Activity activity) {
        try {
            View bar = findView(activity, "pivot_bar");
            if (!(bar instanceof ViewGroup) || installed.containsKey(bar)) return;
            if (!(bar.getParent() instanceof ViewGroup)) return;

            LiquidGlassController controller = new LiquidGlassController((ViewGroup) bar, null, true);
            controller.install();
            installed.put(bar, controller);
            contentBehindBar = true;
            View layout = findView(activity, "watch_while_layout");
            if (layout != null) layout.requestLayout();
            installMiniPlayer(activity);
            Log.d(TAG, "Installed on " + bar);
        } catch (Throwable t) {
            // Never take the app down over a cosmetic patch.
            Log.e(TAG, "Failed to install liquid glass tab bar", t);
        }
    }

    /** The mini player above the bar gets the same glass. */
    private static void installMiniPlayer(Activity activity) {
        try {
            View page = findView(activity, "player_page");
            int miniPlayerId = id(activity, "mini_player");
            if (!(page instanceof ViewGroup) || miniPlayerId == 0) return;
            new MiniPlayerGlass((ViewGroup) page, miniPlayerId, id(activity, "watch_fragment_container")).install();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install mini player glass", t);
        }
    }

    private static int id(Activity activity, String name) {
        Resources res = activity.getResources();
        // Resources keep the original package name even if the app was renamed (GmsCore support).
        int id = res.getIdentifier(name, "id", "com.google.android.apps.youtube.music");
        return id != 0 ? id : res.getIdentifier(name, "id", activity.getPackageName());
    }

    static View findView(Activity activity, String name) {
        int id = id(activity, name);
        return id == 0 ? null : activity.findViewById(id);
    }
}
