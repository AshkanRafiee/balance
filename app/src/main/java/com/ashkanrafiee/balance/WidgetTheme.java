package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Makes the home-screen widget follow a color theme of its own, by default the one picked in the app.
 *
 * <p>The widget is the one part of Balance this app does not get to draw: the launcher inflates the
 * layout in its own process, against its own day/night configuration, which knows nothing about
 * either theme. So a widget whose layout says {@code @color/accent} renders in the *device's*
 * palette, and a user who forced dark on a light phone gets a light widget — or a dark card with
 * light text, once colors are pushed but the card is not.
 *
 * <p>Two rules follow, and the whole widget relies on them:
 * <ul>
 *   <li>Colors the app chooses are resolved here, in this process, where the widget's theme is
 *       known, and pushed to the launcher as literal values via {@code setTextColor} — a resource id
 *       would be re-resolved by the launcher and lose the theme again.</li>
 *   <li>The card background cannot be pushed as a color, so it is picked as a resource id instead:
 *       {@link #background} chooses between two drawables that carry literal colors, so whichever
 *       the launcher inflates is already the right palette.</li>
 * </ul>
 */
final class WidgetTheme {

    private WidgetTheme() { }

    /**
     * Whether the widget renders dark. Follows the app's own theme unless the user pointed the
     * widget somewhere else, in which case that choice wins — which is what makes "a dark app with
     * a light widget" possible on purpose rather than by accident.
     */
    static boolean isDark(Context context) {
        String widget = ThemeHelper.widgetTheme(context);
        if (ThemeHelper.WIDGET_FOLLOW_APP.equals(widget)) return ThemeHelper.isDark(context);
        if (ThemeHelper.THEME_DARK.equals(widget)) return true;
        if (ThemeHelper.THEME_LIGHT.equals(widget)) return false;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The context every widget color must be resolved from: the widget's own theme applied first,
     * then the app's language, so both overrides the app applies to its own activities reach the
     * widget's rendering decisions too.
     *
     * <p>It sets the night mode outright rather than delegating to {@link ThemeHelper#wrap}, because
     * the widget's theme is not always the app's — and because that leaves the common case (the
     * device is already in the mode the widget wants) as a plain no-op.
     */
    static Context context(Context base) {
        Context localed = LocaleHelper.wrap(base);
        int wanted = isDark(localed) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        Configuration current = localed.getResources().getConfiguration();
        if ((current.uiMode & Configuration.UI_MODE_NIGHT_MASK) == wanted) return localed;
        Configuration config = new Configuration(current);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | wanted;
        return localed.createConfigurationContext(config);
    }

    /**
     * The card background matching the widget's effective theme, as a drawable to hand to
     * {@code setBackgroundResource}.
     */
    static int background(Context base) {
        return isDark(base) ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light;
    }
}
