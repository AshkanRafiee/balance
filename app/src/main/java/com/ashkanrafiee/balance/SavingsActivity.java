package com.ashkanrafiee.balance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A private, manual valuation page for holdings kept separate from bank balances. */
public final class SavingsActivity extends Activity {
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP"};
    private static final String[] COIN_VARIANTS = {
        SavingsAsset.COIN_EMAMI,
        SavingsAsset.COIN_BAHAR,
        SavingsAsset.COIN_HALF,
        SavingsAsset.COIN_ROB,
        SavingsAsset.COIN_GRAM,
        SavingsAsset.COIN_OTHER
    };

    private LinearLayout pageShell;
    private LinearLayout body;
    private LockOverlay lockOverlay;
    private BottomNavigation navigation;

    /** Every dialog on this page is tracked so none can remain above the lock screen. */
    final List<AlertDialog> dialogs = new ArrayList<>();

    private final List<SavingsAsset> assets = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
        runnable -> new Thread(runnable, "savings-io"));

    private int bg;
    private int panel;
    private int fg;
    private int muted;
    private int accent;
    private boolean hidden;
    private boolean destroyed;
    private boolean writePending;
    private int loadGeneration;
    private int editorGeneration;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(ThemeHelper.wrap(base)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        bg = color(R.color.bg);
        panel = color(R.color.panel);
        fg = color(R.color.fg);
        muted = color(R.color.muted);
        accent = color(R.color.accent);

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(panel);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(panel);
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));

        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(bg);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(bg);
        pageShell = shell;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), 0, dp(20), 0);
        shell.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));

        FrameLayout navigationArea = new FrameLayout(this);
        navigationArea.setBackgroundColor(panel);
        navigation = new BottomNavigation(
            this, BottomNavigation.SAVINGS, this::navigateTopLevel);
        navigationArea.addView(navigation, new FrameLayout.LayoutParams(-1, -2));
        shell.addView(navigationArea, new LinearLayout.LayoutParams(-1, -2));

        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets systemBars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars());
                top = systemBars.top;
                bottom = systemBars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            shell.setPadding(0, top + dp(14), 0, 0);
            navigationArea.setPadding(0, 0, 0, bottom);
            return insets;
        });

        page.addView(buildHeader(), layoutParams(-1, 52, 0, 0, 0, 12));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        host.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);

        lockOverlay = new LockOverlay(this);
        lockOverlay.setUnlockListener(this::onUnlocked);
        lockOverlay.setCancelListener(() -> {
            updateLockOverlay();
            updateSecureFlag();
        });
        host.addView(lockOverlay, new FrameLayout.LayoutParams(-1, -1));
        lockOverlay.setVisibility(View.GONE);

        refreshMask();
        render();
        updateSecureFlag();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LockManager.registerActivityStart(this);
        if (isLocked()) {
            lockOverlay.showLock();
        } else {
            lockOverlay.hide();
        }
        updateSecureFlag();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LockManager.cancelPendingLock();
        refreshMask();
        render();
        loadAssets();
        updateLockOverlay();
        updateSecureFlag();
    }

    @Override
    protected void onPause() {
        dismissDialogs();

        if (BalanceData.isAutoHide(this)) {
            hidden = true;
            render();
        }

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

    @Override
    protected void onStop() {
        dismissDialogs();
        if (LockManager.isEnabled(this) && LockManager.registerActivityStop()) {
            lockOverlay.showLock();
            lockOverlay.setAutoFingerprintEnabled(false);
        } else {
            lockOverlay.hide();
        }
        updateSecureFlag();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadGeneration++;
        dismissDialogs();
        ioExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishWithoutTransition();
    }

    private void onUnlocked() {
        if (!isActivityAlive()) {
            return;
        }
        updateSecureFlag();
        render();
    }

    private void updateLockOverlay() {
        if (isLocked()) {
            if (!lockOverlay.isShowing()) {
                lockOverlay.showLock();
            }
        } else {
            lockOverlay.hide();
        }
    }

    private void updateSecureFlag() {
        boolean lockEnabled = LockManager.isEnabled(this);
        if (lockEnabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        boolean locked = lockEnabled && LockManager.isSessionLocked();
        if (locked) {
            dismissDialogs();
        }
        for (AlertDialog dialog : dialogs) {
            secureDialog(dialog);
        }
        if (pageShell != null) {
            pageShell.setImportantForAccessibility(locked
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            pageShell.setDescendantFocusability(locked
                ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (lockOverlay != null) {
            lockOverlay.setImportantForAccessibility(locked
                ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (locked && lockOverlay.isShowing() && !lockOverlay.hasFocus()) {
                pageShell.clearFocus();
                lockOverlay.requestFocus();
            }
        }
    }

    private void finishWithoutTransition() {
        finish();
        disableCloseTransition();
    }

    private void navigateTopLevel(int tab) {
        if (isLocked()) {
            return;
        }
        if (tab == BottomNavigation.SAVINGS) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab);
        if (Build.VERSION.SDK_INT >= 34) {
            disableOpenTransition();
            disableCloseTransition();
            startActivity(intent);
        } else {
            startActivity(intent);
            disableOpenTransition();
        }
    }

    @SuppressWarnings("deprecation")
    private void disableOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    @SuppressWarnings("deprecation")
    private void disableCloseTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private LinearLayout buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = text(isRtl() ? "›" : "‹", 24, fg);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.scheduled_back));
        back.setBackground(ripple(rounded(panel, 24)));
        back.setOnClickListener(view -> finishWithoutTransition());
        bar.addView(back, layoutParams(48, 48, 0, 0, 0, 0));

        TextView title = text(getString(R.string.savings_heading), 22, fg);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1);
        titleParams.setMarginStart(dp(10));
        bar.addView(title, titleParams);

        TextView add = text("+", 28, accent);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription(getString(R.string.savings_add));
        add.setBackground(ripple(rounded(panel, 24)));
        add.setOnClickListener(view -> showEditor(null));
        bar.addView(add, layoutParams(48, 48, 0, 0, 0, 0));

        TextView mask = text("◉", 22, accent);
        mask.setGravity(Gravity.CENTER);
        mask.setContentDescription(getString(R.string.widget_action_mask));
        mask.setBackground(ripple(rounded(panel, 24)));
        mask.setOnClickListener(view -> {
            if (isLocked()) {
                return;
            }
            hidden = !hidden;
            getSharedPreferences(BalanceData.PREFS_PREF, MODE_PRIVATE)
                .edit()
                .putBoolean(BalanceData.KEY_HIDDEN, hidden)
                .apply();
            render();
        });
        bar.addView(mask, 1, layoutParams(48, 48, 0, 0, 0, 0));
        return bar;
    }

    private void loadAssets() {
        if (!isActivityAlive()) {
            return;
        }
        final int generation = ++loadGeneration;
        ioExecutor.execute(() -> {
            try {
                List<SavingsAsset> loaded = BalanceData.readSavingsAssets(getApplicationContext());
                runOnUiThread(() -> {
                    if (!isActivityAlive() || generation != loadGeneration) {
                        return;
                    }
                    assets.clear();
                    assets.addAll(loaded);
                    render();
                });
            } catch (IllegalStateException exception) {
                runOnUiThread(() -> {
                    if (!isActivityAlive() || generation != loadGeneration) {
                        return;
                    }
                    body.removeAllViews();
                    addHint(getString(R.string.savings_load_failed));
                });
            }
        });
    }

    private void render() {
        if (body == null) {
            return;
        }

        body.removeAllViews();
        long total = SavingsAsset.totalRial(assets);

        body.addView(summaryCard(total), layoutParams(-1, -2, 0, 0, 0, 14));
        if (assets.isEmpty()) {
            addHint(getString(R.string.savings_empty));
            addAction(getString(R.string.savings_add), () -> showEditor(null));
            return;
        }

        for (SavingsAsset asset : assets) {
            addAssetRow(asset);
        }
    }

    private LinearLayout summaryCard(long total) {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.addView(text(getString(R.string.savings_total_label), 13, muted),
            layoutParams(-1, -2, 0, 0, 0, 5));

        String amount = isMasked()
            ? getString(R.string.accessibility_total_masked)
            : amountText(total);
        TextView value = text(amount, 25, fg);
        value.setTypeface(null, Typeface.BOLD);
        card.addView(value, layoutParams(-1, -2, 0, 0, 0, 4));
        card.addView(text(getString(R.string.savings_manual_estimate), 12, muted),
            layoutParams(-1, -2, 0, 0, 0, 0));
        return card;
    }

    private void addAssetRow(SavingsAsset asset) {
        LinearLayout row = card();
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> showEditor(asset));

        boolean masked = isMasked();
        if (masked) {
            row.setContentDescription(getString(R.string.accessibility_total_masked));
        }
        String titleText = masked
            ? getString(R.string.accessibility_total_masked)
            : displayName(asset);
        TextView title = text(titleText, 15, fg);
        title.setTypeface(null, Typeface.BOLD);
        row.addView(title, layoutParams(-1, -2, 0, 0, 0, 4));

        String detail = masked
            ? getString(R.string.accessibility_total_masked)
            : amountText(asset.totalRial()) + " · " + kindText(asset);
        row.addView(text(detail, 12, muted), layoutParams(-1, -2, 0, 0, 0, 0));
        if (!masked) {
            String valuation = getString(quantityHint(asset.kind)) + ": "
                + formatScaled(asset.quantityScaled) + " · "
                + getString(unitValueLabelResource(asset.kind)) + ": "
                + amountText(asset.unitValueRial);
            row.addView(text(valuation, 12, muted), layoutParams(-1, -2, 0, 4, 0, 0));
        }
        body.addView(row, layoutParams(-1, -2, 0, 0, 0, 8));
    }

    /** Package-private for instrumentation tests and the page's existing test contract. */
    void showEditor(SavingsAsset existing) {
        if (!isActivityAlive() || isLocked() || writePending) {
            return;
        }
        if (isMasked()) {
            Toast.makeText(this, getString(R.string.savings_unmask_to_edit), Toast.LENGTH_SHORT)
                .show();
            return;
        }

        dismissDialogs();
        final boolean editing = existing != null;
        final int generation = editorGeneration;
        final boolean initialToman = isToman();
        final String initialCurrencyLabel = CurrencyHelper.label(this);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);

        EditText label = field(getString(R.string.savings_label_hint), InputType.TYPE_CLASS_TEXT,
            editing ? existing.label : "");
        label.setTag("savings_label");
        form.addView(labeled(getString(R.string.savings_label_hint), label),
            layoutParams(-1, -2, 0, 0, 0, 8));

        Spinner kind = spinner(kindLabels());
        kind.setTag("savings_kind");
        kind.setSelection(editing ? existing.kind.ordinal() : 0);
        form.addView(labeled(getString(R.string.savings_kind), kind),
            layoutParams(-1, -2, 0, 0, 0, 6));

        Spinner karat = spinner(new String[]{"18", "24"});
        karat.setTag("savings_karat");
        karat.setSelection(editing && existing.karat == SavingsAsset.KARAT_24 ? 1 : 0);
        LinearLayout karatRow = labeled(getString(R.string.savings_karat), karat);
        karatRow.setTag("savings_karat_row");
        form.addView(karatRow, layoutParams(-1, -2, 0, 0, 0, 6));

        Spinner coin = spinner(coinLabels());
        coin.setTag("savings_coin");
        coin.setSelection(editing ? coinIndex(existing.variant) : 0);
        LinearLayout coinRow = labeled(getString(R.string.savings_coin_type), coin);
        coinRow.setTag("savings_coin_row");
        form.addView(coinRow, layoutParams(-1, -2, 0, 0, 0, 6));

        Spinner age = spinner(new String[]{
            getString(R.string.savings_coin_before),
            getString(R.string.savings_coin_from)
        });
        age.setTag("savings_coin_age");
        age.setSelection(editing && SavingsAsset.AGE_FROM_1386.equals(coinAge(existing)) ? 1 : 0);
        LinearLayout ageRow = labeled(getString(R.string.savings_coin_age), age);
        ageRow.setTag("savings_coin_age_row");
        form.addView(ageRow, layoutParams(-1, -2, 0, 0, 0, 6));

        Spinner currency = spinner(new String[]{"USD", "EUR", "GBP", getString(R.string.savings_other)});
        currency.setTag("savings_currency");
        currency.setSelection(editing ? currencyIndex(existing.currencyCode) : 0);
        LinearLayout currencyRow = labeled(getString(R.string.savings_currency), currency);
        currencyRow.setTag("savings_currency_row");
        form.addView(currencyRow, layoutParams(-1, -2, 0, 0, 0, 6));

        EditText custom = field(getString(R.string.savings_custom_currency), InputType.TYPE_CLASS_TEXT,
            editing && existing.currencyCode.startsWith(CurrencyHelper.CUSTOM_PREFIX)
                ? existing.currencyCode.substring(CurrencyHelper.CUSTOM_PREFIX.length())
                : "");
        custom.setTag("savings_custom");
        LinearLayout customRow = labeled(getString(R.string.savings_custom_currency), custom);
        customRow.setTag("savings_custom_row");
        form.addView(customRow, layoutParams(-1, -2, 0, 0, 0, 6));

        String initialQuantity = editing ? formatScaled(existing.quantityScaled) : "";
        EditText quantity = field(getString(R.string.savings_quantity),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
            initialQuantity);
        quantity.setTag("savings_quantity");
        quantity.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        TextView quantityLabel = labelView(getString(R.string.savings_quantity));
        LinearLayout quantityRow = labeled(quantityLabel, quantity);
        quantityRow.setTag("savings_quantity_row");
        form.addView(quantityRow, layoutParams(-1, -2, 0, 0, 0, 8));

        String initialAmount = editing
            ? CurrencyHelper.amount(initialToman, LocaleHelper.isPersian(this), existing.unitValueRial)
            : "";
        EditText unitValue = field(unitValueLabelText(SavingsAsset.Kind.GOLD_GRAM, initialCurrencyLabel),
            InputType.TYPE_CLASS_NUMBER, initialAmount);
        unitValue.setTag("savings_unit_value");
        unitValue.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        TextView unitValueLabel = labelView(
            unitValueLabelText(SavingsAsset.Kind.GOLD_GRAM, initialCurrencyLabel));
        LinearLayout unitValueRow = labeled(unitValueLabel, unitValue);
        unitValueRow.setTag("savings_unit_value_row");
        form.addView(unitValueRow, layoutParams(-1, -2, 0, 0, 0, 8));

        TextView help = text(getString(R.string.savings_manual_value_note), 12, muted);
        form.addView(help, layoutParams(-1, -2, 0, 0, 0, 8));

        TextView error = text("", 13, color(R.color.negative));
        error.setTag("savings_error");
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error, layoutParams(-1, -2, 0, 0, 0, 4));

        final String initialAmountText = unitValue.getText().toString();
        final String initialQuantityText = quantity.getText().toString();

        Runnable updateFields = () -> {
            SavingsAsset.Kind selected = selectedKind(kind);
            boolean gold = selected == SavingsAsset.Kind.GOLD_GRAM
                || selected == SavingsAsset.Kind.GOLD_BAR;
            boolean coinKind = selected == SavingsAsset.Kind.COIN;
            boolean currencyKind = selected == SavingsAsset.Kind.CURRENCY;

            karatRow.setVisibility(gold ? View.VISIBLE : View.GONE);
            coinRow.setVisibility(coinKind ? View.VISIBLE : View.GONE);
            ageRow.setVisibility(coinKind ? View.VISIBLE : View.GONE);
            currencyRow.setVisibility(currencyKind ? View.VISIBLE : View.GONE);
            boolean customCurrency = currencyKind && currency.getSelectedItemPosition() == 3;
            customRow.setVisibility(customCurrency ? View.VISIBLE : View.GONE);
            custom.setVisibility(customCurrency ? View.VISIBLE : View.GONE);

            String quantityHint = getString(quantityHint(selected));
            quantityLabel.setText(quantityHint);
            quantity.setHint(quantityHint);
            boolean wholeCount = coinKind || selected == SavingsAsset.Kind.GOLD_BAR;
            int quantityInputType = wholeCount
                ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            if (quantity.getInputType() != quantityInputType) {
                quantity.setInputType(quantityInputType);
            }
            String unitLabel = unitValueLabelText(selected, initialCurrencyLabel);
            unitValueLabel.setText(unitLabel);
            unitValue.setHint(unitLabel);
        };

        AdapterView.OnItemSelectedListener listener = selectionListener(updateFields);
        kind.setOnItemSelectedListener(listener);
        currency.setOnItemSelectedListener(listener);
        updateFields.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(editing ? getString(R.string.savings_edit) : getString(R.string.savings_add))
            .setView(scrollForm(form))
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.note_save), null);
        if (editing) {
            builder.setNeutralButton(getString(R.string.savings_delete), null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (writePending || !isActivityAlive() || isLocked()) {
                    return;
                }
                clearInlineError(error);
                try {
                    SavingsAsset asset = makeAsset(
                        existing,
                        label.getText().toString(),
                        kind.getSelectedItemPosition(),
                        karat.getSelectedItemPosition(),
                        coin.getSelectedItemPosition(),
                        age.getSelectedItemPosition(),
                        currency.getSelectedItemPosition(),
                        custom.getText().toString(),
                        quantity.getText().toString(),
                        unitValue.getText().toString(),
                        initialAmountText,
                        initialQuantityText,
                        initialToman);
                    persistAsset(asset, dialog, form, error, generation);
                } catch (IllegalArgumentException exception) {
                    showInlineError(error, R.string.savings_invalid);
                }
            });

            if (editing) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    showDeleteConfirmation(existing, dialog, form, error, generation));
            }
        });
        trackDialog(dialog);
    }

    private SavingsAsset makeAsset(
            SavingsAsset existing,
            String label,
            int kindIndex,
            int karatIndex,
            int coinIndex,
            int ageIndex,
            int currencyIndex,
            String customCurrency,
            String quantity,
            String value,
            String initialAmountText,
            String initialQuantityText,
            boolean initialToman) {
        SavingsAsset.Kind kind = SavingsAsset.Kind.values()[kindIndex];
        int karat = kind == SavingsAsset.Kind.GOLD_GRAM || kind == SavingsAsset.Kind.GOLD_BAR
            ? (karatIndex == 1 ? SavingsAsset.KARAT_24 : SavingsAsset.KARAT_18)
            : 0;
        String variant = kind == SavingsAsset.Kind.COIN ? coinVariant(coinIndex) : "";
        String coinAge = kind == SavingsAsset.Kind.COIN
            ? (ageIndex == 1 ? SavingsAsset.AGE_FROM_1386 : SavingsAsset.AGE_BEFORE_1386)
            : "";
        String code = kind == SavingsAsset.Kind.CURRENCY
            ? currencyCode(currencyIndex, customCurrency) : "";

        boolean quantityUnchanged = existing != null
            && quantity.equals(initialQuantityText);
        long quantityScaled = quantityUnchanged
            ? existing.quantityScaled
            : SavingsAsset.parseScaled(quantity);

        // The editor field is a display value. Once it is left untouched, the stored raw rial value
        // is authoritative even if the app currency preference changed while this dialog was open.
        boolean amountUnchanged = existing != null && value.equals(initialAmountText);
        long unitRial = amountUnchanged
            ? existing.unitValueRial
            : SavingsAsset.parseValueRial(value, initialToman);

        return new SavingsAsset(
            existing == null ? null : existing.id,
            label,
            kind,
            karat,
            variant,
            coinAge,
            code,
            quantityScaled,
            unitRial);
    }

    private void persistAsset(SavingsAsset asset, AlertDialog editor, LinearLayout form,
            TextView error, int generation) {
        writeAsset(() -> BalanceData.saveSavingsAsset(getApplicationContext(), asset),
            editor, form, error, generation);
    }

    private void deleteAsset(SavingsAsset asset, AlertDialog editor, LinearLayout form,
            TextView error, int generation) {
        writeAsset(() -> BalanceData.deleteSavingsAsset(getApplicationContext(), asset.id),
            editor, form, error, generation);
    }

    /** Writes and reads share one FIFO queue; UI callbacks never resurrect an old editor. */
    private void writeAsset(Runnable write, AlertDialog editor, LinearLayout form,
            TextView error, int generation) {
        if (!isActivityAlive() || isLocked() || writePending || !editor.isShowing()
                || generation != editorGeneration) {
            return;
        }
        writePending = true;
        loadGeneration++;
        clearInlineError(error);
        setEditorSaving(editor, form, true);
        ioExecutor.execute(() -> {
            int errorResource = 0;
            try {
                write.run();
            } catch (IllegalArgumentException exception) {
                errorResource = R.string.savings_invalid;
            } catch (IllegalStateException exception) {
                errorResource = R.string.savings_save_failed;
            }
            final int result = errorResource;
            runOnUiThread(() -> {
                if (!isActivityAlive()) {
                    return;
                }
                writePending = false;
                if (generation == editorGeneration && editor.isShowing() && !isLocked()) {
                    setEditorSaving(editor, form, false);
                    if (result == 0) {
                        editor.dismiss();
                    } else {
                        showInlineError(error, result);
                    }
                }
                if (result == 0) {
                    loadAssets();
                }
            });
        });
    }

    private void setEditorSaving(AlertDialog editor, ViewGroup form, boolean saving) {
        setFormEnabled(form, !saving);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);
        TextView delete = editor.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (delete != null) {
            delete.setEnabled(!saving);
        }
        editor.setCancelable(!saving);
        editor.setCanceledOnTouchOutside(!saving);
    }

    private void setFormEnabled(ViewGroup form, boolean enabled) {
        for (int i = 0; i < form.getChildCount(); i++) {
            View child = form.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof ViewGroup) {
                setFormEnabled((ViewGroup) child, enabled);
            }
        }
    }

    private void showDeleteConfirmation(
            SavingsAsset asset, AlertDialog editor, LinearLayout form,
            TextView editorError, int generation) {
        if (!isActivityAlive() || isMasked() || writePending || !editor.isShowing()
                || generation != editorGeneration || dialogs.size() > 1) {
            return;
        }
        AlertDialog confirmation = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.savings_delete))
            .setMessage(getString(R.string.savings_delete_message))
            .setNegativeButton(getString(R.string.dialog_hard_refresh_cancel), null)
            .setPositiveButton(getString(R.string.savings_delete), null)
            .create();
        confirmation.setOnShowListener(ignored -> confirmation.getButton(
            AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                confirmation.dismiss();
                deleteAsset(asset, editor, form, editorError, generation);
            }));
        trackDialog(confirmation);
    }

    private boolean isActivityAlive() {
        return !destroyed && !isFinishing() && !isDestroyed();
    }

    private void trackDialog(AlertDialog dialog) {
        if (!isActivityAlive() || isMasked()) {
            return;
        }
        dialogs.add(dialog);
        dialog.setOnDismissListener(dismissed -> dialogs.remove(dialog));
        dialog.show();
        secureDialog(dialog);
    }

    private void secureDialog(AlertDialog dialog) {
        if (dialog.getWindow() == null) {
            return;
        }
        if (LockManager.isEnabled(this)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void dismissDialogs() {
        editorGeneration++;
        for (AlertDialog dialog : new ArrayList<>(dialogs)) {
            dialog.dismiss();
        }
        dialogs.clear();
    }

    private void showInlineError(TextView error, int resource) {
        error.setText(getString(resource));
        error.setContentDescription(getString(resource));
        error.setVisibility(View.VISIBLE);
    }

    private void clearInlineError(TextView error) {
        error.setText("");
        error.setVisibility(View.GONE);
    }

    private void refreshMask() {
        hidden = BalanceData.isHidden(this) || BalanceData.isAutoHide(this);
    }

    private boolean isMasked() {
        return hidden || isLocked();
    }

    private boolean isLocked() {
        return LockManager.isEnabled(this) && LockManager.isSessionLocked();
    }

    private boolean isToman() {
        return CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(this));
    }

    private String amountText(long amount) {
        return CurrencyHelper.amount(this, amount) + " " + CurrencyHelper.label(this);
    }

    private String displayName(SavingsAsset asset) {
        return asset.label.isEmpty() ? kindText(asset) : asset.label;
    }

    private String formatScaled(long scaled) {
        return BigDecimal.valueOf(scaled, 3).stripTrailingZeros().toPlainString();
    }

    private String currencyCode(int index, String customCurrency) {
        if (index >= 0 && index < CURRENCIES.length) {
            return CURRENCIES[index];
        }
        return CurrencyHelper.CUSTOM_PREFIX + customCurrency.trim();
    }

    private int currencyIndex(String code) {
        if ("EUR".equals(code)) {
            return 1;
        }
        if ("GBP".equals(code)) {
            return 2;
        }
        return code != null && code.startsWith(CurrencyHelper.CUSTOM_PREFIX) ? 3 : 0;
    }

    private int coinIndex(String code) {
        for (int i = 0; i < COIN_VARIANTS.length; i++) {
            if (COIN_VARIANTS[i].equals(code)) {
                return i;
            }
        }
        return 0;
    }

    private String coinVariant(int index) {
        int safeIndex = Math.max(0, Math.min(COIN_VARIANTS.length - 1, index));
        return COIN_VARIANTS[safeIndex];
    }

    private String[] kindLabels() {
        return new String[]{
            getString(R.string.savings_gold_gram),
            getString(R.string.savings_gold_bar),
            getString(R.string.savings_silver),
            getString(R.string.savings_coin),
            getString(R.string.savings_currency)
        };
    }

    private String[] coinLabels() {
        return new String[]{
            getString(R.string.coin_emami),
            getString(R.string.coin_bahar),
            getString(R.string.coin_half),
            getString(R.string.coin_rob),
            getString(R.string.coin_gram),
            getString(R.string.coin_other)
        };
    }

    private String kindText(SavingsAsset asset) {
        switch (asset.kind) {
            case GOLD_GRAM:
                return getString(R.string.savings_gold_gram) + " · "
                    + getString(R.string.savings_gold_karat, asset.karat);
            case GOLD_BAR:
                return getString(R.string.savings_gold_bar) + " · "
                    + getString(R.string.savings_gold_karat, asset.karat);
            case SILVER_GRAM:
                return getString(R.string.savings_silver);
            case COIN:
                return getString(R.string.savings_coin) + " · "
                    + coinLabel(asset.variant) + " · " + coinAgeLabel(coinAge(asset));
            case CURRENCY:
            default:
                return displayCurrencyCode(asset.currencyCode);
        }
    }

    private String displayCurrencyCode(String code) {
        if (code != null && code.startsWith(CurrencyHelper.CUSTOM_PREFIX)) {
            return code.substring(CurrencyHelper.CUSTOM_PREFIX.length());
        }
        return code == null ? "" : code;
    }

    private String coinLabel(String variant) {
        switch (variant) {
            case SavingsAsset.COIN_BAHAR:
                return getString(R.string.coin_bahar);
            case SavingsAsset.COIN_HALF:
                return getString(R.string.coin_half);
            case SavingsAsset.COIN_ROB:
                return getString(R.string.coin_rob);
            case SavingsAsset.COIN_GRAM:
                return getString(R.string.coin_gram);
            case SavingsAsset.COIN_OTHER:
                return getString(R.string.coin_other);
            case SavingsAsset.COIN_EMAMI:
            default:
                return getString(R.string.coin_emami);
        }
    }

    private String coinAgeLabel(String age) {
        return SavingsAsset.AGE_FROM_1386.equals(age)
            ? getString(R.string.savings_coin_from)
            : getString(R.string.savings_coin_before);
    }

    private String coinAge(SavingsAsset asset) {
        return asset.kind == SavingsAsset.Kind.COIN ? asset.coinAge : "";
    }

    private SavingsAsset.Kind selectedKind(Spinner kind) {
        return SavingsAsset.Kind.values()[kind.getSelectedItemPosition()];
    }

    private int quantityHint(SavingsAsset.Kind kind) {
        switch (kind) {
            case COIN:
                return R.string.savings_quantity_coins;
            case CURRENCY:
                return R.string.savings_quantity_currency;
            case GOLD_BAR:
                return R.string.savings_quantity_bars;
            case GOLD_GRAM:
            case SILVER_GRAM:
            default:
                return R.string.savings_quantity_grams;
        }
    }

    private int unitValueLabelResource(SavingsAsset.Kind kind) {
        switch (kind) {
            case GOLD_BAR:
                return R.string.savings_unit_value_per_bar;
            case COIN:
                return R.string.savings_unit_value_per_coin;
            case CURRENCY:
                return R.string.savings_unit_value_per_currency;
            case GOLD_GRAM:
            case SILVER_GRAM:
            default:
                return R.string.savings_unit_value_per_gram;
        }
    }

    private String unitValueLabelText(SavingsAsset.Kind kind, String currencyLabel) {
        return getString(unitValueLabelResource(kind)) + " (" + currencyLabel + ")";
    }

    private AdapterView.OnItemSelectedListener selectionListener(Runnable update) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                update.run();
            }
        };
    }

    private ScrollView scrollForm(View form) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form);
        return scroll;
    }

    private void addHint(String value) {
        TextView hint = text(value, 14, muted);
        hint.setPadding(dp(16), dp(16), dp(16), dp(16));
        hint.setBackground(rounded(panel, 16));
        body.addView(hint, layoutParams(-1, -2, 0, 0, 0, 8));
    }

    private void addAction(String label, Runnable action) {
        TextView button = text(label, 15, accent);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(ripple(rounded(panel, 16)));
        button.setOnClickListener(view -> action.run());
        body.addView(button, layoutParams(-1, -2, 0, 0, 0, 8));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(panel, 18));
        return card;
    }

    private LinearLayout labeled(String label, View control) {
        return labeled(labelView(label), control);
    }

    private LinearLayout labeled(TextView label, View control) {
        ensureId(control);
        label.setLabelFor(control.getId());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(label, layoutParams(-1, -2, 0, 0, 0, 2));
        row.addView(control, layoutParams(-1, -2, 0, 0, 0, 0));
        return row;
    }

    private TextView labelView(String value) {
        return text(value, 12, muted);
    }

    private void ensureId(View view) {
        if (view.getId() == View.NO_ID) {
            view.setId(View.generateViewId());
        }
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
        field.setBackground(rounded(panel, 12));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        return field;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(isRtl() ? Gravity.RIGHT : Gravity.LEFT);
        return text;
    }

    private LinearLayout.LayoutParams layoutParams(
            int width, int height, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        params.setMarginStart(dp(start));
        params.topMargin = dp(top);
        params.setMarginEnd(dp(end));
        params.bottomMargin = dp(bottom);
        return params;
    }

    private Drawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private Drawable ripple(Drawable background) {
        if (Build.VERSION.SDK_INT < 21) {
            return background;
        }
        return new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(35, 0, 0, 0)), background, null);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private int color(int resource) {
        return getResources().getColor(resource, getTheme());
    }

    private boolean isRtl() {
        return getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_RTL;
    }

}
