package com.ashkanrafiee.balance;

import android.util.JsonReader;
import android.util.JsonToken;

import com.ashkanrafiee.balance.parser.JsonLexicalGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free Android JSON codec used by the packaged engine path (main assets) and the
 * instrumentation tests. Limits: 256 KiB UTF-8, 65,536 lexical tokens, 256 chars per number,
 * 16 containers deep (root object counts as one). Strings share the document cap. Numbers are
 * exact BigDecimal; syntactically valid exponents outside BigDecimal's scale range are rejected.
 */
public final class PlatformRuleJson {
    public static final int MAX_DEPTH = 16;

    public enum Code {
        IO, UTF8, SIZE_LIMIT, TOKEN_LIMIT, NUMBER_LIMIT, INVALID_LEXEME, INVALID_UNICODE,
        SYNTAX, ROOT_OBJECT_REQUIRED, DUPLICATE_KEY, DEPTH_LIMIT, NUMBER_RANGE
    }

    /** No raw platform exception/cause escapes this boundary: it may contain document text. */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final Code code;
        private Failure(Code code) { super(code.name()); this.code = code; }
    }

    private PlatformRuleJson() {}

    /** Reads at most cap + 1 bytes; the caller retains ownership of the input stream. */
    public static Map<String, Object> read(InputStream input) throws Failure {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            while (true) {
                int remaining = JsonLexicalGuard.MAX_DOCUMENT_BYTES - bytes.size();
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
                if (count == -1) break;
                // Also support streams that return zero without spinning forever.
                if (count == 0) {
                    int one = input.read();
                    if (one == -1) break;
                    if (remaining == 0) throw new Failure(Code.SIZE_LIMIT);
                    bytes.write(one);
                } else {
                    if (count > remaining) throw new Failure(Code.SIZE_LIMIT);
                    bytes.write(buffer, 0, count);
                }
            }
        } catch (Failure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new Failure(Code.IO);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException failure) {
            throw new Failure(Code.UTF8);
        }
        try {
            JsonLexicalGuard.validate(text);
        } catch (JsonLexicalGuard.Failure failure) {
            throw new Failure(Code.valueOf(failure.code.name()));
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new Failure(Code.ROOT_OBJECT_REQUIRED);
            Map<String, Object> root = object(reader, 1);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Failure(Code.SYNTAX);
            return root;
        } catch (Failure failure) {
            throw failure;
        } catch (NumberFormatException failure) {
            throw new Failure(Code.NUMBER_RANGE);
        } catch (IOException | IllegalStateException failure) {
            throw new Failure(Code.SYNTAX);
        }
    }

    private static Map<String, Object> object(JsonReader reader, int depth) throws IOException {
        checkDepth(depth);
        reader.beginObject();
        Map<String, Object> result = new LinkedHashMap<>();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (result.containsKey(name)) throw new Failure(Code.DUPLICATE_KEY);
            result.put(name, value(reader, depth));
        }
        reader.endObject();
        return result;
    }

    private static Object value(JsonReader reader, int parentDepth) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT: return object(reader, parentDepth + 1);
            case BEGIN_ARRAY:
                checkDepth(parentDepth + 1);
                reader.beginArray();
                List<Object> values = new ArrayList<>();
                while (reader.hasNext()) values.add(value(reader, parentDepth + 1));
                reader.endArray();
                return values;
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return null;
            case NUMBER: return new BigDecimal(reader.nextString());
            default: throw new Failure(Code.SYNTAX);
        }
    }

    private static void checkDepth(int depth) throws Failure {
        if (depth > MAX_DEPTH) throw new Failure(Code.DEPTH_LIMIT);
    }
}
