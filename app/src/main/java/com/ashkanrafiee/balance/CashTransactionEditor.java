package com.ashkanrafiee.balance;

import static com.ashkanrafiee.balance.CalendarRangePicker.calOf;
import static com.ashkanrafiee.balance.HistoryActivity.faDigitsString;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** The cash entry and edit dialogs, owned by the activity that opened them. */
final class CashTransactionEditor {
    private static final Executor CASH_PREVIEW_WORKER = Executors.newSingleThreadExecutor(
        task -> new Thread(task, "cash-preview"));

    private final Activity activity;
    private final Runnable onChanged, onClosed;
    private final BooleanSupplier hideBalances;
    private final Set<AlertDialog> dialogs = new LinkedHashSet<>();
    private final int fg, muted;
    private final boolean iranCalendar, persian;
    private Editor activeEditor;
    private int editorRequest;
    private boolean loadingEdit;

    CashTransactionEditor(Activity activity, Runnable onChanged, Runnable onClosed) {
        this(activity, onChanged, onClosed, () -> false);
    }

    CashTransactionEditor(Activity activity, Runnable onChanged, Runnable onClosed,
            BooleanSupplier hideBalances) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.onClosed = onClosed;
        this.hideBalances = hideBalances;
        fg = ExpressiveUi.color(activity, R.color.fg);
        muted = ExpressiveUi.color(activity, R.color.expressive_on_surface_variant);
        iranCalendar = RegionHelper.isIran(activity);
        persian = LocaleHelper.isPersian(activity);
    }

    void showNew() {
        if (isShowing() || !canShow() || !CashStore.isEnabled(activity)) return;
        open(new CashDraft(null, System.currentTimeMillis(), "", false, "",
            java.util.Collections.emptyList(), null), null);
    }

    void showEdit(Transaction transaction) {
        if (isShowing() || !canShow()) return;
        loadingEdit = true;
        int request = ++editorRequest;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            CashStore.Annotation annotation;
            try {
                annotation = CashStore.readAnnotation(app, transaction);
            } catch (Exception failure) {
                annotation = null;
            }
            CashStore.Annotation loaded = annotation;
            activity.runOnUiThread(() -> {
                if (request != editorRequest) return;
                loadingEdit = false;
                if (!canShow()) return;
                if (loaded == null) {
                    android.widget.Toast.makeText(activity, R.string.cash_history_annotation_load_failed,
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                open(new CashDraft(transaction, transaction.date, cashInput(Math.abs(transaction.amount)),
                    transaction.amount < 0, loaded.note, loaded.tags, loaded), null);
            });
        }, "cash-load-annotations").start();
    }

    boolean isShowing() {
        return loadingEdit || (activeEditor != null && activeEditor.dialog.isShowing());
    }

    void requestPreview() {
        if (activeEditor != null) activeEditor.requestPreview();
    }

    void dismiss() {
        editorRequest++;
        loadingEdit = false;
        if (activeEditor != null) activeEditor.cancelPendingWork();
        boolean wasOpen = activeEditor != null;
        activeEditor = null;
        // Close nested pickers before the editor beneath them.
        List<AlertDialog> showing = new ArrayList<>(dialogs);
        for (int i = showing.size() - 1; i >= 0; i--) showing.get(i).dismiss();
        if (wasOpen) onClosed.run();
    }

    List<AlertDialog> shownDialogsForTest() {
        return new ArrayList<>(dialogs);
    }

    private boolean canShow() {
        return !activity.isDestroyed() && !activity.isFinishing() && !isLocked(activity);
    }

    private static boolean isLocked(Context context) {
        return LockManager.isSessionLocked() && LockManager.isEnabled(context);
    }

    private void open(CashDraft draft, CashDraft returnDraft) {
        if (!canShow()) return;
        Editor previous = activeEditor;
        activeEditor = new Editor(draft, returnDraft);
        if (previous != null) {
            previous.cancelPendingWork();
            previous.dialog.dismiss();
        }
        activeEditor.show();
    }

    private void showDialog(AlertDialog dialog) {
        showDialog(dialog, null);
    }

    private void showDialog(AlertDialog dialog, Runnable onDismiss) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (onDismiss != null) onDismiss.run();
        });
        if (LockManager.isEnabled(activity)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();
    }

    private static final class CashDraft {
        final Transaction replacing;
        final long timestamp;
        final String amountText;
        final boolean withdrawal;
        final String noteText;
        final List<String> tags;
        final CashStore.Annotation original;

        CashDraft(Transaction replacing, long timestamp, String amountText, boolean withdrawal,
                String noteText, List<String> tags, CashStore.Annotation original) {
            this.replacing = replacing;
            this.timestamp = timestamp;
            this.amountText = amountText;
            this.withdrawal = withdrawal;
            this.noteText = noteText;
            this.tags = tags;
            this.original = original;
        }
    }

    private String cashInput(long rials) {
        String value = CashStore.formatAmountForInput(activity, rials);
        return persian ? faDigitsString(value).replace('.', '٫') : value;
    }

    private String cashBalanceValue(long rials) {
        return hideBalances.getAsBoolean() ? "••••••"
            : CurrencyHelper.amountExact(activity, rials) + " " + CurrencyHelper.label(activity);
    }

    private final class Editor {
        private static final long AMOUNT_PREVIEW_DELAY_MS = 240;
        private final Transaction replacing;
        private final CashDraft returnDraft;
        private final LinearLayout form = new LinearLayout(activity);
        private final RadioGroup direction = new RadioGroup(activity);
        private final int withdrawalId = View.generateViewId();
        private final EditText amount = new EditText(activity);
        private final EditText note;
        private final EditText tag;
        private final LinearLayout tagRows = new LinearLayout(activity);
        private final List<String> tags;
        private final CashStore.Annotation original;
        private final TextView dateButton = ExpressiveUi.button(activity, "", false);
        private final TextView timeButton = ExpressiveUi.button(activity, "", false);
        private final TextView preview = text("", 14, fg);
        private final TextView feedback;
        private final TextView recover = ExpressiveUi.button(activity,
            activity.getString(R.string.cash_history_record_deposit), false);
        private final AlertDialog dialog;
        private final Handler previewHandler = new Handler(Looper.getMainLooper());
        private final Runnable previewWork = this::loadPreview;
        private long timestamp;
        private long previewDueAt;
        private Long previewAmount;
        private volatile int previewGeneration;
        private volatile boolean canceled;
        private boolean previewRunning;
        private boolean saving;

        Editor(CashDraft draft, CashDraft returnDraft) {
            replacing = draft.replacing;
            original = draft.original;
            tags = new ArrayList<>(draft.tags);
            timestamp = draft.timestamp;
            this.returnDraft = returnDraft;
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(dp(24), dp(8), dp(24), dp(8));

            direction.setOrientation(LinearLayout.HORIZONTAL);
            RadioButton deposit = new RadioButton(activity);
            deposit.setId(View.generateViewId());
            deposit.setText(R.string.history_deposit);
            direction.addView(deposit, new RadioGroup.LayoutParams(0, -2, 1));
            RadioButton withdrawal = new RadioButton(activity);
            withdrawal.setId(withdrawalId);
            withdrawal.setText(R.string.history_withdrawal);
            direction.addView(withdrawal, new RadioGroup.LayoutParams(0, -2, 1));
            direction.check(draft.withdrawal ? withdrawalId : deposit.getId());
            if (returnDraft == null) {
                form.addView(direction, margins(0, 6));
            } else {
                form.addView(text(activity.getString(R.string.cash_history_recovery_hint), 13, muted),
                    margins(0, 8));
            }

            amount.setId(View.generateViewId());
            amount.setSingleLine(true);
            boolean decimal = CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(activity));
            amount.setKeyListener(DigitsKeyListener.getInstance(
                "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩,٬،" + (decimal ? ".٫" : "")));
            amount.setRawInputType(InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
            amount.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            amount.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            amount.setSaveEnabled(false);
            amount.setText(draft.amountText);
            TextView amountLabel = text(activity.getString(R.string.cash_history_amount,
                CurrencyHelper.label(activity)), 13, muted);
            amountLabel.setLabelFor(amount.getId());
            form.addView(amountLabel);
            form.addView(amount, margins(0, 8));

            note = annotationInput(R.string.cash_history_note_optional);
            note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            note.setMaxLines(3);
            note.setText(draft.noteText);
            form.addView(note, margins(0, 8));

            tag = annotationInput(R.string.cash_history_tag_optional);
            tag.setSingleLine(true);
            tag.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            tag.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                addTypedTag();
                return true;
            });
            LinearLayout tagInput = new LinearLayout(activity);
            tagInput.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tagInput.addView(tag, new LinearLayout.LayoutParams(0, -2, 1));
            TextView addTag = ExpressiveUi.button(activity,
                activity.getString(R.string.cash_history_add_tag), false);
            addTag.setOnClickListener(v -> addTypedTag());
            tagInput.addView(addTag);
            form.addView(tagInput, margins(0, 4));
            tagRows.setOrientation(LinearLayout.VERTICAL);
            form.addView(tagRows, margins(0, 4));
            renderTags();

            updateDateLabels();
            dateButton.setOnClickListener(v -> showCashDatePicker(timestamp, this::pickTimestamp));
            timeButton.setOnClickListener(v -> showCashTimePicker(timestamp, this::pickTimestamp));
            form.addView(dateButton, margins(4, 4));
            form.addView(timeButton, margins(4, 4));
            preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            preview.setVisibility(View.GONE);
            form.addView(preview, margins(8, 4));
            feedback = cashFeedback(form);
            recover.setVisibility(View.GONE);
            form.addView(recover, margins(4, 4));
            form.addView(text(activity.getString(R.string.cash_history_count_rule), 13, muted),
                margins(4, 4));

            ScrollView scroll = new ScrollView(activity);
            scroll.addView(form);
            int title = returnDraft != null ? R.string.cash_history_record_deposit
                : replacing != null ? R.string.cash_history_edit : R.string.cash_history_add;
            dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.note_save, null)
                .setNegativeButton(R.string.lock_cancel, null)
                .create();
            dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.setOnCancelListener(d -> restoreDraft());
            direction.setOnCheckedChangeListener((group, checked) -> draftChanged(0));
            amount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    draftChanged(AMOUNT_PREVIEW_DELAY_MS);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        void show() {
            showDialog(dialog, () -> {
                cancelPendingWork();
                if (activeEditor == this) {
                    activeEditor = null;
                    onClosed.run();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (returnDraft != null && canShow()) restoreDraft();
                else dialog.dismiss();
            });
            requestPreview();
        }

        private void cancelPendingWork() {
            canceled = true;
            previewGeneration++;
            previewHandler.removeCallbacks(previewWork);
        }

        private boolean isActive() {
            return activeEditor == this && !canceled && dialog.isShowing() && canShow();
        }

        private void updateDateLabels() {
            dateButton.setText(activity.getString(R.string.cash_history_date_value,
                dateText(timestamp)));
            timeButton.setText(activity.getString(R.string.cash_history_time_value, timeText(timestamp)));
        }

        private void pickTimestamp(long value) {
            timestamp = value;
            updateDateLabels();
            draftChanged(0);
        }

        private boolean isWithdrawal() {
            return direction.getCheckedRadioButtonId() == withdrawalId;
        }

        private Long parseAmount() {
            Long value = CashStore.parseAmount(activity, amount.getText().toString());
            if (value == null || value == 0) return null;
            return isWithdrawal() ? -value : value;
        }

        private void draftChanged(long delayMs) {
            feedback.setVisibility(View.GONE);
            amount.setError(null);
            requestPreview(delayMs);
        }

        void requestPreview() {
            // A history notification should not cut short the pause after the latest keystroke.
            requestPreview(Math.max(0, previewDueAt - SystemClock.uptimeMillis()));
        }

        private void requestPreview(long delayMs) {
            if (!isActive() || saving) return;
            previewGeneration++;
            previewHandler.removeCallbacks(previewWork);
            previewDueAt = SystemClock.uptimeMillis() + delayMs;
            previewAmount = parseAmount();
            boolean validAmount = previewAmount != null;
            preview.setText(R.string.cash_history_preview_loading);
            preview.setVisibility(validAmount ? View.VISIBLE : View.GONE);
            recover.setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!validAmount);
            if (validAmount && !previewRunning) previewHandler.postAtTime(previewWork, previewDueAt);
        }

        private void loadPreview() {
            if (!isActive() || saving) return;
            Long signedAmount = previewAmount;
            if (signedAmount == null) return;
            long date = timestamp;
            int generation = previewGeneration;
            Context app = activity.getApplicationContext();
            previewRunning = true;
            CASH_PREVIEW_WORKER.execute(() -> {
                CashStore.Preview result = null;
                int error = 0;
                try {
                    if (generation != previewGeneration) throw new CancellationException();
                    if (!CashStore.isEnabled(app)) {
                        error = R.string.cash_history_disabled;
                    } else {
                        result = CashStore.preview(app, date, signedAmount, replacing,
                            () -> generation == previewGeneration);
                    }
                } catch (CancellationException superseded) {
                    // Completion below releases the worker slot and schedules the newest input.
                } catch (CashStore.StaleCashTransactionException stale) {
                    error = R.string.cash_history_stale;
                } catch (IllegalArgumentException invalid) {
                    error = date > System.currentTimeMillis() ? R.string.cash_history_future
                        : R.string.cash_history_unavailable_amount;
                } catch (Exception failure) {
                    error = R.string.cash_history_preview_failed;
                }
                CashStore.Preview loaded = result;
                int failureMessage = error;
                activity.runOnUiThread(() -> {
                    previewRunning = false;
                    if (!isActive() || saving) return;
                    if (generation != previewGeneration) {
                        if (previewAmount != null)
                            previewHandler.postAtTime(previewWork, previewDueAt);
                    } else {
                        showPreview(loaded, failureMessage);
                    }
                });
            });
        }

        private void showPreview(CashStore.Preview result, int error) {
            // A transient preview failure still permits a write attempt.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                error == R.string.cash_history_preview_failed || (result != null && !result.insufficient));
            if (error != 0) {
                preview.setText(error);
                preview.setVisibility(View.VISIBLE);
                return;
            }
            if (result.insufficient) {
                preview.setText(activity.getString(R.string.cash_history_preview_insufficient,
                    dateText(result.shortfallDate) + " · "
                        + timeText(result.shortfallDate),
                    cashBalanceValue(result.availableAtShortfall),
                    cashBalanceValue(result.requiredAtShortfall)));
                feedback.setVisibility(View.GONE);
                if (returnDraft == null && result.recoveryDate > 0) {
                    recover.setVisibility(View.VISIBLE);
                    recover.setOnClickListener(v -> {
                        CashDraft draft = new CashDraft(replacing, timestamp,
                            amount.getText().toString(), isWithdrawal(),
                            note.getText().toString(), selectedTags(), original);
                        String depositAmount = hideBalances.getAsBoolean()
                            ? "" : cashInput(result.shortfall);
                        open(new CashDraft(null, result.recoveryDate, depositAmount, false, "",
                            java.util.Collections.emptyList(), null), draft);
                    });
                }
            } else if (result.affectsBalance) {
                preview.setText(activity.getString(R.string.cash_history_preview_balance,
                    cashBalanceValue(result.currentBalance), cashBalanceValue(result.resultingBalance)));
            } else {
                preview.setText(activity.getString(R.string.cash_history_preview_history_only,
                    cashBalanceValue(result.currentBalance)));
            }
            preview.setVisibility(View.VISIBLE);
        }

        private void restoreDraft() {
            if (returnDraft != null && activeEditor == this) open(returnDraft, null);
        }

        private EditText annotationInput(int hint) {
            EditText field = new EditText(activity);
            field.setHint(hint);
            field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            field.setSaveEnabled(false);
            return field;
        }

        private List<String> selectedTags() {
            List<String> selected = new ArrayList<>(tags);
            String typed = tag.getText().toString().trim();
            if (!typed.isEmpty() && selected.stream().noneMatch(current -> BalanceData.sameTag(current, typed)))
                selected.add(typed);
            return selected;
        }

        private void addTypedTag() {
            List<String> selected = selectedTags();
            tags.clear();
            tags.addAll(selected);
            tag.setText("");
            renderTags();
        }

        private void renderTags() {
            tagRows.removeAllViews();
            for (String label : tags) {
                TextView remove = ExpressiveUi.button(activity, label + "  ×", false);
                remove.setContentDescription(activity.getString(R.string.cash_history_remove_tag, label));
                remove.setOnClickListener(v -> {
                    tags.remove(label);
                    renderTags();
                });
                tagRows.addView(remove, margins(2, 2));
            }
        }

        private void save() {
            if (!isActive() || saving) return;
            feedback.setVisibility(View.GONE);
            Long signedAmount = parseAmount();
            if (signedAmount == null) {
                amount.setError(activity.getString(R.string.cash_history_invalid_amount));
                amount.requestFocus();
                return;
            }
            if (timestamp > System.currentTimeMillis()) {
                showCashFeedback(feedback, R.string.cash_history_future);
                return;
            }
            saving = true;
            previewGeneration++;
            previewHandler.removeCallbacks(previewWork);
            setBusy(true);
            long date = timestamp;
            CashStore.Annotation annotation = new CashStore.Annotation(
                note.getText().toString(), selectedTags());
            Context app = activity.getApplicationContext();
            new Thread(() -> {
                int result = write(app, date, signedAmount, annotation);
                activity.runOnUiThread(() -> {
                    if (!isActive()) return;
                    if (result == 0) {
                        onChanged.run();
                        if (returnDraft != null) restoreDraft();
                        else dialog.dismiss();
                    } else {
                        saving = false;
                        setBusy(false);
                        showCashFeedback(feedback, result);
                        requestPreview();
                    }
                });
                if (result == 0) {
                    try {
                        BalanceWidgetProvider.updateAll(app);
                    } catch (RuntimeException ignored) {
                        // The cash change is already saved.
                    }
                }
            }, "cash-save").start();
        }

        private int write(Context app, long date, long signedAmount, CashStore.Annotation annotation) {
            try {
                synchronized (BalanceData.class) {
                    if (canceled || isLocked(app)) return R.string.cash_history_save_failed;
                    if (!CashStore.isEnabled(app)) return R.string.cash_history_disabled;
                    if (replacing == null) CashStore.add(app, date, signedAmount, annotation);
                    else CashStore.update(app, replacing, date, signedAmount, original, annotation);
                }
                return 0;
            } catch (CashStore.StaleCashTransactionException stale) {
                return R.string.cash_history_stale;
            } catch (CashStore.InsufficientCashException insufficient) {
                return R.string.cash_history_insufficient;
            } catch (IllegalArgumentException invalid) {
                return date > System.currentTimeMillis() ? R.string.cash_history_future
                    : R.string.cash_history_unavailable_amount;
            } catch (Exception failure) {
                return R.string.cash_history_save_failed;
            }
        }

        private void setBusy(boolean busy) {
            setEnabled(form, !busy);
            dialog.setCancelable(!busy);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        }
    }

    private void showCashDatePicker(long timestamp, LongConsumer update) {
        CalDate current = calOf(timestamp, iranCalendar);
        CalendarRangePicker picker = new CalendarRangePicker(activity, iranCalendar, current, null,
            CalDate.today(iranCalendar), this::showDialog);
        LinearLayout content = picker.content();
        TextView feedback = cashFeedback(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.cash_history_choose_date)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.lock_cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                CalDate date = picker.picked[0];
                Calendar clock = Calendar.getInstance(Locale.US);
                clock.setTimeInMillis(timestamp);
                if (current.sameDay(date) && timestamp <= System.currentTimeMillis()) {
                    dialog.dismiss();
                } else {
                    applyCashTimestamp(dialog, feedback, date, clock.get(Calendar.HOUR_OF_DAY),
                        clock.get(Calendar.MINUTE), update);
                }
            }));
        showDialog(dialog);
    }

    private void showCashTimePicker(long timestamp, LongConsumer update) {
        Calendar clock = Calendar.getInstance(Locale.US);
        clock.setTimeInMillis(timestamp);
        TimePicker picker = new TimePicker(activity);
        picker.setIs24HourView(true);
        picker.setHour(clock.get(Calendar.HOUR_OF_DAY));
        picker.setMinute(clock.get(Calendar.MINUTE));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(picker);
        TextView feedback = cashFeedback(content);
        feedback.setPadding(dp(24), 0, dp(24), 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.cash_history_choose_time)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.lock_cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                picker.clearFocus();
                if (picker.getHour() == clock.get(Calendar.HOUR_OF_DAY)
                        && picker.getMinute() == clock.get(Calendar.MINUTE)
                        && timestamp <= System.currentTimeMillis()) {
                    dialog.dismiss();
                } else {
                    applyCashTimestamp(dialog, feedback, calOf(timestamp, iranCalendar),
                        picker.getHour(), picker.getMinute(), update);
                }
            }));
        showDialog(dialog);
    }

    private void applyCashTimestamp(AlertDialog dialog, TextView feedback, CalDate date, int hour,
            int minute, LongConsumer update) {
        long timestamp;
        try {
            Calendar clock = Calendar.getInstance(Locale.US);
            clock.clear();
            clock.setLenient(false);
            int[] gregorian = date.toGregorian(iranCalendar);
            clock.set(gregorian[0], gregorian[1] - 1, gregorian[2], hour, minute);
            timestamp = clock.getTimeInMillis();
        } catch (IllegalArgumentException invalidLocalTime) {
            showCashFeedback(feedback, R.string.cash_history_invalid_time);
            return;
        }
        if (timestamp > System.currentTimeMillis()) {
            showCashFeedback(feedback, R.string.cash_history_future);
            return;
        }
        if (timestamp <= 0) {
            showCashFeedback(feedback, R.string.cash_history_too_early);
            return;
        }
        update.accept(timestamp);
        dialog.dismiss();
    }

    private String dateText(long date) {
        return CalendarRangePicker.dateText(calOf(date, iranCalendar), iranCalendar, persian);
    }

    private String timeText(long date) {
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(date));
        return persian ? faDigitsString(time) : time;
    }

    private TextView cashFeedback(LinearLayout content) {
        TextView feedback = text("", 13, ExpressiveUi.color(activity, R.color.negative));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        feedback.setVisibility(View.GONE);
        content.addView(feedback, margins(4, 4));
        return feedback;
    }

    private static void showCashFeedback(TextView feedback, int message) {
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }

    private int dp(float value) {
        return ExpressiveUi.dp(activity, value);
    }

    private LinearLayout.LayoutParams margins(float top, float bottom) {
        return ExpressiveUi.widthMatch(activity, top, bottom);
    }

    private TextView text(String value, float size, int color) {
        return ExpressiveUi.label(activity, value, size, color);
    }
}
