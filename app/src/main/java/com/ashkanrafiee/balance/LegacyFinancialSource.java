package com.ashkanrafiee.balance;

import android.content.SharedPreferences;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Read-only, single-process legacy adapter. The caller MUST hold BalanceData.class throughout
 * migration.open(source), and serialize every legacy writer with that same monitor. This is not
 * a cross-process preferences snapshot. No production entry point is wired to this adapter.
 *
 * Only the thirteen named financial keys are read, using typed getters on the two injected stores.
 * Absent keys are omitted (including a valid completely empty source); any present invalid value
 * aborts the entire snapshot. Component names equal preference keys. The seven data components
 * and excluded_banks contain original UTF-8 JSON bytes, never reserialized. The five numeric
 * settings contain signed base-10 ASCII/UTF-8 integers with no whitespace or newline.
 *
 * Data strings beginning with '{' are legacy plaintext; others are legacy Base64(12-byte IV ||
 * AES-GCM ciphertext || 128-bit tag), without AAD. excluded_banks is always a plaintext JSON array,
 * as written by the legacy settings serializer. Keys are resolved lazily, once per snapshot that
 * needs decryption, and are never created. Unknown record/envelope fields are validated as JSON
 * and preserved, not interpreted. Dynamic map entries must all satisfy their component schema.
 * Optional historical fields may be absent or null. Required integers must be integer lexemes
 * in signed 64-bit range: strings, decimals and exponents are not silently coerced or rounded.
 *
 * Bounds apply before Base64 decoding/UTF-8 allocation, then to plaintext bytes and total output.
 * Parsing additionally bounds nesting and value/name count; number lexemes are capped at 256
 * characters, including unknown fields. Preferences itself has already loaded
 * its strings; these bounds cannot limit the platform's preferences-loading allocation.
 * Legacy writers had no aggregate cap. Larger valid stores require explicitly chosen source and
 * destination limits after measuring memory use; exceeding these limits never authorizes adoption
 * of a partial snapshot or deletion of the legacy source.
 */
public final class LegacyFinancialSource implements LegacyGenerationMigration.Source {
    private static final String[] DATA = { "balances", "transactions", "transaction_notes",
            "transaction_reasons", "transaction_channels", "recent_movements",
            "history_last_balance" };
    private static final String[] LONGS = { "scanned_through", "history_through" };
    private static final String[] INTS = { "rules_version", "history_rules_version", "history_schema" };
    private static final Pattern NUMBER = Pattern.compile(
            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");

    public interface ExistingKey { SecretKey resolve() throws Exception; }

    /** Fetch-only provider; an absent or non-secret key is an error, never a generation request. */
    public static ExistingKey androidKeyStore() {
        return () -> {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            java.security.Key key = store.getKey("balance_enc_key", null);
            if (!(key instanceof SecretKey)) throw failure("KEY");
            return (SecretKey) key;
        };
    }

    public static final class Limits {
        public final int maxPlaintextBytes, maxDepth, maxNodes;
        public final long maxTotalBytes;

        public Limits(int maxPlaintextBytes, long maxTotalBytes, int maxDepth, int maxNodes) {
            if (maxPlaintextBytes < 1 || maxTotalBytes < 1 || maxDepth < 1 || maxDepth > 128
                    || maxNodes < 1) throw new IllegalArgumentException("LIMITS");
            this.maxPlaintextBytes = maxPlaintextBytes;
            this.maxTotalBytes = maxTotalBytes;
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
        }

        public static Limits defaults() {
            return new Limits(16 * 1024 * 1024, 64L * 1024 * 1024, 32, 2_000_000);
        }
    }

    private final SharedPreferences data, settings;
    private final ExistingKey keys;
    private final Limits limits;

    public LegacyFinancialSource(SharedPreferences data, SharedPreferences settings) {
        this(data, settings, androidKeyStore(), Limits.defaults());
    }

    public LegacyFinancialSource(SharedPreferences data, SharedPreferences settings,
            ExistingKey keys, Limits limits) {
        if (data == null || settings == null || keys == null || limits == null)
            throw new IllegalArgumentException("ARGUMENT");
        this.data = data;
        this.settings = settings;
        this.keys = keys;
        this.limits = limits;
    }

    @Override public Map<String, byte[]> readValidatedSnapshot() throws IOException {
        if (!Thread.holdsLock(BalanceData.class)) throw failure("LOCK");
        try {
            Map<String, byte[]> result = new LinkedHashMap<>();
            SecretKey key = null;
            long total = 0;
            for (String name : DATA) {
                if (!data.contains(name)) continue;
                String stored = data.getString(name, null);
                if (stored == null) throw failure("TYPE");
                byte[] plain;
                long budget = Math.min(limits.maxPlaintextBytes, limits.maxTotalBytes - total);
                if (stored.startsWith("{")) {
                    plain = utf8(stored, budget);
                } else {
                    // Legacy writes NO_WRAP Base64. Reject alternate/garbled encodings as corrupt.
                    long maxEncoded = 4L * ((budget + 28L + 2) / 3);
                    if (stored.length() > maxEncoded) throw failure("LIMIT");
                    if (stored.length() % 4 != 0) throw failure("CIPHERTEXT");
                    int padding = stored.endsWith("==") ? 2 : stored.endsWith("=") ? 1 : 0;
                    long decodedLength = stored.length() / 4L * 3 - padding;
                    if (decodedLength < 28) throw failure("CIPHERTEXT");
                    if (decodedLength - 28 > budget) throw failure("LIMIT");
                    byte[] sealed = Base64.decode(stored, Base64.NO_WRAP);
                    if (sealed.length < 28 || sealed.length - 28 > limits.maxPlaintextBytes
                            || !Base64.encodeToString(sealed, Base64.NO_WRAP).equals(stored))
                        throw failure("CIPHERTEXT");
                    if (key == null) key = keys.resolve();
                    if (key == null) throw failure("KEY");
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
                    plain = cipher.doFinal(sealed, 12, sealed.length - 12);
                }
                total = count(total, plain);
                validate(name, parse(plain));
                result.put(name, plain);
            }
            for (String name : LONGS) {
                if (!settings.contains(name)) continue;
                byte[] value = Long.toString(settings.getLong(name, 0)).getBytes(StandardCharsets.UTF_8);
                total = count(total, value);
                result.put(name, value);
            }
            for (String name : INTS) {
                if (!settings.contains(name)) continue;
                byte[] value = Integer.toString(settings.getInt(name, 0)).getBytes(StandardCharsets.UTF_8);
                total = count(total, value);
                result.put(name, value);
            }
            if (settings.contains("excluded_banks")) {
                byte[] value = utf8(settings.getString("excluded_banks", null),
                        Math.min(limits.maxPlaintextBytes, limits.maxTotalBytes - total));
                count(total, value);
                for (Object bank : array(parse(value))) string(bank);
                result.put("excluded_banks", value);
            }
            return result;
        } catch (Exception ignored) {
            // Platform JSON/crypto/preferences errors can include sensitive input. Never attach them.
            throw failure("INVALID_SOURCE");
        }
    }

    private byte[] utf8(String text, long budget) throws IOException {
        if (text == null) throw failure("TYPE");
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw failure("UTF8");
                length += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw failure("UTF8");
            } else {
                length += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            }
            if (length > budget) throw failure("LIMIT");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private long count(long total, byte[] bytes) throws IOException {
        if (bytes.length > limits.maxPlaintextBytes || bytes.length > limits.maxTotalBytes - total)
            throw failure("LIMIT");
        return total + bytes.length;
    }

    private Object parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        lexicalCheck(text);
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = value(reader, 0, new int[] { 0 });
            if (reader.peek() != JsonToken.END_DOCUMENT) throw failure("JSON");
            return value;
        }
    }

    private void node(int[] nodes) throws IOException {
        if (nodes[0] >= limits.maxNodes) throw failure("LIMIT");
        nodes[0]++;
    }

    private Object value(JsonReader reader, int depth, int[] nodes) throws IOException {
        node(nodes);
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                if (depth >= limits.maxDepth) throw failure("LIMIT");
                reader.beginObject();
                Map<String, Object> object = new LinkedHashMap<>();
                while (reader.hasNext()) {
                    node(nodes);
                    String name = reader.nextName();
                    unicode(name);
                    if (object.containsKey(name)) throw failure("DUPLICATE");
                    object.put(name, value(reader, depth + 1, nodes));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                if (depth >= limits.maxDepth) throw failure("LIMIT");
                reader.beginArray();
                List<Object> array = new ArrayList<>();
                while (reader.hasNext()) array.add(value(reader, depth + 1, nodes));
                reader.endArray();
                return array;
            case STRING:
                String text = reader.nextString();
                unicode(text);
                return text;
            case NUMBER: return new Numeric(reader.nextString());
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return null;
            default: throw failure("JSON");
        }
    }

    /** Lexemes only, not a structural parser: closes Android JsonReader's permissive escape,
     * control-character and number handling. Structure/duplicates are checked by the reader above. */
    private static void lexicalCheck(String text) throws IOException {
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i++);
            if (" \t\r\n{}[],:".indexOf(c) >= 0) continue;
            if (c == '"') {
                boolean closed = false;
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == '"') { closed = true; break; }
                    if (c < 0x20) throw failure("JSON");
                    if (c != '\\') continue;
                    if (i == text.length()) throw failure("JSON");
                    c = text.charAt(i++);
                    if (c == 'u') {
                        for (int n = 0; n < 4; n++) {
                            if (i == text.length()) throw failure("JSON");
                            char h = text.charAt(i++);
                            if (!(h >= '0' && h <= '9' || h >= 'a' && h <= 'f'
                                    || h >= 'A' && h <= 'F')) throw failure("JSON");
                        }
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) throw failure("JSON");
                }
                if (!closed) throw failure("JSON");
            } else {
                int start = i - 1;
                while (i < text.length() && " \t\r\n{}[],:\"".indexOf(text.charAt(i)) < 0) i++;
                if (i - start > 256) throw failure("LIMIT");
                String token = text.substring(start, i);
                if (!token.equals("true") && !token.equals("false") && !token.equals("null")
                        && !NUMBER.matcher(token).matches()) throw failure("JSON");
            }
        }
    }

    private static void unicode(String text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw failure("UNICODE");
            } else if (Character.isLowSurrogate(c)) throw failure("UNICODE");
        }
    }

    private static final class Numeric {
        final String text;
        Numeric(String text) { this.text = text; }
    }

    private static void integer(Object value) throws IOException {
        if (!(value instanceof Numeric)) throw failure("TYPE");
        String text = ((Numeric) value).text;
        if (!INTEGER.matcher(text).matches()) throw failure("INTEGER");
        try { Long.parseLong(text); }
        catch (NumberFormatException ignored) { throw failure("INTEGER"); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw failure("TYPE");
        return (Map<String, Object>) value;
    }

    private static List<?> array(Object value) throws IOException {
        if (!(value instanceof List)) throw failure("TYPE");
        return (List<?>) value;
    }

    private static void string(Object value) throws IOException {
        if (!(value instanceof String)) throw failure("TYPE");
    }

    private static void optionalString(Map<String, Object> entry, String name) throws IOException {
        if (entry.get(name) != null) string(entry.get(name));
    }

    private static void validate(String name, Object root) throws IOException {
        Map<String, Object> map = object(root);
        switch (name) {
            case "balances":
                for (Object value : map.values()) {
                    Map<String, Object> entry = object(value);
                    integer(entry.get("amount"));
                    integer(entry.get("date"));
                    string(entry.get("sender"));
                    optionalString(entry, "account");
                }
                break;
            case "transactions":
                for (Object value : array(map.get("transactions"))) {
                    Map<String, Object> entry = object(value);
                    string(entry.get("bank"));
                    integer(entry.get("date"));
                    integer(entry.get("amount"));
                    optionalString(entry, "account");
                    optionalString(entry, "sig");
                    optionalString(entry, "content");
                    if (entry.get("bal") != null) integer(entry.get("bal"));
                }
                break;
            case "transaction_notes": case "transaction_reasons": case "transaction_channels":
                for (Object value : map.values()) string(value);
                break;
            case "recent_movements":
                for (Object value : map.values()) {
                    for (Object item : array(value)) {
                        Map<String, Object> entry = object(item);
                        integer(entry.get("d"));
                        integer(entry.get("a"));
                        integer(entry.get("b"));
                        optionalString(entry, "s");
                    }
                }
                break;
            case "history_last_balance":
                for (Object value : map.values()) integer(value);
                break;
            default: throw failure("COMPONENT");
        }
    }

    private static IOException failure(String code) { return new IOException(code); }
}
