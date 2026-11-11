package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.LocaleList;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Verifies that the widget mirrors the main app's ordering for included banks and drops excluded
 *  banks entirely, so the widget stays a glanceable summary of the total. */
@RunWith(AndroidJUnit4.class)
public class WidgetOrderTest {

    private Context ctx;
    private LocaleList originalLocales;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLocales = LocaleList.getDefault();
        // Disposable, serialized test installation: no inherited lock, data, sort or exclusions.
        LockManager.disable(ctx);
        LockManager.resetSessionForTest();
        DataGeneration.clearForTests(ctx);
        BalanceData.reset(ctx, false);
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        BalanceData.setSort(ctx, BalanceData.SORT_BALANCE_HIGH);
    }

    @After public void tearDown() throws Exception {
        try {
            LockManager.disable(ctx);
            LockManager.resetSessionForTest();
            BalanceData.reset(ctx, false);
            DataGeneration.clearForTests(ctx);
        } finally {
            ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
            LocaleList.setDefault(originalLocales);
        }
    }

    private static LinkedHashMap<String, Bank> banks() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        // These entries have no account; exclusion keys are deliberately plain bank names.
        // Date ranks conflict with balance ranks, including after Saman is excluded.
        map.put("Tejarat", new Bank("Tejarat", 1_000_000, 2000L, "5000"));
        map.put("Saman", new Bank("Saman", 2_000_000, 4000L, "5001"));
        map.put("Melli", new Bank("Melli", 3_000_000, 1000L, "5002"));
        map.put("Mellat", new Bank("Mellat", 4_000_000, 3000L, "5003"));
        return map;
    }

    private static List<String> keys(List<Bank> list) {
        List<String> n = new ArrayList<>();
        for (Bank b : list) n.add(BalanceData.storageKey(b.name, b.account));
        return n;
    }

    private static List<String> appIncludedOrder(Set<String> excluded, int mode) {
        List<String> n = new ArrayList<>();
        for (Bank b : BalanceData.orderForDisplay(banks(), excluded, mode)) {
            String key = BalanceData.storageKey(b.name, b.account);
            if (!excluded.contains(key)) n.add(key);
        }
        return n;
    }

    /** Widget ordering matches the app at every sort mode (after stripping excluded banks). */
    @Test public void widgetBanks_matchesAppForEveryMode() {
        Set<String> excluded = new HashSet<>();
        excluded.add("Saman");
        BalanceData.setExcluded(ctx, excluded);
        assertTrue(BalanceData.write(ctx, banks()));

        int[] modes = {BalanceData.SORT_BALANCE_HIGH, BalanceData.SORT_BALANCE_LOW,
            BalanceData.SORT_DATE_RECENT, BalanceData.SORT_DATE_OLDEST};
        String[][] expected = {
            {"Mellat", "Melli", "Tejarat"}, {"Tejarat", "Melli", "Mellat"},
            {"Mellat", "Tejarat", "Melli"}, {"Melli", "Tejarat", "Mellat"}
        };
        for (int i = 0; i < modes.length; i++) {
            int mode = modes[i];
            BalanceData.setSort(ctx, mode);
            List<String> actual = keys(BalanceWidgetService.widgetBanks(ctx));
            assertEquals("Literal order for mode " + mode, Arrays.asList(expected[i]), actual);
            assertEquals("App parity for mode " + mode, appIncludedOrder(excluded, mode), actual);
        }
    }

    /** Excluded banks never appear in the widget list, no matter the mode. */
    @Test public void widgetBanks_excludedNeverListed() {
        Set<String> excluded = new HashSet<>();
        excluded.add("Melli");
        excluded.add("Tejarat");
        excluded.add("Mellat");
        BalanceData.setExcluded(ctx, excluded);
        assertTrue(BalanceData.write(ctx, banks()));

        for (int mode : new int[]{BalanceData.SORT_BALANCE_HIGH, BalanceData.SORT_BALANCE_LOW,
                BalanceData.SORT_DATE_RECENT, BalanceData.SORT_DATE_OLDEST}) {
            BalanceData.setSort(ctx, mode);
            List<String> actual = keys(BalanceWidgetService.widgetBanks(ctx));
            assertEquals("Included bank remains in mode " + mode,
                Collections.singletonList("Saman"), actual);
            for (String name : actual) {
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
        all.add("Mellat");
        BalanceData.setExcluded(ctx, all);
        assertTrue(BalanceData.write(ctx, banks()));
        assertEquals(0, BalanceWidgetService.widgetBanks(ctx).size());

        BalanceData.reset(ctx, false);
        assertEquals(0, BalanceWidgetService.widgetBanks(ctx).size());
    }

    /** Excluding one account of a multi-account bank must drop only that account; its sibling and
     *  every other bank stay in the widget list. */
    @Test public void widgetBanks_excludesOnlyTheChosenAccount() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Mellat|1110000222", new Bank("Mellat", 1_000_000, 1000L, "5300", "1110000222"));
        map.put("Mellat|1110000333", new Bank("Mellat", 2_000_000, 2000L, "5300", "1110000333"));
        map.put("Tejarat", new Bank("Tejarat", 5_000_000, 3000L, "5301"));
        assertTrue(BalanceData.write(ctx, map));

        Set<String> excluded = new HashSet<>();
        excluded.add("Mellat|1110000222");
        BalanceData.setExcluded(ctx, excluded);

        List<Bank> widget = BalanceWidgetService.widgetBanks(ctx);
        assertEquals(2, widget.size());
        assertEquals("Tejarat", widget.get(0).name);
        assertEquals("Mellat", widget.get(1).name);
        assertEquals("1110000333", widget.get(1).account);
    }

    /** Accounts rank independently, not in bank groups, and keep their own balances in every mode. */
    @Test public void widgetBanks_oneRowPerAccount() {
        LinkedHashMap<String, Bank> map = new LinkedHashMap<>();
        map.put("Mellat|1110000222", new Bank("Mellat", 1_000_000, 2000L, "5300", "1110000222"));
        map.put("Mellat|1110000333", new Bank("Mellat", 2_000_000, 1000L, "5300", "1110000333"));
        map.put("Tejarat", new Bank("Tejarat", 1_500_000, 3000L, "5301"));
        assertTrue(BalanceData.write(ctx, map));

        List<Bank> widget = BalanceWidgetService.widgetBanks(ctx);
        assertEquals(3, widget.size());
        assertEquals("Mellat", widget.get(0).name);
        assertEquals("1110000333", widget.get(0).account);
        assertEquals(2_000_000, widget.get(0).amount);
        assertEquals("Tejarat", widget.get(1).name);
        assertEquals(1_500_000, widget.get(1).amount);
        assertEquals("Mellat", widget.get(2).name);
        assertEquals("1110000222", widget.get(2).account);
        assertEquals(1_000_000, widget.get(2).amount);

        int[] modes = {BalanceData.SORT_BALANCE_HIGH, BalanceData.SORT_BALANCE_LOW,
            BalanceData.SORT_DATE_RECENT, BalanceData.SORT_DATE_OLDEST};
        String[][] expected = {
            {"Mellat|1110000333", "Tejarat", "Mellat|1110000222"},
            {"Mellat|1110000222", "Tejarat", "Mellat|1110000333"},
            {"Tejarat", "Mellat|1110000222", "Mellat|1110000333"},
            {"Mellat|1110000333", "Mellat|1110000222", "Tejarat"}
        };
        for (int i = 0; i < modes.length; i++) {
            BalanceData.setSort(ctx, modes[i]);
            widget = BalanceWidgetService.widgetBanks(ctx);
            assertEquals("Flat account order for mode " + modes[i],
                Arrays.asList(expected[i]), keys(widget));
            for (Bank bank : widget) {
                Bank seeded = map.get(BalanceData.storageKey(bank.name, bank.account));
                assertEquals(seeded.amount, bank.amount);
                assertEquals(seeded.date, bank.date);
            }
        }
    }
}
