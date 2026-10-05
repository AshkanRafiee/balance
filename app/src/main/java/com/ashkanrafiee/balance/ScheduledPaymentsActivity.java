package com.ashkanrafiee.balance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Manage manual bills, subscriptions, debts and fixed loan installments. */
public final class ScheduledPaymentsActivity extends Activity {
    private LinearLayout body;
    private LockOverlay lockOverlay;
    private BottomNavigation navigation;
    private android.app.AlertDialog activeDialog;
    /** Tracked dialogs are dismissed before the planner is covered by the lock. */
    final List<android.app.AlertDialog> dialogs = new ArrayList<>();
    private final List<ScheduledPayment> plans = new ArrayList<>();
    private int bg, panel, fg, muted, accent, divider, warn, warnBg;
    private ScheduledPaymentSummary screenSummary = ScheduledPaymentSummary.empty();
    private boolean hidden;
    private int loadGeneration;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(ThemeHelper.wrap(base)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30)
            getWindow().setDecorFitsSystemWindows(false);
        bg = color(R.color.bg);
        panel = color(R.color.panel);
        fg = color(R.color.fg);
        muted = color(R.color.muted);
        accent = color(R.color.accent);
        divider = color(R.color.divider);
        warn = color(R.color.warn);
        warnBg = color(R.color.warn_bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));

        FrameLayout host = new FrameLayout(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(bg);
        setContentView(host);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), 0, dp(20), 0);
        shell.addView(root, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = i.top; bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top + dp(14), 0, bottom);
            return insets;
        });
        root.addView(buildHeader(), lp(-1, 52, 0, 0, 0, 12));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new BottomNavigation(this, BottomNavigation.PAYMENTS, this::navigateTopLevel);
        shell.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        host.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        lockOverlay = new LockOverlay(this);
        lockOverlay.setUnlockListener(this::updateSecureFlag);
        lockOverlay.setCancelListener(() -> lockOverlay.hide());
        host.addView(lockOverlay, new FrameLayout.LayoutParams(-1, -1));
        lockOverlay.setVisibility(View.GONE);
        updateSecureFlag();
        loadPlans();
    }

    @Override protected void onStart() {
        super.onStart();
        LockManager.registerActivityStart(this);
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked()) lockOverlay.showLock();
        else lockOverlay.hide();
        updateSecureFlag();
    }

    @Override protected void onStop() {
        if (LockManager.isEnabled(this) && LockManager.registerActivityStop()) {
            lockOverlay.showLock();
            lockOverlay.setAutoFingerprintEnabled(false);
        } else lockOverlay.hide();
        updateSecureFlag();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        refreshMask();
        loadPlans();
        if (LockManager.isEnabled(this) && LockManager.isSessionLocked() && !lockOverlay.isShowing())
            lockOverlay.showLock();
    }

    @Override protected void onPause() {
        dismissDialogs();
        if (LockManager.isEnabled(this)) {
            LockManager.scheduleLock(this);
            if (LockManager.isSessionLocked()) {
                lockOverlay.showLock();
                lockOverlay.setAutoFingerprintEnabled(false);
            }
        }
        updateSecureFlag();
        super.onPause();
    }

    private void updateSecureFlag() {
        if (LockManager.isEnabled(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void navigateTopLevel(int tab) {
        if (tab == BottomNavigation.PAYMENTS) return;
        Intent intent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab);
        startActivity(intent);
    }

    private void refreshMask() {
        hidden = BalanceData.isHidden(this) || BalanceData.isAutoHide(this);
    }

    private boolean isMasked() {
        return hidden;
    }

    private String amountText(long amount) {
        return isMasked() ? getString(R.string.accessibility_total_masked)
            : CurrencyHelper.amount(this, amount) + " " + CurrencyHelper.label(this);
    }

    private String displayTitle(String title) {
        return isMasked() ? "••••••" : title;
    }

    private void dismissDialogs() {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }

    private void showTrackedDialog(android.app.AlertDialog dialog) {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = dialog;
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (activeDialog == dialog) activeDialog = null;
        });
        dialog.show();
    }

    private LinearLayout buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(isRtl() ? "›" : "‹", 24, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.scheduled_back));
        back.setBackground(ripple(round(panel, 24)));
        back.setOnClickListener(v -> finish());
        bar.addView(back, lp(48, 48, 0, 0, 0, 0));

        TextView title = text(getString(R.string.scheduled_title), 22, fg);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -1, 1);
        titleLp.setMarginStart(dp(10));
        bar.addView(title, titleLp);

        TextView add = text("+", 28, accent);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription(getString(R.string.scheduled_add));
        add.setBackground(ripple(round(panel, 24)));
        add.setOnClickListener(v -> showEditor(null));
        bar.addView(add, lp(48, 48, 0, 0, 0, 0));
        TextView mask = text("◉", 22, accent);
        mask.setGravity(Gravity.CENTER);
        mask.setContentDescription(getString(R.string.widget_action_mask));
        mask.setBackground(ripple(round(panel, 24)));
        mask.setOnClickListener(v -> {
            hidden = !hidden;
            getSharedPreferences(BalanceData.PREFS_PREF, MODE_PRIVATE).edit()
                .putBoolean(BalanceData.KEY_HIDDEN, hidden).apply();
            render();
        });
        bar.addView(mask, 1, lp(48, 48, 0, 0, 0, 0));
        return bar;
    }

    private void loadPlans() {
        final int generation = ++loadGeneration;
        new Thread(() -> {
            try {
            List<ScheduledPayment> loaded = BalanceData.readScheduledPayments(getApplicationContext());
            java.util.LinkedHashMap<String, Bank> banks = BalanceData.read(getApplicationContext());
            java.util.Set<String> excluded = BalanceData.getExcluded(getApplicationContext());
            long total = 0;
            boolean hasIncluded = false;
            for (java.util.Map.Entry<String, Bank> e : banks.entrySet()) {
                if (!excluded.contains(e.getKey())) {
                    total = ScheduledPayments.safeAdd(total, e.getValue().amount);
                    hasIncluded = true;
                }
            }
            ScheduledPaymentSummary summary = summarizePlans(loaded, total, hasIncluded);
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                plans.clear();
                plans.addAll(loaded);
                screenSummary = summary;
                render();
            });
            } catch (IllegalStateException e) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                    body.removeAllViews();
                    addHint(getString(R.string.scheduled_load_failed));
                });
            }
        }, "scheduled-payments-load").start();
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        body.addView(summaryCard(screenSummary), lp(-1, -2, 0, 0, 0, 14));

        CalendarSystem activeCalendar = RegionHelper.isIran(this)
            ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        ScheduledDate today = today(activeCalendar);
        ScheduledDate monthStart = new ScheduledDate(activeCalendar, today.year, today.month, 1);
        ScheduledDate monthEnd = new ScheduledDate(activeCalendar, today.year, today.month,
            activeCalendar.daysInMonth(today.year, today.month));
        ScheduledDate displayFrom = today.plusDays(-366);
        ScheduledDate displayTo = today.plusDays(400);
        List<PaymentOccurrence> occurrences = ScheduledPayments.occurrences(plans, displayFrom, displayTo);
        List<PaymentOccurrence> overdue = new ArrayList<>();
        List<PaymentOccurrence> month = new ArrayList<>();
        List<PaymentOccurrence> future = new ArrayList<>();
        List<PaymentOccurrence> completed = new ArrayList<>();
        List<PaymentOccurrence> skipped = new ArrayList<>();
        for (PaymentOccurrence occurrence : occurrences) {
            long day = occurrence.date.ordinal();
            if (day < monthStart.ordinal()) {
                if (occurrence.unpaid()) overdue.add(occurrence);
                else if (occurrence.skipped()) {
                    skipped.add(occurrence);
                    if (skipped.size() > 20) skipped.remove(0);
                } else {
                    completed.add(occurrence);
                    if (completed.size() > 20) completed.remove(0);
                }
            } else if (day <= monthEnd.ordinal()) month.add(occurrence);
            else if (future.size() < 20) future.add(occurrence);
        }
        if (!overdue.isEmpty()) addSection(getString(R.string.scheduled_overdue), warn);
        for (PaymentOccurrence occurrence : overdue) addOccurrence(occurrence, true);
        addSection(getString(R.string.scheduled_this_month), fg);
        if (month.isEmpty()) addHint(getString(R.string.scheduled_no_current));
        for (PaymentOccurrence occurrence : month) addOccurrence(occurrence, false);
        if (!future.isEmpty()) {
            addSection(getString(R.string.scheduled_upcoming), fg);
            for (PaymentOccurrence occurrence : future) addOccurrence(occurrence, false);
        }
        if (!completed.isEmpty()) {
            addSection(getString(R.string.scheduled_completed), muted);
            for (PaymentOccurrence occurrence : completed) addOccurrence(occurrence, false);
        }
        if (!skipped.isEmpty()) {
            addSection(getString(R.string.scheduled_skipped), muted);
            for (PaymentOccurrence occurrence : skipped) addOccurrence(occurrence, false);
        }
        if (plans.isEmpty()) {
            addHint(getString(R.string.scheduled_empty_detail));
            addActionButton(getString(R.string.scheduled_add), () -> showEditor(null));
        } else {
            addSection(getString(R.string.scheduled_plans), fg);
            for (ScheduledPayment plan : plans) addPlanRow(plan);
        }
    }

    private ScheduledPaymentSummary summarizePlans(List<ScheduledPayment> source, long total,
            boolean hasIncluded) {
        boolean iran = RegionHelper.isIran(this);
        ScheduledDate now = today(iran ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN);
        return ScheduledPayments.summary(source, now, total, hasIncluded);
    }

    private LinearLayout summaryCard(ScheduledPaymentSummary summary) {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView title = text(getString(R.string.scheduled_remaining_label), 13, muted);
        card.addView(title, lp(-1, -2, 0, 0, 0, 5));
        String amount = summary.hasIncludedBalance ? amountText(summary.remainingAfterUnpaid)
            : getString(R.string.total_empty_value);
        TextView value = text(amount, 25, fg);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(value, lp(-1, -2, 0, 0, 0, 5));
        String line = getResources().getQuantityString(R.plurals.scheduled_unpaid_count,
            summary.currentMonthCount, summary.currentMonthCount);
        line += " · " + amountText(summary.currentMonthUnpaid);
        if (summary.overdueCount > 0)
            line += " · " + getResources().getQuantityString(R.plurals.scheduled_overdue_count,
                summary.overdueCount, summary.overdueCount);
        TextView detail = text(line, 12, summary.overdueCount > 0 ? warn : muted);
        card.addView(detail, lp(-1, -2, 0, 0, 0, 0));
        return card;
    }

    private void addSection(String title, int color) {
        TextView heading = text(title, 14, color);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(heading, lp(-1, -2, 4, 16, 4, 6));
    }

    private void addHint(String value) {
        TextView hint = text(value, 14, muted);
        hint.setPadding(dp(16), dp(16), dp(16), dp(16));
        hint.setBackground(round(panel, 16));
        body.addView(hint, lp(-1, -2, 0, 0, 0, 8));
    }

    private void addActionButton(String label, final Runnable action) {
        TextView button = text(label, 15, accent);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setBackground(ripple(round(panel, 16)));
        button.setOnClickListener(v -> action.run());
        body.addView(button, lp(-1, -2, 0, 0, 0, 8));
    }

    private void addOccurrence(PaymentOccurrence occurrence, boolean overdue) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.setBackground(ripple(round(panel, 16)));
        row.setMinimumHeight(dp(68));
        row.setOnClickListener(v -> showEditor(occurrence.payment));

        CheckBox check = new CheckBox(this);
        check.setChecked(occurrence.paid());
        check.setContentDescription(getString(occurrence.paid()
            ? R.string.scheduled_mark_unpaid : R.string.scheduled_mark_paid,
            displayTitle(occurrence.payment.title), dateText(occurrence.date)));
        check.setOnClickListener(v -> {
            int state = check.isChecked() ? ScheduledPayment.STATE_PAID : ScheduledPayment.STATE_UNPAID;
            persistOccurrenceState(occurrence, state);
        });
        row.addView(check, lp(48, 56, 0, 0, 0, 0));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(displayTitle(occurrence.payment.title), 15, occurrence.paid() ? muted : fg);
        if (occurrence.paid()) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        copy.addView(title, lp(-1, -2, 0, 0, 0, 2));
        String detail = (occurrence.skipped() ? getString(R.string.scheduled_skipped) + " · " : "")
            + amountText(occurrence.payment.amountRial) + " · " + dateText(occurrence.date) + " · "
            + frequencyText(occurrence.payment.frequency);
        TextView subtitle = text(detail, 12, overdue ? warn : muted);
        copy.addView(subtitle, lp(-1, -2, 0, 0, 0, 0));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView menu = text("⋮", 23, muted);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription(getString(R.string.scheduled_more));
        menu.setOnClickListener(v -> occurrenceMenu(occurrence));
        row.addView(menu, lp(42, 56, 0, 0, 0, 0));
        body.addView(row, lp(-1, -2, 0, 0, 0, 8));
    }

    private void addPlanRow(ScheduledPayment plan) {
        LinearLayout row = card();
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> showEditor(plan));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(displayTitle(plan.title), 15, fg);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView menu = text("⋮", 23, muted);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription(getString(R.string.scheduled_more));
        menu.setOnClickListener(v -> planMenu(plan));
        heading.addView(menu, lp(42, 42, 0, 0, 0, 0));
        row.addView(heading, lp(-1, -2, 0, 0, 0, 4));
        row.addView(text(typeText(plan.type) + " · " + amountText(plan.amountRial), 12, muted), lp(-1, -2, 0, 0, 0, 3));
        row.addView(text(scheduleText(plan), 12, muted), lp(-1, -2, 0, 0, 0, 0));
        body.addView(row, lp(-1, -2, 0, 0, 0, 8));
    }

    private void planMenu(ScheduledPayment plan) {
        boolean canStop = plan.hasFutureOccurrences(today(plan.calendar));
        List<String> labels = new ArrayList<>();
        if (canStop) labels.add(getString(R.string.scheduled_stop_future));
        labels.add(getString(R.string.scheduled_edit));
        labels.add(getString(R.string.scheduled_delete));
        android.app.AlertDialog menu = new android.app.AlertDialog.Builder(this).setTitle(plan.title)
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (canStop && which == 0) {
                    android.app.AlertDialog confirm = new android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.scheduled_stop_future))
                        .setMessage(getString(R.string.scheduled_stop_future_message))
                        .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
                        .setPositiveButton(getString(R.string.scheduled_stop_future), (x, y) -> stopFuture(plan))
                        .create();
                    showTrackedDialog(confirm);
                } else if ((canStop && which == 1) || (!canStop && which == 0)) {
                    showEditor(plan);
                } else {
                    confirmDelete(plan);
                }
            }).create();
        showTrackedDialog(menu);
    }

    private void stopFuture(ScheduledPayment plan) {
        ScheduledDate cutoff = today(plan.calendar);
        new Thread(() -> {
            try {
                BalanceData.stopScheduledPayment(getApplicationContext(), plan.id, cutoff);
                runOnUiThread(this::loadPlans);
            } catch (IllegalStateException e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.scheduled_invalid), Toast.LENGTH_SHORT).show());
            }
        }, "scheduled-payment-stop").start();
    }

    private void confirmDelete(ScheduledPayment plan) {
        android.app.AlertDialog confirm = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.scheduled_delete))
            .setMessage(getString(R.string.scheduled_delete_message))
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.scheduled_delete), (x, y) -> new Thread(() -> {
                BalanceData.deleteScheduledPayment(getApplicationContext(), plan.id);
                runOnUiThread(this::loadPlans);
            }, "scheduled-payment-delete").start())
            .create();
        showTrackedDialog(confirm);
    }

    private void occurrenceMenu(PaymentOccurrence occurrence) {
        String[] options = {
            getString(occurrence.paid() ? R.string.scheduled_mark_unpaid_action : R.string.scheduled_mark_paid_action),
            getString(occurrence.skipped() ? R.string.scheduled_restore_action : R.string.scheduled_skip_action),
            getString(R.string.scheduled_edit)
        };
        android.app.AlertDialog menu = new android.app.AlertDialog.Builder(this).setTitle(occurrence.payment.title)
            .setItems(options, (d, which) -> {
                if (which == 0) persistOccurrenceState(occurrence,
                    occurrence.paid() ? ScheduledPayment.STATE_UNPAID : ScheduledPayment.STATE_PAID);
                else if (which == 1) persistOccurrenceState(occurrence,
                    occurrence.skipped() ? ScheduledPayment.STATE_UNPAID : ScheduledPayment.STATE_SKIPPED);
                else { showEditor(occurrence.payment); return; }
            }).create();
        showTrackedDialog(menu);
    }

    /** Paid toggles are common interactions; keep encryption off the main thread. */
    private void persistOccurrenceState(PaymentOccurrence occurrence, int state) {
        new Thread(() -> {
            try {
                BalanceData.setScheduledPaymentState(getApplicationContext(), occurrence.payment.id,
                    occurrence.sequence, state);
                runOnUiThread(this::loadPlans);
            } catch (IllegalStateException e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.scheduled_invalid), Toast.LENGTH_SHORT).show());
            }
        }, "scheduled-payment-state").start();
    }

    void showEditor(ScheduledPayment existing) {
        refreshMask();
        if (isMasked()) {
            Toast.makeText(this, getString(R.string.scheduled_unmask_to_edit), Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean editing = existing != null;
        final CalendarSystem scheduleCalendar = editing ? existing.calendar
            : (RegionHelper.isIran(this) ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);
        EditText title = field(getString(R.string.scheduled_title_hint), InputType.TYPE_CLASS_TEXT,
            existing == null ? "" : existing.title);
        title.setTag("payment_title");
        form.addView(title, lp(-1, -2, 0, 0, 0, 8));
        Spinner type = spinner(typeLabels());
        type.setTag("payment_type");
        type.setSelection(existing == null ? 0 : existing.type.ordinal());
        form.addView(labeled(getString(R.string.scheduled_type), type), lp(-1, -2, 0, 0, 0, 6));
        EditText amount = field(getString(R.string.scheduled_amount_hint) + " (" + CurrencyHelper.label(this) + ")",
            InputType.TYPE_CLASS_NUMBER, existing == null ? "" : CurrencyHelper.amount(this, existing.amountRial));
        amount.setTag("payment_amount");
        final String initialAmountText = amount.getText().toString();
        amount.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        form.addView(amount, lp(-1, -2, 0, 0, 0, 8));

        final ScheduledDate[] first = {existing == null ? today(scheduleCalendar) : existing.firstDate()};
        TextView date = pickerValue(dateText(first[0]));
        date.setTag("payment_first");
        date.setContentDescription(getString(R.string.scheduled_due_date));
        date.setOnClickListener(v -> chooseDate(first[0], scheduleCalendar, value -> {
            first[0] = value; date.setText(dateText(value));
        }));
        form.addView(labeled(getString(R.string.scheduled_due_date), date), lp(-1, -2, 0, 0, 0, 6));

        Spinner repeat = spinner(new String[]{getString(R.string.scheduled_one_time)});
        repeat.setTag("payment_repeat");
        final int[][] repeatValues = {new int[]{ScheduledPayment.Frequency.ONCE.ordinal()}};
        LinearLayout repeatRow = labeled(getString(R.string.scheduled_repeat), repeat);
        repeatRow.setTag("payment_repeat_row");
        form.addView(repeatRow, lp(-1, -2, 0, 0, 0, 6));
        Spinner ending = spinner(endLabels());
        ending.setTag("payment_ending");
        ending.setSelection(existing == null ? 0 : existing.endMode.ordinal());
        LinearLayout endingRow = labeled(getString(R.string.scheduled_ending), ending);
        endingRow.setTag("payment_ending_row");
        form.addView(endingRow, lp(-1, -2, 0, 0, 0, 6));
        final ScheduledDate[] endDate = {existing != null && existing.endMode == ScheduledPayment.EndMode.DATE
            ? existing.endDate() : first[0]};
        TextView endPicker = pickerValue(dateText(endDate[0]));
        endPicker.setTag("payment_end");
        endPicker.setOnClickListener(v -> chooseDate(endDate[0], scheduleCalendar, value -> {
            endDate[0] = value; endPicker.setText(dateText(value));
        }));
        LinearLayout endDateRow = labeled(getString(R.string.scheduled_end_date), endPicker);
        endDateRow.setTag("payment_end_row");
        form.addView(endDateRow, lp(-1, -2, 0, 0, 0, 6));
        EditText count = field(getString(R.string.scheduled_count_hint), InputType.TYPE_CLASS_NUMBER,
            existing != null && existing.endMode == ScheduledPayment.EndMode.COUNT
                ? Integer.toString(existing.occurrenceCount) : "12");
        count.setTag("payment_count");
        count.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        form.addView(count, lp(-1, -2, 0, 0, 0, 4));
        final Runnable[] updateEndingFields = {null};
        final int[] configuredType = {type.getSelectedItemPosition()};
        Runnable configureForType = () -> {
            ScheduledPayment.Type chosen = ScheduledPayment.Type.values()[type.getSelectedItemPosition()];
            if (configuredType[0] != type.getSelectedItemPosition()) {
                ScheduledPayment.Frequency previous = ScheduledPayment.Frequency.values()[
                    repeatValues[0][Math.max(0, Math.min(repeat.getSelectedItemPosition(), repeatValues[0].length - 1))]];
                configuredType[0] = type.getSelectedItemPosition();
                repeatValues[0] = repeatValuesFor(chosen);
                configureRepeatSpinner(repeat, chosen, previous);
            }
            boolean oneTime = chosen == ScheduledPayment.Type.ONE_TIME;
            boolean loan = chosen == ScheduledPayment.Type.LOAN;
            boolean repeats = !oneTime && (loan ||
                ScheduledPayment.Frequency.values()[repeatValues[0][repeat.getSelectedItemPosition()]]
                    != ScheduledPayment.Frequency.ONCE);
            repeatRow.setVisibility(loan || oneTime ? View.GONE : View.VISIBLE);
            endingRow.setVisibility(repeats ? View.VISIBLE : View.GONE);
            endDateRow.setVisibility(repeats && ending.getSelectedItemPosition()
                == ScheduledPayment.EndMode.DATE.ordinal() ? View.VISIBLE : View.GONE);
            count.setVisibility(repeats && ending.getSelectedItemPosition()
                == ScheduledPayment.EndMode.COUNT.ordinal() ? View.VISIBLE : View.GONE);
            ending.setEnabled(repeats);
            repeat.setEnabled(!oneTime && !loan && (!editing || existing.states.isEmpty()));
            if (loan) repeat.setSelection(0);
        };
        updateEndingFields[0] = configureForType;
        ScheduledPayment.Type initialType = ScheduledPayment.Type.values()[type.getSelectedItemPosition()];
        repeatValues[0] = repeatValuesFor(initialType);
        configureRepeatSpinner(repeat, initialType,
            existing == null ? ScheduledPayment.Frequency.MONTHLY : existing.frequency);
        repeat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateEndingFields[0].run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { updateEndingFields[0].run(); }
        });
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateEndingFields[0].run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { updateEndingFields[0].run(); }
        });
        ending.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateEndingFields[0].run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { updateEndingFields[0].run(); }
        });
        updateEndingFields[0].run();
        if (editing && !existing.states.isEmpty()) {
            date.setEnabled(false);
            repeat.setEnabled(false);
        }
        TextView note = text(getString(R.string.scheduled_local_note), 12, muted);
        note.setPadding(0, dp(4), 0, dp(4));
        form.addView(note, lp(-1, -2, 0, 0, 0, 0));
        if (editing && planHasFuture(existing)) {
            TextView stop = text(getString(R.string.scheduled_stop_future), 14, warn);
            stop.setTag("payment_stop");
            stop.setGravity(Gravity.CENTER);
            stop.setMinHeight(dp(48));
            stop.setBackground(ripple(round(warnBg, 14)));
            stop.setOnClickListener(v -> showStopConfirmation(existing));
            form.addView(stop, lp(-1, -2, 0, 8, 0, 6));
        }

        ScrollView formScroll = new ScrollView(this);
        formScroll.setVerticalScrollBarEnabled(false);
        formScroll.addView(form);
        android.app.AlertDialog.Builder editorBuilder = new android.app.AlertDialog.Builder(this)
            .setTitle(editing ? getString(R.string.scheduled_edit) : getString(R.string.scheduled_add))
            .setView(formScroll).setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.note_save), null);
        if (editing) editorBuilder.setNeutralButton(getString(R.string.scheduled_delete), null);
        android.app.AlertDialog dialog = editorBuilder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    int frequencyOrdinal = repeatValues[0][repeat.getSelectedItemPosition()];
                    long amountRial = editing && initialAmountText.equals(amount.getText().toString())
                        ? existing.amountRial
                        : ScheduledPayments.parseAmountRial(amount.getText().toString(),
                            CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(this)));
                    ScheduledPayment candidate = makePlan(existing, title.getText().toString(), type.getSelectedItemPosition(),
                        amountRial, first[0], frequencyOrdinal,
                        ending.getSelectedItemPosition(), endDate[0], count.getText().toString());
                    if (editing) {
                        candidate.states.putAll(existing.states);
                        candidate.stoppedAfter = existing.stoppedAfter;
                    }
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    persistPlan(candidate, dialog);
                } catch (IllegalArgumentException e) {
                    Toast.makeText(this, getString(R.string.scheduled_invalid), Toast.LENGTH_SHORT).show();
                }
            });
            if (editing) dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.scheduled_delete))
                    .setMessage(getString(R.string.scheduled_delete_message))
                    .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
                    .setPositiveButton(getString(R.string.scheduled_delete), (x, y) -> {
                        dialog.dismiss();
                        new Thread(() -> {
                            BalanceData.deleteScheduledPayment(getApplicationContext(), existing.id);
                            runOnUiThread(this::loadPlans);
                        }, "scheduled-payment-delete").start();
                    }).show();
            });
        });
        showTrackedDialog(dialog);
    }

    private boolean planHasFuture(ScheduledPayment plan) {
        return plan.hasFutureOccurrences(today(plan.calendar));
    }

    private void showStopConfirmation(ScheduledPayment plan) {
        android.app.AlertDialog confirm = new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.scheduled_stop_future))
            .setMessage(getString(R.string.scheduled_stop_future_message))
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.scheduled_stop_future), (d, w) -> stopFuture(plan))
            .create();
        showTrackedDialog(confirm);
    }

    private void persistPlan(ScheduledPayment plan, android.app.AlertDialog editor) {
        new Thread(() -> {
            try {
                BalanceData.saveScheduledPayment(getApplicationContext(), plan);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    editor.dismiss();
                    loadPlans();
                });
            } catch (IllegalStateException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    Toast.makeText(this, getString(R.string.scheduled_save_failed), Toast.LENGTH_LONG).show();
                });
            }
        }, "scheduled-payment-save").start();
    }

    private ScheduledPayment makePlan(ScheduledPayment existing, String title, int typeIndex, long amountRial,
            ScheduledDate first, int frequencyOrdinal, int endIndex, ScheduledDate end, String countText) {
        ScheduledPayment.Type type = ScheduledPayment.Type.values()[Math.max(0,
            Math.min(typeIndex, ScheduledPayment.Type.values().length - 1))];
        ScheduledPayment.Frequency frequency = ScheduledPayment.Frequency.values()[Math.max(0,
            Math.min(frequencyOrdinal, ScheduledPayment.Frequency.values().length - 1))];
        ScheduledPayment.EndMode endMode = ScheduledPayment.EndMode.values()[Math.max(0,
            Math.min(endIndex, ScheduledPayment.EndMode.values().length - 1))];
        if (type == ScheduledPayment.Type.ONE_TIME) frequency = ScheduledPayment.Frequency.ONCE;
        if (frequency == ScheduledPayment.Frequency.ONCE) endMode = ScheduledPayment.EndMode.NEVER;
        int count = 0;
        if (endMode == ScheduledPayment.EndMode.COUNT) {
            try { count = Integer.parseInt(Digits.ascii(countText).trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Count required"); }
        }
        String id = existing == null ? null : existing.id;
        return new ScheduledPayment(id, title, type, amountRial, first.calendar, first.year, first.month,
            first.day, frequency, endMode, end == null ? 0 : end.year, end == null ? 0 : end.month,
            end == null ? 0 : end.day, endMode == ScheduledPayment.EndMode.COUNT ? count : 0);
    }

    private int[] repeatValuesFor(ScheduledPayment.Type type) {
        if (type == ScheduledPayment.Type.SUBSCRIPTION)
            return new int[]{ScheduledPayment.Frequency.WEEKLY.ordinal(),
                ScheduledPayment.Frequency.MONTHLY.ordinal(), ScheduledPayment.Frequency.YEARLY.ordinal()};
        if (type == ScheduledPayment.Type.LOAN)
            return new int[]{ScheduledPayment.Frequency.MONTHLY.ordinal()};
        if (type == ScheduledPayment.Type.ONE_TIME)
            return new int[]{ScheduledPayment.Frequency.ONCE.ordinal()};
        return new int[]{ScheduledPayment.Frequency.ONCE.ordinal(), ScheduledPayment.Frequency.WEEKLY.ordinal(),
            ScheduledPayment.Frequency.MONTHLY.ordinal(), ScheduledPayment.Frequency.YEARLY.ordinal()};
    }

    private void configureRepeatSpinner(Spinner spinner, ScheduledPayment.Type type,
            ScheduledPayment.Frequency preferred) {
        String[] labels;
        if (type == ScheduledPayment.Type.SUBSCRIPTION) {
            labels = new String[]{getString(R.string.scheduled_weekly), getString(R.string.scheduled_monthly),
                getString(R.string.scheduled_yearly)};
        } else if (type == ScheduledPayment.Type.LOAN) {
            labels = new String[]{getString(R.string.scheduled_monthly)};
        } else if (type == ScheduledPayment.Type.ONE_TIME) {
            labels = new String[]{getString(R.string.scheduled_one_time)};
        } else {
            labels = repeatLabels();
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int[] choices = repeatValuesFor(type);
        int selected = 0;
        for (int i = 0; i < choices.length; i++)
            if (choices[i] == preferred.ordinal()) { selected = i; break; }
        spinner.setSelection(selected);
    }

    private void chooseDate(ScheduledDate initial, CalendarSystem calendar, DateCallback callback) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(4), dp(8), 0);
        NumberPicker year = picker(calendar == CalendarSystem.JALALI ? 1100 : 1900,
            calendar == CalendarSystem.JALALI ? 1700 : 2100, initial.year);
        NumberPicker month = picker(1, 12, initial.month);
        NumberPicker day = picker(1, calendar.daysInMonth(initial.year, initial.month), initial.day);
        box.addView(year, lp(0, 120, 4, 0, 4, 0, 1));
        box.addView(month, lp(0, 120, 4, 0, 4, 0, 1));
        box.addView(day, lp(0, 120, 4, 0, 4, 0, 1));
        NumberPicker.OnValueChangeListener updateDays = (v, old, value) -> {
            int max = calendar.daysInMonth(year.getValue(), month.getValue());
            int oldDay = day.getValue();
            day.setMinValue(1); day.setMaxValue(max); day.setValue(Math.min(oldDay, max));
        };
        year.setOnValueChangedListener(updateDays);
        month.setOnValueChangedListener(updateDays);
        new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.scheduled_choose_date))
            .setView(box).setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.history_filter_apply), (d, w) -> {
                try { callback.accept(new ScheduledDate(calendar, year.getValue(), month.getValue(), day.getValue())); }
                catch (IllegalArgumentException ignored) { }
            }).show();
    }

    private interface DateCallback { void accept(ScheduledDate date); }

    private NumberPicker picker(int min, int max, int value) {
        NumberPicker p = new NumberPicker(this);
        p.setMinValue(min); p.setMaxValue(max); p.setValue(Math.max(min, Math.min(max, value)));
        p.setWrapSelectorWheel(false);
        return p;
    }

    private ScheduledDate today(CalendarSystem calendar) {
        Calendar c = Calendar.getInstance();
        return ScheduledDate.fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH), calendar);
    }

    private String dateText(ScheduledDate date) {
        boolean fa = LocaleHelper.isPersian(this);
        CalendarSystem displayCalendar = RegionHelper.isIran(this)
            ? CalendarSystem.JALALI : CalendarSystem.GREGORIAN;
        int[] gregorian = date.toGregorian();
        ScheduledDate shown = date.calendar == displayCalendar ? date
            : ScheduledDate.fromGregorian(gregorian[0], gregorian[1], gregorian[2], displayCalendar);
        String month = monthName(displayCalendar == CalendarSystem.JALALI, shown.month, fa);
        if (fa) return HistoryActivity.faDigits(shown.day) + " " + month + " " + HistoryActivity.faDigits(shown.year);
        return month + " " + shown.day + " " + shown.year;
    }

    private String monthName(boolean iran, int month, boolean fa) {
        if (iran) {
            String[] en = {"", "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
                "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"};
            String[] faNames = {"", "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
                "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};
            return fa ? faNames[month] : en[month];
        }
        String[] en = {"", "January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};
        String[] faNames = {"", "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن", "ژوئیه", "اوت",
            "سپتامبر", "اکتبر", "نوامبر", "دسامبر"};
        return fa ? faNames[month] : en[month];
    }

    private String typeText(ScheduledPayment.Type type) {
        switch (type) {
            case SUBSCRIPTION: return getString(R.string.scheduled_type_subscription);
            case DEBT: return getString(R.string.scheduled_type_debt);
            case LOAN: return getString(R.string.scheduled_type_loan);
            default: return getString(R.string.scheduled_type_one_time);
        }
    }

    private String frequencyText(ScheduledPayment.Frequency frequency) {
        switch (frequency) {
            case WEEKLY: return getString(R.string.scheduled_weekly);
            case MONTHLY: return getString(R.string.scheduled_monthly);
            case YEARLY: return getString(R.string.scheduled_yearly);
            default: return getString(R.string.scheduled_one_time);
        }
    }

    private String scheduleText(ScheduledPayment plan) {
        String end = "";
        if (plan.endMode == ScheduledPayment.EndMode.DATE) end = " · " + dateText(plan.endDate());
        else if (plan.endMode == ScheduledPayment.EndMode.COUNT)
            end = " · " + getString(R.string.scheduled_installments, plan.occurrenceCount);
        if (plan.stoppedAfter != null)
            end += " · " + getString(R.string.scheduled_stopped_after, dateText(plan.stoppedAfter));
        return dateText(plan.firstDate()) + " · " + frequencyText(plan.frequency) + end;
    }

    private String[] typeLabels() {
        return new String[]{getString(R.string.scheduled_type_one_time), getString(R.string.scheduled_type_subscription),
            getString(R.string.scheduled_type_debt), getString(R.string.scheduled_type_loan)};
    }

    private String[] repeatLabels() {
        return new String[]{getString(R.string.scheduled_one_time), getString(R.string.scheduled_weekly),
            getString(R.string.scheduled_monthly), getString(R.string.scheduled_yearly)};
    }

    private String[] endLabels() {
        return new String[]{getString(R.string.scheduled_end_never), getString(R.string.scheduled_end_date_option),
            getString(R.string.scheduled_end_count)};
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        return s;
    }

    private LinearLayout labeled(String label, View value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, 12, muted);
        box.addView(l, lp(-1, -2, 0, 0, 0, 2));
        box.addView(value, lp(-1, -2, 0, 0, 0, 0));
        return box;
    }

    private EditText field(String hint, int inputType, String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(16);
        field.setTextColor(fg);
        field.setHintTextColor(muted);
        field.setInputType(inputType);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(round(panel, 12));
        return field;
    }

    private TextView pickerValue(String value) {
        TextView field = text(value, 16, fg);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(12), dp(13), dp(12), dp(13));
        field.setBackground(ripple(round(panel, 12)));
        field.setClickable(true);
        return field;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(panel, 18));
        return card;
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(isRtl() ? Gravity.RIGHT : Gravity.LEFT);
        return t;
    }

    private android.graphics.drawable.Drawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private android.graphics.drawable.Drawable ripple(android.graphics.drawable.Drawable background) {
        if (android.os.Build.VERSION.SDK_INT >= 21)
            return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), background, null);
        return background;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
            height < 0 ? height : dp(height));
        p.setMarginStart(dp(start)); p.topMargin = dp(top); p.setMarginEnd(dp(end)); p.bottomMargin = dp(bottom); return p;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int start, int top, int end, int bottom, float weight) {
        LinearLayout.LayoutParams p = lp(width, height, start, top, end, bottom); p.weight = weight; return p;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private int color(int res) { return getResources().getColor(res, getTheme()); }
    private boolean isRtl() { return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }
}
