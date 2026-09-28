package com.ashkanrafiee.balance;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Typed boundary for the financial component namespace. It deliberately has no BalanceData wiring. */
final class FinancialSnapshotAdapter {
    static final String BALANCES = "balances";
    static final String TRANSACTIONS = "transactions";
    static final String TRANSACTION_NOTES = "transaction_notes";
    static final String TRANSACTION_REASONS = "transaction_reasons";
    static final String TRANSACTION_CHANNELS = "transaction_channels";
    static final String RECENT_MOVEMENTS = "recent_movements";
    static final String HISTORY_LAST_BALANCE = "history_last_balance";
    static final String SCANNED_THROUGH = "scanned_through";
    static final String RULES_VERSION = "rules_version";
    static final String HISTORY_THROUGH = "history_through";
    static final String HISTORY_RULES_VERSION = "history_rules_version";
    static final String HISTORY_SCHEMA = "history_schema";
    static final String EXCLUDED_BANKS = "excluded_banks";

    private static final int MAX_BYTES = Math.min(
            LegacyFinancialSource.Limits.defaults().maxPlaintextBytes,
            EncryptedGenerationStore.Limits.defaults().maxComponentBytes);
    private static final String[] JSON = { BALANCES, TRANSACTIONS, TRANSACTION_NOTES,
             TRANSACTION_REASONS, TRANSACTION_CHANNELS, RECENT_MOVEMENTS, HISTORY_LAST_BALANCE };
    private static final int MAX_DEPTH = 32, MAX_NODES = 2_000_000;
    private static final String[] LONGS = { SCANNED_THROUGH, HISTORY_THROUGH };
    private static final String[] INTS = { RULES_VERSION, HISTORY_RULES_VERSION, HISTORY_SCHEMA };

    interface Work<T> { T run(MutableSnapshot snapshot) throws Exception; }

    static final class Snapshot {
        private final String revision;
        private final Map<String, byte[]> values;

        private Snapshot(String revision, Map<String, byte[]> values) {
            this.revision = revision;
            this.values = copy(values);
        }

        String revision() { return revision; }
        boolean contains(String name) { return values.containsKey(name); }
        byte[] get(String name) { return FinancialSnapshotAdapter.clone(values.get(name)); }
        Map<String, byte[]> components() { return copy(values); }
    }

    static final class MutableSnapshot {
        private final FinancialRepository.Draft draft;

        private MutableSnapshot(FinancialRepository.Draft draft) { this.draft = draft; }
        boolean contains(String name) { return draft.components().containsKey(name); }
        byte[] get(String name) { return draft.get(name); }
        void put(String name, byte[] value) throws IOException {
            if (name == null || value == null) throw invalid();
            validate(name, value);
            draft.put(name, value);
        }
        void remove(String name) { draft.remove(name); }
        Map<String, byte[]> components() { return draft.components(); }
    }

    private final FinancialRepository repository;

    FinancialSnapshotAdapter(FinancialRepository repository) {
        if (repository == null) throw new IllegalArgumentException("ARGUMENT");
        this.repository = repository;
    }

    Snapshot snapshot() throws IOException {
        return checked(repository.snapshot());
    }

    <T> T transaction(Work<T> work) throws IOException {
        if (work == null) throw new IllegalArgumentException("ARGUMENT");
        return repository.transaction(draft -> {
            for (Map.Entry<String, byte[]> entry : draft.components().entrySet())
                validate(entry.getKey(), entry.getValue());
            return work.run(new MutableSnapshot(draft));
        });
    }

    private static Snapshot checked(FinancialRepository.Snapshot source) throws IOException {
        Map<String, byte[]> values = source.components();
        for (Map.Entry<String, byte[]> entry : values.entrySet()) validate(entry.getKey(), entry.getValue());
        return new Snapshot(source.revision(), values);
    }

    private static void validate(String name, byte[] value) throws IOException {
        if (name == null || value == null || value.length > MAX_BYTES) throw invalid();
        if (is(name, LONGS)) number(value, Long.MIN_VALUE, Long.MAX_VALUE);
        else if (is(name, INTS)) number(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
        else if (is(name, JSON)) json(value, false);
        else if (EXCLUDED_BANKS.equals(name)) json(value, true);
    }

    private static void number(byte[] bytes, long minimum, long maximum) throws IOException {
        String text = utf8(bytes);
        if (text.isEmpty() || text.charAt(0) == '+') throw invalid();
        if (text.charAt(0) == '-' && text.length() == 1) throw invalid();
        int first = text.charAt(0) == '-' ? 1 : 0;
        if (first == text.length() || (text.length() - first > 1 && text.charAt(first) == '0'))
            throw invalid();
        for (int i = first; i < text.length(); i++)
            if (text.charAt(i) < '0' || text.charAt(i) > '9') throw invalid();
        try {
            long result = Long.parseLong(text);
            if (result < minimum || result > maximum) throw invalid();
        } catch (NumberFormatException e) { throw invalid(); }
    }

    private static void json(byte[] bytes, boolean arrayRequired) throws IOException {
        String text = utf8(bytes);
        if (text.isEmpty()) throw invalid();
        int first = 0, last = text.length() - 1;
        while (first <= last && Character.isWhitespace(text.charAt(first))) first++;
        while (last >= first && Character.isWhitespace(text.charAt(last))) last--;
        if (first > last || (arrayRequired ? text.charAt(first) != '[' || text.charAt(last) != ']'
                : !((text.charAt(first) == '{' && text.charAt(last) == '}')
                || (text.charAt(first) == '[' && text.charAt(last) == ']')))) throw invalid();
        lexical(text);
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonToken token = reader.peek();
            if (arrayRequired && token != JsonToken.BEGIN_ARRAY) throw invalid();
            if (!arrayRequired && token != JsonToken.BEGIN_OBJECT && token != JsonToken.BEGIN_ARRAY)
                throw invalid();
            consume(reader, 0, new int[] { 0 });
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
        }
        if (arrayRequired) validateExcludedStrings(text);
    }

    private static void validateExcludedStrings(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginArray();
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.STRING) throw invalid();
                unicode(reader.nextString());
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }

    private static void consume(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) throw invalid();
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                unicode(name);
                if (!names.add(name)) throw invalid();
                consume(reader, depth + 1, nodes);
            }
            reader.endObject();
        } else if (token == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) consume(reader, depth + 1, nodes);
            reader.endArray();
        } else if (token == JsonToken.STRING) {
            unicode(reader.nextString());
        } else if (token == JsonToken.NUMBER) {
            reader.nextString();
        } else if (token == JsonToken.BOOLEAN) {
            reader.nextBoolean();
        } else if (token == JsonToken.NULL) {
            reader.nextNull();
        } else {
            throw invalid();
        }
    }

    private static void unicode(String text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) { throw invalid(); }
    }

    private static void lexical(String text) throws IOException {
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i++);
            if (Character.isWhitespace(c) || "{}[],:".indexOf(c) >= 0) continue;
            if (c == '"') {
                boolean closed = false;
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == '"') { closed = true; break; }
                    if (c < 0x20) throw invalid();
                    if (c == '\\') {
                        if (i == text.length()) throw invalid();
                        c = text.charAt(i++);
                        if (c == 'u') {
                            for (int n = 0; n < 4; n++)
                                if (i == text.length() || Character.digit(text.charAt(i++), 16) < 0)
                                    throw invalid();
                        } else if ("\"\\/bfnrt".indexOf(c) < 0) throw invalid();
                    }
                }
                if (!closed) throw invalid();
            }
        }
    }

    private static boolean is(String name, String[] names) {
        for (String candidate : names) if (candidate.equals(name)) return true;
        return false;
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) result.put(entry.getKey(), clone(entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static byte[] clone(byte[] value) { return value == null ? null : value.clone(); }
    private static IOException invalid() { return new IOException("INVALID_FINANCIAL_DATA"); }
}
