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
     *  totals over exactly what is shown (settled dues are displayed but never summed), plus
     *  the current month's share the hero reports together with the overdue dues. */
    static final class Summary {
        final List<Row> overdue = new ArrayList<>();
        final List<MonthGroup> settledMonths = new ArrayList<>();
        final List<MonthGroup> months = new ArrayList<>();
        long overduePay;
        long overdueReceive;
        long payTotal;
        long receiveTotal;
        long thisMonthPay;
        long thisMonthReceive;
    }

    /** Groups commitments into the screen's window: every unsettled-or-not overdue due, then the
     *  next {@code upcomingMonths} calendar months with their dues. Pure and headless, so the
     *  instrumented tests pin the grouping without touching a view. */
    static Summary summarize(List<Commitment> commitments, CalendarSystem cal, long nowMs,
            int upcomingMonths) {
        Summary summary = new Summary();
        if (commitments == null || cal == null) return summary;
        long today = Commitment.startOfDay(nowMs);
        int[] currentCivil = Commitment.civilDay(today, cal);
        long currentMonthStart = Commitment.millisOf(currentCivil[0], currentCivil[1], 1, cal);
        for (Commitment c : commitments) {
            if (c == null) continue;
            for (long at : Commitment.occurrences(c, cal, Math.min(c.start, today - 366L * 86400000L),
                    today - 1)) {
                if (c.isSettled(at)) {
                    // The current month already owns its settled rows. Keep older paid rows in
                    // their original month so they never appear in the overdue section.
                    if (at < currentMonthStart) {
                        int[] settledCivil = Commitment.civilDay(at, cal);
                        MonthGroup settledMonth = null;
                        for (MonthGroup candidate : summary.settledMonths) {
                            if (candidate.year == settledCivil[0]
                                    && candidate.month == settledCivil[1]) {
                                settledMonth = candidate;
                                break;
                            }
                        }
                        if (settledMonth == null) {
                            settledMonth = new MonthGroup(settledCivil[0], settledCivil[1]);
                            summary.settledMonths.add(settledMonth);
                        }
                        settledMonth.rows.add(new Row(c, at, true));
                    }
                } else {
                    summary.overdue.add(new Row(c, at, false));
                    if (c.isPayment()) summary.overduePay += c.amount;
                    else summary.overdueReceive += c.amount;
                }
            }
        }
        summary.overdue.sort((x, y) -> {
            int byDate = Long.compare(y.date, x.date);
            return byDate != 0 ? byDate : x.commitment.name.compareTo(y.commitment.name);
        });
        for (MonthGroup group : summary.settledMonths) {
            group.rows.sort((x, y) -> {
                int byDate = Long.compare(x.date, y.date);
                return byDate != 0 ? byDate : x.commitment.name.compareTo(y.commitment.name);
            });
        }
        summary.settledMonths.sort((x, y) -> {
            if (x.year != y.year) return Integer.compare(y.year, x.year);
            return Integer.compare(y.month, x.month);
        });
        int year = currentCivil[0];
        int month = currentCivil[1];
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
        for (MonthGroup group : summary.months) {
            if (group.year == currentCivil[0] && group.month == currentCivil[1]) {
                summary.thisMonthPay = group.pay;
                summary.thisMonthReceive = group.receive;
                break;
            }
        }
        return summary;
    }

    private int fg, muted, accent, card, chipBg, depBg, warnBg, warnFg, negativeColor,
        positiveColor;
    private boolean iran;
    private boolean persian;
    private LinearLayout root;
    private AlertDialog manageDialogWindow;

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
        depBg = color(R.color.history_dep_bg);
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
        // Re-arm reminders whenever the screen is seen: edits elsewhere, a restored backup or
        // a granted permission all land here before the next due.
        CommitmentReminders.scheduleAll(this);
        render();
    }

    private void render() {
        root.removeAllViews();
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        long now = System.currentTimeMillis();
        List<Commitment> commitments = BalanceData.readCommitments(this);
        Summary summary = summarize(commitments, cal, now, WINDOW_MONTHS);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(isRtl() ? "›" : "‹", 24, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.history_back));
        back.setOnClickListener(v -> finish());
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text(getString(R.string.commitments_title), 22, fg, medium());
        titleBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(titleBar, margin(0, 0, 0, 12));

        heroCard(summary);
        buttonsRow();

        if (commitments.isEmpty()) {
            TextView empty = text(getString(R.string.commitments_empty_hint), 14, muted);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.3f);
            root.addView(empty, margin(8, 24, 8, 0));
            return;
        }
        if (!summary.overdue.isEmpty()) overdueCard(summary);
        if (!summary.settledMonths.isEmpty()) olderPaidMenu(summary);
        int lastYear = -1;
        for (MonthGroup group : summary.months) {
            if (group.year != lastYear) {
                lastYear = group.year;
                root.addView(yearHeader(group.year, yearPay(summary, lastYear),
                    yearReceive(summary, lastYear)), margin(4, 20, 4, 8));
            }
            root.addView(monthCard(group), margin(0, 0, 0, 12));
        }
    }

    private long yearPay(Summary summary, int year) {
        long total = 0;
        for (MonthGroup group : summary.months)
            if (group.year == year) total += group.pay;
        return total;
    }

    private long yearReceive(Summary summary, int year) {
        long total = 0;
        for (MonthGroup group : summary.months)
            if (group.year == year) total += group.receive;
        return total;
    }

    private void heroCard(Summary summary) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(rounded(card, 20));
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        hero.addView(text(getString(R.string.commitments_total), 12, muted, medium()),
            new LinearLayout.LayoutParams(-2, -2));
        long pay = summary.overduePay + summary.thisMonthPay;
        long receive = summary.overdueReceive + summary.thisMonthReceive;
        hero.addView(bold(getString(R.string.commitments_payable) + " " + signedAmount(pay),
            24, negativeColor), new LinearLayout.LayoutParams(-2, -2));
        hero.addView(bold(getString(R.string.commitments_receivable) + " "
            + signedAmount(receive), 24, accent), new LinearLayout.LayoutParams(-2, -2));
        TextView window = text(getString(R.string.commitments_window_this_month), 12, muted);
        hero.addView(window, new LinearLayout.LayoutParams(-2, -2));
        root.addView(hero, margin(0, 0, 0, 12));
    }

    private void buttonsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView add = text(getString(R.string.commitments_add), 15, Color.WHITE, medium());
        add.setGravity(Gravity.CENTER);
        add.setBackground(rounded(accent, 14));
        add.setPadding(dp(16), dp(13), dp(16), dp(13));
        add.setOnClickListener(v -> editorDialog(null));
        row.addView(add, new LinearLayout.LayoutParams(0, -2, 1));
        TextView manage = text(getString(R.string.commitments_manage), 15, fg, medium());
        manage.setGravity(Gravity.CENTER);
        manage.setBackground(rounded(chipBg, 14));
        manage.setPadding(dp(16), dp(13), dp(16), dp(13));
        manage.setOnClickListener(v -> manageDialog());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(dp(10));
        row.addView(manage, lp);
        root.addView(row, margin(0, 0, 0, 12));
    }

    /** The series behind the dues: one row per commitment definition with its schedule, opening
     *  the editor on tap, so a whole series is managed here instead of due by due. */
    private void manageDialog() {
        List<Commitment> commitments = BalanceData.readCommitments(this);
        CalendarSystem cal = iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));
        if (commitments.isEmpty()) {
            TextView hint = text(getString(R.string.commitments_manage_empty), 14, muted);
            hint.setPadding(dp(8), dp(16), dp(8), dp(16));
            list.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
        for (Commitment c : commitments) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(rounded(card, 14));

            LinearLayout heading = new LinearLayout(this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(c.name, 15, fg, medium());
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            applyUserTextDirection(name);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Long total = Commitment.totalAmount(c, cal);
            String amountLabel = total == null
                ? getString(R.string.commitments_each_amount, signedAmount(c.amount))
                : getString(R.string.commitments_total_each_amount, signedAmount(total),
                    signedAmount(c.amount));
            TextView amount = bold(amountLabel, 14, valueColor(total == null ? c.amount : total));
            amount.setGravity(Gravity.END);
            amount.setMaxLines(2);
            LinearLayout.LayoutParams amountLp = new LinearLayout.LayoutParams(-2, -2);
            amountLp.setMarginStart(dp(12));
            heading.addView(amount, amountLp);
            row.addView(heading, new LinearLayout.LayoutParams(-1, -2));

            String ending = c.end == null ? getString(R.string.commitments_series_open)
                : getString(R.string.commitments_series_ends, dateText(c.end));
            TextView details = text(freqLabel(c.frequency) + " · " + ending, 12, muted);
            details.setPadding(0, dp(4), 0, 0);
            row.addView(details, new LinearLayout.LayoutParams(-1, -2));
            final String id = c.id;
            row.setOnClickListener(v -> {
                if (manageDialogWindow != null) {
                    manageDialogWindow.dismiss();
                    manageDialogWindow = null;
                }
                editorDialog(id);
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, 0, 0, dp(8));
            list.addView(row, rowLp);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(list, new LinearLayout.LayoutParams(-1, -2));
        manageDialogWindow = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.commitments_manage_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        manageDialogWindow.setOnDismissListener(d -> manageDialogWindow = null);
    }

    private String freqLabel(int frequency) {
        switch (frequency) {
            case Commitment.DAILY: return getString(R.string.commitments_freq_daily);
            case Commitment.WEEKLY: return getString(R.string.commitments_freq_weekly);
            case Commitment.MONTHLY: return getString(R.string.commitments_freq_monthly);
            case Commitment.YEARLY: return getString(R.string.commitments_freq_yearly);
            default: return getString(R.string.commitments_freq_once);
        }
    }

    private void overdueCard(Summary summary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(warnBg, 16));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(text(getString(R.string.commitments_overdue), 15, warnFg, medium()),
            new LinearLayout.LayoutParams(-2, -2));
        if (!summary.overdue.isEmpty()) {
            box.addView(text(getString(R.string.commitments_payable) + " "
                    + signedAmount(summary.overduePay), 16, negativeColor, medium()),
                new LinearLayout.LayoutParams(-2, -2));
            box.addView(text(getString(R.string.commitments_receivable) + " "
                    + signedAmount(summary.overdueReceive), 16, accent, medium()),
                new LinearLayout.LayoutParams(-2, -2));
        }
        int shown = Math.min(summary.overdue.size(), MAX_OVERDUE_ROWS);
        for (int i = 0; i < shown; i++) box.addView(occurrenceRow(summary.overdue.get(i)));
        if (summary.overdue.size() > shown) {
            box.addView(text(getString(R.string.commitments_older, summary.overdue.size() - shown),
                12, warnFg), new LinearLayout.LayoutParams(-2, -2));
        }
        root.addView(box, margin(0, 0, 0, 12));
    }

    /** Previous paid months stay out of the overdue card and are available from one collapsed menu. */
    private void olderPaidMenu(Summary summary) {
        int count = 0;
        for (MonthGroup group : summary.settledMonths) count += group.rows.size();
        final int olderCount = count;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(chipBg, 14));
        final boolean[] expanded = {false};
        TextView toggle = text(getString(R.string.commitments_show_older_paid, olderCount), 14, fg, medium());
        toggle.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout months = new LinearLayout(this);
        months.setOrientation(LinearLayout.VERTICAL);
        months.setVisibility(View.GONE);
        for (MonthGroup group : summary.settledMonths) months.addView(settledMonthCard(group));
        toggle.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            months.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            toggle.setText(expanded[0]
                ? getString(R.string.commitments_hide_older_paid)
                : getString(R.string.commitments_show_older_paid, olderCount));
        });
        box.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        box.addView(months, new LinearLayout.LayoutParams(-1, -2));
        root.addView(box, margin(0, 0, 0, 12));
    }

    private LinearLayout settledMonthCard(MonthGroup group) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView head = text(CalDate.monthName(group.month, iran, persian) + " "
            + yearText(group.year), 15, fg, medium());
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        for (Row row : group.rows) box.addView(occurrenceRow(row));
        return box;
    }

    private LinearLayout yearHeader(int year, long pay, long receive) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = bold(yearText(year), 18, fg);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout totals = new LinearLayout(this);
        totals.setOrientation(LinearLayout.VERTICAL);
        totals.setGravity(Gravity.END);
        totals.addView(text(getString(R.string.commitments_payable) + " " + signedAmount(pay),
            12, negativeColor, medium()));
        totals.addView(text(getString(R.string.commitments_receivable) + " "
            + signedAmount(receive), 12, accent, medium()));
        row.addView(totals, new LinearLayout.LayoutParams(-2, -2));
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
        TextView head = text(CalDate.monthName(group.month, iran, persian), 15, fg, medium());
        box.addView(head, new LinearLayout.LayoutParams(-2, -2));
        box.addView(text(getString(R.string.commitments_payable) + " "
            + signedAmount(group.pay), 16, negativeColor, medium()),
            new LinearLayout.LayoutParams(-2, -2));
        box.addView(text(getString(R.string.commitments_receivable) + " "
            + signedAmount(group.receive), 16, accent, medium()),
            new LinearLayout.LayoutParams(-2, -2));
        for (Row row : group.rows) box.addView(occurrenceRow(row));
        return box;
    }

    private LinearLayout occurrenceRow(Row row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(7), dp(10), dp(7));
        if (row.settled) line.setBackground(rounded(depBg, 10));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(row.commitment.name, 14, row.settled ? muted : fg);
        applyUserTextDirection(name);
        if (row.settled) {
            name.setPaintFlags(name.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        info.addView(name, new LinearLayout.LayoutParams(-1, -2));
        info.addView(text(dateText(row.date), 12, muted), new LinearLayout.LayoutParams(-2, -2));
        line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView figure = bold(signedAmount(row.commitment.amount), 15,
            row.settled ? muted : valueColor(row.commitment.amount));
        if (row.settled) {
            figure.setPaintFlags(
                figure.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        line.addView(figure, new LinearLayout.LayoutParams(-2, -2));
        if (row.settled) {
            TextView undo = text(getString(R.string.commitments_undo), 12, accent, medium());
            undo.setBackground(rounded(chipBg, 10));
            undo.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(10));
            undo.setOnClickListener(v -> undoSettled(row));
            line.addView(undo, lp);
        } else {
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
                    true, c.paid, c.remind, c.remindBeforeMs));
            } else {
                List<Long> paid = new ArrayList<>(c.paid);
                if (!paid.contains(row.date)) paid.add(row.date);
                kept.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    c.done, paid, c.remind, c.remindBeforeMs));
            }
        }
        BalanceData.writeCommitments(this, kept);
        CommitmentReminders.scheduleAll(this);
        render();
    }

    /** Reopens exactly the due the user settled: a one-time commitment goes back to unpaid,
     *  a recurring due leaves the settled-day list. Nothing else moves. */
    private void undoSettled(Row row) {
        List<Commitment> commitments = BalanceData.readCommitments(this);
        List<Commitment> kept = new ArrayList<>();
        for (Commitment c : commitments) {
            if (!c.id.equals(row.commitment.id)) {
                kept.add(c);
                continue;
            }
            if (c.frequency == Commitment.ONCE) {
                kept.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    false, c.paid, c.remind, c.remindBeforeMs));
            } else {
                List<Long> paid = new ArrayList<>(c.paid);
                paid.remove(Long.valueOf(row.date));
                kept.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                    c.done, paid, c.remind, c.remindBeforeMs));
            }
        }
        BalanceData.writeCommitments(this, kept);
        CommitmentReminders.scheduleAll(this);
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
        form.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText name = new EditText(this);
        name.setHint(getString(R.string.commitments_name_hint));
        if (existing != null) name.setText(existing.name);
        name.setTypeface(Fonts.text(this), Typeface.NORMAL);
        applyUserTextDirection(name);
        form.addView(name, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText amount = new EditText(this);
        amount.setHint(getString(R.string.commitments_amount_hint, CurrencyHelper.label(this)));
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (existing != null) amount.setText(formatMagnitude(Math.abs(existing.amount)));
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
        openEnded.setChecked(existing != null && existing.end == null);
        form.addView(openEnded, new LinearLayout.LayoutParams(-2, -2));
        int[] endCivil = existing != null && existing.end != null
            ? Commitment.civilDay(existing.end, cal) : today;
        EditText[] endFields = dateFields(endCivil);
        LinearLayout endRow = dateRow(endFields);
        endRow.setVisibility(openEnded.isChecked() ? View.GONE : View.VISIBLE);
        openEnded.setOnCheckedChangeListener((v, checked) ->
            endRow.setVisibility(checked ? View.GONE : View.VISIBLE));
        form.addView(endRow, margin(0, 0, 0, 0));

        form.addView(text(getString(R.string.commitments_remind), 13, muted, medium()),
            margin(0, 12, 0, 2));
        CheckBox remindBox = new CheckBox(this);
        remindBox.setText(getString(R.string.commitments_remind_me));
        remindBox.setTypeface(Fonts.text(this), Typeface.NORMAL);
        remindBox.setChecked(existing != null && existing.remind);
        form.addView(remindBox, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout leadRow = new LinearLayout(this);
        leadRow.setOrientation(LinearLayout.HORIZONTAL);
        leadRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText leadNumber = new EditText(this);
        leadNumber.setInputType(InputType.TYPE_CLASS_NUMBER);
        leadNumber.setTypeface(Fonts.text(this), Typeface.NORMAL);
        leadNumber.setGravity(Gravity.CENTER);
        long[] decomposed = decomposeLead(existing != null ? existing.remindBeforeMs : 86400000L);
        leadNumber.setText(String.valueOf(decomposed[0]));
        leadRow.addView(leadNumber, new LinearLayout.LayoutParams(0, -2, 1));
        TextView[] unitChips = {
            text(getString(R.string.commitments_unit_minutes), 12, fg, medium()),
            text(getString(R.string.commitments_unit_hours), 12, fg, medium()),
            text(getString(R.string.commitments_unit_days), 12, fg, medium()),
            text(getString(R.string.commitments_unit_weeks), 12, fg, medium())};
        final int[] unitSelected = {(int) decomposed[1]};
        Runnable[] unitActions = new Runnable[4];
        for (int i = 0; i < 4; i++) {
            final int index = i;
            unitActions[i] = () -> {
                unitSelected[0] = index;
                refreshChips(unitChips, index);
            };
        }
        LinearLayout unitRow = chipRow(unitChips, unitSelected[0], unitActions);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(0, -2, 3);
        unitLp.setMarginStart(dp(8));
        leadRow.addView(unitRow, unitLp);
        leadRow.setVisibility(remindBox.isChecked() ? View.VISIBLE : View.GONE);
        TextView exactNote = text(getString(R.string.commitments_exact_note), 12, muted);
        exactNote.setOnClickListener(v -> startActivity(new android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:" + getPackageName()))));
        exactNote.setVisibility(remindBox.isChecked()
            && !CommitmentReminders.canScheduleExact(this) ? View.VISIBLE : View.GONE);
        remindBox.setOnCheckedChangeListener((v, checked) -> {
            leadRow.setVisibility(checked ? View.VISIBLE : View.GONE);
            exactNote.setVisibility(
                checked && !CommitmentReminders.canScheduleExact(this) ? View.VISIBLE
                    : View.GONE);
            if (checked && needsNotificationPermission()) requestNotificationPermission();
        });
        form.addView(leadRow, margin(0, 4, 0, 0));
        form.addView(exactNote, margin(0, 4, 0, 0));

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
                applyUserTextDirection(name);
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(canSave(name, amount));
            }
        };
        name.addTextChangedListener(gate);
        amount.addTextChangedListener(gate);
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(canSave(name, amount));
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                long leadMs = parseLead(leadNumber.getText().toString(), unitSelected[0]);
                if (saveFromForm(existing, name, amount, pay[0], freqSelected[0], startFields,
                        openEnded.isChecked() ? null : endFields, cal, remindBox.isChecked(),
                        leadMs)) {
                    dlg.dismiss();
                }
            });
        });
        dlg.show();
        if (existing != null) {
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            View title = titleId == 0 ? null : dlg.findViewById(titleId);
            if (title instanceof TextView) applyUserTextDirection((TextView) title);
        }
        if (existing != null && existing.remind && needsNotificationPermission()) {
            requestNotificationPermission();
        }
    }

    private static final long[] UNIT_MS = {60_000L, 3600_000L, 86400_000L, 604800_000L};
    private static final int REQUEST_NOTIFY = 41;

    /** Splits a lead time into the largest whole unit that divides it (weeks down to minutes),
     *  defaulting a fresh editor to one day. Returns {number, unitIndex}. */
    private static long[] decomposeLead(long ms) {
        if (ms <= 0) return new long[]{1, 2};
        if (ms % UNIT_MS[3] == 0) return new long[]{ms / UNIT_MS[3], 3};
        if (ms % UNIT_MS[2] == 0) return new long[]{ms / UNIT_MS[2], 2};
        if (ms % UNIT_MS[1] == 0) return new long[]{ms / UNIT_MS[1], 1};
        return new long[]{Math.max(1, ms / UNIT_MS[0]), 0};
    }

    private boolean needsNotificationPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        requestPermissions(
            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_NOTIFY) return;
        if (grants.length == 0
                || grants[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // The choice stays saved: enabling notifications later just works, since every
            // resume re-arms from the stored commitments.
            Toast.makeText(this, getString(R.string.commitments_notifications_off),
                Toast.LENGTH_LONG).show();
        }
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

    private boolean isToman() {
        return CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(this));
    }

    /** A stored rial magnitude written in the display currency, the way every amount on screen
     *  reads (toman drops the trailing zero the same way the dashboard does). */
    private String formatMagnitude(long rialAbs) {
        return String.valueOf(isToman() ? rialAbs / 10 : rialAbs);
    }

    /** The editor figure back in stored rials. Toman input regains its trailing zero; a figure
     *  that would overflow the stored bound reads as empty and keeps Save disabled. */
    private long parseAmount(String raw) {
        try {
            String digits = BalanceData.digits(raw.replace(",", "").trim());
            if (digits.isEmpty()) return 0;
            long value = Long.parseLong(digits);
            if (value <= 0) return 0;
            if (isToman()) {
                if (value > Commitment.MAX_AMOUNT / 10) return 0;
                return value * 10;
            }
            return value > Commitment.MAX_AMOUNT ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseLead(String raw, int unit) {
        try {
            long n = Long.parseLong(BalanceData.digits(raw.trim()));
            if (n < 0) n = 0;
            long step = UNIT_MS[Math.max(0, Math.min(unit, UNIT_MS.length - 1))];
            if (n > Commitment.MAX_REMIND_BEFORE_MS / step) return Commitment.MAX_REMIND_BEFORE_MS;
            return n * step;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean saveFromForm(Commitment existing, EditText name, EditText amount, boolean pay,
            int frequency, EditText[] startFields, EditText[] endFields, CalendarSystem cal,
            boolean remind, long remindBeforeMs) {
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
                frequency, start, end, false, null, remind, remindBeforeMs));
        } else {
            for (Commitment c : commitments) {
                if (!c.id.equals(existing.id)) {
                    kept.add(c);
                    continue;
                }
                kept.add(new Commitment(c.id, title, signed, frequency, start, end, c.done,
                    c.paid, remind, remindBeforeMs));
            }
        }
        BalanceData.writeCommitments(this, kept);
        CommitmentReminders.scheduleAll(this);
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
                CommitmentReminders.scheduleAll(this);
                render();
            })
            .setNegativeButton(getString(R.string.lock_cancel), null)
            .show();
    }

    /** A series in one line for the editor: how many of its dues around now are still
     *  remaining, and their total. */
    private String seriesSummary(Commitment c, CalendarSystem cal) {
        long now = System.currentTimeMillis();
        List<Long> dues = Commitment.occurrences(c, cal, now - 366L * 86400000L,
            now + 365L * 86400000L);
        int remaining = 0;
        for (long at : dues) if (!c.isSettled(at)) remaining++;
        return getString(R.string.commitments_series, remaining, dues.size(),
            signedAmount(remaining * c.amount));
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
        // Year/month/day stay in this order in every language, matching how the bank messages
        // and the history write dates, instead of mirroring in RTL layouts.
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
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

    private boolean isRtl() {
        return getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_RTL;
    }

    private void applyUserTextDirection(TextView view) {
        boolean rtl = isRtl();
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        int vertical = view.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        view.setGravity(vertical | (rtl ? Gravity.RIGHT : Gravity.LEFT));
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
