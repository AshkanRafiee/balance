package com.ashkanrafiee.balance;

import android.graphics.Color;

import java.util.Locale;

/** The colored square with a bank's initials drawn by the dashboard and the history screen: the
 *  canonical (English) name keeps a bank's badge stable across languages, and the same palette in
 *  both screens makes a bank look identical everywhere. */
final class BankBadge {
    static final int[] COLORS = {
        Color.rgb(14, 165, 233), Color.rgb(139, 92, 246),
        Color.rgb(16, 185, 129), Color.rgb(245, 158, 11),
        Color.rgb(244, 63, 94), Color.rgb(20, 184, 166)
    };

    private BankBadge() {}

    /** The palette color for a canonical bank name. */
    static int colorFor(String canonicalName) {
        return COLORS[Math.floorMod(canonicalName.hashCode(), COLORS.length)];
    }

    /** Up to two initials from the canonical name, e.g. "PUK" for "Post Bank of Iran". */
    static String initials(String name) {
        String canonical = name.trim();
        if (canonical.isEmpty()) return "?";
        String[] words = canonical.split(" ");
        if (words.length > 1 && words[0].length() > 0 && words[1].length() > 0) {
            return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(Locale.US);
        }
        return canonical.substring(0, Math.min(2, canonical.length())).toUpperCase(Locale.US);
    }
}