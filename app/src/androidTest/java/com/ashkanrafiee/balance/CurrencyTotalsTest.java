package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** The per-currency totals the dashboard, the widget and the clipboard now read. A mixed set is
 *  never collapsed into one number, and a sum that would overflow saturates instead of wrapping. */
public class CurrencyTotalsTest {

    @Test public void aSingleCurrencyIsTheWholeTotal() {
        CurrencyTotals t = new CurrencyTotals();
        assertTrue(t.isEmpty());
        assertNull(t.only());
        t.add("IRR", 100);
        t.add("IRR", -30);
        assertEquals("IRR", t.only());
        assertEquals(70L, t.get("IRR"));
        assertEquals(1, t.size());
    }

    @Test public void currenciesAreKeptApartAndNeverSummed() {
        CurrencyTotals t = new CurrencyTotals();
        t.add("IRR", 100);
        t.add("USD", 250);
        t.add("IRR", 5);
        assertNull(t.only());
        assertEquals(2, t.size());
        assertEquals(105L, t.get("IRR"));
        assertEquals(250L, t.get("USD"));
        assertEquals(0L, t.get("EUR"));
    }

    @Test public void aNullCurrencyIsTheRial() {
        CurrencyTotals t = new CurrencyTotals();
        t.add(null, 10);
        t.add("IRR", 5);
        assertEquals(1, t.size());
        assertEquals(15L, t.get("IRR"));
        assertEquals(15L, t.get(null));
    }

    @Test public void entriesFollowFirstContributionOrder() {
        CurrencyTotals t = new CurrencyTotals();
        t.add("USD", 1);
        t.add("IRR", 2);
        t.add("EUR", 3);
        t.add("USD", 1);
        Map<String, Long> entries = t.entries();
        assertEquals(3, entries.size());
        assertEquals("USD", entries.keySet().toArray()[0]);
        assertEquals("IRR", entries.keySet().toArray()[1]);
        assertEquals("EUR", entries.keySet().toArray()[2]);
        assertEquals(2L, entries.get("USD").longValue());
    }

    @Test public void anOverflowingSumSaturatesRatherThanWraps() {
        CurrencyTotals t = new CurrencyTotals();
        t.add("IRR", Long.MAX_VALUE);
        t.add("IRR", 1);
        assertEquals(Long.MAX_VALUE, t.get("IRR"));
        // Once saturated, an amount that still fits is exact again.
        t.add("IRR", -1);
        assertEquals(Long.MAX_VALUE - 1, t.get("IRR"));
        CurrencyTotals down = new CurrencyTotals();
        down.add("IRR", Long.MIN_VALUE);
        down.add("IRR", -1);
        assertEquals(Long.MIN_VALUE, down.get("IRR"));
    }

    @Test public void clearEmptiesEveryCurrency() {
        CurrencyTotals t = new CurrencyTotals();
        t.add("IRR", 10);
        t.add("USD", 10);
        t.clear();
        assertTrue(t.isEmpty());
        assertNull(t.only());
        assertEquals(0L, t.get("IRR"));
    }
}
