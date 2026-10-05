package com.ashkanrafiee.balance;

/** Small immutable dashboard snapshot, calculated off the UI thread. */
final class ScheduledPaymentSummary {
    final boolean hasPlans;
    final boolean hasIncludedBalance;
    final long currentMonthUnpaid;
    final int currentMonthCount;
    final long overdueAmount;
    final int overdueCount;
    final long remainingAfterUnpaid;
    /** Completed occurrences in the current month, split so callers can display status totals. */
    final int currentMonthPaidCount;
    final int currentMonthSkippedCount;
    /** Unpaid amount/count after the current month, calculated without expanding the history. */
    final long futureUnpaidAmount;
    final int futureUnpaidCount;

    ScheduledPaymentSummary(boolean hasPlans, boolean hasIncludedBalance, long currentMonthUnpaid,
            int currentMonthCount, long overdueAmount, int overdueCount, long remainingAfterUnpaid) {
        this(hasPlans, hasIncludedBalance, currentMonthUnpaid, currentMonthCount, overdueAmount,
            overdueCount, remainingAfterUnpaid, 0, 0, 0, 0);
    }

    ScheduledPaymentSummary(boolean hasPlans, boolean hasIncludedBalance, long currentMonthUnpaid,
            int currentMonthCount, long overdueAmount, int overdueCount, long remainingAfterUnpaid,
            int currentMonthPaidCount, int currentMonthSkippedCount, long futureUnpaidAmount,
            int futureUnpaidCount) {
        this.hasPlans = hasPlans;
        this.hasIncludedBalance = hasIncludedBalance;
        this.currentMonthUnpaid = currentMonthUnpaid;
        this.currentMonthCount = currentMonthCount;
        this.overdueAmount = overdueAmount;
        this.overdueCount = overdueCount;
        this.remainingAfterUnpaid = remainingAfterUnpaid;
        this.currentMonthPaidCount = currentMonthPaidCount;
        this.currentMonthSkippedCount = currentMonthSkippedCount;
        this.futureUnpaidAmount = futureUnpaidAmount;
        this.futureUnpaidCount = futureUnpaidCount;
    }

    static ScheduledPaymentSummary empty() {
        return new ScheduledPaymentSummary(false, false, 0, 0, 0, 0, 0);
    }
}
