package com.ashkanrafiee.balance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** User-defined commitments: loans, debts, subscriptions and anything else the user names.
 *
 *  <p>The app never categorizes — the name is the user's own words and the grounding is
 *  technical: the schedule below, the per-month and total figures, and (when enabled) the
 *  reminders. Amounts are signed: paying out is negative, receiving is positive. The screen
 *  shows every overdue due plus the coming twelve months grouped under their years, so an
 *  open-ended commitment reads as a continuing series rather than an infinite sum, and the
 *  totals say exactly which window they cover. */
public final class CommitmentsActivity extends Activity {

    /** How many upcoming months the screen covers, starting with the current one. */
    static final int WINDOW_MONTHS = 12;
    /** How many overdue rows render before the rest collapse into a "+N older" line. */
    static final int MAX_OVERDUE_ROWS = 50;

    /** One due day of one commitment on screen. */
    static final class Row {
        final Commitment commitment;
        final long date;
        final boolean settled;
        Row(Commitment commitment, long date, boolean settled) {
            this.commitment = commitment;
            this.date = date;
            this.settled = settled;
        }
    }

    /** One calendar month of dues, with its own totals. */
    static final class MonthGroup {
        final int year;
        final int month;
        long pay;
        long receive;
        final List<Row> rows = new ArrayList<>();
        MonthGroup(int year, int month) {
            this.year = year;
            this.month = month;
        }
    }

    /** Everything the screen renders: the overdue dues and the windowed months, with the
     *  totals over exactly what is shown (settled dues are displayed dimmed but never summed). */
    static final class Summary {
        final List<Row> overdue = new ArrayList<>();
        final List<MonthGroup> months = new ArrayList<>();
        long overduePay;
        long overdueReceive;
        long payTotal;
        long receiveTotal;
    }

    /** Groups commitments into the screen's window: every unsettled-or-not overdue due, then the
     *  next {@code upcomingMonths} calendar months with their dues. Pure and headless, so the
     *  instrumented tests pin the grouping without touching a view. */
    static Summary summarize(List<Commitment> commitments, CalendarSystem cal, long nowMs,
            int upcomingMonths) {
        Summary summary = new Summary();
        if (commitments == null || cal == null) return summary;
        long today = Commitment.startOfDay(nowMs);
        for (Commitment c : commitments) {
            if (c == null) continue;
            for (long at : Commitment.occurrences(c, cal, Math.min(c.start, today - 366L * 86400000L),
                    today - 1)) {
                if (c.isSettled(at)) continue;
                summary.overdue.add(new Row(c, at, false));
                if (c.isPayment()) summary.overduePay += c.amount;
                else summary.overdueReceive += c.amount;
            }
        }
        summary.overdue.sort((x, y) -> {
            int byDate = Long.compare(y.date, x.date);
            return byDate != 0 ? byDate : x.commitment.name.compareTo(y.commitment.name);
        });
        int[] civil = Commitment.civilDay(today, cal);
        int year = civil[0];
        int month = civil[1];
        for (int i = 0; i < upcomingMonths; i++) {
            MonthGroup group = new MonthGroup(year, month);
            long monthStart = Commitment.millisOf(year, month, 1, cal);
            long monthEnd = Commitment.millisOf(year, month, cal.daysInMonth(year, month), cal);
            for (Commitment c : commitments) {
                if (c == null) continue;
                for (long at : Commitment.occurrences(c, cal, monthStart, monthEnd)) {
                    boolean settled = c.isSettled(at);
                    group.rows.add(new Row(c, at, settled));
                    if (!settled) {
                        if (c.isPayment()) group.pay += c.amount;
                        else group.receive += c.amount;
                    }
                }
            }
            group.rows.sort((x, y) -> {
                int byDate = Long.compare(x.date, y.date);
                return byDate != 0 ? byDate : x.commitment.name.compareTo(y.commitment.name);
            });
            if (!group.rows.isEmpty()) summary.months.add(group);
            month++;
            if (month > 12) {
                month = 1;
                year++;
            }
        }
        summary.payTotal = summary.overduePay;
        summary.receiveTotal = summary.overdueReceive;
        for (MonthGroup group : summary.months) {
            summary.payTotal += group.pay;
            summary.receiveTotal += group.receive;
        }
        return summary;
    }

    private int fg, muted, accent, card, chipBg, warnBg, warnFg, negativeColor, positiveColor;
    private boolean iran;
    private boolean persian;
    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(ThemeHelper.wrap(base)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        iran = RegionHelper.isIran(this);
        persian = LocaleHelper.isPersian(this);
        fg = color(R.color.fg);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        card = color(R.color.panel);
        chipBg = color(R.color.history_chip_bg);
        warnBg = color(R.color.warn_bg);
        warnFg = color(R.color.warn);
        positiveColor = color(R.color.accent);
        negativeColor = color(R.color.negative);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        root.setOnApplyWindowInsetsListener((v, i) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x =
                    i.getInsets(android.view.WindowInsets.Type.systemBars());
                top = x.top;
                bottom = x.bottom;
            } else {
                top = i.getSystemWindowInsetTop();
                bottom = i.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(24));
            return i;
        });
        scroll.addView(root, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        long now = System.currentTimeMillis();
        List<Commitment> commitments = BalanceData.readCommitments(this);
        Summary summary = summarize(commitments, cal, now, WINDOW_MONTHS);

        TextView title = text(getString(R.string.commitments_title), 22, fg, medium());
        root.addView(title, margin(4, 4, 4, 12));

        heroCard(summary);
        addButton();

        if (commitments.isEmpty()) {
            TextView empty = text(getString(R.string.commitments_empty_hint), 14, muted);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.3f);
            root.addView(empty, margin(8, 24, 8, 0));
            return;
        }
        if (!summary.overdue.isEmpty()) overdueCard(summary);
        int lastYear = -1;
        for (MonthGroup group : summary.months) {
            if (group.year != lastYear) {
                lastYear = group.year;
                root.addView(yearHeader(group.year, yearNet(summary, lastYear)), margin(4, 20, 4, 8));
            }
            root.addView(monthCard(group), margin(0, 0, 0, 12));
        }
    }

    private long yearNet(Summary summary, int year) {
        long net = 0;
        for (MonthGroup group : summary.months)
            if (group.year == year) net += group.pay + group.receive;
        return net;
    }

    private void heroCard(Summary summary) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(rounded(card, 20));
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        hero.addView(text(getString(R.string.commitments_total), 12, muted, medium()),
            new LinearLayout.LayoutParams(-2, -2));
        long net = summary.payTotal + summary.receiveTotal;
        TextView total = bold(signedAmount(net), 32, valueColor(net));
        hero.addView(total, new LinearLayout.LayoutParams(-2, -2));
        TextView split = text(
            getString(R.string.commitments_payable) + " " + signedAmount(summary.payTotal)
                + "  ·  " + getString(R.string.commitments_receivable) + " "
                + signedAmount(summary.receiveTotal),
            13, muted);
        hero.addView(split, new LinearLayout.LayoutParams(-2, -2));
        TextView window = text(getString(R.string.commitments_window), 12, muted);
        hero.addView(window, new LinearLayout.LayoutParams(-2, -2));
        root.addView(hero, margin(0, 0, 0, 12));
    }

    private void addButton() {
        TextView add = text(getString(R.string.commitments_add), 15, Color.WHITE, medium());
        add.setGravity(Gravity.CENTER);
        add.setBackground(rounded(accent, 14));
        add.setPadding(dp(16), dp(13), dp(16), dp(13));
        add.setOnClickListener(v -> editorDialog(null));
        root.addView(add, margin(0, 0, 0, 12));
    }

    private void overdueCard(Summary summary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(warnBg, 16));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        long net = summary.overduePay + summary.overdueReceive;
        box.addView(text(getString(R.string.commitments_overdue), 15, warnFg, medium()),
            new LinearLayout.LayoutParams(-2, -2));
        box.addView(bold(signedAmount(net), 20, valueColor(net)),
            new LinearLayout.LayoutParams(-2, -2));
        int shown = Math.min(summary.overdue.size(), MAX_OVERDUE_ROWS);
        for (int i = 0; i < shown; i++) box.addView(occurrenceRow(summary.overdue.get(i)));
        if (summary.overdue.size() > shown) {
            box.addView(text(getString(R.string.commitments_older, summary.overdue.size() - shown),
                12, warnFg), new LinearLayout.LayoutParams(-2, -2));
        }
        root.addView(box, margin(0, 0, 0, 12));
    }

    private LinearLayout yearHeader(int year, long net) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = bold(yearText(year), 18, fg);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(bold(signedAmount(net), 15, valueColor(net)),
            new LinearLayout.LayoutParams(-2, -2));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private LinearLayout monthCard(MonthGroup group) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(card, 16));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        long net = group.pay + group.receive;
        TextView head = text(CalDate.monthName(group.month, iran, persian), 15, fg, medium());
        box.addView(head, new LinearLayout.LayoutParams(-2, -2));
        box.addView(bold(signedAmount(net), 19, valueColor(net)),
            new LinearLayout.LayoutParams(-2, -2));
        for (Row row : group.rows) box.addView(occurrenceRow(row));
        return box;
    }

    private LinearLayout occurrenceRow(Row row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(7), 0, dp(7));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(row.commitment.name, 14, row.settled ? muted : fg);
        info.addView(name, new LinearLayout.LayoutParams(-2, -2));
        info.addView(text(dateText(row.date), 12, muted), new LinearLayout.LayoutParams(-2, -2));
        line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(bold(signedAmount(row.commitment.amount), 15,
            row.settled ? muted : valueColor(row.commitment.amount)),
            new LinearLayout.LayoutParams(-2, -2));
        if (!row.settled) {
            TextView pay = text(row.commitment.isPayment()
                    ? getString(R.string.commitments_mark_paid)
                    : getString(R.string.commitments_mark_received),
                12, accent, medium());
            pay.setBackground(rounded(chipBg, 10));
            pay.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(10));
            pay.setOnClickListener(v -> markSettled(row));
            line.addView(pay, lp);
        }
        line.setOnClickListener(v -> editorDialog(row.commitment.id));
        return line;
    }

    private void markSettled(Row row) {
        List<Commitment> commitments = BalanceData.readCommitments(this);
        List<Commitment> kept = new ArrayList<>();
        for (Commitment c : commitments) {
            if (!c.id.equals(row.commitment.id)) {
                kept.add(c);
                continue;
            }
            if (c.frequency == Commitment.ONCE) {
                kept.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    true, c.paidThrough, c.remind, c.remindBeforeMs));
            } else {
                kept.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    c.done, Math.max(c.paidThrough, row.date), c.remind, c.remindBeforeMs));
            }
        }
        BalanceData.writeCommitments(this, kept);
        render();
    }

    // ====================================================================
    // Editor
    // ====================================================================

    private void editorDialog(String commitmentId) {
        final Commitment existing = lookupCommitment(commitmentId);
        boolean iranNow = iran;
        CalendarSystem cal = iranNow ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        int[] today = Commitment.civilDay(System.currentTimeMillis(), cal);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(4), dp(4), dp(4));

        EditText name = new EditText(this);
        name.setHint(getString(R.string.commitments_name_hint));
        if (existing != null) name.setText(existing.name);
        name.setTypeface(Fonts.text(this), Typeface.NORMAL);
        form.addView(name, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText amount = new EditText(this);
        amount.setHint(getString(R.string.commitments_amount_hint));
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (existing != null) amount.setText(String.valueOf(Math.abs(existing.amount)));
        amount.setTypeface(Fonts.text(this), Typeface.NORMAL);
        amountRow.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(amountRow, margin(0, 8, 0, 0));

        final boolean[] pay = {existing == null || existing.isPayment()};
        TextView[] directionChips = {
            text(getString(R.string.commitments_pay), 13, fg, medium()),
            text(getString(R.string.commitments_receive), 13, fg, medium())};
        final int[] directionSelected = {pay[0] ? 0 : 1};
        Runnable[] directionActions = new Runnable[2];
        for (int i = 0; i < 2; i++) {
            final int index = i;
            directionActions[i] = () -> {
                pay[0] = index == 0;
                directionSelected[0] = index;
                refreshChips(directionChips, index);
            };
        }
        form.addView(chipRow(directionChips, directionSelected[0], directionActions),
            margin(0, 8, 0, 0));

        final int[] frequency = {existing != null ? existing.frequency : Commitment.MONTHLY};
        String[] freqLabels = {
            getString(R.string.commitments_freq_once), getString(R.string.commitments_freq_daily),
            getString(R.string.commitments_freq_weekly),
            getString(R.string.commitments_freq_monthly),
            getString(R.string.commitments_freq_yearly)};
        TextView[] freqChips = new TextView[5];
        final int[] freqSelected = {frequency[0]};
        for (int i = 0; i < 5; i++) freqChips[i] = text(freqLabels[i], 13, fg, medium());
        Runnable[] freqActions = new Runnable[5];
        for (int i = 0; i < 5; i++) {
            final int index = i;
            freqActions[i] = () -> {
                freqSelected[0] = index;
                refreshChips(freqChips, index);
            };
        }
        form.addView(chipRow(freqChips, freqSelected[0], freqActions), margin(0, 8, 0, 0));
        refreshChips(freqChips, freqSelected[0]);

        form.addView(text(getString(R.string.commitments_start), 13, muted, medium()),
            margin(0, 12, 0, 2));
        int[] startCivil = existing != null
            ? Commitment.civilDay(existing.start, cal) : today;
        EditText[] startFields = dateFields(startCivil);
        form.addView(dateRow(startFields), margin(0, 0, 0, 0));

        form.addView(text(getString(R.string.commitments_end), 13, muted, medium()),
            margin(0, 12, 0, 2));
        CheckBox openEnded = new CheckBox(this);
        openEnded.setText(getString(R.string.commitments_open_ended));
        openEnded.setTypeface(Fonts.text(this), Typeface.NORMAL);
        openEnded.setChecked(existing == null || existing.end == null);
        form.addView(openEnded, new LinearLayout.LayoutParams(-2, -2));
        int[] endCivil = existing != null && existing.end != null
            ? Commitment.civilDay(existing.end, cal) : today;
        EditText[] endFields = dateFields(endCivil);
        LinearLayout endRow = dateRow(endFields);
        endRow.setVisibility(openEnded.isChecked() ? View.GONE : View.VISIBLE);
        openEnded.setOnCheckedChangeListener((v, checked) ->
            endRow.setVisibility(checked ? View.GONE : View.VISIBLE));
        form.addView(endRow, margin(0, 0, 0, 0));

        if (existing != null) {
            form.addView(text(seriesSummary(existing, cal), 12, muted), margin(0, 12, 0, 0));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(existing == null
                ? getString(R.string.commitments_new) : existing.name)
            .setView(form)
            .setPositiveButton(getString(R.string.note_save), null)
            .setNegativeButton(getString(R.string.lock_cancel), null);
        if (existing != null) {
            final String deleteId = existing.id;
            builder.setNeutralButton(getString(R.string.commitments_delete),
                (d, w) -> confirmDelete(deleteId));
        }
        AlertDialog dlg = builder.create();
        TextWatcher gate = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(canSave(name, amount));
            }
        };
        name.addTextChangedListener(gate);
        amount.addTextChangedListener(gate);
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(canSave(name, amount));
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (saveFromForm(existing, name, amount, pay[0], freqSelected[0], startFields,
                        openEnded.isChecked() ? null : endFields, cal)) {
                    dlg.dismiss();
                }
            });
        });
        dlg.show();
    }

    private Commitment lookupCommitment(String commitmentId) {
        if (commitmentId == null) return null;
        for (Commitment c : BalanceData.readCommitments(this)) {
            if (c.id.equals(commitmentId)) return c;
        }
        return null;
    }

    private boolean canSave(EditText name, EditText amount) {
        return !name.getText().toString().trim().isEmpty()
            && parseAmount(amount.getText().toString()) != 0;
    }

    private long parseAmount(String raw) {
        try {
            String digits = BalanceData.digits(raw.replace(",", "").trim());
            if (digits.isEmpty()) return 0;
            long value = Long.parseLong(digits);
            return Math.abs(value) > Commitment.MAX_AMOUNT ? 0 : Math.abs(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean saveFromForm(Commitment existing, EditText name, EditText amount, boolean pay,
            int frequency, EditText[] startFields, EditText[] endFields, CalendarSystem cal) {
        String title = name.getText().toString().trim();
        long magnitude = parseAmount(amount.getText().toString());
        if (title.isEmpty() || magnitude == 0) return false;
        Long start = parseDate(startFields, cal);
        if (start == null) {
            Toast.makeText(this, getString(R.string.commitments_bad_date),
                Toast.LENGTH_SHORT).show();
            return false;
        }
        Long end = null;
        if (endFields != null) {
            end = parseDate(endFields, cal);
            if (end == null) {
                Toast.makeText(this, getString(R.string.commitments_bad_date),
                    Toast.LENGTH_SHORT).show();
                return false;
            }
            if (end < Commitment.startOfDay(start)) {
                Toast.makeText(this, getString(R.string.commitments_end_before_start),
                    Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        long signed = pay ? -magnitude : magnitude;
        List<Commitment> commitments = BalanceData.readCommitments(this);
        List<Commitment> kept = new ArrayList<>();
        if (existing == null) {
            kept.addAll(commitments);
            kept.add(new Commitment(java.util.UUID.randomUUID().toString(), title, signed,
                frequency, start, end, false, 0, false, 0));
        } else {
            for (Commitment c : commitments) {
                if (!c.id.equals(existing.id)) {
                    kept.add(c);
                    continue;
                }
                kept.add(new Commitment(c.id, title, signed, frequency, start, end, c.done,
                    c.paidThrough, c.remind, c.remindBeforeMs));
            }
        }
        BalanceData.writeCommitments(this, kept);
        render();
        return true;
    }

    private void confirmDelete(String commitmentId) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.commitments_delete_title))
            .setMessage(getString(R.string.commitments_delete_message))
            .setPositiveButton(getString(R.string.commitments_delete), (d, w) -> {
                List<Commitment> kept = new ArrayList<>();
                for (Commitment c : BalanceData.readCommitments(this))
                    if (!c.id.equals(commitmentId)) kept.add(c);
                BalanceData.writeCommitments(this, kept);
                render();
            })
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .show();
    }

    /** A series in one line for the editor: how many dues the window holds and their total. */
    private String seriesSummary(Commitment c, CalendarSystem cal) {
        long now = System.currentTimeMillis();
        List<Long> dues = Commitment.occurrences(c, cal, now - 366L * 86400000L,
            now + 365L * 86400000L);
        return getString(R.string.commitments_series, dues.size(),
            signedAmount(dues.size() * c.amount));
    }

    // ====================================================================
    // Small builders
    // ====================================================================

    private EditText[] dateFields(int[] civil) {
        EditText year = dateField(String.valueOf(civil[0]));
        EditText month = dateField(String.valueOf(civil[1]));
        EditText day = dateField(String.valueOf(civil[2]));
        return new EditText[]{year, month, day};
    }

    private EditText dateField(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTypeface(Fonts.text(this), Typeface.NORMAL);
        e.setGravity(Gravity.CENTER);
        return e;
    }

    private LinearLayout dateRow(EditText[] fields) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < fields.length; i++) {
            row.addView(fields[i], new LinearLayout.LayoutParams(0, -2, 1));
            if (i + 1 < fields.length) {
                TextView sep = text("/", 14, muted);
                sep.setGravity(Gravity.CENTER);
                row.addView(sep, new LinearLayout.LayoutParams(dp(20), -2, 0));
            }
        }
        return row;
    }

    private Long parseDate(EditText[] fields, CalendarSystem cal) {
        try {
            int year = Integer.parseInt(BalanceData.digits(fields[0].getText().toString().trim()));
            int month = Integer.parseInt(BalanceData.digits(fields[1].getText().toString().trim()));
            int day = Integer.parseInt(BalanceData.digits(fields[2].getText().toString().trim()));
            if (!cal.ownsYear(year) || month < 1 || month > 12) return null;
            if (day < 1 || day > cal.daysInMonth(year, month)) return null;
            return Commitment.millisOf(year, month, day, cal);
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout chipRow(TextView[] chips, int selected, Runnable... actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < chips.length; i++) {
            final int index = i;
            TextView chip = chips[i];
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(8), dp(9), dp(8), dp(9));
            chip.setOnClickListener(v -> actions[index].run());
            row.addView(chip, new LinearLayout.LayoutParams(0, -2, 1));
        }
        refreshChips(chips, selected);
        return row;
    }

    private void refreshChips(TextView[] chips, int selected) {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == selected;
            chips[i].setBackground(rounded(on ? accent : chipBg, 11));
            chips[i].setTextColor(on ? Color.WHITE : fg);
        }
    }

    private String signedAmount(long n) {
        String mag = CurrencyHelper.amount(this, Math.abs(n));
        if (n == 0) return mag;
        String sign = n < 0 ? "−" : "+";
        if (!persian) return sign + mag;
        return "⁦" + sign + mag + "⁩";
    }

    private int valueColor(long value) {
        return value < 0 ? negativeColor : value > 0 ? positiveColor : muted;
    }

    private String dateText(long millis) {
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        int[] civil = Commitment.civilDay(millis, cal);
        return yearText(civil[2]) + " " + CalDate.monthName(civil[1], iran, persian);
    }

    private String yearText(int year) {
        return persian ? faDigits(String.valueOf(year)) : String.valueOf(year);
    }

    private String faDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') b.append((char) ('۰' + c - '0'));
            else b.append(c);
        }
        return b.toString();
    }

    private int color(int res) {
        return getResources().getColor(res, getTheme());
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private Typeface medium() {
        return Fonts.medium(this);
    }

    private TextView text(String s, float size, int color) {
        return text(s, size, color, null);
    }

    private TextView text(String s, float size, int color, Typeface tf) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (tf != null) v.setTypeface(tf);
        else v.setTypeface(Fonts.text(this), Typeface.NORMAL);
        v.setIncludeFontPadding(false);
        return v;
    }

    private TextView bold(String s, float size, int color) {
        TextView v = text(s, size, color);
        v.setTypeface(Fonts.text(this), Typeface.BOLD);
        return v;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }
}
