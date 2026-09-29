package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.Parser.*;
import static com.ashkanrafiee.balance.parser.Rules.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic fixtures only. Run directly; assertions are always enabled by this harness. */
public final class PrototypeTest {
    private static int checks;
    private static final Instant ARRIVAL = Instant.parse("2026-04-04T12:00:00Z");
    private static final Field WHOLE = new Field(-1, "", "", MAX_INPUT);
    private static final Field ACCOUNT = field("Account=", ";");
    private static final Field AMOUNT = field("Amount=", ";");
    private static final Field TOKEN = field("Currency=", ";");

    public static void main(String[] args) {
        multiCurrency();
        purchaseContext();
        independentOutputs();
        exactMoney();
        trailingSigns();
        directions();
        conflicts();
        multisetConflicts();
        bounds();
        lateWorkLimits();
        dates();
        validation();
        System.out.println("PrototypeTest: " + checks + " checks passed");
    }

    private static void multiCurrency() {
        Output usd = output("usd", Field.line(1), Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.USD), null);
        Output eur = output("eur", Field.line(2), Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.EUR), null);
        Parser parser = parser(usd, eur);
        Result result = parse(parser, "Statement\nAccount=0001;Amount=100.00;\nAccount=0001;Amount=200.00;");
        status(result, Status.PARSED);
        equal(result.facts().size(), 2, "two currency balances");
        equal(fact(result, "usd").money(), new Money(Currency.USD, 10000, 2), "USD ledger");
        equal(fact(result, "eur").money(), new Money(Currency.EUR, 20000, 2), "EUR ledger");
        equal(fact(result, "usd").account(), "0001", "leading zeros");
        equal(fact(result, "usd").provenance().sourceId(), "fixture-1", "source identity");
        rejection(parse(parser, "Statement\nAccount=0001;Amount=100.00;\nAccount=0001;Amount=bad;"), Status.INVALID, Code.INVALID_MONEY);
        rejection(parse(parser, "Statement\nAccount=0001;Amount=100.00;"), Status.ABSENT, Code.REQUIRED_ABSENT);
        CurrencyRule mapped = new CurrencyRule(null, TOKEN, Map.of("USD", Currency.USD, "EUR", Currency.EUR));
        Parser dynamic = parser(output("balance", WHOLE, Kind.BOOKED_BALANCE, mapped, null));
        equal(parse(dynamic, row("100.00", "USD")).facts().get(0).money().currency(), Currency.USD, "finite USD");
        equal(parse(dynamic, row("200.00", "EUR")).facts().get(0).money().currency(), Currency.EUR, "finite EUR");
        rejection(parse(dynamic, row("100.00", "$")), Status.INVALID, Code.UNKNOWN_CURRENCY);
        CurrencyRule checked = new CurrencyRule(Currency.USD, TOKEN, Map.of("EUR", Currency.EUR));
        rejection(parse(parser(output("balance", WHOLE, Kind.BOOKED_BALANCE, checked, null)), row("100", "EUR")),
                Status.INVALID, Code.CURRENCY_CONFLICT);
    }

    private static void purchaseContext() {
        MoneyRule ledger = money(field("Debited=", ";"), Currency.USD);
        MoneyRule original = money(field("Original=", ";"), Currency.EUR);
        Output movement = new Output("debit", WHOLE, ACCOUNT, Kind.POSTED_MOVEMENT, ledger,
                DirectionRule.fixed(Direction.DEBIT), original, null);
        Output balance = new Output("balance", WHOLE, ACCOUNT, Kind.BOOKED_BALANCE,
                money(field("Balance=", ";"), Currency.USD), null, null, null);
        String body = "Statement;Account=0001;Original=10.00;Debited=11.00;Balance=89.00;";
        Result result = parse(parser(movement, balance), body);
        status(result, Status.PARSED);
        equal(result.facts().size(), 2, "original is not third output");
        equal(fact(result, "debit").money(), new Money(Currency.USD, -1100, 2), "ledger debit");
        equal(fact(result, "debit").originalAmount(), new Money(Currency.EUR, 1000, 2), "original context");
        equal(fact(result, "balance").money().minorUnits(), 8900L, "ledger balance");
        Span span = fact(result, "debit").provenance().amountSpan();
        equal(body.substring(span.start(), span.end()), "11.00", "source span");
        rejection(parse(parser(movement, balance), body.replace("Original=10.00", "Original=bad")), Status.INVALID, Code.INVALID_MONEY);
    }

    private static void independentOutputs() {
        Output one = output("first", Field.line(1), Kind.POSTED_MOVEMENT, CurrencyRule.fixed(Currency.USD),
                DirectionRule.fixed(Direction.DEBIT));
        Output two = output("second", Field.line(2), Kind.POSTED_MOVEMENT, CurrencyRule.fixed(Currency.USD),
                DirectionRule.fixed(Direction.CREDIT));
        String body = "Statement\nAccount=001;Amount=5.00;\nAccount=002;Amount=7.00;";
        Result result = parse(parser(one, two), body);
        status(result, Status.PARSED);
        equal(fact(result, "first").money().minorUnits(), -500L, "first movement");
        equal(fact(result, "second").money().minorUnits(), 700L, "second movement");
        equal(fact(result, "second").account(), "002", "independent account");
        Output duplicate = output("duplicate", Field.line(1), Kind.POSTED_MOVEMENT,
                CurrencyRule.fixed(Currency.USD), DirectionRule.fixed(Direction.DEBIT));
        rejection(parse(parser(one, duplicate), body), Status.INVALID, Code.OVERLAPPING_MOVEMENTS);
        // Equal amounts in different spans are legitimate independent posted movements.
        status(parse(parser(one, two), body.replace("7.00", "5.00")), Status.PARSED);
        rejection(parse(parser(one, two), body.replace("Amount=7.00", "Missing=7.00")), Status.ABSENT, Code.REQUIRED_ABSENT);
        // An amount from the next line must never repair this output's missing amount.
        rejection(parse(parser(one, two), body.replace("Amount=5.00", "Missing=5.00")), Status.ABSENT, Code.REQUIRED_ABSENT);
        status(parse(parser(one), body.replace("001", "00.01-2")), Status.PARSED);
        rejection(parse(parser(one), body.replace("001", "**01")), Status.INVALID, Code.INVALID_ACCOUNT);
    }

    private static void exactMoney() {
        moneyCase(Currency.USD, "1,234.56", 123456L);
        moneyCase(Currency.USD, "-0.01", -1L);
        moneyCase(Currency.USD, "0", 0L);
        moneyCase(Currency.JPY, "123", 123L);
        moneyCase(Currency.KWD, "1.234", 1234L);
        moneyCase(Currency.IRR, "-9223372036854775808", Long.MIN_VALUE);
        moneyCase(Currency.IRR, "9223372036854775807", Long.MAX_VALUE);
        for (String invalid : List.of("1,23.45", "1.234", "1.230", "1.2.3", "1e2", "NaN", " 1", "1 ",
                "--1", "1-", "(1)", ".10", "1.", "1,,000", "1,0000", "1 USD", "١.٠٠"))
            rejection(parse(single(Currency.USD), row(invalid, "USD")), Status.INVALID, Code.INVALID_MONEY);
        rejection(parse(single(Currency.JPY), row("1.0", "JPY")), Status.INVALID, Code.INVALID_MONEY);
        rejection(parse(single(Currency.KWD), row("1.2345", "KWD")), Status.INVALID, Code.INVALID_MONEY);
        for (String overflow : List.of("9223372036854775808", "-9223372036854775809", "9".repeat(200)))
            rejection(parse(single(Currency.IRR), row(overflow, "IRR")), Status.OVERFLOW, Code.MONEY_OVERFLOW);
        MoneyRule indian = new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.USD), '.', ',',
                Grouping.INDIAN, Digits.ASCII, 1);
        equal(parse(withMoney(indian), row("12,34,567.89", "USD")).facts().get(0).money().minorUnits(),
                123456789L, "Indian grouping");
        rejection(parse(withMoney(indian), row("123,456.78", "USD")), Status.INVALID, Code.INVALID_MONEY);
        MoneyRule european = new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.EUR), ',', '.',
                Grouping.WESTERN, Digits.ASCII, 1);
        equal(parse(withMoney(european), row("1.234,56", "EUR")).facts().get(0).money().minorUnits(),
                123456L, "explicit decimal comma");
        MoneyRule toman = new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.IRR), '.', ',',
                Grouping.WESTERN, Digits.ASCII_PERSIAN_ARABIC, 10);
        equal(parse(withMoney(toman), row("۱۲۳.۴", "IRR")).facts().get(0).money().minorUnits(), 1234L, "toman exact");
        equal(parse(withMoney(toman), row("١٢٣", "IRR")).facts().get(0).money().minorUnits(), 1230L, "Arabic digits");
        rejection(parse(withMoney(toman), row("922337203685477581", "IRR")), Status.OVERFLOW, Code.MONEY_OVERFLOW);
        rejection(parse(withMoney(toman), row("1.01", "IRR")), Status.INVALID, Code.INVALID_MONEY);
    }

    private static void trailingSigns() {
        // The legacy corpus's trailing-sign shapes: مبلغ:100- → -100, مبلغ:100+ → +100,
        // \u202B100-\u202C → -100. TRAILING is an explicit sign position; LEADING stays strict.
        MoneyRule debit = new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.IRR), '.', ',',
                Grouping.WESTERN, Digits.ASCII_PERSIAN_ARABIC, 1, Sign.TRAILING);
        MoneyRule credit = new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.IRR), '.', ',',
                Grouping.WESTERN, Digits.ASCII_PERSIAN_ARABIC, 1, Sign.TRAILING);
        Parser debitParser = parser(new Output("movement", WHOLE, ACCOUNT, Kind.POSTED_MOVEMENT, debit,
                DirectionRule.fixed(Direction.DEBIT), null, null));
        Parser creditParser = parser(new Output("movement", WHOLE, ACCOUNT, Kind.POSTED_MOVEMENT, credit,
                DirectionRule.fixed(Direction.CREDIT), null, null));
        equal(parse(debitParser, row("100-", "IRR")).facts().get(0).money().minorUnits(), -100L, "trailing debit");
        equal(parse(creditParser, row("100+", "IRR")).facts().get(0).money().minorUnits(), 100L, "trailing credit");
        equal(parse(debitParser, row("100", "IRR")).facts().get(0).money().minorUnits(), -100L, "unsigned trailing");
        equal(parse(debitParser, row("100 -", "IRR")).facts().get(0).money().minorUnits(), -100L, "spaced trailing sign");
        equal(parse(debitParser, row("\u202B100-\u202C", "IRR")).facts().get(0).money().minorUnits(), -100L,
                "bidi-wrapped trailing sign");
        rejection(parse(creditParser, row("100-", "IRR")), Status.INVALID, Code.DIRECTION_CONFLICT);
        rejection(parse(single(Currency.USD), row("1-", "USD")), Status.INVALID, Code.INVALID_MONEY);
    }

    private static void directions() {
        DirectionRule debit = DirectionRule.fixed(Direction.DEBIT);
        Parser parser = parser(output("movement", WHOLE, Kind.POSTED_MOVEMENT, CurrencyRule.fixed(Currency.IRR), debit));
        equal(parse(parser, row("-9223372036854775808", "IRR")).facts().get(0).money().minorUnits(),
                Long.MIN_VALUE, "minimum long debit avoids abs overflow");
        equal(parse(parser, row("12", "IRR")).facts().get(0).money().minorUnits(), -12L, "unsigned debit");
        rejection(parse(parser, row("+12", "IRR")), Status.INVALID, Code.DIRECTION_CONFLICT);
        rejection(parse(parser, row("0", "IRR")), Status.INVALID, Code.ZERO_MOVEMENT);
        DirectionRule mapped = new DirectionRule(Direction.DEBIT, field("Direction=", ";"),
                Map.of("DR", Direction.DEBIT, "CR", Direction.CREDIT));
        Parser checked = parser(output("movement", WHOLE, Kind.POSTED_MOVEMENT, CurrencyRule.fixed(Currency.USD), mapped));
        rejection(parse(checked, row("1", "USD") + "Direction=CR;"), Status.INVALID, Code.DIRECTION_CONFLICT);
        rejection(parse(checked, row("1", "USD") + "Direction=unknown;"), Status.INVALID, Code.UNKNOWN_DIRECTION);
        status(parse(checked, row("-1", "USD") + "Direction=DR;"), Status.PARSED);
        Parser credit = parser(output("movement", WHOLE, Kind.POSTED_MOVEMENT, CurrencyRule.fixed(Currency.USD),
                DirectionRule.fixed(Direction.CREDIT)));
        rejection(parse(credit, row("-1", "USD")), Status.INVALID, Code.DIRECTION_CONFLICT);
    }

    private static void conflicts() {
        Output usd = output("balance", WHOLE, Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.USD), null);
        Output eur = output("balance", WHOLE, Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.EUR), null);
        Template a = template("a", "synthetic.bank", List.of(usd));
        Template b = template("b", "synthetic.bank", List.of(eur));
        Result ab = parse(new Parser(List.of(a, b)), row("1", "USD"));
        Result ba = parse(new Parser(List.of(b, a)), row("1", "USD"));
        rejection(ab, Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        equal(ab, ba, "template order-independent conflict");
        Template otherBank = template("c", "synthetic.other", List.of(usd));
        rejection(parse(new Parser(List.of(a, otherBank)), row("1", "USD")), Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        equal(parse(new Parser(List.of(a, otherBank)), row("1", "USD")),
                parse(new Parser(List.of(otherBank, a)), row("1", "USD")), "bank order-independent conflict");
        Template same = template("same", "synthetic.bank", List.of(usd));
        Result coalesced = parse(new Parser(List.of(same, a)), row("1", "USD"));
        status(coalesced, Status.PARSED);
        equal(coalesced.facts().size(), 1, "coalesced fact");
        equal(coalesced.matchedProvenance().size(), 2, "coalesced provenance");
        equal(coalesced, parse(new Parser(List.of(a, same)), row("1", "USD")), "coalescing order-independent");
        equal(new Parser(List.of(b, a)).senderOverlaps(), new Parser(List.of(a, b)).senderOverlaps(), "static overlap order");
        Parser single = new Parser(List.of(a));
        status(single.parse(message("SYNTHETIC ", row("1", "USD"))), Status.UNKNOWN_SENDER);
        status(single.parse(message("synthetic", row("1", "USD"))), Status.UNKNOWN_SENDER);
        status(parse(single, row("1", "USD").replace("Statement", "Promotion")), Status.NO_MATCH);
        rejection(parse(single, row("1", "USD") + "Amount=2;"), Status.AMBIGUOUS, Code.CAPTURE_AMBIGUOUS);
        Template broken = template("broken", "synthetic.bank", List.of(output("missing", Field.line(2),
                Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.USD), null)));
        rejection(parse(new Parser(List.of(a, broken)), row("1", "USD")), Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        Template excluded = new Template("synthetic.pack", "r1", "synthetic.bank", "excluded", Set.of("SYNTHETIC"),
                List.of(new Guard(-1, "Statement", false), new Guard(-1, "OTP", true)), List.of(usd));
        status(parse(new Parser(List.of(excluded)), row("1", "USD") + "OTP"), Status.NO_MATCH);
    }

    private static void multisetConflicts() {
        Output first = output("a", Field.line(1), Kind.POSTED_MOVEMENT,
                CurrencyRule.fixed(Currency.USD), DirectionRule.fixed(Direction.DEBIT));
        Output second = output("b", Field.line(2), Kind.POSTED_MOVEMENT,
                CurrencyRule.fixed(Currency.USD), DirectionRule.fixed(Direction.DEBIT));
        Template one = template("one", "synthetic.bank", List.of(first));
        Template two = template("two", "synthetic.bank", List.of(first, second));
        String identical = "Statement\nAccount=001;Amount=5.00;\nAccount=001;Amount=5.00;";
        Result twoAlone = parse(new Parser(List.of(two)), identical);
        status(twoAlone, Status.PARSED);
        equal(twoAlone.facts().size(), 2, "identical movements retain multiplicity");
        equal(twoAlone.facts().stream().map(f -> f.provenance().amountSpan()).distinct().count(), 2L,
                "identical movements have disjoint source captures");
        Result conflict = parse(new Parser(List.of(one, two)), identical);
        rejection(conflict, Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        equal(conflict, parse(new Parser(List.of(two, one)), identical), "multiset conflict order independence");

        Output reversedFirst = output("a", Field.line(2), Kind.POSTED_MOVEMENT,
                CurrencyRule.fixed(Currency.USD), DirectionRule.fixed(Direction.DEBIT));
        Output reversedSecond = output("b", Field.line(1), Kind.POSTED_MOVEMENT,
                CurrencyRule.fixed(Currency.USD), DirectionRule.fixed(Direction.DEBIT));
        Template reversed = template("reversed", "synthetic.bank", List.of(reversedSecond, reversedFirst));
        // Also use unequal amounts: list equality must not accidentally stand in for multiset equality.
        for (String body : List.of(identical, identical.replace("Amount=5.00;\n", "Amount=7.00;\n"))) {
            Result forwardAlone = parse(new Parser(List.of(two)), body);
            Result reversedAlone = parse(new Parser(List.of(reversed)), body);
            equal(forwardAlone.facts().get(0).money(), reversedAlone.facts().get(1).money(), "reversed first fact");
            equal(forwardAlone.facts().get(1).money(), reversedAlone.facts().get(0).money(), "reversed second fact");
            Result coalesced = parse(new Parser(List.of(two, reversed)), body);
            status(coalesced, Status.PARSED);
            equal(coalesced.facts().size(), 2, "coalesced multiset size");
            equal(coalesced.matchedProvenance().size(), 4, "all four output provenances retained");
            equal(coalesced.matchedProvenance().stream().distinct().count(), 4L, "four distinct provenances");
            equal(coalesced, parse(new Parser(List.of(reversed, two)), body), "multiset coalescing order independence");
        }
    }

    private static void bounds() {
        Parser single = single(Currency.USD);
        rejection(parse(single, "x".repeat(MAX_INPUT + 1)), Status.LIMIT_EXCEEDED, Code.INPUT_LIMIT);
        rejection(parse(single, "\n".repeat(MAX_LINES)), Status.LIMIT_EXCEEDED, Code.INPUT_LIMIT);
        rejection(single.parse(message("x".repeat(MAX_SENDER + 1), row("1", "USD"))), Status.LIMIT_EXCEEDED, Code.INPUT_LIMIT);
        rejection(parse(single, row("1".repeat(MAX_FIELD + 1), "USD")), Status.LIMIT_EXCEEDED, Code.FIELD_LIMIT);
        List<Template> many = new ArrayList<>();
        Output balance = output("balance", WHOLE, Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.USD), null);
        for (int i = 0; i <= MAX_CANDIDATES; i++) many.add(template("t" + i, "synthetic.bank", List.of(balance)));
        rejection(parse(new Parser(many), row("1", "USD")), Status.LIMIT_EXCEEDED, Code.CANDIDATE_LIMIT);
        Template expensive = new Template("synthetic.pack", "r1", "synthetic.bank", "expensive", Set.of("SYNTHETIC"),
                List.of(new Guard(-1, "z".repeat(MAX_LITERAL), false)), List.of(balance));
        rejection(parse(new Parser(List.of(expensive)), "x".repeat(MAX_INPUT)), Status.LIMIT_EXCEEDED, Code.WORK_LIMIT);
        rejection(parse(single, row("1", "USD") + "\r"), Status.INVALID, Code.INVALID_INPUT);
        rejection(parse(single, row("1", "USD") + "\uD800"), Status.INVALID, Code.INVALID_INPUT);
        String body = "Statement\r\nAccount=001;Amount=1.00;";
        Result crlf = parse(parser(output("balance", Field.line(1), Kind.BOOKED_BALANCE,
                CurrencyRule.fixed(Currency.USD), null)), body);
        status(crlf, Status.PARSED);
        Span span = crlf.facts().get(0).provenance().amountSpan();
        equal(body.substring(span.start(), span.end()), "1.00", "CRLF offsets preserved");
    }

    private static void lateWorkLimits() {
        String shortRow = row("1.00", "USD");
        String body = shortRow + "\n" + "x".repeat(MAX_INPUT - shortRow.length() - 1);
        Output first = output("a", Field.line(0), Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.USD), null);
        Template good = template("a.good", "synthetic.bank", List.of(first));
        Template expensive = new Template("synthetic.pack", "r1", "synthetic.bank", "z.expensive", Set.of("SYNTHETIC"),
                List.of(new Guard(-1, "z".repeat(MAX_LITERAL), false)), List.of(first));
        Result priorSuccess = parse(new Parser(List.of(good)), body);
        status(priorSuccess, Status.PARSED);
        equal(priorSuccess.facts().size(), 1, "prior candidate has a valid fact");
        equal(priorSuccess.matchedProvenance().size(), 1, "prior candidate has provenance");
        Result exhausted = parse(new Parser(List.of(good, expensive)), body);
        rejection(exhausted, Status.LIMIT_EXCEEDED, Code.WORK_LIMIT);
        equal(exhausted, parse(new Parser(List.of(expensive, good)), body), "late candidate work limit order independence");

        Field account = new Field(0, "Account=", ";", MAX_FIELD);
        MoneyRule money = money(new Field(0, "Amount=", ";", MAX_FIELD), Currency.USD);
        Output withoutDate = new Output("z", WHOLE, account, Kind.BOOKED_BALANCE, money, null, null, null);
        Result financialControl = parse(parser(first, withoutDate), body);
        status(financialControl, Status.PARSED);
        equal(financialControl.facts().size(), 2, "both outputs valid before optional date extraction");
        DateRule date = new DateRule(field("z".repeat(MAX_LITERAL), ";"), Set.of(Order.YMD), '/', false,
                Digits.ASCII, Duration.ofDays(45), Duration.ofHours(6));
        Output withDate = new Output("z", WHOLE, account, Kind.BOOKED_BALANCE, money, null, null, date);
        Result dateExhausted = parse(parser(first, withDate), body);
        rejection(dateExhausted, Status.LIMIT_EXCEEDED, Code.WORK_LIMIT);
        equal(dateExhausted, parse(parser(withDate, first), body), "late date work limit output order independence");
    }

    private static void dates() {
        Result dmy = dated(Set.of(Order.DMY), "03/04/2026", false, "UTC", ARRIVAL);
        Result mdy = dated(Set.of(Order.MDY), "03/04/2026", false, "UTC", ARRIVAL);
        equal(dmy.facts().get(0).time().instant(), Instant.parse("2026-04-03T00:00:00Z"), "explicit DMY");
        equal(mdy.facts().get(0).time().instant(), Instant.parse("2026-03-04T00:00:00Z"), "explicit MDY");
        Result ambiguous = dated(Set.of(Order.DMY, Order.MDY), "03/04/2026", false, "UTC", ARRIVAL);
        status(ambiguous, Status.PARSED);
        dateFallback(ambiguous, Code.DATE_AMBIGUOUS);
        dateFallback(dated(Set.of(Order.DMY, Order.MDY), "03/04/2026", false, "UTC",
                Instant.parse("2026-04-30T00:00:00Z")), Code.DATE_AMBIGUOUS);
        equal(ambiguous.facts().get(0).time().instant(), ARRIVAL, "ambiguous date arrival fallback");
        equal(ambiguous.facts().get(0).time().precision(), Precision.ARRIVAL, "fallback precision");
        equal(dmy.facts().get(0).time().precision(), Precision.DAY, "date-only precision");
        Result agrees = dated(Set.of(Order.DMY, Order.MDY), "04/04/2026", false, "UTC", ARRIVAL);
        equal(agrees.diagnostics().size(), 0, "agreeing date alternatives");
        dateFallback(dated(Set.of(Order.DMY), "31/02/2026", false, "UTC", ARRIVAL), Code.DATE_INVALID);
        dateFallback(dated(Set.of(Order.DMY), "03/04/0026", false, "UTC", ARRIVAL), Code.DATE_OUT_OF_WINDOW);
        dateFallback(dated(Set.of(Order.DMY), "3/4/2026", false, "UTC", ARRIVAL), Code.DATE_INVALID);
        dateFallback(dated(Set.of(Order.DMY), "29/02/2025", false, "UTC", ARRIVAL), Code.DATE_INVALID);
        Result leap = dated(Set.of(Order.YMD), "2024/02/29", false, "UTC", Instant.parse("2024-03-01T00:00:00Z"));
        equal(leap.facts().get(0).time().instant(), Instant.parse("2024-02-29T00:00:00Z"), "Gregorian leap day");
        Result ydm = dated(Set.of(Order.YDM), "2026/03/04", false, "UTC", ARRIVAL);
        equal(ydm.facts().get(0).time().instant(), Instant.parse("2026-04-03T00:00:00Z"), "explicit YDM");
        Result zone = dated(Set.of(Order.DMY), "03/04/2026 12:30", true, "Asia/Tehran", ARRIVAL);
        equal(zone.facts().get(0).time().instant(), Instant.parse("2026-04-03T09:00:00Z"), "injected zone");
        dateFallback(dated(Set.of(Order.YMD), "2026/03/08 02:30", true, "America/New_York", ARRIVAL), Code.DATE_ZONE_INVALID);
        dateFallback(dated(Set.of(Order.YMD), "2026/11/01 01:30", true, "America/New_York",
                Instant.parse("2026-11-02T00:00:00Z")), Code.DATE_ZONE_AMBIGUOUS);
        dateFallback(dated(Set.of(Order.YMD), "2026/04/03 25:30", true, "UTC", ARRIVAL), Code.DATE_INVALID);
    }

    private static void validation() {
        rejects(() -> new Money(Currency.USD, 1, 0));
        rejects(() -> new Field(MAX_LINES, "", "", 1));
        rejects(() -> new Field(0, "same", "same", 10));
        rejects(() -> new CurrencyRule(null, null, Map.of()));
        rejects(() -> new CurrencyRule(Currency.USD, TOKEN, Map.of()));
        rejects(() -> new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.USD), '.', '.', Grouping.WESTERN, Digits.ASCII, 1));
        rejects(() -> new MoneyRule(AMOUNT, CurrencyRule.fixed(Currency.USD), '.', ',', Grouping.WESTERN, Digits.ASCII, 10));
        Output balance = output("balance", WHOLE, Kind.BOOKED_BALANCE, CurrencyRule.fixed(Currency.USD), null);
        rejects(() -> template("bad/id", "synthetic.bank", List.of(balance)));
        rejects(() -> template("duplicate", "synthetic.bank", List.of(balance, balance)));
        List<Output> outputs = new ArrayList<>();
        for (int i = 0; i <= MAX_OUTPUTS; i++) outputs.add(output("o" + i, WHOLE, Kind.BOOKED_BALANCE,
                CurrencyRule.fixed(Currency.USD), null));
        rejects(() -> template("many", "synthetic.bank", outputs));
        Template t = template("a", "synthetic.bank", List.of(balance));
        rejects(() -> new Parser(List.of(t, t)));
        rejects(() -> new Parser(Collections.nCopies(MAX_TEMPLATES + 1, t)));
        rejects(() -> new DateRule(field("Date=", ";"), Set.of(), '/', false, Digits.ASCII,
                Duration.ofDays(45), Duration.ofHours(6)));
        Map<String, Currency> mutable = new HashMap<>();
        mutable.put("USD", Currency.USD);
        CurrencyRule rule = new CurrencyRule(null, TOKEN, mutable);
        mutable.put("USD", Currency.EUR);
        equal(rule.mapping().get("USD"), Currency.USD, "defensive mapping copy");
        List<Template> templates = new ArrayList<>(List.of(t));
        Parser frozen = new Parser(templates);
        templates.clear();
        status(parse(frozen, row("1", "USD")), Status.PARSED);
    }

    private static Result dated(Set<Order> orders, String date, boolean time, String zone, Instant arrival) {
        DateRule rule = new DateRule(field("Date=", ";"), orders, '/', time, Digits.ASCII,
                Duration.ofDays(45), Duration.ofHours(6));
        Output output = new Output("balance", WHOLE, ACCOUNT, Kind.BOOKED_BALANCE, money(AMOUNT, Currency.USD),
                null, null, rule);
        return parser(output).parse(new Message("fixture-1", "SYNTHETIC", row("1", "USD") + "Date=" + date + ";", arrival,
                ZoneId.of(zone)));
    }
    private static void moneyCase(Currency currency, String text, long expected) {
        Result result = parse(single(currency), row(text, currency.name()));
        status(result, Status.PARSED);
        equal(result.facts().get(0).money(), new Money(currency, expected, currency.scale), "exact money");
    }
    private static Parser withMoney(MoneyRule money) {
        return parser(new Output("balance", WHOLE, ACCOUNT, Kind.BOOKED_BALANCE, money, null, null, null));
    }
    private static Field field(String after, String before) { return new Field(-1, after, before, MAX_FIELD); }
    private static MoneyRule money(Field amount, Currency currency) {
        return new MoneyRule(amount, CurrencyRule.fixed(currency), '.', ',', Grouping.WESTERN, Digits.ASCII, 1);
    }
    private static Output output(String id, Field region, Kind kind, CurrencyRule currency, DirectionRule direction) {
        return new Output(id, region, ACCOUNT, kind,
                new MoneyRule(AMOUNT, currency, '.', ',', Grouping.WESTERN, Digits.ASCII, 1), direction, null, null);
    }
    private static Template template(String id, String bank, List<Output> outputs) {
        return new Template("synthetic.pack", "r1", bank, id, Set.of("SYNTHETIC"),
                List.of(new Guard(-1, "Statement", false)), outputs);
    }
    private static Parser parser(Output... outputs) { return new Parser(List.of(template("statement", "synthetic.bank", List.of(outputs)))); }
    private static Parser single(Currency currency) {
        return parser(output("balance", WHOLE, Kind.BOOKED_BALANCE, CurrencyRule.fixed(currency), null));
    }
    private static String row(String amount, String currency) {
        return "Statement;Account=0001;Amount=" + amount + ";Currency=" + currency + ";";
    }
    private static Message message(String sender, String body) { return new Message("fixture-1", sender, body, ARRIVAL, ZoneId.of("UTC")); }
    private static Result parse(Parser parser, String body) { return parser.parse(message("SYNTHETIC", body)); }
    private static Fact fact(Result result, String id) {
        return result.facts().stream().filter(f -> f.provenance().outputId().equals(id)).findFirst().orElseThrow();
    }
    private static void status(Result result, Status status) {
        equal(result.status(), status, "status");
        if (status != Status.PARSED) {
            equal(result.facts().size(), 0, "atomic rejection");
            equal(result.matchedProvenance().size(), 0, "no rejected provenance");
        }
    }
    private static void rejection(Result result, Status expected, Code code) {
        if (expected == Status.PARSED) throw new AssertionError("Rejection must not expect PARSED");
        status(result, expected);
        diagnostic(result, code);
    }
    private static void dateFallback(Result result, Code code) {
        status(result, Status.PARSED);
        diagnostic(result, code);
        equal(result.facts().size(), 1, "date fallback retains financial fact");
        equal(result.matchedProvenance().size(), 1, "date fallback retains provenance");
        Fact fact = result.facts().get(0);
        equal(fact.time().fallback(), true, "date fallback marked");
        equal(fact.time().precision(), Precision.ARRIVAL, "date fallback precision");
        equal(fact.time().instant(), fact.arrival(), "date fallback uses arrival");
    }
    private static void diagnostic(Result result, Code code) {
        checks++;
        if (result.diagnostics().stream().noneMatch(d -> d.code() == code))
            throw new AssertionError("Missing diagnostic " + code + ": " + result.status());
    }
    private static void equal(Object actual, Object expected, String message) {
        checks++;
        if (!actual.equals(expected)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid rule accepted");
    }
}
