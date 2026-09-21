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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Tests for the exclude-bank-from-total feature: persistence, toggle, and reset.
 */
@RunWith(AndroidJUnit4.class)
public class ExcludeTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    // ---- getExcluded ----

    @Test public void getExcluded_freshInstall_returnsEmptySet() {
        assertTrue(BalanceData.getExcluded(ctx).isEmpty());
    }

    // ---- setExcluded / getExcluded round-trip ----

    @Test public void setExcluded_singleBank_persistsAndReadsBack() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        BalanceData.setExcluded(ctx, s);
        Set<String> result = BalanceData.getExcluded(ctx);
        assertEquals(1, result.size());
        assertTrue(result.contains("Tejarat"));
    }

    @Test public void setExcluded_multipleBanks_persistsAll() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        s.add("Saman");
        s.add("Melli");
        BalanceData.setExcluded(ctx, s);
        Set<String> result = BalanceData.getExcluded(ctx);
        assertEquals(3, result.size());
        assertTrue(result.contains("Tejarat"));
        assertTrue(result.contains("Saman"));
        assertTrue(result.contains("Melli"));
    }

    @Test public void setExcluded_emptySet_clearsAll() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        BalanceData.setExcluded(ctx, s);
        assertEquals(1, BalanceData.getExcluded(ctx).size());
        BalanceData.setExcluded(ctx, new HashSet<>());
        assertTrue(BalanceData.getExcluded(ctx).isEmpty());
    }

    // ---- isExcluded ----

    @Test public void isExcluded_notExcluded_returnsFalse() {
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
    }

    @Test public void isExcluded_excluded_returnsTrue() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        BalanceData.setExcluded(ctx, s);
        assertTrue(BalanceData.isExcluded(ctx, "Tejarat"));
    }

    @Test public void isExcluded_differentBank_returnsFalse() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        BalanceData.setExcluded(ctx, s);
        assertFalse(BalanceData.isExcluded(ctx, "Saman"));
    }

    // ---- toggleExcluded ----

    @Test public void toggleExcluded_notExcluded_addsToSet() {
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
        BalanceData.toggleExcluded(ctx, "Tejarat");
        assertTrue(BalanceData.isExcluded(ctx, "Tejarat"));
    }

    @Test public void toggleExcluded_excluded_removesFromSet() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        BalanceData.setExcluded(ctx, s);
        assertTrue(BalanceData.isExcluded(ctx, "Tejarat"));
        BalanceData.toggleExcluded(ctx, "Tejarat");
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
    }

    @Test public void toggleExcluded_multipleBanks_onlyTogglesTarget() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        s.add("Saman");
        BalanceData.setExcluded(ctx, s);
        BalanceData.toggleExcluded(ctx, "Tejarat");
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
        assertTrue(BalanceData.isExcluded(ctx, "Saman"));
    }

    @Test public void toggleExcluded_doubleToggle_returnsToOriginal() {
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
        BalanceData.toggleExcluded(ctx, "Tejarat");
        assertTrue(BalanceData.isExcluded(ctx, "Tejarat"));
        BalanceData.toggleExcluded(ctx, "Tejarat");
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
    }

    // ---- reset clears excluded ----

    @Test public void reset_clearsExcludedBanks() {
        Set<String> s = new HashSet<>();
        s.add("Tejarat");
        s.add("Saman");
        BalanceData.setExcluded(ctx, s);
        assertEquals(2, BalanceData.getExcluded(ctx).size());
        BalanceData.reset(ctx);
        assertTrue(BalanceData.getExcluded(ctx).isEmpty());
    }

    // ---- Display ordering: excluded banks move to the end ----

    private static LinkedHashMap<String, Bank> banks() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));
        map.put("Saman", new Bank("Saman", 2_000_000, 1000L, "5001"));
        map.put("Melli", new Bank("Melli", 3_000_000, 1000L, "5002"));
        return map;
    }

    private static List<String> names(List<Bank> list) {
        List<String> names = new ArrayList<>();
        for (Bank b : list) names.add(b.name);
        return names;
    }

    private static Set<String> setOf(String... names) {
        Set<String> set = new HashSet<>();
        for (String n : names) set.add(n);
        return set;
    }

    @Test public void orderForDisplay_noExcluded_defaultsToBalanceHigh() {
        List<String> expected = new ArrayList<>();
        expected.add("Melli");
        expected.add("Saman");
        expected.add("Tejarat");
        assertEquals(expected, names(BalanceData.orderForDisplay(banks(), setOf())));
    }

    @Test public void orderForDisplay_singleExcluded_movesItToEnd() {
        List<String> expected = new ArrayList<>();
        expected.add("Melli");
        expected.add("Tejarat");
        expected.add("Saman");
        assertEquals(expected, names(BalanceData.orderForDisplay(banks(), setOf("Saman"))));
    }

    @Test public void orderForDisplay_firstBankExcluded_movesItToEnd() {
        List<String> expected = new ArrayList<>();
        expected.add("Melli");
        expected.add("Saman");
        expected.add("Tejarat");
        assertEquals(expected, names(BalanceData.orderForDisplay(banks(), setOf("Tejarat"))));
    }

    @Test public void orderForDisplay_multipleExcluded_sortedAtEnd() {
        List<String> expected = new ArrayList<>();
        expected.add("Tejarat");
        expected.add("Melli");
        expected.add("Saman");
        assertEquals(expected, names(BalanceData.orderForDisplay(banks(), setOf("Melli", "Saman"))));
    }

    @Test public void orderForDisplay_allExcluded_sortedByBalance() {
        List<String> expected = new ArrayList<>();
        expected.add("Melli");
        expected.add("Saman");
        expected.add("Tejarat");
        assertEquals(expected, names(BalanceData.orderForDisplay(banks(), setOf("Tejarat", "Saman", "Melli"))));
    }

    @Test public void orderForDisplay_emptyMap_returnsEmpty() {
        assertTrue(BalanceData.orderForDisplay(new LinkedHashMap<>(), setOf("Tejarat")).isEmpty());
    }

    // ---- Per-account exclusion: one account of a bank, not all of them ----

    private static LinkedHashMap<String, Bank> multiAccount() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Melli|1111", new Bank("Melli", 2_000_000, 1000L, "5001", "1111"));
        map.put("Melli|2222", new Bank("Melli", 4_000_000, 1100L, "5001", "2222"));
        map.put("Tejarat", new Bank("Tejarat", 3_000_000, 900L, "5000"));
        return map;
    }

    @Test public void orderForDisplay_excludingOneAccount_keepsItsSiblingIncluded() {
        List<String> result = names(BalanceData.orderForDisplay(
            multiAccount(), setOf("Melli|2222")));
        // Sort balance-high: Tejarat (3M) and the sibling Melli|1111 (2M) come first, the excluded
        // Melli|2222 (4M) sinks to the end even though it ranks highest by amount.
        assertEquals(java.util.Arrays.asList("Tejarat", "Melli", "Melli"), result);
        assertEquals(3, result.size());
    }

    @Test public void orderForDisplay_excludingBankName_doesNotCatchItsAccounts() {
        // A plain bank name is only the storage key of an account-less entry; it must not match the
        // composite bank|account keys of a multi-account bank.
        List<String> result = names(BalanceData.orderForDisplay(
            multiAccount(), setOf("Melli")));
        assertEquals(java.util.Arrays.asList("Melli", "Tejarat", "Melli"), result);
    }

    @Test public void toggleExcluded_perAccount_onlyTogglesTarget() {
        BalanceData.toggleExcluded(ctx, "Melli|2222");
        assertTrue(BalanceData.isExcluded(ctx, "Melli|2222"));
        assertFalse(BalanceData.isExcluded(ctx, "Melli|1111"));
        assertFalse(BalanceData.isExcluded(ctx, "Tejarat"));
    }

    @Test public void totalCalculation_excludesOnlyTheChosenAccount() {
        LinkedHashMap<String, Bank> banks = multiAccount();
        BalanceData.setExcluded(ctx, setOf("Melli|2222"));

        long total = 0;
        for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
            if (!BalanceData.isExcluded(ctx, e.getKey())) total += e.getValue().amount;

        // Melli|1111 (2M) + Tejarat (3M); only Melli|2222 (4M) is out.
        assertEquals(5_000_000L, total);
    }

    // ---- Total exclusion integration ----

    @Test public void totalCalculation_skipsExcludedBanks() {
        // Simulate the total calculation logic used in MainActivity and widget
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));
        banks.put("Saman", new Bank("Saman", 2_000_000, 1000L, "5001"));
        banks.put("Melli", new Bank("Melli", 3_000_000, 1000L, "5002"));

        // Exclude Saman
        Set<String> excluded = new HashSet<>();
        excluded.add("Saman");
        BalanceData.setExcluded(ctx, excluded);

        // Calculate total the same way MainActivity does
        long total = 0;
        for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
            if (!BalanceData.isExcluded(ctx, e.getKey())) total += e.getValue().amount;

        assertEquals(4_000_000L, total); // Tejarat(1M) + Melli(3M), Saman excluded
    }

    @Test public void totalCalculation_noExcludes_sumsAll() {
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));
        banks.put("Saman", new Bank("Saman", 2_000_000, 1000L, "5001"));

        long total = 0;
        for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
            if (!BalanceData.isExcluded(ctx, e.getKey())) total += e.getValue().amount;

        assertEquals(3_000_000L, total);
    }

    @Test public void totalCalculation_allExcluded_returnsZero() {
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));

        Set<String> excluded = new HashSet<>();
        excluded.add("Tejarat");
        BalanceData.setExcluded(ctx, excluded);

        long total = 0;
        for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
            if (!BalanceData.isExcluded(ctx, e.getKey())) total += e.getValue().amount;

        assertEquals(0L, total);
    }

    @Test public void totalCalculation_afterUnexclude_includesAgain() {
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put("Tejarat", new Bank("Tejarat", 1_000_000, 1000L, "5000"));
        banks.put("Saman", new Bank("Saman", 2_000_000, 1000L, "5001"));

        BalanceData.toggleExcluded(ctx, "Saman");
        long total = 0;
        for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
            if (!BalanceData.isExcluded(ctx, e.getKey())) total += e.getValue().amount;
        assertEquals(1_000_000L, total);

        BalanceData.toggleExcluded(ctx, "Saman");
        total = 0;
        for (java.util.Map.Entry<String, Bank> e : banks.entrySet())
            if (!BalanceData.isExcluded(ctx, e.getKey())) total += e.getValue().amount;
        assertEquals(3_000_000L, total);
    }
}
