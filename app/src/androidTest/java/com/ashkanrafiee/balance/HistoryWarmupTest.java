package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reusable encrypted snapshots, exact revision validation, and independent caller ownership. */
@RunWith(AndroidJUnit4.class)
public class HistoryWarmupTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        HistoryWarmup.invalidate();
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        HistoryWarmup.invalidate();
        BalanceData.reset(context, true);
    }

    @Test public void repeatedOpensAndWarmCalls_reuseWithoutRebuilding() throws Exception {
        seed();
        assertNull(takeDefault());
        HistoryWarmup.warmNow(context);
        HistoryReader.Result first = takeDefault();
        assertNotNull(first);
        HistoryReader.RevisionStamp stamp = first.revisionStamp;
        Set<String> files = timelineFiles();
        first.close();
        first.close();

        HistoryWarmup.warmNow(context, new WorkProgress() {
            @Override public void stage(int stage) {
                throw new AssertionError("unchanged cache must not build another timeline");
            }
            @Override public void progress(long done, long total) {}
        });
        HistoryReader.Result second = takeDefault();
        assertNotNull("closing a caller must not consume the cache", second);
        try {
            assertNotSame(first, second);
            assertSame("same built snapshot, not merely equal totals", stamp, second.revisionStamp);
            assertEquals(2L, second.movementCount);
            assertEquals(files, timelineFiles());
        } finally {
            second.close();
        }
    }

    @Test public void independentHandles_surviveCacheInvalidationAndOtherHandleClose() throws Exception {
        seed();
        Set<String> before = timelineFiles();
        HistoryWarmup.warmNow(context);
        Set<String> retained = timelineFiles();
        HistoryReader.Result first = takeDefault();
        HistoryReader.Result second = takeDefault();
        assertNotNull(first);
        assertNotNull(second);
        try {
            HistoryWarmup.invalidate();
            assertNull(takeDefault());
            first.close();
            first.close();
            assertEquals(retained, timelineFiles());
            HistoryReader.DayRows page = HistoryReader.readPage(context, second, dayKey(),
                null, false, 8);
            assertEquals(2, page.transactions.size());
        } finally {
            first.close();
            second.close();
        }
        assertEquals("last owner deletes the spool and wrapped key", before, timelineFiles());
    }

    @Test public void metadataMismatch_discardsAndRearmsCache() throws Exception {
        seed();
        HistoryWarmup.warmNow(context);
        HistoryReader.Result first = takeDefault();
        assertNotNull(first);
        HistoryReader.RevisionStamp old = first.revisionStamp;
        first.close();
        BalanceData.setNote(context, tx(1L), "edited");
        assertNull("a metadata write must invalidate the warm-up", takeDefault());
        HistoryWarmup.warmNow(context);
        HistoryReader.Result fresh = takeDefault();
        assertNotNull("a stale miss must not leave warming disabled", fresh);
        try {
            assertFalse(old.equals(fresh.revisionStamp));
            assertEquals(MetadataStore.revision(context), fresh.revisionStamp.metadata);
        } finally {
            fresh.close();
        }
    }

    @Test public void orphanSweep_preservesAnOlderActiveSnapshot() throws Exception {
        seed();
        Set<String> previous = timelineFiles();
        HistoryWarmup.warmNow(context);
        Set<String> oldFiles = timelineFiles();
        oldFiles.removeAll(previous);
        assertFalse(oldFiles.isEmpty());
        HistoryReader.Result active = takeDefault();
        assertNotNull(active);
        try {
            HistoryWarmup.invalidate();
            long yesterday = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000;
            for (String name : oldFiles) {
                assertTrue(new File(context.getNoBackupFilesDir(), name).setLastModified(yesterday));
            }
            HistoryWarmup.warmNow(context);
            assertTrue(timelineFiles().containsAll(oldFiles));
            assertEquals(2, HistoryReader.readPage(context, active, dayKey(), null, false, 8)
                .transactions.size());
        } finally {
            active.close();
        }
        assertTrue(Collections.disjoint(oldFiles, timelineFiles()));
    }

    @Test public void transactionMismatch_neverReturnsOldSummary() throws Exception {
        seed();
        HistoryWarmup.warmNow(context);
        assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(tx(9L))));
        assertNull(takeDefault());
        HistoryWarmup.warmNow(context);
        HistoryReader.Result fresh = takeDefault();
        assertNotNull(fresh);
        try {
            assertEquals(1L, fresh.movementCount);
            assertEquals(9L, fresh.total);
        } finally {
            fresh.close();
        }
    }

    @Test public void scopeFilterAndCalendarMisses_preserveValidDefaultEntry() throws Exception {
        seed();
        HistoryWarmup.warmNow(context);
        boolean iran = RegionHelper.isIran(context);
        assertNull(HistoryWarmup.take("Other", (String) null, HistoryActivity.Filter.ALL,
            "", Collections.emptyList(), iran, context));
        assertNull(HistoryWarmup.take((String) null, "222", HistoryActivity.Filter.ALL,
            "", Collections.emptyList(), iran, context));
        assertNull(HistoryWarmup.take((String) null, (String) null, HistoryActivity.Filter.ALL,
            "search", Collections.emptyList(), iran, context));
        assertNull(HistoryWarmup.take((String) null, (String) null, HistoryActivity.Filter.ALL,
            "", Collections.singletonList("tag"), iran, context));
        HistoryActivity.Filter deposits = new HistoryActivity.Filter(HistoryActivity.DIR_DEPOSIT,
            HistoryActivity.RANGE_ALL, null, null);
        assertNull(HistoryWarmup.take((String) null, (String) null, deposits,
            "", Collections.emptyList(), iran, context));
        assertNull(HistoryWarmup.take((String) null, (String) null, HistoryActivity.Filter.ALL,
            "", Collections.emptyList(), !iran, context));
        HistoryReader.Result stillDefault = takeDefault();
        assertNotNull("a different request must not consume the valid default entry", stillDefault);
        stillDefault.close();
    }

    @Test public void timezoneChange_invalidatesEvenWhenCalendarDateMatches() throws Exception {
        seed();
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
            HistoryWarmup.warmNow(context);
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+00:00"));
            assertNull("timezone identity is part of the snapshot", takeDefault());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test public void invalidationWhileWarming_fencesPublicationAndDeletesUnpublishedSpool()
            throws Exception {
        seed();
        Set<String> before = timelineFiles();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread warming = new Thread(() -> {
            try {
                HistoryWarmup.warmNow(context, new WorkProgress() {
                    @Override public void stage(int stage) {
                        if (stage != R.string.history_stage_loading) return;
                        entered.countDown();
                        await(release);
                    }
                    @Override public void progress(long done, long total) {}
                });
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "test-history-warm");
        warming.start();
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            HistoryWarmup.invalidate();
        } finally {
            release.countDown();
            warming.join(15_000);
        }
        assertFalse("warm job must finish", warming.isAlive());
        assertNull(failure.get());
        assertNull("an invalidated in-flight snapshot must never be installed", takeDefault());
        assertEquals(before, timelineFiles());
        HistoryWarmup.warmNow(context);
        HistoryReader.Result fresh = takeDefault();
        assertNotNull(fresh);
        fresh.close();
    }

    @Test public void snapshotStamp_isCapturedBeforeWaitingWriterCanPublish() throws Exception {
        seed();
        BalanceData.setNote(context, tx(1L), "before");
        String oldRevision = MetadataStore.revision(context);
        CountDownLatch writerStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            writerStarted.countDown();
            try {
                BalanceData.setNote(context, tx(1L), "after");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "test-history-writer");
        HistoryReader.Result snapshot = null;
        try {
            HistoryReader.Request request = HistoryReader.Request.all(RegionHelper.isIran(context))
                .withRequestedDays(Collections.singleton(dayKey()), 8);
            snapshot = HistoryReader.summaryWithResiduals(context, request, new WorkProgress() {
                @Override public void stage(int stage) {
                    if (stage != R.string.history_stage_building) return;
                    writer.start();
                    await(writerStarted);
                }
                @Override public void progress(long done, long total) {}
            });
            writer.join(15_000);
            assertFalse(writer.isAlive());
            assertNull(failure.get());
            assertEquals(oldRevision, snapshot.revisionStamp.metadata);
            assertFalse(oldRevision.equals(MetadataStore.revision(context)));
            assertEquals("before", snapshot.requestedDayRows.get(dayKey()).metadata
                .get(BalanceData.noteKey(tx(1L))).note);
        } finally {
            if (snapshot != null) snapshot.close();
            if (writer.getState() != Thread.State.NEW) writer.join(15_000);
        }
    }

    @Test public void warmRequestedAfterInvalidation_rearmsAnAlreadyQueuedJob() throws Exception {
        seed();
        synchronized (BalanceData.class) {
            HistoryWarmup.warm(context);
            HistoryWarmup.invalidate();
            HistoryWarmup.warm(context);
        }
        HistoryReader.Result warmed = awaitDefault();
        assertNotNull("a new warm request must survive rejection of the older queued epoch", warmed);
        warmed.close();
    }

    @Test public void rewarmFlag_hitsImmediatelyWithoutConsumingEntry() throws Exception {
        seed();
        HistoryWarmup.warmNow(context);
        HistoryReader.Result first = HistoryWarmup.take((String) null, (String) null,
            HistoryActivity.Filter.ALL, "", Collections.emptyList(),
            RegionHelper.isIran(context), context, true);
        HistoryReader.Result second = takeDefault();
        assertNotNull(first);
        assertNotNull("no asynchronous refill should be necessary", second);
        try {
            assertSame(first.revisionStamp, second.revisionStamp);
        } finally {
            first.close();
            second.close();
        }
    }

    @Test public void warmDispatch_doesNotWaitForStoreMonitorOnMainThread() throws Exception {
        seed();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                synchronized (BalanceData.class) {
                    held.countDown();
                    await(release);
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "test-history-store-holder");
        holder.start();
        try {
            assertTrue(held.await(15, TimeUnit.SECONDS));
            long start = SystemClock.elapsedRealtime();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> HistoryWarmup.warm(context));
            assertTrue("warm dispatch must not do store work on main",
                SystemClock.elapsedRealtime() - start < 1_000);
        } finally {
            release.countDown();
            holder.join(15_000);
        }
        assertFalse(holder.isAlive());
        assertNull(failure.get());
        HistoryReader.Result warmed = awaitDefault();
        assertNotNull(warmed);
        warmed.close();
    }

    @Test public void remember_reusesBankAndAccountViewsWithIndependentHandles() throws Exception {
        seedScopes();
        HistoryWarmup.warmNow(context);
        HistoryReader.RevisionStamp bankStamp = rememberScope("Mellat", null);
        HistoryReader.RevisionStamp accountStamp = rememberScope("Mellat", "111");
        Set<String> files = timelineFiles();
        for (int open = 0; open < 3; open++) {
            HistoryReader.Result bank = takeScope("Mellat", null);
            HistoryReader.Result account = takeScope("Mellat", "111");
            assertNotNull(bank);
            assertNotNull(account);
            try {
                assertSame(bankStamp, bank.revisionStamp);
                assertSame(accountStamp, account.revisionStamp);
                assertEquals(2L, bank.movementCount);
                assertEquals(1L, account.movementCount);
                assertTrue("seed pages are not needed for a cache hit", account.requestedDayRows.isEmpty());
                assertEquals(1, HistoryReader.readPage(context, account, dayKey(), null, false, 8)
                    .transactions.size());
                HistoryWarmup.remember(Collections.singleton("Mellat"), Collections.singleton("111"),
                    HistoryActivity.Filter.ALL, "", Collections.emptyList(),
                    RegionHelper.isIran(context), context, account);
            } finally {
                bank.close();
                account.close();
            }
        }
        HistoryReader.Result all = takeDefault();
        assertNotNull("scoped hits must preserve the valid default entry", all);
        all.close();
        assertEquals("repeated opens create no new spool files", files, timelineFiles());
    }

    @Test public void remember_evictsLeastRecentlyUsedOfThreeEntriesAndCleansItsFiles() throws Exception {
        seedScopes();
        rememberScope("Mellat", null);
        Set<String> beforeSecond = timelineFiles();
        rememberScope(null, "111");
        Set<String> secondFiles = timelineFiles();
        secondFiles.removeAll(beforeSecond);
        assertFalse(secondFiles.isEmpty());
        rememberScope("Melli", null);
        assertScopeCount("Mellat", null, 2L); // Makes the first entry newer than the account view.
        rememberScope(null, "444");
        assertNull("fourth view evicts the least recently used, not the first inserted",
            takeScope(null, "111"));
        assertTrue("evicted owner must delete its disposable files",
            Collections.disjoint(secondFiles, timelineFiles()));
        assertScopeCount("Mellat", null, 2L);
        assertScopeCount("Melli", null, 1L);
        assertScopeCount(null, "444", 1L);

        HistoryWarmup.warmNow(context, new WorkProgress() {
            @Override public void stage(int stage) {
                throw new AssertionError("speculation must not rebuild into a full scoped cache");
            }
            @Override public void progress(long done, long total) {}
        });
        assertNull("default speculation must not evict a scoped entry", takeDefault());
        assertScopeCount("Mellat", null, 2L);
        assertScopeCount("Melli", null, 1L);
        assertScopeCount(null, "444", 1L);
    }

    @Test public void invalidate_clearsAllScopedEntriesButKeepsBorrowedTimelineAlive() throws Exception {
        seedScopes();
        Set<String> before = timelineFiles();
        rememberScope("Mellat", "111");
        Set<String> firstFiles = timelineFiles();
        rememberScope("Melli", null);
        rememberScope(null, "444");
        HistoryReader.Result borrowed = takeScope("Mellat", "111");
        assertNotNull(borrowed);
        try {
            HistoryWarmup.invalidate();
            assertNull(takeScope("Mellat", "111"));
            assertNull(takeScope("Melli", null));
            assertNull(takeScope(null, "444"));
            assertEquals(firstFiles, timelineFiles());
            assertEquals(1, HistoryReader.readPage(context, borrowed, dayKey(), null, false, 8)
                .transactions.size());
        } finally {
            borrowed.close();
        }
        assertEquals(before, timelineFiles());
    }

    @Test public void unopenedDefaultWarmup_isEvictedBeforeRecentlyRenderedScopes() throws Exception {
        seedScopes();
        rememberScope("Mellat", null);
        rememberScope("Melli", null);
        HistoryWarmup.warmNow(context);
        rememberScope(null, "444");
        assertNull("speculation is not a recent user access", takeDefault());
        assertScopeCount("Mellat", null, 2L);
        assertScopeCount("Melli", null, 1L);
        assertScopeCount(null, "444", 1L);
    }

    @Test public void invalidate_fencesRememberWaitingForStoreMonitor() throws Exception {
        seedScopes();
        HistoryReader.Result result = readScope("Mellat", "111");
        boolean iran = RegionHelper.isIran(context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread remembering = new Thread(() -> {
            try {
                HistoryWarmup.remember(Collections.singleton("Mellat"), Collections.singleton("111"),
                    HistoryActivity.Filter.ALL, "", Collections.emptyList(),
                    iran, context, result);
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "test-history-remember");
        try {
            synchronized (BalanceData.class) {
                remembering.start();
                long deadline = SystemClock.elapsedRealtime() + 15_000;
                while (remembering.getState() != Thread.State.BLOCKED
                        && remembering.isAlive() && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(Thread.State.BLOCKED, remembering.getState());
                HistoryWarmup.invalidate();
            }
            remembering.join(15_000);
            assertFalse(remembering.isAlive());
            assertNull(failure.get());
            assertNull("invalidation must fence an already-started admission", takeScope("Mellat", "111"));
        } finally {
            remembering.join(15_000);
            result.close();
        }
    }

    @Test public void remember_rejectsStaleSnapshotWithoutConsumingCallerOwnership() throws Exception {
        seedScopes();
        HistoryReader.Result old = readScope("Mellat", "111");
        try {
            BalanceData.setNote(context, tx(1L), "changed after the snapshot");
            HistoryWarmup.remember(Collections.singleton("Mellat"), Collections.singleton("111"),
                HistoryActivity.Filter.ALL, "", Collections.emptyList(),
                RegionHelper.isIran(context), context, old);
            assertNull(takeScope("Mellat", "111"));
            assertEquals(1, HistoryReader.readPage(context, old, dayKey(), null, false, 8)
                .transactions.size());
        } finally {
            old.close();
        }
        rememberScope("Mellat", "111");
        assertScopeCount("Mellat", "111", 1L);
    }

    @Test public void searchedViews_invalidateWhenFormattingSettingsChange() throws Exception {
        seedScopes();
        HistoryWarmup.warmNow(context);
        boolean iran = RegionHelper.isIran(context);
        HistoryReader.Request request = new HistoryReader.Request(iran,
            HistoryReader.DEFAULT_PAGE_SIZE, "Mellat", (String) null,
            HistoryActivity.Filter.ALL, "Mellat", Collections.emptyList(),
            Collections.emptyList(), 0, 8);
        HistoryReader.Result searched = HistoryReader.summaryWithResiduals(context, request);
        try {
            HistoryWarmup.remember(Collections.singleton("Mellat"), null,
                HistoryActivity.Filter.ALL, "Mellat", Collections.emptyList(), iran, context, searched);
        } finally {
            searched.close();
        }
        HistoryReader.Result hit = HistoryWarmup.take("Mellat", (String) null,
            HistoryActivity.Filter.ALL, "Mellat", Collections.emptyList(), iran, context);
        assertNotNull(hit);
        hit.close();
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        assertNull(HistoryWarmup.take("Mellat", (String) null, HistoryActivity.Filter.ALL,
            "Mellat", Collections.emptyList(), iran, context));
        HistoryReader.Result all = takeDefault();
        assertNotNull("unsearched summaries do not depend on amount formatting", all);
        all.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private Set<String> timelineFiles() {
        Set<String> files = new HashSet<>();
        File[] children = context.getNoBackupFilesDir().listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().startsWith("history-timeline-")) files.add(child.getName());
            }
        }
        return files;
    }

    private HistoryReader.Result awaitDefault() throws Exception {
        HistoryReader.Result warmed = null;
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (warmed == null && SystemClock.elapsedRealtime() < deadline) {
            warmed = takeDefault();
            if (warmed == null) Thread.sleep(25);
        }
        return warmed;
    }

    private HistoryReader.Result readScope(String bank, String account) throws Exception {
        HistoryReader.Request request = new HistoryReader.Request(RegionHelper.isIran(context),
            HistoryReader.DEFAULT_PAGE_SIZE, bank, account, HistoryActivity.Filter.ALL, "",
            Collections.emptyList(), Collections.emptyList(), 0, 8);
        return HistoryReader.summaryWithResiduals(context, request);
    }

    private HistoryReader.RevisionStamp rememberScope(String bank, String account) throws Exception {
        HistoryReader.Result result = readScope(bank, account);
        try {
            HistoryWarmup.remember(bank == null ? null : Collections.singleton(bank),
                account == null ? null : Collections.singleton(account), HistoryActivity.Filter.ALL,
                "", Collections.emptyList(), RegionHelper.isIran(context), context, result);
            return result.revisionStamp;
        } finally {
            result.close();
        }
    }

    private HistoryReader.Result takeScope(String bank, String account) {
        return HistoryWarmup.take(bank, account, HistoryActivity.Filter.ALL, "",
            Collections.emptyList(), RegionHelper.isIran(context), context);
    }

    private void assertScopeCount(String bank, String account, long count) throws Exception {
        HistoryReader.Result result = takeScope(bank, account);
        assertNotNull(result);
        try {
            assertEquals(count, result.movementCount);
        } finally {
            result.close();
        }
    }

    private void seedScopes() {
        Transaction first = tx(1L);
        List<Transaction> stored = new ArrayList<>();
        stored.add(first);
        stored.add(new Transaction("Mellat", "222", first.date, 2L, null, "sig-2", "content-2"));
        stored.add(new Transaction("Melli", "333", first.date, 3L, null, "sig-3", "content-3"));
        stored.add(new Transaction("Saderat", "444", first.date, 4L, null, "sig-4", "content-4"));
        assertTrue(BalanceData.writeTransactions(context, stored));
    }

    private void seed() {
        List<Transaction> stored = new ArrayList<>();
        stored.add(tx(1L));
        stored.add(tx(2L));
        assertTrue(BalanceData.writeTransactions(context, stored));
    }

    private Transaction tx(long amount) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0);
        return new Transaction("Mellat", "111", calendar.getTimeInMillis(), amount, null,
            "sig-" + amount, "content-" + amount);
    }

    private String dayKey() {
        return CalDate.fromGregorian(2026, 9, 15, RegionHelper.isIran(context)).key();
    }

    private HistoryReader.Result takeDefault() {
        return HistoryWarmup.take((String) null, (String) null, HistoryActivity.Filter.ALL, "",
            Collections.emptyList(), RegionHelper.isIran(context), context);
    }
}
