package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CashHistoryUiTest {
    private Context context;
    private ActivityScenario<HistoryActivity> scenario;
    private String previousCurrency;
    private boolean previouslyEnabled;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        previousCurrency = CurrencyHelper.currency(context);
        previouslyEnabled = CashStore.isEnabled(context);
        BalanceData.reset(context, true);
        CashStoreTest.clear(context);
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        CashStore.setEnabled(context, true);
    }

    @After public void tearDown() {
        if (scenario != null) scenario.close();
        BalanceData.reset(context, true);
        CashStoreTest.clear(context);
        CurrencyHelper.setCurrency(context, previousCurrency);
        CashStore.setEnabled(context, previouslyEnabled);
    }

    @Test public void totalCanOpenEmptyCashScopeThenAddAndConfirmRemoval() {
        launch(null);
        await(activity -> findByDescription(activity, R.string.cash_history_add) != null);
        scenario.onActivity(activity -> {
            View chip = findByDescription(activity, activity.getString(
                R.string.history_filter_tags_accessibility, activity.getString(R.string.cash_label)));
            assertNotNull(chip);
            assertTrue(chip.performClick());
        });
        await(activity -> find(root(activity), v -> hasText(v,
            activity.getString(R.string.cash_history_empty))) != null);
        long beforeEditor = System.currentTimeMillis();
        scenario.onActivity(activity ->
            assertTrue(findByDescription(activity, R.string.cash_history_add).performClick()));
        await(activity -> currentDialog(activity) != null);
        scenario.onActivity(activity -> {
            AlertDialog editor = currentDialog(activity);
            EditText amount = (EditText) find(editor.getWindow().getDecorView(),
                v -> v instanceof EditText);
            assertNotNull(amount);
            amount.setText("0");
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(activity.getString(R.string.cash_history_invalid_amount), amount.getError());
            amount.setText("25");
            editorNote(activity).setText("Cash from the market");
            editorTag(activity).setText("market");
        });
        saveEditor();
        await(activity -> currentDialog(activity) == null && movement(activity) != null);
        Transaction added = CashStore.read(context).transactions.get(0);
        assertEquals(250, added.amount);
        assertTrue(added.date >= beforeEditor && added.date <= System.currentTimeMillis());
        assertEquals("Cash from the market", BalanceData.getNote(context, added));
        assertEquals(Collections.singletonList("market"), BalanceData.getTags(context, added));

        requestRemoval();
        scenario.onActivity(activity -> currentDialog(activity)
            .getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(activity -> currentDialog(activity) == null);
        assertEquals(250, CashStore.read(context).balance);

        requestRemoval();
        scenario.onActivity(activity -> currentDialog(activity)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(activity -> currentDialog(activity) == null && movement(activity) == null);
        assertTrue(CashStore.read(context).transactions.isEmpty());
        assertEquals(0, CashStore.read(context).balance);
    }

    @Test public void bankAndDisabledCashScopesDoNotOfferManualAdd() {
        launch("Mellat");
        await(activity -> findByDescription(activity, R.string.history_export) != null
            && HistoryActivity.idleForTest(activity));
        scenario.onActivity(activity ->
            assertNull(findByDescription(activity, R.string.cash_history_add)));
        scenario.close();

        CashStore.setEnabled(context, false);
        launch(null);
        await(activity -> findByDescription(activity, R.string.history_export) != null
            && HistoryActivity.idleForTest(activity));
        scenario.onActivity(activity -> {
            assertNull(findByDescription(activity, R.string.cash_history_add));
            assertNull(findByDescription(activity, activity.getString(
                R.string.history_filter_tags_accessibility, activity.getString(R.string.cash_label))));
        });
    }

    @Test public void newCashEditorShowsPreviewForLatestAmount() {
        CashStore.setBalance(context, 21);
        launch(CashStore.KEY);
        openNew();
        scenario.onActivity(activity -> {
            editorAmount(activity).setText("5.2");
            editorAmount(activity).setText("1.3");
        });
        await(activity -> dialogText(activity, activity.getString(
            R.string.cash_history_preview_balance, cashValue(activity, 21),
            cashValue(activity, 34))) != null);
        scenario.onActivity(activity -> currentDialog(activity)
            .getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(activity -> currentDialog(activity) == null);
        scenario.recreate();
        await(activity -> findByDescription(activity, R.string.cash_history_add) != null
            && HistoryActivity.idleForTest(activity));
        scenario.onActivity(activity -> assertNull(currentDialog(activity)));
        assertTrue(CashStore.read(context).transactions.isEmpty());
    }

    @Test public void withdrawalDraftKeepsItsOwnNoteDuringDepositRecovery() {
        launch(CashStore.KEY);
        openNew();
        scenario.onActivity(activity -> {
            ((RadioButton) dialogText(activity, activity.getString(R.string.history_withdrawal)))
                .setChecked(true);
            editorAmount(activity).setText("5");
            editorNote(activity).setText("Original withdrawal");
        });
        awaitRecoveryAction();
        scenario.onActivity(activity -> {
            click(dialogText(activity, activity.getString(R.string.cash_history_record_deposit)));
            assertEquals("", editorNote(activity).getText().toString());
            editorNote(activity).setText("Canceled deposit note");
            click(currentDialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE));
        });
        awaitRecoveryAction();
        scenario.onActivity(activity -> {
            assertEquals("Original withdrawal", editorNote(activity).getText().toString());
            click(dialogText(activity, activity.getString(R.string.cash_history_record_deposit)));
            assertEquals("", editorNote(activity).getText().toString());
            editorNote(activity).setText("Separate deposit");
        });
        saveEditor();
        await(activity -> dialogText(activity, activity.getString(R.string.cash_history_preview_balance,
            cashValue(activity, 50), cashValue(activity, 0))) != null);
        scenario.onActivity(activity ->
            assertEquals("Original withdrawal", editorNote(activity).getText().toString()));
        saveEditor();
        await(activity -> currentDialog(activity) == null);

        CashStore.State state = CashStore.read(context);
        assertEquals(2, state.transactions.size());
        assertEquals(0, state.balance);
        for (Transaction transaction : state.transactions) {
            assertEquals(transaction.amount > 0 ? "Separate deposit" : "Original withdrawal",
                BalanceData.getNote(context, transaction));
        }
    }

    @Test public void editingCashMovementCanUpdateItsNoteAndReplaceATag() throws Exception {
        Transaction original = CashStore.add(context, System.currentTimeMillis(), 120);
        MetadataStore.setAnnotations(context, original, "Original note",
            Collections.singletonList("old tag"));
        launch(CashStore.KEY);
        openEdit();
        scenario.onActivity(activity -> {
            assertEquals("Original note", editorNote(activity).getText().toString());
            editorNote(activity).setText("Updated note");
            click(removeTagControl(activity, "old tag"));
            assertNull(removeTagControl(activity, "old tag"));
            editorTag(activity).setText("new tag");
            click(dialogText(activity, activity.getString(R.string.cash_history_add_tag)));
            assertNotNull(removeTagControl(activity, "new tag"));
        });
        saveEditor();
        await(activity -> currentDialog(activity) == null && HistoryActivity.idleForTest(activity));

        CashStore.State state = CashStore.read(context);
        assertEquals(1, state.transactions.size());
        assertEquals(120, state.balance);
        Transaction updated = state.transactions.get(0);
        assertEquals(original.sig, updated.sig);
        assertEquals(original.date, updated.date);
        assertEquals(original.amount, updated.amount);
        assertEquals("Updated note", BalanceData.getNote(context, updated));
        assertEquals(Collections.singletonList("new tag"), BalanceData.getTags(context, updated));
    }

    @Test public void appLockSecuresCashDetailsAndAnnotations() throws Exception {
        CashStore.add(context, System.currentTimeMillis(), 100, "Private cash note");
        LockManager.enable(context, "test-password", false, false);
        try {
            launch(CashStore.KEY);
            scenario.onActivity(activity -> {
                assertTrue(LockManager.isSessionLocked());
                View screen = root(activity);
                EditText password = (EditText) find(screen,
                    v -> v instanceof EditText && v.isShown()
                        && (((EditText) v).getInputType()
                            & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0);
                assertNotNull(password);
                password.setText("test-password");
                View unlock = find(screen, v -> v.isShown()
                    && hasText(v, activity.getString(R.string.lock_action_unlock)));
                assertNotNull(unlock);
                click((View) unlock.getParent());
            });
            await(activity -> !LockManager.isSessionLocked() && movement(activity) != null);
            scenario.onActivity(activity -> {
                click(movement(activity));
                AlertDialog details = currentDialog(activity);
                assertSecure(details);
                click(details.getButton(AlertDialog.BUTTON_POSITIVE));
            });
            await(activity -> currentDialog(activity) != null && find(currentDialog(activity).getWindow()
                    .getDecorView(), v -> v instanceof EditText
                        && hasText(v, "Private cash note")) != null);
            scenario.onActivity(activity -> assertSecure(currentDialog(activity)));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            scenario.onActivity(activity -> assertNull(currentDialog(activity)));
        } finally {
            if (scenario != null) scenario.close();
            scenario = null;
            LockManager.disable(context);
        }
    }

    private static void assertSecure(AlertDialog dialog) {
        assertNotNull(dialog);
        assertTrue((dialog.getWindow().getAttributes().flags
            & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    @Test public void tagControlsMirrorInPersianAndKeepMixedScriptNamesIntact() {
        String previousLanguage = LocaleHelper.currentTag(context);
        try {
            for (String language : new String[]{"en", "fa"}) {
                LocaleHelper.setLanguage(context, language);
                boolean rtl = "fa".equals(language);
                launch(CashStore.KEY);
                openNew();
                String label = rtl ? "Lunch 123" : "خرید ۱۲۳";
                await(activity -> {
                    View button = dialogText(activity, activity.getString(R.string.cash_history_add_tag));
                    return button != null && button.isLaidOut();
                });
                scenario.onActivity(activity -> {
                    assertEquals(rtl ? "افزودن برچسب" : "Add tag",
                        activity.getString(R.string.cash_history_add_tag));
                    EditText field = editorTag(activity);
                    View button = dialogText(activity, activity.getString(R.string.cash_history_add_tag));
                    assertNotNull(button);
                    assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR,
                        field.getLayoutDirection());
                    assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, field.getTextDirection());
                    assertEquals(View.TEXT_DIRECTION_LTR, editorAmount(activity).getTextDirection());
                    if (field.getTop() < button.getTop() && field.getBottom() <= button.getTop()) {
                        assertEquals(rtl ? field.getLeft() : field.getRight(),
                            rtl ? button.getLeft() : button.getRight());
                    } else {
                        assertTrue(rtl ? button.getRight() <= field.getLeft()
                            : field.getRight() <= button.getLeft());
                    }
                    field.setText(label);
                    click(button);
                });
                await(activity -> {
                    View chip = removeTagControl(activity, label);
                    return chip != null && chip.isLaidOut();
                });
                scenario.onActivity(activity -> {
                    ViewGroup chip = (ViewGroup) removeTagControl(activity, label);
                    TextView name = (TextView) chip.getChildAt(0);
                    View icon = chip.getChildAt(1);
                    assertEquals(label, name.getText().toString());
                    assertTrue(rtl ? icon.getRight() <= name.getLeft()
                        : name.getRight() <= icon.getLeft());
                });
                scenario.close();
                scenario = null;
            }
        } finally {
            if (scenario != null) scenario.close();
            LocaleHelper.setLanguage(context, previousLanguage);
        }
    }

    @Test public void recoveryFromAnEditRestoresDraftOnCancelAndSave() throws Exception {
        long timestamp = (System.currentTimeMillis() / 60_000 - 1) * 60_000 + 12_345;
        Transaction original = CashStore.add(context, timestamp, 12);
        MetadataStore.setAnnotations(context, original, "cash note", Collections.singletonList("tag"));
        launch(CashStore.KEY);
        openEdit();
        scenario.onActivity(activity -> {
            assertEquals("cash note", editorNote(activity).getText().toString());
            assertNotNull(removeTagControl(activity, "tag"));
            assertEquals(Long.valueOf(12), CashStore.parseAmount(activity,
                editorAmount(activity).getText().toString()));
            RadioButton withdrawal = (RadioButton) dialogText(activity,
                activity.getString(R.string.history_withdrawal));
            assertNotNull(withdrawal);
            withdrawal.setChecked(true);
            editorAmount(activity).setText("۴٫۳");
        });
        awaitRecoveryAction();
        scenario.onActivity(activity -> {
            View time = find(currentDialog(activity).getWindow().getDecorView(), view ->
                view instanceof TextView && ((TextView) view).getText().toString().startsWith(
                    activity.getString(R.string.cash_history_time_value, "")));
            click(time);
        });
        await(activity -> currentDialog(activity) != null && dialogText(activity,
            activity.getString(R.string.cash_history_choose_time)) != null);
        scenario.onActivity(activity -> currentDialog(activity)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        awaitRecoveryAction();
        scenario.onActivity(activity -> {
            click(dialogText(activity, activity.getString(R.string.cash_history_record_deposit)));
            assertEquals(Long.valueOf(43), CashStore.parseAmount(activity,
                editorAmount(activity).getText().toString()));
            assertEquals("", editorNote(activity).getText().toString());
            assertNull(removeTagControl(activity, "tag"));
            currentDialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        awaitRecoveryAction();
        scenario.onActivity(activity -> {
            assertEquals("۴٫۳", editorAmount(activity).getText().toString());
            assertEquals("cash note", editorNote(activity).getText().toString());
            assertNotNull(removeTagControl(activity, "tag"));
            click(dialogText(activity, activity.getString(R.string.cash_history_record_deposit)));
        });
        saveEditor();
        await(activity -> dialogText(activity, activity.getString(R.string.cash_history_preview_balance,
            cashValue(activity, 55), cashValue(activity, 0))) != null);
        scenario.onActivity(activity -> {
            assertEquals("۴٫۳", editorAmount(activity).getText().toString());
            assertEquals("cash note", editorNote(activity).getText().toString());
            assertNotNull(removeTagControl(activity, "tag"));
            RadioButton withdrawal = (RadioButton) dialogText(activity,
                activity.getString(R.string.history_withdrawal));
            assertTrue(withdrawal.isChecked());
        });
        saveEditor();
        await(activity -> currentDialog(activity) == null && HistoryActivity.idleForTest(activity));
        CashStore.State state = CashStore.read(context);
        assertEquals(2, state.transactions.size());
        assertEquals(0, state.balance);
        Transaction updated = state.transactions.stream().filter(tx -> original.sig.equals(tx.sig))
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals(timestamp, updated.date);
        assertEquals(-43, updated.amount);
        assertEquals("cash note", MetadataStore.getNote(context, updated));
        assertEquals(Collections.singletonList("tag"), MetadataStore.getTags(context, updated));
        Transaction deposit = state.transactions.stream().filter(tx -> tx.amount > 0)
            .findFirst().orElseThrow(AssertionError::new);
        assertNull(MetadataStore.getNote(context, deposit));
        assertTrue(MetadataStore.getTags(context, deposit).isEmpty());
    }

    @Test public void editingHistoricalMovementShowsHistoryOnlyWithUnchangedBalance() {
        CashStore.setBalance(context, 111);
        long historical = CashStore.read(context).countedAt - 60_000;
        CashStore.add(context, historical, -109);
        launch(CashStore.KEY);
        openEdit();
        await(activity -> dialogText(activity, activity.getString(
            R.string.cash_history_preview_history_only, cashValue(activity, 111))) != null);
        scenario.onActivity(activity -> editorAmount(activity).setText("12.3"));
        saveEditor();
        await(activity -> currentDialog(activity) == null && HistoryActivity.idleForTest(activity));
        CashStore.State state = CashStore.read(context);
        assertEquals(111, state.balance);
        assertEquals(-123, state.transactions.get(0).amount);
        assertEquals(historical, state.transactions.get(0).date);
    }

    private void openEdit() {
        await(activity -> movement(activity) != null);
        scenario.onActivity(activity -> {
            click(movement(activity));
            click(dialogText(activity, activity.getString(R.string.cash_history_edit_action)));
        });
        await(activity -> editorAmount(activity) != null);
    }

    private void openNew() {
        await(activity -> findByDescription(activity, R.string.cash_history_add) != null);
        scenario.onActivity(activity -> click(findByDescription(activity, R.string.cash_history_add)));
        await(activity -> editorAmount(activity) != null);
    }

    private void awaitRecoveryAction() {
        await(activity -> {
            View action = dialogText(activity, activity.getString(R.string.cash_history_record_deposit));
            return action != null && action.isClickable();
        });
    }

    private void saveEditor() {
        await(activity -> editorAmount(activity) != null
            && currentDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        scenario.onActivity(activity ->
            currentDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    }

    private static String cashValue(HistoryActivity activity, long rials) {
        return CurrencyHelper.amountExact(activity, rials) + " " + CurrencyHelper.label(activity);
    }

    private void launch(String bank) {
        Intent intent = new Intent(context, HistoryActivity.class);
        if (bank != null) intent.putExtra(HistoryActivity.EXTRA_BANK, bank);
        scenario = ActivityScenario.launch(intent);
    }

    private void requestRemoval() {
        scenario.onActivity(activity -> {
            assertTrue(movement(activity).performClick());
            assertTrue(currentDialog(activity).getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        });
        await(activity -> {
            AlertDialog dialog = currentDialog(activity);
            return dialog != null && hasText(dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                activity.getString(R.string.cash_history_remove));
        });
    }

    private void await(Predicate<HistoryActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        do {
            boolean[] matched = {false};
            scenario.onActivity(activity -> matched[0] = condition.test(activity));
            if (matched[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Cash history did not reach the expected state");
    }

    private static View movement(HistoryActivity activity) {
        return find(root(activity), view -> {
            CharSequence description = view.getContentDescription();
            return description != null
                && description.toString().contains(activity.getString(R.string.cash_history_row_hint));
        });
    }

    private static boolean hasText(View view, String text) {
        return view instanceof TextView && text.contentEquals(((TextView) view).getText());
    }

    private static View root(HistoryActivity activity) {
        return activity.getWindow().getDecorView();
    }

    private static AlertDialog currentDialog(HistoryActivity activity) {
        List<AlertDialog> dialogs = HistoryActivity.shownDialogsForTest(activity);
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            if (dialogs.get(i).isShowing()) return dialogs.get(i);
        }
        return null;
    }

    private static EditText editorAmount(HistoryActivity activity) {
        AlertDialog dialog = currentDialog(activity);
        return dialog == null ? null : (EditText) find(dialog.getWindow().getDecorView(),
            view -> view instanceof EditText && ((EditText) view).getHint() == null);
    }

    private static EditText editorNote(HistoryActivity activity) {
        return editorAnnotationField(activity, R.string.cash_history_note_optional);
    }

    private static EditText editorTag(HistoryActivity activity) {
        return editorAnnotationField(activity, R.string.cash_history_tag_optional);
    }

    private static EditText editorAnnotationField(HistoryActivity activity, int hintRes) {
        AlertDialog dialog = currentDialog(activity);
        String hint = activity.getString(hintRes);
        View field = find(dialog.getWindow().getDecorView(), view -> view instanceof EditText
            && TextUtils.equals(hint, ((EditText) view).getHint()));
        assertNotNull(hint, field);
        return (EditText) field;
    }

    private static View removeTagControl(HistoryActivity activity, String tag) {
        boolean rtl = activity.getResources().getConfiguration().getLayoutDirection()
            == View.LAYOUT_DIRECTION_RTL;
        String description = activity.getString(R.string.cash_history_remove_tag,
            android.text.BidiFormatter.getInstance(rtl).unicodeWrap(tag));
        return find(currentDialog(activity).getWindow().getDecorView(),
            view -> TextUtils.equals(description, view.getContentDescription()));
    }

    private static View dialogText(HistoryActivity activity, String text) {
        AlertDialog dialog = currentDialog(activity);
        return dialog == null ? null : find(dialog.getWindow().getDecorView(),
            view -> view.getVisibility() == View.VISIBLE && hasText(view, text));
    }

    private static void click(View view) {
        assertNotNull(view);
        assertTrue(view.performClick());
    }

    private static View findByDescription(HistoryActivity activity, int resource) {
        return findByDescription(activity, activity.getString(resource));
    }

    private static View findByDescription(HistoryActivity activity, String description) {
        return find(root(activity), view -> view.getContentDescription() != null
            && description.contentEquals(view.getContentDescription()));
    }

    private static View find(View root, Predicate<View> predicate) {
        if (predicate.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
