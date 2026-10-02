package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * The gate that hides an experimental feature until someone deliberately asks for it.
 *
 * <p>Two claims are being held here. The first is that the feature really is absent by default: a
 * reader who never opened the gate must not find the own-packs row on the banks screen, because a row
 * offering something we cannot yet promise is worse than no row at all. The second is that turning it
 * on is deliberate — a press that ends early, or a hold abandoned because the screen went away, must
 * leave everything exactly as it was.</p>
 */
@RunWith(AndroidJUnit4.class)
public class ExperimentalGateTest {

    private Context ctx;
    private final List<android.app.Activity> launched = new ArrayList<>();

    @Before public void startFromTheDefault() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Experimental.setOwnPacksEnabled(ctx, false);
        // The banks screen reads the rule engine while it draws. Activating it first is what the
        // dashboard has already done by the time anyone reaches that screen, and it keeps the test
        // from measuring the engine's first load instead of what it is asking about.
        EngineRules.activate(ctx);
    }

    @After public void restoreTheDefault() {
        // Screens left open would sit on the stack for the next test to fight with, and one of them
        // still counting down would keep the main thread busy for the rest of the run.
        for (android.app.Activity act : launched)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        launched.clear();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Experimental.setOwnPacksEnabled(ctx, false);
    }

    @Test public void theFeatureIsOffUntilSomeoneTurnsItOn() {
        assertFalse("nothing opts a reader in by accident", Experimental.ownPacksEnabled(ctx));
        Experimental.setOwnPacksEnabled(ctx, true);
        assertTrue("the choice sticks", Experimental.ownPacksEnabled(ctx));
        Experimental.setOwnPacksEnabled(ctx, false);
        assertFalse("and it can be taken back", Experimental.ownPacksEnabled(ctx));
    }

    @Test public void theHoldIsLongEnoughToBeDeliberate() {
        assertEquals("ten seconds, as the About screen says it is",
            10_000L, Experimental.UNLOCK_MILLIS);
    }

    @Test public void theBanksScreenOffersNoOwnPacksRowUntilTheGateIsOpen() {
        assertFalse("the row is not on the banks screen by default",
            banksScreenMentionsOwnPacks());
        Experimental.setOwnPacksEnabled(ctx, true);
        assertTrue("and it is there once the gate is open",
            banksScreenMentionsOwnPacks());
    }

    @Test public void theVersionLineCountsDownWhileItIsHeld() throws Exception {
        AboutActivity about = launch(AboutActivity.class);
        TextView footer = versionLine(about);
        String idle = textOf(footer);

        touch(about, footer, MotionEvent.ACTION_DOWN);
        try {
            SystemClock.sleep(200);
            String counting = textOf(footer);
            assertNotEquals("a held version line says it is counting down", idle, counting);
            assertTrue("and it is the countdown, not some other text",
                counting.startsWith(ctx.getString(R.string.experimental_hold, 10)
                    .replaceAll("[0-9]+$", "").trim()));
        } finally {
            // A hold left running would keep posting for ten seconds and starve whatever the next
            // test needs the main thread for.
            touch(about, footer, MotionEvent.ACTION_UP);
        }
    }

    @Test public void releasingEarlyLeavesTheGateShut() throws Exception {
        AboutActivity about = launch(AboutActivity.class);
        TextView footer = versionLine(about);
        String idle = textOf(footer);

        touch(about, footer, MotionEvent.ACTION_DOWN);
        SystemClock.sleep(200);
        assertFalse("a press in progress has not unlocked anything yet",
            Experimental.ownPacksEnabled(ctx));
        touch(about, footer, MotionEvent.ACTION_UP);

        assertFalse("releasing early leaves the gate shut", Experimental.ownPacksEnabled(ctx));
        assertEquals("and puts the version line back", idle, textOf(footer));
    }

    @Test public void aHoldAbandonedWithTheScreenDoesNotUnlock() throws Exception {
        AboutActivity about = launch(AboutActivity.class);
        TextView footer = versionLine(about);
        String idle = textOf(footer);

        touch(about, footer, MotionEvent.ACTION_DOWN);
        SystemClock.sleep(200);
        // The screen going away mid-hold is the same thing as letting go: the countdown must stop,
        // or a press the reader walked away from could still open the gate on its own later.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            about.onPause();
            footer.dispatchTouchEvent(MotionEvent.obtain(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1f, 1f, 0));
        });
        SystemClock.sleep(300);

        assertFalse("an abandoned hold leaves the gate shut", Experimental.ownPacksEnabled(ctx));
        assertEquals("and puts the version line back", idle, textOf(footer));
    }

    /** The version line, found by what it says: the lock overlay is added to the window after the
     *  body and brings its own buttons with it, so position in the tree is not the identifier. */
    private TextView versionLine(AboutActivity about) {
        List<String> texts = new ArrayList<>();
        List<TextView> found = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            collect(about.getWindow().getDecorView(), found, texts));
        String version = version();
        TextView match = null;
        for (int i = 0; i < found.size(); i++)
            if (texts.get(i).contains(version)
                && texts.get(i).contains(ctx.getString(R.string.app_name))) match = found.get(i);
        assertNotNull("the About screen has a version line to hold", match);
        return match;
    }

    private String version() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void touch(AboutActivity about, TextView footer, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 1f, 1f, 0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            footer.dispatchTouchEvent(event));
        event.recycle();
    }

    private String textOf(TextView v) {
        String[] out = { null };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            out[0] = v.getText().toString());
        return out[0];
    }

    private boolean banksScreenMentionsOwnPacks() {
        boolean[] found = { false };
        try (androidx.test.core.app.ActivityScenario<BankRecognitionActivity> screen =
                androidx.test.core.app.ActivityScenario.launch(BankRecognitionActivity.class)) {
            screen.onActivity(act -> {
                List<String> out = new ArrayList<>();
                collectText(act.getWindow().getDecorView(), out);
                for (String t : out)
                    if (t.contains(ctx.getString(R.string.local_packs_title))) found[0] = true;
            });
        }
        return found[0];
    }

    @SuppressWarnings("unchecked")
    private <T extends android.app.Activity> T launch(Class<T> screen) {
        T act = (T) InstrumentationRegistry.getInstrumentation().startActivitySync(
            new android.content.Intent(ctx, screen).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        launched.add(act);
        return act;
    }

    private void collect(android.view.View v, List<TextView> out, List<String> texts) {
        if (v instanceof TextView) {
            out.add((TextView) v);
            texts.add(((TextView) v).getText().toString());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out, texts);
        }
    }

    private void collectText(android.view.View v, List<String> out) {
        if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), out);
        }
    }
}