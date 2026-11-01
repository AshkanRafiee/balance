package com.ashkanrafiee.balance;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Precomputes the default history view while the user is still on the dashboard, so opening
 * history usually finds a ready result instead of paying for a full scan. The cached result is
 * fingerprinted by scope, filters and the revisions of every store it was read from: any write,
 * scan, restore or reset changes a revision and the cache is refused. Each hit gets an independent
 * handle to the immutable result. At most three views are retained, each for ten minutes. An
 * encrypted disposable timeline is deleted when the cache and every active screen release it.
 */
final class HistoryWarmup {
    private static final String TAG = "HistoryWarmup";
    /** Staging files older than this are orphans of a dead process and safe to delete. */
    private static final long STALE_FILE_AGE_MS = 24L * 60 * 60 * 1000;
    private static final Object LOCK = new Object();
    /** Dispatch has its own monitor: cache acquisition/cleanup must never delay warm() on UI. */
    private static final Object DISPATCH_LOCK = new Object();
    private static final long RETENTION_MS = 10L * 60 * 1000;
    private static final int MAX_ENTRIES = 3;
    private static final ScheduledThreadPoolExecutor WORKER = new ScheduledThreadPoolExecutor(2,
        task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "history-warmup"));
    static { WORKER.setRemoveOnCancelPolicy(true); }
    private static boolean warming;
    private static long warmingEpoch;
    private static Context pendingWarm;
    private static volatile long epoch;
    private static final LinkedHashMap<Fingerprint, Entry> CACHE =
        new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true);

    private HistoryWarmup() {}

    /** Dispatch only: never waits for the store monitor or performs disk work on the caller. */
    static void warm(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context application = app;
        final long ticket;
        synchronized (DISPATCH_LOCK) {
            if (warming) {
                if (warmingEpoch != epoch) pendingWarm = application;
                return;
            }
            warming = true;
            ticket = warmingEpoch = epoch;
        }
        WORKER.execute(() -> {
            try {
                warmNow(application, ticket, null);
            } catch (Exception e) {
                Log.w(TAG, "history warm-up failed", e);
            } finally {
                Context again;
                synchronized (DISPATCH_LOCK) {
                    warming = false;
                    again = pendingWarm;
                    pendingWarm = null;
                }
                if (again != null) warm(again);
            }
        });
    }

    /**
     * Borrows a cached result when it describes exactly the requested view read from
     * unchanged stores. The caller owns this handle and must close it. Any mismatch — filters,
     * day rollover or a store write — returns null and the caller runs a fresh pass.
     */
    static HistoryReader.Result take(String bank, String account, HistoryActivity.Filter filter,
            String query, List<String> tags, boolean iran, Context context) {
        return take(bank == null ? null : Collections.singleton(bank),
            account == null ? null : Collections.singleton(account),
            filter, query, tags, iran, context, false);
    }

    /**
     * Same, optionally dispatching a background warm on a miss. Hits are reusable and need no
     * rebuild. The default remains deterministic for tests.
     */
    static HistoryReader.Result take(String bank, String account, HistoryActivity.Filter filter,
            String query, List<String> tags, boolean iran, Context context, boolean rewarm) {
        return take(bank == null ? null : Collections.singleton(bank),
            account == null ? null : Collections.singleton(account),
            filter, query, tags, iran, context, rewarm);
    }

    /**
     * Same, over several banks and accounts at once. Empty collections mean no narrowing on
     * that axis. Tests keep the single-bank default to stay deterministic.
     */
    static HistoryReader.Result take(Collection<String> banks,
            Collection<String> accounts, HistoryActivity.Filter filter,
            String query, List<String> tags, boolean iran, Context context, boolean rewarm) {
        List<HistoryReader.Result> discarded = new ArrayList<>();
        HistoryReader.Result out = null;
        // The store monitor always precedes LOCK; warm() never acquires the store monitor.
        synchronized (BalanceData.class) {
            BalanceData.ensureResetComplete(context);
            HistoryReader.RevisionStamp current = HistoryReader.RevisionStamp.current(context,
                RegionHelper.isIran(context));
            Fingerprint wanted = new Fingerprint(banks, accounts, filter, query, tags, iran, current);
            synchronized (LOCK) {
                if (pruneLocked(current, discarded)) epoch++;
                Entry hit = CACHE.get(wanted);
                if (hit != null) out = hit.result.retain();
            }
        }
        closeAll(discarded);
        if (out == null && rewarm) warm(context);
        return out;
    }

    /**
     * Remembers a completed render-worker read. Retains independent ownership, never consumes the
     * caller's result, and admits only an exact current revision/date/calendar snapshot. Requested
     * day pages are deliberately not part of the key: missing pages can use the retained timeline.
     */
    static void remember(Collection<String> banks, Collection<String> accounts,
            HistoryActivity.Filter filter, String query, List<String> tags,
            boolean iran, Context context, HistoryReader.Result result) {
        if (result == null || result.revisionStamp == null) return;
        long ticket = epoch;
        List<HistoryReader.Result> discarded = new ArrayList<>();
        try {
            synchronized (BalanceData.class) {
                BalanceData.ensureResetComplete(context);
                HistoryReader.RevisionStamp current = HistoryReader.RevisionStamp.current(context,
                    RegionHelper.isIran(context));
                Fingerprint key = new Fingerprint(banks, accounts, filter, query, tags, iran, current);
                if (iran != current.iran || !key.matchesStamp(result.revisionStamp)) return;
                synchronized (LOCK) {
                    if (ticket != epoch) return;
                    pruneLocked(current, discarded);
                    // Re-rendering a hit must not rebuild ownership or extend its fixed TTL.
                    if (CACHE.get(key) == null) putLocked(key, result.retain(), discarded, false);
                }
            }
        } finally {
            closeAll(discarded);
        }
    }

    /** Synchronous warm used by tests; the asynchronous {@link #warm} wraps this. */
    static void warmNow(Context context) throws Exception {
        warmNow(context, null);
    }

    /** Progress is advisory, also allowing deterministic coverage of an in-flight invalidation. */
    static void warmNow(Context context, WorkProgress progress) throws Exception {
        final long ticket;
        synchronized (LOCK) { ticket = epoch; }
        warmNow(context, ticket, progress);
    }

    private static void warmNow(Context context, long ticket, WorkProgress progress) throws Exception {
        HistoryReader.Result result = null;
        List<HistoryReader.Result> discarded = new ArrayList<>();
        try {
            BalanceData.ensureResetComplete(context);
            synchronized (BalanceData.class) {
                boolean iran = RegionHelper.isIran(context);
                HistoryReader.RevisionStamp current = HistoryReader.RevisionStamp.current(context, iran);
                synchronized (LOCK) {
                    if (ticket != epoch) return;
                    pruneLocked(current, discarded);
                    Fingerprint key = new Fingerprint(null, null, HistoryActivity.Filter.ALL, "",
                        Collections.emptyList(), iran, current);
                    // Speculation must not displace a useful scoped view or refresh default LRU.
                    if (CACHE.containsKey(key) || CACHE.size() == MAX_ENTRIES) return;
                }
                CalDate today = CalDate.today(iran);
                Set<String> days = new LinkedHashSet<>();
                for (int day = 1; day <= CalDate.daysInMonth(today.year, today.month, iran); day++) {
                    days.add(CalDate.of(today.year, today.month, day).key());
                }
                HistoryReader.Request request = new HistoryReader.Request(iran,
                    HistoryReader.DEFAULT_PAGE_SIZE, (String) null, (String) null,
                    HistoryActivity.Filter.ALL, "", Collections.emptyList(), days, 0,
                    HistoryReader.DEFAULT_MAX_ROWS_PER_DAY);
                result = HistoryReader.summaryWithResiduals(context, request, progress);
                // The result carries revisions captured inside its own cross-store snapshot.
                // Check date/timezone again: a midnight/configuration change rejects this pass.
                current = HistoryReader.RevisionStamp.current(context, RegionHelper.isIran(context));
                synchronized (LOCK) {
                    if (ticket != epoch || !current.sameData(result.revisionStamp)
                            || !today.key().equals(result.revisionStamp.today)) return;
                    Fingerprint key = new Fingerprint(null, null, HistoryActivity.Filter.ALL, "",
                        Collections.emptyList(), iran, result.revisionStamp);
                    putLocked(key, result, discarded, true);
                    result = null;
                }
            }
        } finally {
            close(result);
            closeAll(discarded);
        }
        sweepStaleFiles(context);
    }

    /** Drops any cached result, e.g. before a test that needs a deterministic cold start. */
    static void invalidate() {
        List<HistoryReader.Result> discarded = new ArrayList<>();
        synchronized (LOCK) {
            epoch++;
            for (Entry entry : CACHE.values()) discardLocked(entry, discarded);
            CACHE.clear();
        }
        closeAll(discarded);
    }

    private static final class Entry {
        final HistoryReader.Result result;
        final long createdAt = android.os.SystemClock.elapsedRealtime();
        ScheduledFuture<?> expiry;

        Entry(HistoryReader.Result result) { this.result = result; }
    }

    /** Returns whether store/calendar staleness (not merely TTL expiry) was observed. */
    private static boolean pruneLocked(HistoryReader.RevisionStamp current,
            List<HistoryReader.Result> discarded) {
        boolean stale = false;
        long now = android.os.SystemClock.elapsedRealtime();
        Iterator<Map.Entry<Fingerprint, Entry>> entries = CACHE.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Fingerprint, Entry> item = entries.next();
            boolean mismatch = !item.getKey().matchesStamp(current);
            if (mismatch || now - item.getValue().createdAt >= RETENTION_MS) {
                stale |= mismatch;
                entries.remove();
                discardLocked(item.getValue(), discarded);
            }
        }
        return stale;
    }

    /** Takes ownership of result; every removed owner is closed outside the cache/store locks. */
    private static void putLocked(Fingerprint key, HistoryReader.Result result,
            List<HistoryReader.Result> discarded, boolean speculative) {
        Entry entry = new Entry(result);
        Entry old = CACHE.remove(key);
        if (old != null) discardLocked(old, discarded);
        if (speculative && !CACHE.isEmpty()) {
            // A view nobody opened yet is the first eviction candidate, not the most recent use.
            Map<Fingerprint, Entry> recent = new LinkedHashMap<>(CACHE);
            CACHE.clear();
            CACHE.put(key, entry);
            CACHE.putAll(recent);
        } else {
            CACHE.put(key, entry);
        }
        if (CACHE.size() > MAX_ENTRIES) {
            Iterator<Entry> entries = CACHE.values().iterator();
            Entry eldest = entries.next();
            entries.remove();
            discardLocked(eldest, discarded);
        }
        entry.expiry = WORKER.schedule(() -> expire(key, entry), RETENTION_MS, TimeUnit.MILLISECONDS);
    }

    private static void discardLocked(Entry entry, List<HistoryReader.Result> discarded) {
        if (entry.expiry != null) entry.expiry.cancel(false);
        discarded.add(entry.result);
    }

    private static void expire(Fingerprint key, Entry expected) {
        boolean removed;
        synchronized (LOCK) {
            // Identity checked without get(): an obsolete timer must not touch newer LRU order.
            removed = CACHE.remove(key, expected);
        }
        if (removed) close(expected.result);
    }

    private static void closeAll(List<HistoryReader.Result> results) {
        for (HistoryReader.Result result : results) close(result);
    }

    private static void close(HistoryReader.Result result) {
        if (result != null) {
            try {
                result.close();
            } catch (Exception e) {
                Log.w(TAG, "warm cache cleanup failed", e);
            }
        }
    }

    private static void sweepStaleFiles(Context context) {
        sweepStaleFiles(context, "history-timeline-");
        sweepStaleFiles(context, "history-residual-");
        sweepStaleFiles(context, "history-csv-");
    }

    private static void sweepStaleFiles(Context context, String prefix) {
        try {
            File directory = context.getNoBackupFilesDir();
            if (directory == null) return;
            File[] files = directory.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File file : files) {
                if (!file.getName().startsWith(prefix)) continue;
                if (HistoryTimeline.isLiveFile(file)) continue;
                if (now - file.lastModified() > STALE_FILE_AGE_MS && file.delete()) continue;
                if (now - file.lastModified() > STALE_FILE_AGE_MS) {
                    Log.w(TAG, "stale file could not be deleted");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "timeline sweep failed", e);
        }
    }

    private static final class Fingerprint {
        final Set<String> banks;
        final Set<String> accounts;
        final int direction;
        final String from;
        final String to;
        final String query;
        final List<String> tags;
        final boolean iran;
        final HistoryReader.RevisionStamp stamp;

        Fingerprint(Collection<String> banks, Collection<String> accounts,
                HistoryActivity.Filter filter, String query,
                List<String> tags, boolean iran, HistoryReader.RevisionStamp stamp) {
            this.banks = banks == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(banks));
            this.accounts = accounts == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(accounts));
            this.direction = filter == null ? 0 : filter.direction;
            this.from = filter == null || filter.from == null ? null : filter.from.key();
            this.to = filter == null || filter.to == null ? null : filter.to.key();
            this.query = query == null ? "" : query.trim();
            this.tags = tags == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tags));
            this.iran = iran;
            this.stamp = stamp;
        }

        boolean matchesStamp(HistoryReader.RevisionStamp other) {
            return query.isEmpty() ? stamp.sameData(other) : stamp.equals(other);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Fingerprint)) return false;
            Fingerprint other = (Fingerprint) o;
            return Objects.equals(banks, other.banks)
                && Objects.equals(accounts, other.accounts)
                && direction == other.direction
                && Objects.equals(from, other.from)
                && Objects.equals(to, other.to)
                && query.equals(other.query)
                && tags.equals(other.tags)
                && iran == other.iran
                && matchesStamp(other.stamp);
        }

        @Override public int hashCode() {
            return Objects.hash(banks, accounts, direction, from, to, query, tags, iran,
                query.isEmpty() ? stamp.dataHashCode() : stamp.hashCode());
        }
    }
}
