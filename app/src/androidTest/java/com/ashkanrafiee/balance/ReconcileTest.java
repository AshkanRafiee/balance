package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Unit tests for the balance-chain reconciliation that fixes bank messages arriving out of order.
 *  Pure logic, no SMS provider involved. */
@RunWith(AndroidJUnit4.class)
public class ReconcileTest {

    private static Reconcile.Entry e(int i, long amount, long balance) {
        return new Reconcile.Entry(i, amount, balance, "sig-" + i);
    }

    private static long[] sums(List<Reconcile.Entry> in) {
        long[] a = new long[in.size()];
        for (int i = 0; i < in.size(); i++) a[i] = in.get(i).amount;
        return a;
    }

    @Test public void order_reversedMellatPair_returnsFeeThenTransfer() {
        // Mellat: the fee (balance 177M) precedes the transfer (balance 77M), but the bank delivered
        // them in the reverse order.
        Reconcile.Entry fee = e(1, -10000, 177222945L);
        Reconcile.Entry transfer = e(2, -100000000L, 77222945L);
        List<Reconcile.Entry> out = Reconcile.order(Arrays.asList(transfer, fee));
        assertEquals(2, out.size());
        assertSame(fee, out.get(0));
        assertSame(transfer, out.get(1));
    }

    @Test public void order_reversedResalatPair_returnsTransferThenFee() {
        // Resalat: the transfer (balance 2,279,545,033) precedes the fee (balance 2,279,505,033).
        Reconcile.Entry transfer = e(1, -200000000L, 2279545033L);
        Reconcile.Entry fee = e(2, -40000L, 2279505033L);
        List<Reconcile.Entry> out = Reconcile.order(Arrays.asList(fee, transfer));
        assertEquals(2, out.size());
        assertSame(transfer, out.get(0));
        assertSame(fee, out.get(1));
    }

    @Test public void order_alreadyChronological_returnsSameOrder() {
        Reconcile.Entry a = e(1, -40000L, 2279505033L);
        Reconcile.Entry b = e(2, -200000000L, 2079505033L);
        List<Reconcile.Entry> out = Reconcile.order(Arrays.asList(a, b));
        assertEquals(2, out.size());
        assertSame(a, out.get(0));
        assertSame(b, out.get(1));
    }

    @Test public void order_threeMovementChain_returnsInternalOrder() {
        Reconcile.Entry a = e(1, 100000L, 1000000L);
        Reconcile.Entry b = e(2, -200000L, 800000L);
        Reconcile.Entry c = e(3, 50000L, 850000L);
        List<Reconcile.Entry> in = Arrays.asList(c, a, b);
        assertEquals(3, Reconcile.order(in).size());
        long[] expected = {100000L, -200000L, 50000L};
        assertEquals(Arrays.toString(expected), Arrays.toString(sums(Reconcile.order(in))));
    }

    @Test public void order_singleEntry_returnsNull() {
        assertNull(Reconcile.order(Arrays.asList(e(1, 100L, 1000L))));
    }

    @Test public void order_equalOppositeWithUnlinkedBalances_returnsNull() {
        // The balances cannot be connected by the amounts, so the true order is unknowable.
        Reconcile.Entry dep = e(1, 500000L, 5500000L);
        Reconcile.Entry wit = e(2, -500000L, 7000000L);
        assertNull(Reconcile.order(Arrays.asList(wit, dep)));
    }

    @Test public void order_duplicateEntries_returnsNull() {
        // The same amount + balance twice: neither can precede the other.
        Reconcile.Entry a = e(1, 100000L, 1000000L);
        Reconcile.Entry b = e(1, 100000L, 1000000L);
        assertNull(Reconcile.order(Arrays.asList(a, b)));
    }

    @Test public void order_branching_returnsNull() {
        // One entry with two possible successors is ambiguous.
        Reconcile.Entry a = e(1, -100000L, 1000000L);
        Reconcile.Entry b = e(2, -100000L, 900000L);
        Reconcile.Entry c = e(3, -100000L, 900000L);
        assertNull(Reconcile.order(Arrays.asList(a, b, c)));
    }

    @Test public void order_cycle_returnsNull() {
        // A true 2-cycle: 0 + -100 == -100 (A precedes B) and -100 + 100 == 0 (B precedes A).
        // No in-degree-0 head exists, so the loop is ambiguous and correctly rejected.
        Reconcile.Entry a = e(1, 100L, 0L);
        Reconcile.Entry b = e(2, -100L, -100L);
        assertNull(Reconcile.order(Arrays.asList(a, b)));
    }

    @Test public void order_disconnectedPairs_returnsNull() {
        // Two independent chains cannot be ordered into one without knowing their relationship.
        Reconcile.Entry a = e(1, 100L, 0L);
        Reconcile.Entry b = e(2, -100L, 100L);
        Reconcile.Entry c = e(3, 50L, 0L);
        Reconcile.Entry d = e(4, -50L, 50L);
        assertNull(Reconcile.order(Arrays.asList(b, d, c, a)));
    }

    /** Long-overflowing sums must never forge an edge: without the guard, MAX_VALUE + 1 silently
     *  wraps to MIN_VALUE and would fabricate a false chain between genuinely unconnected entries. */
    @Test public void order_overflowingSums_neverForgeEdges() {
        Reconcile.Entry a = e(1, Long.MAX_VALUE, Long.MAX_VALUE);
        Reconcile.Entry b = e(2, 1L, Long.MIN_VALUE);
        Reconcile.Entry c = e(3, 1L, Long.MIN_VALUE + 1);
        assertNull("Overflow must not connect these entries", Reconcile.order(Arrays.asList(a, b, c)));
    }

    @Test public void order_overClusterLimit_returnsNull() {
        // The O(n^2) matcher is only walked for small windows; a larger fold must bail out cleanly
        // and leave the caller on the original arrival order.
        List<Reconcile.Entry> in = new ArrayList<>();
        for (int i = 0; i < 65; i++) in.add(e(i, 100L, 1000L + i));
        assertNull(Reconcile.order(in));
    }
}