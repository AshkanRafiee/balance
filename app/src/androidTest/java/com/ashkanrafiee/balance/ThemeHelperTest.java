package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Covers the theme setting: the system-default, round-tripping dark and light, and the wrapper
 *  that forces the matching night/day uiMode so the day-night color resources resolve correctly. */
@RunWith(AndroidJUnit4.class)
public class ThemeHelperTest {

    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences("balance_theme", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        ctx.getSharedPreferences("balance_theme", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void defaultFollowsSystem() {
        assertEquals(ThemeHelper.THEME_SYSTEM, ThemeHelper.theme(ctx));
        assertEquals(ctx, ThemeHelper.wrap(ctx));
    }

    @Test public void darkAndLightRoundTrip() {
        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_DARK);
        assertEquals(ThemeHelper.THEME_DARK, ThemeHelper.theme(ctx));
        assertTrue(ThemeHelper.isDark(ctx));
        assertTrue(isNight(ThemeHelper.wrap(ctx)));

        ThemeHelper.setTheme(ctx, ThemeHelper.THEME_LIGHT);
        assertEquals(ThemeHelper.THEME_LIGHT, ThemeHelper.theme(ctx));
        assertFalse(ThemeHelper.isDark(ctx));
        assertFalse(isNight(ThemeHelper.wrap(ctx)));
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }
}