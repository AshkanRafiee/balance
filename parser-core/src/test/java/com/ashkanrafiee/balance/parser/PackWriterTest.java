package com.ashkanrafiee.balance.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Holds the encoder against the format it feeds: every pack the app ships and every example
 * round-trips through {@link PackWriter} into the same document, member for member, and the text
 * that comes out is canonical -- the same bytes whatever order the document was built in, and the
 * same bytes again when what was written is read back and written once more.
 *
 * <p>The corpus is the point. Synthetic documents prove the corners (an anchor holding a quote, a
 * Persian anchor, every optional member at once, a mapping whose keys only a sorted writer puts in
 * order), but only the real packs prove the promise that matters to a user sharing one: a pack this
 * app read is a pack this app can hand out and take back without losing anything.
 *
 * <p>Dependency-free executable main like its sibling suites -- parser-core has no test framework,
 * and the reader is the same strict one the shipped-pack gate uses so the round trip cannot be
 * made to pass by a permissive parser.
 */
public final class PackWriterTest {
    private static int checks;

    public static void main(String[] args) {
        String root = args.length > 0 ? args[0] : ".";
        List<Path> packs = documents(root);
        equal(packs.isEmpty(), false);
        for (Path path : packs) roundTrip(path);
        canonicalForm();
        escaping();
        everyOptionalMember();
        defaultsAreOmitted();
        System.out.println("PackWriterTest: " + checks + " checks passed, " + packs.size()
                + " documents round-tripped");
    }

    /** The document the encoder wrote is the document it was given, and writing it again is the
     *  same text -- which is what makes a pack's bytes stable enough to diff and to digest. */
    private static void roundTrip(Path path) {
        PackDocument document = PackDocument.decode(StrictJson.read(path));
        String written = PackWriter.write(document);
        JsonLexicalGuard.validate(written);
        PackDocument again = PackDocument.decode(StrictJson.object(StrictJson.parse(written), path.toString()));
        equal(again, document, "round trip " + path.getFileName());
        equal(PackWriter.write(again), written, "canonical bytes " + path.getFileName());
        // The written text is the whole document: no member the schema requires is missing, and
        // the first and last bytes are the braces a reader needs to accept it.
        equal(written.startsWith("{\n"), true);
        equal(written.endsWith("}\n"), true);
        equal(StrictJson.parse(written) instanceof Map<?, ?>, true);
    }

    /** Two documents that mean the same thing produce one text, whichever order they were built
     *  in: the sets and maps are sorted, so no hash order can reach the bytes. */
    private static void canonicalForm() {
        PackDocument straight = PackDocument.decode(SyntheticPack.pack(List.of("+98AAA", "+98BBB"),
            List.of("YMD", "DMY"), SyntheticPack.currencyMapping(), Map.of()));
        PackDocument shuffled = PackDocument.decode(SyntheticPack.pack(List.of("+98BBB", "+98AAA"),
            List.of("DMY", "YMD"), SyntheticPack.reversedCurrencyMapping(), Map.of()));
        String first = PackWriter.write(straight);
        equal(PackWriter.write(shuffled), first, "canonical order");
        // Senders and mapping keys come out in alphabetical order, and date orders in the order
        // the grammar itself declares, so neither a hash order nor an insertion order reaches the
        // bytes.
        equal(first.indexOf("\"+98AAA\"") < first.indexOf("\"+98BBB\""), true, "sorted senders");
        equal(first.indexOf("\"YMD\"") < first.indexOf("\"DMY\""), true, "grammar order");
        // Looked at inside the mapping member alone, since the fixed currency is spelled IRR
        // earlier in the pack and would otherwise answer for it.
        int mapping = first.indexOf("\"mapping\":");
        equal(first.indexOf("\"IRR\"", mapping) < first.indexOf("\"USD\"", mapping), true,
            "sorted mapping");
        // And the text is stable across a write of a decoded copy, not just of the same object.
        equal(PackWriter.write(PackDocument.decode(StrictJson.object(StrictJson.parse(first), "written"))),
            first, "stable across re-decode");
    }

    /** What a person edits by hand has to survive the trip unchanged: a quote, a backslash, a
     *  control character and a Persian anchor all come back exactly as they went in. */
    private static void escaping() {
        String awkward = "quote\" backslash\\ tab\t newline\n bell\u0007";
        PackDocument persian = PackDocument.decode(SyntheticPack.pack(List.of("+98AAA"), List.of("YMD"),
            Map.of(), Map.of("موجودی", "balance")));
        String written = PackWriter.write(persian);
        equal(written.contains("موجودی"), true, "persian stays readable");
        equal(PackDocument.decode(StrictJson.object(StrictJson.parse(written), "written")), persian,
            "persian round trip");
        // The awkward text is a token the rule looks for, so it is written where a person editing
        // the file would look for it: as the mapping key, with the escapes the reader takes back.
        PackDocument escaped = PackDocument.decode(SyntheticPack.pack(List.of("+98AAA"), List.of("YMD"),
            Map.of(), Map.of(awkward, "balance")));
        String escapedText = PackWriter.write(escaped);
        equal(escapedText.contains("\"quote\\\" backslash\\\\ tab\\t newline\\n bell\\u0007\""),
            true, "every escape written");
        equal(PackDocument.decode(StrictJson.object(StrictJson.parse(escapedText), "written")), escaped,
            "escaped round trip");
    }

    /** Every optional member the schema can carry survives a round trip, so a pack that uses the
     *  full grammar -- numeric shapes, optional accounts, reason and channel maps, an explicit date
     *  -- is written completely rather than quietly trimmed to the defaults. */
    private static void everyOptionalMember() {
        Map<String, Object> source = SyntheticPack.full();
        PackDocument document = PackDocument.decode(source);
        String written = PackWriter.write(document);
        for (String member : List.of("\"account\":", "\"accountOptional\":", "\"numeric\":",
                "\"normalization\":", "\"terminated\":", "\"originalAmount\":", "\"direction\":",
                "\"options\":", "\"reason\":", "\"channel\":", "\"leadingPoint\":", "\"sign\":")) {
            equal(written.contains(member), true, "written " + member);
        }
        equal(PackDocument.decode(StrictJson.object(StrictJson.parse(written), "written")), document,
            "full grammar round trip");
    }

    /** A pack that spells out a default comes back without it: the same document, a smaller one.
     *  This is what keeps a shared pack readable rather than padded with what it already means. */
    private static void defaultsAreOmitted() {
        PackDocument spelled = PackDocument.decode(SyntheticPack.defaults(true));
        PackDocument assumed = PackDocument.decode(SyntheticPack.defaults(false));
        equal(spelled, assumed, "defaults are the same document");
        String written = PackWriter.write(assumed);
        for (String member : List.of("\"normalization\":", "\"terminated\":", "\"sign\":",
                "\"leadingPoint\":", "\"accountOptional\":")) {
            equal(written.contains(member), false, "default " + member + " omitted");
        }
        // Both spellings are one canonical text, so which one a pack arrived as cannot be told
        // from what this app hands out next.
        equal(PackWriter.write(spelled), written, "one canonical text for both");
    }

    // ---- corpus ----

    private static List<Path> documents(String root) {
        List<Path> found = new ArrayList<>();
        for (String directory : List.of("rules/app", "rules/examples")) {
            Path base = Path.of(root).resolve(directory);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> tree = Files.walk(base, 4)) {
                tree.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().equals("pack.json"))
                    .forEach(found::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        found.sort(Path::compareTo);
        return found;
    }

    // ---- assertions ----

    private static void equal(Object actual, Object expected) {
        equal(actual, expected, "check");
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected))
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
}