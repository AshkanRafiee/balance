package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** UI contracts for manual savings holdings, including their independent store and editor. */
@RunWith(AndroidJUnit4.class)
public class SavingsUiTest {
    private static final long TIMEOUT_MS = 20_000L;

    private Context context;
    private SavingsActivity activity;

    private SavedPreference language;
    private SavedPreference currency;
    private SavedPreference region;
    private SavedPreference hidden;
    private SavedPreference autoHide;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        language = SavedPreference.capture(context, "balance_language", "language");
        currency = SavedPreference.capture(context, "balance_currency", "currency");
        region = SavedPreference.capture(context, "balance_region", "region");
        hidden = SavedPreference.capture(context, BalanceData.PREFS_PREF, BalanceData.KEY_HIDDEN);
        autoHide = SavedPreference.capture(context, BalanceData.PREFS_PREF, BalanceData.KEY_AUTO_HIDE);

        finishOwnedActivities();
        // This test owns only the savings store. Bank balances, history and planned payments stay
        // untouched so a UI run cannot erase data belonging to another feature.
        BalanceData.writeSavingsAssets(context, Collections.emptyList());

        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        setHidden(false);
        BalanceData.setAutoHide(context, false);
    }

    @After public void tearDown() {
        finishOwnedActivities();
        BalanceData.writeSavingsAssets(context, Collections.emptyList());

        // Setters restore process-wide locale state before the exact preference presence/value is
        // put back. This matters when the original preference was absent rather than defaulted.
        LocaleHelper.setLanguage(context, stringValue(language, ""));
        CurrencyHelper.setCurrency(context, stringValue(currency, CurrencyHelper.CURRENCY_TOMAN));
        RegionHelper.setRegion(context, "int".equals(stringValue(region, "ir"))
            ? RegionHelper.REGION_INTERNATIONAL : RegionHelper.REGION_IRAN);
        setHidden(booleanValue(hidden, false));
        BalanceData.setAutoHide(context, booleanValue(autoHide, false));
        language.restore(context);
        currency.restore(context);
        region.restore(context);
        hidden.restore(context);
        autoHide.restore(context);
    }

    @Test public void savingsPageUsesTheModelTotalAfterItsAsyncRenderAndKeepsFourTabs() {
        List<SavingsAsset> source = Arrays.asList(
            new SavingsAsset("ui-gold", "Gold holding", SavingsAsset.Kind.GOLD_GRAM,
                SavingsAsset.KARAT_24, "", "", 1_250, 2_000_000L),
            new SavingsAsset("ui-silver", "Silver holding", SavingsAsset.Kind.SILVER_GRAM,
                0, "", "", 500, 100_000L));
        BalanceData.writeSavingsAssets(context, source);
        long expectedTotal = SavingsAsset.totalRial(BalanceData.readSavingsAssets(context));

        activity = launchSavings();
        String expectedAmount = CurrencyHelper.amount(activity, expectedTotal) + " "
            + CurrencyHelper.label(activity);
        await("the savings summary render", () -> textContent(activity).contains(expectedAmount));

        BottomNavigation bar = navigation(activity);
        assertNotNull(bar);
        assertEquals(BottomNavigation.SAVINGS, selectedTab(bar));
        assertEquals(4, childCount(bar));
        assertNotNull(findText(activity, activity.getString(R.string.savings_total_label)));
        assertNotNull(findText(activity, activity.getString(R.string.savings_manual_estimate)));
    }

    @Test public void editorAddsGoldWithSelectedPurityAndExposesOnlyRelevantRows() {
        activity = launchSavings();
        openEditor(null);
        AlertDialog editor = requireEditor();
        assertEditorFields(editor);

        assertTrue(isShown(editor, "savings_karat"));
        assertFalse(isShown(editor, "savings_coin"));
        assertFalse(isShown(editor, "savings_coin_age"));
        assertFalse(isShown(editor, "savings_currency"));
        assertFalse(isShown(editor, "savings_custom"));

        select(spinner(editor, "savings_karat"), 1); // 24 karat
        setText(editText(editor, "savings_label"), "24k gold");
        setText(editText(editor, "savings_quantity"), "1.250");
        setText(editText(editor, "savings_unit_value"), "2000000");
        clickPositive(editor);

        await("gold save", () -> BalanceData.readSavingsAssets(context).size() == 1
            && lastShowingDialog() == null);
        SavingsAsset saved = BalanceData.readSavingsAssets(context).get(0);
        assertEquals(SavingsAsset.Kind.GOLD_GRAM, saved.kind);
        assertEquals(SavingsAsset.KARAT_24, saved.karat);
        assertEquals(1_250L, saved.quantityScaled);
        assertEquals(2_000_000L, saved.unitValueRial);
        assertEquals(SavingsAsset.totalRial(Collections.singletonList(saved)), saved.totalRial());
    }

    @Test public void editingUntouchedRialValueUsesTheCurrencyAtEditorOpen() {
        SavingsAsset original = new SavingsAsset("currency-bound", "Rial holding",
            SavingsAsset.Kind.GOLD_GRAM, SavingsAsset.KARAT_18, "", "", 2_000, 12_345L);
        BalanceData.writeSavingsAssets(context, Collections.singletonList(original));
        // setUp deliberately opens every editor in Rial; retain that opening unit while the dialog
        // is on screen, then change the app preference underneath it.
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);

        activity = launchSavings();
        await("currency-bound row", () -> existingRow(original.label) != null);
        click(existingRow(original.label));
        await("currency-bound editor", () -> lastShowingDialog() != null
            && view(lastShowingDialog(), "savings_unit_value") != null);
        AlertDialog editor = requireEditor();
        String openingValue = textOf(editText(editor, "savings_unit_value"));
        assertTrue(openingValue.contains("12"));

        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        assertEquals("changing the app currency must not rewrite the untouched field", openingValue,
            textOf(editText(editor, "savings_unit_value")));
        clickPositive(editor);

        await("currency-bound save", () -> BalanceData.readSavingsAssets(context).size() == 1
            && lastShowingDialog() == null);
        SavingsAsset saved = BalanceData.readSavingsAssets(context).get(0);
        assertEquals(original.id, saved.id);
        assertEquals(2_000L, saved.quantityScaled);
        assertEquals(12_345L, saved.unitValueRial);
    }

    @Test public void coinCanBeAddedEditedAndDeletedThroughItsDialog() {
        activity = launchSavings();
        openEditor(null);
        AlertDialog editor = requireEditor();
        Spinner kind = spinner(editor, "savings_kind");
        select(kind, SavingsAsset.Kind.COIN.ordinal());
        final AlertDialog coinEditor = editor;
        await("coin conditional rows", () -> isShown(coinEditor, "savings_coin")
            && isShown(coinEditor, "savings_coin_age") && !isShown(coinEditor, "savings_karat")
            && !isShown(coinEditor, "savings_currency") && !isShown(coinEditor, "savings_custom"));

        select(spinner(editor, "savings_coin"), spinnerPosition(spinner(editor, "savings_coin"),
            activity.getString(R.string.coin_bahar)));
        select(spinner(editor, "savings_coin_age"), spinnerPosition(spinner(editor, "savings_coin_age"),
            activity.getString(R.string.savings_coin_before)));
        setText(editText(editor, "savings_label"), "Coin holding");
        setText(editText(editor, "savings_quantity"), "2");
        setText(editText(editor, "savings_unit_value"), "50000000");
        clickPositive(editor);

        await("coin add", () -> BalanceData.readSavingsAssets(context).size() == 1
            && lastShowingDialog() == null);
        SavingsAsset added = BalanceData.readSavingsAssets(context).get(0);
        assertEquals(SavingsAsset.Kind.COIN, added.kind);
        assertEquals(SavingsAsset.COIN_BAHAR, added.variant);
        assertEquals(SavingsAsset.AGE_BEFORE_1386, added.coinAge);
        assertEquals(2_000L, added.quantityScaled);
        assertEquals(50_000_000L, added.unitValueRial);

        await("coin row", () -> existingRow("Coin holding") != null);
        click(existingRow("Coin holding"));
        await("coin editor from existing row", () -> lastShowingDialog() != null
            && view(lastShowingDialog(), "savings_label") != null);
        editor = requireEditor();
        select(spinner(editor, "savings_coin"), spinnerPosition(spinner(editor, "savings_coin"),
            activity.getString(R.string.coin_emami)));
        select(spinner(editor, "savings_coin_age"), spinnerPosition(spinner(editor, "savings_coin_age"),
            activity.getString(R.string.savings_coin_from)));
        setText(editText(editor, "savings_label"), "Edited coin");
        clickPositive(editor);

        await("coin edit", () -> BalanceData.readSavingsAssets(context).size() == 1
            && "Edited coin".equals(BalanceData.readSavingsAssets(context).get(0).label));
        SavingsAsset edited = BalanceData.readSavingsAssets(context).get(0);
        assertEquals(added.id, edited.id);
        assertEquals(SavingsAsset.COIN_EMAMI, edited.variant);
        assertEquals(SavingsAsset.AGE_FROM_1386, edited.coinAge);
        await("edited coin description", () -> {
            String content = textContent(activity);
            return content.contains("Edited coin")
                && content.contains(activity.getString(R.string.coin_emami))
                && content.contains(activity.getString(R.string.savings_coin_from));
        });

        click(existingRow("Edited coin"));
        editor = requireEditor();
        click(editor.getButton(AlertDialog.BUTTON_NEUTRAL));
        await("coin delete confirmation", () -> activeWindowHasClickableText(
            activity.getString(R.string.savings_delete)));
        assertTrue(clickActiveWindowText(activity.getString(R.string.savings_delete)));
        await("coin delete", () -> BalanceData.readSavingsAssets(context).isEmpty()
            && lastShowingDialog() == null && !textContent(activity).contains("Edited coin"));
    }

    @Test public void currencyChoicesPersistUsdEurGbpAndCustomCodes() {
        activity = launchSavings();
        String[] expectedCodes = {"USD", "EUR", "GBP", "custom:CAD"};
        for (int i = 0; i < expectedCodes.length; i++) {
            openEditor(null);
            AlertDialog editor = requireEditor();
            select(spinner(editor, "savings_kind"), SavingsAsset.Kind.CURRENCY.ordinal());
            await("currency rows", () -> isShown(editor, "savings_currency")
                && !isShown(editor, "savings_karat") && !isShown(editor, "savings_coin")
                && !isShown(editor, "savings_coin_age"));

            Spinner currencySpinner = spinner(editor, "savings_currency");
            if (i == 0) {
                assertEquals(Arrays.asList("USD", "EUR", "GBP", activity.getString(R.string.savings_other)),
                    spinnerEntries(currencySpinner));
            }
            select(currencySpinner, i);
            if (i == 3) {
                await("custom currency field", () -> isShown(editor, "savings_custom"));
                setText(editText(editor, "savings_custom"), "CAD");
            } else {
                assertFalse(isShown(editor, "savings_custom"));
            }
            setText(editText(editor, "savings_label"), "Currency " + i);
            setText(editText(editor, "savings_quantity"), "1");
            setText(editText(editor, "savings_unit_value"), "1000");
            clickPositive(editor);
            final int expectedSize = i + 1;
            await("currency save " + i, () -> BalanceData.readSavingsAssets(context).size() == expectedSize
                && lastShowingDialog() == null);
        }

        List<SavingsAsset> saved = BalanceData.readSavingsAssets(context);
        assertEquals(4, saved.size());
        for (int i = 0; i < expectedCodes.length; i++) {
            assertEquals(expectedCodes[i], saved.get(i).currencyCode);
            assertEquals(SavingsAsset.Kind.CURRENCY, saved.get(i).kind);
        }
        long expectedTotal = SavingsAsset.totalRial(saved);
        await("currency total", () -> textContent(activity).contains(
            CurrencyHelper.amount(activity, expectedTotal) + " " + CurrencyHelper.label(activity)));
    }

    @Test public void maskedSavingsHideAmountsAndLabelsAndBlockEditingUntilTheEyeIsTapped() {
        SavingsAsset secret = new SavingsAsset("masked-ui", "Private gold", SavingsAsset.Kind.GOLD_GRAM,
            SavingsAsset.KARAT_18, "", "", 1_000, 98_765L);
        BalanceData.writeSavingsAssets(context, Collections.singletonList(secret));
        setHidden(true);
        activity = launchSavings();
        String amount = CurrencyHelper.amount(activity, secret.totalRial());

        await("masked savings render", () -> {
            String content = textContent(activity);
            return content.contains(activity.getString(R.string.savings_total_label))
                && !content.contains(secret.label) && !content.contains(amount);
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.showEditor(secret));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse("masked holdings must not open an editor", hasShowingDialog());

        click(findByDescription(activity, activity.getString(R.string.widget_action_mask)));
        await("unmasked savings", () -> !BalanceData.isHidden(context)
            && textContent(activity).contains(secret.label)
            && textContent(activity).contains(amount));
        await("unmasked row", () -> existingRow(secret.label) != null);
        click(existingRow(secret.label));
        await("unmasked editor", () -> lastShowingDialog() != null
            && view(lastShowingDialog(), "savings_label") != null);
    }

    @Test public void pausingSavingsDismissesTheTrackedEditorDialog() {
        activity = launchSavings();
        openEditor(null);
        assertTrue(hasShowingDialog());

        // Exercise only pause/resume. Calling start/stop directly would alter the app-wide lock
        // activity counter, while pause is the dialog-dismissal contract under test.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.onPause());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(hasShowingDialog());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.onResume());
        await("savings after resume", () -> findText(activity,
            activity.getString(R.string.savings_total_label)) != null);
    }

    // ---------------------------------------------------------------------
    // Editor and activity helpers
    // ---------------------------------------------------------------------

    private SavingsActivity launchSavings() {
        activity = (SavingsActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(context, SavingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("savings activity", () -> navigation(activity) != null);
        return activity;
    }

    private void openEditor(SavingsAsset existing) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.showEditor(existing));
        await("savings editor", () -> lastShowingDialog() != null
            && view(lastShowingDialog(), "savings_label") != null);
    }

    private AlertDialog requireEditor() {
        AlertDialog dialog = lastShowingDialog();
        assertNotNull("the savings editor must be showing", dialog);
        return dialog;
    }

    private static void assertEditorFields(AlertDialog dialog) {
        String[] tags = {"savings_label", "savings_kind", "savings_karat", "savings_coin",
            "savings_coin_age", "savings_currency", "savings_custom", "savings_quantity",
            "savings_unit_value"};
        for (String tag : tags) assertNotNull("missing editor field " + tag, view(dialog, tag));
    }

    private static void select(Spinner spinner, int position) {
        assertTrue("spinner position must exist", position >= 0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> spinner.setSelection(position));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static List<String> spinnerEntries(Spinner spinner) {
        AtomicReference<List<String>> result = new AtomicReference<>(Collections.emptyList());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            List<String> entries = new ArrayList<>();
            if (spinner != null && spinner.getAdapter() != null) {
                for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
                    Object item = spinner.getAdapter().getItem(i);
                    entries.add(item == null ? "" : item.toString());
                }
            }
            result.set(entries);
        });
        return result.get();
    }

    private static int spinnerPosition(Spinner spinner, String value) {
        List<String> entries = spinnerEntries(spinner);
        return entries.indexOf(value);
    }

    private static void clickPositive(AlertDialog dialog) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void click(View view) {
        assertNotNull("expected a clickable savings view", view);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(view::performClick);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void setText(EditText view, String value) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.setText(value));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static String textOf(TextView view) {
        AtomicReference<String> result = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
            () -> result.set(view == null ? "" : view.getText().toString()));
        return result.get();
    }

    private static EditText editText(AlertDialog dialog, String tag) {
        return (EditText) view(dialog, tag);
    }

    private static Spinner spinner(AlertDialog dialog, String tag) {
        return (Spinner) view(dialog, tag);
    }

    private static View existingRow(String label) {
        View title = findTextOnMain(label);
        AtomicReference<View> row = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View current = title;
            while (current != null) {
                if (current.isClickable()) {
                    row.set(current);
                    return;
                }
                current = current.getParent() instanceof View ? (View) current.getParent() : null;
            }
        });
        return row.get();
    }

    // ---------------------------------------------------------------------
    // Reflection and view discovery
    // ---------------------------------------------------------------------

    private static View view(AlertDialog dialog, String tag) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (dialog != null && dialog.getWindow() != null) {
                result.set(find(dialog.getWindow().getDecorView(), candidate -> tag.equals(candidate.getTag())));
            }
        });
        return result.get();
    }

    /** Visibility includes every ancestor, which is required for controls inside GONE conditional rows. */
    private static boolean isShown(AlertDialog dialog, String tag) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View target = dialog == null || dialog.getWindow() == null ? null
                : find(dialog.getWindow().getDecorView(), candidate -> tag.equals(candidate.getTag()));
            result.set(target != null && target.isShown());
        });
        return result.get();
    }

    private static View findTextOnMain(String text) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity candidate : resumedActivities()) {
                View found = find(candidate.getWindow().getDecorView(), view ->
                    view instanceof TextView && text.contentEquals(((TextView) view).getText()));
                if (found != null) {
                    result.set(found);
                    return;
                }
            }
        });
        return result.get();
    }

    private static TextView findText(Activity activity, String text) {
        AtomicReference<TextView> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View found = find(activity.getWindow().getDecorView(), view ->
                view instanceof TextView && text.contentEquals(((TextView) view).getText()));
            if (found instanceof TextView) result.set((TextView) found);
        });
        return result.get();
    }

    private static View findByDescription(Activity activity, String description) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
            find(activity.getWindow().getDecorView(), view -> view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription()))));
        return result.get();
    }

    private static String textContent(Activity activity) {
        AtomicReference<String> result = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            StringBuilder all = new StringBuilder();
            collectText(activity.getWindow().getDecorView(), all);
            result.set(all.toString());
        });
        return result.get();
    }

    private static void collectText(View root, StringBuilder out) {
        if (root instanceof TextView) out.append(((TextView) root).getText()).append('\n');
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    private interface Match { boolean accepts(View view); }

    private static View find(View root, Match match) {
        if (match.accepts(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The editor list is intentionally private; this verifies the activity tracks dialogs correctly. */
    private static List<AlertDialog> showingDialogs(SavingsActivity savings) {
        AtomicReference<List<AlertDialog>> result = new AtomicReference<>(Collections.emptyList());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Field field = SavingsActivity.class.getDeclaredField("dialogs");
                field.setAccessible(true);
                Object value = field.get(savings);
                if (!(value instanceof List<?>)) return;
                List<AlertDialog> showing = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    if (item instanceof AlertDialog && ((AlertDialog) item).isShowing()) {
                        showing.add((AlertDialog) item);
                    }
                }
                result.set(showing);
            } catch (ReflectiveOperationException ignored) {
                // A missing private tracking field makes the editor assertions fail as no dialog is found.
            }
        });
        return result.get();
    }

    private AlertDialog lastShowingDialog() {
        List<AlertDialog> dialogs = showingDialogs(activity);
        return dialogs.isEmpty() ? null : dialogs.get(dialogs.size() - 1);
    }

    private boolean hasShowingDialog() {
        return lastShowingDialog() != null;
    }

    /** The delete confirmation is a separate, intentionally untracked dialog; click its real button. */
    private static boolean activeWindowHasClickableText(String wanted) {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        return findClickableText(root, wanted) != null;
    }

    private static boolean clickActiveWindowText(String wanted) {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        AccessibilityNodeInfo button = findClickableText(root, wanted);
        return button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static AccessibilityNodeInfo findClickableText(AccessibilityNodeInfo node, String wanted) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (node.isClickable() && text != null && wanted.contentEquals(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClickableText(node.getChild(i), wanted);
            if (found != null) return found;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Lifecycle and bounded polling
    // ---------------------------------------------------------------------

    private static List<Activity> resumedActivities() {
        AtomicReference<List<Activity>> result = new AtomicReference<>();
        Runnable collect = () -> result.set(new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)));
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) collect.run();
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(collect);
        return result.get();
    }

    private static void finishOwnedActivities() {
        for (int pass = 0; pass < 5; pass++) {
            AtomicReference<Boolean> finished = new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity candidate : resumedActivities()) {
                    if (candidate instanceof SavingsActivity) {
                        finished.set(true);
                        candidate.finish();
                    }
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (!finished.get()) return;
            sleep(100);
        }
    }

    private static BottomNavigation navigation(SavingsActivity savings) {
        AtomicReference<BottomNavigation> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View found = find(savings.getWindow().getDecorView(), view -> view instanceof BottomNavigation);
            if (found instanceof BottomNavigation) result.set((BottomNavigation) found);
        });
        return result.get();
    }

    private static int selectedTab(BottomNavigation bar) {
        AtomicReference<Integer> result = new AtomicReference<>(-1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(bar.selectedTab()));
        return result.get();
    }

    private static int childCount(ViewGroup group) {
        AtomicReference<Integer> result = new AtomicReference<>(0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(group.getChildCount()));
        return result.get();
    }

    private static void await(String what, Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.call()) return;
            } catch (Exception ignored) {
                // A background render may replace a view or storage snapshot between polls.
            }
            sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling", e);
        }
    }

    // ---------------------------------------------------------------------
    // Preference isolation
    // ---------------------------------------------------------------------

    private void setHidden(boolean value) {
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, value).commit();
    }

    private static String stringValue(SavedPreference preference, String fallback) {
        return preference.present && preference.value instanceof String
            ? (String) preference.value : fallback;
    }

    private static boolean booleanValue(SavedPreference preference, boolean fallback) {
        return preference.present && preference.value instanceof Boolean
            ? (Boolean) preference.value : fallback;
    }

    private static String textContent(SavingsActivity savings) {
        return textContent((Activity) savings);
    }

    private static final class SavedPreference {
        final String file;
        final String key;
        final boolean present;
        final Object value;

        private SavedPreference(String file, String key, boolean present, Object value) {
            this.file = file;
            this.key = key;
            this.present = present;
            this.value = value;
        }

        static SavedPreference capture(Context context, String file, String key) {
            SharedPreferences preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE);
            Map<String, ?> all = preferences.getAll();
            return new SavedPreference(file, key, all.containsKey(key), all.get(key));
        }

        @SuppressWarnings("unchecked")
        void restore(Context context) {
            SharedPreferences.Editor editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit();
            if (!present) editor.remove(key);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof java.util.Set<?>)
                editor.putStringSet(key, (java.util.Set<String>) value);
            editor.commit();
        }
    }
}
