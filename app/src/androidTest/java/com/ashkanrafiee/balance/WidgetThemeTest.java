package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;

/** Verifies that the home-screen widget follows a theme of its own, by default the app's.
 *
 *  <p>The widget is the one part of Balance this app does not draw: the launcher inflates the layout
 *  in its own process against its own day/night configuration, which knows nothing about either
 *  theme. So every color the app is responsible for has to reach the launcher as a literal value,
 *  and the card background as a drawable that already carries literal colors.
 *
 *  <p>The end-to-end tests therefore apply the built {@link RemoteViews} to a context that has
 *  <em>not</em> been wrapped by {@link ThemeHelper} — that is what the launcher has, and it is the
 *  only way to tell a pushed literal apart from a color that merely happened to resolve correctly
 *  because the test inflated the layout with the app's own theme. */
@RunWith(AndroidJUnit4.class)
public class WidgetThemeTest {

    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clearPrefs();
        clearThemePrefs();
        BalanceData.reset(ctx, false);
    }

    @After public void tearDown() {
        clearPrefs();
        clearThemePrefs();
        BalanceData.reset(ctx, false);
    }

    /** The privacy mask and the staleness threshold both live here and would otherwise leak in from
     *  another test's leftovers. */
    private void clearPrefs() {
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void clearThemePrefs() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_SYSTEM);
        ThemeHelper.setWidgetTheme(ctx, ThemeHelper.WIDGET_FOLLOW_APP);
    }

    /** A context reporting the given night mode, whichever mode the device itself is in, so both
     *  palettes can be read from a test no matter how the emulator is configured. */
    private static Context forced(Context base, boolean night) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int uiMode = config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        uiMode |= night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = uiMode;
        return base.createConfigurationContext(config);
    }

    /** The value the widget is expected to carry for a color. Derived straight from the night mode
     *  rather than through {@link WidgetTheme}, so the expectation does not lean on the code under
     *  test — otherwise a widget that had quietly stopped applying the theme would still pass. */
    private int appColor(int colorRes) {
        return forced(ctx, WidgetTheme.isDark(ctx)).getColor(colorRes);
    }

    private boolean deviceDark() {
        return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int fill(Context base, int drawableRes) {
        return ((GradientDrawable) base.getDrawable(drawableRes)).getColor().getDefaultColor();
    }

    private static int fillOf(View view) {
        return ((GradientDrawable) view.getBackground()).getColor().getDefaultColor();
    }

    /** Inflates a built widget the way a launcher does: with a context that knows nothing about the
     *  theme selected in the app. */
    private View inflate(RemoteViews views) {
        return views.apply(ctx, null);
    }

    private LinkedHashMap<String, Bank> oneBank() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Tejarat", new Bank("Tejarat", 1_000_000, Long.MAX_VALUE, "5000"));
        return map;
    }

    /** The two palettes really do differ, so every assertion below is testing something. */
    @Test public void devicePalettesDiffer() {
        assertNotEquals(forced(ctx, true).getColor(R.color.accent),
            forced(ctx, false).getColor(R.color.accent));
        assertNotEquals(forced(ctx, true).getColor(R.color.widget_bg),
            forced(ctx, false).getColor(R.color.widget_bg));
    }

    /** The widget resolves colors in the app's theme, not the device's. */
    @Test public void context_resolvesColorsInTheSelectedTheme() {
        for (String theme : ThemeHelper.CHOICES) {
            ThemeHelper.setTheme(ctx, theme);
            boolean dark = ThemeHelper.isDark(ctx);
            assertEquals(theme + " accent",
                forced(ctx, dark).getColor(R.color.accent), appColor(R.color.accent));
            assertEquals(theme + " widget card",
                forced(ctx, dark).getColor(R.color.widget_bg), appColor(R.color.widget_bg));
        }
    }

    /** A theme forced against the device is exactly the case that used to be wrong. */
    @Test public void context_differsFromDeviceWhenThemeIsForced() {
        ThemeHelper.setTheme(ctx, deviceDark() ? ThemeHelper.THEME_LIGHT : ThemeHelper.THEME_DARK);

        assertNotEquals("widget still follows the device",
            ctx.getColor(R.color.accent), appColor(R.color.accent));
        assertNotEquals("widget still follows the device",
            ctx.getColor(R.color.widget_bg), appColor(R.color.widget_bg));
    }

    /** The card background is the one color that cannot be pushed as a value, so it is chosen as a
     *  drawable id instead — and only the right one, for both explicit themes. */
    @Test public void background_matchesTheSelectedTheme() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_DARK);
        assertEquals(R.drawable.widget_bg_dark, WidgetTheme.background(ctx));
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_LIGHT);
        assertEquals(R.drawable.widget_bg_light, WidgetTheme.background(ctx));
    }

    /** On "system" the widget still tracks the device, the way the rest of the system does. */
    @Test public void background_followsTheDeviceOnSystem() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_SYSTEM);
        assertEquals(deviceDark() ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light,
            WidgetTheme.background(ctx));
    }

    /** The two card drawables carry literal colors, so a launcher re-resolving them in a
     *  configuration we do not control cannot put its own palette back. This is the invariant the
     *  whole approach rests on. */
    @Test public void cardDrawablesAreLiteralNotThemeQualified() {
        int darkBg = forced(ctx, true).getColor(R.color.widget_bg);
        int lightBg = forced(ctx, false).getColor(R.color.widget_bg);
        assertNotEquals(darkBg, lightBg);

        // Read the dark card from a light context and the light card from a dark one.
        assertEquals(darkBg, fill(forced(ctx, false), R.drawable.widget_bg_dark));
        assertEquals(lightBg, fill(forced(ctx, true), R.drawable.widget_bg_light));
    }

    /** And they stay in step with the app's own palette, so a palette change cannot leave the
     *  widget's card behind. */
    @Test public void cardDrawablesTrackTheAppPalette() {
        for (String theme : new String[]{ThemeHelper.THEME_DARK, ThemeHelper.THEME_LIGHT}) {
            ThemeHelper.setTheme(ctx, theme);
            assertEquals(theme + " card", appColor(R.color.widget_bg),
                fill(ctx, WidgetTheme.background(ctx)));
        }
    }

    /** The card on the locked widget, applied the way a launcher applies it. */
    @Test public void lockedWidget_carriesTheAppCard() {
        for (String theme : new String[]{ThemeHelper.THEME_DARK, ThemeHelper.THEME_LIGHT}) {
            ThemeHelper.setTheme(ctx, theme);
            View root = inflate(BalanceWidgetProvider.lockedViews(ctx));
            assertEquals(theme + " card", appColor(R.color.widget_bg), fillOf(root));
            assertEquals(theme + " message", appColor(R.color.muted),
                ((TextView) root.findViewById(R.id.widget_locked_msg)).getCurrentTextColor());
        }
    }

    /** The main widget's total, unit and permission hint. */
    @Test public void mainWidget_carriesTheAppText() {
        for (String theme : new String[]{ThemeHelper.THEME_DARK, ThemeHelper.THEME_LIGHT}) {
            ThemeHelper.setTheme(ctx, theme);
            BalanceData.write(ctx, oneBank());
            View root = inflate(BalanceWidgetProvider.buildViews(ctx));

            assertEquals(theme + " card", appColor(R.color.widget_bg), fillOf(root));
            assertEquals(theme + " total", appColor(R.color.accent),
                ((TextView) root.findViewById(R.id.widget_total)).getCurrentTextColor());
            assertEquals(theme + " unit", appColor(R.color.muted),
                ((TextView) root.findViewById(R.id.widget_unit)).getCurrentTextColor());
            assertEquals(theme + " hint", appColor(R.color.muted),
                ((TextView) root.findViewById(R.id.widget_hint)).getCurrentTextColor());
        }
    }

    /** A list row: the bank name, the account line and the amount, plus the stale override. */
    @Test public void listRow_carriesTheAppText() {
        for (String theme : new String[]{ThemeHelper.THEME_DARK, ThemeHelper.THEME_LIGHT}) {
            ThemeHelper.setTheme(ctx, theme);
            Bank fresh = new Bank("Tejarat", 1_000_000, Long.MAX_VALUE, "5000", "1110000222");
            View row = inflate(BalanceWidgetService.bankViews(ctx, fresh));

            assertEquals(theme + " bank name", appColor(R.color.bank_name),
                ((TextView) row.findViewById(R.id.bank_name)).getCurrentTextColor());
            assertEquals(theme + " account", appColor(R.color.muted),
                ((TextView) row.findViewById(R.id.bank_account)).getCurrentTextColor());
            assertEquals(theme + " amount", appColor(R.color.muted),
                ((TextView) row.findViewById(R.id.bank_amount)).getCurrentTextColor());
        }
    }

    /** A stale amount is amber in whichever theme is showing, not the device's amber. */
    @Test public void listRow_staleAmountUsesTheAppWarnColor() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_SYSTEM);
        long now = System.currentTimeMillis();
        for (boolean stale : new boolean[]{true, false}) {
            // isStale reads date <= 0 as "unknown", not old, so a real past stamp is needed.
            long date = stale ? now - 30L * 86400000L : now;
            View row = inflate(BalanceWidgetService.bankViews(ctx,
                new Bank("Tejarat", 1_000_000, date, "5000")));
            int expected = stale ? appColor(R.color.warn) : appColor(R.color.muted);
            assertEquals("stale=" + stale, expected,
                ((TextView) row.findViewById(R.id.bank_amount)).getCurrentTextColor());
        }
    }

    /** The "no balances yet" row. */
    @Test public void emptyRow_carriesTheAppText() {
        for (String theme : new String[]{ThemeHelper.THEME_DARK, ThemeHelper.THEME_LIGHT}) {
            ThemeHelper.setTheme(ctx, theme);
            View row = inflate(BalanceWidgetService.emptyViews(ctx));
            assertEquals(theme + " empty", appColor(R.color.empty),
                ((TextView) row.findViewById(R.id.widget_empty)).getCurrentTextColor());
        }
    }

    /** The locked list entry, which the factory builds separately from the locked widget. */
    @Test public void lockedRow_carriesTheAppCard() {
        for (String theme : new String[]{ThemeHelper.THEME_DARK, ThemeHelper.THEME_LIGHT}) {
            ThemeHelper.setTheme(ctx, theme);
            View root = inflate(BalanceWidgetService.lockedViews(ctx));
            assertEquals(theme + " card", appColor(R.color.widget_bg), fillOf(root));
            assertEquals(theme + " message", appColor(R.color.muted),
                ((TextView) root.findViewById(R.id.widget_locked_msg)).getCurrentTextColor());
        }
    }

    /** No surface may quietly keep the device's palette under a forced theme — the regression that
     *  left the widget light on a dark phone. */
    @Test public void noSurfaceFallsBackToTheDevicePalette() {
        ThemeHelper.setTheme(ctx, deviceDark() ? ThemeHelper.THEME_LIGHT : ThemeHelper.THEME_DARK);
        BalanceData.write(ctx, oneBank());
        String theme = ThemeHelper.theme(ctx);

        assertNotEquals(theme + " locked card", ctx.getColor(R.color.widget_bg),
            fillOf(inflate(BalanceWidgetProvider.lockedViews(ctx))));
        assertNotEquals(theme + " main card", ctx.getColor(R.color.widget_bg),
            fillOf(inflate(BalanceWidgetProvider.buildViews(ctx))));
        assertNotEquals(theme + " locked row card", ctx.getColor(R.color.widget_bg),
            fillOf(inflate(BalanceWidgetService.lockedViews(ctx))));

        View main = inflate(BalanceWidgetProvider.buildViews(ctx));
        assertNotEquals(theme + " total", ctx.getColor(R.color.accent),
            ((TextView) main.findViewById(R.id.widget_total)).getCurrentTextColor());
        assertNotEquals(theme + " unit", ctx.getColor(R.color.muted),
            ((TextView) main.findViewById(R.id.widget_unit)).getCurrentTextColor());
        assertNotEquals(theme + " hint", ctx.getColor(R.color.muted),
            ((TextView) main.findViewById(R.id.widget_hint)).getCurrentTextColor());

        View row = inflate(BalanceWidgetService.bankViews(ctx,
            new Bank("Tejarat", 1_000_000, Long.MAX_VALUE, "5000", "1110000222")));
        assertNotEquals(theme + " bank name", ctx.getColor(R.color.bank_name),
            ((TextView) row.findViewById(R.id.bank_name)).getCurrentTextColor());
        assertNotEquals(theme + " account", ctx.getColor(R.color.muted),
            ((TextView) row.findViewById(R.id.bank_account)).getCurrentTextColor());
        assertNotEquals(theme + " amount", ctx.getColor(R.color.muted),
            ((TextView) row.findViewById(R.id.bank_amount)).getCurrentTextColor());
        assertNotEquals(theme + " empty", ctx.getColor(R.color.empty),
            ((TextView) inflate(BalanceWidgetService.emptyViews(ctx))
                .findViewById(R.id.widget_empty)).getCurrentTextColor());
    }

    /** A widget rebuilt after a theme switch carries the new palette, which is what makes the
     *  repaint on selection work. */
    @Test public void rebuiltWidget_followsTheNewTheme() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_DARK);
        int darkTotal = ((TextView) inflate(BalanceWidgetProvider.buildViews(ctx))
            .findViewById(R.id.widget_total)).getCurrentTextColor();

        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_LIGHT);
        int lightTotal = ((TextView) inflate(BalanceWidgetProvider.buildViews(ctx))
            .findViewById(R.id.widget_total)).getCurrentTextColor();

        assertNotEquals(darkTotal, lightTotal);
    }

    // ---- the widget's own theme setting ----

    /** The app's theme and the widget's, over every combination the Display menu can hold. */
    private static boolean expectedWidgetDark(String app, String widget, boolean deviceDark) {
        if (ThemeHelper.WIDGET_FOLLOW_APP.equals(widget))
            return ThemeHelper.THEME_DARK.equals(app)
                || (ThemeHelper.THEME_SYSTEM.equals(app) && deviceDark);
        if (ThemeHelper.THEME_DARK.equals(widget)) return true;
        if (ThemeHelper.THEME_LIGHT.equals(widget)) return false;
        return deviceDark;
    }

    /** An install that has never touched the setting follows the app, so it cannot start out
     *  disagreeing with the screens it sits next to. */
    @Test public void widgetTheme_defaultsToFollowingTheApp() {
        assertEquals(ThemeHelper.WIDGET_FOLLOW_APP, ThemeHelper.widgetTheme(ctx));
        for (String app : ThemeHelper.CHOICES) {
            ThemeHelper.setTheme(ctx, app);
            assertEquals("app " + app, ThemeHelper.isDark(ctx), WidgetTheme.isDark(ctx));
        }
    }

    /** Every combination of the two settings resolves the way the picker words promise. */
    @Test public void widgetTheme_resolvesEveryCombination() {
        for (String app : ThemeHelper.CHOICES) {
            for (String widget : ThemeHelper.WIDGET_CHOICES) {
                ThemeHelper.setTheme(ctx, app);
                ThemeHelper.setWidgetTheme(ctx, widget);
                assertEquals("app " + app + ", widget " + widget,
                    expectedWidgetDark(app, widget, deviceDark()), WidgetTheme.isDark(ctx));
            }
        }
    }

    /** The point of the setting: the widget can be light while the app is dark, and the card really
     *  does come out light, not merely the text. */
    @Test public void widgetTheme_canDisagreeWithTheApp() {
        String appTheme = deviceDark() ? ThemeHelper.THEME_DARK : ThemeHelper.THEME_LIGHT;
        String widgetTheme = deviceDark() ? ThemeHelper.THEME_LIGHT : ThemeHelper.THEME_DARK;
        ThemeHelper.setTheme(ctx, appTheme);
        ThemeHelper.setWidgetTheme(ctx, widgetTheme);
        BalanceData.write(ctx, oneBank());

        View root = inflate(BalanceWidgetProvider.buildViews(ctx));
        assertEquals("card follows the widget, not the app",
            forced(ctx, WidgetTheme.isDark(ctx)).getColor(R.color.widget_bg), fillOf(root));
        assertEquals("total follows the widget, not the app",
            forced(ctx, WidgetTheme.isDark(ctx)).getColor(R.color.accent),
            ((TextView) root.findViewById(R.id.widget_total)).getCurrentTextColor());
        assertNotEquals("test needs a disagreement to be meaningful",
            ThemeHelper.isDark(ctx), WidgetTheme.isDark(ctx));
    }

    /** ...and the rows obey it too, not just the card and the total. */
    @Test public void widgetTheme_appliesToListRows() {
        ThemeHelper.setTheme(ctx, deviceDark() ? ThemeHelper.THEME_DARK : ThemeHelper.THEME_LIGHT);
        ThemeHelper.setWidgetTheme(ctx, deviceDark() ? ThemeHelper.THEME_LIGHT : ThemeHelper.THEME_DARK);

        View row = inflate(BalanceWidgetService.bankViews(ctx,
            new Bank("Tejarat", 1_000_000, System.currentTimeMillis(), "5000", "1110000222")));
        assertEquals(forced(ctx, WidgetTheme.isDark(ctx)).getColor(R.color.bank_name),
            ((TextView) row.findViewById(R.id.bank_name)).getCurrentTextColor());
    }

    /** Pointing the widget elsewhere changes only the widget: the app's own theme is untouched, so
     *  the screen does not have to be redrawn. */
    @Test public void widgetTheme_leavesTheAppThemeAlone() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_DARK);
        ThemeHelper.setWidgetTheme(ctx, ThemeHelper.THEME_LIGHT);

        assertEquals(ThemeHelper.THEME_DARK, ThemeHelper.theme(ctx));
        assertNotEquals(ThemeHelper.isDark(ctx), WidgetTheme.isDark(ctx));
    }

    /** Changing only the widget theme repaints the widget, the same way changing the app's does. */
    @Test public void rebuiltWidget_followsANewWidgetTheme() {
        ThemeHelper.setWidgetTheme(ctx, ThemeHelper.THEME_DARK);
        int darkCard = fillOf(inflate(BalanceWidgetProvider.lockedViews(ctx)));

        ThemeHelper.setWidgetTheme(ctx, ThemeHelper.THEME_LIGHT);
        int lightCard = fillOf(inflate(BalanceWidgetProvider.lockedViews(ctx)));

        assertNotEquals(darkCard, lightCard);
    }
}
