package com.ashkanrafiee.balance;

/** Digit normalization shared by the parsers: Persian (۰-۹) and Arabic-Indic (٠-٩) digits are
 *  mapped to ASCII so pattern matching and amount parsing always run over a single script. */
final class Digits {
    private Digits() {}

    /** Converts Persian and Arabic-Indic digits to ASCII, leaving every other character as is. */
    static String ascii(String s) {
        return TextNormalizer.asciiDigits(s);
    }
}
