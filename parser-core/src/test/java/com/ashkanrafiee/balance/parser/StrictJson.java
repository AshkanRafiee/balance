package com.ashkanrafiee.balance.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict minimal JSON reader for the gates: no library is on this module's classpath, and a
 * dependency-free encoder is only worth having if something reads its output back with the same
 * strictness the app applies to an imported pack.
 *
 * <p>Duplicate keys are rejected rather than resolved, an object is required to be an object, and
 * numbers arrive as {@code Long} or {@link BigDecimal} so a document cannot round through a
 * double. Integers wider than a long arrive as {@link BigInteger}, which the pack decoder rejects
 * as out of range for its own fields.
 */
final class StrictJson {
    private StrictJson() {}

    /** Reads a whole file as a JSON object. */
    static Map<String, Object> read(Path path) {
        try {
            return object(parse(Files.readString(path, StandardCharsets.UTF_8)), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses one complete JSON document, rejecting anything trailing it. */
    static Object parse(String text) {
        int[] at = {0};
        Object value = value(text, at, 0);
        whitespace(text, at);
        if (at[0] != text.length()) throw new IllegalArgumentException("Trailing JSON");
        return value;
    }

    static Map<String, Object> object(Object value, String what) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        throw new IllegalArgumentException("Expected object for " + what);
    }

    private static Object value(String text, int[] at, int depth) {
        if (depth > 16) throw new IllegalArgumentException("JSON depth");
        whitespace(text, at);
        char c = text.charAt(at[0]);
        if (c == '{') return member(text, at, depth);
        if (c == '[') return array(text, at, depth);
        if (c == '"') return string(text, at);
        if (text.startsWith("true", at[0])) return literal(text, at, "true", Boolean.TRUE);
        if (text.startsWith("false", at[0])) return literal(text, at, "false", Boolean.FALSE);
        if (text.startsWith("null", at[0])) return literal(text, at, "null", null);
        return number(text, at);
    }

    private static Map<String, Object> member(String text, int[] at, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        at[0]++;
        whitespace(text, at);
        if (text.charAt(at[0]) == '}') { at[0]++; return result; }
        while (true) {
            whitespace(text, at);
            String key = string(text, at);
            whitespace(text, at);
            if (text.charAt(at[0]++) != ':') throw new IllegalArgumentException("Expected ':'");
            if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate key " + key);
            result.put(key, value(text, at, depth + 1));
            whitespace(text, at);
            char c = text.charAt(at[0]++);
            if (c == '}') return result;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or '}'");
        }
    }

    private static List<Object> array(String text, int[] at, int depth) {
        List<Object> result = new ArrayList<>();
        at[0]++;
        whitespace(text, at);
        if (text.charAt(at[0]) == ']') { at[0]++; return result; }
        while (true) {
            result.add(value(text, at, depth + 1));
            whitespace(text, at);
            char c = text.charAt(at[0]++);
            if (c == ']') return result;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or ']'");
        }
    }

    private static String string(String text, int[] at) {
        if (text.charAt(at[0]++) != '"') throw new IllegalArgumentException("Expected string");
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(at[0]++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new IllegalArgumentException("Control character in string");
            if (c != '\\') { out.append(c); continue; }
            char escape = text.charAt(at[0]++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at[0], at[0] + 4), 16));
                    at[0] += 4;
                }
                default -> throw new IllegalArgumentException("Unknown escape");
            }
        }
    }

    private static Object literal(String text, int[] at, String token, Object value) {
        at[0] += token.length();
        return value;
    }

    private static Object number(String text, int[] at) {
        int start = at[0];
        while (at[0] < text.length() && "+-.eE0123456789".indexOf(text.charAt(at[0])) >= 0) at[0]++;
        String digits = text.substring(start, at[0]);
        if (digits.isEmpty()) throw new IllegalArgumentException("Expected value");
        if (digits.indexOf('.') < 0 && digits.indexOf('e') < 0 && digits.indexOf('E') < 0) {
            BigInteger integer = new BigInteger(digits);
            return integer.bitLength() < 64 ? (Object) integer.longValue() : integer;
        }
        return new BigDecimal(digits);
    }

    private static void whitespace(String text, int[] at) {
        while (at[0] < text.length() && Character.isWhitespace(text.charAt(at[0]))) at[0]++;
    }
}