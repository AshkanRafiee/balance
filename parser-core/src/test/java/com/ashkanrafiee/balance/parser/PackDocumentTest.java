package com.ashkanrafiee.balance.parser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Synthetic object fixtures; no JSON library or assertion-enable flag required. */
public final class PackDocumentTest {
    private static int checks;

    public static void main(String[] args) {
        validAndImmutable();
        structuralValidation();
        semanticValidation();
        moneyFlags();
        numericValidation();
        boundsAndGraphs();
        System.out.println("PackDocumentTest: " + checks + " checks passed");
    }

    private static void validAndImmutable() {
        Map<String, Object> source = pack();
        PackDocument document = PackDocument.decode(source);
        equal(document.schema(), "prototype-1");
        equal(document.id(), "synthetic.pack");
        equal(document.revision(), "r1");
        equal(document.bank(), new PackDocument.Bank("synthetic.bank", "US", "Synthetic Bank",
                PackDocument.Bank.Provenance.OFFICIAL));
        Rules.Template template = document.templates().get(0);
        equal(template.packId(), document.id());
        equal(template.revision(), document.revision());
        equal(template.bankId(), document.bank().id());
        equal(template.outputs().get(0).region().maxLength(), Rules.MAX_INPUT);
        equal(template.outputs().get(1).money().currency().mapping(), Map.of("USD", Rules.Currency.USD));
        equal(template.outputs().get(1).date().orders(), Set.of(Rules.Order.YMD, Rules.Order.DMY));
        Parser.Result result = new Parser(document.templates()).parse(new Parser.Message("synthetic-source", "SYNTHETIC",
                "Statement;Account=0001;Balance=89.00;Debited=11.00;Original=10.00;Currency=USD;Direction=DR;Date=2026/04/04;",
                Instant.parse("2026-04-04T12:00:00Z"), ZoneOffset.UTC));
        equal(result.status(), Parser.Status.PARSED);
        equal(result.facts().size(), 2);
        equal(result.facts().get(0).money().minorUnits(), 8900L);
        equal(result.facts().get(1).money().minorUnits(), -1100L);
        equal(result.facts().get(1).originalAmount(), new Parser.Money(Rules.Currency.EUR, 1000, 2));
        equal(result.facts().get(1).time().precision(), Parser.Precision.DAY);
        at(source, "templates", 0, "outputs", 1, "money", "currency", "mapping").clear();
        at(source, "bank").put("name", "Changed");
        source.clear();
        equal(document.bank().name(), "Synthetic Bank");
        equal(template.outputs().get(1).money().currency().mapping().size(), 1);
        immutable(() -> document.templates().clear());
        immutable(() -> template.outputs().clear());
        immutable(() -> template.senders().clear());
        immutable(() -> template.outputs().get(1).money().currency().mapping().clear());

        // Fixed-only, token-only and fixed-with-token are all intentional schema variants.
        for (boolean currency : List.of(false, true)) {
            for (int variant = 0; variant < 3; variant++) {
                Map<String, Object> p = pack();
                Map<String, Object> choice = currency
                        ? at(p, "templates", 0, "outputs", 1, "money", "currency")
                        : at(p, "templates", 0, "outputs", 1, "direction");
                if (variant == 0) { choice.remove("token"); choice.remove("mapping"); }
                if (variant == 1) choice.remove("fixed");
                equal(PackDocument.decode(p).templates().size(), 1);
            }
        }
        Map<String, Object> p = pack();
        at(p, "templates", 0, "outputs", 1).remove("date");
        at(p, "templates", 0, "outputs", 1).remove("originalAmount");
        equal(PackDocument.decode(p).templates().get(0).outputs().get(1).date(), null);
    }

    private static void structuralValidation() {
        invalid(null);
        // Every fixed-schema object rejects unknown keys and explicit nulls.
        Object[][] paths = {
                {}, {"bank"}, {"templates", 0}, {"templates", 0, "guards", 0},
                {"templates", 0, "outputs", 1}, {"templates", 0, "outputs", 1, "region"},
                {"templates", 0, "outputs", 1, "account"}, {"templates", 0, "outputs", 1, "money"},
                {"templates", 0, "outputs", 1, "money", "amount"},
                {"templates", 0, "outputs", 1, "money", "currency"},
                {"templates", 0, "outputs", 1, "direction"},
                {"templates", 0, "outputs", 1, "originalAmount"}, {"templates", 0, "outputs", 1, "date"}
        };
        for (Object[] path : paths) {
            reject(p -> at(p, path).put("private-unknown-key", "private-value"));
            for (String key : at(pack(), path).keySet()) {
                reject(p -> at(p, path).put(key, null));
                // Optional members are tested separately rather than treated as required.
                if (!Set.of("fixed", "token", "mapping", "direction", "originalAmount", "date").contains(key))
                    reject(p -> at(p, path).remove(key));
            }
        }
        reject(p -> p.put("schema", "future"));
        reject(p -> p.put("bank", List.of()));
        reject(p -> p.put("templates", Set.of()));
        reject(p -> at(p, "bank").put("name", 1));
        reject(p -> at(p, "templates", 0, "guards", 0).put("excluded", "false"));
        reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("withTime", 0));
        for (String which : List.of("direction", "currency")) {
            Object[] path = which.equals("direction") ? new Object[]{"templates", 0, "outputs", 1, "direction"}
                    : new Object[]{"templates", 0, "outputs", 1, "money", "currency"};
            reject(p -> at(p, path).clear());
            reject(p -> at(p, path).remove("token"));
            reject(p -> at(p, path).remove("mapping"));
            reject(p -> at(p, path).put("mapping", Map.of()));
            reject(p -> at(p, path).put("mapping", Map.of("", "USD")));
            reject(p -> at(p, path).put("mapping", Map.of(1, "USD")));
            reject(p -> at(p, path).put("mapping", Map.of("private-token", "private-enum")));
        }
    }

    private static void semanticValidation() {
        for (String id : List.of("", "UPPER", "with space", "a".repeat(65), "private/id")) {
            reject(p -> p.put("id", id));
            reject(p -> p.put("revision", id));
            reject(p -> at(p, "bank").put("id", id));
            reject(p -> at(p, "templates", 0).put("id", id));
            reject(p -> at(p, "templates", 0, "outputs", 0).put("id", id));
        }
        for (String country : List.of("us", "USA", "U1", "", "US\n"))
            reject(p -> at(p, "bank").put("country", country));
        for (String name : List.of("", "x".repeat(129), "bad\u0000", "bad\ud800"))
            reject(p -> at(p, "bank").put("name", name));
        // Provenance is required and is exactly the three declared values. It is a label the app
        // shows, never a quality claim, so nothing else about the pack changes with it -- but a
        // contributed pack that defaulted to looking official would be a trust failure, not a
        // cosmetic one, so absence is refused outright.
        for (String provenance : List.of("", "official", "Official", "UNKNOWN", "0", "true", "1"))
            reject(p -> at(p, "bank").put("provenance", provenance));
        reject(p -> at(p, "bank").remove("provenance"));
        Map<String, Object> community = pack();
        at(community, "bank").put("provenance", "COMMUNITY");
        equal(PackDocument.decode(community).bank().provenance(),
                PackDocument.Bank.Provenance.COMMUNITY);
        equal(PackDocument.decode(pack()).bank().provenance(),
                PackDocument.Bank.Provenance.OFFICIAL);
        // LOCAL is the value the store writes for a pack a user brought themselves, so that an
        // imported pack can never present itself as one the app vetted.
        Map<String, Object> local = pack();
        at(local, "bank").put("provenance", "LOCAL");
        equal(PackDocument.decode(local).bank().provenance(), PackDocument.Bank.Provenance.LOCAL);
        equal(PackDocument.decode(local).templates(), PackDocument.decode(pack()).templates(),
                "the label changes nothing about the rules");
        reject(p -> p.put("templates", List.of(at(p, "templates", 0), at(p, "templates", 0))));
        reject(p -> at(p, "templates", 0, "outputs", 1).put("id", "balance"));
        reject(p -> at(p, "templates", 0).put("senders", List.of("SYNTHETIC", "SYNTHETIC")));
        reject(p -> at(p, "templates", 0, "guards", 0).put("excluded", true));
        reject(p -> at(p, "templates", 0, "outputs", 1).remove("direction"));
        reject(p -> at(p, "templates", 0, "outputs", 0).put("direction", obj("fixed", "CREDIT")));
        reject(p -> at(p, "templates", 0, "outputs", 0).put("originalAmount", money("Original=", "EUR")));
        reject(p -> at(p, "templates", 0, "outputs", 0, "account").put("before", "Account="));
        for (String decimal : List.of("", "..", ";", "\u0000", "private-separator"))
            reject(p -> at(p, "templates", 0, "outputs", 0, "money").put("decimal", decimal));
        for (String group : List.of(".", ";", "\u0000", ",,"))
            reject(p -> at(p, "templates", 0, "outputs", 0, "money").put("group", group));
        reject(p -> at(p, "templates", 0, "outputs", 0, "money").put("grouping", "WESTERN"));
        reject(p -> at(p, "templates", 0, "outputs", 0, "money").put("unitMultiplier", 10));
        for (String calendar : List.of("JALALI", "gregorian", ""))
            reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("calendar", calendar));
        reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("orders", List.of("YMD", "YMD")));
        reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("orders", List.of("private-order")));
        reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("separator", ":"));
    }

    private static void moneyFlags() {
        // sign and leadingPoint are opt-in money flags: absent means the strict default, present
        // means the declared widening. Both are booleans/enumerations only — a pack cannot smuggle
        // a number or a string in where the typed boundary expects one of them.
        Rules.MoneyRule plain = PackDocument.decode(pack()).templates().get(0)
                .outputs().get(0).money();
        equal(plain.sign(), Rules.Sign.LEADING);
        equal(plain.leadingPoint(), false);
        Map<String, Object> source = pack();
        Map<String, Object> money = at(source, "templates", 0, "outputs", 0, "money");
        money.put("sign", "TRAILING");
        money.put("leadingPoint", true);
        Rules.MoneyRule declared = PackDocument.decode(source).templates().get(0)
                .outputs().get(0).money();
        equal(declared.sign(), Rules.Sign.TRAILING);
        equal(declared.leadingPoint(), true);
        for (Map.Entry<String, List<Object>> e : Map.of(
                "sign", List.of("", "true", "leading", 1, 1L, 0.0, new BigDecimal("1"), List.of(), Map.of()),
                "leadingPoint", List.of("", "true", "LEADING", 1, 1L, 0.0, new BigDecimal("1"), List.of(), Map.of()))
                    .entrySet())
            for (Object value : e.getValue()) {
                Map<String, Object> p = pack();
                at(p, "templates", 0, "outputs", 0, "money").put(e.getKey(), value);
                invalid(p);
            }
    }

    private static void numericValidation() {
        for (Object n : List.of(1, 1L, new BigDecimal("1.000"), new BigDecimal("1E+0"))) {
            Map<String, Object> p = pack();
            at(p, "templates", 0, "outputs", 0, "money").put("unitMultiplier", n);
            equal(PackDocument.decode(p).templates().size(), 1);
        }
        for (Object n : List.of("1", 1.0, 1.0f, (short) 1, BigInteger.ONE, true,
                new BigDecimal("1.1"), new BigDecimal("1E+1000000"), new BigDecimal("1E-1000000"),
                Long.MAX_VALUE, Long.MIN_VALUE, -1, 0, 2, 11))
            reject(p -> at(p, "templates", 0, "outputs", 0, "money").put("unitMultiplier", n));
        Object[][] paths = {{"templates", 0, "outputs", 0, "region"},
                {"templates", 0, "outputs", 0, "account"}, {"templates", 0, "guards", 0}};
        for (Object[] path : paths) {
            for (Object n : List.of(-2, 256, "0", new BigDecimal("0.5")))
                reject(p -> at(p, path).put("line", n));
        }
        for (String key : List.of("maxPastSeconds", "maxFutureSeconds")) {
            int max = key.equals("maxPastSeconds") ? 31_622_400 : 172_800;
            for (Object n : List.of(-1, max + 1, "0", new BigDecimal("0.1")))
                reject(p -> at(p, "templates", 0, "outputs", 1, "date").put(key, n));
            for (Object n : List.of(0, (long) max, new BigDecimal(max))) {
                Map<String, Object> p = pack();
                at(p, "templates", 0, "outputs", 1, "date").put(key, n);
                equal(PackDocument.decode(p).templates().size(), 1);
            }
        }
    }

    private static void boundsAndGraphs() {
        reject(p -> p.put("templates", List.of()));
        reject(p -> p.put("templates", Collections.nCopies(257, at(p, "templates", 0))));
        for (String key : List.of("senders", "guards", "outputs")) {
            reject(p -> at(p, "templates", 0).put(key, List.of()));
            reject(p -> at(p, "templates", 0).put(key, Collections.nCopies(key.equals("outputs") ? 9 : 17, "x")));
        }
        reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("orders", List.of()));
        reject(p -> at(p, "templates", 0, "outputs", 1, "date").put("orders", Collections.nCopies(5, "YMD")));
        reject(p -> at(p, "templates", 0, "outputs", 0, "region").put("maxLength", 16385));
        reject(p -> at(p, "templates", 0, "outputs", 0, "account").put("maxLength", 257));
        reject(p -> at(p, "templates", 0, "outputs", 0, "account").put("maxLength", 0));
        reject(p -> at(p, "templates", 0, "outputs", 0, "account").put("after", "x".repeat(129)));
        reject(p -> at(p, "templates", 0).put("senders", List.of("x".repeat(129))));
        reject(p -> {
            Map<String, Object> mapping = at(p, "templates", 0, "outputs", 1, "money", "currency", "mapping");
            mapping.clear();
            for (int i = 0; i < 17; i++) mapping.put("token" + i, "USD");
        });
        reject(p -> p.put("bank", p));
        reject(p -> at(p, "templates", 0, "outputs", 1, "money", "currency", "mapping").put("cycle", p));
        reject(p -> {
            List<Object> cycle = new ArrayList<>();
            cycle.add(cycle);
            p.put("templates", cycle);
        });
        // Maximum valid template/output counts are accepted, not merely rejected above the cap.
        Map<String, Object> p = pack();
        List<Object> templates = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            Map<String, Object> t = at(pack(), "templates", 0);
            t.put("id", "t" + i);
            List<Object> outputs = new ArrayList<>();
            for (int j = 0; j < 8; j++) outputs.add(output("o" + j, "BOOKED_BALANCE", "Balance="));
            t.put("outputs", outputs);
            templates.add(t);
        }
        p.put("templates", templates);
        equal(PackDocument.decode(p).templates().size(), 256);
    }

    private static Map<String, Object> pack() {
        Map<String, Object> movement = output("movement", "POSTED_MOVEMENT", "Debited=");
        movement.put("direction", obj("fixed", "DEBIT", "token", field("Direction="), "mapping", obj("DR", "DEBIT")));
        at(movement, "money").put("currency", obj("fixed", "USD", "token", field("Currency="), "mapping", obj("USD", "USD")));
        movement.put("originalAmount", money("Original=", "EUR"));
        movement.put("date", obj("calendar", "GREGORIAN", "field", field("Date="), "orders", List.of("YMD", "DMY"),
                "separator", "/", "withTime", false, "digits", "ASCII", "maxPastSeconds", 31622400, "maxFutureSeconds", 172800));
        return obj("schema", "prototype-1", "id", "synthetic.pack", "revision", "r1",
                "bank", obj("id", "synthetic.bank", "country", "US", "name", "Synthetic Bank",
                        "provenance", "OFFICIAL"),
                "templates", List.of(obj("id", "statement", "senders", List.of("SYNTHETIC"),
                        "guards", List.of(obj("line", -1, "literal", "Statement", "excluded", false)),
                        "outputs", List.of(output("balance", "BOOKED_BALANCE", "Balance="), movement))));
    }

    private static Map<String, Object> output(String id, String kind, String amount) {
        return obj("id", id, "kind", kind, "region", obj("line", -1, "after", "", "before", "", "maxLength", 16384),
                "account", field("Account="), "money", money(amount, "USD"));
    }

    private static Map<String, Object> money(String amount, String currency) {
        return obj("amount", field(amount), "currency", obj("fixed", currency), "decimal", ".", "group", "",
                "grouping", "NONE", "digits", "ASCII", "unitMultiplier", 1);
    }

    private static Map<String, Object> field(String after) {
        return obj("line", -1, "after", after, "before", ";", "maxLength", 256);
    }

    private static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> root, Object... path) {
        Object value = root;
        for (Object key : path) value = key instanceof Integer i ? ((List<?>) value).get(i) : ((Map<?, ?>) value).get(key);
        return (Map<String, Object>) value;
    }

    private static void reject(Consumer<Map<String, Object>> change) {
        Map<String, Object> p = pack();
        change.accept(p);
        invalid(p);
    }

    private static void invalid(Map<String, Object> p) {
        try { PackDocument.decode(p); }
        catch (IllegalArgumentException e) {
            equal(e.getMessage(), "Invalid pack document");
            equal(e.getCause(), null);
            equal(e.getSuppressed().length, 0);
            return;
        }
        throw new AssertionError("Invalid document accepted");
    }

    private static void immutable(Runnable action) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Mutable decoded collection");
    }

    private static void equal(Object actual, Object expected) {
        equal(actual, expected, "check");
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected))
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
}
