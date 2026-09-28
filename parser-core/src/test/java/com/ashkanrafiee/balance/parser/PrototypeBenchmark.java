package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.Parser.*;
import static com.ashkanrafiee.balance.parser.Rules.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Observational microbenchmark: synthetic inputs, no external libraries or timing assertions. */
public final class PrototypeBenchmark {
    private static final int SAMPLES = 5;
    private static volatile long sink;

    public static void main(String[] args) {
        Field account = new Field(-1, "Account=", ";", MAX_FIELD);
        Field amount = new Field(-1, "Amount=", ";", MAX_FIELD);
        Output usd = new Output("usd", Field.line(1), account, Kind.BOOKED_BALANCE,
                money(amount, Currency.USD), null, null, null);
        Output eur = new Output("eur", Field.line(2), account, Kind.BOOKED_BALANCE,
                money(amount, Currency.EUR), null, null, null);
        List<Output> outputs = List.of(usd, eur);
        List<Template> rules = new ArrayList<>();
        for (int i = 0; i < 32; i++) rules.add(new Template("synthetic.benchmark", "r1", "synthetic.bank", "t" + i,
                Set.of("SYNTHETIC"), List.of(new Guard(0, "Statement", false)), outputs));
        rules = List.copyOf(rules);
        System.out.println("Synthetic benchmark; fresh snapshot compilation excludes typed-rule construction and JVM startup.");
        System.out.println("Five samples; warm parse includes result/checksum consumption; no timing thresholds.");
        for (int sample = 1; sample <= SAMPLES; sample++) {
            int iterations = 200;
            long checksum = 0;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                Parser fresh = new Parser(rules);
                checksum += fresh.senderOverlaps().get(0).templateKeys().size();
            }
            report("cold-compile-32-templates", sample, iterations, start, checksum);
        }

        Parser shortParser = new Parser(List.of(rules.get(0)));
        Message[] shortInputs = {
            message("Statement\nAccount=001;Amount=100.00;\nAccount=001;Amount=200.00;"),
            message("Statement\nAccount=001;Amount=-3.25;\nAccount=002;Amount=12.34;")
        };
        verify(shortParser, shortInputs, Status.PARSED, 2);
        sink += runParses(shortParser, shortInputs, 3_000);
        sampleParses("warm-short-2-outputs", shortParser, shortInputs, 10_000);

        // Near-matching long literal spends the shared work budget on bounded maximum-size input.
        Template costly = new Template("synthetic.benchmark", "r1", "synthetic.bank", "costly", Set.of("SYNTHETIC"),
                List.of(new Guard(-1, "x".repeat(MAX_LITERAL - 1) + "z", false)), outputs);
        Parser boundedParser = new Parser(List.of(costly));
        Message[] boundedInputs = {message("x".repeat(MAX_INPUT))};
        verify(boundedParser, boundedInputs, Status.LIMIT_EXCEEDED, 0);
        if (boundedParser.parse(boundedInputs[0]).diagnostics().get(0).code() != Code.WORK_LIMIT)
            throw new AssertionError("Worst-case fixture must exhaust matcher work");
        sink += runParses(boundedParser, boundedInputs, 100);
        sampleParses("warm-bounded-work-limit", boundedParser, boundedInputs, 200);
        System.out.println("checksum=" + sink);
    }

    private static void sampleParses(String label, Parser parser, Message[] inputs, int iterations) {
        System.out.printf(Locale.ROOT, "%s input-code-units=%d iterations/sample=%d%n", label,
                inputs[0].body().length(), iterations);
        for (int sample = 1; sample <= SAMPLES; sample++) {
            long start = System.nanoTime();
            long checksum = runParses(parser, inputs, iterations);
            report(label, sample, iterations, start, checksum);
        }
    }
    private static long runParses(Parser parser, Message[] inputs, int iterations) {
        long checksum = 0;
        for (int i = 0; i < iterations; i++) {
            Result result = parser.parse(inputs[i % inputs.length]);
            checksum += result.status().ordinal() + result.matchedProvenance().size();
            for (Fact fact : result.facts()) checksum += fact.money().minorUnits() + fact.money().scale();
            for (Diagnostic diagnostic : result.diagnostics()) checksum += diagnostic.code().ordinal() + 1;
        }
        return checksum;
    }
    private static void report(String label, int sample, int iterations, long start, long checksum) {
        long elapsed = System.nanoTime() - start;
        sink += checksum;
        System.out.printf(Locale.ROOT,
                "%s sample=%d elapsed-ms=%.3f ns/op=%.1f ops/s=%.1f checksum=%d%n",
                label, sample, elapsed / 1_000_000.0, (double) elapsed / iterations,
                iterations * 1_000_000_000.0 / elapsed, checksum);
    }
    private static void verify(Parser parser, Message[] inputs, Status expected, int facts) {
        for (Message input : inputs) {
            Result result = parser.parse(input);
            if (result.status() != expected || result.facts().size() != facts
                    || result.matchedProvenance().size() != facts)
                throw new AssertionError("Benchmark fixture expectation failed");
        }
    }
    private static MoneyRule money(Field amount, Currency currency) {
        return new MoneyRule(amount, CurrencyRule.fixed(currency), '.', ',', Grouping.WESTERN, Digits.ASCII, 1);
    }
    private static Message message(String body) {
        return new Message("benchmark-source", "SYNTHETIC", body, Instant.parse("2026-04-04T12:00:00Z"), ZoneOffset.UTC);
    }
}
