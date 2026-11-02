package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure scanner-window tests: no SMS provider, store writes, or shell permissions. */
@RunWith(AndroidJUnit4.class)
public class RecentMovementWindowTest {
    private static final long T = 1_000_000_000L;
    private static final String BODY = "واریز:200,000 ریال\nمانده:3,000,000 ریال";

    @Test public void boundedDuplicates_keepFirstCanonicalAndDoNotExtendItsWindow() throws Exception {
        List<Reconcile.Entry> merged = merge(null, Arrays.asList(
            row(BODY, T), row(BODY, T + BalanceData.DUPLICATE_DELIVERY_MS),
            row(BODY, T + BalanceData.DUPLICATE_DELIVERY_MS + 1)), "Saman", null);
        assertEquals(2, merged.size());
        assertEquals(T, merged.get(0).date);
        assertEquals(T + BalanceData.DUPLICATE_DELIVERY_MS + 1, merged.get(1).date);
    }

    @Test public void retainedWindow_matchesNearestDeliveryOnEitherSide() throws Exception {
        Transaction canonical = BalanceData.parseMovement("Saman", "500095", BODY, T, false, 0);
        Reconcile.Entry retained = new Reconcile.Entry(T, canonical.amount, canonical.balance, canonical.sig);
        Map<String, String> senders = new HashMap<>();
        List<Reconcile.Entry> merged = merge(Collections.singletonList(retained), Arrays.asList(
            row(BODY, T - BalanceData.DUPLICATE_DELIVERY_MS),
            row(BODY, T + BalanceData.DUPLICATE_DELIVERY_MS),
            row(BODY, T + BalanceData.DUPLICATE_DELIVERY_MS + 1)), "Saman", senders);
        assertEquals(2, merged.size());
        assertSame(retained, merged.get(0));
        assertEquals("only newly accepted entries acquire a sender", 1, senders.size());
        assertEquals("500095", senders.get(merged.get(1).sig));
    }

    @Test public void exactSignature_staysExactEvenIfTheRetainedTimestampDiffers() throws Exception {
        Transaction canonical = BalanceData.parseMovement("Saman", "500095", BODY, T, false, 0);
        Reconcile.Entry retained = new Reconcile.Entry(T - 86_400_000L,
            canonical.amount, canonical.balance, canonical.sig);
        List<Reconcile.Entry> merged = merge(Collections.singletonList(retained),
            Collections.singletonList(row(BODY, T)), "Saman", null);
        assertEquals(1, merged.size());
        assertSame(retained, merged.get(0));
    }

    @Test public void reliableReferencesAndStatedDates_stayDistinctInsideDeliveryWindow() throws Exception {
        List<Object[]> rows = Arrays.asList(
            row(BODY + "\nشماره پیگیری:123456", T),
            row(BODY + "\nشماره پیگیری:654321", T + 1000),
            row(BODY + "\n2026/08/30 12:00", at(2026, 8, 30, 12, 0)),
            row(BODY + "\n2026/08/30 12:01", at(2026, 8, 30, 12, 1)));
        assertEquals(4, merge(null, rows, "Saman", null).size());
    }

    @Test public void equalAmountsAcrossAccounts_haveSeparateFamilies() throws Exception {
        String first = "حساب:10001\n" + BODY;
        String second = "حساب:10002\n" + BODY;
        assertEquals(2, merge(null, Arrays.asList(row(first, T), row(second, T + 1000)),
            "Melli", null).size());
    }

    @Test public void extremeTimestamps_doNotOverflowIntoDuplicateMatches() throws Exception {
        assertEquals(2, merge(null, Arrays.asList(row(BODY, Long.MIN_VALUE), row(BODY, Long.MAX_VALUE)),
            "Saman", null).size());
    }

    @Test public void distinctMovementFlood_doesNotSearchTheWholeCollectedHistoryPerRow() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 8000; i++)
            rows.add(row("واریز:" + (200_000 + i) + " مانده:" + (1_000_000 + i), T + i * 1000L));
        long start = SystemClock.elapsedRealtime();
        assertEquals(8000, merge(null, rows, "Saman", null).size());
        long elapsed = SystemClock.elapsedRealtime() - start;
        // Broad device-stall guard, not a microbenchmark. The quadratic baseline took ~75 s
        // for this fixture; the family index avoids its ~32 million pairwise comparisons.
        assertTrue("an 8000-movement window must finish without a long-device stall: " + elapsed + "ms",
            elapsed < 30_000L);
    }

    @Test public void recurringFamilyAcrossManyDays_doesNotBecomeOneLifetimeEvent() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) rows.add(row(BODY, T + i * 86_400_000L));
        assertEquals(2000, merge(null, rows, "Saman", null).size());
    }

    private static Object[] row(String body, long date) {
        return new Object[]{"500095", body, date};
    }

    @SuppressWarnings("unchecked")
    private static List<Reconcile.Entry> merge(List<Reconcile.Entry> retained, List<Object[]> rows,
            String bank, Map<String, String> senders) throws Exception {
        Method method = BalanceData.class.getDeclaredMethod("mergedForWindow", List.class,
            List.class, Map.class, String.class);
        method.setAccessible(true);
        return (List<Reconcile.Entry>) method.invoke(null, retained, rows, senders, bank);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
