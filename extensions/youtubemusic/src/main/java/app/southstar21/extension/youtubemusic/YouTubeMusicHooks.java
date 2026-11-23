package app.southstar21.extension.youtubemusic;

import android.app.Activity;
import android.util.Log;

import app.southstar21.extension.youtubemusic.liquidglass.LiquidGlassNavBarPatch;
import app.southstar21.extension.youtubemusic.settings.Settings;
import app.southstar21.extension.youtubemusic.settings.SettingsButton;
import app.southstar21.extension.youtubemusic.settings.WelcomeBanner;

/**
 * Shared entry point, called from MusicActivity.onWindowFocusChanged by the shared extension
 * patch. Starts each included patch.
 */
@SuppressWarnings("unused")
public final class YouTubeMusicHooks {
    public static final String TAG = "southstar21";

    private YouTubeMusicHooks() {
    }

    public static void onMainActivityWindowFocusChanged(Activity activity) {
        try {
            Settings.init(activity);
            if (LiquidGlassNavBarPatch.isIncluded()) {
                LiquidGlassNavBarPatch.onMainActivityReady(activity);
                // The menu only holds the glass's settings so far.
                SettingsButton.ensureInstalled(activity);
                // Once ever: thanks, where the settings are, and how to get the full glass.
                WelcomeBanner.maybeShow(activity);
            }
        } catch (Throwable t) {
            // Never take the app down over a patch.
            Log.e(TAG, "Main activity hook failed", t);
        }
    }
}
