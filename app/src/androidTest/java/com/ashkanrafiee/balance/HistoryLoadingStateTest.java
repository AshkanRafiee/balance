package com.ashkanrafiee.balance;

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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private HistoryActivity activity;
    private HoldingRenderExecutor heldRenders;

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
    }

    @After public void tearDown() {
        // Restore dispatch before closing the activity: a scan completion can enqueue one last
        // render while the lifecycle callbacks remove their listener. The held workers are released
        // and joined below, so none can outlive test cleanup.
        if (heldRenders != null) HistoryActivity.historyRenderExecutor = heldRenders.previous;
        try {
            closeOpenedHistory();
        } finally {
            try {
                if (heldRenders != null) heldRenders.close();
            } finally {
                heldRenders = null;
                LocaleHelper.setLanguage(ctx, originalTag);
                CurrencyHelper.setCurrency(ctx, originalCurrency);
                BalanceData.reset(ctx, true);
            }
        }
    }

    /** A small real history; the loading test controls the worker rather than its data size. */
    private void storeHistory() {
        long now = System.currentTimeMillis();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            txs.add(new Transaction(MELLAT, ACCOUNT, now - i * HOUR,
                (i % 3 == 0 ? 1 : -1) * (100_000L + i), "sig" + i, null));
        }
        BalanceData.writeTransactions(ctx, txs);
    }

    @Test public void whileItIsStillReading_theScreenShowsPlaceholdersRatherThanBlank()
            throws Exception {
        storeHistory();
        heldRenders = new HoldingRenderExecutor();
        openHistory();
        assertTrue("the history render worker must start",
            heldRenders.started.await(10, TimeUnit.SECONDS));

        assertNotNull("a blank white page is not a loading state",
            findByDescription(ctx.getString(R.string.history_loading)));

        // Collect the placeholders while they are still the content. They are the only thing wearing
        // a shimmer, so this cannot be satisfied later by the real history taking their place.
        final List<View> bars = shimmerBars();
        assertTrue("the placeholders must have something to show", !bars.isEmpty());
        // Children alone prove nothing: a bare View carrying only a background has no intrinsic size,
        // so a placeholder left to wrap its content lays out at zero and the skeleton is a set of
        // empty cards. The screen has not been measured when the activity starts, so let it have the
        // frames it needs; bars that can never lay out never satisfy this.
        await(() -> allLaidOut(bars), 15_000, "the placeholders to be laid out");

        heldRenders.release();
        await(() -> findByText(ctx.getString(R.string.history_breakdown)) != null, 60_000,
            "the history to finish loading after release");
    }

    @Test public void onceLoaded_thePlaceholdersAreGone() {
        storeHistory();
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

    private void openHistory() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (HistoryActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(i);
    }

    private static boolean allLaidOut(List<View> bars) {
        for (View v : bars) if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        return true;
    }

    /** Every view on screen still wearing a placeholder surface. */
    private List<View> shimmerBars() {
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
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * The screen this test opened, and only that one.
     *
     * <p>An activity closed by a previous test can still be in the resumed stage for a moment, and a
     * search across every resumed screen would then find the old one's placeholders and report that
     * the loading state outlived the load. The launch result is retained so no fallback search is
     * needed.
     */
    private HistoryActivity opened() {
        // Keep all probes tied to the instance this test launched. Looking through every resumed
        // activity lets a screen left by another test become the source of a loading assertion.
        return activity;
    }

    private View findByDescription(String want) {
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

    private View findByText(String want) {
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

    /** Finish the exact screen opened by this test before resetting its data and preferences. */
    private void closeOpenedHistory() {
        if (activity == null) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (!activity.isFinishing()) activity.finish();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        activity = null;
    }

    /** Holds every render worker submitted during launch until the loading state is inspected. */
    private static final class HoldingRenderExecutor implements Executor {
        final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService workers = Executors.newSingleThreadExecutor();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Executor previous;

        HoldingRenderExecutor() {
            previous = HistoryActivity.historyRenderExecutor;
            HistoryActivity.historyRenderExecutor = this;
        }

        @Override public void execute(Runnable command) {
            workers.execute(() -> {
                started.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new AssertionError("history render gate was not released");
                    }
                    command.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        void release() {
            release.countDown();
        }

        void close() {
            release();
            workers.shutdown();
            try {
                assertTrue("history render workers must finish before cleanup",
                    workers.awaitTermination(60, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for history render workers", e);
            } finally {
                workers.shutdownNow();
            }
            Throwable t = failure.get();
            if (t != null) throw new AssertionError("history render worker failed", t);
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
