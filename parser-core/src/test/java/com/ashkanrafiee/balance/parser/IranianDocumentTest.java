package com.ashkanrafiee.balance.parser;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Executable draft prototype-1 mappings for the opt-in Iranian extensions. */
public final class IranianDocumentTest {
    private static int checks;

    public static void main(String[] args) {
        validMappings();
        strictObjects();
        invalidCombinations();
        boundedNumbersAndMaps();
        fieldRoleValidation();
        optionalConflicts();
        System.out.println("IranianDocumentTest: " + checks + " checks passed");
    }

    private static void validMappings() {
        Map<String, Object> source = pack();
        PackDocument decoded = PackDocument.decode(source);
        Rules.Output output = decoded.templates().get(0).outputs().get(0);
        equal(output.accountOptional(), true);
        equal(output.account().normalization(), Rules.Normalization.ACCOUNT);
        equal(output.account().numeric().mode(), Rules.NumericMode.EXACT);
        equal(output.account().numeric().segments(), List.of(new Rules.Width(6, 24)));
        equal(output.reason().field().terminated(), true);
        equal(output.channel().field().normalization(), Rules.Normalization.CHANNEL);
        equal(output.date().options().calendar(), Rules.Calendar.JALALI);
        equal(output.date().options().year(), Rules.Year.TWO_DIGIT);
        equal(output.date().options().yearBase(), 1400);
        equal(output.date().options().zone().getId(), "Asia/Tehran");
        Parser.Result result = parse(decoded, "05/6/30-08:53", "Account= ۰۰۰۱۲۳\n");
        equal(result.status(), Parser.Status.PARSED);
        equal(result.facts().get(0).account(), "000123");
        equal(result.facts().get(0).reason(), new Parser.SemanticText("refund", "برگشت پول"));
        equal(result.facts().get(0).channel(), new Parser.SemanticText("mobile", "همراه بانک"));
        equal(result.facts().get(0).time().instant(), Instant.parse("2026-09-21T05:23:00Z"));
        at(source, "templates", 0, "outputs", 0, "reason", "mapping").clear();
        at(source, "templates", 0, "outputs", 0, "account", "numeric", "segments", 0).put("min", 20);
        equal(output.reason().mapping(), Map.of("برگشت پول", "refund"));
        equal(output.account().numeric().segments().get(0).min(), 6);
        immutable(() -> output.reason().mapping().clear());
        immutable(() -> output.account().numeric().segments().clear());
        Map<String, Object> absent = pack();
        at(absent, "templates", 0, "outputs", 0).remove("account");
        result = parse(PackDocument.decode(absent), "05/6/30-08:53", "No account\n");
        equal(result.facts().get(0).accountState(), Parser.AccountState.UNRESOLVED);
        equal(result.facts().get(0).account(), null);
        Map<String, Object> compact = pack();
        Map<String, Object> date = at(compact, "templates", 0, "outputs", 0, "date");
        date.put("orders", List.of("MD"));
        Map<String, Object> options = at(date, "options");
        options.put("year", "NEIGHBOR"); options.put("yearBase", 0);
        options.put("layout", "COMPACT"); options.put("variableWidth", false);
        result = parse(PackDocument.decode(compact), "0630-08:53", "Account=000123\n");
        equal(result.facts().get(0).time().instant(), Instant.parse("2026-09-21T05:23:00Z"));
        Map<String, Object> foreign = pack();
        date = at(foreign, "templates", 0, "outputs", 0, "date");
        date.put("calendar", "GREGORIAN"); date.put("orders", List.of("DMY"));
        options = at(date, "options");
        options.put("yearBase", 2000); options.put("seconds", true); options.put("zone", "UTC");
        result = parse(PackDocument.decode(foreign), "21/9/26-05:23:59", "Account=000123\n");
        equal(result.facts().get(0).time().instant(), Instant.parse("2026-09-21T05:23:59Z"));
        equal(result.facts().get(0).time().precision(), Parser.Precision.SECOND);
    }

    private static void strictObjects() {
        Object[][] paths = {
                {"account", "numeric"}, {"account", "numeric", "segments", 0}, {"reason"},
                {"reason", "field"}, {"channel"}, {"date", "options"}
        };
        for (Object[] path : paths) {
            reject(p -> out(p, path).put("private-unknown", "private-value"));
            for (String key : out(pack(), path).keySet()) {
                reject(p -> out(p, path).put(key, null));
                // Field extension properties are optional; object grammar members are required.
                if (!List.of("normalization", "numeric", "terminated").contains(key))
                    reject(p -> out(p, path).remove(key));
            }
        }
        reject(p -> out(p).put("accountOptional", "true"));
        reject(p -> out(p, "account").put("normalization", "UNKNOWN"));
        reject(p -> out(p, "account", "numeric").put("mode", "FIRST"));
        reject(p -> out(p, "reason", "field").put("terminated", "true"));
        reject(p -> out(p, "reason").put("digitFree", "true"));
        reject(p -> out(p, "date", "options").put("calendar", "GREGORIAN"));
        reject(p -> out(p, "date", "options").put("zone", "Private/Unknown"));
        reject(p -> out(p, "date", "options").put("zone", "x".repeat(129)));
        reject(p -> out(p, "date", "options").put("zone", 1));
    }

    private static void invalidCombinations() {
        reject(p -> { out(p).remove("account"); out(p).remove("accountOptional"); });
        reject(p -> out(p, "region").put("normalization", "TEXT"));
        reject(p -> out(p, "account", "numeric").put("segments", List.of()));
        reject(p -> out(p, "account", "numeric").put("segments",
                List.of(obj("min", 1, "max", 2), obj("min", 1, "max", 2), obj("min", 1, "max", 2),
                        obj("min", 1, "max", 2), obj("min", 1, "max", 2))));
        reject(p -> out(p, "account", "numeric").put("segments", List.of(obj("min", 1, "max", 256), obj("min", 1, "max", 1))));
        reject(p -> out(p, "account", "numeric", "segments", 0).put("min", 25));
        reject(p -> out(p, "reason", "field").put("line", -1));
        reject(p -> out(p, "reason").put("minLength", 61));
        reject(p -> out(p, "reason").put("mapping", Map.of()));
        reject(p -> out(p, "reason").put("mapping", Map.of("private", "invalid/id")));
        reject(p -> out(p, "reason").put("mapping", Map.of("", "valid")));
        reject(p -> out(p, "date").remove("options")); // Jalali requires explicit zone/year policies.
        reject(p -> { out(p, "date").put("withTime", false); out(p, "date", "options").put("seconds", true); });
        reject(p -> out(p, "date", "options").put("yearBase", 0));
        reject(p -> out(p, "date", "options").put("yearBase", 3079));
        reject(p -> out(p, "date", "options").put("year", "FULL"));
        reject(p -> out(p, "date", "options").put("year", "NEIGHBOR"));
        reject(p -> out(p, "date", "options").put("layout", "COMPACT"));
        reject(p -> out(p, "date", "options").put("timeSeparator", ":"));
        reject(p -> out(p, "date").put("orders", List.of("MD", "YMD")));
    }

    private static void boundedNumbersAndMaps() {
        for (Object value : List.of(0, -1, 257, "6", 6.0, new BigDecimal("6.1"), new BigDecimal("1E+1000000")))
            reject(p -> out(p, "account", "numeric", "segments", 0).put("min", value));
        for (Object value : List.of(6, 6L, new BigDecimal("6.000"))) {
            Map<String, Object> p = pack();
            out(p, "account", "numeric", "segments", 0).put("min", value);
            equal(PackDocument.decode(p).templates().get(0).outputs().get(0).account().numeric().segments().get(0).min(), 6);
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        for (int i = 0; i < Rules.MAX_MAP; i++) mapping.put("token" + i, "id" + i);
        Map<String, Object> p = pack();
        out(p, "reason").put("mapping", mapping);
        equal(PackDocument.decode(p).templates().get(0).outputs().get(0).reason().mapping().size(), 16);
        mapping.put("excess", "excess");
        invalid(p);
        for (Object value : List.of("0", -1, 9901, new BigDecimal("1400.5")))
            reject(doc -> out(doc, "date", "options").put("yearBase", value));
    }

    private static void fieldRoleValidation() {
        for (String role : List.of("amount", "originalAmount", "date", "currency", "direction")) {
            for (Rules.NumericMode mode : Rules.NumericMode.values()) {
                reject(p -> roleField(p, role).put("numeric",
                        obj("segments", List.of(obj("min", 1, "max", 24)), "mode", mode.name())));
            }
            for (Rules.Normalization normalization : Rules.Normalization.values()) {
                boolean token = role.equals("currency") || role.equals("direction");
                Map<String, Object> p = pack();
                roleField(p, role).put("normalization", normalization.name());
                if (normalization == Rules.Normalization.NONE || token && normalization == Rules.Normalization.TEXT)
                    equal(PackDocument.decode(p).templates().size(), 1);
                else invalid(p);
            }
        }
        // Optional metadata retains numeric operations; forbidden roles cannot reach evaluation.
        Map<String, Object> p = pack();
        out(p, "reason", "field").put("numeric", obj("segments", List.of(obj("min", 1, "max", 24)), "mode", "UNIQUE"));
        equal(PackDocument.decode(p).templates().get(0).outputs().get(0).reason().field().numeric().mode(), Rules.NumericMode.UNIQUE);
        // The actual source amount remains strict after document decoding.
        for (String amount : List.of("1  000", "1\u200c2", "12-", "12 IRR", "۱۲")) {
            p = pack();
            out(p, "money").put("group", " ");
            out(p, "money").put("digits", "ASCII");
            Parser.Result result = new Parser(PackDocument.decode(p).templates()).parse(new Parser.Message("synthetic", "SYNTHETIC",
                    "Synthetic\nبرگشت پول\nاز طریق: همراه بانک\nAccount=000123\nAmount=" + amount + ";Date=05/6/30-08:53;",
                    Instant.parse("2026-09-21T06:00:00Z"), ZoneOffset.UTC));
            equal(result.status(), Parser.Status.INVALID);
            equal(result.facts().size(), 0);
            equal(result.diagnostics().stream().anyMatch(d -> d.code() == Parser.Code.INVALID_MONEY), true);
        }
    }

    private static Map<String, Object> roleField(Map<String, Object> p, String role) {
        return switch (role) {
            case "amount" -> out(p, "money", "amount");
            case "originalAmount" -> {
                Map<String, Object> original = new LinkedHashMap<>(out(p, "money"));
                original.put("amount", field(-1, "Original=", ";", 64));
                out(p).put("originalAmount", original);
                yield at(original, "amount");
            }
            case "date" -> out(p, "date", "field");
            case "currency", "direction" -> {
                Map<String, Object> rule = role.equals("currency") ? out(p, "money", "currency") : out(p, "direction");
                rule.put("token", field(-1, role + "=", ";", 64));
                rule.put("mapping", obj(role.equals("currency") ? "IRR" : "DR", role.equals("currency") ? "IRR" : "DEBIT"));
                yield at(rule, "token");
            }
            default -> throw new AssertionError("Unknown test role");
        };
    }

    private static void optionalConflicts() {
        for (String field : List.of("reason", "channel")) for (boolean absent : List.of(true, false)) {
            Map<String, Object> p = pack();
            Map<String, Object> other = pack();
            at(other, "templates", 0).put("id", "second");
            if (absent) out(other).remove(field);
            else out(other, field, "mapping").replaceAll((key, value) -> "other");
            p.put("templates", List.of(at(p, "templates", 0), at(other, "templates", 0)));
            Parser.Result result = parse(PackDocument.decode(p), "05/6/30-08:53", "Account=000123\n");
            equal(result.status(), Parser.Status.PARSED);
            equal(result.facts().size(), 1);
            equal(result.facts().get(0).money().minorUnits(), -100L);
            equal(result.matchedProvenance().size(), 2);
            equal(field.equals("reason") ? result.facts().get(0).reason() : result.facts().get(0).channel(), null);
            equal(result.diagnostics().stream().anyMatch(d -> d.field().equals(field) && d.code() == Parser.Code.OPTIONAL_CONFLICT), true);
            List<?> templates = (List<?>) p.get("templates");
            p.put("templates", List.of(templates.get(1), templates.get(0)));
            equal(parse(PackDocument.decode(p), "05/6/30-08:53", "Account=000123\n"), result);
        }
    }

    private static Parser.Result parse(PackDocument pack, String date, String account) {
        return new Parser(pack.templates()).parse(new Parser.Message("synthetic", "SYNTHETIC",
                "Synthetic\nبرگشت پول\nاز طریق: همراه بانك\n" + account + "Amount=100;Date=" + date + ";",
                Instant.parse("2026-09-21T06:00:00Z"), ZoneOffset.UTC));
    }
    private static Map<String, Object> pack() {
        Map<String, Object> account = field(3, "Account=", "", 64);
        account.put("normalization", "ACCOUNT");
        account.put("numeric", obj("segments", List.of(obj("min", 6, "max", 24)), "mode", "EXACT"));
        Map<String, Object> reasonField = field(1, "", "", 256);
        reasonField.put("normalization", "TEXT"); reasonField.put("terminated", true);
        Map<String, Object> channelField = field(2, "از طریق:", "", 256);
        channelField.put("normalization", "CHANNEL"); channelField.put("terminated", true);
        Map<String, Object> output = obj("id", "movement", "region", field(-1, "", "", 16384),
                "account", account, "accountOptional", true, "kind", "POSTED_MOVEMENT",
                "direction", obj("fixed", "DEBIT"),
                "money", obj("amount", field(-1, "Amount=", ";", 64), "currency", obj("fixed", "IRR"),
                        "decimal", ".", "group", ",", "grouping", "WESTERN", "digits", "ASCII_PERSIAN_ARABIC", "unitMultiplier", 1),
                "reason", obj("field", reasonField, "mapping", obj("برگشت پول", "refund"), "minLength", 2, "maxLength", 60, "digitFree", true),
                "channel", obj("field", channelField, "mapping", obj("همراه بانک", "mobile"), "minLength", 2, "maxLength", 40, "digitFree", true),
                "date", obj("calendar", "JALALI", "field", field(-1, "Date=", ";", 64), "orders", List.of("YMD"),
                        "separator", "/", "withTime", true, "digits", "ASCII_PERSIAN_ARABIC", "maxPastSeconds", 3888000, "maxFutureSeconds", 21600,
                        "options", obj("year", "TWO_DIGIT", "yearBase", 1400, "variableWidth", true, "layout", "SEPARATED",
                                "timeSeparator", "-", "seconds", false, "zone", "Asia/Tehran")));
        return obj("schema", "prototype-1", "id", "synthetic.ir", "revision", "r1",
                "bank", obj("id", "synthetic.bank", "country", "IR", "name", "Synthetic bank",
                        "provenance", "OFFICIAL"),
                "templates", List.of(obj("id", "example", "senders", List.of("SYNTHETIC"),
                        "guards", List.of(obj("line", 0, "literal", "Synthetic", "excluded", false)), "outputs", List.of(output))));
    }
    private static Map<String, Object> field(int line, String after, String before, int max) {
        return obj("line", line, "after", after, "before", before, "maxLength", max);
    }
    private static Map<String, Object> obj(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }
    private static Map<String, Object> out(Map<String, Object> p, Object... path) {
        List<Object> all = new ArrayList<>(List.of("templates", 0, "outputs", 0));
        all.addAll(List.of(path));
        return at(p, all.toArray());
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> p, Object... path) {
        Object current = p;
        for (Object key : path) current = key instanceof Integer index ? ((List<?>) current).get(index) : ((Map<?, ?>) current).get(key);
        return (Map<String, Object>) current;
    }
    private static void reject(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> p = pack(); mutation.accept(p); invalid(p);
    }
    private static void invalid(Map<String, Object> p) {
        try { PackDocument.decode(p); throw new AssertionError("Expected document rejection"); }
        catch (IllegalArgumentException expected) {
            equal(expected.getMessage(), "Invalid pack document"); equal(expected.getCause(), null);
        }
    }
    private static void immutable(Runnable action) {
        try { action.run(); throw new AssertionError("Expected immutable value"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void equal(Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) throw new AssertionError("Document check " + checks + ": " + actual + " != " + expected);
    }
}
