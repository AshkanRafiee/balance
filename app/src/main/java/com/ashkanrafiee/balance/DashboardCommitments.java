package com.ashkanrafiee.balance;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
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
        long last = Commitment.millisOf(day[0], day[1], calendar.daysInMonth(day[0], day[1]), calendar);
        LocalDate lastDay = CommitmentSchedule.day(last, ZoneId.systemDefault());
        for (Commitment c : commitments) {
            BigInteger amount = new CommitmentOutstanding(c, calendar, lastDay).amount;
            if (c.isPayment()) totals.pay = totals.pay.add(amount);
            else totals.receive = totals.receive.add(amount);
        }
        return totals;
    }
}
