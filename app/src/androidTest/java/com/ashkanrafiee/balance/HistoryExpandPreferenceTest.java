package com.ashkanrafiee.balance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The Display-menu "expand all history" preference: a boolean (off by default) that opens every
 *  year, month and day of the history breakdown instead of only the current year, month and its days. */
@RunWith(AndroidJUnit4.class)
public class HistoryExpandPreferenceTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void expandAllHistory_freshInstall_defaultsToOff() {
        // The history is unbounded, so expanding all of it means building a view per transaction and
        // a header per group before the screen can open. The freshest history is visible either way,
        // because the screen seeds the current year, month and day open.
        assertFalse(BalanceData.getExpandAllHistory(ctx));
    }

    @Test public void expandAllHistory_aChoiceAlreadyMade_isKept() {
        // Turning it on must survive the default changing, or a user who asked for the full
        // breakdown would silently get the collapsed one back on every launch.
        BalanceData.setExpandAllHistory(ctx, true);
        assertTrue(BalanceData.getExpandAllHistory(ctx));
    }

    @Test public void expandAllHistory_persistsOnAndOff() {
        BalanceData.setExpandAllHistory(ctx, true);
        assertTrue(BalanceData.getExpandAllHistory(ctx));
        BalanceData.setExpandAllHistory(ctx, false);
        assertFalse(BalanceData.getExpandAllHistory(ctx));
    }
}