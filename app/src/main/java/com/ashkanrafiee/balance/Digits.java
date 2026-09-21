package com.ashkanrafiee.balance;

/** Digit normalization shared by the parsers: Persian (۰-۹) and Arabic-Indic (٠-٩) digits are
 *  mapped to ASCII so pattern matching and amount parsing always run over a single script. */
final class Digits {
    private Digits() {}

    /** Converts Persian and Arabic-Indic digits to ASCII, leaving every other character as is. */
    static String ascii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\u06F0' && c <= '\u06F9')
                b.append((char) ('0' + c - '\u06F0'));
            else if (c >= '\u0660' && c <= '\u0669')
                b.append((char) ('0' + c - '\u0660'));
            else b.append(c);
        }
        return b.toString();
    }
}