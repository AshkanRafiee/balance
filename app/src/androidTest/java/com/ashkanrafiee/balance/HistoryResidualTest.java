package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * How unaccounted money behaves inside the history breakdown: which sums it moves, which it must not
 * touch, and where it lands on screen. The point of the feature is that the totals and the rows can
 * never disagree, so most of these check the two against each other.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryResidualTest {

    private static final String MELLAT = "Mellat";
    private static final String SADERAT = "Saderat";
    private static final long DAY = 86400000L;

    private static Transaction t(String bank, String account, long date, long amount) {
        return new Transaction(bank, account, date, amount, null, null);
    }

    private static Residual r(String bank, String account, long toDate, long amount) {
        return new Residual(bank, account, toDate - 2 * DAY, toDate, amount, 2);
    }

    private static long epoch(int gy, int gm, int gd) {
        Calendar c = Calendar.getInstance();
        c.set(gy, gm - 1, gd, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The single day group a one-day list should collapse to. */
    private static HistoryActivity.DayGroup onlyDay(HistoryActivity.Lists lists) {
        assertEquals(1, lists.years.size());
        assertEquals(1, lists.years.get(0).months.size());
        assertEquals(1, lists.years.get(0).months.get(0).days.size());
        return lists.years.get(0).months.get(0).days.get(0);
    }

    /**
     * Every day group of the breakdown, flattened, newest first.
     *
     * <p>Day numbers are the Jalali ones the screen shows, so a test that spans a Jalali month
     * boundary still reads as one ordered list instead of having to know which month a Gregorian
     * date happens to fall in.
     */
    private static List<HistoryActivity.DayGroup> allDays(HistoryActivity.Lists lists) {
        List<HistoryActivity.DayGroup> days = new ArrayList<>();
        for (HistoryActivity.YearGroup y : lists.years)
            for (HistoryActivity.MonthGroup m : y.months) days.addAll(m.days);
        return days;
    }

    /** The day group carrying the gap, found by its amount rather than by a guessed position. */
    private static HistoryActivity.DayGroup dayOf(HistoryActivity.Lists lists, long amount) {
        for (HistoryActivity.DayGroup d : allDays(lists))
            for (Residual r : d.residuals) if (r.amount == amount) return d;
        throw new AssertionError("no day group holds a residual of " + amount);
    }

    // -----------------------------------------------------------------------
    // Unaccounted money counts toward money
    // -----------------------------------------------------------------------

    @Test public void buildLists_residual_movesEverySum() {
        long d = epoch(2026, 9, 10);
        HistoryActivity.Lists lists = HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", d, 1_000_000L)),
            Arrays.asList(r(MELLAT, "1", d, -2_500_000L)), true);
        assertEquals(1_000_000L - 2_500_000L, lists.total);
        assertEquals(-1_500_000L, onlyDay(lists).sum);
    }

    @Test public void buildLists_residual_landsOnTheLaterStatementsDay() {
        // The gap is placed where the statement that proved it was sent, not where the money
        // probably moved: the user needs to see the proof next to the conclusion.
        long d = epoch(2026, 9, 10);
        HistoryActivity.DayGroup day = onlyDay(HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", d, 1_000_000L)),
            Arrays.asList(r(MELLAT, "1", d, -2_000_000L)), true));
        assertEquals(CalDate.fromGregorian(2026, 9, 10, true).key(), day.date.key());
        assertEquals(1, day.residuals.size());
        assertEquals(1, day.txs.size());
    }

    @Test public void buildLists_residual_joinsAnExistingDay() {
        // Same day as movements: one group holding both, with the movements untouched.
        long d = epoch(2026, 9, 10);
        HistoryActivity.DayGroup day = onlyDay(HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", d, 1_000_000L), t(MELLAT, "1", d, -300_000L)),
            Arrays.asList(r(MELLAT, "1", d, -700_000L)), true));
        assertEquals(2, day.txs.size());
        assertEquals(1, day.residuals.size());
        assertEquals(1_000_000L - 300_000L - 700_000L, day.sum);
    }

    @Test public void buildLists_residual_makesItsOwnDayWhenAlone() {
        HistoryActivity.DayGroup day = onlyDay(HistoryActivity.buildLists(
            new ArrayList<Transaction>(),
            Arrays.asList(r(MELLAT, "1", epoch(2026, 9, 10), -2_000_000L)), true));
        assertTrue(day.txs.isEmpty());
        assertEquals(1, day.residuals.size());
        assertEquals(-2_000_000L, day.sum);
    }

    @Test public void buildLists_residual_splitsTheDepositAndWithdrawalSubtotals() {
        // A gap is money that left or arrived, so it has to land on the same side of the split as a
        // real movement would, or the two figures stop adding up to the net.
        long d = epoch(2026, 9, 10);
        HistoryActivity.Lists lists = HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", d, 1_000_000L)),
            Arrays.asList(r(MELLAT, "1", d, -2_000_000L), r(MELLAT, "1", d, 500_000L)), true);
        assertEquals(-500_000L, lists.total);
        assertEquals(1_500_000L, lists.years.get(0).dep);
        assertEquals(-2_000_000L, lists.years.get(0).wit);
    }

    // -----------------------------------------------------------------------
    // Unaccounted money is not a transaction
    // -----------------------------------------------------------------------

    @Test public void buildLists_residual_doesNotCountAsAMovement() {
        // "2 transactions" must keep meaning two messages arrived. Letting a gap inflate the count
        // would quietly paper over the very fact the amber row exists to report.
        long d = epoch(2026, 9, 10);
        HistoryActivity.Lists lists = HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", d, 1_000_000L), t(MELLAT, "1", d, -300_000L)),
            Arrays.asList(r(MELLAT, "1", d, -700_000L), r(MELLAT, "1", d, 200_000L)), true);
        assertEquals(2, lists.years.get(0).n);
        assertEquals(2, lists.years.get(0).months.get(0).n);
    }

    // -----------------------------------------------------------------------
    // The ordering the merged pass exists to guarantee
    // -----------------------------------------------------------------------

    @Test public void buildLists_residualNewerThanEveryMovement_keepsDaysNewestFirst() {
        // The statement that dated this gap was filtered out, leaving the gap with no movement to
        // share a day with. Folding it in after the movements would have appended its day last and
        // shown the month out of order.
        // Mordad 1405 runs 2026-08-23 to 2026-09-22, so all three dates below land in one Jalali
        // month and the day order is the only thing under test.
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "1", epoch(2026, 8, 25), 1_000_000L),
            t(MELLAT, "1", epoch(2026, 9, 5), -2_000_000L));
        List<HistoryActivity.DayGroup> days = allDays(HistoryActivity.buildLists(txs,
            Arrays.asList(r(MELLAT, "1", epoch(2026, 9, 15), -3_000_000L)), true));
        assertEquals(3, days.size());
        assertEquals(24, days.get(0).date.day);
        assertEquals(14, days.get(1).date.day);
        assertEquals(3, days.get(2).date.day);
    }

    @Test public void buildLists_residualOlderThanEveryMovement_keepsDaysNewestFirst() {
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "1", epoch(2026, 9, 5), 1_000_000L),
            t(MELLAT, "1", epoch(2026, 9, 15), -2_000_000L));
        List<HistoryActivity.DayGroup> days = allDays(HistoryActivity.buildLists(txs,
            Arrays.asList(r(MELLAT, "1", epoch(2026, 8, 25), -3_000_000L)), true));
        assertEquals(3, days.size());
        assertEquals(24, days.get(0).date.day);
        assertEquals(14, days.get(1).date.day);
        assertEquals(3, days.get(2).date.day);
    }

    @Test public void buildLists_residualOnAMonthBoundary_joinsItsOwnMonth() {
        // A gap dated into the next month is a real event in that month's total, not an adjustment
        // left hanging on the old one.
        HistoryActivity.Lists lists = HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", epoch(2026, 8, 20), 1_000_000L)),
            Arrays.asList(r(MELLAT, "1", epoch(2026, 9, 2), -4_000_000L)), true);
        assertEquals(2, lists.years.get(0).months.size());
        HistoryActivity.MonthGroup sep = lists.years.get(0).months.get(0);
        assertEquals(-4_000_000L, sep.sum);
        assertEquals(0, sep.n);
        HistoryActivity.MonthGroup aug = lists.years.get(0).months.get(1);
        assertEquals(1_000_000L, aug.sum);
    }

    @Test public void buildLists_residualOnAYearBoundary_joinsItsOwnYear() {
        HistoryActivity.Lists lists = HistoryActivity.buildLists(
            Arrays.asList(t(MELLAT, "1", epoch(2026, 3, 10), 1_000_000L)),
            Arrays.asList(r(MELLAT, "1", epoch(2026, 4, 2), -4_000_000L)), true);
        assertEquals(2, lists.years.size());
        assertEquals(-4_000_000L, lists.years.get(0).sum);
        assertEquals(1_000_000L, lists.years.get(1).sum);
    }

    @Test public void buildLists_residualOnlyOnAMisorderedInput_comesOutOrdered() {
        // Neither list arrives sorted; the merged pass sorts both kinds together.
        List<HistoryActivity.DayGroup> days = allDays(HistoryActivity.buildLists(
            Arrays.asList(
                t(MELLAT, "1", epoch(2026, 8, 25), 1_000_000L),
                t(MELLAT, "1", epoch(2026, 9, 15), -2_000_000L)),
            Arrays.asList(
                r(MELLAT, "1", epoch(2026, 9, 5), -3_000_000L),
                r(MELLAT, "1", epoch(2026, 8, 30), -1_000_000L)), true));
        assertEquals(4, days.size());
        int[] expected = {24, 14, 8, 3};
        for (int i = 0; i < expected.length; i++) {
            assertEquals("day " + expected[i], expected[i], days.get(i).date.day);
        }
    }

    @Test public void buildLists_twoGapsOnOneDay_shareTheGroupInOrder() {
        // Two independent accounts both short on the same day: one group, both listed oldest first.
        long d = epoch(2026, 9, 10);
        HistoryActivity.DayGroup day = onlyDay(HistoryActivity.buildLists(
            new ArrayList<Transaction>(),
            Arrays.asList(r(MELLAT, "1", d, -2_000_000L), r(MELLAT, "2", d, -1_000_000L)), true));
        assertEquals(2, day.residuals.size());
        assertEquals(-3_000_000L, day.sum);
    }

    // -----------------------------------------------------------------------
    // Nothing to do
    // -----------------------------------------------------------------------

    @Test public void buildLists_noResiduals_keepsTheOldResultExactly() {
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "1", epoch(2026, 9, 10), 1_000_000L),
            t(MELLAT, "1", epoch(2026, 9, 11), 3_000_000L),
            t(SADERAT, "9", epoch(2026, 9, 11), -2_000_000L));
        HistoryActivity.Lists plain = HistoryActivity.buildLists(txs, true);
        HistoryActivity.Lists withEmpty = HistoryActivity.buildLists(txs, null, true);
        HistoryActivity.Lists withNone = HistoryActivity.buildLists(txs,
            new ArrayList<Residual>(), true);
        assertEquals(plain.total, withEmpty.total);
        assertEquals(plain.total, withNone.total);
        assertEquals(plain.years.get(0).n, withEmpty.years.get(0).n);
        assertEquals(plain.years.get(0).sum, withNone.years.get(0).sum);
        assertEquals(plain.years.get(0).months.get(0).days.size(),
            withNone.years.get(0).months.get(0).days.size());
    }

    @Test public void buildLists_nothingAtAll_isEmpty() {
        assertTrue(HistoryActivity.buildLists(new ArrayList<Transaction>(), null, true).years.isEmpty());
        assertTrue(HistoryActivity.buildLists(null, new ArrayList<Residual>(), true).years.isEmpty());
    }

    @Test public void buildLists_nullTransactionsAndResiduals_isEmpty() {
        assertTrue(HistoryActivity.buildLists(null, null, true).years.isEmpty());
        assertEquals(0L, HistoryActivity.buildLists(null, null, true).total);
    }

    @Test public void buildLists_doesNotMutateItsInputs() {
        long d = epoch(2026, 9, 10);
        List<Transaction> txs = new ArrayList<>(Arrays.asList(
            t(MELLAT, "1", d, 1_000_000L), t(MELLAT, "1", d + DAY, -2_000_000L)));
        List<Residual> residuals = new ArrayList<>(Arrays.asList(r(MELLAT, "1", d, -500_000L)));
        List<Transaction> txsBefore = new ArrayList<>(txs);
        List<Residual> residualsBefore = new ArrayList<>(residuals);
        HistoryActivity.buildLists(txs, residuals, true);
        assertEquals(txsBefore, txs);
        assertEquals(residualsBefore, residuals);
    }

    // -----------------------------------------------------------------------
    // Filters see a residual exactly as they see a movement
    // -----------------------------------------------------------------------

    @Test public void applyResidualFilters_keepsGapsInsideTheRange() {
        // The filter's bounds are dates in the active calendar, so they are Jalali here like every
        // other date the Iran region works in.
        long inside = epoch(2026, 9, 10);
        long outside = epoch(2020, 8, 10);
        List<Residual> kept = HistoryActivity.applyResidualFilters(
            Arrays.asList(r(MELLAT, "1", inside, -2_000_000L), r(MELLAT, "1", outside, -3_000_000L)),
            new HistoryActivity.Filter(HistoryActivity.DIR_ALL, HistoryActivity.RANGE_CUSTOM,
                CalDate.of(1405, 4, 1), CalDate.of(1405, 7, 31)), true);
        assertEquals(1, kept.size());
        assertEquals(-2_000_000L, kept.get(0).amount);
    }

    @Test public void applyResidualFilters_dropsGapsOnABareBoundaryDay() {
        // The filters compare the same way for a gap as for a movement, so a day that is entirely
        // "unaccounted" still respects the range the user picked.
        long d = epoch(2026, 9, 10);
        assertTrue(HistoryActivity.applyResidualFilters(
            Arrays.asList(r(MELLAT, "1", d, -2_000_000L)),
            new HistoryActivity.Filter(HistoryActivity.DIR_ALL, HistoryActivity.RANGE_CUSTOM,
                CalDate.of(1405, 1, 1), CalDate.of(1405, 3, 31)), true).isEmpty());
    }

    @Test public void applyResidualFilters_depositsAndWithdraws_splitGapsToo() {
        // The direction filter has to be able to tell the two apart, or "withdrawals only" would
        // still show a gap that was really a deposit.
        long out = epoch(2026, 9, 10);
        long into = epoch(2026, 9, 11);
        List<Residual> both = Arrays.asList(
            r(MELLAT, "1", out, -2_000_000L), r(MELLAT, "1", into, 5_000_000L));
        assertEquals(2, HistoryActivity.applyResidualFilters(both, HistoryActivity.Filter.ALL, true).size());
        List<Residual> ins = HistoryActivity.applyResidualFilters(both,
            new HistoryActivity.Filter(HistoryActivity.DIR_DEPOSIT, HistoryActivity.RANGE_ALL, null, null), true);
        assertEquals(1, ins.size());
        assertEquals(5_000_000L, ins.get(0).amount);
        List<Residual> outs = HistoryActivity.applyResidualFilters(both,
            new HistoryActivity.Filter(HistoryActivity.DIR_WITHDRAWAL, HistoryActivity.RANGE_ALL, null, null), true);
        assertEquals(1, outs.size());
        assertEquals(-2_000_000L, outs.get(0).amount);
    }

    @Test public void applyResidualFilters_agreesWithTheMovementFilterOnTheSameDates() {
        // Same input dates, same filter, same verdict — whichever way the two lists are built, the
        // totals and the rows cannot end up describing different ranges.
        long d = epoch(2026, 9, 10);
        List<Transaction> txs = Arrays.asList(t(MELLAT, "1", d, 1_000_000L));
        List<Residual> residuals = Arrays.asList(r(MELLAT, "1", d, -2_000_000L));
        int[] ranges = {HistoryActivity.RANGE_ALL, HistoryActivity.RANGE_TODAY,
            HistoryActivity.RANGE_MONTH, HistoryActivity.RANGE_YEAR, HistoryActivity.RANGE_CUSTOM};
        for (int range : ranges) {
            HistoryActivity.Filter f = new HistoryActivity.Filter(HistoryActivity.DIR_ALL, range,
                range == HistoryActivity.RANGE_CUSTOM ? CalDate.of(1405, 4, 1) : null,
                range == HistoryActivity.RANGE_CUSTOM ? CalDate.of(1405, 7, 31) : null);
            assertEquals("range " + range,
                !HistoryActivity.applyFilters(txs, f, true).isEmpty(),
                !HistoryActivity.applyResidualFilters(residuals, f, true).isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Bank and account narrowing for the export
    // -----------------------------------------------------------------------

    @Test public void filterResidualsByBank_keepsOnlyThatBanksGaps() {
        List<Residual> only = HistoryActivity.filterResidualsByBank(Arrays.asList(
            r(MELLAT, "1", epoch(2026, 9, 10), -2_000_000L),
            r(SADERAT, "1", epoch(2026, 9, 10), -3_000_000L),
            r(MELLAT, "2", epoch(2026, 9, 10), -4_000_000L)), SADERAT);
        assertEquals(1, only.size());
        assertEquals(SADERAT, only.get(0).bank);
    }

    @Test public void filterResidualsByAccount_keepsOnlyThatAccountsGaps() {
        List<Residual> only = HistoryActivity.filterResidualsByAccount(Arrays.asList(
            r(MELLAT, "1", epoch(2026, 9, 10), -2_000_000L),
            r(MELLAT, "2", epoch(2026, 9, 10), -3_000_000L)), "2");
        assertEquals(1, only.size());
        assertEquals("2", only.get(0).account);
    }

    @Test public void filterResiduals_matchesTheMovementFiltersItMirrors() {
        // An export narrowed to one account must carry exactly the gaps that account's view shows,
        // or the file and the screen disagree about the same money.
        long d = epoch(2026, 9, 10);
        List<Transaction> txs = Arrays.asList(
            t(MELLAT, "1", d, 1_000_000L), t(MELLAT, "2", d, -2_000_000L));
        List<Residual> residuals = Arrays.asList(
            r(MELLAT, "1", d, -3_000_000L), r(MELLAT, "2", d, -4_000_000L));
        // Same predicate, so the same rows survive on both sides — checked field by field, since
        // a movement and a gap are different kinds of thing and can never be compared as objects.
        List<Transaction> keptTxs = HistoryActivity.filterByAccount(txs, "1");
        List<Residual> keptGaps = HistoryActivity.filterResidualsByAccount(residuals, "1");
        assertEquals(keptTxs.size(), keptGaps.size());
        for (int i = 0; i < keptTxs.size(); i++) {
            assertEquals(keptTxs.get(i).bank, keptGaps.get(i).bank);
            assertEquals(keptTxs.get(i).account, keptGaps.get(i).account);
        }
        assertEquals(HistoryActivity.filterByBank(txs, MELLAT).size(),
            HistoryActivity.filterResidualsByBank(residuals, MELLAT).size());
        assertEquals(HistoryActivity.filterByBank(txs, SADERAT).size(),
            HistoryActivity.filterResidualsByBank(residuals, SADERAT).size());
    }

    // -----------------------------------------------------------------------
    // The whole path, from stored movements to what the user is told
    // -----------------------------------------------------------------------

    @Test public void endToEnd_oneMissingWithdrawal_surfacesInTheFilteredTotals() {
        // The chain the feature exists for: parse, detect, narrow, fold, and the number the user
        // sees finally matches the bank.
        long d = epoch(2026, 9, 10);
        List<Transaction> stored = Arrays.asList(
            new Transaction(MELLAT, "1", d, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, "1", d + 2 * DAY, -5_000_000L, 283_000_000L, "b", null));
        HistoryActivity.Filter all = new HistoryActivity.Filter(
            HistoryActivity.DIR_ALL, HistoryActivity.RANGE_ALL, null, null);
        List<Residual> residuals =
            HistoryActivity.applyResidualFilters(Residual.between(stored), all, true);
        assertEquals(1, residuals.size());
        assertEquals(-2_000_000L, residuals.get(0).amount);
        HistoryActivity.Lists lists = HistoryActivity.buildLists(
            HistoryActivity.applyFilters(stored, all, true), residuals, true);
        assertEquals(-35_000_000L - 2_000_000L, lists.total);
        assertEquals(-2_000_000L, dayOf(lists, -2_000_000L).residuals.get(0).amount);
        // It sits on the later statement's own day, not on the day the money probably moved.
        assertEquals(CalDate.fromGregorian(2026, 9, 12, true).key(),
            dayOf(lists, -2_000_000L).date.key());
    }

    @Test public void endToEnd_theLateArrivingMessage_makesTheGapDisappear() {
        // Nothing to invalidate and nothing to migrate: the same stored list plus one recovered
        // message must reconcile, because the residual was never stored in the first place.
        long d = epoch(2026, 9, 10);
        List<Transaction> before = Arrays.asList(
            new Transaction(MELLAT, "1", d, -30_000_000L, 290_000_000L, "a", null),
            new Transaction(MELLAT, "1", d + 2 * DAY, -5_000_000L, 283_000_000L, "b", null));
        assertEquals(-2_000_000L, Residual.between(before).get(0).amount);
        List<Transaction> after = new ArrayList<>(before);
        after.add(new Transaction(MELLAT, "1", d + DAY, -2_000_000L, 288_000_000L, "c", null));
        // The recovered message states the balance as it stood right after that withdrawal, which
        // is the 288,000,000 the later statement had already reported.
        assertTrue(Residual.between(after).isEmpty());
    }
}
