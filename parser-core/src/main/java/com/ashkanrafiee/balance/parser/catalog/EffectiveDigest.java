package com.ashkanrafiee.balance.parser.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Deterministic effective digest over the canonical compiled catalog content.
 * The digest binds engine, profile, declaration order, calendars, normalized
 * senders and allowlist pins; it is the identity an activation or persistence
 * layer can use to decide whether a compiled catalog actually changed. */
public final class EffectiveDigest {
    private EffectiveDigest() {}

    public static String canonical(List<String> orderedLines) {
        return String.join("\n", orderedLines);
    }

    public static String sha256(String canonicalContent) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalContent.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}