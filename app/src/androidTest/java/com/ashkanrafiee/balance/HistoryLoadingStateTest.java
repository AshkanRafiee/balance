package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ScrollView;
import androidx.lifecycle.Lifecycle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the history screen shows while it is still reading its history.
 *
 * <p>It reads and groups off the main thread, and until that finishes there is nothing to show. A
 * blank white page reads as an empty account, which is a different and alarming thing to say, so the
 * screen shows the shape of the history it is fetching instead.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryLoadingStateTest {

    private Context ctx;
    private String originalTag;
    private String originalCurrency;
    private androidx.test.core.app.ActivityScenario<HistoryActivity> scenario;
    private Transaction firstTransaction;

    private static final String MELLAT = "Mellat";
    private static final String ACCOUNT = "111";
    private static final long HOUR = 3600000L;

    @Before public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalTag = LocaleHelper.currentTag(ctx);
        originalCurrency = CurrencyHelper.currency(ctx);
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
        finishAnyResumedHistory();
        HistoryWarmup.invalidate();
    }

    @After public void tearDown() {
        HistoryActivity.setRenderGateForTest(null, null);
        HistoryActivity.scanHookForTest = null;
        HistoryActivity.renderCompletionHookForTest = null;
        HistoryActivity.skeletonSchedulerForTest = null;
        HistoryActivity.summaryReadHookForTest = null;
        if (scenario != null) scenario.close();
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.reset(ctx, true);
    }

    /** A small history is enough because the render gate makes the loading state deterministic. */
    private void storeEnoughToBeSlowToRead() {
        long now = System.currentTimeMillis();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            txs.add(new Transaction(MELLAT, ACCOUNT, now - i * HOUR,
                (i % 3 == 0 ? 1 : -1) * (100_000L + i), "sig" + i, null));
        }
        BalanceData.writeTransactions(ctx, txs);
        firstTransaction = txs.get(0);
    }

    @Test public void whileItIsStillReading_theScreenShowsPlaceholdersRatherThanBlank()
            throws Exception {
        storeEnoughToBeSlowToRead();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HistoryActivity.setRenderGateForTest(started, release);
        openHistory();
        try {
            assertTrue("the history render should reach the gate",
                started.await(10, TimeUnit.SECONDS));
            await(() -> findByDescription(ctx.getString(R.string.history_loading)) != null,
                10_000, "the delayed cold-load placeholders");
            assertNull("cold history uses placeholders, not a second refresh chip",
                findByDescription(ctx.getString(R.string.history_refreshing)));

            final List<View> bars = shimmerBars();
            assertTrue("the placeholders must have something to show", !bars.isEmpty());
            await(() -> allLaidOut(bars), 15_000, "the placeholders to be laid out");
        } finally {
            HistoryActivity.setRenderGateForTest(null, null);
            release.countDown();
        }
    }

    @Test public void coldReadUsesDelayedSkeletonWithoutAutomaticChip() throws Exception {
        storeEnoughToBeSlowToRead();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> deadlines = new ArrayList<>();
        HistoryActivity.skeletonSchedulerForTest = deadlines::add;
        HistoryActivity.setRenderGateForTest(started, release);
        openHistory();
        try {
            assertTrue("the first history render should reach the gate",
                started.await(10, TimeUnit.SECONDS));
            assertNull("the cold placeholder must wait for its grace period",
                findByDescription(ctx.getString(R.string.history_loading)));
            assertNull("automatic history loads must never start the manual refresh chip",
                findByDescription(ctx.getString(R.string.history_refreshing)));
            scenario.onActivity(activity -> {
                assertTrue("cold loading must schedule a placeholder deadline", !deadlines.isEmpty());
                deadlines.get(deadlines.size() - 1).run();
            });
            assertNotNull("a genuinely slow cold load needs its single loading treatment",
                findByDescription(ctx.getString(R.string.history_loading)));
            assertNull(findByDescription(ctx.getString(R.string.history_refreshing)));
            release.countDown();
            await(() -> findByText(ctx.getString(R.string.history_breakdown)) != null,
                60_000, "the refreshed history to finish rendering");
            assertNull(findByDescription(ctx.getString(R.string.history_loading)));
            assertNull(findByDescription(ctx.getString(R.string.history_refreshing)));
        } finally {
            HistoryActivity.skeletonSchedulerForTest = null;
            HistoryActivity.setRenderGateForTest(null, null);
            release.countDown();
        }
    }

    @Test public void quickWarmFullBankAndAccountOpens_neverFlashLoadingAnimation() {
        storeEnoughToBeSlowToRead();
        List<Runnable> deadlines = new ArrayList<>();
        HistoryActivity.skeletonSchedulerForTest = deadlines::add;
        try {
            for (int scope = 0; scope < 3; scope++) {
                String bank = scope == 0 ? null : MELLAT;
                String account = scope == 2 ? ACCOUNT : null;
                openHistory(bank, account);
                await(this::idle, 60_000, "the scope's cache to be populated");
                scenario.close();
                scenario = null;
                deadlines.clear();

                openHistory(bank, account);
                await(this::idle, 60_000, "the repeated cached scope to render before its deadline");
                scenario.onActivity(activity -> {
                    // Advance every captured grace-period callback after the quick result. A
                    // canceled or stale callback must not produce even a single shimmer frame.
                    for (Runnable deadline : new ArrayList<>(deadlines)) deadline.run();
                    assertEquals("quick cached opens must never show placeholders", 0,
                        HistoryActivity.skeletonShowsForTest(activity));
                    assertTrue("automatic opens never own manual refresh feedback",
                        !HistoryActivity.refreshRunningForTest(activity));
                });
                assertNotNull(findByText(ctx.getString(R.string.history_breakdown)));
                assertNull(findByDescription(ctx.getString(R.string.history_loading)));
                assertNull(findByDescription(ctx.getString(R.string.history_refreshing)));
                assertTrue(shimmerBars().isEmpty());
                scenario.close();
                scenario = null;
                deadlines.clear();
            }
        } finally {
            HistoryActivity.skeletonSchedulerForTest = null;
        }
    }

    @Test public void initialEmptySnapshot_keepsColdProgressUntilCatchupScanFinishes()
            throws Exception {
        BalanceData.reset(ctx, true);
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch scanRelease = new CountDownLatch(1);
        List<Runnable> deadlines = new ArrayList<>();
        HistoryActivity.skeletonSchedulerForTest = deadlines::add;
        HistoryActivity.scanHookForTest = () -> gate(scanStarted, scanRelease);
        try {
            openHistory();
            assertTrue(scanStarted.await(10, TimeUnit.SECONDS));
            await(() -> {
                final boolean[] idle = {false};
                scenario.onActivity(activity -> idle[0] = HistoryActivity.renderIdleForTest(activity));
                return idle[0];
            }, 10_000, "the initial saved-empty read to finish");
            scenario.onActivity(activity -> {
                assertTrue(!deadlines.isEmpty());
                deadlines.get(deadlines.size() - 1).run();
            });
            assertNotNull("an in-flight initial import must retain truthful loading feedback",
                findByDescription(ctx.getString(R.string.history_loading)));
            assertNull("unscanned emptiness is not a finished empty account",
                findByText(ctx.getString(R.string.history_empty_account)));
            assertNull(findByDescription(ctx.getString(R.string.history_refreshing)));

            storeEnoughToBeSlowToRead();
            scanRelease.countDown();
            await(this::idle, 60_000, "catch-up and its final render");
            assertNotNull(findByText(ctx.getString(R.string.history_breakdown)));
            assertNull(findByDescription(ctx.getString(R.string.history_loading)));
        } finally {
            HistoryActivity.skeletonSchedulerForTest = null;
            HistoryActivity.scanHookForTest = null;
            scanRelease.countDown();
        }
    }

    @Test public void coldPlaceholderDeadlineIsCanceledWhenFirstReadFails() {
        storeEnoughToBeSlowToRead();
        List<Runnable> deadlines = new ArrayList<>();
        HistoryActivity.skeletonSchedulerForTest = deadlines::add;
        HistoryActivity.summaryReadHookForTest = () -> {
            throw new IllegalStateException("synthetic history read failure");
        };
        try {
            openHistory();
            await(this::idle, 60_000, "the failed read and catch-up to settle");
            assertNotNull("a failed first read must not claim that history is empty",
                findByText(ctx.getString(R.string.history_load_failed)));
            scenario.onActivity(activity -> {
                for (Runnable deadline : new ArrayList<>(deadlines)) deadline.run();
                assertEquals("a canceled loading deadline must not replace the error", 0,
                    HistoryActivity.skeletonShowsForTest(activity));
            });
            assertNull(findByDescription(ctx.getString(R.string.history_loading)));
            assertNull(findByDescription(ctx.getString(R.string.history_refreshing)));
        } finally {
            HistoryActivity.skeletonSchedulerForTest = null;
            HistoryActivity.summaryReadHookForTest = null;
        }
    }

    @Test public void onceLoaded_thePlaceholdersAreGone() {
        storeEnoughToBeSlowToRead();
        openHistory();

        // The breakdown heading only exists once a render has produced real history. Waiting on a
        // control that is simply always on screen (the export action, say) would return before the
        // read finished and then report the placeholders as leftovers.
        await(() -> findByText(ctx.getString(R.string.history_breakdown)) != null, 60_000,
            "the history to finish loading");
        assertNull("the loading state must not outlive the load",
            findByDescription(ctx.getString(R.string.history_loading)));
        assertTrue("the placeholders must not outlive the load", shimmerBars().isEmpty());
    }

    @Test public void unchangedResume_keepsHistoryTreeAndScrollWithoutAnotherFullRead()
            throws Exception {
        storeEnoughToBeSlowToRead();
        openHistory();
        await(this::idle, 60_000, "the initial scan and render to settle");
        View heading = findByText(ctx.getString(R.string.history_breakdown));
        assertNotNull(heading);
        final int[] offset = {0};
        scenario.onActivity(activity -> {
            ScrollView scroll = findScroll(activity.getWindow().getDecorView());
            scroll.scrollTo(0, 100);
            offset[0] = scroll.getScrollY();
        });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HistoryActivity.setRenderGateForTest(started, release);
        try {
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            await(this::idle, 30_000, "unchanged resume validation");
            assertEquals("unchanged resume must not decrypt/group the history again", 1L,
                started.getCount());
            assertSame("unchanged resume must retain the actual history tree", heading,
                findByText(ctx.getString(R.string.history_breakdown)));
            scenario.onActivity(activity -> assertEquals("scroll survives unchanged resume",
                offset[0], findScroll(activity.getWindow().getDecorView()).getScrollY()));
            assertNull(findByDescription(ctx.getString(R.string.history_refreshing)));
        } finally {
            HistoryActivity.setRenderGateForTest(null, null);
            release.countDown();
        }
    }

    @Test public void metadataChangedWhilePaused_keepsSavedContentDuringQuietReload()
            throws Exception {
        storeEnoughToBeSlowToRead();
        openHistory();
        await(this::idle, 60_000, "the initial scan and render to settle");
        View heading = findByText(ctx.getString(R.string.history_breakdown));
        assertNotNull(heading);
        scenario.moveToState(Lifecycle.State.CREATED);
        BalanceData.setNote(ctx, firstTransaction, "saved note after pause");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HistoryActivity.setRenderGateForTest(started, release);
        try {
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue("metadata revision must trigger a fresh read", started.await(10,
                TimeUnit.SECONDS));
            assertSame("saved history stays usable during the read", heading,
                findByText(ctx.getString(R.string.history_breakdown)));
            assertNull("saved history must not revert to placeholders",
                findByDescription(ctx.getString(R.string.history_loading)));
            assertNull("saved-content resume must not show an automatic spinner",
                findByDescription(ctx.getString(R.string.history_refreshing)));
            release.countDown();
            await(() -> findByText("saved note after pause") != null, 60_000,
                "the changed metadata to appear");
        } finally {
            HistoryActivity.setRenderGateForTest(null, null);
            release.countDown();
        }
    }

    private boolean idle() {
        final boolean[] idle = {false};
        scenario.onActivity(activity -> idle[0] = HistoryActivity.idleForTest(activity));
        return idle[0];
    }

    @Test public void manualPullDuringPreviousScanValidation_keepsFeedbackUntilItsOwnValidation()
            throws Exception {
        storeEnoughToBeSlowToRead();
        openHistory();
        await(this::idle, 60_000, "the initial scan and render to settle");
        CountDownLatch oldRenderStarted = new CountDownLatch(1);
        CountDownLatch oldRenderRelease = new CountDownLatch(1);
        CountDownLatch manualScanStarted = new CountDownLatch(1);
        CountDownLatch manualScanRelease = new CountDownLatch(1);
        CountDownLatch manualRenderStarted = new CountDownLatch(1);
        CountDownLatch manualRenderRelease = new CountDownLatch(1);
        AtomicInteger resumeValidations = new AtomicInteger();
        HistoryActivity.renderCompletionHookForTest = () -> {
            // Resume validates once immediately and once after its automatic scan completes.
            if (resumeValidations.incrementAndGet() == 2)
                gate(oldRenderStarted, oldRenderRelease);
        };
        try {
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue("the automatic scan's validation must reach its completion gate",
                oldRenderStarted.await(10, TimeUnit.SECONDS));
            HistoryActivity.scanHookForTest = () -> gate(manualScanStarted, manualScanRelease);
            scenario.onActivity(activity -> pull(findScroll(activity.getWindow().getDecorView())));
            assertTrue("the manual pull must start its own scan",
                manualScanStarted.await(10, TimeUnit.SECONDS));

            oldRenderRelease.countDown();
            await(() -> {
                final boolean[] idle = {false};
                scenario.onActivity(activity -> idle[0] = HistoryActivity.renderIdleForTest(activity));
                return idle[0];
            }, 10_000, "the previous scan's validation to finish");
            assertManualRefreshActive();

            HistoryActivity.renderCompletionHookForTest =
                () -> gate(manualRenderStarted, manualRenderRelease);
            manualScanRelease.countDown();
            assertTrue("the manual scan must reach its own validation",
                manualRenderStarted.await(10, TimeUnit.SECONDS));
            assertManualRefreshActive();
            manualRenderRelease.countDown();
            await(this::idle, 30_000, "the manual scan and validation to finish");
            await(() -> findByDescription(ctx.getString(R.string.history_refreshing)) == null,
                10_000, "the manual refresh indicator to settle");
        } finally {
            HistoryActivity.scanHookForTest = null;
            HistoryActivity.renderCompletionHookForTest = null;
            oldRenderRelease.countDown();
            manualScanRelease.countDown();
            manualRenderRelease.countDown();
        }
    }

    private void assertManualRefreshActive() {
        scenario.onActivity(activity -> {
            assertTrue("an older render must not settle the new manual refresh",
                HistoryActivity.refreshRunningForTest(activity));
            assertEquals("manual feedback stays visible until its own validation finishes",
                ctx.getString(R.string.history_refreshing),
                findScroll(activity.getWindow().getDecorView()).getContentDescription());
        });
    }

    private static void gate(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            if (!release.await(60, TimeUnit.SECONDS))
                throw new AssertionError("refresh regression gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void pull(ScrollView scroll) {
        scroll.scrollTo(0, 0);
        float start = 1f;
        float end = start + 130 * scroll.getResources().getDisplayMetrics().density;
        assertTrue("the list must have enough room for a real pull", end < scroll.getHeight());
        float x = scroll.getWidth() / 2f;
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 15; step++) {
            int action = step == 0 ? MotionEvent.ACTION_DOWN
                : step == 15 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, down + step * 16L, action,
                x, start + (end - start) * step / 15f, 0);
            scroll.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void openHistory() {
        openHistory(MELLAT, ACCOUNT);
    }

    private void openHistory(String bank, String account) {
        Intent i = new Intent(ctx, HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (bank != null) i.putExtra(HistoryActivity.EXTRA_BANK, bank);
        if (account != null) i.putExtra(HistoryActivity.EXTRA_ACCOUNT, account);
        scenario = androidx.test.core.app.ActivityScenario.launch(i);
    }

    private static boolean allLaidOut(List<View> bars) {
        for (View v : bars) if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        return true;
    }

    /** Every view on screen still wearing a placeholder surface. */
    private static List<View> shimmerBars() {
        HistoryActivity a = opened();
        if (a == null) return new ArrayList<>();
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
            () -> collectShimmer(a.getWindow().getDecorView(), hits));
        return hits;
    }

    private static void collectShimmer(View v, List<View> out) {
        if (v.getBackground() instanceof HistoryActivity.ShimmerDrawable) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectShimmer(g.getChildAt(i), out);
        }
    }

    private static void finishAnyResumedHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) a.finish();
            }
        });
    }

    /**
     * The screen this test opened, and only that one.
     *
     * <p>An activity closed by a previous test can still be in the resumed stage for a moment, and a
     * search across every resumed screen would then find the old one's placeholders and report that
     * the loading state outlived the load.
     */
    private static HistoryActivity opened() {
        final List<Activity> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) out.add(a);
            }
        });
        return out.isEmpty() ? null : (HistoryActivity) out.get(out.size() - 1);
    }

    private static View findByDescription(String want) {
        // Resolve the activity first: runOnMainSync blocks the caller while the runnable is
        // pending, so asking for it from inside another runOnMainSync would wait on the main
        // thread from the main thread and never come back.
        HistoryActivity a = opened();
        if (a == null) return null;
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation()
            .runOnMainSync(() -> collect(a.getWindow().getDecorView(), want, hits));
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static View findByText(String want) {
        HistoryActivity a = opened();
        if (a == null) return null;
        final List<View> hits = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation()
            .runOnMainSync(() -> collectText(a.getWindow().getDecorView(), want, hits));
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static void collectText(View v, String want, List<View> out) {
        if (v instanceof TextView && want.contentEquals(((TextView) v).getText())) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), want, out);
        }
    }

    private static void collect(View v, String want, List<View> out) {
        if (want.equals(v.getContentDescription())) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), want, out);
        }
    }

    private static void await(Callable<Boolean> done, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (done.call()) return; } catch (Exception ignored) { }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
