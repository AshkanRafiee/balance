package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.function.Predicate;

/** Real note-editor coverage. These fixtures require an isolated emulator SMS inbox. */
@RunWith(AndroidJUnit4.class)
public class HistoryResidualNoteEditorTest {
    private static final String ACCOUNT = "01351234567890";
    private static final String NOTE = "late cash withdrawal from the editor";
    private Context context;
    private ActivityScenario<HistoryActivity> scenario;
    private String originalLanguage, originalCurrency;
    private boolean originalExpandAll;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLanguage = LocaleHelper.currentTag(context);
        originalCurrency = CurrencyHelper.currency(context);
        originalExpandAll = BalanceData.getExpandAllHistory(context);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        BalanceData.setExpandAllHistory(context, true);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(), Manifest.permission.READ_SMS);
        exec("cmd appops set " + context.getPackageName()
            + " android:read_restricted_messages allow");
        clearInbox();
        BalanceData.reset(context, true);
        HistoryWarmup.invalidate();
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        clearInbox();
        BalanceData.reset(context, true);
        LocaleHelper.setLanguage(context, originalLanguage);
        CurrencyHelper.setCurrency(context, originalCurrency);
        BalanceData.setExpandAllHistory(context, originalExpandAll);
    }

    @Test public void editorNoteFollowsExactDelayedMovementAfterKeepNotesReset() throws Exception {
        Residual gap = seedGapAndOpen();
        saveThroughEditor(NOTE);
        await(() -> NOTE.equals(BalanceData.getNote(context, gap)), "the editor note to save");
        assertTrue("the editor must save the original balance bracket",
            DataGeneration.dataPrefs(context).contains(BalanceData.KEY_RESIDUAL_NOTE_PROVENANCE));

        // No observer may consume the delayed message before the keep-notes reset.
        scenario.close();
        scenario = null;
        seed(message("برداشت", "2,034,872,000", "498,563,016", "07", "12:00"),
            at(2026, 9, 10, 9));
        BalanceData.reset(context, false);
        assertEquals(3, BalanceData.scanHistory(context));
        Transaction recovered = null;
        List<Transaction> transactions = BalanceData.readTransactions(context);
        for (Transaction transaction : transactions) {
            if (transaction.amount == -2_034_872_000L) recovered = transaction;
        }
        assertNotNull("the delayed movement must be recovered", recovered);
        assertEquals("the note written in the real editor must follow the movement", NOTE,
            BalanceData.getNote(context, recovered));
        assertNull("the old Unaccounted note must not remain duplicated",
            BalanceData.getNote(context, gap));
        assertTrue(Residual.between(transactions).isEmpty());

        openHistory();
        await(() -> screenHasText(NOTE), "the recovered movement's note to appear");
    }

    @Test public void clearingThroughEditorRemovesNoteAndBracketProvenance() throws Exception {
        Residual gap = seedGapAndOpen();
        saveThroughEditor(NOTE);
        await(() -> NOTE.equals(BalanceData.getNote(context, gap)), "the editor note to save");
        await(this::idle, "the saved note to render");
        openEditor();
        scenario.onActivity(activity -> {
            AlertDialog editor = lastDialog(activity);
            assertNotNull("a saved note must offer Clear", editor.getButton(AlertDialog.BUTTON_NEUTRAL));
            editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        });
        await(() -> BalanceData.getNote(context, gap) == null, "the note to clear");
        assertFalse("clearing in the editor must remove its saved balance bracket",
            DataGeneration.dataPrefs(context).contains(BalanceData.KEY_RESIDUAL_NOTE_PROVENANCE));
    }

    private Residual seedGapAndOpen() throws Exception {
        seed(message("واریز", "805,000,000", "2,533,435,016", "06", "00:08"),
            at(2026, 8, 30, 9));
        seed(message("برداشت", "490,098,000", "8,465,016", "07", "20:16"),
            at(2026, 8, 30, 10));
        assertEquals(2, BalanceData.scanHistory(context));
        List<Residual> gaps = Residual.between(BalanceData.readTransactions(context));
        assertEquals(1, gaps.size());
        assertEquals(-2_034_872_000L, gaps.get(0).amount);
        openHistory();
        return gaps.get(0);
    }

    private void openHistory() throws Exception {
        BalanceData.setExpandAllHistory(context, true);
        scenario = ActivityScenario.launch(new Intent(context, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, "Tejarat")
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(this::idle, "the history scan and render to settle");
    }

    private void openEditor() throws Exception {
        await(() -> {
            final boolean[] found = {false};
            scenario.onActivity(activity -> found[0] = residualRow(activity.getWindow().getDecorView()) != null);
            return found[0];
        }, "the Unaccounted row");
        scenario.onActivity(activity ->
            residualRow(activity.getWindow().getDecorView()).performClick());
        // Dialog's OnShowListener is posted to the main queue. It installs the note button's
        // custom listener, so clicking in the same UI turn as show() would hit the default button.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> lastDialog(activity)
            .getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        await(() -> {
            final boolean[] visible = {false};
            scenario.onActivity(activity -> {
                AlertDialog editor = lastDialog(activity);
                visible[0] = editor.isShowing() && find(editor.getWindow().getDecorView(),
                    view -> view instanceof EditText) != null;
            });
            return visible[0];
        }, "the real note editor to open");
        scenario.onActivity(activity -> {
            assertNotNull("the note editor must contain a real editable field",
                find(lastDialog(activity).getWindow().getDecorView(), view -> view instanceof EditText));
        });
    }

    private void saveThroughEditor(String text) throws Exception {
        openEditor();
        scenario.onActivity(activity -> {
            AlertDialog editor = lastDialog(activity);
            ((EditText) find(editor.getWindow().getDecorView(), view -> view instanceof EditText))
                .setText(text);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
    }

    private static AlertDialog lastDialog(HistoryActivity activity) {
        List<AlertDialog> dialogs = HistoryActivity.shownDialogsForTest(activity);
        assertFalse("the real editor must show a dialog", dialogs.isEmpty());
        return dialogs.get(dialogs.size() - 1);
    }

    private static View residualRow(View root) {
        return find(root, view -> view instanceof LinearLayout && view.isClickable()
            && view.getContentDescription() != null
            && view.getContentDescription().toString().contains("Unaccounted"));
    }

    private boolean screenHasText(String text) {
        final boolean[] found = {false};
        scenario.onActivity(activity -> found[0] = find(activity.getWindow().getDecorView(),
            view -> view instanceof TextView && text.contentEquals(((TextView) view).getText())) != null);
        return found[0];
    }

    private boolean idle() {
        final boolean[] idle = {false};
        scenario.onActivity(activity -> idle[0] = HistoryActivity.idleForTest(activity));
        return idle[0];
    }

    private static View find(View root, Predicate<View> match) {
        if (match.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = find(group.getChildAt(i), match);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String message(String direction, String amount, String balance, String day, String time) {
        return "*بانک تجارت*\nحساب: " + ACCOUNT + "\n" + direction + ": " + amount
            + " ریال\nمانده: " + balance + " ریال\n1405/06/" + day + "\n" + time;
    }

    private static long at(int year, int month, int day, int hour) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, 0);
        return calendar.getTimeInMillis();
    }

    private void seed(String body, long base) throws Exception {
        String encoded = android.util.Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.SEED"
            + " -e sender TejaratBank -e body64 " + encoded + " -e base " + base);
        await(() -> inboxContains(body), "the seeded SMS to arrive");
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver -a com.ashkanrafiee.smsinject.CLEAR");
        await(() -> {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, new String[]{Telephony.Sms._ID}, null, null, null)) {
                return cursor != null && !cursor.moveToFirst();
            }
        }, "the isolated SMS inbox to clear");
    }

    private boolean inboxContains(String body) {
        try (android.database.Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI, new String[]{Telephony.Sms.BODY}, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) if (body.equals(cursor.getString(0))) return true;
        }
        return false;
    }

    private static void exec(String command) throws Exception {
        android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        try (InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[2048];
            while (stream.read(buffer) >= 0) { }
        }
    }

    private interface Check { boolean done() throws Exception; }

    private static void await(Check check, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.done()) return;
            Thread.sleep(100);
        }
        fail("timed out waiting for " + what);
    }
}
