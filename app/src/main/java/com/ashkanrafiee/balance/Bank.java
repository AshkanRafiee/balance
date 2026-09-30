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
    /** Whether this row is a movement with no balance behind it, which is the only kind of row that
     *  may never be summed, sorted by, or shown as a balance. */
    boolean movementOnly() {
        return !balanceReported;
    }
}
