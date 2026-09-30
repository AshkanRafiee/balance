package com.ashkanrafiee.balance;

final class Bank {
    String name, sender, account;
    long amount, date;
    /** ISO-style code of the currency {@link #amount} is denominated in, never null; the rial
     *  default for every message the app parses today. */
    String currency = BalanceData.IRR;
    /** Whether the bank ever stated a balance for this account. False only for a bank that reports
     *  movements alone: a card or a transfer service tells you what it did, not what you have left,
     *  and {@link #amount} then holds no balance at all. */
    boolean balanceReported = true;
    /** The most recent movement this bank reported for this account, or null when the bank states
     *  balances. Kept so the app can show that it understood a bank that speaks only in movements
     *  without inventing a balance out of them. */
    Long movement;
    /** ISO-style code of the currency {@link #movement} is denominated in, never null; the rial
     *  default. */
    String movementCurrency = BalanceData.IRR;
    Bank(String n, long a, long d, String s) {
        name = n; amount = a; date = d; sender = s;
    }
    Bank(String n, long a, long d, String s, String ac) {
        this(n, a, d, s);
        account = ac;
    }
    Bank(String n, long a, long d, String s, String ac, String cur) {
        this(n, a, d, s, ac);
        if (cur != null) currency = cur;
    }
    /** A row for a bank that reports movements and never a balance.
     *
     *  <p>Every such row is built here rather than at its two construction sites -- the scan that
     *  finds one and the reader that restores one -- because the row has to say three things
     *  together to mean anything: that it holds no balance, which movement it holds, and the
     *  currency that movement is in. A row that says two of the three reads as a balance of zero
     *  rials, which is worse than no row at all. */
    static Bank movementOnly(String name, String account, String sender, long date, long movement,
            String currency) {
        Bank bank = new Bank(name, 0, date, sender, account, currency);
        bank.balanceReported = false;
        bank.movement = movement;
        bank.movementCurrency = currency == null ? BalanceData.IRR : currency;
        return bank;
    }

    /** Whether this row is a movement with no balance behind it, which is the only kind of row that
     *  may never be summed, sorted by, or shown as a balance. */
    boolean movementOnly() {
        return !balanceReported;
    }
}
