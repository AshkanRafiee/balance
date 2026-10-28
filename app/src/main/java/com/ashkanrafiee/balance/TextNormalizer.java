package com.ashkanrafiee.balance;

import java.text.Normalizer;

/**
 * Normalizes the harmless spelling and presentation differences found in Persian and Arabic SMS
 * text before a parser rule sees it. This deliberately does not change whitespace or ordinary
 * punctuation, because line boundaries and separators carry meaning to the bank layouts.
 */
final class TextNormalizer {
    private TextNormalizer() {}

    /**
     * Returns a parser-safe form while preserving the text's line structure. Compatibility
     * normalization folds presentation forms and fullwidth punctuation; the explicit mappings cover
     * Arabic/Persian letter variants and the two numeric scripts used by Iranian banks.
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String compatible = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(compatible.length());
        for (int i = 0; i < compatible.length();) {
            int cp = compatible.codePointAt(i);
            i += Character.charCount(cp);

            if (isInvisible(cp) || cp == 0x0640 || isMark(cp)) continue;

            if (cp >= 0x06F0 && cp <= 0x06F9) {
                out.append((char) ('0' + cp - 0x06F0));
            } else if (cp >= 0x0660 && cp <= 0x0669) {
                out.append((char) ('0' + cp - 0x0660));
            } else if (cp == 0x064A || cp == 0x0649 || cp == 0x06CC) {
                out.append('\u06CC'); // Arabic yeh / alef maksura -> Persian yeh
            } else if (cp == 0x0643) {
                out.append('\u06A9'); // Arabic kaf -> Persian kaf
            } else if (cp == 0x0622 || cp == 0x0623 || cp == 0x0625 || cp == 0x0671) {
                out.append('\u0627'); // alef with hamza / madda -> bare alef
            } else if (cp == 0x0629) {
                out.append('\u0647'); // Arabic teh marbuta -> Persian heh
            } else if (cp == 0x066C || cp == 0x060C) {
                out.append(',');
            } else if (cp == 0x066B) {
                // Keep Arabic decimal amounts consistent with the existing ASCII-decimal behavior.
                out.append('.');
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }

    private static boolean isInvisible(int cp) {
        return cp == 0x061C || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F
            || cp == 0xFEFF || (cp >= 0x202A && cp <= 0x202E)
            || (cp >= 0x2066 && cp <= 0x2069);
    }
}
