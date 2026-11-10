package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Movement details remain the entry to the real, asynchronous complete-annotation editor. */
@RunWith(AndroidJUnit4.class)
public class HistoryFigureUiTest {
    private Context context;
    private ActivityScenario<HistoryActivity> scenario;
    private String language;
    private String currency;
    private Transaction transaction;

    @Before public void before() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        language = LocaleHelper.currentTag(context);
        currency = CurrencyHelper.currency(context);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
        BalanceData.reset(context, true);
        transaction = new Transaction("Refah", "123456789", System.currentTimeMillis(), -25L,
            975L, "figure-sig", "figure-content");
        BalanceData.writeTransactions(context, Arrays.asList(transaction));
        MetadataStore.setAnnotations(context, transaction, "previous note", Arrays.asList("old tag"));
        scenario = ActivityScenario.launch(new Intent(context, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, "Refah")
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, "123456789"));
        await(() -> {
            final boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = HistoryActivity.idleForTest(activity)
                && movement(activity.getWindow().getDecorView()) != null);
            return ready[0];
        });
    }

    @After public void after() {
        if (scenario != null) scenario.close();
        BalanceData.reset(context, true);
        LocaleHelper.setLanguage(context, language);
        CurrencyHelper.setCurrency(context, currency);
    }

    @Test public void rowOpensScrollableExplanationWithUnitAndSeparatePersonalFacts() throws Exception {
        openDetails();
        await(() -> dialogContains(context.getString(R.string.figure_source_missing)));
        scenario.onActivity(activity -> {
            View root = lastDialog(activity).getWindow().getDecorView();
            assertNotNull(find(root, v -> v instanceof ScrollView));
            assertNotNull(find(root, v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains("Amount: -25 rials")));
            String body = allText(root);
            assertTrue(body.contains("Account 123456789"));
            assertTrue(body.contains(context.getString(R.string.figure_sourced)));
            assertTrue(body.contains(context.getString(R.string.figure_personal)));
            assertTrue(body.contains("previous note"));
            assertTrue(body.contains("old tag"));
            assertEquals(context.getString(R.string.figure_edit),
                lastDialog(activity).getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        });
    }

    @Test public void completeEditorSavePreservesTagsAndUpdatesSearchableNote() throws Exception {
        openEditor();
        scenario.onActivity(activity -> {
            AlertDialog dialog = lastDialog(activity);
            noteInput(dialog).setText("updated personal note");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(() -> "updated personal note".equals(BalanceData.getNote(context, transaction)));
        assertEquals(Arrays.asList("old tag"), BalanceData.getTags(context, transaction));
        await(this::idle);
        scenario.onActivity(activity -> assertTrue(allText(activity.getWindow().getDecorView())
            .contains("updated personal note")));
    }

    @Test public void explanationShowsExactTomanFirstAndStoredRialsAlongside() throws Exception {
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        openDetails();
        scenario.onActivity(activity -> {
            String body = allText(lastDialog(activity).getWindow().getDecorView());
            String selected = context.getString(R.string.figure_display_amount,
                NotificationAmounts.format(context, transaction.amount));
            String stored = context.getString(R.string.figure_raw_rials, "-25");
            assertTrue(body.contains(selected));
            assertTrue(body.contains(stored));
            assertTrue(body.indexOf(selected) < body.indexOf(stored));
        });
    }

    @Test public void clearNoteThenCancelDoesNotPersistAnyStagedChange() throws Exception {
        openEditor();
        scenario.onActivity(activity -> {
            AlertDialog editor = lastDialog(activity);
            editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            assertEquals("", noteInput(editor).getText().toString());
            View clearTags = find(editor.getWindow().getDecorView(), v -> v instanceof TextView
                && context.getString(R.string.tag_clear).contentEquals(((TextView) v).getText()));
            assertNotNull(clearTags);
            clearTags.performClick();
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        assertEquals("previous note", BalanceData.getNote(context, transaction));
        assertEquals(Arrays.asList("old tag"), BalanceData.getTags(context, transaction));
    }

    @Test public void queuedSaveDoesNotBlockUiAndCancelPreventsLaterCommit() throws Exception {
        openEditor();
        final AlertDialog[] opened = {null};
        scenario.onActivity(activity -> opened[0] = lastDialog(activity));
        AlertDialog editor = opened[0];
        try (StoreBlock ignored = new StoreBlock()) {
            runWithoutBlockingUi("Save must return to the UI queue while the store is busy", () -> {
                noteInput(editor).setText("must not save");
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertTrue(editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
            });
            runWithoutBlockingUi("Cancel must return while the store is busy", () ->
                editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        }
        await(this::idle);
        scenario.onActivity(activity -> assertTrue(HistoryActivity.shownDialogsForTest(activity).isEmpty()));
        assertEquals("previous note", BalanceData.getNote(context, transaction));
        assertEquals(Arrays.asList("old tag"), BalanceData.getTags(context, transaction));
    }

    @Test public void openingEditorDoesNotBlockUiAndCancelPreventsLateWindow() throws Exception {
        openDetails();
        await(() -> dialogContains(context.getString(R.string.figure_source_missing)));
        final HistoryActivity[] opened = {null};
        scenario.onActivity(activity -> opened[0] = activity);
        HistoryActivity activity = opened[0];
        try (StoreBlock ignored = new StoreBlock()) {
            runWithoutBlockingUi("Opening must return to the UI queue while the store is busy", () ->
                lastDialog(activity).getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
            runWithoutBlockingUi("Cancel must return while the store is busy", () -> {
                AlertDialog loading = lastDialog(activity);
                assertNull(find(loading.getWindow().getDecorView(), v -> v instanceof EditText));
                loading.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
        }
        await(this::idle);
        scenario.onActivity(current -> assertTrue(HistoryActivity.shownDialogsForTest(current).isEmpty()));
        assertEquals("previous note", BalanceData.getNote(context, transaction));
        assertEquals(Arrays.asList("old tag"), BalanceData.getTags(context, transaction));
    }

    @Test public void reparseWhileEditorOpenRejectsSaveAndPreservesTypedNoteAndTags() throws Exception {
        openEditor();
        final AlertDialog[] opened = {null};
        scenario.onActivity(activity -> {
            opened[0] = lastDialog(activity);
            noteInput(opened[0]).setText("typed after opening original movement");
            EditText tagInput = (EditText) find(opened[0].getWindow().getDecorView(), v -> v instanceof EditText
                && context.getString(R.string.tag_add_hint).contentEquals(((EditText) v).getHint()));
            assertNotNull(tagInput);
            tagInput.setText("typed tag");
        });
        Transaction reparsed = new Transaction(transaction.bank, transaction.account, transaction.date - 1_000,
            -30L, 970L, "reparsed-figure-sig", "reparsed-figure-content");
        // The full reparse publishes a new projection and migrates the existing personal metadata
        // while the old editor still holds its pre-reparse Transaction object.
        synchronized (BalanceData.class) {
            TransactionStore.replace(context, Arrays.asList(reparsed));
            MetadataStore.migrateTransactionText(context, java.util.Collections.singletonMap(transaction, reparsed));
        }
        scenario.onActivity(activity -> opened[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        await(this::idle);
        scenario.onActivity(activity -> {
            AlertDialog editor = lastDialog(activity);
            assertSame("rejecting a stale save must preserve the actual typed form", opened[0], editor);
            assertTrue(editor.isShowing());
            assertEquals("typed after opening original movement", noteInput(editor).getText().toString());
            assertTrue(noteInput(editor).isEnabled());
            assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            String body = allText(editor.getWindow().getDecorView());
            assertTrue(body.contains("typed tag"));
            assertTrue(body.contains("Nothing was saved"));
            assertTrue(body.contains("reopen the movement to retry"));
            View feedback = find(editor.getWindow().getDecorView(), v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains("Nothing was saved"));
            assertNotNull(feedback);
            assertEquals(View.VISIBLE, feedback.getVisibility());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, feedback.getAccessibilityLiveRegion());
        });
        assertNull("the old metadata key must not be recreated", MetadataStore.getNote(context, transaction));
        assertTrue(MetadataStore.getTags(context, transaction).isEmpty());
        assertEquals("previous note", MetadataStore.getNote(context, reparsed));
        assertEquals(Arrays.asList("old tag"), MetadataStore.getTags(context, reparsed));
    }

    private void openDetails() {
        scenario.onActivity(activity -> movement(activity.getWindow().getDecorView()).performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void openEditor() throws Exception {
        openDetails();
        scenario.onActivity(activity -> lastDialog(activity).getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        await(() -> {
            final boolean[] ready = {false};
            scenario.onActivity(activity -> {
                List<AlertDialog> dialogs = HistoryActivity.shownDialogsForTest(activity);
                ready[0] = !dialogs.isEmpty() && find(lastDialog(activity).getWindow().getDecorView(),
                    v -> v instanceof EditText) != null;
            });
            return ready[0];
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private boolean dialogContains(String expected) {
        final boolean[] found = {false};
        scenario.onActivity(activity -> found[0] = allText(lastDialog(activity).getWindow().getDecorView())
            .contains(expected));
        return found[0];
    }

    private boolean idle() {
        final boolean[] idle = {false};
        scenario.onActivity(activity -> idle[0] = HistoryActivity.idleForTest(activity)
            && HistoryActivity.annotationIdleForTest(activity));
        return idle[0];
    }

    private static EditText noteInput(AlertDialog dialog) {
        return (EditText) find(dialog.getWindow().getDecorView(), v -> v instanceof EditText);
    }

    private static AlertDialog lastDialog(HistoryActivity activity) {
        List<AlertDialog> dialogs = HistoryActivity.shownDialogsForTest(activity);
        assertFalse(dialogs.isEmpty());
        return dialogs.get(dialogs.size() - 1);
    }

    private static View movement(View root) {
        return find(root, v -> v.isClickable() && v.getContentDescription() != null
            && v.getContentDescription().toString().contains("Account 123456789")
            && v.getContentDescription().toString().contains("explain this figure"));
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

    private static String allText(View view) {
        StringBuilder text = new StringBuilder();
        find(view, v -> {
            if (v instanceof TextView) text.append(((TextView) v).getText()).append('\n');
            return false;
        });
        return text.toString();
    }

    private interface Condition { boolean check() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.check()) return;
            Thread.sleep(30);
        }
        fail("UI condition timed out");
    }

    private static void runWithoutBlockingUi(String message, Runnable action) throws Exception {
        CountDownLatch returnedToQueue = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable posted = () -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                // A separate queue turn also catches work posted by the click that blocks the UI.
                // On timeout the enclosing StoreBlock releases the monitor instead of hanging.
                main.post(returnedToQueue::countDown);
            }
        };
        main.post(posted);
        try {
            if (!returnedToQueue.await(2, TimeUnit.SECONDS)) {
                fail(message + "\nUI thread: "
                    + Arrays.toString(main.getLooper().getThread().getStackTrace()));
            }
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally {
            main.removeCallbacks(posted);
        }
    }

    private static final class StoreBlock implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;
        StoreBlock() throws Exception {
            thread = new Thread(() -> {
                synchronized (BalanceData.class) {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }, "figure-test-store-holder");
            thread.start();
            boolean acquired = false;
            try {
                acquired = entered.await(5, TimeUnit.SECONDS);
                assertTrue("the test store holder did not start", acquired);
            } finally {
                if (!acquired) release.countDown();
            }
        }
        @Override public void close() throws Exception {
            release.countDown();
            thread.join(5_000);
            assertFalse("the test must release its store monitor", thread.isAlive());
        }
    }
}
