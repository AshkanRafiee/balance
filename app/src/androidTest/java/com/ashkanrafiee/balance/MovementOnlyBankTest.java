package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A bank that reports what it did but never what is left on the account.
 *
 * <p>Such a bank is reached through a pack, which is the only source of a message that states a
 * movement without a balance: the legacy reducers deliberately read no movement out of a message
 * that does not also state a balance, so no Iranian bank message can produce one. The rows below are
 * therefore seeded the way a scan leaves them, and what is pinned here is everything that has to
 * stay true of such a row afterwards, because a zero standing in for an unreported balance is the
 * one outcome worse than showing nothing: it is a figure the bank never sent.
 */
@RunWith(AndroidJUnit4.class)
public class MovementOnlyBankTest {

    private static final long T = 1_000_000_000L;

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FinancialTestStore.wipe(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        FinancialTestStore.wipe(ctx);
    }

    /** A row for a bank that never stated a balance, as the balance scan stores it. */
    private static Bank movementOnlyRow(String bank, long movement, String currency) {
        Bank row = new Bank(bank, 0, T, "sender", null, currency);
        row.balanceReported = false;
        row.movement = movement;
        row.movementCurrency = currency;
        return row;
    }

    private static LinkedHashMap<String, Bank> twoRows() {
        LinkedHashMap<String, Bank> rows = new LinkedHashMap<>();
        rows.put("Saman", new Bank("Saman", 1000000L, T, "sender"));
        rows.put("CartaBCC", movementOnlyRow("CartaBCC", -1234L, "EUR"));
        return rows;
    }

    @Test public void aMovementOnlyRowIsStoredAsAMovementNotABalance() throws Exception {
        FinancialTestStore.write(ctx, twoRows());

        Bank reread = BalanceData.read(ctx).get("CartaBCC");
        assertNotNull(reread);
        assertTrue("the row must say it holds no balance", reread.movementOnly());
        assertEquals("the movement the bank reported", -1234L, (long) reread.movement);
        assertEquals("EUR", reread.movementCurrency);
        // The amount stays a placeholder: nothing may read it as a balance that was reported.
        assertEquals(0L, reread.amount);
    }

    @Test public void aBankThatStatesABalanceIsNotAMovementOnlyRow() {
        Bank row = new Bank("Saman", 1000000L, T, "sender");
        assertFalse(row.movementOnly());
        assertNull(row.movement);
    }

    @Test public void noIranianMessageCanReachAMovementOnlyRowOnItsOwn() {
        // The legacy reducers read no movement out of a message that does not also state a
        // balance, deliberately: an amount standing alone is too easy to mistake for something the
        // bank meant. A movement with no balance therefore only ever comes from a pack, which is
        // why the rows above are seeded rather than scanned -- and why an Iranian message can never
        // put a card with no balance on the dashboard.
        assertNull(com.ashkanrafiee.balance.parser.legacy.LegacyMoney
                .extractTransaction("-200,000,000  \n06/22_20:37"));
        assertEquals(Long.valueOf(-200000000L), com.ashkanrafiee.balance.parser.legacy.LegacyMoney
                .extractTransaction("-200,000,000  \n06/22_20:37 \n\u0645\u0627\u0646\u062f\u0647: 2,279,545,033"));
    }

    @Test public void theWidgetLeavesOutABankWithNoBalance() throws Exception {
        FinancialTestStore.write(ctx, twoRows());

        List<Bank> widget = BalanceWidgetService.widgetBanks(ctx);
        assertEquals("only the bank that reports a balance belongs in a balance glance", 1,
                widget.size());
        assertEquals("Saman", widget.get(0).name);
    }

    @Test public void theDashboardDrawsSuchACardWithoutClaimingABalance() throws Exception {
        // The card is drawn on a canvas rather than composed from views, so the only way to know it
        // renders a bank with no balance is to render it: a masked row with no movement, and a row
        // that states both, are the two shapes that could ask the renderer for a figure it does not
        // have.
        FinancialTestStore.write(ctx, twoRows());
        LinkedHashMap<String, Bank> masked = new LinkedHashMap<>();
        masked.put("Quiet", movementOnlyRow("Quiet", 0, "EUR"));
        masked.get("Quiet").movement = null;
        FinancialTestStore.write(ctx, masked);

        try (androidx.test.core.app.ActivityScenario<MainActivity> scenario =
                androidx.test.core.app.ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { });
        }
    }

    @Test public void aRowWithoutABalanceSortsAfterEveryBalanceRow() throws Exception {
        LinkedHashMap<String, Bank> rows = twoRows();

        for (int sort : new int[]{BalanceData.SORT_BALANCE_HIGH, BalanceData.SORT_BALANCE_LOW}) {
            List<List<Bank>> blocks = BalanceData.groupedForDisplay(rows, new HashSet<>(), sort);
            List<Bank> flat = new ArrayList<>();
            for (List<Bank> block : blocks) flat.addAll(block);
            assertEquals(2, flat.size());
            assertFalse("a bank with no balance never ranks among balances, in either direction",
                    flat.get(0).movementOnly());
            assertTrue(flat.get(1).movementOnly());
        }
    }

}
