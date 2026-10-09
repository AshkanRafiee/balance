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

/** The "expand all history" preference: an off-by-default boolean that opens every
 *  year, month and day of the history breakdown instead of only the current year, month and its days.
 *  It used to live in the Display menu and now lives in the hidden debug menu. */
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
        assertFalse(BalanceData.getExpandAllHistory(ctx));
    }

    @Test public void expandAllHistory_persistsOnAndOff() {
        BalanceData.setExpandAllHistory(ctx, true);
        assertTrue(BalanceData.getExpandAllHistory(ctx));
        BalanceData.setExpandAllHistory(ctx, false);
        assertFalse(BalanceData.getExpandAllHistory(ctx));
    }

    @Test public void migrateExpandAllToDebugMenu_turnsOffALefoverOptInOnce() {
        // A user who had expand-all on before it moved to the debug menu gets the fast
        // default back; re-enabling afterwards (via the debug menu) is respected.
        BalanceData.setExpandAllHistory(ctx, true);
        BalanceData.migrateExpandAllToDebugMenu(ctx);
        assertFalse(BalanceData.getExpandAllHistory(ctx));
        BalanceData.setExpandAllHistory(ctx, true);
        BalanceData.migrateExpandAllToDebugMenu(ctx);
        assertTrue(BalanceData.getExpandAllHistory(ctx));
    }
}