package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

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
        assertEquals(0, summary.payTotal + summary.receiveTotal);
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
        assertEquals(-7000, october.pay);
        assertEquals(9000, october.receive);
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
        assertEquals(-5000 * 13 - 2000, summary.payTotal);
        assertEquals(9000 * 13, summary.receiveTotal);
    }

    @Test public void overdue_collectsUnsettledPastDues() {
        List<Commitment> commitments = sample();
        commitments.add(Commitment.create("old daily", -100, Commitment.DAILY,
            Commitment.millisOf(2026, 9, 20, CalendarSystem.GREGORIAN),
            Commitment.millisOf(2026, 9, 25, CalendarSystem.GREGORIAN), false, 0));
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            commitments, CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        // Six September daily dues plus the September rent plus the October salary.
        assertEquals(8, summary.overdue.size());
        assertEquals(-100 * 6 - 5000, summary.overduePay);
        assertEquals(9000, summary.overdueReceive);
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
        assertEquals(7, resettled.overdue.size());
        assertEquals(1, resettled.settledOverdue.size());
        assertEquals("rent", resettled.settledOverdue.get(0).commitment.name);
        assertEquals(-600, resettled.overduePay);
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
        assertEquals(-5000, october.pay);
        assertTrue(summary.settledOverdue.isEmpty());
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
        assertEquals(-5000, october.pay);
        assertEquals(0, october.receive);
    }

    @Test public void heroTotal_coversThisMonthPlusOverdue() {
        CommitmentsActivity.Summary summary = CommitmentsActivity.summarize(
            sample(), CalendarSystem.GREGORIAN, OCT_7_NOON, 12);
        assertEquals(-5000, summary.overduePay);
        assertEquals(9000, summary.overdueReceive);
        assertEquals(-7000, summary.thisMonthPay);
        assertEquals(9000, summary.thisMonthReceive);
    }

    @Test public void userEnteredNames_followTheirOwnDirection() {
        assertTrue(CommitmentsActivity.isRtlText("اجاره"));
        assertFalse(CommitmentsActivity.isRtlText("rent"));
        assertTrue(CommitmentsActivity.isRtlText("2026 اجاره"));
    }
}
