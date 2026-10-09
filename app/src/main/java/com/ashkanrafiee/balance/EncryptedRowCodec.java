package com.ashkanrafiee.balance;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Session-scoped AES-GCM codec for rows owned by one SQLite store.
 *
 * <p>The data key is software-held only for the lifetime of a store session. Its serialized form
 * is still protected by the application's Keystore bridge, while row payloads use the software
 * key so large stores do not invoke Keystore once per row. Each call uses a fresh cipher with a
 * random nonce, so concurrent calls are safe; a codec cannot be used after it has been closed.
 */
final class EncryptedRowCodec implements AutoCloseable {
    static final String PREFIX = "ER1:";
    private static final String AAD_PREFIX = "balance-encrypted-row\n1\n";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final String domain;
    private final SecureRandom random = new SecureRandom();
    private boolean closed;

    private EncryptedRowCodec(byte[] key, String domain) {
        this.key = key;
        this.domain = domain;
    }

    /** Creates and Keystore-wraps a fresh independent AES-256 row key. */
    static String createWrappedKey() throws Exception {
        byte[] key = new byte[KEY_BYTES];
        try {
            new SecureRandom().nextBytes(key);
            return BalanceData.encryptStorePayload(Base64.encodeToString(key, Base64.NO_WRAP));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static boolean isVersioned(String encoded) {
        return encoded != null && encoded.length() > 3 && encoded.startsWith("ER")
            && encoded.charAt(2) >= '0' && encoded.charAt(2) <= '9'
            && encoded.charAt(3) == ':';
    }

    /** Unwraps a store key once and keeps only the raw key for this session. */
    static EncryptedRowCodec open(String wrappedKey, String domain) throws Exception {
        if (wrappedKey == null || wrappedKey.isEmpty()) throw new Exception("missing row key");
        if (domain == null || domain.isEmpty()) throw new IllegalArgumentException("missing row domain");
        byte[] decoded = null;
        byte[] key = null;
        boolean transferred = false;
        try {
            decoded = Base64.decode(BalanceData.decryptStorePayload(wrappedKey), Base64.NO_WRAP);
            if (decoded.length != KEY_BYTES) throw new Exception("invalid row key");
            key = decoded.clone();
            EncryptedRowCodec result = new EncryptedRowCodec(key, domain);
            transferred = true;
            return result;
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            // Ownership transfers to the codec only on the successful return above.
            if (key != null && !transferred) Arrays.fill(key, (byte) 0);
        }
    }

    String encrypt(String plaintext, String identity) throws Exception {
        checkThread();
        if (plaintext == null) throw new NullPointerException("plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] input = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] associated = aad(identity);
            try {
                cipher.updateAAD(associated);
            } finally {
                Arrays.fill(associated, (byte) 0);
            }
            encrypted = cipher.doFinal(input);
            byte[] output = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            try {
                return PREFIX + Base64.encodeToString(output, Base64.NO_WRAP);
            } finally {
                Arrays.fill(output, (byte) 0);
            }
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(input, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    /** Reads an ER1 row, or permanently supports the old Keystore row format when unprefixed. */
    String decrypt(String encoded, String identity) throws Exception {
        checkThread();
        if (encoded == null) throw new NullPointerException("encoded");
        if (!encoded.startsWith(PREFIX)) {
            if (isVersioned(encoded))
                throw new Exception("unsupported encrypted row version");
            return BalanceData.decryptStorePayload(encoded);
        }

        byte[] input = null;
        byte[] nonce = null;
        byte[] encrypted = null;
        try {
            input = Base64.decode(encoded.substring(PREFIX.length()), Base64.NO_WRAP);
            if (input.length < NONCE_BYTES + TAG_BITS / 8)
                throw new Exception("invalid encrypted row");
            nonce = Arrays.copyOfRange(input, 0, NONCE_BYTES);
            encrypted = Arrays.copyOfRange(input, NONCE_BYTES, input.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] associated = aad(identity);
            try {
                cipher.updateAAD(associated);
            } finally {
                Arrays.fill(associated, (byte) 0);
            }
            byte[] plaintext = cipher.doFinal(encrypted);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } finally {
            if (input != null) Arrays.fill(input, (byte) 0);
            if (nonce != null) Arrays.fill(nonce, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    @Override public synchronized void close() {
        if (!closed) {
            Arrays.fill(key, (byte) 0);
            closed = true;
        }
    }

    private SecretKeySpec keySpec() {
        return new SecretKeySpec(key, "AES");
    }

    private byte[] aad(String identity) {
        if (identity == null || identity.isEmpty()) throw new IllegalArgumentException("missing row identity");
        return (AAD_PREFIX + domain + "\n" + identity).getBytes(StandardCharsets.UTF_8);
    }

    private synchronized void checkThread() {
        if (closed) throw new IllegalStateException("row codec is closed");
    }
}
