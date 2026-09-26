package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the unaccounted-money detector ({@link Residual}). Pure logic over stored
 * movements: no SMS provider, no store, no UI. The cases below are the ones that decide whether the
 * app is honest or merely confident, so the ambiguous ones matter as much as the obvious one.
 */
@RunWith(AndroidJUnit4.class)
public class ResidualTest {

    private static final String MELLAT = "Mellat";
    private static final long DAY = 86400000L;

    /** A movement that also carries the balance its message reported. */
    private static Transaction t(String bank, String account, long date, long amount, long balance) {
        return new Transaction(bank, account, date, amount, balance, "sig", null);
    }

    /** A legacy-style movement with no reported balance, which can never anchor a window. */
    private static Transaction blind(String bank, String account, long date, long amount) {
        return new Transaction(bank, account, date, amount, null, null);
    }

    private static long[] amounts(List<Residual> rs) {
        long[] a = new long[rs.size()];
        for (int i = 0; i < rs.size(); i++) a[i] = rs.get(i).amount;
        return a;
    }

    // -----------------------------------------------------------------------
    // The reported case: a withdrawal whose message never arrived
    // -----------------------------------------------------------------------

    @Test public void between_oneMissingWithdrawal_reportsTheShortfall() {
        // Statement 1: -30,000,000 received, balance 290,000,000. A 2,000,000 withdrawal then never
        // arrives. Statement 2: another -5,000,000 received, balance 283,000,000. The balance fell
        // 7,000,000 while only 5,000,000 of movements reached us, so 2,000,000 went missing.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -30_000_000L, 290_000_000L),
            t(MELLAT, "123", 12 * DAY, -5_000_000L, 283_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(-2_000_000L, out.get(0).amount);
        assertEquals(10 * DAY, out.get(0).fromDate);
        assertEquals(12 * DAY, out.get(0).toDate);
        assertEquals(1, out.get(0).movements);
    }

    @Test public void between_missingDeposit_reportsTheOvershoot() {
        // The balance rose by more than the deposit we received, so money came in unannounced.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, 1_000_000L, 121_000_000L),
            t(MELLAT, "123", 12 * DAY, -1_000_000L, 125_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(5_000_000L, out.get(0).amount);
    }

    // -----------------------------------------------------------------------
    // The property that matters most: silence when the books agree
    // -----------------------------------------------------------------------

    @Test public void between_fullyAccounted_reportsNothing() {
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -30_000_000L, 290_000_000L),
            t(MELLAT, "123", 12 * DAY, -2_000_000L, 288_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_singleStatement_reportsNothing() {
        // One statement is one end of a bracket. Without a second one there is no arithmetic to do,
        // and inventing a gap here would be the exact failure this feature must not have.
        assertTrue(Residual.between(Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -30_000_000L, 290_000_000L))).isEmpty());
    }

    @Test public void between_noStatementsAtAll_reportsNothing() {
        assertTrue(Residual.between(Arrays.asList(
            blind(MELLAT, "123", 10 * DAY, -30_000_000L))).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Several missing movements collapse to the one net we can prove
    // -----------------------------------------------------------------------

    @Test public void between_threeMissingMovements_reportOnlyTheirNet() {
        // Three unseen withdrawals totalling -9,000,000. We can prove the net and nothing more, so
        // the result must be a single line, never three fabricated ones.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            t(MELLAT, "123", 20 * DAY, -1_000_000L, 89_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(-9_000_000L, out.get(0).amount);
    }

    @Test public void between_mixedWindow_reportsTheGapAndKeepsTheRest() {
        // First window closes cleanly; only the second is short.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            t(MELLAT, "123", 11 * DAY, -1_000_000L, 98_000_000L),
            t(MELLAT, "123", 20 * DAY, -1_000_000L, 94_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(11 * DAY, out.get(0).fromDate);
        assertEquals(20 * DAY, out.get(0).toDate);
        assertEquals(-3_000_000L, out.get(0).amount);
    }

    // -----------------------------------------------------------------------
    // Movements that cannot anchor a window still have to be counted
    //
    // The parser only ever records a movement alongside the balance its message stated, so in
    // practice every stored row has one. These cases are the walk's safety net for rows that reach it
    // some other way — a hand-edited or third-party backup, or a bank that starts sending movements
    // without balances. A row like that must never open or close a bracket, and must never be
    // dropped from the sum either.
    // -----------------------------------------------------------------------

    @Test public void between_balanceLessMovementInsideTheWindow_isCounted() {
        // An entry written before balance capture carries no balance of its own, so it cannot open
        // or close a bracket — but its amount is still money that moved, and leaving it out would
        // turn a legacy entry into a phantom gap.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            blind(MELLAT, "123", 12 * DAY, -2_000_000L),
            t(MELLAT, "123", 14 * DAY, -1_000_000L, 96_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_balanceLessMovementOutsideAnyWindow_isIgnored() {
        // Before the first statement there is nothing to measure against, so the movement is simply
        // outside the question rather than evidence of one.
        List<Transaction> txs = Arrays.asList(
            blind(MELLAT, "123", 1 * DAY, -2_000_000L),
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            t(MELLAT, "123", 12 * DAY, -1_000_000L, 98_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Ordering, ties and per-account isolation
    // -----------------------------------------------------------------------

    @Test public void between_sameInstant_claimsNoGap() {
        // Two statements stamped the same second bound no interval at all. Which came first is
        // unknowable, and a residual claimed across that would be a fabrication.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 103_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_sameInstant_thenAFreshStatement_resumesDetection() {
        // The ambiguous pair is dropped, so the region it sits in stays silent. Detection picks up
        // again from the next message that states a balance rather than being disabled for good.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 103_000_000L),
            t(MELLAT, "123", 20 * DAY, 0L, 50_000_000L),
            t(MELLAT, "123", 30 * DAY, -1_000_000L, 46_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(20 * DAY, out.get(0).fromDate);
        assertEquals(30 * DAY, out.get(0).toDate);
        assertEquals(-3_000_000L, out.get(0).amount);
    }

    @Test public void between_sameInstantPair_neitherBracketIsClaimed() {
        // After the pair is dropped there is no open bracket, so the very next statement only
        // re-anchors: no residual, and in particular no gap equal to the tied movement's own
        // amount, which is exactly the number a careless walk would report.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 103_000_000L),
            t(MELLAT, "123", 12 * DAY, -1_000_000L, 102_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_outOfOrderInput_isWalkedInDateOrder() {
        // The stored list arrives newest-first and can even be mis-ordered; the walk must sort.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 20 * DAY, -1_000_000L, 94_000_000L),
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(10 * DAY, out.get(0).fromDate);
        assertEquals(20 * DAY, out.get(0).toDate);
        assertEquals(-4_000_000L, out.get(0).amount);
    }

    @Test public void between_twoAccounts_areNeverReconciledAgainstEachOther() {
        // Both accounts look short against each other; measured separately, neither is. Letting them
        // borrow each other's balances would invent a gap out of two healthy accounts.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "111", 10 * DAY, -1_000_000L, 50_000_000L),
            t(MELLAT, "222", 11 * DAY, -1_000_000L, 70_000_000L),
            t(MELLAT, "111", 12 * DAY, -1_000_000L, 49_000_000L),
            t(MELLAT, "222", 13 * DAY, -1_000_000L, 69_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_oneAccountOfTwo_short_reportsOnlyThatAccount() {
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "111", 10 * DAY, -1_000_000L, 50_000_000L),
            t(MELLAT, "222", 11 * DAY, -1_000_000L, 70_000_000L),
            t(MELLAT, "111", 12 * DAY, -1_000_000L, 44_000_000L),
            t(MELLAT, "222", 13 * DAY, -1_000_000L, 69_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals("111", out.get(0).account);
        assertEquals(-5_000_000L, out.get(0).amount);
    }

    @Test public void between_accountLessMessages_formTheirOwnSlot() {
        // A bank that states no account number shares one slot, so its messages do bracket each
        // other exactly as a single account's would.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, null, 10 * DAY, -1_000_000L, 50_000_000L),
            t(MELLAT, null, 12 * DAY, -1_000_000L, 45_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(-4_000_000L, out.get(0).amount);
        assertEquals(MELLAT, out.get(0).key());
    }

    @Test public void between_accountAndAccountLess_areSeparateSlots() {
        // Mixing them would measure one account's balance against another's movement.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "111", 10 * DAY, -1_000_000L, 50_000_000L),
            t(MELLAT, null, 12 * DAY, -1_000_000L, 45_000_000L));
        assertTrue(Residual.between(txs).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Arithmetic that must never lie
    // -----------------------------------------------------------------------

    @Test public void between_overflowingWindow_reportsNothing() {
        // Long.MAX_VALUE - Long.MIN_VALUE wraps to -1, which would be reported as a tidy one-rial
        // gap. A wrapped number is not a small error, so the window stays unclaimed.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, 0L, Long.MAX_VALUE),
            t(MELLAT, "123", 12 * DAY, 0L, Long.MIN_VALUE));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_overflowingMovementSum_reportsNothing() {
        // The running sum itself wraps before the bracket closes, so nothing downstream is knowable.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, 0L, 0L),
            blind(MELLAT, "123", 11 * DAY, Long.MAX_VALUE),
            blind(MELLAT, "123", 12 * DAY, Long.MAX_VALUE),
            t(MELLAT, "123", 13 * DAY, 0L, Long.MIN_VALUE));
        assertTrue(Residual.between(txs).isEmpty());
    }

    @Test public void between_ordinaryLargeAmounts_stillDetected() {
        // A ten-billion-rial window is unremarkable for these accounts and must not trip the guard.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, -9_000_000_000L, 11_000_000_000L),
            t(MELLAT, "123", 12 * DAY, -1_000_000_000L, 9_000_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(-1_000_000_000L, out.get(0).amount);
    }

    // -----------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------

    @Test public void between_emptyAndNullInputs_reportNothing() {
        assertTrue(Residual.between(new ArrayList<Transaction>()).isEmpty());
        assertTrue(Residual.between(null).isEmpty());
    }

    @Test public void between_doesNotMutateItsInput() {
        List<Transaction> txs = new ArrayList<>(Arrays.asList(
            t(MELLAT, "123", 20 * DAY, -1_000_000L, 94_000_000L),
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L)));
        List<Transaction> before = new ArrayList<>(txs);
        Residual.between(txs);
        assertEquals(before, txs);
    }

    @Test public void between_resultsAreOldestFirst() {
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "123", 10 * DAY, 0L, 100_000_000L),
            t(MELLAT, "123", 20 * DAY, 0L, 90_000_000L),
            t(MELLAT, "123", 40 * DAY, 0L, 70_000_000L));
        List<Residual> out = Residual.between(txs);
        assertEquals(2, out.size());
        assertEquals(20 * DAY, out.get(0).toDate);
        assertEquals(40 * DAY, out.get(1).toDate);
        assertEquals(Arrays.toString(new long[]{-10_000_000L, -20_000_000L}),
            Arrays.toString(amounts(out)));
    }

    @Test public void between_singletonAndNullEntries_areSkipped() {
        List<Transaction> txs = new ArrayList<>(Arrays.asList(
            null,
            t(MELLAT, "123", 10 * DAY, -1_000_000L, 99_000_000L),
            null,
            t(MELLAT, "123", 12 * DAY, -1_000_000L, 95_000_000L)));
        List<Residual> out = Residual.between(txs);
        assertEquals(1, out.size());
        assertEquals(-3_000_000L, out.get(0).amount);
    }
}
