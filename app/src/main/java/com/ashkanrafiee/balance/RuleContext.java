package com.ashkanrafiee.balance;

import com.ashkanrafiee.balance.parser.Parser;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rule context a report carries about one message: which rules were considered, which one
 * matched, and which fields failed — named by identity, never by content.
 *
 * <p>A maintainer reading "Balance could not parse this" has to work out which of the forty-odd
 * rules was even tried, and whether it was tried and failed or never matched at all. Those are very
 * different problems with very different fixes, and the difference is invisible from the outside.
 * This block answers it from the engine itself, so the report cannot claim a rule that was not
 * consulted.
 *
 * <p>Everything here is an identity or a code: pack, revision, template, output, field, status.
 * No amount, account, reference or fragment of the message body is included, which is what lets the
 * block be attached to a report automatically instead of being something the reader has to opt into
 * and check. The message text itself is in the report only because the reader put it there.
 */
final class RuleContext {

    private RuleContext() {}

    /** The one-line header naming the engine and what it is made of, or an empty string when the
     *  engine could not be loaded at all — in which case there is nothing truthful to say about it. */
    static String header(EngineRules engine) {
        if (engine == null) return "";
        return "Engine `" + Parser.ENGINE + "` · " + engine.banksInOrder().size()
                + " bundled packs · " + engine.localBanks().size() + " local";
    }

    /** What the engine made of one message: the outcome, the rules that matched, and the fields
     *  that failed. An empty string when the engine has no opinion, so a report from a device whose
     *  engine did not load carries no invented context. */
    static String forMessage(EngineRules engine, String sender, String body, long arrival) {
        if (engine == null || sender == null || body == null) return "";
        Parser.Result result;
        try {
            result = engine.parse("report", sender, body, Instant.ofEpochMilli(arrival),
                    ZoneId.systemDefault());
        } catch (RuntimeException unavailable) {
            return "";
        }
        // A sender no pack covers has no engine opinion at all: that is what the legacy path is for,
        // and a report claiming a rule was consulted here would be inventing one.
        if (result == null) return "";
        List<String> lines = new ArrayList<>();
        lines.add("Engine read: " + outcome(result.status()));
        // Identity before facts: a rule that matched and produced nothing is still the rule to fix.
        Set<String> matched = new LinkedHashSet<>();
        for (Parser.Provenance provenance : result.matchedProvenance())
            matched.add(provenance.packId() + " r" + provenance.revision() + " · "
                    + provenance.templateId() + "." + provenance.outputId());
        for (Parser.Fact fact : result.facts())
            matched.add(fact.provenance().packId() + " r" + fact.provenance().revision() + " · "
                    + fact.provenance().templateId() + "." + fact.provenance().outputId());
        if (matched.isEmpty()) lines.add("No rule matched this message.");
        else for (String rule : matched) lines.add("Matched: `" + rule + "`");
        for (String field : fields(result)) lines.add("Field issue: " + field);
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append("\n");
        return out.toString();
    }

    /** The distinct field/code pairs, in the order the engine reported them, each named once. */
    private static List<String> fields(Parser.Result result) {
        Set<String> fields = new LinkedHashSet<>();
        for (Parser.Diagnostic diagnostic : result.diagnostics())
            fields.add("`" + diagnostic.field() + "` → " + diagnostic.code());
        return new ArrayList<>(fields);
    }

    /** The engine's own verdict, in the words a reporter can act on. A message the engine rejected
     *  as malformed is a different bug from one no rule claimed, and the report says which. */
    private static String outcome(Parser.Status status) {
        switch (status) {
            case PARSED: return "parsed in full";
            case UNKNOWN_SENDER: return "the sender is not known to any rule";
            case NO_MATCH: return "a rule claims this sender, but no rule matched this message";
            case ABSENT: return "nothing to read: the message is empty or only whitespace";
            case INVALID: return "rejected as malformed input";
            case AMBIGUOUS: return "rejected as ambiguous: more than one reading fits";
            case OVERFLOW: return "rejected as too large to read safely";
            case LIMIT_EXCEEDED: return "rejected as beyond the reader's limits";
            default: return status.name();
        }
    }
}