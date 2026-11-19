package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The commitments screen grouping: overdue dues, windowed months and their totals. */
@RunWith(AndroidJUnit4.class)
public class CommitmentsViewTest {

    private static final long OCT_7_NOON =
        Commitment.millisOf(2026, 10, 7, CalendarSystem.GREGORIAN) + 12 * 3600000L;

    private static List<Commitment> sample() {
        List<Commitment> out = new ArrayList<>();
        out.add(Commitment.create("rent", -5000, Commitment.MONTHLY,
            Commitment.millisOf(2026, 9, 15, CalendarSystem.GREGORIAN), null, false, 0));
        out.add(Commitment.create("salary", 9000, Commitment.MONTHLY,
            Commitment.millisOf(2026, 10, 1, CalendarSystem.GREGORIAN), null, false, 0));
        out.add(Commitment.create("gift", -2000, Commitment.ONCE,
            Commitment.millisOf(2026, 10, 20, CalendarSystem.GREGORIAN), null, false, 0));
        return out;
    }

    @Test public void emptyCommitments_renderEmptySummary() {
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            new ArrayList<Commitment>(), CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        assertTrue(summary.overdue.isEmpty());
        assertTrue(summary.months.isEmpty());
        assertEquals(BigInteger.ZERO, summary.payTotal);
        assertEquals(BigInteger.ZERO, summary.receiveTotal);
    }

    @Test public void currentMonth_groupsItsDuesInOrderWithTotals() {
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            sample(), CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        CommitmentsActivity.MonthGroup october = null;
        for (CommitmentsActivity.MonthGroup group : summary.months) {
            if (group.year == 2026 && group.month == 10) october = group;
        }
        assertTrue(october != null);
        assertEquals(3, october.rows.size());
        assertEquals("salary", october.rows.get(0).commitment.name);
        assertEquals("rent", october.rows.get(1).commitment.name);
        assertEquals("gift", october.rows.get(2).commitment.name);
        assertAmount(-7000, october.pay);
        assertAmount(9000, october.receive);
        assertEquals("current-month dues are not duplicated as overdue", 1, summary.overdue.size());
        assertAmount(0, summary.overdueReceive);
    }

    @Test public void series_continueThroughTheWindowAndAcrossYears() {
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            sample(), CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        // October 2026 through September 2027: the one-time gift lands once, the monthly
        // series land in every month.
        assertEquals(12, summary.months.size());
        assertEquals(2026, summary.months.get(0).year);
        assertEquals(10, summary.months.get(0).month);
        assertEquals(2027, summary.months.get(11).year);
        assertEquals(9, summary.months.get(11).month);
        assertEquals(2, summary.months.get(11).rows.size());
        // Twelve monthly rents, the one-time gift, and the September rent sitting overdue.
        assertAmount(-5000 * 13 - 2000, summary.payTotal);
        assertAmount(9000 * 12, summary.receiveTotal);
    }

    @Test public void overdue_collectsUnsettledPastDues() {
        List<Commitment> commitments = sample();
        commitments.add(Commitment.create("old daily", -100, Commitment.DAILY,
            Commitment.millisOf(2026, 9, 20, CalendarSystem.GREGORIAN),
            Commitment.millisOf(2026, 9, 25, CalendarSystem.GREGORIAN), false, 0));
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            commitments, CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        // Six September daily dues plus the September rent. The October salary belongs only to
        // the current-month group, not to overdue as well.
        assertEquals(7, summary.overdue.size());
        assertAmount(-100 * 6 - 5000, summary.overduePay);
        assertAmount(0, summary.overdueReceive);
        // Settling the rent leaves only the daily dues overdue.
        List<Commitment> settled = new ArrayList<>();
        for (Commitment c : commitments) {
            if (c.name.equals("rent")) {
                List<Long> rentPaid = new ArrayList<>();
                rentPaid.add(Commitment.millisOf(2026, 9, 15, CalendarSystem.GREGORIAN));
                settled.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    c.done, rentPaid, c.remind, c.remindBeforeMs));
            } else settled.add(c);
        }
        CommitmentsActivity.Summary resettled = CommitmentsActivity.summarize(
            settled, CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        assertEquals(6, resettled.overdue.size());
        assertEquals(1, resettled.settledMonths.size());
        assertEquals("rent", resettled.settledMonths.get(0).rows.get(0).commitment.name);
        assertAmount(-600, resettled.overduePay);
    }

    @Test public void settledDues_showWithoutBeingSummed() {
        List<Commitment> commitments = sample();
        List<Commitment> settled = new ArrayList<>();
        for (Commitment c : commitments) {
            if (c.name.equals("gift")) {
                settled.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    true, null, false, 0));
            } else settled.add(c);
        }
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            settled, CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        CommitmentsActivity.MonthGroup october = null;
        for (CommitmentsActivity.MonthGroup group : summary.months) {
            if (group.year == 2026 && group.month == 10) october = group;
        }
        assertTrue(october != null);
        assertEquals(3, october.rows.size());
        assertAmount(-5000, october.pay);
        assertTrue(summary.settledMonths.isEmpty());
    }

    @Test public void settledRows_stayInChronologicalOrder() {
        List<Commitment> commitments = sample();
        List<Commitment> settled = new ArrayList<>();
        for (Commitment c : commitments) {
            if (c.name.equals("salary")) {
                List<Long> salaryPaid = new ArrayList<>();
                salaryPaid.add(Commitment.millisOf(2026, 10, 1, CalendarSystem.GREGORIAN));
                settled.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    c.done, salaryPaid, c.remind, c.remindBeforeMs));
            } else if (c.name.equals("gift")) {
                settled.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    true, null, false, 0));
            } else settled.add(c);
        }
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            settled, CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        CommitmentsActivity.MonthGroup october = null;
        for (CommitmentsActivity.MonthGroup group : summary.months) {
            if (group.year == 2026 && group.month == 10) october = group;
        }
        assertTrue(october != null);
        assertEquals(3, october.rows.size());
        assertEquals("salary", october.rows.get(0).commitment.name);
        assertEquals("rent", october.rows.get(1).commitment.name);
        assertEquals("gift", october.rows.get(2).commitment.name);
        assertAmount(-5000, october.pay);
        assertAmount(0, october.receive);
    }

    @Test public void olderPaid_keepsPreviousRecurringAndOneTimeRowsOutOfOverdue() {
        long oldRecurringStart = Commitment.millisOf(2025, 10, 15, CalendarSystem.GREGORIAN);
        Commitment recurring = Commitment.create("old rent", -500, Commitment.MONTHLY,
            oldRecurringStart, Commitment.millisOf(2025, 10, 15, CalendarSystem.GREGORIAN),
            false, 0);
        List<Long> paid = new ArrayList<>();
        paid.add(oldRecurringStart);
        recurring = new Commitment(recurring.id, recurring.name, recurring.amount,
            recurring.frequency, recurring.start, recurring.end, false, paid,
            recurring.remind, recurring.remindBeforeMs);
        Commitment once = Commitment.create("old gift", 800, Commitment.ONCE,
            Commitment.millisOf(2025, 9, 20, CalendarSystem.GREGORIAN), null, false, 0);
        once = new Commitment(once.id, once.name, once.amount, once.frequency, once.start, once.end,
            true, null, once.remind, once.remindBeforeMs);
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(recurring);
        commitments.add(once);

        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            commitments, CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        assertTrue(summary.overdue.isEmpty());
        assertEquals(2, summary.settledMonths.size());
        assertTrue(!summary.settledMonths.get(0).rows.isEmpty());
        assertTrue(!summary.settledMonths.get(1).rows.isEmpty());
        assertAmount(0, summary.overduePay);
        assertAmount(0, summary.overdueReceive);
    }

    @Test public void overdueRecurringDuesOlderThanAYearRemainVisibleAndCounted() {
        Commitment old = Commitment.create("old daily", -100, Commitment.DAILY,
            Commitment.millisOf(2024, 1, 1, CalendarSystem.GREGORIAN), null, false, 0);
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            java.util.Collections.singletonList(old), CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        assertEquals(CommitmentsActivity.MAX_OVERDUE_ROWS, summary.overdue.size());
        assertTrue(summary.overdueCount > 366);
        assertAmount(-100L * summary.overdueCount, summary.overduePay);
    }

    @Test public void heroTotal_coversThisMonthPlusOverdue() {
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            sample(), CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        assertAmount(-5000, summary.overduePay);
        assertAmount(0, summary.overdueReceive);
        assertAmount(-7000, summary.thisMonthPay);
        assertAmount(9000, summary.thisMonthReceive);
    }

    @Test public void customDues_changeTheCorrectGroupsButSettledDuesAreExcluded() {
        CalendarSystem cal = CalendarSystem.GREGORIAN;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long august = Commitment.millisOf(2026, 8, 5, cal);
        long september = Commitment.millisOf(2026, 9, 5, cal);
        long october = Commitment.millisOf(2026, 10, 5, cal);
        long november = Commitment.millisOf(2026, 11, 5, cal);
        Commitment pay = Commitment.create("rent", -5000, Commitment.MONTHLY, august,
            november, false, 0, cal, zone);
        Map<Long, Long> payEdits = new HashMap<>();
        payEdits.put(august, -12000L);
        payEdits.put(september, -7000L);
        payEdits.put(october, -3000L);
        pay = pay.withOccurrenceAmounts(payEdits)
            .withSettlements(false, Arrays.asList(august), null);
        Commitment receive = Commitment.create("salary", 9000, Commitment.MONTHLY, september,
            november, false, 0, cal, zone);
        Map<Long, Long> receiveEdits = new HashMap<>();
        receiveEdits.put(september, 12000L);
        receiveEdits.put(october, 22000L);
        receiveEdits.put(november, 2000L);
        receive = receive.withOccurrenceAmounts(receiveEdits)
            .withSettlements(false, Arrays.asList(october), null);

        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            Arrays.asList(pay, receive), cal, OCT_7_NOON, 12);
        assertAmount(-7000, summary.overduePay);
        assertAmount(12000, summary.overdueReceive);
        assertAmount(-3000, summary.thisMonthPay);
        assertAmount(0, summary.thisMonthReceive);
        assertAmount(-10000, summary.remainingPay());
        assertAmount(12000, summary.remainingReceive());
        assertAmount(-15000, summary.payTotal);
        assertAmount(14000, summary.receiveTotal);
        assertEquals(-12000, summary.settledMonths.get(0).rows.get(0).amount);
        assertEquals(22000, summary.months.get(0).rows.get(1).amount);
        assertTrue(summary.months.get(0).rows.get(1).settled);
        assertAmount(-5000, summary.months.get(1).pay);
        assertAmount(2000, summary.months.get(1).receive);
    }

    @Test public void summaries_doNotOverflowSignedAmounts() {
        CalendarSystem cal = CalendarSystem.GREGORIAN;
        long september = Commitment.millisOf(2026, 9, 5, cal);
        long october = Commitment.millisOf(2026, 10, 5, cal);
        Commitment pay = Commitment.create("rent", Long.MIN_VALUE, Commitment.MONTHLY,
            september, october, false, 0);
        Commitment receive = Commitment.create("salary", Long.MAX_VALUE, Commitment.MONTHLY,
            september, october, false, 0);
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            Arrays.asList(pay, receive), cal, OCT_7_NOON, 12);
        assertEquals(new BigInteger("-18446744073709551616"), summary.remainingPay());
        assertEquals(new BigInteger("18446744073709551614"), summary.remainingReceive());
        assertEquals(summary.remainingPay(), summary.payTotal);
        assertEquals(summary.remainingReceive(), summary.receiveTotal);
    }

    private static void assertAmount(long expected, BigInteger actual) {
        assertEquals(BigInteger.valueOf(expected), actual);
    }

    @Test public void onceFrequency_hidesEndDateControls() {
        assertFalse(CommitmentsActivity.endDateControlsVisible(Commitment.ONCE));
        assertTrue(CommitmentsActivity.endDateControlsVisible(Commitment.DAILY));
        assertTrue(CommitmentsActivity.endDateControlsVisible(Commitment.MONTHLY));
    }

    @Test public void leadDecomposition_usesMinutesAndKeepsRawMillisecondsOutOfChips() {
        long thirtyMinutes = 30L * 60_000L;
        long[] minuteParts = CommitmentsActivity.decomposeLead(thirtyMinutes);
        assertEquals(30L, minuteParts[0]);
        assertEquals(0L, minuteParts[1]);

        long[] rawParts = CommitmentsActivity.decomposeLead(thirtyMinutes + 1L);
        assertEquals(thirtyMinutes + 1L, rawParts[0]);
        assertEquals(CommitmentsActivity.RAW_LEAD_UNIT, rawParts[1]);
        assertEquals(-1, CommitmentsActivity.leadChipSelection((int) rawParts[1]));
        assertEquals(3, CommitmentsActivity.leadChipSelection(3));
    }

    @Test public void parseCount_acceptsWholeCountsOnly() {
        assertEquals(Long.valueOf(30L), CommitmentsActivity.parseCount("30"));
        assertEquals(Long.valueOf(1L), CommitmentsActivity.parseCount("1"));
        assertEquals(Long.valueOf(Commitment.MAX_PAYMENT_COUNT),
            CommitmentsActivity.parseCount(String.valueOf(Commitment.MAX_PAYMENT_COUNT)));
        assertEquals(null, CommitmentsActivity.parseCount("0"));
        assertEquals(null, CommitmentsActivity.parseCount(""));
        assertEquals(null, CommitmentsActivity.parseCount("  "));
        assertEquals(null, CommitmentsActivity.parseCount("3.5"));
        assertEquals(null, CommitmentsActivity.parseCount("-4"));
        assertEquals(null,
            CommitmentsActivity.parseCount(String.valueOf(Commitment.MAX_PAYMENT_COUNT + 1)));
        assertEquals(null, CommitmentsActivity.parseCount(null));
    }

    @Test public void editorDraft_roundTripsCountModeThroughABundle() {
        CommitmentsActivity.Draft seed = new CommitmentsActivity.Draft(null, "Loan", "50000",
            true, Commitment.MONTHLY, new String[]{"2026", "1", "15"},
            CommitmentsActivity.END_MODE_COUNT, "30", new String[]{"2026", "1", "15"},
            false, "1", 2);
        android.os.Bundle bundle = new android.os.Bundle();
        seed.writeTo(bundle);
        CommitmentsActivity.Draft back = CommitmentsActivity.Draft.readFrom(bundle);
        assertTrue(back != null);
        assertEquals(CommitmentsActivity.END_MODE_COUNT, back.endMode);
        assertEquals("30", back.count);
        assertEquals(Commitment.MONTHLY, back.frequency);
    }

    @Test public void editorDraft_legacyOpenEndedBundleReadsAsOpenMode() {
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putBoolean("editor_draft", true);
        bundle.putString("editor_id", null);
        bundle.putString("editor_name", "Rent");
        bundle.putString("editor_amount", "1000");
        bundle.putBoolean("editor_pay", true);
        bundle.putInt("editor_frequency", Commitment.MONTHLY);
        bundle.putStringArray("editor_start", new String[]{"2026", "1", "1"});
        bundle.putBoolean("editor_open_ended", true);
        bundle.putStringArray("editor_end", new String[]{"2026", "1", "1"});
        bundle.putBoolean("editor_remind", false);
        bundle.putString("editor_lead", "1");
        bundle.putInt("editor_unit", 2);
        CommitmentsActivity.Draft back = CommitmentsActivity.Draft.readFrom(bundle);
        assertTrue(back != null);
        assertEquals(CommitmentsActivity.END_MODE_OPEN, back.endMode);
        assertEquals("", back.count);
    }

}
