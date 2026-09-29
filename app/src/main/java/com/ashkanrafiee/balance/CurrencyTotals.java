package com.ashkanrafiee.balance;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-currency running sums of stored amounts, kept in the order each currency first contributed.
 *
 *  <p>Two amounts in different currencies are not summable, so this never collapses a mixed set
 *  into one number: {@link #only()} names a single currency only when every contribution shares it,
 *  and {@link #get} is the only way to read a sum. Adding is checked — a total that would overflow
 *  {@code long} saturates at the bound in the direction it was already heading, because a wrapped
 *  figure is spectacularly wrong and reads as fact. */
final class CurrencyTotals {
    private final LinkedHashMap<String, Long> sums = new LinkedHashMap<>();

    /** Adds a signed amount to its currency's sum. A null currency is the rial. */
    void add(String currency, long amount) {
        String code = code(currency);
        long current = sums.containsKey(code) ? sums.get(code) : 0L;
        long next;
        try {
            next = Math.addExact(current, amount);
        } catch (ArithmeticException overflow) {
            // Overflow needs both operands to share a sign, so the direction is already decided.
            next = amount > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        sums.put(code, next);
    }

    /** The total of one currency, zero when nothing of that currency was added. */
    long get(String currency) {
        Long total = sums.get(code(currency));
        return total == null ? 0L : total;
    }

    /** The one currency every contribution shares, or null when the set is empty or spans several. */
    String only() {
        return sums.size() == 1 ? sums.keySet().iterator().next() : null;
    }

    int size() {
        return sums.size();
    }

    boolean isEmpty() {
        return sums.isEmpty();
    }

    void clear() {
        sums.clear();
    }

    /** The sums, in the order their currencies first contributed. */
    Map<String, Long> entries() {
        return new LinkedHashMap<>(sums);
    }

    private static String code(String currency) {
        return currency == null || currency.isEmpty() ? BalanceData.IRR : currency;
    }
}
