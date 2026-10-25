package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Covers the selectable theme palettes, including their contrast guarantees and widget bridge. */
@RunWith(AndroidJUnit4.class)
public class ThemeColorHelperTest {
    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences("balance_theme", Context.MODE_PRIVATE).edit().clear().commit();
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_SYSTEM);
    }

    @After public void tearDown() {
        ctx.getSharedPreferences("balance_theme", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void defaultPaletteIsOceanAndInvalidValuesAreSafe() {
        assertEquals(ThemeColorHelper.COLOR_OCEAN, ThemeColorHelper.themeColor(ctx));

        ThemeColorHelper.setThemeColor(ctx, "not-a-palette");
        assertEquals(ThemeColorHelper.COLOR_OCEAN, ThemeColorHelper.themeColor(ctx));
        assertFalse(ThemeColorHelper.themeColor(ctx).equals("not-a-palette"));
    }

    @Test public void everyPaletteRoundTrips() {
        for (String choice : ThemeColorHelper.CHOICES) {
            ThemeColorHelper.setThemeColor(ctx, choice);
            assertEquals(choice, ThemeColorHelper.themeColor(ctx));
        }
    }

    @Test public void everyPaletteKeepsPrimaryTextReadableInBothModes() {
        for (String choice : ThemeColorHelper.CHOICES) {
            ThemeColorHelper.setThemeColor(ctx, choice);
            for (boolean dark : new boolean[]{false, true}) {
                Context themed = forced(ctx, dark);
                int primary = ThemeColorHelper.color(themed, R.color.expressive_primary);
                int onPrimary = ThemeColorHelper.color(themed, R.color.expressive_on_primary);
                int container = ThemeColorHelper.color(themed, R.color.expressive_primary_container);
                int onContainer = ThemeColorHelper.color(themed,
                    R.color.expressive_on_primary_container);

                assertTrue(choice + " primary contrast", contrast(primary, onPrimary) >= 4.5f);
                assertTrue(choice + " container contrast", contrast(container, onContainer) >= 4.5f);
                assertEquals(primary, ThemeColorHelper.color(themed, R.color.accent));
                assertEquals(container, ThemeColorHelper.color(themed, R.color.hero));
            }
        }
    }

    @Test public void customPaletteReachesWidgetAsALiteralColor() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_LIGHT);
        ThemeColorHelper.setThemeColor(ctx, ThemeColorHelper.COLOR_ROSE);

        View root = BalanceWidgetProvider.unreadableViews(ctx).apply(ctx, null);
        int expected = ThemeColorHelper.color(WidgetTheme.context(ctx), R.color.accent);
        assertEquals(expected, ((TextView) root.findViewById(R.id.widget_total))
            .getCurrentTextColor());
    }

    private static Context forced(Context base, boolean dark) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int uiMode = config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        uiMode |= dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = uiMode;
        return base.createConfigurationContext(config);
    }

    private static float contrast(int first, int second) {
        float a = luminance(first);
        float b = luminance(second);
        return (Math.max(a, b) + .05f) / (Math.min(a, b) + .05f);
    }

    private static float luminance(int color) {
        float r = channel(Color.red(color));
        float g = channel(Color.green(color));
        float b = channel(Color.blue(color));
        return .2126f * r + .7152f * g + .0722f * b;
    }

    private static float channel(int value) {
        float scaled = value / 255f;
        return scaled <= .04045f ? scaled / 12.92f
            : (float) Math.pow((scaled + .055f) / 1.055f, 2.4f);
    }
}
