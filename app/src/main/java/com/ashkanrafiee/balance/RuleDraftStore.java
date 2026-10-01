package com.ashkanrafiee.balance;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The half-finished rule a reader has not saved yet, kept between visits to the builder.
 *
 * <p>An unfinished rule is the one thing this app holds that is both derived from a real bank
 * message and not a rule yet: it contains the words a bank sent and whatever the reader had
 * highlighted. So it is sealed with a keystore key that never leaves the device, stored where the
 * platform will not copy it to a backup or to another phone, and never written in the clear even
 * for a moment. There is one draft at a time, which is what a reader editing one message has; a
 * second draft is the first one's work being abandoned, and keeping both would keep a message the
 * reader thought they had thrown away.
 *
 * <p>A draft that cannot be read is dropped rather than reported. Unlike a saved pack, there is
 * nothing to recover and nobody waiting on it: the reader still has the message they pasted, and a
 * half-written rule is worth rebuilding from a fresh example rather than restoring from a
 * ciphertext the app can no longer open.
 */
final class RuleDraftStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "balance.rule.draft";
    private static final String FILE = "draft.bin";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    /** A draft is one example message with four highlights. Anything larger is not one. */
    private static final int MAX_DRAFT = 64 * 1024;

    private final File file;

    RuleDraftStore(Context context) {
        this.file = new File(context.getNoBackupFilesDir(), FILE);
    }

    boolean present() {
        return file.isFile() && file.length() > 0;
    }

    /** The saved state, or null when there is none or it cannot be opened. */
    Map<String, Object> read() {
        if (!present()) return null;
        try {
            byte[] blob = readFile();
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return PlatformRuleJson.read(new ByteArrayInputStream(plain));
        } catch (Exception unusable) {
            clear();
            return null;
        }
    }

    /** Replaces the draft in one write. Failure leaves the previous draft intact rather than a
     *  half-written one, and is reported so the screen can say the draft was not kept. */
    boolean write(Map<String, Object> state) {
        if (state == null) {
            clear();
            return true;
        }
        try {
            byte[] plain = encode(state).getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_DRAFT) return false;
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(plain);
            byte[] blob = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(sealed, 0, blob, iv.length, sealed.length);
            File temporary = new File(file.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temporary, false)) {
                out.write(blob);
                out.flush();
                out.getFD().sync();
            }
            if (!temporary.renameTo(file)) {
                temporary.delete();
                return false;
            }
            return true;
        } catch (Exception failed) {
            return false;
        }
    }

    /** Forgets the draft. Used when the reader saves, discards, or starts a different message:
     *  a message the reader is done with should not stay on the device because a screen was left
     *  open. */
    void clear() {
        // Overwrite first: on a filesystem that does not immediately release the blocks, a plain
        // delete leaves the words recoverable from the device's own storage.
        if (file.isFile()) {
            try {
                byte[] empty = new byte[(int) Math.min(file.length(), MAX_DRAFT)];
                java.util.Arrays.fill(empty, (byte) 0);
                java.io.FileOutputStream out = new java.io.FileOutputStream(file, false);
                try {
                    out.write(empty);
                    out.flush();
                    out.getFD().sync();
                } finally {
                    out.close();
                }
            } catch (IOException ignored) {
                // The delete below is what matters; an unoverwritable file is still removed.
            }
        }
        file.delete();
        new File(file.getPath() + ".tmp").delete();
    }

    /**
     * The draft as JSON text. Only the three value kinds a draft actually holds are written --
     * strings, whole numbers and booleans -- and nothing else is accepted, so the writer cannot
     * reach a value the reader would not take back. The document is small by construction (one
     * message, four highlights), and {@link #MAX_DRAFT} holds the result inside the size the
     * platform reader accepts.
     */
    static String encode(Map<String, Object> state) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof String) out.append(quote((String) value));
            else if (value instanceof Boolean) out.append(value.toString());
            else if (value instanceof BigDecimal) out.append(((BigDecimal) value).toBigInteger()
                    .toString());
            else if (value instanceof List) {
                out.append('[');
                boolean inner = true;
                for (Object item : (List<?>) value) {
                    if (!inner) out.append(',');
                    inner = false;
                    if (item instanceof String) out.append(quote((String) item));
                    else if (item instanceof BigDecimal) out.append(((BigDecimal) item)
                            .toBigInteger().toString());
                    else out.append("0");
                }
                out.append(']');
            } else out.append("null");
        }
        return out.append('}').toString();
    }

    /** A JSON string, escaped the way the platform reader's lexical guard requires: control
     *  characters as escapes, and everything else as the characters themselves so a Persian
     *  message stays readable while debugging. */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** The draft's own fields, read back in the order they were written, with numbers as the
     *  integers they were rather than the BigDecimals the JSON reader produces. */
    static Map<String, Object> decode(Map<String, Object> state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) return out;
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof BigDecimal) out.put(entry.getKey(),
                    ((BigDecimal) value).toBigInteger());
            else if (value instanceof List) {
                List<Object> items = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    items.add(item instanceof BigDecimal ? ((BigDecimal) item).toBigInteger()
                            : item);
                }
                out.put(entry.getKey(), items);
            } else out.put(entry.getKey(), value);
        }
        return out;
    }

    private byte[] readFile() throws IOException {
        byte[] blob = new byte[(int) file.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            if (in.read(blob) != blob.length) throw new IOException("short draft");
        } finally {
            in.close();
        }
        return blob;
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("no draft key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT
                | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No user authentication: a draft must be resumable from the lock screen without
            // asking for a fingerprint that enrollment changes would silently invalidate.
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}