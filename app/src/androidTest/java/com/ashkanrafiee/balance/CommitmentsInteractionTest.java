package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

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
import java.util.List;
import java.util.Locale;

/**
 * The commitments screen's two layers of interaction: an occurrence settles exactly its due day,
 * while the definition remains available through Manage, and the dashboard distinguishes an empty
 * definition list from a list with no currently remaining dues.
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
        // Detach lazy settlement lists before clear() invalidates their store revision, keeping
        // the original frozen calendar/zone rather than recapturing it during restoration.
        List<Commitment> snapshot = new ArrayList<>();
        for (Commitment c : BalanceData.readCommitments(ctx)) {
            snapshot.add(new Commitment(c.id, c.name, c.amount, c.frequency, c.start, c.end,
                c.done, new ArrayList<>(c.paid), c.legacyPaidThrough, new ArrayList<>(c.unpaid),
                c.remind, c.remindBeforeMs).withSchedule(c.schedule));
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

    @Test public void monthlyPaymentOccurrence_hasNoRowEditorAndMarkUndoKeepsExactDate() {
        assertOccurrenceRoundTrip(-125_000L, R.string.commitments_mark_paid);
    }

    @Test public void monthlyReceiptOccurrence_hasNoRowEditorAndMarkUndoKeepsExactDate() {
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
            assertTrue("Undo must remain a direct row action", undo.performClick());
        });

        List<Commitment> restored = BalanceData.readCommitments(ctx);
        assertEquals(1, restored.size());
        assertTrue("Undo must remove only the marked occurrence date", restored.get(0).paid.isEmpty());
        assertFalse(restored.get(0).done);
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

    @Test public void editor_preservesNonMinuteReminderWithoutAnInvalidChipSelection() {
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
            String manageLabel = LocaleHelper.wrap(ctx).getString(R.string.commitments_manage);
            TextView manage = findText(activity.getWindow().getDecorView(), manageLabel);
            assertNotNull("the commitments screen must show Manage", manage);
            assertTrue(manage.performClick());

            AlertDialog manageDialog = dialogField(activity, "manageDialogWindow");
            assertNotNull("the manage dialog must be showing", manageDialog);
            TextView definition = findText(manageDialog.getWindow().getDecorView(), source.name);
            assertNotNull("the reminder definition must be listed", definition);
            View clickable = definition;
            while (clickable != null && !clickable.isClickable()) {
                android.view.ViewParent parent = clickable.getParent();
                clickable = parent instanceof View ? (View) parent : null;
            }
            assertNotNull("the reminder row must be clickable", clickable);
            assertTrue(clickable.performClick());

            AlertDialog editor = dialogField(activity, "activeDialog");
            assertNotNull("the editor must open synchronously from the row click", editor);
            opened[0] = editor;
            EditText lead = findEditText(editor.getWindow().getDecorView(), String.valueOf(rawLeadMs));
            assertNotNull("a raw reminder must reopen with its exact milliseconds", lead);
            assertEquals(String.valueOf(rawLeadMs), lead.getText().toString());
            EditText name = findEditText(editor.getWindow().getDecorView(), source.name);
            assertNotNull("the editor must expose the current name", name);
            name.setText(changedName);
        });
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
