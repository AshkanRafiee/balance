package com.ashkanrafiee.balance.parser.legacy;

import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Executable synthetic compatibility goldens; no Android, dependencies, or enabled assertions needed. */
public final class LegacyCompatibilityTest {
    private static int checks;
    private static final LegacyCalendarContext UTC = context("UTC");

    public static void main(String[] args) throws Exception {
        banks();
        accounts();
        reasonsAndChannels();
        money();
        identity();
        dates();
        calendarIsolation();
        System.out.println("LegacyCompatibilityTest: OK (" + checks + " checks); VERSION=" + LegacyBankRules.VERSION);
    }

    private static void banks() {
        eq(653419919, LegacyBankRules.VERSION);
        eq(345, LegacyBankRules.aliasList().size());
        eq(43, LegacyBankRules.supportedNames().size());
        eq(42, LegacyBankRules.reachableBanks().size());
        String[][] senders = {
            {"+9830005816", "Tosee Taavon"}, {"20004860", "Middle East"},
            {"98700717", "Melli"}, {"Bankino", "Bankino"}, {"Bank-Mellat", "Mellat"},
            {"+۹۸۵۰۰۰۹۷۳۱۸۹", "Tejarat"}, {"۹۰۰۰۴۸۰۰", "Eghtesad Novin"},
            {"0098500019000", "Pasargad"}, {"٣٠٠٠٩٤١٩", "Saderat"},
            {"*30009419", null}, {"30009419#", null}, {"unknown", null}, {"", null},
            {null, null}, {"Mellat extra", null}, {"9419", null},
            {"09419", "Saderat"}, {"77730009419", "Saderat"}
        };
        for (String[] row : senders) eq(row[1], LegacyBankRules.resolve(row[0]));
        eq("98500019000", LegacyBankRules.normalize("00989898500019000"));
        eq(false, LegacyBankRules.reachableBanks().contains("Tosee Credit Inst."));
        for (String bank : LegacyBankRules.supportedNames()) eq(LegacyCalendarSystem.JALALI, LegacyBankRules.calendar(bank));
        eq(LegacyCalendarSystem.JALALI, LegacyBankRules.calendar(null));
        // Every alias and synthetic suffix/extension must preserve the original linear winner.
        for (String alias : LegacyBankRules.aliasList()) {
            eq(linearResolve(alias), LegacyBankRules.resolve(alias));
            eq(linearResolve("777" + alias), LegacyBankRules.resolve("777" + alias));
            String normalized = LegacyBankRules.normalize(alias);
            if (normalized.length() >= 5) {
                String suffix = normalized.substring(normalized.length() - 5);
                eq(linearResolve(suffix), LegacyBankRules.resolve(suffix));
            }
        }
        String[][] rows = LegacyBankRules.rulesTestOnly();
        rows[0][0] = "modified";
        eq("Pasargad", LegacyBankRules.rulesTestOnly()[0][0]);
        LegacyBankRules.supportedNames().clear();
        LegacyBankRules.aliasList().clear();
        eq(43, LegacyBankRules.supportedNames().size());
        eq(345, LegacyBankRules.aliasList().size());
    }

    private static String linearResolve(String sender) {
        String a = LegacyBankRules.normalize(sender);
        for (String[] row : LegacyBankRules.rulesTestOnly()) {
            for (String alias : row[1].split("\\|")) {
                String b = LegacyBankRules.normalize(alias);
                if (a.equals(b)) return row[0];
                if (a.matches("[0-9]{5,}") && b.matches("[0-9]{5,}")
                        && (a.endsWith(b) || b.endsWith(a))) return row[0];
            }
        }
        return null;
    }

    private static void accounts() {
        String[][] cases = {
            {"Mellat", "حساب۰۰۰۱۲۳۴۵۶\nمانده:900", "000123456"},
            {"Melli", "حساب: ۰۰۱۲۳", "00123"},
            {"Tejarat", "حساب: 000123456789012345678901", "000123456789012345678901"},
            {"Saderat", "مقصد حساب:9999\n حساب:01234", "01234"},
            {"Parsian", "0001234567\r\nمبلغ:100-", "0001234567"},
            {"Mehr", "\u202B0001234567\u202C\r\n", "0001234567"},
            {"Resalat", "شماره 1.00012345.2", "1.00012345.2"},
            // The dotted whole-line match historically retains its trailing whitespace.
            {"Pasargad", "1.2.000123456.3 \n", "1.2.000123456.3 \n"},
            {"Mellat", "مانده حساب: 123456", null},
            {"Melli", "حساب: 1,234,567", null},
            {"Melli", "حساب:1234567890123", null},
            {"Saderat", "به حساب:12345", null},
            {"Parsian", "0001234567\nمانده:100", null},
            {"Mehr", "0001234567,000", null},
            {"Resalat", "1405.06.01", null},
            {"Pasargad", "حساب 1.2.000123456.3", null},
            {"Blu", "حساب:123456789", null}, {null, "حساب:123", null},
            {"Melli", null, null}
        };
        for (String[] c : cases) eq(c[2], LegacyBankRules.extractAccount(c[0], c[1]));
        eq(8, LegacyBankRules.accountRulesTestOnly().length);
    }

    private static void reasonsAndChannels() {
        String[] reasons = {"شارژ شدی", "پرداخت قبض", "برگشت پول", "دریافت پل", "انتقال پل"};
        for (String r : reasons) eq(r, LegacyBankRules.extractReason("Blu", "blu\r\n\t" + r + " \r\n100 ریال"));
        eq("شارژ شدی", LegacyBankRules.extractReason("Blu", "blu\nشارژ\u200C  شدی\u2069\n100 ریال"));
        eq(null, LegacyBankRules.extractReason("Blu", "blu\nواریز پول\n100 ریال"));
        eq(null, LegacyBankRules.extractReason("Blu", "blu\nشارژ شدی"));
        eq(null, LegacyBankRules.extractReason("Blu", "blu\nشارژ شدی ۱\n100 ریال"));
        eq(null, LegacyBankRules.extractReason("Blu", "blu\nشارژ شدي\n100 ریال"));
        eq(null, LegacyBankRules.extractReason("Melli", "blu\nشارژ شدی\n100 ریال"));
        String[] channels = {"شتاب", "سامانه پل (پرداخت لحظه ای)", "پایانه فروش", "همراه بانک", "شعبه"};
        for (String c : channels) eq(c, LegacyBankRules.extractChannel("Tejarat", "از طريق: " + c + "\nمانده:900"));
        eq("همراه بانک", LegacyBankRules.extractChannel("Tejarat", "از طریق: همراه بانك\n"));
        eq(null, LegacyBankRules.extractChannel("Tejarat", "از طريق: شتاب"));
        eq(null, LegacyBankRules.extractChannel("Tejarat", "از طريق: ناشناخته\n"));
        eq(null, LegacyBankRules.extractChannel("Tejarat", "از طريق: شتاب ۱\n"));
        eq(null, LegacyBankRules.extractChannel("Blu", "از طريق: شتاب\n"));
        eq(5, LegacyBankRules.reasonCaptionKeys().size());
        eq(5, LegacyBankRules.channelCaptionKeys().size());
        LegacyBankRules.reasonCaptionKeys().clear();
        LegacyBankRules.channelCaptionKeys().clear();
        eq(5, LegacyBankRules.reasonCaptionKeys().size());
        eq(5, LegacyBankRules.channelCaptionKeys().size());
        char[] large = new char[100000];
        Arrays.fill(large, 'x');
        eq(null, LegacyBankRules.extractReason("Blu", new String(large)));
        eq(null, LegacyBankRules.extractChannel("Tejarat", new String(large)));
    }

    private static void money() {
        Object[][] balances = {
            {null, -1L}, {"balance: -123.45", 123L}, {"balance: 1,2,3", 123L},
            {"مانده: ۱۲٬۳۴۵", 12345L}, {"balance:1\nbal:2", 2L},
            {"balance:9223372036854775808", -1L}, {"balance:9223372036854775807", Long.MAX_VALUE},
            {"balance:100 code:1234", -1L}, {"balance:0", 0L},
            {"موجودي:100", -1L}, {"مانده: ۱،۲۳۴ تومان", 1234L}
        };
        for (Object[] c : balances) eq(c[1], LegacyMoney.extract((String) c[0]));
        Object[][] movements = {
            {"مبلغ:100-\nمانده:900", -100L}, {"مبلغ:100+\nبرداشت\nمانده:900", 100L},
            {"واریز:100\nپرداخت\nمانده:900", 100L}, {"برداشت:100\nمانده:900", -100L},
            {"حواله پل:100+\nمانده:900", 100L},
            {"خرید 100 ریال و 200 ریال\nمانده:900", -200L},
            {"-100\nمانده:900", -100L}, {"\u202B100-\u202C\nمانده:900", -100L},
            {"مبلغ:100\nخرید و واریز\nمانده:900", null},
            {"مبلغ:0-\nمانده:900", null}, {"مبلغ:100-", null},
            {"مبلغ:100-\nمانده:900\nرمز:1234", null},
            // Destructive balance replacement removes matching digits inside the moved amount.
            {"خرید 1900 ریال\nمانده:900", -1L},
            {"خرید 900 ریال\nمانده:900", null},
            {"مبلغ:9223372036854775808-\nمانده:900", null},
            {"مبلغ:9223372036854775807-\nمانده:900", -Long.MAX_VALUE}
        };
        for (Object[] c : movements) eq(c[1], LegacyMoney.extractTransaction((String) c[0]));
        eq(100L, LegacyMoney.inferMovement("خرید و واریز\nمانده:900", true, 800));
        eq(null, LegacyMoney.inferMovement("مانده:900", true, 800));
        eq(null, LegacyMoney.inferMovement("خرید\nمانده:900", false, 800));
        eq(null, LegacyMoney.inferMovement("خرید\nمانده:900", true, 900));
        eq(Long.MIN_VALUE, LegacyMoney.inferMovement("خرید\nمانده:0", true, Long.MIN_VALUE));
        eq("0123456789 0123456789 １２", LegacyDigits.ascii("۰۱۲۳۴۵۶۷۸۹ ٠١٢٣٤٥٦٧٨٩ １２"));
    }

    private static void identity() {
        String body = "مبلغ:100-\nمانده:900";
        eq("194893b96914efd799670a9d6760f512", LegacyMoney.messageSig("synthetic", body));
        eq("fd1970d38de795cd5dc8a0fd6c4469f7", LegacyMoney.messageSig("synthetic", body, "000123"));
        eq(LegacyMoney.messageSig("synthetic", body), LegacyMoney.messageSig("synthetic", body + "\n2026/09/01"));
        eq(false, LegacyMoney.contentHash("synthetic", body).equals(LegacyMoney.contentHash("synthetic", body + "\n2026/09/01")));
        eq(LegacyMoney.contentHash("synthetic", "ي ك 123,456"), LegacyMoney.contentHash("synthetic", "  ی\nک ۱۲۳٬۴۵۶  "));
        eq(LegacyMoney.messageSig("synthetic", "not financial", "000123"), LegacyMoney.messageSig("synthetic", "not financial", "999999"));
        eq(null, LegacyMoney.messageSig("synthetic", " \t\n"));
        eq(null, LegacyMoney.contentHash("synthetic", null));
    }

    private static void dates() {
        long arrival = utc(2026, 9, 23, 12, 0);
        String[] layouts = {"1405/06/31 10:20:59", "05/06/31 10:20", "0631-10:20", "06/31 10:20", "2026-09-22 10:20"};
        for (String body : layouts) eq(utc(2026, 9, 22, 10, 20), date(body, arrival, UTC));
        eq(utc(2026, 9, 22, 0, 0), date("2026/09/22", arrival, UTC));
        eq(utc(2026, 9, 22, 10, 20), date("29:90 10:20:99 2026/09/22 11:30", arrival, UTC));
        eq(arrival, date("2026/13/22 2026/09/22 10:20", arrival, UTC));
        eq(arrival, date("1800/09/22 10:20", arrival, UTC));
        eq(arrival, date("06/31", arrival, UTC));
        eq(arrival, date("0631-25:20 10:20", arrival, UTC));
        eq(arrival, date(null, arrival, UTC));
        eq(arrival, date("2026/02/29", arrival, UTC));
        eq(utc(2026, 9, 23, 18, 0), date("2026/09/23 18:00", arrival, UTC));
        eq(arrival, date("2026/09/23 18:01", arrival, UTC));
        eq(arrival - LegacyMessageDate.MAX_AGE_MS, date("2026/08/09 12:00", arrival, UTC));
        eq(arrival, date("2026/08/09 11:59", arrival, UTC));
        eq(utc(2025, 12, 31, 23, 0), LegacyMessageDate.eventTime("12/31 23:00", utc(2026, 1, 1, 1, 0), LegacyCalendarSystem.GREGORIAN, UTC));
        eq(utc(2024, 2, 29, 10, 0), LegacyMessageDate.eventTime("24/02/29 10:00", utc(2024, 3, 1, 1, 0), LegacyCalendarSystem.GREGORIAN, UTC));
        eq(30, LegacyJalaliCalendar.daysInMonth(1403, 12));
        eq(29, LegacyJalaliCalendar.daysInMonth(1404, 12));
        eq("[2024, 3, 20]", Arrays.toString(LegacyJalaliCalendar.of(1403, 1, 1).toGregorian()));
        LegacyJalaliCalendar jalali = LegacyJalaliCalendar.fromGregorian(2024, 3, 20);
        eq(1403, jalali.year); eq(1, jalali.month); eq(1, jalali.day);
        eq(null, LegacyCalendarSystem.ofTag("j"));
        eq(null, LegacyCalendarSystem.ofTag(null));
        eq(LegacyCalendarSystem.JALALI, LegacyCalendarSystem.ofTag("J"));
    }

    private static void calendarIsolation() throws Exception {
        long arrival = utc(2026, 9, 23, 12, 0);
        TimeZone zone = TimeZone.getTimeZone("GMT+03:30");
        LegacyCalendarContext east = new LegacyCalendarContext(zone, Locale.ROOT);
        zone.setRawOffset(0);
        long expectedEast = utc(2026, 9, 22, 6, 50);
        eq(expectedEast, date("2026/09/22 10:20", arrival, east));
        Calendar prototype = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        LegacyCalendarContext captured = new LegacyCalendarContext(prototype);
        prototype.setTimeZone(TimeZone.getTimeZone("GMT-08:00"));
        eq(utc(2026, 9, 22, 10, 20), date("2026/09/22 10:20", arrival, captured));
        // The same worker alternates contexts: a stale thread-local zone would fail this.
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 30; i++) {
                final LegacyCalendarContext c = i % 2 == 0 ? east : UTC;
                Future<Long> result = worker.submit(new Callable<Long>() {
                    public Long call() { return date("2026/09/22 10:20", arrival, c); }
                });
                eq(i % 2 == 0 ? expectedEast : utc(2026, 9, 22, 10, 20), result.get());
            }
        } finally { worker.shutdownNow(); }
        // Legacy leniency rolls a nonexistent local clock through the DST gap.
        eq(utc(2026, 3, 8, 7, 30), date("2026/03/08 02:30", utc(2026, 3, 9, 12, 0), context("America/New_York")));
    }

    private static LegacyCalendarContext context(String zone) {
        return new LegacyCalendarContext(TimeZone.getTimeZone(zone), Locale.ROOT);
    }
    private static long date(String body, long arrival, LegacyCalendarContext context) {
        return LegacyMessageDate.eventTime(body, arrival, LegacyCalendarSystem.JALALI, context);
    }
    private static long utc(int year, int month, int day, int hour, int minute) {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }
    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("check " + checks + ": expected " + expected + ", got " + actual);
    }
}
