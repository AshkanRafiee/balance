package com.ashkanrafiee.balance;

/**
 * Length-preserving redaction of digits in text a reader is about to share.
 *
 * <p>The rule keying on bank messages is positional, so a sample stays useful only if every number
 * keeps its length and every separator, digit style and line break stays where it was. This replaces
 * each digit with another digit of the same script and never changes anything else, which keeps a
 * redacted sample parseable by a rule written against the original — and keeps the reader's account
 * number, reference codes and amounts out of it.
 *
 * <p>The replacement is deterministic: the same digit always becomes the same digit, so a preview
 * does not shuffle under the reader while they read it, and two copies of one message redact to the
 * same text. It is deliberately not reversible — nothing here keeps the original — and it is
 * deliberately not a guarantee: a message can carry a name, a place or a merchant that is worth
 * more to someone than a number is. So this is offered as assistance next to the exact text, never
 * instead of it, and the reader is the one who decides.
 *
 * <p>Persian and Arabic-Indic digits stay in their own script, because a Persian number replaced by
 * a Latin one changes the layout the rule keys on.
 */
final class Redactor {

    private static final char LATIN_ZERO = '0';
    /** Persian ۰-۹. */
    private static final char PERSIAN_ZERO = '۰';
    /** Arabic-Indic ٠-٩. */
    private static final char ARABIC_ZERO = '٠';

    private Redactor() {}

    /** The text with every digit replaced by another digit of the same script and length, and
     *  nothing else touched: no letter, separator, space or line break moves. */
    static String redact(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) out.append(replacement(text.charAt(i)));
        return out.toString();
    }

    /** One digit becomes another digit; everything else is itself. The new digit is a rotation of
     *  the old one by seven, which keeps a run of repeated digits from becoming a run of the same
     *  digit (a masked account must not read as 000000) while staying trivially checkable by eye. */
    private static char replacement(char c) {
        if (c >= LATIN_ZERO && c <= '9') return (char) (LATIN_ZERO + (c - LATIN_ZERO + 7) % 10);
        if (c >= PERSIAN_ZERO && c <= '۹') return (char) (PERSIAN_ZERO + (c - PERSIAN_ZERO + 7) % 10);
        if (c >= ARABIC_ZERO && c <= '٩') return (char) (ARABIC_ZERO + (c - ARABIC_ZERO + 7) % 10);
        return c;
    }

    /** Whether the text still carries any digit the redactor would have touched, which is how the
     *  preview can say plainly that a sample has been rewritten rather than implying it has not. */
    static boolean hasDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= LATIN_ZERO && c <= '9') return true;
            if (c >= PERSIAN_ZERO && c <= '۹') return true;
            if (c >= ARABIC_ZERO && c <= '٩') return true;
        }
        return false;
    }
}