package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.Parser.*;
import static com.ashkanrafiee.balance.parser.Rules.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Synthetic format demonstrations, not migrated bank tables or legacy-equivalence claims. */
public final class IranianPrototypeTest {
    private static int checks;
    private static final Field WHOLE = new Field(-1, "", "", MAX_INPUT);
    private static final Instant SEPTEMBER = Instant.parse("2026-09-21T06:00:00Z");
    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");
    private static final Field DATE = new Field(-1, "Date=", ";", 64);
    private static final Field ACCOUNT = new Field(-1, "Account=", ";", 64);
    private static final MoneyRule MONEY = new MoneyRule(new Field(-1, "Amount=", ";", 64),
            CurrencyRule.fixed(Currency.IRR), '.', ',', Grouping.WESTERN, Digits.ASCII_PERSIAN_ARABIC, 1);

    public static void main(String[] args) {
        mellatAndMelli();
        dottedAccounts();
        optionalMetadata();
        fieldBoundaries();
        optionalAccountsAndAtomicity();
        dates();
        dateZones();
        capsAndValidation();
        financialFieldRoles();
        metadataReconciliation();
        System.out.println("IranianPrototypeTest: " + checks + " checks passed");
    }

    private static void mellatAndMelli() {
        Field glued = numeric(1, "حساب", Normalization.DIGITS, NumericMode.PREFIX, new Width(6, 24));
        DateRule date = date(Calendar.JALALI, Year.TWO_DIGIT, 1400, true, DateLayout.SEPARATED,
                Set.of(Order.YMD), '-', false, TEHRAN, 45, 6);
        Output debit = output("debit", glued, false, date, null, null, Kind.POSTED_MOVEMENT);
        Output balance = new Output("balance", WHOLE, glued, Kind.BOOKED_BALANCE,
                new MoneyRule(new Field(-1, "Balance=", ";", 64), CurrencyRule.fixed(Currency.IRR),
                        '.', ',', Grouping.WESTERN, Digits.ASCII_PERSIAN_ARABIC, 1), null, null, date);
        String body = "Synthetic Mellat\nحساب۰۰۰۱۲۳۴۵۶ خرید\nAmount=۱,۲۰۰;Balance=۵,۰۰۰;Date=۰۵/۶/۳۰-۰۸:۵۳;";
        Result r = parse(List.of(debit, balance), body, SEPTEMBER);
        equal(r.status(), Status.PARSED);
        equal(r.facts().size(), 2);
        equal(r.facts().get(0).account(), "000123456");
        equal(r.facts().get(0).accountState(), AccountState.REFERENCED);
        equal(r.facts().get(0).time().instant(), Instant.parse("2026-09-21T05:23:00Z"));
        equal(r.facts().get(0).time().zone(), TEHRAN);
        equal(r.facts().get(0).money().minorUnits(), 5000L);
        equal(r.facts().get(1).money().minorUnits(), -1200L);
        Span span = r.facts().get(1).provenance().amountSpan();
        equal(body.substring(span.start(), span.end()), "۱,۲۰۰");
        failure(parse(List.of(debit, balance), body.replace("Balance=۵,۰۰۰", "Balance=bad"), SEPTEMBER),
                Status.INVALID, Code.INVALID_MONEY);
        failure(parse(debit, body.replace("۰۰۰۱۲۳۴۵۶", "۱۲۳۴۵"), SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        failure(parse(debit, body.replace("حساب۰۰۰", "حساب ۰۰۰"), SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);

        Field colon = numeric(1, "حساب:", Normalization.ACCOUNT, NumericMode.EXACT, new Width(3, 12));
        DateRule compact = date(Calendar.JALALI, Year.NEIGHBOR, 0, false, DateLayout.COMPACT,
                Set.of(Order.MD), '-', false, TEHRAN, 45, 6);
        Output melli = output("melli", colon, false, compact, null, null, Kind.POSTED_MOVEMENT);
        r = parse(melli, "Synthetic Melli\nحساب: ۰۰۰۱۲۳ \nAmount=100;Date=۰۶۳۰-۰۸:۵۳;", SEPTEMBER);
        equal(r.status(), Status.PARSED);
        equal(r.facts().get(0).account(), "000123");
        equal(r.facts().get(0).time().instant(), Instant.parse("2026-09-21T05:23:00Z"));
        equal(r.facts().get(0).time().precision(), Precision.MINUTE);
        // No calendar override based on the numeral script or the apparent year range.
        fallback(parse(debit, body.replace("۰۵/۶/۳۰", "2026/6/30"), SEPTEMBER), Code.DATE_INVALID);
    }

    private static void dottedAccounts() {
        Field resalat = numeric(1, "", Normalization.DIGITS, NumericMode.UNIQUE,
                new Width(1, 2), new Width(4, 12), new Width(1, 2));
        Output output = output("resalat", resalat, false, null, null, null, Kind.BOOKED_BALANCE);
        String body = "Synthetic Resalat\nحساب ۰۱.۰۰۰۱۲۳۴۵.۰۲ پایان\nAmount=100;";
        equal(parse(output, body, SEPTEMBER).facts().get(0).account(), "01.00012345.02");
        for (String account : List.of("001.00012345.02", "01.123.02", "01.00012345.002", "01..00012345.02",
                ".01.00012345.02", "01.00012345.02.", "01.00012345.02,", "01.00012345.02۳"))
            failure(parse(output, body.replace("۰۱.۰۰۰۱۲۳۴۵.۰۲", account), SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        failure(parse(output, body.replace(" پایان", " 2.1234.1 پایان"), SEPTEMBER), Status.AMBIGUOUS, Code.CAPTURE_AMBIGUOUS);

        Field pasargad = numeric(1, "", Normalization.ACCOUNT, NumericMode.EXACT,
                new Width(1, 4), new Width(1, 6), new Width(6, 12), new Width(1, 3));
        Output p = output("pasargad", pasargad, false, null, null, null, Kind.BOOKED_BALANCE);
        equal(parse(p, "Synthetic Pasargad\n\u202a001.02.000123456.003\u202c\t\nAmount=100;", SEPTEMBER)
                .facts().get(0).account(), "001.02.000123456.003");
        failure(parse(p, "Synthetic\n001.02.00012.003\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        failure(parse(p, "Synthetic\n001.02.000123456.003 trailing\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
    }

    private static void optionalMetadata() {
        Map<String, String> reasons = Map.of("شارژ شدی", "top-up", "پرداخت قبض", "bill-payment",
                "برگشت پول", "refund", "دریافت پل", "pol-in", "انتقال پل", "pol-out");
        TextRule reason = text(1, "", Normalization.TEXT, reasons, 60);
        Output blu = output("blu", null, true, null, reason, null, Kind.POSTED_MOVEMENT);
        for (Map.Entry<String, String> item : reasons.entrySet()) {
            Result r = parse(blu, "Synthetic Blu\n\t\u200f" + item.getKey().replace(" ", "  ") + "\u200e \r\nAmount=100;", SEPTEMBER);
            equal(r.status(), Status.PARSED);
            equal(r.facts().get(0).reason(), new SemanticText(item.getValue(), item.getKey()));
            equal(r.facts().get(0).account(), null);
            equal(r.facts().get(0).accountState(), AccountState.UNRESOLVED);
        }
        Result unknown = parse(blu, "Synthetic Blu\nبرداشت\nAmount=100;", SEPTEMBER);
        equal(unknown.status(), Status.PARSED);
        equal(unknown.facts().get(0).reason(), null);
        diagnostic(unknown, Code.UNKNOWN_TEXT);
        Result absent = parse(blu, "Synthetic Blu Amount=100;", SEPTEMBER);
        equal(absent.status(), Status.PARSED);
        diagnostic(absent, Code.OPTIONAL_ABSENT);
        Result unterminated = parse(blu, "Synthetic Blu Amount=100;\nشارژ شدی", SEPTEMBER);
        diagnostic(unterminated, Code.OPTIONAL_ABSENT);
        equal(unterminated.facts().get(0).reason(), null);
        Result blank = parse(blu, "Synthetic Blu\n\t\u200f \nAmount=100;", SEPTEMBER);
        equal(blank.status(), Status.PARSED);
        diagnostic(blank, Code.OPTIONAL_ABSENT);
        for (String invalid : List.of("قبض ۱", "قبض ١", "قبض 1", "قبض १", "قبض 𝟙", "x", " ".repeat(61) + "شارژ شدی"))
            diagnostic(parse(blu, "Synthetic Blu\n" + invalid + "\nAmount=100;", SEPTEMBER), Code.INVALID_TEXT);

        Map<String, String> channels = Map.of("شتاب", "shetab", "سامانه پل (پرداخت لحظه ای)", "pol",
                "پایانه فروش", "pos", "همراه بانک", "mobile", "شعبه", "branch");
        TextRule channel = text(2, "از طریق:", Normalization.CHANNEL, channels, 40);
        Field account = numeric(1, "حساب:", Normalization.ACCOUNT, NumericMode.EXACT, new Width(6, 24));
        Output tejarat = output("tejarat", account, true, null, null, channel, Kind.POSTED_MOVEMENT);
        for (Map.Entry<String, String> item : channels.entrySet()) {
            String token = item.getKey().replace('ی', 'ي').replace('ک', 'ك');
            Result r = parse(tejarat, "Synthetic Tejarat\nحساب: 000001\nاز طریق: " + token + "\nAmount=100;", SEPTEMBER);
            equal(r.status(), Status.PARSED);
            equal(r.facts().get(0).channel(), new SemanticText(item.getValue(), item.getKey()));
        }
        Result r = parse(tejarat, "Synthetic Tejarat\nحساب: unknown?\nاز طریق: تازه\nاز طریق: شتاب\nAmount=100;", SEPTEMBER);
        equal(r.status(), Status.PARSED);
        equal(r.facts().get(0).accountState(), AccountState.UNRESOLVED);
        equal(r.facts().get(0).channel(), null);
        diagnostic(r, Code.UNKNOWN_TEXT);
        diagnostic(r, Code.INVALID_FIELD);
        Result repeated = parse(tejarat, "Synthetic Tejarat\nحساب: 000001\nاز طریق: شتاب از طریق: شتاب\nAmount=100;", SEPTEMBER);
        equal(repeated.status(), Status.PARSED);
        equal(repeated.facts().get(0).channel(), null);
        diagnostic(repeated, Code.CAPTURE_AMBIGUOUS);
        // Optional metadata disagreement cannot erase equivalent financial facts.
        TextRule other = text(1, "", Normalization.TEXT, Map.of("شارژ شدی", "different"), 60);
        Parser conflict = new Parser(List.of(template("a", blu), template("b",
                output("blu", null, true, null, other, null, Kind.POSTED_MOVEMENT))));
        Result coalesced = conflict.parse(message("Synthetic\nشارژ شدی\nAmount=100;", SEPTEMBER));
        equal(coalesced.status(), Status.PARSED);
        equal(coalesced.facts().get(0).reason(), null);
        equal(coalesced.matchedProvenance().size(), 2);
        diagnostic(coalesced, Code.OPTIONAL_CONFLICT);
    }

    private static void fieldBoundaries() {
        Field normalized = numeric(1, "Account=", Normalization.ACCOUNT, NumericMode.EXACT, new Width(6, 24));
        Output o = output("account", normalized, false, null, null, null, Kind.BOOKED_BALANCE);
        equal(parse(o, "Synthetic\nAccount=\u2066۰۰۰۱۲۳\u2069\nAmount=100;", SEPTEMBER).facts().get(0).account(), "000123");
        for (String invalid : List.of("000\u200f123", "000\u200c123", "\u00a0000123", "000123\u00a0", "000123,", "000123."))
            failure(parse(o, "Synthetic\nAccount=" + invalid + "\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        failure(parse(o, "Synthetic\nAccount=000123 Account=000456\nAmount=100;", SEPTEMBER), Status.AMBIGUOUS, Code.CAPTURE_AMBIGUOUS);
        failure(parse(o, "Synthetic\nAccount=\n000123\nAmount=100;", SEPTEMBER), Status.ABSENT, Code.REQUIRED_ABSENT);
        Field strict = numeric(1, "Account=", Normalization.NONE, NumericMode.PREFIX, new Width(6, 24));
        Output s = output("strict", strict, false, null, null, null, Kind.BOOKED_BALANCE);
        // A mixed-script suffix must not turn an overlong/malformed token into a valid prefix.
        failure(parse(s, "Synthetic\nAccount=000123۷\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        failure(parse(s, "Synthetic\nAccount=000123१\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        failure(parse(s, "Synthetic\nAccount=000123𝟙\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_FIELD);
        Output old = output("old", new Field(1, "Account=", "", 64), false, null, null, null, Kind.BOOKED_BALANCE);
        failure(parse(old, "Synthetic\nAccount=۰۰۰۱۲۳\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_ACCOUNT);
        failure(parse(old, "Synthetic\nAccount= 000123\nAmount=100;", SEPTEMBER), Status.INVALID, Code.INVALID_ACCOUNT);
    }

    private static void optionalAccountsAndAtomicity() {
        Output optional = output("optional", ACCOUNT, true, null, null, null, Kind.POSTED_MOVEMENT);
        for (String account : List.of("", "Account=;", "Account=unknown?;", "Account=**1234;")) {
            Result r = parse(optional, "Synthetic " + account + "Amount=100;", SEPTEMBER);
            equal(r.status(), Status.PARSED);
            equal(r.facts().get(0).account(), null);
            equal(r.facts().get(0).accountState(), AccountState.UNRESOLVED);
            diagnostic(r, Code.ACCOUNT_UNRESOLVED);
        }
        failure(parse(optional, "Synthetic Account=1;Account=2;Amount=100;", SEPTEMBER), Status.AMBIGUOUS, Code.CAPTURE_AMBIGUOUS);
        failure(parse(optional, "Synthetic Account=" + "1".repeat(65) + ";Amount=100;", SEPTEMBER), Status.LIMIT_EXCEEDED, Code.FIELD_LIMIT);
        Output required = output("required", ACCOUNT, false, null, null, null, Kind.BOOKED_BALANCE);
        failure(parse(List.of(optional, required), "Synthetic Amount=100;", SEPTEMBER), Status.ABSENT, Code.REQUIRED_ABSENT);
        // An output that asks for no account is a complete read of a message that states none: it
        // resolves nothing, so it has nothing unresolved to report.
        Output accountless = output("accountless", null, true, null, null, null, Kind.POSTED_MOVEMENT);
        Result complete = parse(accountless, "Synthetic Amount=100;", SEPTEMBER);
        equal(complete.status(), Status.PARSED);
        equal(complete.facts().get(0).account(), null);
        equal(complete.facts().get(0).accountState(), AccountState.UNRESOLVED);
        noDiagnostic(complete);
        Result r = parse(optional, "Synthetic Account=0001;Amount=100;", SEPTEMBER);
        equal(r.facts().get(0).account(), "0001");
        equal(r.facts().get(0).accountState(), AccountState.REFERENCED);
    }

    private static void dates() {
        DateRule jalali = date(Calendar.JALALI, Year.FULL, 0, true, DateLayout.SEPARATED,
                Set.of(Order.YMD), ' ', false, TEHRAN, 45, 6);
        time(jalali, "1403/12/30 12:00", "2025-03-20T09:00:00Z", "2025-03-20T08:30:00Z");
        time(jalali, "1404/1/1 12:00", "2025-03-21T09:00:00Z", "2025-03-21T08:30:00Z");
        fallback(dated(jalali, "1404/12/30 12:00", "2026-03-21T09:00:00Z"), Code.DATE_INVALID);
        fallback(dated(jalali, "1405/7/31 12:00", "2026-10-23T09:00:00Z"), Code.DATE_INVALID);
        for (String bad : List.of("0000/1/1 12:00", "3178/1/1 12:00", "1405/0/1 12:00", "1405/1/0 12:00",
                "1405/06.30 12:00", "1405/6/30 24:00", "1405/6/30 12:60", "1405/6/30 8:53", "1405/6/30 08:53:01",
                "999999999999999999999/1/1 12:00", "1405/6/30 08:53 trailing"))
            fallback(dated(jalali, bad, SEPTEMBER.toString()), Code.DATE_INVALID);
        DateRule seconds = date(Calendar.GREGORIAN, Year.TWO_DIGIT, 2000, false, DateLayout.SEPARATED,
                Set.of(Order.DMY), 'T', true, ZoneOffset.UTC, 45, 6);
        Result second = dated(seconds, "21/09/26T05:23:59", SEPTEMBER.toString());
        equal(second.facts().get(0).time().precision(), Precision.SECOND);
        equal(second.facts().get(0).time().instant(), Instant.parse("2026-09-21T05:23:59Z"));
        fallback(dated(seconds, "21/09/26T05:23:60", SEPTEMBER.toString()), Code.DATE_INVALID);
        fallback(dated(seconds, "21/9/26T05:23:59", SEPTEMBER.toString()), Code.DATE_INVALID);

        DateRule missing = date(Calendar.GREGORIAN, Year.NEIGHBOR, 0, false, DateLayout.SEPARATED,
                Set.of(Order.MD), ' ', false, ZoneOffset.UTC, 366, 48);
        time(missing, "02/29 12:00", "2025-02-28T12:00:00Z", "2024-02-29T12:00:00Z");
        // Two neighboring years in a wide window are ambiguous; no nearest-year guess.
        fallback(dated(missing, "03/01 12:00", "2025-03-01T12:00:00Z"), Code.DATE_AMBIGUOUS);
        DateRule tight = date(Calendar.GREGORIAN, Year.NEIGHBOR, 0, false, DateLayout.SEPARATED,
                Set.of(Order.MD), ' ', false, ZoneOffset.UTC, 45, 6);
        time(tight, "12/31 23:59", "2026-01-01T00:01:00Z", "2025-12-31T23:59:00Z");
        time(tight, "01/01 00:01", "2025-12-31T23:59:00Z", "2026-01-01T00:01:00Z");
        fallback(dated(tight, "02/29 12:00", "2025-03-01T12:00:00Z"), Code.DATE_OUT_OF_WINDOW);
        DateRule jalaliMissing = date(Calendar.JALALI, Year.NEIGHBOR, 0, false, DateLayout.COMPACT,
                Set.of(Order.MD), '-', false, TEHRAN, 366, 6);
        time(jalaliMissing, "1230-12:00", "2026-03-20T09:00:00Z", "2025-03-20T08:30:00Z");
        DateRule compact = date(Calendar.JALALI, Year.NEIGHBOR, 0, false, DateLayout.COMPACT,
                Set.of(Order.MD), '-', false, TEHRAN, 45, 6);
        time(compact, "1230-23:59", "2025-03-20T20:31:00Z", "2025-03-20T20:29:00Z");
        for (String bad : List.of("630-08:53", "06300-08:53", "0630 08:53", "0630-8:53", "0630--08:53"))
            fallback(dated(compact, bad, SEPTEMBER.toString()), Code.DATE_INVALID);

        DateRule ambiguous = date(Calendar.GREGORIAN, Year.FULL, 0, false, DateLayout.SEPARATED,
                Set.of(Order.MDY, Order.DMY), ' ', false, ZoneOffset.UTC, 1, 0);
        fallback(dated(ambiguous, "03/04/2026 12:00", "2026-04-04T12:00:00Z"), Code.DATE_AMBIGUOUS);
        DateRule shortAmbiguous = date(Calendar.GREGORIAN, Year.NEIGHBOR, 0, false, DateLayout.SEPARATED,
                Set.of(Order.MD, Order.DM), ' ', false, ZoneOffset.UTC, 1, 0);
        fallback(dated(shortAmbiguous, "03/04 12:00", "2026-04-04T12:00:00Z"), Code.DATE_AMBIGUOUS);
        time(shortAmbiguous, "04/04 12:00", "2026-04-04T12:00:00Z", "2026-04-04T12:00:00Z");
        DateRule ydm = date(Calendar.JALALI, Year.TWO_DIGIT, 1400, true, DateLayout.SEPARATED,
                Set.of(Order.YDM), '-', false, TEHRAN, 45, 6);
        time(ydm, "05/30/6-08:53", SEPTEMBER.toString(), "2026-09-21T05:23:00Z");
        DateRule day = new DateRule(DATE, Set.of(Order.YMD), '/', false, Digits.ASCII,
                Duration.ofDays(45), Duration.ofHours(6),
                new DateOptions(Calendar.JALALI, Year.FULL, 0, true, DateLayout.SEPARATED, ' ', false, TEHRAN));
        Result dayResult = dated(day, "1405/6/30", SEPTEMBER.toString());
        equal(dayResult.facts().get(0).time().precision(), Precision.DAY);
        equal(dayResult.facts().get(0).time().instant(), Instant.parse("2026-09-20T20:30:00Z"));
    }

    private static void dateZones() {
        DateRule newYork = date(Calendar.GREGORIAN, Year.FULL, 0, false, DateLayout.SEPARATED,
                Set.of(Order.YMD), ' ', false, ZoneId.of("America/New_York"), 45, 6);
        fallback(dated(newYork, "2026/03/08 02:30", "2026-03-08T08:00:00Z"), Code.DATE_ZONE_INVALID);
        fallback(dated(newYork, "2026/11/01 01:30", "2026-11-01T08:00:00Z"), Code.DATE_ZONE_AMBIGUOUS);
        time(newYork, "2026/03/08 03:30", "2026-03-08T08:00:00Z", "2026-03-08T07:30:00Z");
        DateRule utc = date(Calendar.GREGORIAN, Year.FULL, 0, false, DateLayout.SEPARATED,
                Set.of(Order.YMD), ' ', false, ZoneOffset.UTC, 1, 6);
        time(utc, "2026/09/20 06:00", SEPTEMBER.toString(), "2026-09-20T06:00:00Z");
        time(utc, "2026/09/21 12:00", SEPTEMBER.toString(), "2026-09-21T12:00:00Z");
        fallback(dated(utc, "2026/09/20 05:59", SEPTEMBER.toString()), Code.DATE_OUT_OF_WINDOW);
        fallback(dated(utc, "2026/09/21 12:01", SEPTEMBER.toString()), Code.DATE_OUT_OF_WINDOW);
        failure(dated(utc, "x".repeat(65), SEPTEMBER.toString()), Status.LIMIT_EXCEEDED, Code.FIELD_LIMIT);
        Result r = dated(newYork, "bad", SEPTEMBER.toString());
        equal(r.facts().get(0).time().zone(), ZoneId.of("America/New_York"));
        equal(r.facts().get(0).time().instant(), SEPTEMBER);
        DateRule inferred = date(Calendar.GREGORIAN, Year.NEIGHBOR, 0, false, DateLayout.SEPARATED,
                Set.of(Order.MD), ' ', false, ZoneId.of("America/New_York"), 45, 6);
        fallback(dated(inferred, "03/08 02:30", "2026-03-08T08:00:00Z"), Code.DATE_ZONE_INVALID);
        // An irrelevant neighboring-year DST gap must not veto a valid current-year candidate.
        time(inferred, "03/08 02:30", "2027-03-08T08:00:00Z", "2027-03-08T07:30:00Z");
    }

    private static void capsAndValidation() {
        TextRule text = text(1, "", Normalization.TEXT, Map.of("title", "known"), 60);
        Output o = output("text", null, true, null, text, null, Kind.BOOKED_BALANCE);
        failure(parse(o, "Synthetic\n" + "x".repeat(257) + "\nAmount=100;", SEPTEMBER), Status.LIMIT_EXCEEDED, Code.FIELD_LIMIT);
        failure(parse(o, "Synthetic" + "x".repeat(MAX_INPUT), SEPTEMBER), Status.LIMIT_EXCEEDED, Code.INPUT_LIMIT);
        List<Template> templates = new ArrayList<>();
        for (int i = 0; i <= MAX_CANDIDATES; i++) templates.add(template("t" + i, o));
        failure(new Parser(templates).parse(message("Synthetic\ntitle\nAmount=100;", SEPTEMBER)), Status.LIMIT_EXCEEDED, Code.CANDIDATE_LIMIT);
        TextRule expensive = new TextRule(new Field(-1, "x".repeat(MAX_LITERAL - 1) + "z", "", 256),
                Map.of("title", "known"), 1, 60, true);
        Output slow = output("slow", null, true, null, expensive, null, Kind.BOOKED_BALANCE);
        failure(parse(slow, "Synthetic Amount=100;" + "x".repeat(10_000), SEPTEMBER), Status.LIMIT_EXCEEDED, Code.WORK_LIMIT);
        invalid(() -> new Width(0, 10));
        invalid(() -> new Width(2, 1));
        invalid(() -> new Width(1, 257));
        invalid(() -> new NumericShape(List.of(), NumericMode.EXACT));
        invalid(() -> new NumericShape(List.of(new Width(1, 256), new Width(1, 1)), NumericMode.EXACT));
        invalid(() -> new Field(-1, "", "", 64, Normalization.TEXT, null, true));
        invalid(() -> new Field(0, "", "", 257, Normalization.TEXT, null, false));
        invalid(() -> new Output("x", new Field(-1, "", "", 64, Normalization.DIGITS, null, false),
                ACCOUNT, Kind.BOOKED_BALANCE, MONEY, null, null, null));
        invalid(() -> output("x", null, false, null, null, null, Kind.BOOKED_BALANCE));
        invalid(() -> new TextRule(Field.line(0), Map.of("x", "private/id"), 1, 60, true));
        invalid(() -> date(Calendar.JALALI, Year.TWO_DIGIT, 0, true, DateLayout.SEPARATED,
                Set.of(Order.YMD), '-', false, TEHRAN, 45, 6));
        invalid(() -> date(Calendar.JALALI, Year.TWO_DIGIT, 3079, true, DateLayout.SEPARATED,
                Set.of(Order.YMD), '-', false, TEHRAN, 45, 6));
        invalid(() -> date(Calendar.GREGORIAN, Year.FULL, 2000, true, DateLayout.SEPARATED,
                Set.of(Order.YMD), '-', false, TEHRAN, 45, 6));
        invalid(() -> date(Calendar.JALALI, Year.NEIGHBOR, 0, true, DateLayout.COMPACT,
                Set.of(Order.MD), '-', false, TEHRAN, 45, 6));
        invalid(() -> date(Calendar.JALALI, Year.NEIGHBOR, 0, false, DateLayout.COMPACT,
                Set.of(Order.DM), '-', false, TEHRAN, 45, 6));
        invalid(() -> date(Calendar.JALALI, Year.NEIGHBOR, 0, false, DateLayout.SEPARATED,
                Set.of(Order.YMD), '-', false, TEHRAN, 45, 6));
        invalid(() -> new DateRule(DATE, Set.of(Order.MD), '/', true, Digits.ASCII, Duration.ZERO, Duration.ZERO));
    }

    private static void financialFieldRoles() {
        for (Normalization normalization : Normalization.values()) {
            Field f = new Field(-1, "Amount=", ";", 64, normalization, null, false);
            if (normalization != Normalization.NONE) {
                invalid(() -> rawMoney(f));
                invalid(() -> strictDate(f));
                invalid(() -> new DateRule(f, Set.of(Order.YMD), '/', true, Digits.ASCII,
                        Duration.ofDays(45), Duration.ZERO, new DateOptions(Calendar.GREGORIAN,
                        Year.FULL, 0, false, DateLayout.SEPARATED, ' ', false, ZoneOffset.UTC)));
            }
            if (normalization != Normalization.NONE && normalization != Normalization.TEXT) {
                invalid(() -> new CurrencyRule(null, f, Map.of("IRR", Currency.IRR)));
                invalid(() -> new DirectionRule(null, f, Map.of("DR", Direction.DEBIT)));
            }
        }
        for (NumericMode mode : NumericMode.values()) {
            Field f = new Field(-1, "Amount=", ";", 64, Normalization.NONE,
                    new NumericShape(List.of(new Width(1, 24)), mode), false);
            invalid(() -> rawMoney(f));
            invalid(() -> strictDate(f));
            invalid(() -> new CurrencyRule(null, f, Map.of("1", Currency.IRR)));
            invalid(() -> new DirectionRule(null, f, Map.of("1", Direction.DEBIT)));
            // Numeric selection is still usable in its intended roles.
            equal(new TextRule(f, Map.of("1", "one"), 1, 24, false).field(), f);
            equal(output("account", f, false, null, null, null, Kind.BOOKED_BALANCE).account(), f);
        }
        MoneyRule money = rawMoney(new Field(-1, "Amount=", ";", 64));
        Output balance = new Output("balance", WHOLE, null, Kind.BOOKED_BALANCE, money,
                null, null, null, true, null, null);
        equal(parse(balance, "Synthetic Amount=-12;", SEPTEMBER).facts().get(0).money().minorUnits(), -12L);
        equal(parse(balance, "Synthetic Amount=1 000;", SEPTEMBER).facts().get(0).money().minorUnits(), 1000L);
        for (String text : List.of("1  000", "1\u200c2", "1\u200d2", "1\u200f2", "۱۲", "١٢", "12 IRR", "12-", "12+", " 12")) {
            failure(parse(balance, "Synthetic Amount=" + text + ";", SEPTEMBER), Status.INVALID, Code.INVALID_MONEY);
            Output context = new Output("debit", WHOLE, null, Kind.POSTED_MOVEMENT, MONEY,
                    DirectionRule.fixed(Direction.DEBIT), rawMoney(new Field(-1, "Original=", ";", 64)),
                    null, true, null, null);
            failure(parse(context, "Synthetic Amount=10;Original=" + text + ";", SEPTEMBER), Status.INVALID, Code.INVALID_MONEY);
        }
        for (String text : List.of("۲۰۲۶/۰۹/۲۱ ۰۶:۰۰", "2026/09/21  06:00", "2026/09/2\u200c1 06:00", " 2026/09/21 06:00"))
            fallback(dated(strictDate(DATE), text, SEPTEMBER.toString()), Code.DATE_INVALID);
        Field currency = new Field(-1, "Currency=", ";", 64, Normalization.TEXT, null, false);
        Field direction = new Field(-1, "Direction=", ";", 64, Normalization.TEXT, null, false);
        Output mapped = new Output("mapped", WHOLE, null, Kind.POSTED_MOVEMENT,
                new MoneyRule(new Field(-1, "Amount=", ";", 64), new CurrencyRule(null, currency, Map.of("IRR", Currency.IRR)),
                        '.', ',', Grouping.WESTERN, Digits.ASCII, 1),
                new DirectionRule(null, direction, Map.of("DR", Direction.DEBIT)), null, null, true, null, null);
        equal(parse(mapped, "Synthetic Amount=12;Currency= \u200fIRR ;Direction= DR ;", SEPTEMBER)
                .facts().get(0).money().minorUnits(), -12L);
        failure(parse(mapped, "Synthetic Amount=12;Currency=IRR other;Direction=DR;", SEPTEMBER), Status.INVALID, Code.UNKNOWN_CURRENCY);
        failure(parse(mapped, "Synthetic Amount=12;Currency=IRR;Direction=DR other;", SEPTEMBER), Status.INVALID, Code.UNKNOWN_DIRECTION);
    }

    private static MoneyRule rawMoney(Field field) {
        return new MoneyRule(field, CurrencyRule.fixed(Currency.IRR), '.', ' ', Grouping.WESTERN, Digits.ASCII, 1);
    }
    private static DateRule strictDate(Field field) {
        return new DateRule(field, Set.of(Order.YMD), '/', true, Digits.ASCII, Duration.ofDays(45), Duration.ofHours(6));
    }
    private static TextRule metadata(String anchor, String token, String id) {
        return new TextRule(new Field(-1, anchor, ";", 64), Map.of(token, id), 1, 64, false);
    }
    private static Output annotated(String id, int line, TextRule reason, TextRule channel) {
        return new Output(id, line < 0 ? WHOLE : Field.line(line), null, Kind.BOOKED_BALANCE,
                MONEY, null, null, null, true, reason, channel);
    }
    private static Result alternatives(String body, Template... templates) {
        return new Parser(List.of(templates)).parse(message(body, SEPTEMBER));
    }
    private static void fieldDiagnostic(Result result, String field, Code code, long count) {
        equal(result.diagnostics().stream().filter(d -> d.field().equals(field) && d.code() == code).count(), count);
    }

    private static void metadataReconciliation() {
        TextRule reason = metadata("R=", "x", "reason");
        TextRule channel = metadata("C=", "c", "channel");
        String body = "Synthetic Amount=10;R=x;Q=y;C=c;D=d;";
        // Different IDs, different normalized text, absent declaration, missing/unknown token.
        TextRule[] differing = {metadata("R=", "x", "other"), metadata("Q=", "y", "reason"), null,
                metadata("Missing=", "x", "reason"), metadata("R=", "unknown", "reason")};
        for (boolean reasonField : List.of(true, false)) for (TextRule different : differing) {
            Template a = template("a", annotated("one", -1, reasonField ? reason : channel, reasonField ? channel : reason));
            Template b = template("b", annotated("one", -1, reasonField ? different : channel, reasonField ? channel : different));
            Result result = alternatives(body, a, b);
            equal(result, alternatives(body, b, a));
            equal(result.status(), Status.PARSED);
            equal(result.facts().size(), 1);
            equal(result.facts().get(0).money().minorUnits(), 10L);
            equal(result.matchedProvenance().size(), 2);
            equal(reasonField ? result.facts().get(0).reason() : result.facts().get(0).channel(), null);
            equal(reasonField ? result.facts().get(0).channel() : result.facts().get(0).reason(), new SemanticText("channel", "c"));
            fieldDiagnostic(result, reasonField ? "reason" : "channel", Code.OPTIONAL_CONFLICT, 1);
            diagnosticAbsent(result, Code.TEMPLATE_CONFLICT);
        }
        Template a = template("a", annotated("one", -1, reason, channel));
        Template same = template("b", annotated("other-id", -1, reason, channel));
        Result equalMetadata = alternatives(body, a, same);
        equal(equalMetadata.facts().get(0).reason(), new SemanticText("reason", "x"));
        equal(equalMetadata.facts().get(0).channel(), new SemanticText("channel", "c"));
        diagnosticAbsent(equalMetadata, Code.OPTIONAL_CONFLICT);
        Result absent = alternatives(body, template("a", annotated("one", -1, null, null)),
                template("b", annotated("one", -1, null, null)));
        equal(absent.status(), Status.PARSED);
        diagnosticAbsent(absent, Code.OPTIONAL_CONFLICT);
        Result both = alternatives(body, a, template("b", annotated("one", -1, null, null)));
        fieldDiagnostic(both, "reason", Code.OPTIONAL_CONFLICT, 1);
        fieldDiagnostic(both, "channel", Code.OPTIONAL_CONFLICT, 1);
        // A third template agreeing with one side cannot supply a majority winner.
        Result three = alternatives(body, a, same, template("c", annotated("one", -1, null, channel)));
        equal(three.facts().get(0).reason(), null);
        equal(three.matchedProvenance().size(), 3);
        Result repeated = alternatives(body + "R=x;C=c;", a);
        equal(repeated.status(), Status.PARSED);
        equal(repeated.facts().get(0).reason(), null);
        equal(repeated.facts().get(0).channel(), null);
        fieldDiagnostic(repeated, "reason", Code.CAPTURE_AMBIGUOUS, 1);
        fieldDiagnostic(repeated, "channel", Code.CAPTURE_AMBIGUOUS, 1);

        String multi = "Synthetic\nAmount=10;R=x;Q=y;C=c;\nAmount=20;R=x;Q=y;C=c;";
        Template first = template("a", annotated("a", 1, reason, channel), annotated("z", 2, reason, channel));
        Template second = template("b", annotated("z", 1, metadata("Q=", "y", "other"), channel), annotated("a", 2, reason, channel));
        Result distinct = alternatives(multi, first, second);
        equal(distinct, alternatives(multi, second, first));
        equal(distinct.status(), Status.PARSED);
        equal(distinct.facts().size(), 2);
        equal(distinct.matchedProvenance().size(), 4);
        equal(distinct.facts().get(0).reason(), null);
        equal(distinct.facts().get(1).reason(), new SemanticText("reason", "x"));
        fieldDiagnostic(distinct, "reason", Code.OPTIONAL_CONFLICT, 1);

        // Same financial multiset, differently associated annotations: IDs/array order prove nothing.
        String duplicate = multi.replace("Amount=20", "Amount=10");
        TextRule otherReason = metadata("Q=", "y", "other");
        Template d1 = template("a", annotated("a", 1, reason, channel), annotated("z", 2, otherReason, channel));
        Template d2 = template("b", annotated("z", 1, reason, channel), annotated("a", 2, otherReason, channel));
        Result duplicates = alternatives(duplicate, d1, d2);
        equal(duplicates, alternatives(duplicate, d2, d1));
        equal(duplicates.status(), Status.PARSED);
        equal(duplicates.facts().size(), 2);
        equal(duplicates.matchedProvenance().size(), 4);
        for (Fact fact : duplicates.facts()) {
            equal(fact.reason(), null);
            equal(fact.channel(), new SemanticText("channel", "c"));
        }
        fieldDiagnostic(duplicates, "reason", Code.OPTIONAL_ASSOCIATION_AMBIGUOUS, 2);
        // Without cross-template association, explicit per-output annotations are preserved.
        Result single = alternatives(duplicate, d1);
        equal(single.facts().get(0).reason(), new SemanticText("reason", "x"));
        equal(single.facts().get(1).reason(), new SemanticText("other", "y"));
        Result unanimous = alternatives(duplicate, first, template("b", annotated("z", 1, reason, channel), annotated("a", 2, reason, channel)));
        for (Fact fact : unanimous.facts()) equal(fact.reason(), new SemanticText("reason", "x"));
        diagnosticAbsent(unanimous, Code.OPTIONAL_ASSOCIATION_AMBIGUOUS);
        Result missingDuplicate = alternatives(duplicate, first,
                template("b", annotated("a", 1, null, channel), annotated("z", 2, reason, channel)));
        for (Fact fact : missingDuplicate.facts()) equal(fact.reason(), null);
        fieldDiagnostic(missingDuplicate, "reason", Code.OPTIONAL_ASSOCIATION_AMBIGUOUS, 2);
        // Financial mismatches and multiplicity mismatches must still reject the interpretation.
        failure(alternatives(multi, first, template("b", annotated("a", 1, reason, channel), annotated("z", 1, reason, channel))),
                Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        failure(alternatives(duplicate, first, template("b", annotated("a", 1, reason, channel))), Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        TextRule capped = new TextRule(new Field(-1, "R=", ";", 1), Map.of("x", "reason"), 1, 1, false);
        failure(alternatives(body.replace("R=x", "R=xx"), a, template("b", annotated("one", -1, capped, channel))),
                Status.AMBIGUOUS, Code.TEMPLATE_CONFLICT);
        failure(alternatives(body.replace("R=x", "R=xx"), template("b", annotated("one", -1, capped, channel))),
                Status.LIMIT_EXCEEDED, Code.FIELD_LIMIT);
        // Maximum legal duplicate/candidate multiplicity remains within the reconciliation budget.
        List<Template> many = new ArrayList<>();
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            List<Output> outputs = new ArrayList<>();
            for (int j = 0; j < MAX_OUTPUTS; j++) outputs.add(annotated("o" + j, -1, reason, channel));
            many.add(template("t" + i, outputs.toArray(new Output[0])));
        }
        Result bounded = alternatives(body, many.toArray(new Template[0]));
        equal(bounded.status(), Status.PARSED);
        equal(bounded.facts().size(), MAX_OUTPUTS);
        equal(bounded.matchedProvenance().size(), MAX_CANDIDATES * MAX_OUTPUTS);
    }
    private static void diagnosticAbsent(Result result, Code code) {
        equal(result.diagnostics().stream().anyMatch(d -> d.code() == code), false);
    }

    private static Field numeric(int line, String after, Normalization normalization, NumericMode mode, Width... widths) {
        return new Field(line, after, "", 128, normalization, new NumericShape(List.of(widths), mode), false);
    }
    private static TextRule text(int line, String after, Normalization normalization, Map<String, String> mapping, int max) {
        return new TextRule(new Field(line, after, "", 256, normalization, null, true), mapping, 2, max, true);
    }
    private static DateRule date(Calendar calendar, Year year, int base, boolean variable, DateLayout layout,
                                 Set<Order> orders, char clockSeparator, boolean seconds, ZoneId zone, int pastDays, int futureHours) {
        return new DateRule(DATE, orders, '/', true, Digits.ASCII_PERSIAN_ARABIC,
                Duration.ofDays(pastDays), Duration.ofHours(futureHours),
                new DateOptions(calendar, year, base, variable, layout, clockSeparator, seconds, zone));
    }
    private static Output output(String id, Field account, boolean optional, DateRule date, TextRule reason, TextRule channel, Kind kind) {
        return new Output(id, WHOLE, account, kind, MONEY, kind == Kind.POSTED_MOVEMENT ? DirectionRule.fixed(Direction.DEBIT) : null,
                null, date, optional, reason, channel);
    }
    private static Template template(String id, Output... outputs) {
        return new Template("synthetic.ir", "r1", "synthetic.bank", id, Set.of("SYNTHETIC"),
                List.of(new Guard(0, "Synthetic", false)), List.of(outputs));
    }
    private static Message message(String body, Instant arrival) {
        // Deliberately different from every pinned template zone; no device defaults.
        return new Message("synthetic-source", "SYNTHETIC", body, arrival, ZoneId.of("Pacific/Honolulu"));
    }
    private static Result parse(Output output, String body, Instant arrival) { return parse(List.of(output), body, arrival); }
    private static Result parse(List<Output> outputs, String body, Instant arrival) {
        return new Parser(List.of(template("example", outputs.toArray(new Output[0])))).parse(message(body, arrival));
    }
    private static Result dated(DateRule rule, String date, String arrival) {
        return parse(output("date", null, true, rule, null, null, Kind.BOOKED_BALANCE),
                "Synthetic Amount=100;Date=" + date + ";", Instant.parse(arrival));
    }
    private static void time(DateRule rule, String text, String arrival, String expected) {
        Result r = dated(rule, text, arrival);
        equal(r.status(), Status.PARSED);
        equal(r.facts().get(0).time().fallback(), false);
        equal(r.facts().get(0).time().instant(), Instant.parse(expected));
        equal(r.facts().get(0).time().zone(), rule.options().zone());
    }
    private static void fallback(Result r, Code code) {
        equal(r.status(), Status.PARSED);
        equal(r.facts().get(0).time().fallback(), true);
        equal(r.facts().get(0).time().precision(), Precision.ARRIVAL);
        equal(r.facts().get(0).time().instant(), r.facts().get(0).arrival());
        diagnostic(r, code);
    }
    private static void failure(Result r, Status status, Code code) {
        equal(r.status(), status); equal(r.facts().size(), 0); diagnostic(r, code);
    }
    private static void diagnostic(Result r, Code code) {
        equal(r.diagnostics().stream().anyMatch(d -> d.code() == code), true);
    }
    private static void noDiagnostic(Result r) {
        if (!r.diagnostics().isEmpty())
            throw new AssertionError("Expected no diagnostics, got " + r.diagnostics());
        checks++;
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid declaration"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void equal(Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) throw new AssertionError("Check " + checks + ": " + actual + " != " + expected);
    }
}
