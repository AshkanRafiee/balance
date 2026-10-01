package com.ashkanrafiee.balance;

import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.Parser;
import com.ashkanrafiee.balance.parser.Rules;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers the one question a builder has to answer before it offers a rule: if I install this, does
 * it read my message, and what does it read?
 *
 * <p>The draft is tested against a composition that includes everything the engine would use once
 * the rule is installed — the catalog this device ships and the local packs it already holds — and
 * not against a parser holding only the new rule. A rule that passes alone can still lose to
 * another rule for the same sender, and telling a reader "this works" and then having their rule
 * ignored by an existing one is the failure this exists to catch before they install anything.
 *
 * <p>Testing installs nothing. It builds a parser in memory, parses the reader's own example, and
 * reports the engine's own status, facts and diagnostics. Nothing here reaches the catalog, the
 * local store, or the ledger.
 *
 * <p>A sender already covered by an installed rule is reported rather than silently shadowed: the
 * reader is told which packs already claim it, and asked to confirm before continuing, because
 * installing a second rule for one sender is legitimate — a bank with several message layouts needs
 * more than one — but should never be something the reader did.
 */
final class RuleDraftTester {

    private RuleDraftTester() {}

    /** What testing found. Carries only identities and codes: no amount, account or fragment of the
     *  reader's message, so a verdict can be logged, saved or shown without leaking the message. */
    static final class Verdict {
        final Parser.Status status;
        final boolean parsed;
        final boolean covered;
        /** Catalog or local packs that already claim this sender, by pack id, catalog order. */
        final List<String> claims;
        /** Bank ids the reading came from, in the order the engine produced them. */
        final List<String> banks;
        /** Distinct {@code field → code} pairs, engine order, each named once. */
        final List<String> issues;
        /** The reader's own movements, in minor units of the currency they are in. */
        final List<Long> amounts;
        /** Why the verdict is what it is, for a screen that has to say it in a sentence. */
        final String reason;

        Verdict(Parser.Status status, boolean parsed, boolean covered, List<String> claims,
                List<String> banks, List<String> issues, List<Long> amounts, String reason) {
            this.status = status;
            this.parsed = parsed;
            this.covered = covered;
            this.claims = List.copyOf(claims);
            this.banks = List.copyOf(banks);
            this.issues = List.copyOf(issues);
            this.amounts = List.copyOf(amounts);
            this.reason = reason;
        }

        /** Whether the reader should be told, before installing, that this sender is not new. */
        boolean needsConfirming() {
            return !claims.isEmpty();
        }
    }

    /**
     * Runs the draft against the engine as it would be composed after an install.
     *
     * @param draft a buildable draft
     * @param engine the engine currently loaded on this device, or null when it did not load
     * @param example the reader's own message, which is the only thing that may be parsed
     * @param arrival when the message arrived, which the date rule's window is measured against
     */
    static Verdict test(RuleDraft draft, EngineRules engine, String sender, String example,
            long arrival) {
        PackDocument document;
        try {
            document = PackDocument.decode(draft.document());
        } catch (RuntimeException rejected) {
            // The draft claimed to be buildable and its own document did not validate. That is a
            // builder bug, not the reader's message, and it is reported as such.
            return new Verdict(Parser.Status.INVALID, false, false, List.of(), List.of(),
                    List.of("document → INVALID_FIELD"), List.of(), "document");
        }
        List<Rules.Template> templates = new ArrayList<>(document.templates());
        List<String> claims = new ArrayList<>();
        if (engine != null) {
            for (EngineRules.Bank bank : engine.allBanksInOrder()) {
                templates.addAll(bank.templates);
                for (Rules.Template template : bank.templates) {
                    for (String alias : template.senders()) {
                        if (alias.equals(sender) || BankRules.normalize(alias)
                                .equals(BankRules.normalize(sender))) claims.add(bank.id);
                    }
                }
            }
            claims = distinct(claims);
        }
        Parser parser;
        try {
            parser = new Parser(templates);
        } catch (RuntimeException tooMany) {
            return new Verdict(Parser.Status.LIMIT_EXCEEDED, false, !claims.isEmpty(), claims,
                    List.of(), List.of("templates → CANDIDATE_LIMIT"), List.of(), "limit");
        }
        Parser.Result result;
        try {
            result = parser.parse(new Parser.Message("builder", sender, example,
                    Instant.ofEpochMilli(arrival), ZoneId.systemDefault()));
        } catch (RuntimeException unavailable) {
            return new Verdict(Parser.Status.INVALID, false, !claims.isEmpty(), claims, List.of(),
                    List.of(), List.of(), "engine");
        }
        List<String> banks = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (Parser.Fact fact : result.facts()) {
            banks.add(fact.bankId());
            if (fact.kind() == Rules.Kind.POSTED_MOVEMENT && fact.money() != null) {
                amounts.add(fact.money().minorUnits());
            }
        }
        List<String> issues = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (Parser.Diagnostic diagnostic : result.diagnostics()) {
            named.add("`" + diagnostic.field() + "` → " + diagnostic.code());
        }
        issues.addAll(named);
        boolean parsed = result.status() == Parser.Status.PARSED;
        return new Verdict(result.status(), parsed, !claims.isEmpty(), claims,
                distinct(banks), issues, amounts, reason(result));
    }

    /** The engine's verdict in one sentence's worth of words, reusing the engine's own meanings so
     *  the builder and a shared report cannot describe the same failure differently. */
    private static String reason(Parser.Result result) {
        return switch (result.status()) {
            case PARSED -> "parsed";
            case UNKNOWN_SENDER -> "unknown_sender";
            case NO_MATCH -> "no_match";
            case ABSENT -> "absent";
            case INVALID -> "invalid";
            case AMBIGUOUS -> "ambiguous";
            case OVERFLOW -> "overflow";
            case LIMIT_EXCEEDED -> "limit_exceeded";
        };
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    /** The claims list grouped for a screen that shows which packs already read this sender, kept
     *  here so the activity does not walk the engine's templates itself. */
    static Map<String, Integer> claimCounts(Verdict verdict) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String claim : verdict.claims) counts.merge(claim, 1, Integer::sum);
        return counts;
    }
}