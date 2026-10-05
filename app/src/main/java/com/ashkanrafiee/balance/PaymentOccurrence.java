package com.ashkanrafiee.balance;

/** A derived occurrence of a scheduled payment; it is never stored as an SMS transaction. */
final class PaymentOccurrence {
    final ScheduledPayment payment;
    final int sequence;
    final ScheduledDate date;
    final int state;

    PaymentOccurrence(ScheduledPayment payment, int sequence, ScheduledDate date) {
        this.payment = payment;
        this.sequence = sequence;
        this.date = date;
        this.state = payment.state(sequence);
    }

    boolean paid() { return state == ScheduledPayment.STATE_PAID; }
    boolean skipped() { return state == ScheduledPayment.STATE_SKIPPED; }
    boolean unpaid() { return state == ScheduledPayment.STATE_UNPAID; }
    String key() { return payment.id + ":" + sequence; }
}
