package com.ashkanrafiee.balance;

import static org.junit.Assert.*;
import static com.ashkanrafiee.balance.PlatformRuleJson.Code.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.ashkanrafiee.balance.parser.JsonLexicalGuard;
import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Parser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict document boundary and example-pack integration. No device data used. */
@RunWith(AndroidJUnit4.class)
public class PlatformRuleJsonTest {
    @Test public void exactTreeAndUnicode() throws Exception {
        Map<String, Object> root = read(" \r\n{\"n\":9007199254740993,\"d\":1.2300e-2,"
                + "\"z\":-0,\"a\":[true,false,null,{\"s\":\"فارسی 😀\\uD83D\\uDE00\"}],"
                + "\"esc\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\"}\t");
        assertEquals(new BigDecimal("9007199254740993"), root.get("n"));
        assertEquals(new BigDecimal("1.2300e-2"), root.get("d"));
        assertEquals(new BigDecimal("-0"), root.get("z"));
        List<?> list = (List<?>) root.get("a");
        assertEquals(Boolean.TRUE, list.get(0));
        assertEquals(Boolean.FALSE, list.get(1));
        assertNull(list.get(2));
        assertEquals("فارسی 😀😀", ((Map<?, ?>) list.get(3)).get("s"));
        assertEquals("\"\\/\b\f\n\r\t\u0000", root.get("esc"));
        assertTrue(read("{}").isEmpty());
        assertEquals(new BigDecimal("1e400"), read("{\"n\":1e400}").get("n"));
        reject("{\"n\":1e2147483648}", NUMBER_RANGE);
    }

    @Test public void aospPermissiveLexemesAreRejectedBeforeParsing() throws Exception {
        for (String value : new String[] {"TRUE", "False", "NULL", "1.", "1.e2", "01",
                "+1", ".5", "1e+", "NaN", "Infinity", "0x10", "'text'", "/*x*/1",
                "\"\\q\"", "\"\\'\"", "\"\\uZZZZ\""}) {
            reject("{\"x\":" + value + "}", INVALID_LEXEME);
        }
        reject("{\"a\":1;\"b\":2}", INVALID_LEXEME);
        reject("{\"a\"=1}", INVALID_LEXEME);
        reject("\ufeff{}", INVALID_LEXEME);
        reject("{}\u00a0", INVALID_LEXEME);
        for (int c = 0; c < 32; c++) reject("{\"x\":\"a" + (char) c + "b\"}", INVALID_LEXEME);
    }

    @Test public void platformOwnsStructuralGrammarAndEndOfDocument() throws Exception {
        for (String text : new String[] {"", "{", "{]", "{\"a\" 1}", "{\"a\":}", "{a:1}",
                "{\"a\":1,}", "{\"a\":1 \"b\":2}", "{\"a\":[1,]}", "{\"a\":[,1]}",
                "{\"a\":[1,,2]}", "{\"a\":[1 2]}", "{}{}", "{} true", "{}[]", "{\"a\":true false}"}) {
            reject(text, null);
        }
        for (String text : new String[] {"[]", "null", "true", "1", "\"x\""}) reject(text, null);
    }

    @Test public void duplicateDecodedNamesIncludingNullAreRejected() throws Exception {
        reject("{\"a\":null,\"a\":1}", DUPLICATE_KEY);
        reject("{\"a\":1,\"\\u0061\":2}", DUPLICATE_KEY);
        reject("{\"outer\":[{\"a\":false,\"a\":null}]}", DUPLICATE_KEY);
        read("{\"one\":{\"a\":1},\"two\":{\"a\":2}}");
    }

    @Test public void strictUtf8AndScalarUnicode() throws Exception {
        for (byte[] bad : new byte[][] {{(byte) 0xc0, (byte) 0xaf}, {(byte) 0x80},
                {(byte) 0xc2}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {(byte) 0xe2, 0x28, (byte) 0xa1}}) {
            byte[] prefix = "{\"x\":\"".getBytes(StandardCharsets.UTF_8);
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + bad.length + 2);
            System.arraycopy(bad, 0, bytes, prefix.length, bad.length);
            bytes[bytes.length - 2] = '"';
            bytes[bytes.length - 1] = '}';
            reject(bytes, UTF8);
        }
        for (String value : new String[] {"\\uD800", "\\uDC00", "\\uD800x",
                "\\uDC00\\uD800", "\\uD800\\uD800"}) {
            reject("{\"x\":\"" + value + "\"}", INVALID_UNICODE);
        }
        assertEquals("😀", read("{\"x\":\"\\uD83D\\uDE00\"}").get("x"));
    }

    @Test public void documentNumberDepthAndTokenBounds() throws Exception {
        int cap = JsonLexicalGuard.MAX_DOCUMENT_BYTES;
        String exact = "{\"x\":\"" + repeat("x", cap - 8) + "\"}";
        assertEquals(cap, exact.getBytes(StandardCharsets.UTF_8).length);
        read(exact);
        reject(exact + " ", SIZE_LIMIT);
        reject("{\"x\":\"" + repeat("é", cap / 2) + "\"}", SIZE_LIMIT);
        read("{\"x\":" + repeat("1", JsonLexicalGuard.MAX_NUMBER_CHARS) + "}");
        reject("{\"x\":" + repeat("1", JsonLexicalGuard.MAX_NUMBER_CHARS + 1) + "}", NUMBER_LIMIT);
        read(nested(PlatformRuleJson.MAX_DEPTH - 1));
        reject(nested(PlatformRuleJson.MAX_DEPTH), DEPTH_LIMIT);
        read(repeat("{\"x\":", PlatformRuleJson.MAX_DEPTH) + "0"
                + repeat("}", PlatformRuleJson.MAX_DEPTH));
        reject(repeat("{\"x\":", PlatformRuleJson.MAX_DEPTH + 1) + "0"
                + repeat("}", PlatformRuleJson.MAX_DEPTH + 1), DEPTH_LIMIT);
        // For {"a":[0,...]}, token count is 2*N+5. The final member adds four tokens.
        String array = "{\"a\":[" + repeat("0,", 32764) + "0]";
        read(array + "}"); // 65,535 tokens
        reject(array + ",\"b\":0}", TOKEN_LIMIT);
    }

    @Test public void boundedReadStreamOwnershipAndSanitizedIo() throws Exception {
        final int[] reads = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { reads[0]++; return ' '; }
        };
        try {
            PlatformRuleJson.read(endless);
            fail("Expected size limit");
        } catch (PlatformRuleJson.Failure failure) {
            assertEquals(SIZE_LIMIT, failure.code);
            assertEquals(JsonLexicalGuard.MAX_DOCUMENT_BYTES + 1, reads[0]);
        }
        ByteArrayInputStream zeroRead = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] b, int off, int len) { return 0; }
            @Override public void close() { fail("Caller owns stream"); }
        };
        assertTrue(PlatformRuleJson.read(zeroRead).isEmpty());
        try {
            PlatformRuleJson.read(new InputStream() {
                @Override public int read() throws IOException { throw new IOException("private-input"); }
            });
            fail("Expected IO rejection");
        } catch (PlatformRuleJson.Failure failure) {
            assertEquals(IO, failure.code);
            assertEquals("IO", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test public void exampleAssetsDecodeAndParseAllThreeCasesAtomically() throws Exception {
        assertExampleCases("multi-currency", "same-account-two-currencies",
                "required-second-output-invalid", "wrong-currency-must-not-relabel");
    }

    @Test public void iranianAssetsDecodeAndParseAllCasesAtomically() throws Exception {
        assertExampleCases("iranian-prototype",
                "synthetic-mellat-glued-short-jalali",
                "synthetic-mellat-invalid-date-falls-back",
                "synthetic-mellat-stale-date-falls-back",
                "synthetic-mellat-account-cannot-truncate",
                "synthetic-melli-compact-jalali",
                "synthetic-melli-neighbor-previous-year",
                "synthetic-melli-missing-date-falls-back",
                "synthetic-blu-normalized-reason-no-account",
                "synthetic-blu-unknown-reason-omitted",
                "synthetic-blu-digit-bearing-reason-omitted",
                "synthetic-blu-absent-reason-omitted",
                "synthetic-tejarat-folded-channel",
                "synthetic-tejarat-unresolved-account-unknown-channel",
                "synthetic-tejarat-invalid-balance-no-partial-movement",
                "synthetic-tejarat-invalid-movement-no-partial-balance",
                "synthetic-dotted-unique-reference",
                "synthetic-dotted-two-references-ambiguous",
                "synthetic-dotted-trailing-segment-not-truncated",
                "synthetic-blu-repeated-reason-anchor-omitted",
                "synthetic-tejarat-repeated-channel-anchor-omitted");
    }

    private static void assertExampleCases(String directory, String... expectedIds) throws Exception {
        PackDocument pack = PackDocument.decode(asset(directory + "/pack.json"));
        Parser parser = new Parser(pack.templates());
        Map<String, Object> fixtures = asset(directory + "/fixtures.json");
        assertEquals("prototype-fixtures-1", fixtures.get("schema"));
        assertEquals(pack.id(), fixtures.get("pack"));
        List<?> cases = (List<?>) fixtures.get("cases");
        assertEquals(directory, expectedIds.length, cases.size());
        Set<String> remaining = new HashSet<>(Arrays.asList(expectedIds));
        for (Object item : cases) {
            Map<?, ?> fixture = (Map<?, ?>) item;
            String id = (String) fixture.get("id");
            assertTrue("Unexpected or duplicate fixture ID", remaining.remove(id));
            Parser.Result result = parser.parse(new Parser.Message(
                    (String) fixture.get("sourceId"), (String) fixture.get("sender"),
                    (String) fixture.get("body"), Instant.parse((String) fixture.get("arrival")),
                    ZoneId.of((String) fixture.get("zone"))));
            Map<?, ?> expected = (Map<?, ?>) fixture.get("expected");
            assertEquals(id, expected.get("status"), result.status().name());
            List<?> outputs = (List<?>) expected.get("outputs");
            assertEquals(id, outputs.size(), result.facts().size());
            Map<String, Parser.Fact> byId = new HashMap<>();
            for (Parser.Fact fact : result.facts()) {
                assertNull(id, byId.put(fact.provenance().outputId(), fact));
                assertEquals(id, pack.id(), fact.provenance().packId());
                assertEquals(id, fixture.get("sourceId"), fact.provenance().sourceId());
            }
            for (Object output : outputs) {
                Map<?, ?> want = (Map<?, ?>) output;
                Parser.Fact fact = byId.remove((String) want.get("id"));
                assertNotNull(id, fact);
                assertEquals(id, want.get("account"), fact.account());
                assertEquals(id, want.get("currency"), fact.money().currency().name());
                assertEquals(id, Long.parseLong((String) want.get("minorUnits")), fact.money().minorUnits());
                assertEquals(id, ((BigDecimal) want.get("scale")).intValueExact(), fact.money().scale());
                if (want.containsKey("time")) {
                    Map<?, ?> time = (Map<?, ?>) want.get("time");
                    assertNotNull(id, fact.time());
                    assertEquals(id, Instant.parse((String) time.get("instant")), fact.time().instant());
                    assertEquals(id, time.get("precision"), fact.time().precision().name());
                    assertEquals(id, ZoneId.of((String) time.get("zone")), fact.time().zone());
                    assertEquals(id, time.get("fallback"), fact.time().fallback());
                }
                // Missing or rejected optional metadata must stay absent, never be invented.
                assertSemanticText(id + "/reason", want.get("reason"), fact.reason());
                assertSemanticText(id + "/channel", want.get("channel"), fact.channel());
            }
            assertTrue(id, byId.isEmpty());
            if (result.status() != Parser.Status.PARSED) {
                assertTrue("No partial facts: " + id, result.facts().isEmpty());
                assertTrue("No successful provenance: " + id, result.matchedProvenance().isEmpty());
            } else {
                assertEquals(id, result.facts().size(), result.matchedProvenance().size());
                for (Parser.Fact fact : result.facts()) {
                    assertTrue(id, result.matchedProvenance().contains(fact.provenance()));
                }
            }
        }
        assertTrue(remaining.isEmpty());
    }

    private static void assertSemanticText(String label, Object expected, Parser.SemanticText actual) {
        if (expected == null) {
            assertNull(label, actual);
        } else {
            Map<?, ?> text = (Map<?, ?>) expected;
            assertNotNull(label, actual);
            assertEquals(label, text.get("id"), actual.id());
            assertEquals(label, text.get("text"), actual.text());
        }
    }

    @Test public void decodedPackRejectsDuplicateTemplateAndOutputIds() throws Exception {
        Map<String, Object> document = asset("multi-currency/pack.json");
        List<Object> templates = new ArrayList<>((List<?>) document.get("templates"));
        templates.add(templates.get(0));
        document.put("templates", templates);
        rejectPack(document);

        document = asset("multi-currency/pack.json");
        Map<String, Object> template = object(((List<?>) document.get("templates")).get(0));
        List<?> outputs = (List<?>) template.get("outputs");
        object(outputs.get(1)).put("id", object(outputs.get(0)).get("id"));
        rejectPack(document);
    }

    @Test public void decodedPackRejectsUnknownRootAndNestedFields() throws Exception {
        Map<String, Object> document = asset("multi-currency/pack.json");
        document.put("unexpected", Boolean.TRUE);
        rejectPack(document);

        document = asset("multi-currency/pack.json");
        firstMoney(document).put("unexpected", Boolean.TRUE);
        rejectPack(document);
    }

    @Test public void decodedPackEnforcesCurrencyMappingSemantics() throws Exception {
        for (String currency : new String[] {
                "{\"fixed\":\"USD\",\"mapping\":{\"USD\":\"USD\"}}",
                "{\"token\":{\"line\":-1,\"after\":\"Currency=\",\"before\":\";\",\"maxLength\":3}}",
                "{\"token\":{\"line\":-1,\"after\":\"Currency=\",\"before\":\";\",\"maxLength\":3},"
                        + "\"mapping\":{\"USD\":\"UNKNOWN\"}}"}) {
            Map<String, Object> document = asset("multi-currency/pack.json");
            // Valid JSON trees reach semantic validation; failures are not lexical rejections.
            firstMoney(document).put("currency", read(currency));
            rejectPack(document);
        }
    }

    @Test public void sharedRawJsonAcceptanceCorpus() throws Exception {
        // org.json reads only the trusted fixture envelope, never the raw document under test.
        JSONObject corpus;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("json-acceptance/cases.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            corpus = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        assertEquals("prototype-json-acceptance-1", corpus.getString("schema"));
        JSONArray cases = corpus.getJSONArray("cases");
        assertTrue("Corpus must contain cases", cases.length() > 0);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < cases.length(); i++) {
            JSONObject fixture = cases.getJSONObject(i);
            String id = fixture.getString("id");
            assertTrue("Duplicate corpus ID", ids.add(id));
            assertTrue(id, fixture.has("text") != fixture.has("bytesHex"));
            byte[] raw;
            if (fixture.has("text")) {
                raw = fixture.getString("text").getBytes(StandardCharsets.UTF_8);
            } else {
                String hex = fixture.getString("bytesHex");
                assertEquals(id, 0, hex.length() % 2);
                raw = new byte[hex.length() / 2];
                for (int j = 0; j < raw.length; j++) {
                    int high = Character.digit(hex.charAt(j * 2), 16);
                    int low = Character.digit(hex.charAt(j * 2 + 1), 16);
                    assertTrue(id, high >= 0 && low >= 0);
                    raw[j] = (byte) ((high << 4) | low);
                }
            }
            boolean accepted;
            try {
                PlatformRuleJson.read(new ByteArrayInputStream(raw));
                accepted = true;
            } catch (PlatformRuleJson.Failure failure) {
                assertEquals(id, failure.code.name(), failure.getMessage());
                assertNull(id, failure.getCause());
                accepted = false;
            }
            assertEquals(id, fixture.getBoolean("accepted"), accepted);
        }
    }

    private static Map<String, Object> asset(String path) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(path)) {
            return PlatformRuleJson.read(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> firstMoney(Map<String, Object> document) {
        Map<String, Object> template = object(((List<?>) document.get("templates")).get(0));
        Map<String, Object> output = object(((List<?>) template.get("outputs")).get(0));
        return object(output.get("money"));
    }

    private static void rejectPack(Map<String, Object> document) {
        try {
            PackDocument.decode(document);
            fail("Expected semantic pack rejection");
        } catch (IllegalArgumentException failure) {
            assertEquals("Invalid pack document", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    private static String nested(int arrays) {
        return "{\"x\":" + repeat("[", arrays) + "0" + repeat("]", arrays) + "}";
    }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static Map<String, Object> read(String text) throws PlatformRuleJson.Failure {
        return PlatformRuleJson.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void reject(String text, PlatformRuleJson.Code code) throws Exception {
        reject(text.getBytes(StandardCharsets.UTF_8), code);
    }
    private static void reject(byte[] bytes, PlatformRuleJson.Code code) throws Exception {
        try {
            PlatformRuleJson.read(new ByteArrayInputStream(bytes));
            fail("Expected document rejection");
        } catch (PlatformRuleJson.Failure failure) {
            if (code != null) assertEquals(code, failure.code);
            assertEquals(failure.code.name(), failure.getMessage());
            assertNull(failure.getCause());
        }
    }
}
