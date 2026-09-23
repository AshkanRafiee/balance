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
        String csv = CsvExport.csv(ctx, new ArrayList<>(), java.util.Collections.<String, String>emptyMap());
        assertEquals("bank,account,date,date_local,time,amount_rial,amount_display,currency,note",
            line(csv, 0));
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
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), java.util.Collections.<String, String>emptyMap()), 1);
        List<String> cells = parse(row);
        assertEquals(9, cells.size());
        assertEquals(BankRules.displayName(ctx, "bank_melli"), cells.get(0));
        assertEquals("910251846", cells.get(1));
        assertEquals("2026-01-01T00:00:00Z", cells.get(2));
        assertEquals("1250000", cells.get(5));
        assertEquals("125,000", cells.get(6));
        assertEquals("Toman", cells.get(7));
        assertEquals("", cells.get(8));
    }

    @Test public void row_accountMissing_leavesTheAccountCellEmpty() {
        Transaction t = new Transaction("bank_tejarat", null, DATE_2026, -500L, "sig");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), java.util.Collections.<String, String>emptyMap()), 1);
        assertTrue(row.startsWith(BankRules.displayName(ctx, "bank_tejarat") + ",,"));
    }

    @Test public void row_hostileAccount_isQuotedWithDoubledQuotes() {
        Transaction t = new Transaction("bank_melli", "91,\"0", DATE_2026, 500L, "sig");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), java.util.Collections.<String, String>emptyMap()), 1);
        assertTrue(row.contains("\"91,\"\"0\""));
    }

    @Test public void csv_unsortedInputs_writesOldestFirst() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(new Transaction("bank_melli", null, DATE_2026, 111L, "a"));
        txs.add(new Transaction("bank_tejarat", null, DATE_2024, 222L, "b"));
        txs.add(new Transaction("bank_melli", null, DATE_2024, 333L, "c"));
        String csv = CsvExport.csv(ctx, txs, java.util.Collections.<String, String>emptyMap());
        assertTrue(line(csv, 1).contains(",222,"));
        assertTrue(line(csv, 2).contains(",333,"));
        assertTrue(line(csv, 3).contains(",111,"));
    }

    @Test public void csv_persianToman_formatsDisplayAndUnitInFarsi() {
        LocaleHelper.setLanguage(ctx, "fa");
        Context fa = LocaleHelper.wrap(ctx);
        Transaction t = new Transaction("bank_melli", null, DATE_2026, 1_250_000L, "sig");
        String row = line(CsvExport.csv(fa, Arrays.asList(t), java.util.Collections.<String, String>emptyMap()), 1);
        assertTrue(row.contains("۱۲۵٬۰۰۰"));
        assertTrue(row.contains(",تومان,"));
    }

    @Test public void row_withNote_writesNoteAndEscapesItRfc4180() {
        Transaction t = new Transaction("bank_melli", null, DATE_2026, 1_250_000L, "sig",
            "content-hash");
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        notes.put(BalanceData.noteKey(t), "picked up from the cashier, watch out, \"late\"");
        String row = line(CsvExport.csv(ctx, Arrays.asList(t), notes), 1);
        // The raw row carries the RFC-4180 escaping (comma and quote wrapped, quote doubled)…
        assertTrue(row.contains("\"picked up from the cashier, watch out, \"\"late\"\"\""));
        // …and a plain parser sees the original text unquoted in the last cell.
        List<String> cells = parse(row);
        assertEquals(9, cells.size());
        assertEquals("picked up from the cashier, watch out, \"late\"", cells.get(8));
    }
}