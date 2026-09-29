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
    /** ISO-style code of the currency {@link #amount} is denominated in, never null; the rial
     *  default for every message the app parses today. */
    final String currency;
    /** ISO-style code of the currency {@link #balance} is denominated in, never null, and equal to
     *  {@link #currency} for every Iranian message.
     *
     *  <p>It is a second code only because a card can be spent in one currency and settled in
     *  another, so the two figures on the same message are two sums of money rather than one. Where
     *  they differ, the balance moved by an amount that includes a conversion, and the app knows
     *  neither the rate nor the converted amount: it stores both figures in their own currencies and
     *  never adds, subtracts or compares them. */
    final String balanceCurrency;

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
        this(bank, account, date, amount, balance, sig, content, BalanceData.IRR);
    }

    Transaction(String bank, String account, long date, long amount, Long balance, String sig,
            String content, String currency) {
        this(bank, account, date, amount, balance, sig, content, currency, currency);
    }

    Transaction(String bank, String account, long date, long amount, Long balance, String sig,
            String content, String currency, String balanceCurrency) {
        this.bank = bank;
        this.account = account;
        this.date = date;
        this.amount = amount;
        this.balance = balance;
        this.sig = sig;
        this.content = content;
        this.currency = currency == null ? BalanceData.IRR : currency;
        this.balanceCurrency = balanceCurrency == null ? BalanceData.IRR : balanceCurrency;
    }

    /** Whether the movement and the balance the same message reported are two sums in two
     *  currencies, which is what a card conversion looks like from here. */
    boolean converted() {
        return balance != null && !currency.equals(balanceCurrency);
    }
}
