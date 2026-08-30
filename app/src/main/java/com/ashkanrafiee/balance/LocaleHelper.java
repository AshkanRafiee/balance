package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
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
    }

    /** Wraps a base Context so its resources resolve using the saved language override, if any. */
    public static Context wrap(Context base) {
        String tag = currentTag(base);
        if (tag == null || tag.isEmpty()) return base;
        Locale locale = new Locale(tag);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList localeList = new LocaleList(locale);
            LocaleList.setDefault(localeList);
            config.setLocales(localeList);
        } else {
            Locale.setDefault(locale);
            config.locale = locale;
        }
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
