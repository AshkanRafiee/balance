package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.JsonLexicalGuard.Code.*;

/** Standalone host checks, no framework and no reliance on JVM assertion flags. */
public final class JsonLexicalGuardTest {
    private static int checks;

    public static void main(String[] args) {
        for (String token : new String[] {"true", "false", "null", "0", "-0", "123", "-123",
                "0.0", "-0.125", "1e2", "1E+2", "1e-2", "1.25e+003"}) {
            accept("{\"value\":" + token + "}");
        }
        accept(" \t\r\n{\"text\":\"فارسی 😀\",\"a\":[true,false,null]}\n");
        accept("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f\\uFFFF\"");
        accept("\"\\uD83D\\uDE00\"");
        accept("\"" + (char) 0xd83d + "\\uDE00\"");
        accept("\"\\uD83D" + (char) 0xde00 + "\"");
        for (String token : new String[] {"TRUE", "True", "FALSE", "False", "NULL", "Null",
                "undefined", "NaN", "Infinity", "-Infinity", "+1", ".1", "1.", "1.e2",
                "01", "-01", "00.1", "--1", "-", "1e", "1e+", "1e-", "1e1.0", "0x10",
                "1_000", "١", "１", "truefalse", "1true", "true;", "/*x*/0", "//x\n0",
                "#x", "'x'", "=", ">", ";", "\u000b", "\u000c", "\u00a0", "\ufeff"}) {
            reject("{\"v\":" + token + "}", INVALID_LEXEME);
        }
        for (String text : new String[] {"\"\\x\"", "\"\\'\"", "\"\\v\"", "\"\\0\"",
                "\"\\uZZZZ\"", "\"\\u１２３４\"", "\"\\u123\"", "\"\\u", "\"\\", "\"unfinished"}) {
            reject(text, INVALID_LEXEME);
        }
        for (int c = 0; c < 32; c++) reject("\"a" + (char) c + "b\"", INVALID_LEXEME);
        for (String text : new String[] {"\"\\uD800\"", "\"\\uDC00\"", "\"\\uDC00\\uD800\"",
                "\"\\uD800x\"", "\"\\uD800\\n\"", "\"\\uD800\\uD800\"",
                "\"\ud800\"", "\"\udc00\"", "\"\ud800x\"", "\"\ud800\ud800\""}) {
            reject(text, INVALID_UNICODE);
        }
        // These deliberately pass: only the platform parser owns structural grammar.
        for (String text : new String[] {"", "{]", "[1,]", "{\"a\" 1}", "{}{}", "true false",
                "1\"x\"", "{\"a\":1,\"a\":2}"}) accept(text);
        accept("\"" + repeat('x', JsonLexicalGuard.MAX_DOCUMENT_BYTES - 2) + "\"");
        reject(repeat(' ', JsonLexicalGuard.MAX_DOCUMENT_BYTES + 1), SIZE_LIMIT);
        accept(repeat(',', JsonLexicalGuard.MAX_TOKENS));
        reject(repeat(',', JsonLexicalGuard.MAX_TOKENS + 1), TOKEN_LIMIT);
        accept(repeat('1', JsonLexicalGuard.MAX_NUMBER_CHARS));
        reject(repeat('1', JsonLexicalGuard.MAX_NUMBER_CHARS + 1), NUMBER_LIMIT);
        System.out.println("JsonLexicalGuardTest: " + checks + " checks passed");
    }

    private static String repeat(char c, int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void accept(String text) {
        JsonLexicalGuard.validate(text);
        checks++;
    }

    private static void reject(String text, JsonLexicalGuard.Code expected) {
        try {
            JsonLexicalGuard.validate(text);
            throw new AssertionError("Expected lexical rejection");
        } catch (JsonLexicalGuard.Failure failure) {
            if (failure.code != expected || !failure.getMessage().equals(expected.name())
                    || failure.getCause() != null) throw new AssertionError("Unexpected error boundary");
            checks++;
        }
    }
}
