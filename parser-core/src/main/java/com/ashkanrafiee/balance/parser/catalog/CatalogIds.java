package com.ashkanrafiee.balance.parser.catalog;

import java.util.Locale;
import java.util.Objects;

/** Stable namespaced bank IDs derived from canonical display names. Runtime
 * identity comes from these IDs, never from display names or file paths; the
 * display name is kept only as adapter-facing metadata. */
public final class CatalogIds {
    private CatalogIds() {}

    public static String bankId(String market, String displayName) {
        Objects.requireNonNull(market);
        Objects.requireNonNull(displayName);
        StringBuilder slug = new StringBuilder(displayName.length());
        for (char c : displayName.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') slug.append(c);
            else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '-') slug.append('-');
        }
        int end = slug.length();
        while (end > 0 && slug.charAt(end - 1) == '-') end--;
        if (end == 0) throw new IllegalArgumentException("no slug for '" + displayName + "'");
        return market + "." + slug.substring(0, end);
    }
}