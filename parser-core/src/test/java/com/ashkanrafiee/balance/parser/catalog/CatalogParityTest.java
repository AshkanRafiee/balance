package com.ashkanrafiee.balance.parser.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ashkanrafiee.balance.parser.legacy.LegacyBankRules;

/** Differential parity between the compiled sender catalog and the frozen legacy
 * Iranian contract. The catalog must resolve every legacy alias, its suffix probe
 * and hostile variants exactly as LegacyBankRules does, pin the three known
 * collisions explicitly, and produce a deterministic, order-sensitive digest. */
public final class CatalogParityTest {
    private static int checks;
    private static final SenderProfile PROFILE = SenderProfile.IR_LEGACY_S1;
    private static final String[][] LEGACY_ROWS = LegacyBankRules.rulesTestOnly();

    public static void main(String[] args) throws Exception {
        normalizeParity();
        compiledCatalogParity();
        conflictsAndAllowlist();
        digestDeterminism();
        suffixOverlaps();
        ids();
        System.out.println("CatalogParityTest: OK (" + checks + " checks)");
    }

    private static List<CompiledCatalog.BankEntry> entries() {
        // Legacy declares some banks across RULES and OFFICIAL_EXTRA_RULES rows;
        // merge them, preserving the global flattened alias order that defines
        // the first-wins resolution contract.
        Map<String, List<String>> merged = new java.util.LinkedHashMap<>();
        for (String[] row : LEGACY_ROWS)
            merged.computeIfAbsent(row[0], k -> new ArrayList<>()).addAll(Arrays.asList(row[1].split("\\|")));
        List<CompiledCatalog.BankEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> bank : merged.entrySet())
            entries.add(new CompiledCatalog.BankEntry(id(bank.getKey()), bank.getKey(), "jalali",
                    bank.getValue()));
        return entries;
    }

    private static CompiledCatalog catalog() {
        return CompiledCatalog.compile(PROFILE, entries(), CollisionAllowlist.IR_LEGACY_S1);
    }

    private static String id(String displayName) {
        return displayName == null ? null : CatalogIds.bankId("ir", displayName);
    }

    private static void normalizeParity() {
        for (String[] row : LEGACY_ROWS)
            for (String alias : row[1].split("\\|"))
                eq(LegacyBankRules.normalize(alias), PROFILE.normalize(alias));
        eq("98500019000", PROFILE.normalize("00989898500019000"));
        eq("", PROFILE.normalize(""));
        eq("", PROFILE.normalize("*"));
    }

    private static void compiledCatalogParity() {
        CompiledCatalog catalog = catalog();
        // Every legacy alias, its prefix extension, and its 5-digit trailing probe
        // must resolve to the identical bank (or stay unresolved).
        for (String[] row : LEGACY_ROWS) {
            for (String raw : row[1].split("\\|")) {
                parity(catalog, raw);
                parity(catalog, "777" + raw);
                String normalized = LegacyBankRules.normalize(raw);
                if (normalized.length() >= 5)
                    parity(catalog, normalized.substring(normalized.length() - 5));
            }
        }
        String[][] senders = {
            {"+9830005816", "Tosee Taavon"}, {"30005816", "Tosee Taavon"},
            {"20004860", "Middle East"}, {"98700717", "Melli"},
            {"Bankino", "Bankino"}, {"Bank-Mellat", "Mellat"},
            {"+۹۸۵۰۰۰۹۷۳۱۸۹", "Tejarat"}, {"۹۰۰۰۴۸۰۰", "Eghtesad Novin"},
            {"0098500019000", "Pasargad"}, {"٣٠٠٠٩٤١٩", "Saderat"},
            {"*30009419", null}, {"30009419#", null}, {"unknown", null}, {"", null},
            {null, null}, {"Mellat extra", null}, {"9419", null},
            {"09419", "Saderat"}, {"77730009419", "Saderat"}
        };
        for (String[] row : senders) eq(id(row[1]), catalog.resolve(row[0]));
        eq(43, catalog.banks().size());
        eq(42, catalog.reachableBanks().size());
        eq(LegacyBankRules.supportedNames().size(), catalog.banks().size());
        for (String name : LegacyBankRules.reachableBanks())
            eq(true, catalog.reachableBanks().contains(id(name)));
        // SenderList cardinality stays frozen.
        eq(345, LegacyBankRules.aliasList().size());
    }

    private static void parity(CompiledCatalog catalog, String sender) {
        eq(id(LegacyBankRules.resolve(sender)), catalog.resolve(sender));
    }

    private static void conflictsAndAllowlist() {
        List<CompiledCatalog.BankEntry> entries = entries();
        // Without a pin the three exact collisions must refuse to compile.
        try {
            CompiledCatalog.compile(PROFILE, entries);
            throw new AssertionError("unpinned collisions compiled");
        } catch (IllegalArgumentException expected) {
            // Thrown and listed.
        }
        CompiledCatalog catalog = catalog();
        eq(3, catalog.collisions().size());
        eq("20004860", catalog.collisions().get(0).key());
        eq("ir.middle-east", catalog.collisions().get(0).winnerBank());
        eq("30005816", catalog.collisions().get(1).key());
        eq("ir.tosee-taavon", catalog.collisions().get(1).winnerBank());
        eq("98700717", catalog.collisions().get(2).key());
        eq("ir.melli", catalog.collisions().get(2).winnerBank());
        // A stale pin (no such key) is refused.
        expectFailure(() -> CompiledCatalog.compile(PROFILE, entries,
                CollisionAllowlist.of(Map.of("99999999", "ir.melli"))), "stale");
        // A pin that names a lone owner without a second bank is refused.
        expectFailure(() -> CompiledCatalog.compile(PROFILE, entries,
                CollisionAllowlist.of(Map.of("bpasargad", "ir.pasargad"))), "phantom");
        // A pin that rewrites the declared legacy winner is refused.
        expectFailure(() -> CompiledCatalog.compile(PROFILE, entries,
                CollisionAllowlist.of(Map.of("20004860", "ir.bankino"))), "winner");
        // A genuinely new collision without a pin is refused and named.
        List<CompiledCatalog.BankEntry> extended = new ArrayList<>(entries);
        extended.add(new CompiledCatalog.BankEntry("ir.dummy", "Dummy Bank", "jalali",
                List.of("90004800", "999999")));
        expectFailure(() -> CompiledCatalog.compile(PROFILE, extended,
                CollisionAllowlist.IR_LEGACY_S1), "missing pins");
    }

    private static void expectFailure(Runnable runnable, String fragment) {
        String message = null;
        try {
            runnable.run();
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        eq(false, message == null || !message.contains(fragment));
    }

    private static void digestDeterminism() {
        CompiledCatalog first = catalog();
        CompiledCatalog second = catalog();
        eq(first.digest(), second.digest());
        // Declaring the same banks in a different order changes resolution order
        // and therefore the effective digest.
        List<CompiledCatalog.BankEntry> reordered = new ArrayList<>(entries());
        reordered.add(0, reordered.remove(reordered.size() - 1));
        CompiledCatalog moved = CompiledCatalog.compile(PROFILE, reordered,
                CollisionAllowlist.IR_LEGACY_S1);
        eq(false, first.digest().equals(moved.digest()));
        // Perturbing a single alias changes the digest.
        List<CompiledCatalog.BankEntry> perturbed = new ArrayList<>(entries());
        CompiledCatalog.BankEntry target = perturbed.get(0);
        List<String> expanded = new ArrayList<>(target.aliases());
        expanded.add("999000999");
        perturbed.set(0, new CompiledCatalog.BankEntry(target.bankId(), target.displayName(),
                target.calendar(), expanded));
        CompiledCatalog changed = CompiledCatalog.compile(PROFILE, perturbed,
                CollisionAllowlist.IR_LEGACY_S1);
        eq(false, first.digest().equals(changed.digest()));
        eq(64, first.digest().length());
        eq(true, first.digest().matches("[0-9a-f]{64}"));
    }

    private static void suffixOverlaps() {
        // A longer alias declared in an earlier row can suffix-match a later
        // exact alias; the earlier declaration wins and the cross-bank overlap is
        // reported, exactly as the legacy first-wins contract resolves.
        List<CompiledCatalog.BankEntry> bFirst = List.of(
            new CompiledCatalog.BankEntry("ir.b", "B Bank", "jalali", List.of("98123456")),
            new CompiledCatalog.BankEntry("ir.a", "A Bank", "jalali", List.of("123456")));
        CompiledCatalog catalog = CompiledCatalog.compile(PROFILE, bFirst);
        eq(1, catalog.suffixOverlaps().size());
        eq("123456", catalog.suffixOverlaps().get(0).key());
        eq("ir.a", catalog.suffixOverlaps().get(0).exactBank());
        eq("ir.b", catalog.suffixOverlaps().get(0).suffixBank());
        eq(true, catalog.collisions().isEmpty());
        eq("ir.b", catalog.resolve("123456"));
        eq("ir.b", catalog.resolve("98123456"));
        // Declared the other way around the exact alias wins and no overlap is
        // reported: the same alias data in a different order resolves differently.
        List<CompiledCatalog.BankEntry> aFirst = List.of(
            new CompiledCatalog.BankEntry("ir.a", "A Bank", "jalali", List.of("123456")),
            new CompiledCatalog.BankEntry("ir.b", "B Bank", "jalali", List.of("98123456")));
        CompiledCatalog exactFirst = CompiledCatalog.compile(PROFILE, aFirst);
        eq(true, exactFirst.suffixOverlaps().isEmpty());
        // The earlier-declared exact alias suffix-matches every longer form,
        // exactly as the legacy first-wins contract resolves.
        eq("ir.a", exactFirst.resolve("123456"));
        eq("ir.a", exactFirst.resolve("98123456"));
        eq("ir.a", exactFirst.resolve("1000123456"));
    }

    private static void ids() {
        eq("ir.middle-east", CatalogIds.bankId("ir", "Middle East"));
        eq("ir.tosee-taavon", CatalogIds.bankId("ir", "Tosee Taavon"));
        eq("ir.sanat-madan", CatalogIds.bankId("ir", "Sanat Madan"));
        Set<String> ids = new HashSet<>();
        for (String[] row : LEGACY_ROWS) ids.add(id(row[0]));
        eq(LegacyBankRules.supportedNames().size(), ids.size());
        for (String bankId : ids) eq(true, bankId.startsWith("ir."));
        // Display identities are retained and round-trip by id.
        CompiledCatalog catalog = catalog();
        for (String[] row : LEGACY_ROWS) eq(row[0], catalog.displayName(id(row[0])));
        for (String[] row : LEGACY_ROWS) eq("jalali", catalog.calendar(id(row[0])));
        eq(false, catalog.declarationEntries().isEmpty());
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("check " + checks + ": expected " + expected + ", got " + actual);
    }
}