package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
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
import java.util.Set;

/** Tests for the bank-list sort feature: ordering modes, persistence, and excluded-bank placement. */
@RunWith(AndroidJUnit4.class)
public class SortTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    // ---- getSort / setSort persistence ----

    @Test public void getSort_freshInstall_returnsDefault() {
        assertEquals(BalanceData.SORT_DEFAULT, BalanceData.getSort(ctx));
    }

    @Test public void setSort_persistsAndReadsBack() {
        BalanceData.setSort(ctx, BalanceData.SORT_BALANCE_HIGH);
        assertEquals(BalanceData.SORT_BALANCE_HIGH, BalanceData.getSort(ctx));
        BalanceData.setSort(ctx, BalanceData.SORT_DATE_OLDEST);
        assertEquals(BalanceData.SORT_DATE_OLDEST, BalanceData.getSort(ctx));
    }

    @Test public void setSort_default_clearsToDefault() {
        BalanceData.setSort(ctx, BalanceData.SORT_BALANCE_LOW);
        BalanceData.setSort(ctx, BalanceData.SORT_DEFAULT);
        assertEquals(BalanceData.SORT_DEFAULT, BalanceData.getSort(ctx));
    }

    // ---- orderForDisplay default preserves insertion order ----

    @Test public void orderForDisplay_default_keepsOriginalOrder() {
        assertEquals(names(bankList()),
            names(BalanceData.orderForDisplay(banks(), setOf(), BalanceData.SORT_DEFAULT)));
    }

    // ---- balance sort ----

    @Test public void orderForDisplay_balanceHigh_newestAmountFirst() {
        List<String> expected = new ArrayList<>();
        expected.add("Melli");
        expected.add("Saman");
        expected.add("Tejarat");
        assertEquals(expected,
            names(BalanceData.orderForDisplay(banks(), setOf(), BalanceData.SORT_BALANCE_HIGH)));
    }

    @Test public void orderForDisplay_balanceLow_smallestAmountFirst() {
        List<String> expected = new ArrayList<>();
        expected.add("Tejarat");
        expected.add("Saman");
        expected.add("Melli");
        assertEquals(expected,
            names(BalanceData.orderForDisplay(banks(), setOf(), BalanceData.SORT_BALANCE_LOW)));
    }

    // ---- date sort ----

    @Test public void orderForDisplay_dateRecent_newestFirst() {
        List<String> expected = new ArrayList<>();
        expected.add("Pasargad");
        expected.add("Refah");
        expected.add("Tejarat");
        assertEquals(expected,
            names(BalanceData.orderForDisplay(datedBanks(), setOf(), BalanceData.SORT_DATE_RECENT)));
    }

    @Test public void orderForDisplay_dateOldest_oldestFirst() {
        List<String> expected = new ArrayList<>();
        expected.add("Tejarat");
        expected.add("Refah");
        expected.add("Pasargad");
        assertEquals(expected,
            names(BalanceData.orderForDisplay(datedBanks(), setOf(), BalanceData.SORT_DATE_OLDEST)));
    }

    // ---- excluded banks stay at the bottom in every mode ----

    @Test public void orderForDisplay_excludedStaysLast_whenBalanceHigh() {
        List<String> result = names(BalanceData.orderForDisplay(
            banks(), setOf("Saman"), BalanceData.SORT_BALANCE_HIGH));
        assertEquals("Melli", result.get(0));
        assertEquals("Tejarat", result.get(1));
        assertEquals("Saman", result.get(2));
    }

    @Test public void orderForDisplay_excludedStaysLast_whenDateRecent() {
        List<String> result = names(BalanceData.orderForDisplay(
            datedBanks(), setOf("Refah"), BalanceData.SORT_DATE_RECENT));
        assertEquals("Pasargad", result.get(0));
        assertEquals("Tejarat", result.get(1));
        assertEquals("Refah", result.get(2));
    }

    @Test public void orderForDisplay_excludedSortedAmongThemselves_keepsAtBottom() {
        List<String> result = names(BalanceData.orderForDisplay(
            banks(), setOf("Tejarat", "Melli"), BalanceData.SORT_BALANCE_HIGH));
        assertEquals("Saman", result.get(0));
        assertEquals("Melli", result.get(1));
        assertEquals("Tejarat", result.get(2));
    }

    @Test public void orderForDisplay_emptyMap_returnsEmpty() {
        assertTrue(BalanceData.orderForDisplay(
            new LinkedHashMap<>(), setOf("Tejarat"), BalanceData.SORT_BALANCE_LOW).isEmpty());
    }

    // ---- helpers ----

    private static LinkedHashMap<String, Bank> banks() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));
        map.put("Saman", new Bank("Saman", 2_000_000, 1000L, "5001"));
        map.put("Melli", new Bank("Melli", 3_000_000, 1000L, "5002"));
        return map;
    }

    private static LinkedHashMap<String, Bank> datedBanks() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Tejarat", new Bank("Tejarat", 1_000_000, 100L, "5000"));
        map.put("Refah", new Bank("Refah", 2_000_000, 200L, "5001"));
        map.put("Pasargad", new Bank("Pasargad", 3_000_000, 300L, "5002"));
        return map;
    }

    private static List<String> names(List<Bank> list) {
        List<String> names = new ArrayList<>();
        for (Bank b : list) names.add(b.name);
        return names;
    }

    private static List<Bank> bankList() {
        return new ArrayList<>(banks().values());
    }

    private static Set<String> setOf(String... names) {
        Set<String> set = new HashSet<>();
        for (String n : names) set.add(n);
        return set;
    }
}