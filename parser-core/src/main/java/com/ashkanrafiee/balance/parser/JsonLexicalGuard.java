package com.ashkanrafiee.balance.parser;

/**
 * Prototype supplement for Android JsonReader's permissive lexemes, not a JSON parser.
 * Linear scan, constant auxiliary space. The caller must strictly decode UTF-8 and enforce
 * the byte cap first, then use a structural parser (including end-of-document validation).
 * Punctuation, names and values each count as one token; whitespace does not count.
 * Strings have no separate cap beyond the document cap. Unicode must be scalar-valued.
 */
public final class JsonLexicalGuard {
    public static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    public static final int MAX_TOKENS = 65_536;
    public static final int MAX_NUMBER_CHARS = 256;

    public enum Code { SIZE_LIMIT, TOKEN_LIMIT, NUMBER_LIMIT, INVALID_LEXEME, INVALID_UNICODE }

    /** Contains only a fixed code, never input text, offsets or a parser cause. */
    public static final class Failure extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final Code code;
        private Failure(Code code) { super(code.name()); this.code = code; }
    }

    private JsonLexicalGuard() {}

    public static void validate(String text) {
        if (text.length() > MAX_DOCUMENT_BYTES) fail(Code.SIZE_LIMIT);
        int tokens = 0;
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (space(c)) { i++; continue; }
            if (++tokens > MAX_TOKENS) fail(Code.TOKEN_LIMIT);
            if (c == '"') {
                i = stringEnd(text, i + 1);
            } else if (punctuation(c)) {
                i++;
            } else {
                int start = i;
                while (i < text.length() && !space(text.charAt(i))
                        && !punctuation(text.charAt(i)) && text.charAt(i) != '"') i++;
                int length = i - start;
                if (matches(text, start, length, "true")
                        || matches(text, start, length, "false")
                        || matches(text, start, length, "null")) continue;
                if (c != '-' && !digit(c)) fail(Code.INVALID_LEXEME);
                if (length > MAX_NUMBER_CHARS) fail(Code.NUMBER_LIMIT);
                number(text, start, i);
            }
        }
    }

    private static int stringEnd(String text, int i) {
        boolean high = false;
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == '"') {
                if (high) fail(Code.INVALID_UNICODE);
                return i;
            }
            if (c < 0x20) fail(Code.INVALID_LEXEME);
            if (c == '\\') {
                if (i == text.length()) fail(Code.INVALID_LEXEME);
                c = text.charAt(i++);
                switch (c) {
                    case '"': case '\\': case '/': break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        if (text.length() - i < 4) fail(Code.INVALID_LEXEME);
                        int value = 0;
                        for (int end = i + 4; i < end; i++) {
                            char h = text.charAt(i);
                            int digit = h >= '0' && h <= '9' ? h - '0'
                                    : h >= 'a' && h <= 'f' ? h - 'a' + 10
                                    : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                            if (digit < 0) fail(Code.INVALID_LEXEME);
                            value = value * 16 + digit;
                        }
                        c = (char) value;
                        break;
                    default: fail(Code.INVALID_LEXEME);
                }
            }
            if (high) {
                if (!Character.isLowSurrogate(c)) fail(Code.INVALID_UNICODE);
                high = false;
            } else if (Character.isHighSurrogate(c)) {
                high = true;
            } else if (Character.isLowSurrogate(c)) {
                fail(Code.INVALID_UNICODE);
            }
        }
        fail(Code.INVALID_LEXEME);
        return i; // unreachable
    }

    // JSON number grammar only; range/scale representability belongs to the numeric codec.
    private static void number(String text, int i, int end) {
        if (text.charAt(i) == '-') i++;
        if (i == end) fail(Code.INVALID_LEXEME);
        if (text.charAt(i) == '0') {
            i++;
        } else {
            if (text.charAt(i) < '1' || text.charAt(i) > '9') fail(Code.INVALID_LEXEME);
            while (i < end && digit(text.charAt(i))) i++;
        }
        if (i < end && text.charAt(i) == '.') {
            int start = ++i;
            while (i < end && digit(text.charAt(i))) i++;
            if (i == start) fail(Code.INVALID_LEXEME);
        }
        if (i < end && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            if (i < end && (text.charAt(i) == '+' || text.charAt(i) == '-')) i++;
            int start = i;
            while (i < end && digit(text.charAt(i))) i++;
            if (i == start) fail(Code.INVALID_LEXEME);
        }
        if (i != end) fail(Code.INVALID_LEXEME);
    }

    private static boolean matches(String text, int start, int length, String literal) {
        return length == literal.length() && text.regionMatches(start, literal, 0, length);
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static boolean space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
    private static boolean punctuation(char c) {
        return c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':';
    }
    private static void fail(Code code) { throw new Failure(code); }
}
