package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pure schedule and amount tests for the manually entered planner. */
@RunWith(AndroidJUnit4.class)
public class ScheduledPaymentsTest {
    @Test public void monthlyDayThirtyOneClampsAndReturnsToAnchor() {
        ScheduledPayment plan = new ScheduledPayment("jan31", "Rent", ScheduledPayment.Type.SUBSCRIPTION,
            1000, CalendarSystem.GREGORIAN, 2024, 1, 31,
            ScheduledPayment.Frequency.MONTHLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 3);
        List<PaymentOccurrence> out = ScheduledPayments.occurrences(
            java.util.Collections.singletonList(plan), Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(3, out.size());
        assertEquals(31, out.get(0).date.day);
        assertEquals(29, out.get(1).date.day);
        assertEquals(31, out.get(2).date.day);
    }

    @Test public void paidStateBelongsToOneOccurrence() {
        ScheduledPayment plan = new ScheduledPayment("loan", "Loan", ScheduledPayment.Type.LOAN,
            1000, CalendarSystem.GREGORIAN, 2025, 1, 15,
            ScheduledPayment.Frequency.MONTHLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 3);
        plan.setState(0, ScheduledPayment.STATE_PAID);
        List<PaymentOccurrence> out = ScheduledPayments.occurrences(
            java.util.Collections.singletonList(plan), Long.MIN_VALUE, Long.MAX_VALUE);
        assertTrue(out.get(0).paid());
        assertTrue(out.get(1).unpaid());
        plan.setState(0, ScheduledPayment.STATE_UNPAID);
        assertTrue(ScheduledPayments.occurrences(java.util.Collections.singletonList(plan),
            Long.MIN_VALUE, Long.MAX_VALUE).get(0).unpaid());
    }

    @Test public void dateEndedPlanIncludesItsLastOccurrence() {
        ScheduledPayment plan = new ScheduledPayment("sub", "Subscription", ScheduledPayment.Type.SUBSCRIPTION,
            1000, CalendarSystem.GREGORIAN, 2025, 1, 10,
            ScheduledPayment.Frequency.MONTHLY, ScheduledPayment.EndMode.DATE,
            2025, 3, 10, 0);
        assertEquals(3, ScheduledPayments.occurrences(java.util.Collections.singletonList(plan),
            Long.MIN_VALUE, Long.MAX_VALUE).size());
    }

    @Test public void weeklyAndJalaliSchedulesKeepTheirCalendar() {
        ScheduledPayment plan = new ScheduledPayment("weekly", "Weekly", ScheduledPayment.Type.DEBT,
            1000, CalendarSystem.JALALI, 1403, 12, 25,
            ScheduledPayment.Frequency.WEEKLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 2);
        List<PaymentOccurrence> out = ScheduledPayments.occurrences(
            java.util.Collections.singletonList(plan), Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(CalendarSystem.JALALI, out.get(0).date.calendar);
        assertEquals(2, out.size());
        assertEquals(2, out.get(1).date.day);
        assertEquals(1, out.get(1).date.month);
    }

    @Test public void amountParserNormalizesPersianDigitsAndToman() {
        assertEquals(125000L, ScheduledPayments.parseAmountRial("۱۲٬۵۰۰", true));
        assertEquals(12500L, ScheduledPayments.parseAmountRial("12,500", false));
    }

    @Test public void serializedPlanKeepsCalendarAndOccurrenceState() throws Exception {
        ScheduledPayment plan = new ScheduledPayment("backup", "Backup plan", ScheduledPayment.Type.LOAN,
            2500, CalendarSystem.JALALI, 1403, 7, 1,
            ScheduledPayment.Frequency.MONTHLY, ScheduledPayment.EndMode.COUNT,
            0, 0, 0, 12);
        plan.setState(3, ScheduledPayment.STATE_PAID);
        plan.setState(4, ScheduledPayment.STATE_UNPAID);
        List<ScheduledPayment> restored = BalanceData.deserializeScheduledPayments(
            BalanceData.serializeScheduledPayments(java.util.Collections.singletonList(plan)));
        assertEquals(1, restored.size());
        assertEquals(CalendarSystem.JALALI, restored.get(0).calendar);
        assertEquals(ScheduledPayment.STATE_PAID, restored.get(0).state(3));
        assertEquals(ScheduledPayment.STATE_UNPAID, restored.get(0).state(4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void amountParserRejectsOverflow() {
        ScheduledPayments.parseAmountRial("9223372036854775807", true);
    }

    @Test public void summaryCountsPaidSkippedOverdueCurrentAndFutureWithoutExpansion() {
        ScheduledDate today = today(CalendarSystem.GREGORIAN);
        ScheduledDate monthStart = new ScheduledDate(today.calendar, today.year, today.month, 1);
        ScheduledDate monthEnd = new ScheduledDate(today.calendar, today.year, today.month,
            today.calendar.daysInMonth(today.year, today.month));
        ScheduledPayment plan = oneTime("summary", "Summary", monthStart.plusDays(1));
        ScheduledPayment overdue = oneTime("summary-overdue", "Overdue", monthStart.plusDays(-1));
        ScheduledPayment paid = oneTime("summary-paid", "Paid", monthStart.plusDays(2));
        paid.setState(0, ScheduledPayment.STATE_PAID);
        ScheduledPayment skipped = oneTime("summary-skipped", "Skipped", monthStart.plusDays(3));
        skipped.setState(0, ScheduledPayment.STATE_SKIPPED);
        ScheduledPayment future = oneTime("summary-future", "Future", monthEnd.plusDays(1));
        ScheduledPaymentSummary summary = ScheduledPayments.summary(
            java.util.Arrays.asList(plan, overdue, paid, skipped, future), today, 10000, true);
        assertEquals(1, summary.currentMonthCount);
        assertEquals(1000, summary.currentMonthUnpaid);
        assertEquals(1, summary.overdueCount);
        assertEquals(1000, summary.overdueAmount);
        assertEquals(1, summary.currentMonthPaidCount);
        assertEquals(1, summary.currentMonthSkippedCount);
        assertEquals(1, summary.futureUnpaidCount);
        assertEquals(1000, summary.futureUnpaidAmount);
        assertEquals(9000, summary.remainingAfterUnpaid);
    }

    @Test public void summaryUsesJalaliMonthBoundaries() {
        ScheduledDate today = today(CalendarSystem.JALALI);
        ScheduledDate start = new ScheduledDate(today.calendar, today.year, today.month, 1);
        ScheduledDate end = new ScheduledDate(today.calendar, today.year, today.month,
            today.calendar.daysInMonth(today.year, today.month));
        ScheduledPaymentSummary summary = ScheduledPayments.summary(
            java.util.Arrays.asList(oneTime("jalali-current", "Current", start.plusDays(1)),
                oneTime("jalali-overdue", "Overdue", start.plusDays(-1)),
                oneTime("jalali-future", "Future", end.plusDays(1))), today, 0, false);
        assertEquals(1, summary.currentMonthCount);
        assertEquals(1, summary.overdueCount);
        assertEquals(1, summary.futureUnpaidCount);
        assertFalse(summary.hasIncludedBalance);
    }

    private static ScheduledPayment oneTime(String id, String title, ScheduledDate date) {
        return new ScheduledPayment(id, title, ScheduledPayment.Type.ONE_TIME, 1000,
            date.calendar, date.year, date.month, date.day, ScheduledPayment.Frequency.ONCE,
            ScheduledPayment.EndMode.NEVER, 0, 0, 0, 0);
    }

    private static ScheduledDate today(CalendarSystem calendar) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return ScheduledDate.fromGregorian(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH), calendar);
    }
}
