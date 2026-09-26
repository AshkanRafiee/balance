package com.ashkanrafiee.balance;

/**
 * A single transaction parsed from a bank SMS: a signed money movement (deposit or withdrawal) and
 * the time it was reported. Unlike a {@link Bank} balance (which is a point-in-time remaining amount),
 * a transaction records the movement itself, so history can sum them over a day, month or year.
 */
final class Transaction {
    /** Canonical bank name (the storage/lookup key used by {@link BankRules}). */
    final String bank;
    /** Account number this transaction belongs to, or null when the bank message did not state one. */
    final String account;
    /** Epoch millis of the SMS that reported the transaction. */
    final long date;
    /** Signed amount in rials: positive for a deposit, negative for a withdrawal. */
    final long amount;
    /** The account balance the same message reported after this movement settled, in rials, or null
     *  when the message stated none (or the entry predates balance capture). Two balance statements
     *  of one account bracket the movements between them, so a movement whose message never arrived
     *  shows up as the difference between the balance change and the movements we did parse — see
     *  {@link Residual}. This is reported state, never an estimate: it is read straight off the
     *  message by the same {@code extract} the balance screen itself trusts. */
    final Long balance;
    /** Deterministic fingerprint of the source SMS, used to reject exact duplicate redeliveries. */
    final String sig;
    /** Parse-independent digest of the source message (sender + normalized body). Unlike {@link #sig}
     *  it folds no parsed values, so it stays identical however the parsing rules change; the history
     *  scan uses it to reconcile stored entries with their re-parsed messages after a rules update.
     *  Null for entries written before this identity existed. */
    final String content;

    Transaction(String bank, long date, long amount) {
        this(bank, date, amount, null);
    }

    Transaction(String bank, long date, long amount, String sig) {
        this(bank, null, date, amount, sig);
    }

    Transaction(String bank, String account, long date, long amount, String sig) {
        this(bank, account, date, amount, sig, null);
    }

    Transaction(String bank, String account, long date, long amount, String sig, String content) {
        this(bank, account, date, amount, null, sig, content);
    }

    Transaction(String bank, String account, long date, long amount, Long balance, String sig,
            String content) {
        this.bank = bank;
        this.account = account;
        this.date = date;
        this.amount = amount;
        this.balance = balance;
        this.sig = sig;
        this.content = content;
    }
}
