package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Persists and applies the app's color theme: system (follow the device's dark/light setting),
 * forced dark, or forced light. The app is styled entirely through day/night color resources, so a
 * forced theme only needs the activity's configuration to report the matching {@code uiMode} —
 * {@link #wrap} does that without any dependency, just like {@link LocaleHelper} does for language.
 */
public final class ThemeHelper {
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    /** Order of the picker entries in the Display menu: system, dark, light. */
    public static final String[] CHOICES = {THEME_SYSTEM, THEME_DARK, THEME_LIGHT};

    private static final String PREFS = "balance_theme";
    private static final String KEY_THEME = "theme";

    private ThemeHelper() {}

    /** The stored theme value (one of the {@code THEME_*} constants). Defaults to following the system. */
    public static String theme(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, THEME_SYSTEM);
    }

    public static void setTheme(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).apply();
    }

    /** Whether the effective theme renders dark: the explicit dark choice, or, when following the
     *  system, a device whose own dark mode is active. */
    public static boolean isDark(Context context) {
        String t = theme(context);
        if (THEME_DARK.equals(t)) return true;
        if (THEME_LIGHT.equals(t)) return false;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Wraps a base Context so its resources resolve using the forced dark or light mode, if any.
     *  When the theme follows the system the base context is returned untouched. */
    public static Context wrap(Context base) {
        String t = theme(base);
        if (THEME_SYSTEM.equals(t)) return base;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int uiMode = config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        uiMode |= THEME_DARK.equals(t) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = uiMode;
        return base.createConfigurationContext(config);
    }
}