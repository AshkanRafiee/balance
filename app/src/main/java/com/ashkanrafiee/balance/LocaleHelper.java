package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Persists and applies the user's chosen app language, independent of the
 * app's other preference files so a single key controls it everywhere.
 *
 * An empty tag means "follow the system language" (auto-detect): {@link #wrap}
 * then returns the base context untouched, so resource resolution falls back
 * to whatever the device's own locale list already picks.
 */
public final class LocaleHelper {
    private static final String PREFS = "balance_language";
    private static final String KEY_LANGUAGE = "language";

    /** "" is the "system default" entry; keep it first so it's the default selection. */
    public static final String[] SUPPORTED = {"", "en", "fa"};

    private LocaleHelper() {}

    public static String currentTag(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "");
    }

    public static void setLanguage(Context context, String tag) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, tag).apply();
        BalanceWidgetProvider.push(context);
    }

    /** Whether the effective app language is Persian — the explicit fa override, or (when following the
     *  system) a device whose primary language is Persian. Drives Persian-digit rendering so every
     *  value (amounts, dates, counts) uses the same digit style in both cases. */
    public static boolean isPersian(Context context) {
        String tag = currentTag(context);
        if ("fa".equals(tag)) return true;
        if (tag == null || tag.isEmpty()) {
            Locale device = context.getResources().getConfiguration().getLocales().get(0);
            return device != null && "fa".equals(device.getLanguage());
        }
        return false;
    }

    /** Wraps a base Context so its resources resolve using the saved language override, if any.
     *
     *  The process default locale is kept in step with the choice in *both* directions, because it
     *  is the fallback for everything a context does not answer for itself: a view that resolves
     *  its text direction from the locale, and every {@code Calendar.getInstance(Locale.getDefault())}
     *  that prints a month name. It is also a process-lifetime static, so an explicit choice that
     *  was never undone would go on deciding the direction and the month names of every screen
     *  until the process died — which is what "fixed after I force-stopped the app" looks like.
     *  Coming back to "follow the system" therefore puts the device's own locale back, rather than
     *  leaving the previous language's behind. */
    public static Context wrap(Context base) {
        String tag = currentTag(base);
        LocaleList system = base.getResources().getConfiguration().getLocales();
        if (tag == null || tag.isEmpty()) {
            if (!LocaleList.getDefault().equals(system)) LocaleList.setDefault(system);
            return base;
        }
        LocaleList localeList = new LocaleList(new Locale(tag));
        LocaleList.setDefault(localeList);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocales(localeList);
        return base.createConfigurationContext(config);
    }

    /** Native-script display name for a supported language tag (never the empty "system default" tag). */
    public static String displayName(String tag) {
        switch (tag) {
            case "en": return "English";
            case "fa": return "فارسی";
            default: return tag;
        }
    }
}
