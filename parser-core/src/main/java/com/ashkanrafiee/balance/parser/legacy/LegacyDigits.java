package com.ashkanrafiee.balance.parser.legacy;

/** Legacy field-specific digit folding; deliberately neither Unicode-wide nor null-tolerant. */
public final class LegacyDigits {
    private LegacyDigits() {}

    public static String ascii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\u06F0' && c <= '\u06F9') b.append((char) ('0' + c - '\u06F0'));
            else if (c >= '\u0660' && c <= '\u0669') b.append((char) ('0' + c - '\u0660'));
            else b.append(c);
        }
        return b.toString();
    }
}
