package com.ashkanrafiee.balance;

import android.content.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;

/**
 * Builds the UTF-8 CSV export of the transaction history. The columns stay machine-readable — an
 * ISO-8601 UTC timestamp and the raw signed rial figure — while extra human-friendly columns carry
 * the date in the active calendar, the time of day and the amount exactly as the app displays it
 * (including the chosen currency's unit label), plus the per-transaction note and whatever the bank
 * stated about the movement. Only RFC-4180 quoting is applied; the caller writes the text through the
 * Storage Access Framework, so nothing leaves the device until the user picks a location.
 *
 * <p>The {@code note} column carries the user's own words, the {@code tags} column carries the user's
 * ordered multi-value labels as a JSON array, and the {@code reason} and {@code channel} columns carry
 * the bank's words. They are separate stores, so a movement that has any combination shows all of them,
 * and none ever stands in for another. An unaccounted row has none — there is no message behind it for
 * any of them to describe.
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
    /** The number of decrypted rows the store may retain while a streaming export is running. */
    static final int STREAM_PAGE_SIZE = 256;

    /** The fixed, unlocalized column set: spreadsheet tools and scripts must agree on the shape of
     *  the file regardless of the app's language. */
    static final String[] HEADER = {
        "bank", "account", "date", "date_local", "time", "amount_rial", "amount_display", "currency",
        "note", "reason", "channel", "kind", "tags"
    };

    /** {@code kind} value for a row parsed from one bank message. */
    static final String KIND_MOVEMENT = "movement";
    /** {@code kind} value for money that moved with no message to back it. */
    static final String KIND_UNACCOUNTED = "unaccounted";

    /** The per-transaction text an export carries, one map per kind of text, each keyed by the
     *  transaction identity so the columns stay in step with the rows. Grouped in one place because
     *  they are the four metadata stores, read together from {@link BalanceData#readNotes},
     *  {@link BalanceData#readReasons}, {@link BalanceData#readChannels} and {@link BalanceData#readTags}
     *  and written to the same file. */
    static final class Text {
        final Map<String, String> notes;
        final Map<String, String> reasons;
        final Map<String, String> channels;
        final Map<String, List<String>> tags;

        Text(Map<String, String> notes, Map<String, String> reasons, Map<String, String> channels) {
            this(notes, reasons, channels, null);
        }

        Text(Map<String, String> notes, Map<String, String> reasons, Map<String, String> channels,
                Map<String, List<String>> tags) {
            this.notes = notes;
            this.reasons = reasons;
            this.channels = channels;
            this.tags = tags;
        }

        /** The "caller has none of it loaded" case: every text column comes out empty. */
        static Text none() {
            return new Text(null, null, null, null);
        }
    }

    private CsvExport() {}

    /** The CSV text: a UTF-8 BOM, the header row, then one row per transaction in chronological
     *  order (oldest first), with any unaccounted money interleaved at the date it is placed. The
     *  caller's lists are never mutated. {@code text} carries the per-transaction note, reason and
     *  channel maps, any of which may be null, so each of those columns stays empty when the caller
     *  has none loaded. */
    static String csv(Context context, List<Transaction> txs, Text text) {
        return csv(context, txs, null, text);
    }

    static String csv(Context context, List<Transaction> txs, List<Residual> residuals, Text text) {
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
                : cells(context, l.tx, text, iran, calendar, time, iso));
        }
        return out.toString();
    }

    /**
     * Writes the complete transaction store to a UTF-8 CSV destination without building a history-
     * sized list or a history-sized output string. Rows are emitted in the store cursor's order.
     * Returning normally means the writer was flushed after the last row; store and writer failures
     * are propagated to the caller, so a partial destination is never reported as a completed export.
     * The writer remains owned by the caller and is not closed.
     */
    static void write(Context context, Writer writer, Text text) throws Exception {
        write(context, STREAM_PAGE_SIZE, writer, text);
    }

    /** Same as {@link #write(Context, Writer, Text)}, with an explicit bounded store page size. */
    static void write(Context context, int pageSize, Writer writer, Text text) throws Exception {
        if (writer == null) throw new NullPointerException("writer");
            write(context, visitor -> TransactionStore.forEach(context, pageSize, visitor),
            writer, text);
    }

    /**
     * Writes transactions supplied by a synchronous visitor source. This overload lets a caller
     * apply a streaming scope before handing rows to the CSV writer; the source's order is retained.
     * A source backed by {@link TransactionStore#forEach} keeps the same bounded-memory and failure
     * behavior as the store overload. The writer remains owned by the caller and is not closed.
     */
    static void write(Context context, TransactionStore.StreamSource source, Writer writer,
            Text text) throws Exception {
        if (source == null) throw new NullPointerException("source");
        if (writer == null) throw new NullPointerException("writer");

        boolean iran = RegionHelper.isIran(context);
        Calendar calendar = Calendar.getInstance(Locale.getDefault());
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        writer.write('\uFEFF');
        appendRow(writer, HEADER);
        source.forEach(transaction -> {
            writer.write('\n');
            appendRow(writer, cells(context, transaction, text, iran, calendar, time, iso));
        });
        // A successful flush is the completion point. In particular, do not flush from a catch or
        // finally block after a store failure has interrupted the source.
        writer.flush();
    }

    /** Same source-driven export as the writer overload, encoded as UTF-8 and left open. */
    static void write(Context context, TransactionStore.StreamSource source, OutputStream output,
            Text text) throws Exception {
        if (output == null) throw new NullPointerException("output");
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        write(context, source, writer, text);
    }

    /** Writes the complete transaction store as UTF-8 and leaves the supplied stream open. */
    static void write(Context context, OutputStream output, Text text) throws Exception {
        write(context, STREAM_PAGE_SIZE, output, text);
    }

    /** Same as {@link #write(Context, OutputStream, Text)}, with an explicit bounded page size. */
    static void write(Context context, int pageSize, OutputStream output, Text text)
            throws Exception {
        if (output == null) throw new NullPointerException("output");
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        write(context, pageSize, writer, text);
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
     *  moves the totals the same way; the empty note, reason and channel cells are the honest part,
     *  since there is no message here for any of them to describe. */
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
            "",
            KIND_UNACCOUNTED,
            ""
        };
    }

    private static String[] cells(Context context, Transaction t, Text text, boolean iran,
            Calendar calendar, SimpleDateFormat time, SimpleDateFormat iso) {
        calendar.setTimeInMillis(t.date);
        CalDate local = CalDate.fromGregorian(
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), iran);
        String key = BalanceData.noteKey(t);
        String note = text.notes == null ? null : text.notes.get(key);
        // What the bank stated is stored in its own words and captioned here, in the language the file
        // is written in, for the same reason the reason column is: the reader is a person with a
        // spreadsheet, not something that can resolve a Persian term.
        String reason = text.reasons == null ? null : BankRules.reasonCaption(context, text.reasons.get(key));
        String channel = text.channels == null ? null
            : BankRules.channelCaption(context, text.channels.get(key));
        String tagJson = "";
        if (text.tags != null) {
            List<String> values = text.tags.get(key);
            if (values != null && !values.isEmpty()) tagJson = new JSONArray(values).toString();
        }
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
            channel == null ? "" : channel,
            KIND_MOVEMENT,
            tagJson
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

    private static void appendRow(Writer out, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.write(',');
            out.write(escape(cells[i]));
        }
    }
}
