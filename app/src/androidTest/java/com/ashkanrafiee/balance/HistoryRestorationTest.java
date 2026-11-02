package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;

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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Saved history state must survive recreation, including a delayed first content layout. */
@RunWith(AndroidJUnit4.class)
public class HistoryRestorationTest {
    private static final String BANK = "Mellat";
    private static final String ACCOUNT = "99900012345";
    private static final long TIMEOUT_MS = 60_000L;

    private Context context;
    private SharedPreferences prefs;
    private ActivityScenario<HistoryActivity> scenario;
    private CountDownLatch renderRelease;
    private int originalRegion;
    private Set<String> originalExcluded;
    private LocaleList originalLocales;
    private boolean hadExpand, originalExpand, hadMigration, originalMigration;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
        originalRegion = RegionHelper.region(context);
        originalExcluded = BalanceData.getExcluded(context);
        originalLocales = LocaleList.getDefault();
        hadExpand = prefs.contains(BalanceData.KEY_EXPAND_ALL_HISTORY);
        originalExpand = BalanceData.getExpandAllHistory(context);
        hadMigration = prefs.contains(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED);
        originalMigration = prefs.getBoolean(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, false);
        BalanceData.reset(context, true);
        RegionHelper.setRegion(context, RegionHelper.REGION_INTERNATIONAL);
        assertTrue(prefs.edit()
            .putBoolean(BalanceData.KEY_EXPAND_ALL_HISTORY, false)
            .putBoolean(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, true).commit());
        HistoryWarmup.invalidate();
    }

    @After public void tearDown() {
        // Release before draining workers: neither a test failure nor cleanup may strand a gate.
        if (renderRelease != null) renderRelease.countDown();
        HistoryActivity.setRenderGateForTest(null, null);
        HistoryActivity.skeletonSchedulerForTest = null;
        try {
            if (scenario != null) {
                try {
                    await(HistoryActivity::idleForTest,
                        "released history workers to drain before fixture cleanup");
                } finally {
                    scenario.close();
                }
            }
        } finally {
            try {
                BalanceData.reset(context, true);
            } finally {
                HistoryWarmup.invalidate();
                RegionHelper.setRegion(context, originalRegion);
                BalanceData.setExcluded(context, originalExcluded);
                SharedPreferences.Editor restore = prefs.edit();
                if (hadExpand) restore.putBoolean(
                    BalanceData.KEY_EXPAND_ALL_HISTORY, originalExpand);
                else restore.remove(BalanceData.KEY_EXPAND_ALL_HISTORY);
                if (hadMigration) restore.putBoolean(
                    BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED, originalMigration);
                else restore.remove(BalanceData.KEY_EXPAND_ALL_DEBUG_MIGRATED);
                restore.commit();
                LocaleList.setDefault(originalLocales);
            }
        }
    }

    @Test public void gregorianCustomBounds_surviveRecreationWithoutWideningHistory() {
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(transaction(2026, 7, 30, 1_000L, "before"));
        transactions.add(transaction(2026, 7, 31, 2_000L, "from"));
        transactions.add(transaction(2026, 8, 31, 3_000L, "to"));
        transactions.add(transaction(2026, 9, 1, 4_000L, "after"));
        assertTrue(BalanceData.writeTransactions(context, transactions));
        openHistory();
        await(HistoryActivity::idleForTest, "the initial history scan and render");

        // Both dates are valid Gregorian days but invalid Jalali days in these month numbers.
        // Set the same filter the range picker applies without depending on localized dialog text.
        scenario.onActivity(activity -> applyFilter(activity, new HistoryActivity.Filter(
            HistoryActivity.DIR_DEPOSIT, HistoryActivity.RANGE_CUSTOM,
            CalDate.of(2026, 7, 31), CalDate.of(2026, 8, 31))));
        await(activity -> HistoryActivity.idleForTest(activity)
                && result(activity) != null && result(activity).movementCount == 2,
            "the selected custom range to render its two boundary movements");
        scenario.onActivity(HistoryRestorationTest::assertCustomRange);

        scenario.recreate();
        await(HistoryActivity::idleForTest, "the recreated custom-range history");
        scenario.onActivity(HistoryRestorationTest::assertCustomRange);
    }

    @Test public void delayedColdRender_restoresScrollAfterRecreation() throws Exception {
        int savedOffset = recreateWithDelayedColdRender();
        finishColdRender(savedOffset);
        assertScrollOffset(savedOffset);
    }

    @Test public void pausedColdRender_restoresScrollAfterResume() throws Exception {
        int savedOffset = recreateWithDelayedColdRender();
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        finishColdRender(savedOffset);
        assertScrollOffset(savedOffset);
    }

    @Test public void secondRecreationBeforeColdRender_keepsOriginalScrollOffset() throws Exception {
        int savedOffset = recreateWithDelayedColdRender();
        // Saving the empty intermediate screen must not replace the outstanding saved offset by 0.
        scenario.recreate();
        awaitLayoutFrame();
        finishColdRender(savedOffset);
        assertScrollOffset(savedOffset);
    }

    @Test public void newGestureDuringColdRender_cancelsOldScrollOffset() throws Exception {
        int savedOffset = recreateWithDelayedColdRender();
        scenario.onActivity(activity -> {
            ScrollView scroll = scroll(activity);
            long down = SystemClock.uptimeMillis();
            MotionEvent start = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 10, 10, 0);
            MotionEvent cancel = MotionEvent.obtain(down, down + 16,
                MotionEvent.ACTION_CANCEL, 10, 10, 0);
            try {
                scroll.dispatchTouchEvent(start);
                scroll.dispatchTouchEvent(cancel);
            } finally {
                start.recycle();
                cancel.recycle();
            }
        });
        finishColdRender(savedOffset);
        assertScrollOffset(0);
    }

    @Test public void changedFilterDuringColdRender_cancelsOldScrollOffset() throws Exception {
        int savedOffset = recreateWithDelayedColdRender();
        // The fixture contains deposits only: this changes user intent without shrinking the
        // list enough to make the old offset unrepresentable and accidentally hide a regression.
        scenario.onActivity(activity -> applyFilter(activity, new HistoryActivity.Filter(
            HistoryActivity.DIR_DEPOSIT, HistoryActivity.RANGE_ALL, null, null)));
        finishColdRender(savedOffset);
        assertScrollOffset(0);
        scenario.onActivity(activity -> assertEquals(64L, result(activity).movementCount));
    }

    private int recreateWithDelayedColdRender() throws Exception {
        seedScrollableToday();
        openHistory();
        await(activity -> HistoryActivity.idleForTest(activity)
                && laidOut(scroll(activity)) && scrollRange(scroll(activity)) > 0,
            "the initial history render and its scrollable layout");

        int[] savedOffset = {0};
        HistoryActivity[] originalActivity = {null};
        scenario.onActivity(activity -> {
            originalActivity[0] = activity;
            ScrollView scroll = scroll(activity);
            scroll.scrollTo(0, scrollRange(scroll) * 3 / 4);
            savedOffset[0] = scroll.getScrollY();
            assertTrue("fixture must have a meaningful nonzero scroll offset",
                savedOffset[0] > scroll.getHeight());
        });

        // The old screen is idle. Only the recreated worker waits, outside the store monitor.
        HistoryWarmup.invalidate();
        CountDownLatch renderStarted = new CountDownLatch(1);
        renderRelease = new CountDownLatch(1);
        HistoryActivity.setRenderGateForTest(renderStarted, renderRelease);
        // Hold the placeholder deadline too, making the empty-body grace period deterministic
        // rather than requiring the emulator to finish its first frame within 200 ms.
        HistoryActivity.skeletonSchedulerForTest = deadline -> { };
        scenario.recreate();
        assertTrue("the recreated history worker must reach its cold-render gate",
            renderStarted.await(10, TimeUnit.SECONDS));
        scenario.onActivity(activity -> assertTrue("recreation must replace the activity",
            activity != originalActivity[0]));
        awaitLayoutFrame();
        return savedOffset[0];
    }

    private void finishColdRender(int savedOffset) throws Exception {
        renderRelease.countDown();
        await(activity -> HistoryActivity.idleForTest(activity)
                && laidOut(scroll(activity)) && scrollRange(scroll(activity)) >= savedOffset,
            "the recreated history content and completed layout");
        awaitLayoutFrame();
        // Allow a restoration posted by the layout callback to run before inspecting its offset.
        CountDownLatch restored = new CountDownLatch(1);
        scenario.onActivity(activity -> scroll(activity).post(restored::countDown));
        assertTrue("post-layout restoration must complete", restored.await(10, TimeUnit.SECONDS));
    }

    private void assertScrollOffset(int expected) {
        scenario.onActivity(activity -> assertEquals(
            "the accepted content layout must respect the current scroll owner",
            expected, scroll(activity).getScrollY()));
    }

    private void openHistory() {
        Intent intent = new Intent(context, HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, BANK)
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, ACCOUNT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = ActivityScenario.launch(intent);
    }

    private static void assertCustomRange(HistoryActivity activity) {
        HistoryActivity.Filter filter = field(activity, "filter", HistoryActivity.Filter.class);
        assertEquals(HistoryActivity.DIR_DEPOSIT, filter.direction);
        assertEquals(HistoryActivity.RANGE_CUSTOM, filter.rangePreset);
        assertNotNull("Gregorian July 31 lower boundary must survive recreation", filter.from);
        assertNotNull("Gregorian August 31 upper boundary must survive recreation", filter.to);
        assertEquals("2026/7/31", filter.from.key());
        assertEquals("2026/8/31", filter.to.key());
        HistoryReader.Result result = result(activity);
        assertNotNull("the restored range must have a rendered history result", result);
        assertEquals("out-of-range movements must remain excluded", 2L, result.movementCount);
        assertEquals(5_000L, result.total);
    }

    private static void applyFilter(HistoryActivity activity, HistoryActivity.Filter filter) {
        try {
            Field field = HistoryActivity.class.getDeclaredField("filter");
            field.setAccessible(true);
            field.set(activity, filter);
            Method render = HistoryActivity.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not apply the history range", error);
        }
    }

    private static HistoryReader.Result result(HistoryActivity activity) {
        return field(activity, "historyResult", HistoryReader.Result.class);
    }

    private static <T> T field(HistoryActivity activity, String name, Class<T> type) {
        try {
            Field field = HistoryActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not read history state: " + name, error);
        }
    }

    private void seedScrollableToday() {
        Calendar noon = Calendar.getInstance();
        noon.set(Calendar.HOUR_OF_DAY, 12);
        noon.set(Calendar.MINUTE, 0);
        noon.set(Calendar.SECOND, 0);
        noon.set(Calendar.MILLISECOND, 0);
        List<Transaction> transactions = new ArrayList<>();
        // Same civil day around month/midnight boundaries, fewer than one 128-row page.
        for (int i = 0; i < 64; i++) {
            transactions.add(new Transaction(BANK, ACCOUNT,
                noon.getTimeInMillis() - i * 1_000L, 100_000L + i,
                "restoration-scroll-" + i, null));
        }
        assertTrue(BalanceData.writeTransactions(context, transactions));
    }

    private static Transaction transaction(int year, int month, int day, long amount, String id) {
        Calendar date = Calendar.getInstance();
        date.clear();
        date.set(year, month - 1, day, 12, 0, 0);
        return new Transaction(BANK, ACCOUNT, date.getTimeInMillis(), amount,
            "restoration-range-" + id, null);
    }

    private void awaitLayoutFrame() throws InterruptedException {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            ScrollView scroll = scroll(activity);
            scroll.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        frame.countDown();
                        return true;
                    }
                });
            scroll.requestLayout();
            scroll.invalidate();
        });
        // All waits run on the instrumentation thread, never on the UI thread.
        assertTrue("history must complete its requested layout frame",
            frame.await(10, TimeUnit.SECONDS));
    }

    private static ScrollView scroll(HistoryActivity activity) {
        ScrollView scroll = findScroll(activity.getWindow().getDecorView());
        assertNotNull("history must expose its production scroll container", scroll);
        return scroll;
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

    private static boolean laidOut(ScrollView scroll) {
        return scroll.isLaidOut() && !scroll.isLayoutRequested() && scroll.getHeight() > 0
            && scroll.getChildCount() == 1 && scroll.getChildAt(0).isLaidOut()
            && !scroll.getChildAt(0).isLayoutRequested();
    }

    private static int scrollRange(ScrollView scroll) {
        if (scroll.getChildCount() == 0) return 0;
        return Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()
            + scroll.getPaddingTop() + scroll.getPaddingBottom());
    }

    private void await(Predicate<HistoryActivity> condition, String what) {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for " + what);
    }
}
