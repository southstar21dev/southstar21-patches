package app.southstar21.extension.youtubemusic.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The settings of the southstar21 Patches menu, stored in YouTube Music's own preferences.
 * Values read while drawing are kept in fields, so a frame never waits on the preferences.
 * Whoever changes a setting redraws what shows it (see SettingsMenu).
 */
public final class Settings {
    private static final String PREFERENCES = "southstar21_patches";
    private static final String KEY_LIGHTWEIGHT_GLASS = "liquid_glass_lightweight";
    private static final String KEY_WELCOME_SHOWN = "welcome_shown";

    private static SharedPreferences preferences;
    private static volatile boolean lightweightGlass = true;

    private Settings() {
    }

    /** Loads the stored values. Safe to call again. */
    public static void init(Context context) {
        if (preferences != null) return;
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        lightweightGlass = preferences.getBoolean(KEY_LIGHTWEIGHT_GLASS, true);
    }

    /**
     * Lightweight glass: one blur and painted shading everywhere, no refraction shaders, no
     * edge blur. Off: the full Liquid Glass effect. On by default.
     */
    public static boolean lightweightGlass() {
        return lightweightGlass;
    }

    /**
     * Whether the welcome (see {@link WelcomeBanner}) was shown already. Not a setting: resetting
     * the settings leaves it alone. True while the preferences aren't loaded, so it never shows then.
     */
    static boolean welcomeShown() {
        return preferences == null || preferences.getBoolean(KEY_WELCOME_SHOWN, false);
    }

    static void setWelcomeShown() {
        if (preferences != null) preferences.edit().putBoolean(KEY_WELCOME_SHOWN, true).apply();
    }

    public static void setLightweightGlass(boolean on) {
        if (lightweightGlass == on) return;
        lightweightGlass = on;
        if (preferences != null) preferences.edit().putBoolean(KEY_LIGHTWEIGHT_GLASS, on).apply();
    }
}
