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
import android.content.SharedPreferences;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    private boolean hadExpandMigration, originalExpandMigration;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLanguage = LocaleHelper.currentTag(context);
        originalCurrency = CurrencyHelper.currency(context);
        originalExpandAll = BalanceData.getExpandAllHistory(context);
        SharedPreferences prefs = context.getSharedPreferences(BalanceData.PREFS_PREF,
            Context.MODE_PRIVATE);
        hadExpandMigration = prefs.contains(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED);
        originalExpandMigration = prefs.getBoolean(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, false);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        // Apply the one-time migration before choosing this fixture's explicit debug preference.
        // A preceding scan test may have cleared the marker, as on a genuine first history open.
        BalanceData.migrateExpandAllToDebugMenu(context);
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
        SharedPreferences.Editor restore = context.getSharedPreferences(BalanceData.PREFS_PREF,
            Context.MODE_PRIVATE).edit();
        if (hadExpandMigration)
            restore.putBoolean(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, originalExpandMigration);
        else restore.remove(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED);
        assertTrue(restore.commit());
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

    @Test public void editorSavedAfterDelayedMovementResolvesGap_followsTheUniqueMovement()
            throws Exception {
        Residual gap = seedGapAndOpen();
        openEditor();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        final AlertDialog[] opened = {null};
        scenario.onActivity(activity -> {
            opened[0] = lastDialog(activity);
            ((EditText) find(opened[0].getWindow().getDecorView(), view -> view instanceof EditText))
                .setText(NOTE);
        });

        seed(message("برداشت", "2,034,872,000", "498,563,016", "07", "12:00"),
            at(2026, 9, 10, 9));
        // Either our call or the SMS observer may read the message first. Assert committed state,
        // not which worker added it, and finish the quiet refresh before saving the old editor.
        BalanceData.scanHistory(context);
        await(this::idle, "the resolving scan and render to finish while the editor is open");
        List<Transaction> transactions = BalanceData.readTransactions(context);
        assertEquals(3, transactions.size());
        assertTrue(Residual.between(transactions).isEmpty());
        Transaction recovered = null;
        for (Transaction transaction : transactions)
            if (transaction.amount == -2_034_872_000L) recovered = transaction;
        assertNotNull(recovered);

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            AlertDialog editor = lastDialog(activity);
            assertTrue("the same editor must remain open after its row disappears", editor == opened[0]);
            assertTrue(editor.isShowing());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(this::idle, "the note save to render");
        scenario.onActivity(activity -> assertFalse("a successful worker save must dismiss its editor",
            opened[0].isShowing()));
        assertEquals(0, BalanceData.scanHistory(context));
        assertEquals(NOTE, BalanceData.getNote(context, recovered));
        assertNull(BalanceData.getNote(context, gap));
    }

    @Test public void residualEditorOpenDoesNotBlockUiWhileStoreIsBusy_andLoadsSavedNote()
            throws Exception {
        Residual gap = seedGapAndOpen();
        BalanceData.setNote(context, gap, NOTE);
        AlertDialog detail = openResidualDetail();
        try (StoreBlock ignored = new StoreBlock()) {
            clickOpenWithoutBlockingUi(detail);
        }
        awaitEditor();
        scenario.onActivity(activity -> {
            AlertDialog editor = lastDialog(activity);
            assertTrue(editor.isShowing());
            assertEquals("opening asynchronously must load the current saved note", NOTE,
                editorInput(editor).getText().toString());
            android.widget.Button clear = editor.getButton(AlertDialog.BUTTON_NEUTRAL);
            assertNotNull("a loaded existing note must still offer Clear", clear);
            assertEquals("the existing-note Clear action must be visible", View.VISIBLE,
                clear.getVisibility());
            assertTrue("the existing-note Clear action must be usable", clear.isEnabled());
        });
    }

    @Test public void cancelWhileOpeningWaitsForStore_doesNotOpenAnEditorLater() throws Exception {
        seedGapAndOpen();
        AlertDialog detail = openResidualDetail();
        try (StoreBlock ignored = new StoreBlock()) {
            clickOpenWithoutBlockingUi(detail);
            scenario.onActivity(activity -> lastDialog(activity)
                .getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        }
        await(this::idle, "the canceled editor load to finish");
        assertNoDialogs();
    }

    @Test public void backWhileOpeningWaitsForStore_doesNotOpenAnEditorLater() throws Exception {
        seedGapAndOpen();
        AlertDialog detail = openResidualDetail();
        try (StoreBlock ignored = new StoreBlock()) {
            clickOpenWithoutBlockingUi(detail);
            scenario.onActivity(activity -> lastDialog(activity).cancel());
        }
        await(this::idle, "the canceled editor load to finish");
        assertNoDialogs();
    }

    @Test public void pauseWhileOpeningWaitsForStore_discardsTheOldCompletion() throws Exception {
        seedGapAndOpen();
        AlertDialog detail = openResidualDetail();
        try (StoreBlock ignored = new StoreBlock()) {
            clickOpenWithoutBlockingUi(detail);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        await(this::idle, "the resumed screen and canceled editor load");
        assertNoDialogs();
    }

    @Test public void lockBeforeOpeningAcquiresStore_doesNotRevealTheNote() throws Exception {
        Residual gap = seedGapAndOpen();
        BalanceData.setNote(context, gap, NOTE);
        AlertDialog detail = openResidualDetail();
        boolean originallyLocked = LockManager.isSessionLocked();
        try {
            try (StoreBlock ignored = new StoreBlock()) {
                clickOpenWithoutBlockingUi(detail);
                LockManager.lockSession();
            }
            await(this::idle, "the locked editor load to finish");
            assertNoDialogs();
            assertEquals("a canceled read must not change the saved note", NOTE,
                BalanceData.getNote(context, gap));
        } finally {
            if (originallyLocked) LockManager.lockSession();
            else LockManager.unlockSession();
        }
    }

    @Test public void failedEditorLoad_doesNotShowAnEmptyEditorAndCanBeRetried() throws Exception {
        Residual gap = seedGapAndOpen();
        BalanceData.setNote(context, gap, NOTE);
        SharedPreferences prefs = DataGeneration.dataPrefs(context);
        String owner = prefs.getString(MetadataStore.KEY_METADATA_STORE_TOKEN, null);
        assertNotNull(owner);
        try {
            assertTrue(prefs.edit().putString(MetadataStore.KEY_METADATA_STORE_TOKEN,
                "wrong-owner-editor-load-fixture").commit());
            AlertDialog detail = openResidualDetail();
            scenario.onActivity(activity -> detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
            await(this::idle, "the failed editor load to finish");
            assertNoDialogs();
        } finally {
            assertTrue(prefs.edit().putString(MetadataStore.KEY_METADATA_STORE_TOKEN, owner).commit());
        }
        openEditor();
        scenario.onActivity(activity -> assertEquals(NOTE, editorInput(lastDialog(activity))
            .getText().toString()));
        assertEquals(NOTE, BalanceData.getNote(context, gap));
    }

    private void assertNoDialogs() {
        scenario.onActivity(activity -> assertTrue("an obsolete/failed load must not open an editor",
            HistoryActivity.shownDialogsForTest(activity).isEmpty()));
    }

    @Test public void residualSaveDoesNotBlockUiWhileStoreIsBusy_andCapturesSubmittedText()
            throws Exception {
        Residual gap = seedGapAndOpen();
        AlertDialog editor = editorReadyForSave();
        try (StoreBlock ignored = new StoreBlock()) {
            clickSaveWithoutBlockingUi(editor);
            scenario.onActivity(activity -> {
                assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertFalse(editorInput(editor).isEnabled());
                // Even a programmatic edit after submission cannot change the worker's snapshot.
                editorInput(editor).setText("not the submitted note");
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
        }
        await(this::idle, "the background note save to finish");
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        scenario.onActivity(activity -> assertFalse(editor.isShowing()));
    }

    @Test public void cancelWhileSaveWaitsForStore_doesNotCommitQueuedText() throws Exception {
        Residual gap = seedGapAndOpen();
        AlertDialog editor = editorReadyForSave();
        try (StoreBlock ignored = new StoreBlock()) {
            clickSaveWithoutBlockingUi(editor);
            scenario.onActivity(activity -> editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        }
        await(this::idle, "the canceled note worker to finish");
        assertNull(BalanceData.getNote(context, gap));
    }

    @Test public void pauseWhileSaveWaitsForStore_cancelsQueuedWriteAndStaleCompletion()
            throws Exception {
        Residual gap = seedGapAndOpen();
        AlertDialog editor = editorReadyForSave();
        try (StoreBlock ignored = new StoreBlock()) {
            clickSaveWithoutBlockingUi(editor);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        await(this::idle, "the resumed history and canceled note worker to settle");
        assertNull(BalanceData.getNote(context, gap));
        scenario.onActivity(activity -> assertTrue("completion must not reopen a paused editor",
            HistoryActivity.shownDialogsForTest(activity).isEmpty()));
    }

    @Test public void lockBeforeQueuedSaveAcquiresStore_preventsCommit() throws Exception {
        Residual gap = seedGapAndOpen();
        AlertDialog editor = editorReadyForSave();
        boolean originallyLocked = LockManager.isSessionLocked();
        try {
            try (StoreBlock ignored = new StoreBlock()) {
                clickSaveWithoutBlockingUi(editor);
                LockManager.lockSession();
            }
            await(this::idle, "the locked note worker to finish");
            assertNull(BalanceData.getNote(context, gap));
            scenario.onActivity(activity -> assertFalse(editor.isShowing()));
        } finally {
            if (originallyLocked) LockManager.lockSession();
            else LockManager.unlockSession();
        }
    }

    @Test public void failedBackgroundSave_keepsTypedTextAndAllowsRetry() throws Exception {
        Residual gap = seedGapAndOpen();
        AlertDialog editor = editorReadyForSave();
        assertTrue(DataGeneration.dataPrefs(context).edit().putString(
            BalanceData.KEY_RESIDUAL_NOTE_PROVENANCE, "invalid encrypted test fixture").commit());
        scenario.onActivity(activity -> editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(this::idle, "the failed background note save");
        scenario.onActivity(activity -> {
            assertTrue(editor.isShowing());
            assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertTrue(editorInput(editor).isEnabled());
            assertEquals(NOTE, editorInput(editor).getText().toString());
        });
        assertTrue(DataGeneration.dataPrefs(context).edit()
            .remove(BalanceData.KEY_RESIDUAL_NOTE_PROVENANCE).commit());
        scenario.onActivity(activity -> editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(this::idle, "the retried background note save");
        assertEquals(NOTE, BalanceData.getNote(context, gap));
        scenario.onActivity(activity -> assertFalse(editor.isShowing()));
    }

    private AlertDialog editorReadyForSave() throws Exception {
        openEditor();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        final AlertDialog[] editor = {null};
        scenario.onActivity(activity -> {
            editor[0] = lastDialog(activity);
            editorInput(editor[0]).setText(NOTE);
        });
        return editor[0];
    }

    private static EditText editorInput(AlertDialog editor) {
        return (EditText) find(editor.getWindow().getDecorView(), view -> view instanceof EditText);
    }

    private static void clickSaveWithoutBlockingUi(AlertDialog editor) throws Exception {
        CountDownLatch clicked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue("a queued save leaves its editor visible until completion", editor.isShowing());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                clicked.countDown();
            }
        });
        // The old handler waited on BalanceData.class here. A bounded wait fails promptly,
        // then the enclosing StoreBlock's finally releases the UI instead of hanging the suite.
        assertTrue("Save must return to the UI queue while the store is busy", clicked.await(2, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void clickOpenWithoutBlockingUi(AlertDialog detail) throws Exception {
        CountDownLatch returnedToQueue = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                // A separate main-loop turn proves opening did not merely post another UI-blocking
                // metadata read. The enclosing StoreBlock releases the monitor even on failure.
                main.post(returnedToQueue::countDown);
            }
        });
        assertTrue("Add/Edit Note must return to the UI queue while the store is busy",
            returnedToQueue.await(2, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class StoreBlock implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder;

        StoreBlock() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            holder = new Thread(() -> {
                synchronized (BalanceData.class) {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "residual-note-test-store-holder");
            holder.start();
            if (!entered.await(5, TimeUnit.SECONDS)) {
                release.countDown();
                fail("the test store holder did not start");
            }
        }

        @Override public void close() throws Exception {
            release.countDown();
            holder.join(5000);
            assertFalse("the test must release its store monitor", holder.isAlive());
        }
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
        AlertDialog detail = openResidualDetail();
        scenario.onActivity(activity -> detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        awaitEditor();
    }

    private AlertDialog openResidualDetail() throws Exception {
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
        AtomicReference<AlertDialog> detail = new AtomicReference<>();
        scenario.onActivity(activity -> detail.set(lastDialog(activity)));
        return detail.get();
    }

    private void awaitEditor() throws Exception {
        await(() -> {
            final boolean[] visible = {false};
            scenario.onActivity(activity -> {
                if (HistoryActivity.shownDialogsForTest(activity).isEmpty()) return;
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
        scenario.onActivity(activity -> idle[0] = HistoryActivity.idleForTest(activity)
            && HistoryActivity.residualNoteSaveIdleForTest(activity)
            && HistoryActivity.residualNoteLoadIdleForTest(activity));
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
