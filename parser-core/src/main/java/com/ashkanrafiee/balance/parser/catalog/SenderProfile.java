package com.ashkanrafiee.balance.parser.catalog;

import java.util.Map;
import java.util.Objects;

/** Named sender policy shared across packs. A profile owns normalization and the
 * optional suffix-matching contract; the compiled index applies it to an ordered
 * sender table. Profiles are immutable and registered by id so a catalog can
 * declare which policy it was compiled under. */
public final class SenderProfile {
    /** The frozen legacy Iranian contract: fold digits, keep letters/digits,
     * lowercase, strip the "0098" prefix and a "98" prefix longer than 8 digits,
     * then match exactly, falling back to symmetric numeric suffix matching for
     * all-digit senders of at least five digits. Declaration order breaks ties. */
    public static final SenderProfile IR_LEGACY_S1 = new SenderProfile("ir-legacy-s1", 5);

    private static final Map<String, SenderProfile> REGISTRY = Map.of(IR_LEGACY_S1.id(), IR_LEGACY_S1);

    private final String id;
    private final int suffixMinDigits;

    private SenderProfile(String id, int suffixMinDigits) {
        this.id = id;
        this.suffixMinDigits = suffixMinDigits;
    }

    public String id() { return id; }

    /** Minimum effective digit length for suffix matching; senders shorter than
     * this (or not all digits) only match exactly. */
    public int suffixMinDigits() { return suffixMinDigits; }

    public static SenderProfile of(String id) {
        return REGISTRY.get(id);
    }

    public boolean suffixCandidate(String normalized) {
        if (normalized.length() < suffixMinDigits) return false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Mirrors the frozen legacy normalization: Persian/Arabic digits are folded,
     * only letters and digits survive, letters are lowercased, then the "0098" and
     * stray "98" prefixes are stripped exactly as the legacy contract does. */
    public String normalize(String raw) {
        Objects.requireNonNull(raw);
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            char d = foldDigit(c);
            if (Character.isLetterOrDigit(d)) out.append(Character.toLowerCase(d));
        }
        String s = out.toString();
        if (s.startsWith("0098")) s = s.substring(4);
        if (s.startsWith("98") && s.length() > 8) s = s.substring(2);
        return s;
    }

    private static char foldDigit(char c) {
        if (c >= '\u06F0' && c <= '\u06F9') return (char) ('0' + c - '\u06F0');
        if (c >= '\u0660' && c <= '\u0669') return (char) ('0' + c - '\u0660');
        return c;
    }
}