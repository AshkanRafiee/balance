package com.ashkanrafiee.balance.parser.identity;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Keyed exact-envelope evidence, not an event ID. Does not retain the key or source text. */
public final class EnvelopeEvidence {
    public static final int VERSION = 1;
    public static final int MAX_SENDER_CHARS = 4096;
    public static final int MAX_BODY_CHARS = 1_048_576;
    private final String keyId;
    private final long arrivalMillis;
    private final byte[] digest;

    /** Imports caller-computed evidence using the encoding specified by {@link #compute}. */
    public EnvelopeEvidence(int version, String keyId, long arrivalMillis, byte[] digest) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported evidence version");
        this.keyId = Names.require(keyId);
        if (arrivalMillis < 0) throw new IllegalArgumentException("Negative provider arrival");
        this.arrivalMillis = arrivalMillis;
        Objects.requireNonNull(digest, "digest");
        if (digest.length != 32) throw new IllegalArgumentException("Invalid evidence length");
        this.digest = digest.clone();
    }

    /**
     * HMAC-SHA256 over: ASCII "balance-envelope", byte 1, sender, body, arrival.
     * Each string is a four-byte big-endian UTF-16 code-unit count followed by exactly
     * that many two-byte big-endian code units (including unpaired surrogates).
     * Arrival is prefixed by four-byte length 8, then eight-byte big-endian epoch millis.
     * No normalization or lossy charset conversion occurs. The caller persists the stable
     * key and its public key ID securely; rotation does not imply cross-key equivalence.
     */
    public static EnvelopeEvidence compute(String keyId, SecretKey key, String sender,
            String body, long arrivalMillis) {
        Names.require(keyId);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(body, "body");
        if (sender.length() > MAX_SENDER_CHARS || body.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("Envelope limit exceeded");
        }
        if (arrivalMillis < 0) throw new IllegalArgumentException("Negative provider arrival");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String domain = "balance-envelope";
            for (int i = 0; i < domain.length(); i++) mac.update((byte) domain.charAt(i));
            mac.update((byte) VERSION);
            string(mac, sender);
            string(mac, body);
            integer(mac, 8);
            for (int shift = 56; shift >= 0; shift -= 8) mac.update((byte) (arrivalMillis >>> shift));
            return new EnvelopeEvidence(VERSION, keyId, arrivalMillis, mac.doFinal());
        } catch (GeneralSecurityException failure) {
            // Provider exceptions can contain key details; do not attach them to diagnostics.
            throw new IllegalArgumentException("Cannot compute envelope evidence");
        }
    }

    private static void string(Mac mac, String value) {
        integer(mac, value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            mac.update((byte) (c >>> 8));
            mac.update((byte) c);
        }
    }

    private static void integer(Mac mac, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) mac.update((byte) (value >>> shift));
    }

    public int version() { return VERSION; }
    public String keyId() { return keyId; }
    public long arrivalMillis() { return arrivalMillis; }
    public byte[] digest() { return digest.clone(); }

    @Override public boolean equals(Object other) {
        if (!(other instanceof EnvelopeEvidence)) return false;
        EnvelopeEvidence that = (EnvelopeEvidence) other;
        return arrivalMillis == that.arrivalMillis && keyId.equals(that.keyId)
                && MessageDigest.isEqual(digest, that.digest);
    }

    @Override public int hashCode() {
        return Objects.hash(keyId, arrivalMillis, Arrays.hashCode(digest));
    }

    @Override public String toString() { return "EnvelopeEvidence[redacted]"; }
}
