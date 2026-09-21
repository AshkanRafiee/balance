package com.ashkanrafiee.balance;

import android.content.Context;

/**
 * Persists and reads the user's chosen calendar region, kept in its own preference file (like the
 * language in {@link LocaleHelper}) so a single region key controls the whole app.
 *
 * <p>The region is the app-wide settings knob that decides which calendar system history
 * screens use: {@code Iran} maps to the Persian (Jalali) calendar, {@code International} to the
 * Gregorian calendar. The default is International; users who install for the Iranian market can
 * switch to Iran for the Persian calendar.
 */
public final class RegionHelper {
    public static final int REGION_IRAN = 1;
    public static final int REGION_INTERNATIONAL = 2;

    private static final String PREFS = "balance_region";
    private static final String KEY_REGION = "region";
    private static final String VALUE_IRAN = "ir";
    private static final String VALUE_INTERNATIONAL = "int";

    private RegionHelper() {}

    public static int region(Context context) {
        String v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REGION, VALUE_INTERNATIONAL);
        return VALUE_INTERNATIONAL.equals(v) ? REGION_INTERNATIONAL : REGION_IRAN;
    }

    /** Whether the calendar should be the Persian one (the Iran region). */
    public static boolean isIran(Context context) {
        return region(context) == REGION_IRAN;
    }

    public static void setRegion(Context context, int region) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_REGION, region == REGION_INTERNATIONAL
                ? VALUE_INTERNATIONAL : VALUE_IRAN).apply();
    }
}