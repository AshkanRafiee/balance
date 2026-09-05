package com.ashkanrafiee.balance;

final class Bank {
    String name, sender;
    long amount, date;
    Bank(String n, long a, long d, String s) {
        name = n; amount = a; date = d; sender = s;
    }
}