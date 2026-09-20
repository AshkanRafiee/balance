package com.ashkanrafiee.balance;

final class Bank {
    String name, sender, account;
    long amount, date;
    Bank(String n, long a, long d, String s) {
        name = n; amount = a; date = d; sender = s;
    }
    Bank(String n, long a, long d, String s, String ac) {
        this(n, a, d, s);
        account = ac;
    }
}