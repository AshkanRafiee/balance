package com.ashkanrafiee.balance;

/**
 * A single transaction parsed from a bank SMS: a signed money movement (deposit or withdrawal) and
 * the time it was reported. Unlike a {@link Bank} balance (which is a point-in-time remaining amount),
 * a transaction records the movement itself, so history can sum them over a day, month or year.
 */
final class Transaction {
    /** Canonical bank name (the storage/lookup key used by {@link BankRules}). */
    final String bank;
    /** Epoch millis of the SMS that reported the transaction. */
    final long date;
    /** Signed amount in rials: positive for a deposit, negative for a withdrawal. */
    final long amount;

    Transaction(String bank, long date, long amount) {
        this.bank = bank;
        this.date = date;
        this.amount = amount;
    }
}
