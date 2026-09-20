package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
import java.util.Set;

/** Verifies that the widget mirrors the main app's ordering for included banks and drops excluded
 *  banks entirely, so the widget stays a glanceable summary of the total. */
@RunWith(AndroidJUnit4.class)
public class WidgetOrderTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        BalanceData.reset(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static LinkedHashMap<String, Bank> banks() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));
        map.put("Saman", new Bank("Saman", 2_000_000, 1000L, "5001"));
        map.put("Melli", new Bank("Melli", 3_000_000, 1000L, "5002"));
        return map;
    }

    private static List<String> names(List<Bank> list) {
        List<String> n = new ArrayList<>();
        for (Bank b : list) n.add(b.name);
        return n;
    }

    private static List<String> appIncludedOrder(Set<String> excluded, int mode) {
        List<String> n = new ArrayList<>();
        for (Bank b : BalanceData.orderForDisplay(banks(), excluded, mode)) {
            if (!excluded.contains(b.name)) n.add(b.name);
        }
        return n;
    }

    /** Widget ordering matches the app at every sort mode (after stripping excluded banks). */
    @Test public void widgetBanks_matchesAppForEveryMode() {
        Set<String> excluded = new HashSet<>();
        excluded.add("Saman");
        BalanceData.setExcluded(ctx, excluded);
        BalanceData.write(ctx, banks());

        for (int mode : new int[]{BalanceData.SORT_BALANCE_HIGH, BalanceData.SORT_BALANCE_LOW,
                BalanceData.SORT_DATE_RECENT, BalanceData.SORT_DATE_OLDEST}) {
            BalanceData.setSort(ctx, mode);
            assertEquals("Sort mode " + mode,
                appIncludedOrder(excluded, mode), names(BalanceWidgetService.widgetBanks(ctx)));
        }
    }

    /** Excluded banks never appear in the widget list, no matter the mode. */
    @Test public void widgetBanks_excludedNeverListed() {
        Set<String> excluded = new HashSet<>();
        excluded.add("Melli");
        excluded.add("Tejarat");
        BalanceData.setExcluded(ctx, excluded);
        BalanceData.write(ctx, banks());

        for (int mode : new int[]{BalanceData.SORT_BALANCE_HIGH, BalanceData.SORT_BALANCE_LOW,
                BalanceData.SORT_DATE_RECENT, BalanceData.SORT_DATE_OLDEST}) {
            BalanceData.setSort(ctx, mode);
            for (String name : names(BalanceWidgetService.widgetBanks(ctx))) {
                assertFalse("Excluded " + name + " in mode " + mode, excluded.contains(name));
            }
        }
    }

    /** Widget list is empty when no included banks remain. */
    @Test public void widgetBanks_emptyWhenAllExcludedOrNoData() {
        Set<String> all = new HashSet<>();
        all.add("Tejarat");
        all.add("Saman");
        all.add("Melli");
        BalanceData.setExcluded(ctx, all);
        BalanceData.write(ctx, banks());
        assertEquals(0, BalanceWidgetService.widgetBanks(ctx).size());

        BalanceData.reset(ctx);
        assertEquals(0, BalanceWidgetService.widgetBanks(ctx).size());
    }

    /** A multi-account bank contributes one widget row per account, each with its own balance, so the
     *  widget mirrors the main app's flat per-entry cards. Rows keep the app's order: the bank with
     *  the largest balance first, then that bank's accounts newest first. */
    @Test public void widgetBanks_oneRowPerAccount() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Mellat|1110000222", new Bank("Mellat", 1_000_000, 1000L, "5300", "1110000222"));
        map.put("Mellat|1110000333", new Bank("Mellat", 2_000_000, 2000L, "5300", "1110000333"));
        map.put("Tejarat", new Bank("Tejarat", 5_000_000, 3000L, "5301"));
        BalanceData.write(ctx, map);

        List<Bank> widget = BalanceWidgetService.widgetBanks(ctx);
        assertEquals(3, widget.size());
        assertEquals("Tejarat", widget.get(0).name);       // 5M balance ranks first in balance-high
        assertEquals(5_000_000, widget.get(0).amount);
        assertEquals("Mellat", widget.get(1).name);        // one row per account, own balance
        assertEquals("1110000333", widget.get(1).account);
        assertEquals(2_000_000, widget.get(1).amount);
        assertEquals("Mellat", widget.get(2).name);
        assertEquals("1110000222", widget.get(2).account);
        assertEquals(1_000_000, widget.get(2).amount);
    }
}