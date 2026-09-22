package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the balance-freshness feature: the configurable days-threshold and the helpers that
 *  decide whether a balance is stale and how many days old it looks. */
@RunWith(AndroidJUnit4.class)
public class StaleTest {

    private static final long DAY = 86400000L;
    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    // ---- getStaleDays / setStaleDays persistence ----

    @Test public void getStaleDays_freshInstall_defaultsToSeven() {
        assertEquals(BalanceData.DEFAULT_STALE_DAYS, BalanceData.getStaleDays(ctx));
        assertEquals(7, BalanceData.getStaleDays(ctx));
    }

    @Test public void setStaleDays_persistsAndReadsBack() {
        BalanceData.setStaleDays(ctx, 3);
        assertEquals(3, BalanceData.getStaleDays(ctx));
        BalanceData.setStaleDays(ctx, 30);
        assertEquals(30, BalanceData.getStaleDays(ctx));
    }

    @Test public void setStaleDays_zero_disables() {
        BalanceData.setStaleDays(ctx, 0);
        assertEquals(0, BalanceData.getStaleDays(ctx));
    }

    @Test public void setStaleDays_negative_clampedToOff() {
        BalanceData.setStaleDays(ctx, -5);
        assertEquals(0, BalanceData.getStaleDays(ctx));
    }

    // ---- staleDays: whole days since the last SMS, or 0 when fresh/unknown/off ----

    @Test public void staleDays_countsWholeDaysSinceLastSms() {
        long now = System.currentTimeMillis();
        assertEquals(2, BalanceData.staleDays(ctx, now - 2 * DAY));
        assertEquals(8, BalanceData.staleDays(ctx, now - 8 * DAY));
    }

    @Test public void staleDays_unknownZeroDate_neverCounts() {
        assertEquals(0, BalanceData.staleDays(ctx, 0L));
        assertEquals(0, BalanceData.staleDays(ctx, -1L));
    }

    @Test public void staleDays_warningOff_neverCounts() {
        BalanceData.setStaleDays(ctx, 0);
        long now = System.currentTimeMillis();
        assertEquals(0, BalanceData.staleDays(ctx, now - 100 * DAY));
    }

    // ---- isStale: the actual decision used by the screen, the widget, and the accessibility note ----

    @Test public void isStale_underThreshold_fresh() {
        long now = System.currentTimeMillis();
        assertFalse(BalanceData.isStale(ctx, now - 2 * DAY));
    }

    @Test public void isStale_overThreshold_flags() {
        long now = System.currentTimeMillis();
        assertTrue(BalanceData.isStale(ctx, now - 8 * DAY));
    }

    @Test public void isStale_defaultThreshold_sevenDays() {
        long now = System.currentTimeMillis();
        assertFalse("six days is still fresh by default", BalanceData.isStale(ctx, now - 6 * DAY));
        assertTrue("eight days is past the default window", BalanceData.isStale(ctx, now - 8 * DAY));
    }

    @Test public void isStale_respectsConfiguredThreshold() {
        long now = System.currentTimeMillis();
        BalanceData.setStaleDays(ctx, 3);
        assertFalse(BalanceData.isStale(ctx, now - 2 * DAY));
        assertTrue(BalanceData.isStale(ctx, now - 4 * DAY));
        BalanceData.setStaleDays(ctx, 30);
        assertFalse("a 4-day-old balance is fresh under a 30-day window", BalanceData.isStale(ctx, now - 4 * DAY));
    }

    @Test public void isStale_warningOff_neverFlags_howeverOld() {
        BalanceData.setStaleDays(ctx, 0);
        long now = System.currentTimeMillis();
        assertFalse(BalanceData.isStale(ctx, now - 100 * DAY));
    }

    @Test public void isStale_unknownZeroDate_neverFlags() {
        assertFalse(BalanceData.isStale(ctx, 0L));
        assertFalse(BalanceData.isStale(ctx, -1L));
    }

    @Test public void isStale_futureDate_neverFlags() {
        long now = System.currentTimeMillis();
        assertFalse(BalanceData.isStale(ctx, now + DAY));
    }
}