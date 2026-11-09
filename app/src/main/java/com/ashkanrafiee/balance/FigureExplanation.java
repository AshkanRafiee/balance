package com.ashkanrafiee.balance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reusable, read-only figure details. Present through the host's tracked-dialog mechanism.
 * isActive must reject paused/destroyed/locked UI. All storage and crypto run on a worker. */
final class FigureExplanation {
    private FigureExplanation() { }

    private interface Details { String load(Context context) throws Exception; }

    static void showBalance(Activity activity, Bank bank, Consumer<AlertDialog> presenter,
            BooleanSupplier isActive) {
        Bank snapshot = copy(bank);
        String header = identity(activity, snapshot.name, snapshot.account) + "\n"
            + money(activity, snapshot.amount) + "\n"
            + activity.getString(R.string.figure_recorded, time(activity, snapshot.date)) + "\n\n"
            + activity.getString(R.string.figure_balance_caution);
        show(activity, header, presenter, isActive, context -> source(context,
            FigureEvidence.balance(context, snapshot), snapshot.date), null, null);
    }

    /** The list must be the exact account rows contributing to the displayed total. The helper
     * snapshots their values, sums with arbitrary precision and never queries a live bank. */
    static void showTotal(Activity activity, List<Bank> banks, Consumer<AlertDialog> presenter,
            BooleanSupplier isActive) {
        List<Bank> snapshot = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        for (Bank bank : banks) {
            snapshot.add(copy(bank));
            total = total.add(BigInteger.valueOf(bank.amount));
        }
        String header = activity.getString(R.string.figure_amount, number(activity, total))
            + "\n\n" + activity.getString(R.string.figure_total_basis);
        show(activity, header, presenter, isActive, context -> {
            StringBuilder out = new StringBuilder();
            for (Bank bank : snapshot) {
                paragraph(out, identity(context, bank.name, bank.account));
                out.append('\n').append(money(context, bank.amount)).append('\n')
                    .append(context.getString(R.string.figure_recorded, time(context, bank.date)));
                try { paragraph(out, source(context, FigureEvidence.balance(context, bank), bank.date)); }
                catch (Exception unavailable) { paragraph(out, context.getString(R.string.figure_source_error)); }
            }
            return out.toString();
        }, null, null);
    }

    static void showTransaction(Activity activity, Transaction transaction,
            Consumer<AlertDialog> presenter, BooleanSupplier isActive, Runnable edit) {
        String header = identity(activity, transaction.bank, transaction.account) + "\n"
            + money(activity, transaction.amount) + "\n"
            + activity.getString(R.string.figure_recorded, time(activity, transaction.date));
        show(activity, header, presenter, isActive, context -> {
            StringBuilder out = new StringBuilder(context.getString(R.string.figure_sourced));
            if (transaction.balance != null) paragraph(out, context.getString(
                R.string.figure_reported_balance, number(context, transaction.balance)));
            String key = BalanceData.noteKey(transaction);
            try (MetadataStore.LookupSession metadata = MetadataStore.LookupSession.open(context)) {
                String reason = BankRules.reasonCaption(context, metadata.text(MetadataStore.REASONS, key));
                String channel = BankRules.channelCaption(context, metadata.text(MetadataStore.CHANNELS, key));
                String merchant = metadata.text(MetadataStore.MERCHANTS, key);
                if (reason != null) paragraph(out, context.getString(R.string.row_fact_reason, reason));
                if (channel != null) paragraph(out, context.getString(R.string.row_fact_channel, channel));
                if (merchant != null) paragraph(out, context.getString(R.string.row_fact_merchant,
                    ScanDiagnostics.sanitizeForReport(merchant)));
                try { paragraph(out, source(context, FigureEvidence.transaction(context, transaction), transaction.date)); }
                catch (Exception unavailable) { paragraph(out, context.getString(R.string.figure_source_error)); }
                paragraph(out, context.getString(R.string.figure_personal));
                String note = metadata.text(MetadataStore.NOTES, key);
                List<String> tags = metadata.tags(key);
                if (note != null) paragraph(out, context.getString(R.string.row_fact_note, note));
                if (!tags.isEmpty()) paragraph(out, context.getString(R.string.row_fact_tags,
                    android.text.TextUtils.join(", ", tags)));
                if (note == null && tags.isEmpty()) paragraph(out, context.getString(R.string.figure_no_annotation));
            }
            return out.toString();
        }, edit, activity.getString(R.string.figure_edit));
    }

    static void showResidual(Activity activity, Residual residual, Consumer<AlertDialog> presenter,
            BooleanSupplier isActive, Runnable edit, String editLabel) {
        String header = identity(activity, residual.bank, residual.account) + "\n"
            + money(activity, residual.amount) + "\n"
            + activity.getString(R.string.figure_bracket, time(activity, residual.fromDate),
                time(activity, residual.toDate));
        show(activity, header, presenter, isActive, context -> {
            StringBuilder out = new StringBuilder(context.getString(R.string.figure_sourced));
            FigureEvidence.Bracket bracket = FigureEvidence.residual(context, residual);
            paragraph(out, bracket == null ? context.getString(R.string.figure_calculation_unavailable)
                : context.getString(R.string.figure_calculation, number(context, bracket.after),
                    number(context, bracket.before), number(context, bracket.received), number(context, residual.amount)));
            paragraph(out, context.getString(R.string.figure_residual_caution));
            paragraph(out, context.getString(R.string.figure_personal));
            String note = MetadataStore.getNote(context, BalanceData.residualNoteKey(residual));
            paragraph(out, note == null ? context.getString(R.string.figure_no_annotation)
                : context.getString(R.string.row_fact_note, note));
            return out.toString();
        }, edit, editLabel);
    }

    private static void show(Activity activity, String header, Consumer<AlertDialog> presenter,
            BooleanSupplier isActive, Details details, Runnable edit, String editLabel) {
        if (!usable(activity, isActive)) return;
        AtomicBoolean attached = new AtomicBoolean(true);
        TextView text = new TextView(activity);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding / 2, padding, padding);
        text.setTextSize(16);
        text.setTypeface(Fonts.text(activity));
        text.setText(header + "\n\n" + activity.getString(R.string.history_loading));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.figure_title).setView(scroll).setPositiveButton(android.R.string.ok, null);
        if (edit != null) builder.setNeutralButton(editLabel, (dialog, which) -> {
            if (usable(activity, isActive)) edit.run();
        });
        AlertDialog dialog = builder.create();
        presenter.accept(dialog);
        if (!dialog.isShowing()) return;
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { attached.set(false); }
        });
        Context context = activity; // Keep the screen's selected locale for worker-built text.
        new Thread(() -> {
            String body;
            try {
                synchronized (BalanceData.class) {
                    if (!attached.get() || LockManager.isSessionLocked()) return;
                    body = details.load(context);
                }
            } catch (Exception unavailable) {
                // No raw source or personal metadata in logs, including exception messages.
                body = context.getString(R.string.figure_source_error);
            }
            String completed = body;
            activity.runOnUiThread(() -> {
                if (attached.get() && dialog.isShowing() && usable(activity, isActive))
                    text.setText(header + "\n\n" + completed);
            });
        }, "figure-details").start();
    }

    private static boolean usable(Activity activity, BooleanSupplier isActive) {
        return !activity.isDestroyed() && !activity.isFinishing() && !LockManager.isSessionLocked()
            && isActive.getAsBoolean();
    }

    private static String source(Context context, FigureEvidence.SourceMatch match, long recorded) {
        SourceStore.Source source = match.unique();
        if (source == null) return context.getString(match.ambiguous
            ? R.string.figure_source_ambiguous : R.string.figure_source_missing);
        StringBuilder out = new StringBuilder(context.getString(R.string.figure_arrival,
            time(context, source.arrivalTime)));
        // A one-millisecond probe distinguishes a parser fallback from a genuine message date,
        // even when the parsed bank time happens to equal the exact SMS arrival.
        long parsed = MessageDate.eventTime(source.rawBody, source.arrivalTime, BankRules.calendar(source.bank));
        boolean stated = source.arrivalTime < Long.MAX_VALUE && MessageDate.eventTime(source.rawBody,
            source.arrivalTime + 1, BankRules.calendar(source.bank)) == parsed && parsed == recorded;
        paragraph(out, stated ? context.getString(R.string.figure_event, time(context, parsed))
            : context.getString(R.string.figure_event_unknown));
        paragraph(out, context.getString(R.string.figure_time_caution));
        paragraph(out, context.getString(R.string.figure_source_body));
        out.append('\n').append(FigureEvidence.safeBody(source));
        return out.toString();
    }

    private static Bank copy(Bank bank) {
        return new Bank(bank.name, bank.amount, bank.date, bank.sender, bank.account);
    }

    private static String identity(Context context, String bank, String account) {
        return BankRules.displayName(context, bank) + "\n" + (account == null
            ? context.getString(R.string.figure_account_unknown)
            : context.getString(R.string.account_label) + " " + account);
    }

    static String money(Context context, long amount) {
        return context.getString(R.string.figure_amount, number(context, amount));
    }

    private static String number(Context context, Object amount) {
        return NumberFormat.getIntegerInstance(locale(context)).format(amount);
    }

    private static String time(Context context, long date) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale(context))
            .format(new java.util.Date(date));
    }

    private static Locale locale(Context context) {
        return LocaleHelper.isPersian(context) ? new Locale("fa") : Locale.US;
    }

    private static void paragraph(StringBuilder out, String value) {
        if (out.length() > 0) out.append("\n\n");
        out.append(value);
    }
}
