package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    private String originalLanguage;
    private String originalCurrency;
    private int originalRegion;
    private boolean originalShowCommitments;
    private boolean originalOnboardingSeen;
    private boolean originalSmsPermission;
    private List<Commitment> originalCommitments;
    private ActivityScenario<CommitmentsActivity> commitmentsScenario;
    private ActivityScenario<MainActivity> mainScenario;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentStore.clear(ctx);
        originalLanguage = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        originalRegion = RegionHelper.region(ctx);
        originalShowCommitments = BalanceData.getShowCommitments(ctx);
        originalOnboardingSeen = BalanceData.isOnboardingSeen(ctx);
        originalSmsPermission = ctx.checkSelfPermission(Manifest.permission.READ_SMS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
        originalCommitments = new ArrayList<>(BalanceData.readCommitments(ctx));

        // Keep all visible strings and date arithmetic deterministic, including on a Persian device.
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        RegionHelper.setRegion(ctx, RegionHelper.REGION_INTERNATIONAL);
        BalanceData.setShowCommitments(ctx, true);
        BalanceData.setOnboardingSeen(ctx, true);
        BalanceData.writeCommitments(ctx, new ArrayList<Commitment>());
    }

    @After public void tearDown() throws Exception {
        if (commitmentsScenario != null) commitmentsScenario.close();
        if (mainScenario != null) mainScenario.close();

        BalanceData.writeCommitments(ctx, originalCommitments);
        BalanceData.setShowCommitments(ctx, originalShowCommitments);
        BalanceData.setOnboardingSeen(ctx, originalOnboardingSeen);
        LocaleHelper.setLanguage(ctx, originalLanguage);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        RegionHelper.setRegion(ctx, originalRegion);
        setSmsPermission(originalSmsPermission);
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
        // Granting SMS access bypasses the first-run permission dialog. The test never seeds or waits
        // for SMS data; it only invokes the commitment card renderer after the Activity is attached.
        setSmsPermission(true);
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
            due, null, true, rawLeadMs);
        BalanceData.writeCommitments(ctx, singleton(source));

        commitmentsScenario = ActivityScenario.launch(CommitmentsActivity.class);
        commitmentsScenario.onActivity(activity -> {
            String manageLabel = LocaleHelper.wrap(ctx).getString(R.string.commitments_manage);
            TextView manage = findText(activity.getWindow().getDecorView(), manageLabel);
            assertNotNull("the commitments screen must show Manage", manage);
            assertTrue(manage.performClick());

            AlertDialog manageDialog = dialogField(activity, "manageDialogWindow");
            TextView definition = findText(manageDialog.getWindow().getDecorView(), source.name);
            assertNotNull("the reminder definition must be listed", definition);
            assertTrue(definition.performClick());
        });

        commitmentsScenario.onActivity(activity -> {
            AlertDialog editor = dialogField(activity, "activeDialog");
            EditText lead = findEditText(editor.getWindow().getDecorView(), String.valueOf(rawLeadMs));
            assertNotNull("a raw reminder must reopen with its exact milliseconds", lead);
            assertEquals(String.valueOf(rawLeadMs), lead.getText().toString());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });

        List<Commitment> saved = BalanceData.readCommitments(ctx);
        assertEquals(1, saved.size());
        assertEquals(rawLeadMs, saved.get(0).remindBeforeMs);
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
        mainScenario.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("view");
                field.setAccessible(true);
                Object view = field.get(activity);
                Field hidden = view.getClass().getDeclaredField("hidden");
                hidden.setAccessible(true);
                boolean previousHidden = hidden.getBoolean(view);
                hidden.setBoolean(view, masked);
                Method invalidateSummary = view.getClass().getDeclaredMethod(
                    "invalidateCommitmentSummary");
                invalidateSummary.setAccessible(true);
                invalidateSummary.invoke(view);
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

    private void setSmsPermission(boolean grant) {
        try {
            if (grant) {
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(ctx.getPackageName(), Manifest.permission.READ_SMS);
            } else {
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .revokeRuntimePermission(ctx.getPackageName(), Manifest.permission.READ_SMS);
            }
        } catch (SecurityException ignored) {
            // The permission may be fixed by the test harness; the activity still renders without it.
        }
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
