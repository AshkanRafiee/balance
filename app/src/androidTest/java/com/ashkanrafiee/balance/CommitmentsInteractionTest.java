package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The commitments screen's two layers of interaction: a due can be edited or settled independently,
 * while the series stays in Manage and the dashboard displays the remaining amounts.
 */
@RunWith(AndroidJUnit4.class)
public class CommitmentsInteractionTest {

    private static final String REMAINING_PAYABLE = "Remaining payable";
    private static final String REMAINING_RECEIVABLE = "Remaining receivable";

    private Context ctx;
    private LocaleList originalLocales;
    private String originalLanguage;
    private String originalCurrency;
    private int originalRegion;
    private boolean originalShowCommitments;
    private boolean originalOnboardingSeen;
    private boolean originalAutoHide;
    private boolean originalHidden;
    private List<Commitment> originalCommitments;
    private ActivityScenario<CommitmentsActivity> commitmentsScenario;
    private ActivityScenario<MainActivity> mainScenario;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalLocales = LocaleList.getDefault();
        originalLanguage = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        originalRegion = RegionHelper.region(ctx);
        originalShowCommitments = BalanceData.getShowCommitments(ctx);
        originalOnboardingSeen = BalanceData.isOnboardingSeen(ctx);
        originalAutoHide = BalanceData.isAutoHide(ctx);
        originalHidden = BalanceData.isHidden(ctx);
        // Detach lazy settlement lists before clear() invalidates their store revision, keeping
        // the original frozen calendar/zone rather than recapturing it during restoration.
        List<Commitment> snapshot = new ArrayList<>();
        for (Commitment c : BalanceData.readCommitments(ctx)) {
            snapshot.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                c.done, new ArrayList<>(c.paid), c.legacyPaidThrough, new ArrayList<>(c.unpaid),
                c.remind, c.remindBeforeMs).withSchedule(c.schedule)
                .withOccurrenceAmounts(c.occurrenceAmounts));
        }
        originalCommitments = snapshot;
        LockManager.disable(ctx);
        LockManager.resetSessionForTest();
        CommitmentStore.clear(ctx);

        // Keep all visible strings and date arithmetic deterministic, including on a Persian device.
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(ctx, RegionHelper.REGION_INTERNATIONAL);
        BalanceData.setShowCommitments(ctx, true);
        BalanceData.setOnboardingSeen(ctx, true);
    }

    @After public void tearDown() throws Exception {
        try {
            try {
                if (commitmentsScenario != null) commitmentsScenario.close();
            } finally {
                if (mainScenario != null) mainScenario.close();
            }
        } finally {
            try {
                if (originalCommitments != null)
                    BalanceData.writeCommitments(ctx, originalCommitments);
            } finally {
                try {
                    restoreSettings();
                } finally {
                    try { LockManager.disable(ctx); }
                    finally { LockManager.resetSessionForTest(); }
                }
            }
        }
    }

    private void restoreSettings() {
        try {
            BalanceData.setShowCommitments(ctx, originalShowCommitments);
            BalanceData.setAutoHide(ctx, originalAutoHide);
            ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(BalanceData.KEY_HIDDEN, originalHidden).apply();
        } finally {
            try {
                BalanceData.setOnboardingSeen(ctx, originalOnboardingSeen);
            } finally {
                try {
                    LocaleHelper.setLanguage(ctx, originalLanguage);
                } finally {
                    try {
                        CurrencyHelper.setCurrency(ctx, originalCurrency);
                    } finally {
                        try { RegionHelper.setRegion(ctx, originalRegion); }
                        finally { LocaleList.setDefault(originalLocales); }
                    }
                }
            }
        }
    }

    @Test public void monthlyPaymentOccurrence_hasSeparateEditAndMarkUndoKeepsExactDate() {
        assertOccurrenceRoundTrip(-125_000L, R.string.commitments_mark_paid);
    }

    @Test public void monthlyReceiptOccurrence_hasSeparateEditAndMarkUndoKeepsExactDate() {
        assertOccurrenceRoundTrip(275_000L, R.string.commitments_mark_received);
    }

    private void assertOccurrenceRoundTrip(long amount, int actionRes) {
        long due = Commitment.startOfDay(System.currentTimeMillis());
        Commitment source = Commitment.create("monthly interaction", amount, Commitment.MONTHLY,
            due, null, false, 0);
        List<Commitment> stored = new ArrayList<>();
        stored.add(source);
        BalanceData.writeCommitments(ctx, stored);

        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);
        final String actionLabel = LocaleHelper.wrap(ctx).getString(actionRes);
        commitmentsScenario.onActivity(activity -> {
            TextView action = findText(activity.getWindow().getDecorView(), actionLabel);
            assertNotNull("the current monthly occurrence must be rendered", action);
            View row = (View) action.getParent();
            assertFalse("an occurrence row must not open the series editor", row.isClickable());
            assertFalse("a row without a listener must return false from performClick",
                row.performClick());
            assertNotNull("editing the due must be a distinct row action",
                findText(row, activity.getString(R.string.commitments_edit_due)));
            assertTrue("the settle action must remain clickable", action.performClick());
        });

        List<Commitment> marked = BalanceData.readCommitments(ctx);
        assertEquals(1, marked.size());
        assertEquals("marking a monthly occurrence must store one date", 1, marked.get(0).paid.size());
        assertEquals("the exact occurrence date must be stored", due,
            marked.get(0).paid.get(0).longValue());
        assertFalse("a recurring occurrence must not become a finished one-time commitment",
            marked.get(0).done);

        final String undoLabel = LocaleHelper.wrap(ctx).getString(R.string.commitments_undo);
        commitmentsScenario.onActivity(activity -> {
            TextView undo = findText(activity.getWindow().getDecorView(), undoLabel);
            assertNotNull("the settled occurrence must offer Undo", undo);
            View row = (View) undo.getParent();
            assertFalse("the settled occurrence row must remain non-clickable", row.isClickable());
            assertFalse(row.performClick());
            assertNotNull("a settled due still has an Edit action",
                findText(row, activity.getString(R.string.commitments_edit_due)));
            assertTrue("Undo must remain a direct row action", undo.performClick());
        });

        List<Commitment> restored = BalanceData.readCommitments(ctx);
        assertEquals(1, restored.size());
        assertTrue("Undo must remove only the marked occurrence date", restored.get(0).paid.isEmpty());
        assertFalse(restored.get(0).done);
    }

    @Test public void editAndResetARecurringDue_withoutChangingItsSiblingOrSettlement() throws Exception {
        Calendar current = Calendar.getInstance();
        current.set(Calendar.DAY_OF_MONTH, 1);
        long next = Commitment.startOfDay(current.getTimeInMillis());
        current.add(Calendar.MONTH, -1);
        long due = Commitment.startOfDay(current.getTimeInMillis());
        Commitment source = Commitment.create("monthly rent", -125_000L, Commitment.MONTHLY,
            due, next, false, 0);
        BalanceData.writeCommitments(ctx, singleton(source));
        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);
        // Simulate a newer saved amount after the row was rendered.
        assertTrue(CommitmentStore.setOccurrenceAmount(ctx, source.id, due, true, -150_000L));
        commitmentsScenario.onActivity(activity -> {
            AlertDialog editor = openDueEditor(activity, activity.getWindow().getDecorView());
            View form = editor.getWindow().getDecorView();
            assertNotNull(findText(form, source.name));
            assertNotNull(findText(form, activity.getString(R.string.commitments_edit_due_payment)));
            EditText amount = findEditText(form, "150000");
            assertNotNull("editing must prefill the current effective magnitude", amount);
            TextView save = editor.getButton(AlertDialog.BUTTON_POSITIVE);
            amount.setText("0");
            assertFalse(save.isEnabled());
            amount.setText("225000");
            assertTrue(save.isEnabled());
            assertTrue(save.performClick());
            View screen = activity.getWindow().getDecorView();
            assertNotNull(findText(screen, "−225,000"));
            assertNotNull(findText(screen, "−125,000"));
            clickText(screen, activity.getString(R.string.commitments_mark_paid));
        });
        Commitment marked = CommitmentStore.get(ctx, source.id);
        assertEquals(-225_000L, marked.amountAt(due));
        assertTrue(marked.isSettled(due));
        assertEquals(-125_000L, marked.amountAt(next));
        assertFalse(marked.isSettled(next));

        commitmentsScenario.onActivity(activity -> {
            View screen = activity.getWindow().getDecorView();
            clickText(screen, activity.getString(R.string.commitments_show_older_paid, 1));
            TextView undo = findText(screen, activity.getString(R.string.commitments_undo));
            assertNotNull(undo);
            AlertDialog editor = openDueEditor(activity, (View) undo.getParent());
            EditText amount = findEditText(editor.getWindow().getDecorView(), "225000");
            assertNotNull(amount);
            amount.setText("75000");
            assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        });
        Commitment changed = CommitmentStore.get(ctx, source.id);
        assertEquals(-75_000L, changed.amountAt(due));
        assertTrue("editing the amount must not undo a settlement", changed.isSettled(due));
        assertEquals(-125_000L, changed.amountAt(next));

        commitmentsScenario.onActivity(activity -> {
            View screen = activity.getWindow().getDecorView();
            assertNotNull("the older paid group must stay expanded after editing",
                findText(screen, activity.getString(R.string.commitments_hide_older_paid)));
            TextView undo = findText(screen, activity.getString(R.string.commitments_undo));
            assertNotNull(undo);
            assertTrue(undo.performClick());
            AlertDialog editor = openDueEditor(activity, screen);
            assertNotNull(findEditText(editor.getWindow().getDecorView(), "75000"));
            TextView reset = editor.getButton(AlertDialog.BUTTON_NEUTRAL);
            assertEquals(activity.getString(R.string.commitments_reset_due_amount),
                reset.getText().toString());
            assertTrue(reset.performClick());
        });
        Commitment reset = CommitmentStore.get(ctx, source.id);
        assertNull(reset.occurrenceAmountOverride(due));
        assertEquals(-125_000L, reset.amountAt(due));
        assertFalse(reset.isSettled(due));
        assertEquals(-125_000L, reset.amountAt(next));
    }

    @Test public void oneTimeDueIsOnlyEditedFromManage() {
        Commitment once = Commitment.create("single bill", -1000, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, 0);
        BalanceData.writeCommitments(ctx, singleton(once));
        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);
        commitmentsScenario.onActivity(activity -> {
            View screen = activity.getWindow().getDecorView();
            assertNotNull(findText(screen, once.name));
            assertNull(findText(screen, activity.getString(R.string.commitments_edit_due)));
            assertNotNull(openSeriesEditor(activity, once.name));
        });
    }

    @Test public void dueEditorIsSecureAndDismissedOnPause() {
        long due = Commitment.startOfDay(System.currentTimeMillis());
        Commitment source = Commitment.create("private bill", -1000L, Commitment.MONTHLY,
            due, null, false, 0);
        BalanceData.writeCommitments(ctx, singleton(source));
        LockManager.enable(ctx, "test-password", false, false);
        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);
        commitmentsScenario.onActivity(activity -> {
            View screen = activity.getWindow().getDecorView();
            assertTrue(LockManager.isSessionLocked());
            clickText(screen, activity.getString(R.string.commitments_edit_due));
            assertNull(dialogField(activity, "activeDialog"));
            EditText code = findEditText(screen, "");
            assertNotNull(code);
            code.setText("test-password");
            TextView unlock = findText(screen, activity.getString(R.string.lock_action_unlock));
            assertNotNull(unlock);
            assertTrue(((View) unlock.getParent()).performClick());
        });
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (LockManager.isSessionLocked() && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(20);
        assertFalse("verification must finish", LockManager.isSessionLocked());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        AlertDialog[] opened = {null};
        commitmentsScenario.onActivity(activity -> {
            opened[0] = openDueEditor(activity, activity.getWindow().getDecorView());
            assertTrue((opened[0].getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        });
        commitmentsScenario.moveToState(Lifecycle.State.STARTED);
        commitmentsScenario.onActivity(activity -> {
            assertFalse(opened[0].isShowing());
            assertNull(dialogField(activity, "activeDialog"));
        });
    }

    @Test public void aFutureReminderTargetCanBeEdited_andAStaleSaveDoesNotRetarget() throws Exception {
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_TOMAN);
        long future = Commitment.millisOf(2090, 1, 15, CalendarSystem.GREGORIAN);
        Commitment source = Commitment.create("future rebate", 1_000L, Commitment.MONTHLY,
            future, null, false, 0);
        BalanceData.writeCommitments(ctx, singleton(source));
        commitmentsScenario = ActivityScenario.launch(CommitmentReminders
            .dueIntent(ctx, source.id, future).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AlertDialog[] opened = {null};
        commitmentsScenario.onActivity(activity -> {
            View target = activity.getWindow().getDecorView()
                .findViewWithTag("reminder-due:" + source.id + ":" + future);
            assertNotNull(target);
            AlertDialog editor = openDueEditor(activity, target);
            assertNotNull(findText(editor.getWindow().getDecorView(), "15 January 2090"));
            assertNotNull(findText(editor.getWindow().getDecorView(),
                activity.getString(R.string.commitments_edit_due_receipt)));
            EditText amount = findEditText(editor.getWindow().getDecorView(), "100");
            assertNotNull(amount);
            amount.setText("123.4");
            assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            View updatedTarget = activity.getWindow().getDecorView().findViewWithTag(target.getTag());
            assertNotNull(updatedTarget);
            assertNotNull(findText(updatedTarget, "+123.4"));
            opened[0] = openDueEditor(activity, updatedTarget);
        });
        assertEquals(1234L, CommitmentStore.get(ctx, source.id).amountAt(future));
        assertTrue(CommitmentStore.delete(ctx, source.id));
        commitmentsScenario.onActivity(activity -> {
            EditText amount = findEditText(opened[0].getWindow().getDecorView(), "123.4");
            assertNotNull(amount);
            amount.setText("9999");
            assertTrue(opened[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertFalse(opened[0].isShowing());
            assertNotNull(activity.getWindow().getDecorView()
                .findViewWithTag("reminder-due-unavailable"));
        });
        assertNull(CommitmentStore.get(ctx, source.id));
    }

    @Test public void dashboardGuidanceAppearsOnlyWithoutDefinitions() {
        // The harness owns runtime permissions: revoking READ_SMS to restore it can kill this
        // instrumentation process. This renderer test neither seeds nor waits for SMS data.
        assertEquals("the dashboard fixture requires harness-granted SMS access",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_SMS));
        mainScenario = ActivityScenario.launch(MainActivity.class);

        BalanceData.writeCommitments(ctx, new ArrayList<Commitment>());
        List<String> empty = drawCommitmentsCard();
        Context strings = LocaleHelper.wrap(ctx);
        assertTrue(empty.contains(strings.getString(R.string.commitments_card_empty)));
        assertTrue(empty.contains(strings.getString(R.string.commitments_card_empty_action)));
        assertFalse(empty.contains(REMAINING_PAYABLE));
        assertFalse(empty.contains(REMAINING_RECEIVABLE));

        long today = Commitment.startOfDay(System.currentTimeMillis());
        Commitment settled = Commitment.create("settled", -100_000L, Commitment.MONTHLY,
            today, null, false, 0);
        List<Long> paid = new ArrayList<>();
        paid.add(today);
        settled = new Commitment(settled.id, settled.name, settled.amount, settled.frequency,
            settled.start, settled.end, settled.done, paid, settled.remind, settled.remindBeforeMs);
        BalanceData.writeCommitments(ctx, singleton(settled));
        List<String> allSettled = drawCommitmentsCard();
        assertRemainingZeroes(allSettled);
        List<String> masked = drawCommitmentsCard(true);
        assertEquals("both dashboard commitment amounts must be masked", 2,
            count(masked, "••••••"));
        assertFalse(masked.contains(CurrencyHelper.amount(ctx, 0) + " "
            + CurrencyHelper.label(LocaleHelper.wrap(ctx))));

        Commitment future = Commitment.create("future", 200_000L, Commitment.MONTHLY,
            nextMonthDate(), null, false, 0);
        BalanceData.writeCommitments(ctx, singleton(future));
        List<String> futureOnly = drawCommitmentsCard();
        assertRemainingZeroes(futureOnly);
    }

    @Test public void dashboardShowsExactEffectiveAmountsAndExcludesSettledOverrides() {
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_SMS));
        long today = Commitment.startOfDay(System.currentTimeMillis());
        Commitment first = Commitment.create("custom due", -100L, Commitment.MONTHLY,
            today, null, false, 0, CalendarSystem.GREGORIAN, java.time.ZoneId.systemDefault())
            .withOccurrenceAmounts(Collections.singletonMap(today, Long.MIN_VALUE));
        Commitment second = Commitment.create("one-time due", -1000L, Commitment.ONCE,
            today, null, false, 0);
        Commitment settled = Commitment.create("settled receipt", 50L, Commitment.MONTHLY,
            today, null, false, 0, CalendarSystem.GREGORIAN, java.time.ZoneId.systemDefault())
            .withSettlements(false, Collections.singletonList(today), null)
            .withOccurrenceAmounts(Collections.singletonMap(today, 500L));
        BalanceData.writeCommitments(ctx, java.util.Arrays.asList(first, second, settled));
        BalanceData.setAutoHide(ctx, false);
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_HIDDEN, false).apply();
        mainScenario = ActivityScenario.launch(MainActivity.class);

        List<String> drawn = drawCommitmentsCard();
        String unit = CurrencyHelper.label(LocaleHelper.wrap(ctx));
        String expected = "9,223,372,036,854,776,808";
        assertTrue(drawn.contains(expected + " " + unit));
        assertTrue(drawn.contains("0 " + unit));
        mainScenario.onActivity(activity -> {
            View card = activity.findViewById(R.id.dashboard_commitments_card);
            assertNotNull(card);
            assertNotNull(findText(card, expected));
            assertNotNull(findText(card, "0"));
        });
    }

    @Test public void editor_preservesRawReminderAndARecentDueEdit() throws Exception {
        long rawLeadMs = 30L * 60_000L + 1L;
        long due = Commitment.startOfDay(System.currentTimeMillis());
        Commitment source = Commitment.create("raw reminder", -100_000L, Commitment.MONTHLY,
            due, null, true, rawLeadMs)
            .freezeSchedule(CalendarSystem.GREGORIAN, java.time.ZoneId.systemDefault());
        String changedName = "raw reminder saved";
        BalanceData.writeCommitments(ctx, singleton(source));

        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);
        AlertDialog[] opened = {null};
        commitmentsScenario.onActivity(activity -> {
            AlertDialog editor = openSeriesEditor(activity, source.name);
            opened[0] = editor;
            EditText lead = findEditText(editor.getWindow().getDecorView(), String.valueOf(rawLeadMs));
            assertNotNull("a raw reminder must reopen with its exact milliseconds", lead);
            assertEquals(String.valueOf(rawLeadMs), lead.getText().toString());
            EditText name = findEditText(editor.getWindow().getDecorView(), source.name);
            assertNotNull("the editor must expose the current name", name);
            name.setText(changedName);
        });
        assertTrue(CommitmentStore.setOccurrenceAmount(ctx, source.id, due, true, -150_000L));
        // onShow posts the real save listener; an immediate click above only dismisses by default.
        commitmentsScenario.onActivity(activity -> {
            assertSame("save must target this scenario's exact editor", opened[0],
                dialogField(activity, "activeDialog"));
            assertTrue(opened[0].isShowing());
            assertTrue(opened[0].getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertTrue("Save must be clicked",
                opened[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        });
        awaitEditorDismissed(opened[0]);

        List<Commitment> saved = BalanceData.readCommitments(ctx);
        assertEquals(1, saved.size());
        Commitment persisted = saved.get(0);
        assertEquals(source.id, persisted.id);
        assertEquals("the changed name proves Save actually persisted", changedName, persisted.name);
        assertEquals(rawLeadMs, persisted.remindBeforeMs);
        assertEquals(source.remind, persisted.remind);
        assertEquals(source.amount, persisted.amount);
        assertEquals(source.frequency, persisted.frequency);
        assertEquals(source.start, persisted.start);
        assertEquals(source.end, persisted.end);
        assertEquals(source.done, persisted.done);
        assertNotNull(persisted.schedule);
        assertEquals(source.schedule.calendar, persisted.schedule.calendar);
        assertEquals(source.schedule.zone, persisted.schedule.zone);
        assertEquals(source.schedule.first, persisted.schedule.first);
        assertEquals(source.schedule.last, persisted.schedule.last);
        assertEquals(-150_000L, persisted.amountAt(due));
    }

    @Test public void seriesEditKeepsDormantOverrideAndRestoresItWithOriginalDirection()
            throws Exception {
        long due = Commitment.startOfDay(System.currentTimeMillis());
        Commitment source = Commitment.create("monthly bill", -1000L, Commitment.MONTHLY,
            due, due, false, 0, CalendarSystem.GREGORIAN, java.time.ZoneId.systemDefault())
            .withOccurrenceAmounts(Collections.singletonMap(due, -1500L));
        BalanceData.writeCommitments(ctx, singleton(source));
        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);

        changeSeriesDirection(source, R.string.commitments_receive,
            "Total −1,500 · regular amount −1,000", "1 of 1 remaining · −1,500 total");
        Commitment receipt = CommitmentStore.get(ctx, source.id);
        assertEquals(Long.valueOf(-1500L), receipt.occurrenceAmounts.get(due));
        assertNull(receipt.occurrenceAmountOverride(due));
        assertEquals(1000L, receipt.amountAt(due));

        changeSeriesDirection(source, R.string.commitments_pay,
            "Total +1,000 · regular amount +1,000", "1 of 1 remaining · +1,000 total");
        Commitment restored = CommitmentStore.get(ctx, source.id);
        assertEquals(-1500L, restored.amountAt(due));
    }

    private void changeSeriesDirection(Commitment source, int direction, String manageAmount,
            String seriesSummary) {
        AlertDialog[] opened = {null};
        commitmentsScenario.onActivity(activity -> {
            opened[0] = openSeriesEditor(activity, source.name, manageAmount);
            View editor = opened[0].getWindow().getDecorView();
            assertNotNull(findText(editor, seriesSummary));
            clickText(editor, activity.getString(direction));
        });
        commitmentsScenario.onActivity(activity ->
            assertTrue(opened[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick()));
        awaitEditorDismissed(opened[0]);
    }

    private void awaitEditorDismissed(AlertDialog expected) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] gone = {false};
            commitmentsScenario.onActivity(activity -> {
                assertFalse("the editor's activity must remain alive", activity.isDestroyed());
                assertFalse(activity.isFinishing());
                AlertDialog active = dialogField(activity, "activeDialog");
                if (active != null) assertSame("the editor must not be replaced", expected, active);
                gone[0] = active == null && !expected.isShowing();
            });
            if (gone[0]) return;
            SystemClock.sleep(20L);
        }
        throw new AssertionError("the saved editor did not dismiss");
    }

    private void assertRemainingZeroes(List<String> drawn) {
        assertTrue("a non-empty definition list must retain the payable label",
            drawn.contains(REMAINING_PAYABLE));
        assertTrue("a non-empty definition list must retain the receivable label",
            drawn.contains(REMAINING_RECEIVABLE));
        assertEquals("both remaining amounts must be rendered as zero", 2,
            count(drawn, CurrencyHelper.amount(ctx, 0) + " "
                + CurrencyHelper.label(LocaleHelper.wrap(ctx))));
        Context strings = LocaleHelper.wrap(ctx);
        assertFalse("settled or future-only definitions are not an empty state",
            drawn.contains(strings.getString(R.string.commitments_card_empty)));
        assertFalse(drawn.contains(strings.getString(R.string.commitments_card_empty_action)));
    }

    private List<String> drawCommitmentsCard() {
        return drawCommitmentsCard(false);
    }

    private List<String> drawCommitmentsCard(boolean masked) {
        final TextCanvas recording = new TextCanvas();
        // Capture the reference BEFORE invalidating: anything installed afterwards is a fresh
        // object by construction. Capturing after the invalidate risks the opposite order — a
        // live frame can kick, finish and install a worker between the two calls, leaving
        // `before` already equal to the fresh object so the wait below can never observe a
        // turnover, and the helper times out on a healthy screen.
        final Object[] before = {null};
        mainScenario.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("view");
                field.setAccessible(true);
                Object view = field.get(activity);
                Field summaryField = view.getClass().getDeclaredField("commitmentSummary");
                summaryField.setAccessible(true);
                before[0] = summaryField.get(view);
                Method invalidateSummary = view.getClass().getDeclaredMethod(
                    "invalidateCommitmentSummary");
                invalidateSummary.setAccessible(true);
                invalidateSummary.invoke(view);
            } catch (Exception e) {
                throw new AssertionError("could not invalidate the dashboard commitments summary", e);
            }
        });
        // Waiting for a bare loaded flag is not enough: invalidate() keeps the previous
        // summary (and its loaded flag) up while the fresh load runs, so a fast poll would draw
        // the stale card. Every successful load installs a new Summary object, so wait for the
        // reference itself to turn over with no load in flight.
        long deadline = SystemClock.uptimeMillis() + 15_000L;
        boolean fresh = false;
        while (SystemClock.uptimeMillis() < deadline) {
            final Object[] state = {null, null, null, null};
            mainScenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("view");
                    field.setAccessible(true);
                    Object view = field.get(activity);
                    Field summaryField = view.getClass().getDeclaredField("commitmentSummary");
                    summaryField.setAccessible(true);
                    state[0] = summaryField.get(view);
                    Field loadingField =
                        view.getClass().getDeclaredField("commitmentSummaryLoading");
                    loadingField.setAccessible(true);
                    state[1] = loadingField.getBoolean(view);
                    Field loadedField = view.getClass().getDeclaredField("commitmentSummaryLoaded");
                    loadedField.setAccessible(true);
                    state[2] = loadedField.getBoolean(view);
                    Field staleField = view.getClass().getDeclaredField("commitmentSummaryStale");
                    staleField.setAccessible(true);
                    state[3] = staleField.getBoolean(view);
                    // A load overtaken by this invalidate discards its result without
                    // re-kicking: production heals on the next drawn frame, but this
                    // helper draws exactly once, so re-kick here instead of waiting out
                    // the deadline for a worker that will never come. Never invalidate
                    // in this loop: that would bump the generation under a slow worker
                    // and discard every result as fast as it arrives.
                    if (Boolean.TRUE.equals(state[3]) && Boolean.FALSE.equals(state[1])) {
                        Method ensure = view.getClass().getDeclaredMethod(
                            "ensureCommitmentSummary");
                        ensure.setAccessible(true);
                        ensure.invoke(view);
                    }
                } catch (Exception e) {
                    throw new AssertionError("could not read the dashboard summary state", e);
                }
            });
            if (state[0] != before[0] && Boolean.FALSE.equals(state[1])
                    && Boolean.TRUE.equals(state[2])) {
                fresh = true;
                break;
            }
            SystemClock.sleep(20L);
        }
        // Post-mortem for the wait above: reported inside the assertion when the reload
        // never lands, so one failing run tells whether a worker was stuck mid-load or the
        // reference simply never turned over.
        if (!fresh) {
            final Object[] last = {null, null, null};
            mainScenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("view");
                    field.setAccessible(true);
                    Object view = field.get(activity);
                    Field loadingField =
                        view.getClass().getDeclaredField("commitmentSummaryLoading");
                    loadingField.setAccessible(true);
                    last[0] = loadingField.getBoolean(view);
                    Field loadedField = view.getClass().getDeclaredField("commitmentSummaryLoaded");
                    loadedField.setAccessible(true);
                    last[1] = loadedField.getBoolean(view);
                    Field staleField = view.getClass().getDeclaredField("commitmentSummaryStale");
                    staleField.setAccessible(true);
                    last[2] = staleField.getBoolean(view);
                } catch (Exception e) {
                    throw new AssertionError("could not read the dashboard summary state", e);
                }
            });
            throw new AssertionError("the dashboard commitment summary did not finish reloading [loading="
                + last[0] + " loaded=" + last[1] + " stale=" + last[2] + "]");
        }
        mainScenario.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("view");
                field.setAccessible(true);
                Object view = field.get(activity);
                Field hidden = view.getClass().getDeclaredField("hidden");
                hidden.setAccessible(true);
                boolean previousHidden = hidden.getBoolean(view);
                hidden.setBoolean(view, masked);
                Method draw = view.getClass().getDeclaredMethod("drawCommitmentsCard",
                    Canvas.class, int.class, boolean.class);
                draw.setAccessible(true);
                try {
                    draw.invoke(view, recording, 800, false);
                } finally {
                    hidden.setBoolean(view, previousHidden);
                }
            } catch (Exception e) {
                throw new AssertionError("could not render the dashboard commitments card", e);
            }
        });
        return recording.texts;
    }

    private static int count(List<String> values, String wanted) {
        int n = 0;
        for (String value : values) if (wanted.equals(value)) n++;
        return n;
    }

    private static List<Commitment> singleton(Commitment commitment) {
        List<Commitment> out = new ArrayList<>();
        out.add(commitment);
        return out;
    }

    private static long nextMonthDate() {
        Calendar c = Calendar.getInstance(Locale.US);
        c.setTimeInMillis(System.currentTimeMillis());
        int day = c.get(Calendar.DAY_OF_MONTH);
        c.add(Calendar.MONTH, 1);
        day = Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        return Commitment.millisOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, day,
            CalendarSystem.GREGORIAN);
    }

    private static AlertDialog openDueEditor(CommitmentsActivity activity, View row) {
        clickText(row, activity.getString(R.string.commitments_edit_due));
        AlertDialog dialog = dialogField(activity, "activeDialog");
        assertNotNull("the occurrence amount editor must open", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static AlertDialog openSeriesEditor(CommitmentsActivity activity, String name) {
        return openSeriesEditor(activity, name, null);
    }

    private static AlertDialog openSeriesEditor(CommitmentsActivity activity, String name,
            String expectedAmount) {
        clickText(activity.getWindow().getDecorView(), activity.getString(R.string.commitments_manage));
        AlertDialog manage = dialogField(activity, "manageDialogWindow");
        assertNotNull(manage);
        View list = manage.getWindow().getDecorView();
        if (expectedAmount != null) assertNotNull(findText(list, expectedAmount));
        View definition = findText(list, name);
        assertNotNull("Manage must list the commitment", definition);
        View clickable = definition;
        while (clickable != null && !clickable.isClickable()) {
            android.view.ViewParent parent = clickable.getParent();
            clickable = parent instanceof View ? (View) parent : null;
        }
        assertNotNull("the Manage row must be clickable", clickable);
        assertTrue(clickable.performClick());
        AlertDialog editor = dialogField(activity, "activeDialog");
        assertNotNull("the series editor must open", editor);
        return editor;
    }

    private static void clickText(View root, String label) {
        TextView button = findText(root, label);
        assertNotNull("missing action: " + label, button);
        assertTrue(button.performClick());
    }

    private static TextView findText(View root, String wanted) {
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && wanted.contentEquals(text)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText findEditText(View root, String wanted) {
        if (root instanceof EditText) {
            EditText edit = (EditText) root;
            if (wanted.contentEquals(edit.getText())) return edit;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static AlertDialog dialogField(CommitmentsActivity activity, String name) {
        try {
            Field field = CommitmentsActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (AlertDialog) field.get(activity);
        } catch (Exception e) {
            throw new AssertionError("could not inspect the commitments dialog", e);
        }
    }

    private static final class TextCanvas extends Canvas {
        final List<String> texts = new ArrayList<>();

        TextCanvas() {
            super(Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888));
        }

        @Override public void drawText(String text, float x, float y, Paint paint) {
            texts.add(text);
        }
    }
}
