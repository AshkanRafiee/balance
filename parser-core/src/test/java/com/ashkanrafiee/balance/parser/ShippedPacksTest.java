package com.ashkanrafiee.balance.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Decodes and compiles every pack the app ships under rules/app, in both regions, and
 * pins the facts the maintainer reported for the community CartaBCC pack. The expected
 * values live here, not in the fixtures: the fixtures describe the messages, this states
 * the claim. Dependency-free executable main like its sibling suites -- parser-core has no
 * test framework and no JSON library, and a minimal strict reader is kept inline for the
 * same reason the Gradle gates shell out to Python and Groovy for decoding.
 */
public final class ShippedPacksTest {
    private static int checks;
    private static final Pattern REVIEWED_ON = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String APP = "rules/app";
    /** What a shipped rule may honestly claim it was built from. */
    private static final Set<String> EVIDENCE =
            Set.of("legacy-tables", "reported-messages", "official-spec");
    private static final String COMMUNITY = "community.it.cartabcc";

    /** What the engine must produce for each reported message: bank, kind, EUR minor units,
     * scale, and the event time actually achieved (DAY: the stated time is not reachable). */
    private record Claim(String templateId, String outputId, String bank, long minorUnits, int scale,
                         String currency, String instant, Parser.Precision precision, String zone,
                         boolean fallback) {}

    private static final Map<String, Claim> CLAIMS = Map.of(
            "spend", new Claim("spend", "movement", "it.cartabcc", -6680L, 2, "EUR",
                    "2026-09-26T22:00:00Z", Parser.Precision.DAY, "Europe/Rome", false),
            "payment", new Claim("payment", "movement", "it.cartabcc", -799L, 2, "EUR",
                    "2026-09-24T22:00:00Z", Parser.Precision.DAY, "Europe/Rome", false));

    public static void main(String[] args) {
        List<Path> catalogs = shipped("catalog.json");
        List<Path> packs = shipped("pack.json");
        equal(catalogs.isEmpty(), false);
        equal(packs.isEmpty(), false);
        catalogsAgree(catalogs);
        Map<Path, PackDocument> documents = compileEverything(packs);
        movementOnly();
        Path community = appRoot().resolve("it-community/it.cartabcc");
        reportedMessages(documents.get(community.resolve("pack.json")),
                read(community.resolve("fixtures.json")));
        System.out.println("ShippedPacksTest: " + checks + " checks passed, "
                + documents.size() + " shipped packs, " + catalogs.size() + " region catalogs");
    }

    /** One loader reads both regions: every shipped catalog has the same key set, its own
     * counts are truthful, and its bank ids are namespaced by its own market. */
    private static void catalogsAgree(List<Path> catalogs) {
        Set<Set<String>> shapes = new LinkedHashSet<>();
        for (Path path : catalogs) {
            Map<String, Object> catalog = read(path);
            shapes.add(Set.copyOf(catalog.keySet()));
            List<?> banks = list(catalog.get("banks"), "banks");
            int aliases = 0;
            Set<String> ids = new LinkedHashSet<>();
            for (Object item : banks) {
                Map<?, ?> bank = map(item, "bank");
                ids.add(text(bank.get("id"), "bank id"));
                aliases += list(bank.get("senders"), "senders").size();
                reviewed(bank.get("review"));
            }
            Map<String, Object> counts = map(catalog.get("counts"), "counts");
            equal(number(counts.get("banks"), "counts.banks"), (long) banks.size());
            equal(number(counts.get("aliases"), "counts.aliases"), (long) aliases);
            equal(ids.size(), banks.size());
            String market = text(catalog.get("market"), "market");
            equal(text(catalog.get("engine"), "engine"), Parser.ENGINE);
            for (String id : ids) equal(id.startsWith(market.toLowerCase(Locale.ROOT) + "."), true);
        }
        equal(shapes.size(), 1);
        equal(shapes.iterator().next().containsAll(List.of("allowlist", "banks", "catalog", "counts",
                "engine", "market", "profile", "source")), true);
    }

    /** Every shipped rule states what it was built from and who has seen it work on a real
     * message, because those are two different claims. The gates in this repository can only prove
     * that a rule does what its fixtures say: a fixture is evidence about the rule, not about the
     * bank. So the record is kept explicit and required, and 'marketReviewer' is allowed to be
     * false -- that is the honest state of a foreign pack that only reader reports back it. */
    private static void reviewed(Object value) {
        Map<?, ?> review = map(value, "review");
        equal(Set.copyOf(review.keySet()),
                Set.of("evidence", "realMessages", "marketReviewer", "reviewedOn"));
        equal(EVIDENCE.contains(review.get("evidence")), true);
        equal(review.get("realMessages") instanceof Boolean, true);
        equal(review.get("marketReviewer") instanceof Boolean, true);
        // Somebody holding an account is the only real proof a bank still sends this layout, so a
        // named reviewer without real messages is not a review at all.
        boolean reviewer = (Boolean) review.get("marketReviewer");
        boolean realMessages = (Boolean) review.get("realMessages");
        equal(reviewer && !realMessages, false);
        equal(REVIEWED_ON.matcher(text(review.get("reviewedOn"), "reviewedOn")).matches(), true);
    }

    /** Every shipped pack decodes into the typed boundary, compiles on its own, and the whole
     * shipped set compiles into one snapshot; every template sender is an exact sender its own
     * region catalog lists for the pack's bank. */
    private static Map<Path, PackDocument> compileEverything(List<Path> packs) {
        Map<Path, PackDocument> documents = new LinkedHashMap<>();
        List<Rules.Template> all = new ArrayList<>();
        for (Path path : packs) {
            PackDocument document = PackDocument.decode(read(path));
            equal(document.schema(), "prototype-1");
            new Parser(document.templates()); // Each shipped pack compiles on its own.
            documents.put(path, document);
            all.addAll(document.templates());
            Map<String, Object> catalog = read(path.getParent().getParent().resolve("catalog.json"));
            Set<String> known = new LinkedHashSet<>();
            for (Object item : list(catalog.get("banks"), "banks")) {
                Map<?, ?> bank = map(item, "bank");
                if (text(bank.get("id"), "bank id").equals(document.bank().id()))
                    for (Object sender : list(bank.get("senders"), "senders")) known.add(text(sender, "sender"));
            }
            equal(known.isEmpty(), false);
            for (Rules.Template template : document.templates()) {
                equal(template.bankId(), document.bank().id());
                equal(known.containsAll(template.senders()), true);
            }
        }
        equal(all.size() <= Rules.MAX_TEMPLATES, true);
        new Parser(all); // And the whole shipped set compiles into one snapshot.
        return documents;
    }

    /** A movement-only bank: no shipped CartaBCC output declares a balance. */
    private static void movementOnly() {
        PackDocument document = PackDocument.decode(read(appRoot().resolve("it-community/it.cartabcc/pack.json")));
        equal(document.bank().country(), "IT");
        equal(document.bank().name(), "CartaBCC");
        equal(document.bank().provenance(), PackDocument.Bank.Provenance.COMMUNITY);
        equal(document.templates().size(), 2);
        int outputs = 0;
        for (Rules.Template template : document.templates()) for (Rules.Output output : template.outputs()) {
            outputs++;
            equal(output.kind(), Rules.Kind.POSTED_MOVEMENT);
            equal(output.account(), null);
            equal(output.accountOptional(), true);
            equal(output.direction().fixed(), Rules.Direction.DEBIT);
        }
        equal(outputs, 2);
    }

    /** The two messages exactly as the reporting user attached them, and what the engine
     * must make of them: 66,80 EUR is 6680 minor units and 7,99 EUR is 799, both debits. */
    private static void reportedMessages(PackDocument document, Map<String, Object> fixtures) {
        equal(document.id(), COMMUNITY);
        Parser parser = new Parser(document.templates());
        equal(fixtures.get("schema"), "prototype-fixtures-1");
        equal(fixtures.get("pack"), COMMUNITY);
        int seen = 0;
        for (Object item : list(fixtures.get("cases"), "cases")) {
            Map<?, ?> fixture = map(item, "case");
            String id = text(fixture.get("id"), "case id");
            equal(CLAIMS.containsKey(id), true);
            Claim claim = CLAIMS.get(id);
            Parser.Result result = parser.parse(new Parser.Message(text(fixture.get("sourceId"), "sourceId"),
                    text(fixture.get("sender"), "sender"), text(fixture.get("body"), "body"),
                    Instant.parse(text(fixture.get("arrival"), "arrival")),
                    ZoneId.of(text(fixture.get("zone"), "zone"))));
            equal(result.status(), Parser.Status.PARSED);
            equal(result.facts().size(), 1);
            Parser.Fact fact = result.facts().get(0);
            equal(fact.provenance().packId(), COMMUNITY);
            equal(fact.provenance().templateId(), claim.templateId());
            equal(fact.provenance().outputId(), claim.outputId());
            equal(fact.provenance().engine(), Parser.ENGINE);
            equal(fact.bankId(), claim.bank());
            equal(fact.kind(), Rules.Kind.POSTED_MOVEMENT);
            equal(fact.money().currency().name(), claim.currency());
            equal(fact.money().scale(), claim.scale());
            equal(fact.money().minorUnits(), claim.minorUnits());
            // A three-digit mask is not an identity: the pack declares no account at all.
            equal(fact.account(), null);
            equal(fact.accountState(), Parser.AccountState.UNRESOLVED);
            equal(fact.time().instant(), Instant.parse(claim.instant()));
            equal(fact.time().precision(), claim.precision());
            equal(fact.time().zone().getId(), claim.zone());
            equal(fact.time().fallback(), claim.fallback());
            System.out.println("  " + id + ": " + claim.bank() + " " + claim.currency() + " "
                    + fact.money().minorUnits() + " minor units (scale " + fact.money().scale() + ", "
                    + (fact.money().minorUnits() < 0 ? "DEBIT" : "CREDIT") + "), " + fact.kind()
                    + " at " + fact.time().instant() + " (" + fact.time().precision() + ", "
                    + fact.time().zone().getId() + ", fallback=" + fact.time().fallback() + ")");
            seen++;
        }
        equal(seen, CLAIMS.size());
        notParsed(parser, "credit-shaped", creditShaped(fixtures));
        notParsed(parser, "guardless",
                reportedBody(fixtures, "spend").replace("Hai richiesto una spesa di ", ""));
    }

    /** A message that is not one of the declared guards yields no movement at all -- not a
     * zero one. Both bodies below are synthetic markers over the reported text: the words that
     * state who spent are the guard, and they are exactly what a credit line would not carry. */
    private static void notParsed(Parser parser, String label, String body) {
        Parser.Result result = parser.parse(new Parser.Message("synthetic-" + label, "CartaBCC", body,
                Instant.parse("2026-09-27T20:35:00Z"), ZoneId.of("Europe/Rome")));
        equal(result.status(), Parser.Status.NO_MATCH);
        equal(result.facts().isEmpty(), true);
        equal(result.matchedProvenance().isEmpty(), true);
    }

    private static String creditShaped(Map<String, Object> fixtures) {
        return reportedBody(fixtures, "payment").replace("Confermiamo il tuo pagamento di ",
                "Confermiamo il tuo accredito di ");
    }

    private static String reportedBody(Map<String, Object> fixtures, String id) {
        for (Object item : list(fixtures.get("cases"), "cases")) {
            Map<?, ?> fixture = map(item, "case");
            if (id.equals(fixture.get("id"))) return text(fixture.get("body"), "body");
        }
        throw new AssertionError("No reported body " + id);
    }

    private static Path appRoot() {
        Path directory = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && directory != null; i++, directory = directory.getParent())
            if (Files.isDirectory(directory.resolve(APP))) return directory.resolve(APP);
        throw new AssertionError("No " + APP + " above " + Path.of("").toAbsolutePath());
    }

    private static List<Path> shipped(String name) {
        try (Stream<Path> tree = Files.walk(appRoot(), 4)) {
            return tree.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().equals(name))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected))
            throw new AssertionError("Shipped pack check " + checks + ": " + actual + " != " + expected
);
    }

    // ---- reading: no library is on this module's classpath ----
    private static Map<String, Object> read(Path path) {
        return StrictJson.read(path);
    }

    private static Map<String, Object> map(Object value, String what) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        throw new IllegalArgumentException("Expected object for " + what);
    }

    private static List<?> list(Object value, String what) {
        if (value instanceof List<?> source) return source;
        throw new IllegalArgumentException("Expected array for " + what);
    }

    private static String text(Object value, String what) {
        if (value instanceof String source && !source.isEmpty()) return source;
        throw new IllegalArgumentException("Expected nonempty string for " + what);
    }

    private static long number(Object value, String what) {
        if (value instanceof Long source) return source;
        if (value instanceof BigInteger source) return source.longValueExact();
        throw new IllegalArgumentException("Expected integer for " + what);
    }
}
