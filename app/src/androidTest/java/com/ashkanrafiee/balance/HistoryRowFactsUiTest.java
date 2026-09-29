package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * What the bank stated about a movement, as the user actually meets it: that the reason and the
 * channel reach the screen as the app's own words rather than a raw bank string, and that a screen
 * reader says both. The row is one clickable node carrying its own description, so a description on
 * the chip inside it would never be announced — these tests pin the description the row is actually
 * given, for each combination of the three separate stores.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryRowFactsUiTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;

    private static final String BANK = "Tejarat";
    private static final String ACCOUNT = "01350000000";
    private static final String SHETAB = "شتاب";
    private static final String BRANCH = "شعبه";
    private static final String TOPUP = "شارژ شدی";

    /** The one movement under test, dated inside today so the row is on screen without expanding
     *  anything. */
    private Transaction movement;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        // Pin the language and unit so the captions written out below are the ones on screen.
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        // Each test states its own facts and nothing else, so it starts from an empty store rather
        // than from whatever the previous test — or the previous session — left behind.
        BalanceData.reset(ctx, true);
        movement = new Transaction(BANK, ACCOUNT, System.currentTimeMillis(), -220_000L, 48_000_000L,
            "sig-A", "content-A");
        FinancialTestStore.writeTransactions(ctx, new ArrayList<>(Arrays.asList(movement)));
    }

    @After public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.reset(ctx, true);
    }

    private androidx.test.core.app.ActivityScenario<HistoryActivity> scenario;

    private void stateReason(String reason) throws Exception {
        java.util.Map<String, String> reasons = new java.util.HashMap<>();
        reasons.put(BalanceData.noteKey(movement), reason);
        FinancialTestStore.mergeReasons(ctx, reasons);
    }

    private void stateChannel(String channel) throws Exception {
        java.util.Map<String, String> channels = new java.util.HashMap<>();
        channels.put(BalanceData.noteKey(movement), channel);
        FinancialTestStore.mergeChannels(ctx, channels);
    }

    /** Renders the screen and waits for it. {@code stringsIn} is the context the assertions read
     *  their expected text through, which is not {@link #ctx} once the app's language has been
     *  changed for a test: the screen follows the app's language, and a test comparing it against the
     *  old one would be looking for a string the app has no reason to show. */
    private void launch(Context stringsIn) {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, BANK)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = androidx.test.core.app.ActivityScenario.launch(i);
        // The screen builds itself off the main thread, so wait for the export action, which only
        // exists once the history header is in place.
        await(() -> findByDescription(stringsIn.getString(R.string.history_export)) != null, 20_000);
    }

    @Test public void aMovementWithAReason_showsTheCaptionAndSpeaksIt() throws Exception {
        stateReason(TOPUP);
        launch(ctx);

        assertNotNull("the reason must be shown in the app's own words",
            findByText("Phone top-up"));
        View row = findByDescriptionContaining("Phone top-up");
        assertNotNull("the reason must be part of what the row is announced as", row);
        assertTrue("the row must stay reachable by touch", row.isClickable());
    }

    @Test public void aMovementWithAChannel_showsTheCaptionAndSpeaksIt() throws Exception {
        // A channel on its own is the whole reason a screen reader has to be told: the chip inside the
        // row is never reached, so a row the bank said nothing else about would otherwise be heard as
        // nothing but the invitation to add a note.
        stateChannel(SHETAB);
        launch(ctx);

        assertNotNull("the channel must be shown in the app's own words", findByText("Shetab"));
        View row = findByDescriptionContaining("Shetab");
        assertNotNull("the channel must be part of what the row is announced as", row);
        assertTrue("the row must stay reachable by touch", row.isClickable());
    }

    @Test public void aMovementWithBoth_showsAndSpeaksBoth() throws Exception {
        stateReason(TOPUP);
        stateChannel(SHETAB);
        launch(ctx);

        assertNotNull(findByText("Phone top-up"));
        assertNotNull(findByText("Shetab"));
        View row = findByDescriptionContaining("Phone top-up");
        assertNotNull(row);
        // Both clauses ride on one description, and neither displaces the other.
        assertTrue("the description must carry the channel too",
            row.getContentDescription().toString().contains("Shetab"));
        assertTrue("the description must still invite a note",
            row.getContentDescription().toString().contains(
                ctx.getString(R.string.note_row_hint)));
    }

    @Test public void aMovementWithNeither_keepsThePlainNoteHint() throws Exception {
        launch(ctx);
        View row = findByDescription(ctx.getString(R.string.note_row_hint));
        assertNotNull("a movement the bank said nothing about is just an invitation to add a note",
            row);
        assertTrue(row.isClickable());
    }

    @Test public void aChannelThisBuildCannotCaption_showsNothingAndSpeaksNothing() throws Exception {
        // Storage is not the filter: an unknown channel is not on the row and not in the description,
        // so a fragment of a bank message never reaches the user as though the app understood it.
        stateChannel("درگاه اینترنتی");
        launch(ctx);

        assertNull(findByText("درگاه اینترنتی"));
        assertNotNull(findByDescription(ctx.getString(R.string.note_row_hint)));
    }

    @Test public void bothFacts_inPersian_areSpokenInPersian() throws Exception {
        // The same row in the other language: the clauses and the hint are all built from resources,
        // so a Persian user hears Persian and a missing translation would show up here.
        LocaleHelper.setLanguage(ctx, "fa");
        stateReason(TOPUP);
        stateChannel(SHETAB);
        Context fa = LocaleHelper.wrap(ctx);
        launch(fa);

        View row = findByDescriptionContaining(fa.getString(R.string.row_fact_channel, "شتاب"));
        assertNotNull("the channel clause must be spoken in Persian", row);
        assertTrue("the reason clause must be spoken too",
            row.getContentDescription().toString()
                .contains(fa.getString(R.string.row_fact_reason, "شارژ تلفن همراه")));
        assertTrue("the hint must be spoken too",
            row.getContentDescription().toString().contains("یادداشت"));
    }

    @Test public void aUserNote_isSpokenAlongsideTheFactsTheBankGave() throws Exception {
        // All three at once, in the order they are read: the bank's two facts, then the user's own
        // words, then what the tap still does. A note is the one thing on the row no rescan brings
        // back, so leaving it out of the description would make it write-only.
        stateReason(TOPUP);
        stateChannel(SHETAB);
        BalanceData.setNote(ctx, movement, "rent for Ali");
        launch(ctx);

        assertNotNull("the reason must be shown", findByText("Phone top-up"));
        assertNotNull("the channel must be shown", findByText("Shetab"));
        assertNotNull("the user's own note must be shown", findByText("rent for Ali"));
        View row = findByDescriptionContaining("Phone top-up");
        assertNotNull(row);
        assertTrue("tapping the row still opens the note", row.isClickable());
        assertSpeaksInOrder(row, "Reason the bank gave: Phone top-up.", "Channel: Shetab.",
            "Your note: rent for Ali.", "Tap to edit it");
    }

    @Test public void aUserNoteOnItsOwn_isSpokenRatherThanInvited() throws Exception {
        // A note the user wrote with the bank saying nothing is exactly the row that used to be heard
        // as a bare invitation. It is spoken instead, and the invitation to add a note is dropped in
        // favour of the edit the row really offers.
        BalanceData.setNote(ctx, movement, "rent for Ali");
        launch(ctx);

        View row = findByDescriptionContaining("rent for Ali");
        assertNotNull("the note must be spoken even when the bank said nothing", row);
        assertEquals(ctx.getString(R.string.row_fact_note, "rent for Ali") + " "
            + ctx.getString(R.string.row_hint_edit_note), spoken(row));
        assertFalse("a row that already has a note must not be heard as inviting one",
            spoken(row).contains(ctx.getString(R.string.note_row_hint)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Every TextView under the resumed activity's decor view, read on the main thread. */
    private static List<TextView> texts() {
        final List<TextView> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) collect(a.getWindow().getDecorView(), out);
        });
        return out;
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static TextView findByText(String want) {
        for (TextView t : texts()) if (want.equals(t.getText().toString())) return t;
        return null;
    }

    private static View findByDescription(String want) {
        // Compared through the text rather than with equals: a description that the framework has
        // wrapped in a SpannedString is no longer a String, and would never equal one.
        return findByDescription(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && want.contentEquals(d);
        });
    }

    private static View findByDescriptionContaining(String want) {
        return findByDescription(v -> {
            CharSequence d = v.getContentDescription();
            return d != null && d.toString().contains(want);
        });
    }

    private interface Match { boolean ok(View v); }

    private static View findByDescription(Match m) {
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : resumed()) {
                View hit = find(a.getWindow().getDecorView(), m);
                if (hit != null) hits.add(hit);
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static List<Activity> resumed() {
        List<Activity> out = new ArrayList<>();
        for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (a instanceof HistoryActivity) out.add(a);
        }
        return out;
    }

    private static View find(View v, Match m) {
        if (m.ok(v)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = find(g.getChildAt(i), m);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** What the row is actually announced as. */
    private static String spoken(View row) {
        return row.getContentDescription().toString();
    }

    /** Asserts the row is announced with these clauses, each one after the one before it. */
    private static void assertSpeaksInOrder(View row, String... clauses) {
        String said = spoken(row);
        int at = -1;
        for (String clause : clauses) {
            int next = said.indexOf(clause, at + 1);
            assertTrue("\"" + clause + "\" must be spoken after the clause before it, but the row is "
                + "announced as: " + said, next > at);
            at = next;
        }
    }

    private static void await(Callable<Boolean> done, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (done.call()) return; } catch (Exception ignored) { }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for the history screen");
    }
}
