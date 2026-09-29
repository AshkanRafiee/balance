package com.ashkanrafiee.balance;

final class Bank {
    String name, sender, account;
    long amount, date;
    /** ISO-style code of the currency {@link #amount} is denominated in, never null; the rial
     *  default for every message the app parses today. */
    String currency = BalanceData.IRR;
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
}