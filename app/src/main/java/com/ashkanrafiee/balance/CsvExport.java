package com.ashkanrafiee.balance;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the UTF-8 CSV export of the transaction history. The columns stay machine-readable — an
 * ISO-8601 UTC timestamp and the raw signed rial figure — while extra human-friendly columns carry
 * the date in the active calendar, the time of day and the amount exactly as the app displays it
 * (including the chosen currency's unit label). Only RFC-4180 quoting is applied; the caller writes
 * the text through the Storage Access Framework, so nothing leaves the device until the user picks
 * a location.
 */
final class CsvExport {
    /** The fixed, unlocalized column set: spreadsheet tools and scripts must agree on the shape of
     *  the file regardless of the app's language. */
    static final String[] HEADER = {
        "bank", "account", "date", "date_local", "time", "amount_rial", "amount_display", "currency",
        "note"
    };

    private CsvExport() {}

    /** The CSV text: the header row, then one row per transaction in chronological order (oldest
     *  first). The caller's list is never mutated. {@code notes} carries the per-transaction note map
     *  from {@link BalanceData#readNotes}, joined by the transaction identity, so the note column
     *  stays empty when the caller has no notes loaded. */
    static String csv(Context context, List<Transaction> txs, Map<String, String> notes) {
        boolean iran = RegionHelper.isIran(context);
        Calendar calendar = Calendar.getInstance(Locale.getDefault());
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        List<Transaction> sorted = new ArrayList<>(txs);
        sorted.sort(Comparator.comparingLong(t -> t.date));

        StringBuilder out = new StringBuilder();
        appendRow(out, HEADER);
        for (Transaction t : sorted) {
            out.append('\n');
            appendRow(out, cells(context, t, notes, iran, calendar, time, iso));
        }
        return out.toString();
    }

    private static String[] cells(Context context, Transaction t, Map<String, String> notes,
            boolean iran, Calendar calendar, SimpleDateFormat time, SimpleDateFormat iso) {
        calendar.setTimeInMillis(t.date);
        CalDate local = CalDate.fromGregorian(
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), iran);
        String note = notes == null ? null : notes.get(BalanceData.noteKey(t));
        return new String[]{
            BankRules.displayName(context, t.bank),
            t.account == null ? "" : t.account,
            iso.format(new Date(t.date)),
            local.year + "/" + local.month + "/" + local.day,
            time.format(new Date(t.date)),
            String.valueOf(t.amount),
            CurrencyHelper.amount(context, t.amount),
            CurrencyHelper.label(context),
            note == null ? "" : note
        };
    }

    /** RFC-4180 escaping: a field containing a comma, quote or line break is wrapped in quotes with
     *  each internal quote doubled. */
    static String escape(String s) {
        if (s == null) return "";
        if (!s.contains(",") && !s.contains("\"") && !s.contains("\n") && !s.contains("\r")) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void appendRow(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append(escape(cells[i]));
        }
    }
}