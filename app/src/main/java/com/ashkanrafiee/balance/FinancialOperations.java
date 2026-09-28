package com.ashkanrafiee.balance;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Typed, unwired operations. Only touched notes/exclusions are validated; unrelated malformed
 * metadata is tolerated. Reset deliberately removes owned data without parsing it. Readers pin
 * one repository snapshot; nested writers share the caller's transaction. */
final class FinancialOperations {
    private static final String NOTES = "transaction_notes", EXCLUDED = "excluded_banks";
    private static final String[] RESET = { "balances", "transactions", "history_last_balance",
            "recent_movements", "transaction_reasons", "transaction_channels", "scanned_through",
            "rules_version", "history_through", "history_rules_version", "history_schema", EXCLUDED };
    // Match the default migration budget; destination aggregate limits remain enforced by the store.
    private static final int MAX_BYTES = Math.min(LegacyFinancialSource.Limits.defaults().maxPlaintextBytes,
            EncryptedGenerationStore.Limits.defaults().maxComponentBytes);
    private final FinancialRepository repo;

    FinancialOperations(FinancialRepository repo) {
        if (repo == null) throw new IllegalArgumentException("ARGUMENT");
        this.repo = repo;
    }

    Map<String, String> notes() throws IOException { return readNotes(repo.snapshot().get(NOTES)); }

    void setNote(Transaction tx, String text) throws IOException {
        repo.transaction(draft -> {
            Map<String, String> notes = readNotes(draft.get(NOTES));
            String key = BalanceData.noteKey(tx);
            String value = text == null ? "" : text.trim();
            int end = Math.min(value.length(), BalanceData.MAX_NOTE_LENGTH);
            if (end < value.length())
                while (end > 0 && Character.isLowSurrogate(value.charAt(end))) end--;
            unicode(key);
            unicode(value.substring(0, end));
            if (value.isEmpty()) notes.remove(key); else notes.put(key, value.substring(0, end));
            // Unlike serializeTextMap, preserve unrelated empty-string records from migration.
            if (notes.isEmpty()) draft.remove(NOTES);
            else draft.put(NOTES, encode(new JSONObject(notes).toString()));
            return null;
        });
    }

    Set<String> getExcluded() throws IOException { return readExcluded(repo.snapshot().get(EXCLUDED)); }

    void setExcluded(Set<String> excluded) throws IOException {
        repo.transaction(draft -> {
            readExcluded(draft.get(EXCLUDED));
            writeExcluded(draft, excluded);
            return null;
        });
    }

    void toggleExcluded(String key) throws IOException {
        repo.transaction(draft -> {
            Set<String> excluded = readExcluded(draft.get(EXCLUDED));
            if (!excluded.remove(key)) excluded.add(key);
            writeExcluded(draft, excluded);
            return null;
        });
    }

    void reset(boolean alsoNotes) throws IOException {
        repo.transaction(draft -> {
            for (String name : RESET) draft.remove(name);
            if (alsoNotes) draft.remove(NOTES);
            return null;
        });
    }

    private static void writeExcluded(FinancialRepository.Draft draft, Set<String> values)
            throws IOException {
        JSONArray array = new JSONArray();
        for (String value : values) { unicode(value); array.put(value); }
        draft.put(EXCLUDED, encode(array.toString()));
    }

    private static Map<String, String> readNotes(byte[] bytes) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (bytes == null) return result;
        try (JsonReader reader = reader(bytes)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                unicode(key);
                if (result.containsKey(key)) throw invalid();
                String value = string(reader);
                // Match BalanceData's legacy text-map serializer: empty values are absent
                // records, so a subsequent edit must not preserve them accidentally.
                if (!value.isEmpty()) result.put(key, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return result;
        } catch (Exception ignored) { throw invalid(); }
    }

    private static Set<String> readExcluded(byte[] bytes) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        if (bytes == null) return result;
        try (JsonReader reader = reader(bytes)) {
            reader.beginArray();
            while (reader.hasNext()) result.add(string(reader));
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return result;
        } catch (Exception ignored) { throw invalid(); }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        String value = reader.nextString();
        unicode(value);
        return value;
    }

    private static JsonReader reader(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw invalid();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        lexicalCheck(text);
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        return reader;
    }

    /** String-only lexical guard closes Android JsonReader's permissive escapes/raw controls.
     * JsonReader owns structure validation; no recursive or general-purpose JSON parser here. */
    private static void lexicalCheck(String text) throws IOException {
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i++);
            if (" \t\r\n{}[],:".indexOf(c) >= 0) continue;
            if (c != '"') throw invalid();
            boolean closed = false;
            while (i < text.length()) {
                c = text.charAt(i++);
                if (c == '"') { closed = true; break; }
                if (c < 0x20) throw invalid();
                if (c != '\\') continue;
                if (i == text.length()) throw invalid();
                c = text.charAt(i++);
                if (c == 'u') {
                    for (int n = 0; n < 4; n++)
                        if (i == text.length() || "0123456789abcdefABCDEF".indexOf(text.charAt(i++)) < 0)
                            throw invalid();
                } else if ("\"\\/bfnrt".indexOf(c) < 0) throw invalid();
            }
            if (!closed) throw invalid();
        }
    }

    private static void unicode(String text) throws IOException {
        if (text == null) throw invalid();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }

    private static byte[] encode(String text) throws IOException {
        if (text.length() > MAX_BYTES) throw invalid();
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        if (buffer.remaining() > MAX_BYTES) throw invalid();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static IOException invalid() { return new IOException("INVALID_FINANCIAL_DATA"); }
}
