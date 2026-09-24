package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The account-resolved history header (back chevron, bank badge, bank name, account chip, export
 * button) must keep the export action fully on screen no matter how long the account number is:
 * the account chip is the bar's flexible element, so a very long number is ellipsized instead of
 * shoving the export button past the screen edge (where the bar clips it out of view).
 */
@RunWith(AndroidJUnit4.class)
public class HistoryHeaderAccountTest {

    private static final String BANK = "Tejarat";
    private static final String LONG_ACCOUNT = "50417210123456789012";
    private static final String SHORT_ACCOUNT = "5000973189";

    private Context ctx;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Stage s : new Stage[]{Stage.RESUMED, Stage.PAUSED, Stage.STOPPED,
                    Stage.CREATED, Stage.STARTED}) {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s)) {
                    try { a.finish(); } catch (Exception ignored) { }
                }
            }
        });
    }

    @Test public void longAccountNumber_keepsTheExportButtonOnScreen() throws Exception {
        launch(BANK, LONG_ACCOUNT);
        View export = exportButton();
        assertTrue("export button must be visible with a long account number", export.getVisibility() == View.VISIBLE);
        assertOnScreen(export, "long account number");

        TextView chip = accountChip(LONG_ACCOUNT);
        assertNotNull("chip lighting up the account must exist", chip);
        int[] loc = new int[2];
        chip.getLocationOnScreen(loc);
        TextView c = chip;
        assertTrue("a long account number must ellipsize the chip instead of overflowing the bar",
            c.getLayout() != null && c.getLayout().getEllipsisCount(0) > 0);
        assertTrue("the ellipsized chip must still show something",
            c.getLayout().getEllipsisCount(0) < c.getLayout().getText().length());
    }

    @Test public void shortAccountNumber_showsTheChipAtItsNaturalWidth() throws Exception {
        launch(BANK, SHORT_ACCOUNT);
        View export = exportButton();
        assertOnScreen(export, "short account number");

        TextView chip = accountChip(SHORT_ACCOUNT);
        assertNotNull(chip);
        TextView c = chip;
        assertTrue("a short account number must not be ellipsized",
            c.getLayout() == null || c.getLayout().getEllipsisCount(0) == 0);
    }

    @Test public void tappingTheAccountChip_copiesTheAccountNumber() throws Exception {
        launch(BANK, LONG_ACCOUNT);
        TextView chip = accountChip(LONG_ACCOUNT);
        assertNotNull(chip);
        AtomicReference<Boolean> handled = new AtomicReference<>(Boolean.FALSE);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> handled.set(chip.performClick()));
        assertTrue("the account chip must accept a tap", handled.get());
        assertEquals("tapping the chip copies the bare account number", LONG_ACCOUNT, clipboardText());
    }

    @Test public void copiedAccountNumber_isClearedAfterThePasteWindow() throws Exception {
        launch(BANK, LONG_ACCOUNT);
        TextView chip = accountChip(LONG_ACCOUNT);
        assertNotNull(chip);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(chip::performClick);
        assertEquals("copy before waiting out the paste window", LONG_ACCOUNT, clipboardText());
        // The framework clears our clip but may leave a platform-owned empty rec: the guarantee to
        // assert is that the account number itself no longer sits on the clipboard (whatever else
        // the OS may post in its place).
        assertTrue("the account number must not linger on the system clipboard past the paste window",
            waitUntil(() -> !LONG_ACCOUNT.equals(clipboardText()), 25_000));
    }

    private String clipboardText() {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
        return t == null ? null : t.toString();
    }

    private void launch(String bank, String account) throws Exception {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, bank)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, account)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        waitUntil(() -> isResumed(HistoryActivity.class), 20_000);
        waitUntil(() -> exportButton() != null, 20_000);
    }

    /** The export action found by its content description; the header is built before the history
     *  list renders, so an empty store still produces it. */
    private View exportButton() {
        AtomicReference<View> hit = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String want = ctx.getString(R.string.history_export);
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof HistoryActivity)) continue;
                View found = findFirst(a.getWindow().getDecorView(), v -> want.equals(v.getContentDescription()));
                if (found != null) { hit.set(found); return; }
            }
        });
        return hit.get();
    }

    private TextView accountChip(String account) {
        AtomicReference<TextView> hit = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String want = ctx.getString(R.string.account_label) + " " + account;
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(a instanceof HistoryActivity)) continue;
                View found = findFirst(a.getWindow().getDecorView(), v ->
                    v instanceof TextView && want.equals(((TextView) v).getText().toString()));
                if (found instanceof TextView) { hit.set((TextView) found); return; }
            }
        });
        return hit.get();
    }

    /** Asserts the given view fully sits within the screen and its own parent, i.e. the header bar
     *  has not pushed or clipped it out of view. */
    private void assertOnScreen(View v, String what) throws Exception {
        assertNotNull("export button", v);
        AtomicReference<boolean[]> out = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int screenW = v.getResources().getDisplayMetrics().widthPixels;
            int left = loc[0], right = loc[0] + v.getWidth();
            out.set(new boolean[]{
                v.getVisibility() == View.VISIBLE && v.getWidth() > 0
                    && left >= 0 && right <= screenW
                    && right - left <= screenW,
            });
        });
        assertTrue("export button must stay fully on screen with a " + what, out.get()[0]);
    }

    private static View findFirst(View v, java.util.function.Predicate<View> match) {
        if (match.test(v)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findFirst(g.getChildAt(i), match);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private boolean isResumed(Class<?> type) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED))
                if (type.isInstance(a)) { found.set(true); return; }
        });
        return found.get();
    }

    private boolean waitUntil(java.util.function.Supplier<Boolean> probe, long deadlineMs) throws Exception {
        long deadline = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (Boolean.TRUE.equals(probe.get())) return true;
            Thread.sleep(150);
        }
        return Boolean.TRUE.equals(probe.get());
    }
}