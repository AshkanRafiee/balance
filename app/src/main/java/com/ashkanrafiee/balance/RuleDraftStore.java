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
import java.security.KeyStoreException;
import java.security.ProviderException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.BadPaddingException;
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
    /** The keystore's key handle, kept for as long as this store is open. The key itself never
     *  leaves the keystore; holding the handle saves a keystore load on every write, and the
     *  builder writes on every edit the reader makes. */
    private SecretKey held;

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
            return decode(PlatformRuleJson.read(new ByteArrayInputStream(plain)));
        } catch (javax.crypto.AEADBadTagException unreadable) {
            // The bytes are not what this keystore sealed, so there is nothing to open and nothing
            // worth keeping.
            clear();
            return null;
        } catch (KeyStoreException | ProviderException | java.security.InvalidKeyException
                | java.security.NoSuchAlgorithmException | BadPaddingException
                | IllegalStateException temporarily) {
            // The keystore itself was unavailable, not the draft. Dropping the reader's
            // half-finished rule over a momentary failure would lose work they cannot retype,
            // because the plain text exists nowhere else.
            return null;
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
        // The file holds ciphertext, so there is nothing in it to overwrite and nothing in it to
        // recover: the words exist nowhere but in this process, under the key. Removal is the whole
        // of forgetting it, and a partial write before it buys no protection and costs a chance to
        // fail.
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
            append(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    /** Writes one value. Numbers are written as integers because that is all the draft holds, and a
     *  nested object is written as an object because the highlight anchors are a map of role to
     *  span: writing either as null or as zero would lose the anchors and leave a restored draft
     *  that looks filled in but reads nothing. */
    private static void append(StringBuilder out, Object value) {
        if (value instanceof String) out.append(quote((String) value));
        else if (value instanceof Boolean) out.append(value.toString());
        else if (value instanceof BigDecimal) out.append(((BigDecimal) value).toBigInteger()
                .toString());
        else if (value instanceof Integer) out.append(value.toString());
        else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(String.valueOf(entry.getKey()))).append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean inner = true;
            for (Object item : (List<?>) value) {
                if (!inner) out.append(',');
                inner = false;
                append(out, item);
            }
            out.append(']');
        } else out.append("null");
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
            out.put(entry.getKey(), plain(entry.getValue()));
        }
        return out;
    }

    /** One value with the JSON reader's numbers turned into integers, all the way down: the anchors
     *  are a map of lists, and a span read as BigDecimal would not fit where a start and an end are
     *  expected. */
    private static Object plain(Object value) {
        // Offsets and flags only ever hold small whole numbers, and a reader that compares what
        // comes back against what it wrote should not have to know which numeric type it got.
        if (value instanceof BigDecimal) {
            java.math.BigInteger whole = ((BigDecimal) value).toBigIntegerExact();
            return whole.bitLength() < 32 ? (Object) whole.intValue() : whole;
        }
        if (value instanceof Number) return value;
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) items.add(plain(item));
            return items;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
            }
            return out;
        }
        return value;
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

    private SecretKey key(boolean create) throws Exception {
        if (held != null) return held;
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            held = (SecretKey) store.getKey(ALIAS, null);
            return held;
        }
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
        held = generator.generateKey();
        return held;
    }
}