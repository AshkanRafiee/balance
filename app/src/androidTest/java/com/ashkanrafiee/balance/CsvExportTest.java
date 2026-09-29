package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-logic tests for the transaction CSV builder ({@link CsvExport}): the fixed column set,
 *  RFC-4180 escaping, one row's cell layout and the oldest-first ordering. No SMS involved. */
@RunWith(AndroidJUnit4.class)
public class CsvExportTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_TOMAN);
    }

    @After public void tearDown() {
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
    }

    /** The per-transaction text an export is given. The three stores are independent — a caller may
     *  have loaded any of them — so each helper names exactly which ones this call carries. */
    private static CsvExport.Text noText() {
        return CsvExport.Text.none();
    }

    private static CsvExport.Text withNotes(java.util.Map<String, String> notes) {
        return new CsvExport.Text(notes, null, null);
    }

    private static CsvExport.Text withReasons(java.util.Map<String, String> reasons) {
        return new CsvExport.Text(null, reasons, null);
    }

    private static CsvExport.Text withChannels(java.util.Map<String, String> channels) {
        return new CsvExport.Text(null, null, channels);
    }

    private static CsvExport.Text withText(java.util.Map<String, String> notes,
            java.util.Map<String, String> reasons) {
        return new CsvExport.Text(notes, reasons, null);
    }

    private static String line(String csv, int index) {
        return csv.split("\n")[index];
    }

    /** Splits one CSV row honoring RFC-4180 quoting, so fields containing commas (like the grouped
     *  display amount) come back whole. */
    private static List<String> parse(String row) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    private static final long DATE_2024 = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long DATE_2026 = 1767225600000L; // 2026-01-01T00:00:00Z

    @Test public void csv_emptyList_containsOnlyTheHeader() {
        String csv = CsvExport.csv(ctx, new ArrayList<>(), noText());
        // A UTF-8 BOM leads the file so spreadsheets (Excel first) read Persian text as UTF-8 instead
        // of mis-decoding it; the header itself follows right after it.
        assertTrue(csv.startsWith("\uFEFF"));
        assertEquals("bank,account,date,date_local,time,amount,amount_display,currency,unit,note,reason,channel,kind",
            line(csv, 0).substring(1));
        assertEquals(1, csv.split("\n").length);
    }

    @Test public void escape_plainValue_staysUntouched() {
        assertEquals("plain", CsvExport.escape("plain"));
        assertEquals("", CsvExport.escape(""));
    }

    @Test public void escape_commaQuoteAndNewline_areQuotedRfc4180() {
        assertEquals("\"a,b\"", CsvExport.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvExport.escape("line1\nline2"));
        assertEquals("\"a\r\nb\"", CsvExport.escape("a\r\nb"));
    }

    @Test public void row_oneTransaction_laysOutCellsInFixedOrder() {
        Transaction t = new Transaction("bank_melli", "910251846", DATE_2026, 1_250_000L, "sig");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), noText()), 1);
        List<String> cells = parse(row);
        assertEquals(13, cells.size());
        assertEquals(BankRules.displayName(ctx, "bank_melli"), cells.get(0));
        assertEquals("910251846", cells.get(1));
        assertEquals("2026-01-01T00:00:00Z", cells.get(2));
        assertEquals("1250000", cells.get(5));
        assertEquals(CsvExport.KIND_MOVEMENT, cells.get(12));
        assertEquals("125,000", cells.get(6));
        // The code is the machine-readable identity of the money; the unit beside it is the label a
        // person reads, and the app's chosen rial denomination never reaches a foreign row.
        assertEquals("IRR", cells.get(7));
        assertEquals("Toman", cells.get(8));
        assertEquals("", cells.get(9));
    }

    @Test public void row_foreignCurrency_isExportedAtItsOwnScaleUnconverted() {
        Transaction t = new Transaction("bank_xyz", "1", DATE_2026, 1_234L, null, "sig", "c", "USD");
        List<String> cells = parse(line(CsvExport.csv(ctx, Arrays.asList(t), noText()), 1));
        assertEquals(13, cells.size());
        // 1234 US cents is 12.34 dollars, written in both the raw and the displayed column, with no
        // division by ten and no conversion of any kind.
        assertEquals("12.34", cells.get(5));
        assertEquals("12.34", cells.get(6));
        assertEquals("USD", cells.get(7));
        assertEquals("USD", cells.get(8));
    }

    @Test public void amount_aCurrencyOfUnknownScaleKeepsItsRawMinorUnits() {
        // Nothing in the registry says how many decimals "XYZ" has, so the file must not invent a
        // scale: the minor units go out exactly as they are stored, next to the code itself.
        assertEquals("1234", CsvExport.amount("XYZ", 1234L));
        assertEquals("12.34", CsvExport.amount("USD", 1234L));
        assertEquals("20", CsvExport.amount("USD", 2000L));
        assertEquals("1.234", CsvExport.amount("KWD", 1234L));
        assertEquals("1250000", CsvExport.amount("IRR", 1_250_000L));
        assertEquals("-500", CsvExport.amount(null, -500L));
    }

    @Test public void row_accountMissing_leavesTheAccountCellEmpty() {
        Transaction t = new Transaction("bank_tejarat", null, DATE_2026, -500L, "sig");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), noText()), 1);
        assertTrue(row.startsWith(BankRules.displayName(ctx, "bank_tejarat") + ",,"));
    }

    @Test public void row_hostileAccount_isQuotedWithDoubledQuotes() {
        Transaction t = new Transaction("bank_melli", "91,\"0", DATE_2026, 500L, "sig");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), noText()), 1);
        assertTrue(row.contains("\"91,\"\"0\""));
    }

    @Test public void csv_unsortedInputs_writesOldestFirst() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("bank_melli", null, DATE_2026, 111L, "a"));
        txs.add(new Transaction("bank_tejarat", null, DATE_2024, 222L, "b"));
        txs.add(new Transaction("bank_melli", null, DATE_2024, 333L, "c"));
        String csv = CsvExport.csv(ctx, txs, noText());
        assertTrue(line(csv, 1).contains(",222,"));
        assertTrue(line(csv, 2).contains(",333,"));
        assertTrue(line(csv, 3).contains(",111,"));
    }

    @Test public void csv_persianToman_formatsDisplayAndUnitInFarsi() {
        LocaleHelper.setLanguage(ctx, "fa");
        Context fa = LocaleHelper.wrap(ctx);
        Transaction t = new Transaction("bank_melli", null, DATE_2026, 1_250_000L, "sig");
        String row = line(CsvExport.csv(fa, Arrays.asList(t), noText()), 1);
        assertTrue(row.contains("۱۲۵٬۰۰۰"));
        assertTrue(row.contains(",تومان,"));
    }

    @Test public void row_withNote_writesNoteAndEscapesItRfc4180() {
        Transaction t = new Transaction("bank_melli", null, DATE_2026, 1_250_000L, "sig",
            "content-hash");
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        notes.put(BalanceData.noteKey(t), "picked up from the cashier, watch out, \"late\"");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), withNotes(notes)), 1);
        // The raw row carries the RFC-4180 escaping (comma and quote wrapped, quote doubled)…
        assertTrue(row.contains("\"picked up from the cashier, watch out, \"\"late\"\"\""));
        // …and a plain parser sees the original text unquoted in the last cell.
        List<String> cells = parse(row);
        assertEquals(13, cells.size());
        assertEquals("picked up from the cashier, watch out, \"late\"", cells.get(9));
        // The reason is the bank's own statement, read out of the message; a movement with no
        // detected reason carries an empty cell rather than borrowing the note's words.
        assertEquals("", cells.get(10));
        assertEquals("", cells.get(11));
        assertEquals(CsvExport.KIND_MOVEMENT, cells.get(12));
    }

    @Test public void csv_persianNote_survivesUtf8RoundTripAfterTheBom() {
        // The export is written as UTF-8; this pins down that the Persian note bytes are encoded as
        // real UTF-8 (never transliterated or stripped) and land after the BOM that tells a
        // spreadsheet how to read them.
        Transaction t = new Transaction("bank_melli", null, DATE_2026, 1_250_000L, "sig",
            "content-fa");
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        notes.put(BalanceData.noteKey(t), "مبلغ را نگه داشتم برای روز مبادا");
        String csv = CsvExport.csv(ctx, Arrays.asList(t), withNotes(notes));

        byte[] utf8 = csv.getBytes(StandardCharsets.UTF_8);
        assertEquals((byte) 0xEF, utf8[0]);
        assertEquals((byte) 0xBB, utf8[1]);
        assertEquals((byte) 0xBF, utf8[2]);

        String decoded = new String(utf8, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("مبلغ را نگه داشتم برای روز مبادا"));
    }

    // -----------------------------------------------------------------------
    // The reason the bank stated
    // -----------------------------------------------------------------------

    /** A Blu top-up: the bank titles the event, which the app stores and shows as a caption. */
    private static final String TOPUP = "شارژ شدی";

    @Test public void row_withReason_writesTheCaptionBesideTheNote() {
        // The reason is exported as the caption the app shows, in the app's language, so the file
        // reads the way the screen does rather than carrying a raw bank string.
        Transaction t = new Transaction("Blu", null, DATE_2026, -220_000L, "sig", "content-hash");
        java.util.Map<String, String> reasons = new java.util.HashMap<>();
        reasons.put(BalanceData.noteKey(t), TOPUP);
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        notes.put(BalanceData.noteKey(t), "topped up my number");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), withText(notes, reasons)), 1);

        List<String> cells = parse(row);
        assertEquals(13, cells.size());
        assertEquals("topped up my number", cells.get(9));
        assertEquals("Phone top-up", cells.get(10));
        // A movement the bank named no channel for carries an empty cell there rather than borrowing
        // the note's or the reason's words.
        assertEquals("", cells.get(11));
        assertEquals(CsvExport.KIND_MOVEMENT, cells.get(12));
    }

    @Test public void row_reasonIsCaptionedInTheAppLanguage() {
        // The stored reason is the bank's own title; the file shows the caption for the language
        // the export was made in, so a Persian export needs no translation step.
        LocaleHelper.setLanguage(ctx, "fa");
        Context fa = LocaleHelper.wrap(ctx);
        Transaction t = new Transaction("Blu", null, DATE_2026, 1_000_000L, "sig", "content-fa");
        java.util.Map<String, String> reasons = new java.util.HashMap<>();
        reasons.put(BalanceData.noteKey(t), "دریافت پل");
        String row = line(CsvExport.csv(fa, Arrays.asList(t),
            withReasons(reasons)), 1);

        assertEquals("دریافت پل", parse(row).get(10));
    }

    @Test public void row_uncaptionableStoredReason_leavesTheCellEmpty() {
        // A title this build has no caption for is dropped rather than written out as a raw
        // fragment of a bank message, which would read as though the app had understood it.
        Transaction t = new Transaction("Blu", null, DATE_2026, 1_000_000L, "sig", "content-hash");
        java.util.Map<String, String> reasons = new java.util.HashMap<>();
        reasons.put(BalanceData.noteKey(t), "برای وام گرفتن وقت تنگه");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t),
            withReasons(reasons)), 1);

        assertEquals("", parse(row).get(10));
    }

    @Test public void row_reasonsWithoutAMatchingTransaction_changeNothing() {
        // A reason left behind by a movement that is no longer in the history is never written into
        // another row: rows are joined strictly by the transaction identity.
        Transaction t = new Transaction("Blu", null, DATE_2026, -220_000L, "sig", "content-hash");
        Transaction other = new Transaction("Blu", null, DATE_2024, -220_000L, "sig", "other-hash");
        java.util.Map<String, String> reasons = new java.util.HashMap<>();
        reasons.put(BalanceData.noteKey(other), TOPUP);
        String csv = CsvExport.csv(ctx, Arrays.asList(t),
            withReasons(reasons));

        assertEquals("", parse(line(csv, 1)).get(10));
    }

    // -----------------------------------------------------------------------
    // The channel the movement went through
    // -----------------------------------------------------------------------

    /** A Tejarat withdrawal: the bank names the channel the money went through. */
    private static final String SHETAB = "شتاب";

    private static java.util.Map<String, String> channelsOf(Transaction t, String channel) {
        java.util.Map<String, String> channels = new java.util.HashMap<>();
        channels.put(BalanceData.noteKey(t), channel);
        return channels;
    }

    @Test public void row_withChannel_writesTheCaptionBesideTheNoteAndReason() {
        // The channel is exported as the caption the app shows, on the same terms as the reason: the
        // file reads the way the screen does rather than carrying a raw bank string.
        Transaction t = new Transaction("Tejarat", "01350000000", DATE_2026, -220_000L, "sig", "content-hash");
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        notes.put(BalanceData.noteKey(t), "for the shop");
        java.util.Map<String, String> reasons = new java.util.HashMap<>();
        reasons.put(BalanceData.noteKey(t), TOPUP);
        String row = line(CsvExport.csv(ctx, Arrays.asList(t),
            new CsvExport.Text(notes, reasons, channelsOf(t, SHETAB))), 1);

        List<String> cells = parse(row);
        assertEquals(13, cells.size());
        assertEquals("for the shop", cells.get(9));
        assertEquals("Phone top-up", cells.get(10));
        assertEquals("Shetab", cells.get(11));
        assertEquals(CsvExport.KIND_MOVEMENT, cells.get(12));
    }

    @Test public void row_channelIsCaptionedInTheAppLanguage() {
        // The stored channel is the bank's own wording; the file shows the caption for the language
        // the export was made in, so a Persian export needs no translation step.
        LocaleHelper.setLanguage(ctx, "fa");
        Context fa = LocaleHelper.wrap(ctx);
        Transaction t = new Transaction("Tejarat", "01350000000", DATE_2026, 1_000_000L, "sig", "content-fa");
        String row = line(CsvExport.csv(fa, Arrays.asList(t),
            withChannels(channelsOf(t, "سامانه پل (پرداخت لحظه ای)"))), 1);

        assertEquals("سامانه پل (پرداخت لحظه‌ای)", parse(row).get(11));
    }

    @Test public void row_uncaptionableStoredChannel_leavesTheCellEmpty() {
        // A channel this build has no caption for is dropped rather than written out as a raw
        // fragment of a bank message, which would read as though the app had understood it.
        Transaction t = new Transaction("Tejarat", "01350000000", DATE_2026, 1_000_000L, "sig", "content-hash");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t),
            withChannels(channelsOf(t, "درگاه اینترنتی"))), 1);

        assertEquals("", parse(row).get(11));
    }

    @Test public void row_channelsWithoutAMatchingTransaction_changeNothing() {
        // A channel left behind by a movement that is no longer in the history is never written into
        // another row: rows are joined strictly by the transaction identity.
        Transaction t = new Transaction("Tejarat", "01350000000", DATE_2026, -220_000L, "sig", "content-hash");
        Transaction other = new Transaction("Tejarat", "01350000000", DATE_2024, -220_000L, "sig", "other-hash");
        String csv = CsvExport.csv(ctx, Arrays.asList(t), withChannels(channelsOf(other, SHETAB)));

        assertEquals("", parse(line(csv, 1)).get(11));
    }

    @Test public void row_carryingOnlyOneKindOfText_leavesTheOtherColumnsEmpty() {
        // Each store is independent of the others all the way into the file: a channel with no note
        // and no reason, a note with no channel, and so on.
        Transaction t = new Transaction("Tejarat", "01350000000", DATE_2026, -220_000L, "sig", "content-hash");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), withChannels(channelsOf(t, SHETAB))), 1);

        List<String> cells = parse(row);
        assertEquals("", cells.get(9));
        assertEquals("", cells.get(10));
        assertEquals("Shetab", cells.get(11));
    }

    // -----------------------------------------------------------------------
    // Unaccounted money in the exported file
    // -----------------------------------------------------------------------

    /** A movement plus the gap its later statement proved, at two known instants. */
    private static final long DATE_2026_LATER = 1767398400000L; // 2026-01-03T00:00:00Z

    @Test public void csv_unaccounted_isExportedAsItsOwnRow() {
        Transaction t = new Transaction("bank_melli", "910251846", DATE_2026, 1_250_000L, "sig");
        Residual gap = new Residual("bank_melli", "910251846", DATE_2026, DATE_2026_LATER,
            -2_000_000L, 2);
        String csv = CsvExport.csv(ctx, Arrays.asList(t), Arrays.asList(gap), noText());
        assertEquals(3, csv.split("\n").length);

        List<String> cells = parse(line(csv, 2));
        assertEquals(13, cells.size());
        assertEquals(CsvExport.KIND_UNACCOUNTED, cells.get(12));
        // It carries the same amount columns a movement does, because it moves the totals the same
        // way — a spreadsheet sum over the file has to reconcile with the app and the bank.
        assertEquals("-2000000", cells.get(5));
        assertEquals("2026-01-03T00:00:00Z", cells.get(2));
        assertEquals(BankRules.displayName(ctx, "bank_melli"), cells.get(0));
        // …but there is no message behind it, so there is no note and nothing the bank stated — no
        // reason and no channel — to carry, and no content to claim.
        assertEquals("", cells.get(9));
        assertEquals("", cells.get(10));
        assertEquals("", cells.get(11));
    }

    @Test public void csv_movementAndUnaccounted_interleaveByDate() {
        // The exported file has to read as the timeline the user saw, so the gap lands on its own
        // date rather than being appended after everything else.
        Transaction before = new Transaction("bank_melli", "1", DATE_2026, 1_000_000L, "a");
        Transaction between = new Transaction("bank_melli", "1", DATE_2026_LATER, -1_000_000L, "b");
        Residual gap = new Residual("bank_melli", "1", DATE_2026, DATE_2026_LATER, -2_000_000L, 1);
        String csv = CsvExport.csv(ctx, Arrays.asList(between, before), Arrays.asList(gap),
            noText());
        assertEquals("before", CsvExport.KIND_MOVEMENT, parse(line(csv, 1)).get(12));
        assertEquals("gap", CsvExport.KIND_UNACCOUNTED, parse(line(csv, 2)).get(12));
        assertEquals("statement", CsvExport.KIND_MOVEMENT, parse(line(csv, 3)).get(12));
        assertEquals("2026-01-01T00:00:00Z", parse(line(csv, 1)).get(2));
        assertEquals("2026-01-03T00:00:00Z", parse(line(csv, 2)).get(2));
        assertEquals("2026-01-03T00:00:00Z", parse(line(csv, 3)).get(2));
    }

    @Test public void csv_unaccountedAtTheSameInstant_leadsTheStatement() {
        // Same rule as the screen: the gap reads before the statement that proves it.
        Transaction statement = new Transaction("bank_melli", "1", DATE_2026_LATER, -1_000_000L, "s");
        Residual gap = new Residual("bank_melli", "1", DATE_2026, DATE_2026_LATER, -2_000_000L, 1);
        String csv = CsvExport.csv(ctx, Arrays.asList(statement), Arrays.asList(gap),
            noText());
        assertEquals(CsvExport.KIND_UNACCOUNTED, parse(line(csv, 1)).get(12));
        assertEquals(CsvExport.KIND_MOVEMENT, parse(line(csv, 2)).get(12));
    }

    @Test public void csv_unaccounted_onlyRows_isStillAValidFile() {
        Residual gap = new Residual("bank_melli", "910251846", DATE_2026, DATE_2026_LATER,
            -2_000_000L, 2);
        String csv = CsvExport.csv(ctx, new ArrayList<Transaction>(), Arrays.asList(gap),
            noText());
        assertTrue(csv.startsWith("\uFEFF"));
        assertEquals(2, csv.split("\n").length);
        assertEquals(CsvExport.KIND_UNACCOUNTED, parse(line(csv, 1)).get(12));
    }

    @Test public void csv_unaccounted_accountLessRow_leavesTheAccountCellEmpty() {
        // A bank that states no account number exports an empty cell, exactly as a movement from the
        // same messages does, so the two kinds stay comparable in a spreadsheet.
        Residual gap = new Residual("bank_melli", null, DATE_2026, DATE_2026_LATER, -2_000_000L, 1);
        String csv = CsvExport.csv(ctx, new ArrayList<Transaction>(), Arrays.asList(gap),
            noText());
        List<String> cells = parse(line(csv, 1));
        assertEquals("", cells.get(1));
        assertEquals(CsvExport.KIND_UNACCOUNTED, cells.get(12));
    }

    @Test public void csv_nullResiduals_behavesAsThePlainExport() {
        Transaction t = new Transaction("bank_melli", "910251846", DATE_2026, 1_250_000L, "sig");
        assertEquals(CsvExport.csv(ctx, Arrays.asList(t), noText()),
            CsvExport.csv(ctx, Arrays.asList(t), null, noText()));
    }

    @Test public void csv_unaccountedAmountsSumToTheDisplayedTotal() {
        // The whole point of exporting the gap: the file's raw rial column adds up to the same net
        // the history screen shows, so the user can reconcile the two without second-guessing it.
        Transaction t = new Transaction("bank_melli", "1", DATE_2026, 1_250_000L, "sig");
        Residual gap = new Residual("bank_melli", "1", DATE_2026, DATE_2026_LATER, -2_000_000L, 1);
        String csv = CsvExport.csv(ctx, Arrays.asList(t), Arrays.asList(gap), noText());
        long sum = 0;
        for (int i = 1; i < csv.split("\n").length; i++) {
            sum += Long.parseLong(parse(line(csv, i)).get(5));
        }
        assertEquals(-750_000L, sum);
    }
}