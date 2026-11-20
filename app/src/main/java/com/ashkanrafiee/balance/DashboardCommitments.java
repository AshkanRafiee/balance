package com.ashkanrafiee.balance;

import java.math.BigInteger;
import java.util.List;

/** The two amounts on the dashboard: unpaid overdue dues plus the current display month. */
final class DashboardCommitments {
    private BigInteger pay = BigInteger.ZERO;
    private BigInteger receive = BigInteger.ZERO;

    BigInteger remainingPay() { return pay; }
    BigInteger remainingReceive() { return receive; }

    static DashboardCommitments summarize(List<Commitment> commitments, CalendarSystem calendar,
            long now) {
        DashboardCommitments totals = new DashboardCommitments();
        if (commitments.isEmpty()) return totals;
        int[] day = Commitment.civilDay(Commitment.startOfDay(now), calendar);
        long first = Commitment.millisOf(day[0], day[1], 1, calendar);
        long last = Commitment.millisOf(day[0], day[1], calendar.daysInMonth(day[0], day[1]),
            calendar);
        for (Commitment c : commitments) {
            Commitment.OccurrenceVisitor add = due -> {
                if (c.isSettled(due)) return;
                BigInteger amount = BigInteger.valueOf(c.amountAt(due));
                if (c.isPayment()) totals.pay = totals.pay.add(amount);
                else totals.receive = totals.receive.add(amount);
            };
            CommitmentsActivity.visitOverdue(c, calendar, first, add);
            Commitment.visitOccurrences(c, calendar, first, last, add);
        }
        return totals;
    }
}
