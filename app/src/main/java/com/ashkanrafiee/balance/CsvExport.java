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
 * (including the chosen currency's unit label), plus the per-transaction note and reason. Only RFC-4180
 * quoting is applied; the caller writes the text through the Storage Access Framework, so nothing
 * leaves the device until the user picks a location.
 *
 * <p>The {@code note} column carries the user's own words and the {@code reason} column the bank's:
 * they are separate stores, so a movement that has both shows both, and neither ever stands in for
 * the other. An unaccounted row has neither — there is no message behind it for either to describe.
 *
 * <p>The trailing {@code kind} column says what each row actually is. A {@code movement} row came
 * from one bank message; an {@code unaccounted} row is money the bank reported moving for which no
 * message ever arrived, so it is proven by the balance statements around it and must not be read as
 * a transaction the user can look up. Both kinds carry a real signed amount, and both are included
 * in the totals, so a spreadsheet sum reconciles with the app and with the bank.
 *
 * <p>The text starts with a UTF-8 byte-order mark. Persian (and generally non-ASCII) text — the
 * notes above all — is written as real UTF-8, and without a BOM most spreadsheet tools (Excel first
 * among them) assume their system's old single-byte encoding and render those bytes as garbage. The
 * BOM makes every UTF-8-aware consumer read the file correctly.
 */
final class CsvExport {
    /** The fixed, unlocalized column set: spreadsheet tools and scripts must agree on the shape of
     *  the file regardless of the app's language. */
    static final String[] HEADER = {
        "bank", "account", "date", "date_local", "time", "amount_rial", "amount_display", "currency",
        "note", "reason", "kind"
    };

    /** {@code kind} value for a row parsed from one bank message. */
    static final String KIND_MOVEMENT = "movement";
    /** {@code kind} value for money that moved with no message to back it. */
    static final String KIND_UNACCOUNTED = "unaccounted";

    private CsvExport() {}

    /** The CSV text: a UTF-8 BOM, the header row, then one row per transaction in chronological
     *  order (oldest first), with any unaccounted money interleaved at the date it is placed. The
     *  caller's lists are never mutated. {@code notes} and {@code reasons} carry the per-transaction
     *  note and reason maps from {@link BalanceData#readNotes} and {@link BalanceData#readReasons},
     *  joined by the transaction identity, so either column stays empty when the caller has none
     *  loaded. */
    static String csv(Context context, List<Transaction> txs, Map<String, String> notes,
            Map<String, String> reasons) {
        return csv(context, txs, null, notes, reasons);
    }

    static String csv(Context context, List<Transaction> txs, List<Residual> residuals,
            Map<String, String> notes, Map<String, String> reasons) {
        boolean iran = RegionHelper.isIran(context);
        Calendar calendar = Calendar.getInstance(Locale.getDefault());
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        // One oldest-first stream of both kinds, so the file reads as the timeline the user saw.
        // Ties put unaccounted money first, matching the screen: the gap reads before the statement
        // that proves it. The tie-break asks only whether the residual is absent, never compares the
        // residual objects, so two gaps on one day order as equal rather than breaking the contract.
        List<Line> lines = new ArrayList<>();
        for (Transaction t : txs) lines.add(new Line(t.date, t, null));
        if (residuals != null) for (Residual r : residuals) lines.add(new Line(r.toDate, null, r));
        lines.sort((a, b) -> {
            int byDate = Long.compare(a.date, b.date);
            if (byDate != 0) return byDate;
            if ((a.residual == null) != (b.residual == null)) return a.residual == null ? 1 : -1;
            return 0;
        });

        StringBuilder out = new StringBuilder("\uFEFF");
        appendRow(out, HEADER);
        for (Line l : lines) {
            out.append('\n');
            appendRow(out, l.residual != null
                ? residualCells(context, l.residual, iran, calendar, time, iso)
                : cells(context, l.tx, notes, reasons, iran, calendar, time, iso));
        }
        return out.toString();
    }

    /** One exported line: a parsed movement, or unaccounted money. */
    private static final class Line {
        final long date;
        final Transaction tx;
        final Residual residual;

        Line(long date, Transaction tx, Residual residual) {
            this.date = date;
            this.tx = tx;
            this.residual = residual;
        }
    }

    /** The row for unaccounted money. It carries the same amount columns as a movement, because it
     *  moves the totals the same way; the empty note and reason cells are the honest part, since
     *  there is no message here for either to describe. */
    private static String[] residualCells(Context context, Residual r, boolean iran,
            Calendar calendar, SimpleDateFormat time, SimpleDateFormat iso) {
        calendar.setTimeInMillis(r.toDate);
        CalDate local = CalDate.fromGregorian(
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), iran);
        return new String[]{
            BankRules.displayName(context, r.bank),
            r.account == null ? "" : r.account,
            iso.format(new Date(r.toDate)),
            local.year + "/" + local.month + "/" + local.day,
            time.format(new Date(r.toDate)),
            String.valueOf(r.amount),
            CurrencyHelper.amount(context, r.amount),
            CurrencyHelper.label(context),
            "",
            "",
            KIND_UNACCOUNTED
        };
    }

    private static String[] cells(Context context, Transaction t, Map<String, String> notes,
            Map<String, String> reasons, boolean iran, Calendar calendar, SimpleDateFormat time,
            SimpleDateFormat iso) {
        calendar.setTimeInMillis(t.date);
        CalDate local = CalDate.fromGregorian(
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), iran);
        String note = notes == null ? null : notes.get(BalanceData.noteKey(t));
        String reason = reasons == null ? null : BankRules.reasonCaption(context,
            reasons.get(BalanceData.noteKey(t)));
        return new String[]{
            BankRules.displayName(context, t.bank),
            t.account == null ? "" : t.account,
            iso.format(new Date(t.date)),
            local.year + "/" + local.month + "/" + local.day,
            time.format(new Date(t.date)),
            String.valueOf(t.amount),
            CurrencyHelper.amount(context, t.amount),
            CurrencyHelper.label(context),
            note == null ? "" : note,
            reason == null ? "" : reason,
            KIND_MOVEMENT
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