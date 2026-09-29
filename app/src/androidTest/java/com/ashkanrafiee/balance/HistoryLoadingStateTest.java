package com.ashkanrafiee.balance;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (activity != null) activity.finish();
        });
        LocaleHelper.setLanguage(ctx, originalTag);
        CurrencyHelper.setCurrency(ctx, originalCurrency);
        BalanceData.reset(ctx, true);
    }

    /** A real, nonempty history; loading visibility must not depend on how slow it is to read. */
    private void storeHistory() throws Exception {
        long now = System.currentTimeMillis();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            txs.add(new Transaction(MELLAT, ACCOUNT, now - i * HOUR,
                (i % 3 == 0 ? 1 : -1) * (100_000L + i), "sig" + i, null));
        }
        FinancialTestStore.writeTransactions(ctx, txs);
    }

    @Test public void whileItIsStillReading_theScreenShowsPlaceholdersRatherThanBlank() throws Exception {
        storeHistory();
        try (HeldHistoryReads reads = new HeldHistoryReads()) {
            openHistory();
            assertTrue("the history worker must start", reads.started.await(10, TimeUnit.SECONDS));
            awaitLayoutState(true);
            reads.release.countDown();
            // The held task must do the real read/group/render, not just leave a convincing skeleton.
            awaitLayoutState(false);
        }
    }

    @Test public void onceLoaded_thePlaceholdersAreGone() throws Exception {
        storeHistory();
        openHistory();
        awaitLayoutState(false);
    }

    private void openHistory() {
        Intent i = new Intent(ctx, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, MELLAT)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (HistoryActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(i);
    }

    /** Observe attached views after an actual traversal, with every view access on the UI thread. */
    private void awaitLayoutState(boolean loading) throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final View[] root = {null};
        final ViewTreeObserver.OnPreDrawListener[] listener = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            root[0] = activity.getWindow().getDecorView();
            listener[0] = () -> {
                if (root[0].isLayoutRequested()) return true;
                List<View> headings = new ArrayList<>();
                collectText(root[0], ctx.getString(R.string.history_breakdown), headings);
                // The header/export action exists before loading finishes; the breakdown does not.
                if (!loading && headings.isEmpty()) return true;
                root[0].getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                try {
                    List<View> placeholders = new ArrayList<>();
                    collect(root[0], ctx.getString(R.string.history_loading), placeholders);
                    List<View> bars = new ArrayList<>();
                    collectShimmer(root[0], bars);
                    if (loading) {
                        assertTrue("real history must not render before the worker is released",
                            headings.isEmpty());
                        View placeholder = placeholders.isEmpty() ? null : placeholders.get(0);
                        assertNotNull("a blank white page is not a loading state", placeholder);
                        assertTrue("the loading state must be attached and shown",
                            placeholder.isAttachedToWindow() && placeholder.isShown());
                        assertTrue("the placeholders must have something to show", !bars.isEmpty());
                        for (View bar : bars) {
                            assertTrue("each placeholder bar must be attached, shown and laid out",
                                bar.isAttachedToWindow() && bar.isShown() && bar.isLaidOut()
                                    && bar.getWidth() > 0 && bar.getHeight() > 0);
                        }
                    } else {
                        assertTrue("real history must be attached and laid out",
                            headings.get(0).isAttachedToWindow() && headings.get(0).isLaidOut());
                        assertNull("the loading state must not outlive the load",
                            placeholders.isEmpty() ? null : placeholders.get(0));
                        assertTrue("the placeholders must not outlive the load", bars.isEmpty());
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    observed.countDown();
                }
                return true;
            };
            root[0].getViewTreeObserver().addOnPreDrawListener(listener[0]);
            root[0].invalidate();
        });
        try {
            assertTrue("timed out waiting for " + (loading ? "placeholder layout" : "loaded history"),
                observed.await(loading ? 15 : 60, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("history state assertion failed", failure.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (root[0].getViewTreeObserver().isAlive()) {
                    root[0].getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                }
            });
        }
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

    /** Hold dispatched reads off the UI thread, and restore dispatch even when a UI assertion fails.
     * All requests are held because a scan notification can request another render during launch. */
    private static final class HeldHistoryReads implements Executor, AutoCloseable {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private Executor original;

        HeldHistoryReads() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                original = HistoryActivity.historyReadExecutor;
                HistoryActivity.historyReadExecutor = this;
            });
        }

        @Override public void execute(Runnable command) {
            worker.execute(() -> {
                started.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new AssertionError("history read gate was not released");
                    }
                    command.run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    if (t instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            });
        }

        @Override public void close() throws Exception {
            release.countDown();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> HistoryActivity.historyReadExecutor = original);
            worker.shutdown();
            try {
                assertTrue("history reads must finish before cleanup",
                    worker.awaitTermination(60, TimeUnit.SECONDS));
                if (failure.get() != null) throw new AssertionError("history worker failed", failure.get());
            } finally {
                worker.shutdownNow();
            }
        }
    }
}
