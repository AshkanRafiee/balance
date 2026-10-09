package com.ashkanrafiee.balance;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Precomputes the default history view while the user is still on the dashboard, so opening
 * history usually finds a ready result instead of paying for a full scan. The cached result is
 * fingerprinted by scope, filters and the revisions of every store it was read from: any write,
 * scan, restore or reset changes a revision and the cache is refused. Ownership transfers to the
 * first matching caller; everything else runs a fresh pass. Cached timelines live in encrypted
 * disposable files that are deleted when replaced, taken or when the process warms again.
 */
final class HistoryWarmup {
    private static final String TAG = "HistoryWarmup";
    /** Staging files older than this are orphans of a dead process and safe to delete. */
    private static final long STALE_FILE_AGE_MS = 24L * 60 * 60 * 1000;
    private static final Object LOCK = new Object();
    private static boolean warming;
    private static boolean needsWarm = true;
    private static Fingerprint cachedKey;
    private static HistoryReader.Result cached;

    private HistoryWarmup() {}

    /** Starts one background warm of the default history view unless one is running or cached. */
    static void warm(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (warming || !needsWarm) return;
            warming = true;
            needsWarm = false;
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context application = app;
        new Thread(() -> {
            // Speculative work while the user is still on the dashboard: stay out of the
            // way of anything they actually asked for.
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                warmNow(application);
            } catch (Exception e) {
                Log.w(TAG, "history warm-up failed", e);
                synchronized (LOCK) {
                    needsWarm = true;
                }
            } finally {
                synchronized (LOCK) {
                    warming = false;
                }
            }
        }, "history-warmup").start();
    }

    /**
     * Takes the cached result when it describes exactly the requested default view read from
     * unchanged stores. The caller owns the result and must close it. Any mismatch — filters,
     * day rollover or a store write — returns null and the caller runs a fresh pass.
     */
    static HistoryReader.Result take(String bank, String account, HistoryActivity.Filter filter,
            String query, List<String> tags, boolean iran, Context context) {
        return take(bank, account, filter, query, tags, iran, context, false);
    }

    /**
     * Same, optionally re-warming in the background once the cached result is taken. History
     * uses this so a second open right behind the first usually finds a ready result too,
     * instead of waiting for the next dashboard resume to re-warm. Tests keep the default to
     * stay deterministic.
     */
    static HistoryReader.Result take(String bank, String account, HistoryActivity.Filter filter,
            String query, List<String> tags, boolean iran, Context context, boolean rewarm) {
        Fingerprint wanted = new Fingerprint(bank, account, filter, query, tags, iran,
            revisions(context), CalDate.today(iran).key());
        synchronized (LOCK) {
            if (cached == null || !wanted.equals(cachedKey)) return null;
            HistoryReader.Result out = cached;
            cached = null;
            cachedKey = null;
            needsWarm = true;
            if (rewarm) warm(context);
            return out;
        }
    }

    /** Synchronous warm used by tests; the asynchronous {@link #warm} wraps this. */
    static void warmNow(Context context) throws Exception {
        boolean iran = RegionHelper.isIran(context);
        CalDate today = CalDate.today(iran);
        Set<String> requestedDays = new LinkedHashSet<>();
        for (int day = 1; day <= CalDate.daysInMonth(today.year, today.month, iran); day++) {
            requestedDays.add(CalDate.of(today.year, today.month, day).key());
        }
        HistoryReader.Request request = new HistoryReader.Request(iran,
            HistoryReader.DEFAULT_PAGE_SIZE, null, null, HistoryActivity.Filter.ALL, "",
            Collections.emptyList(), requestedDays, 0, HistoryReader.DEFAULT_MAX_ROWS_PER_DAY);
        HistoryReader.Result result = HistoryReader.summaryWithResiduals(context, request);
        boolean installed = false;
        try {
            Fingerprint key = new Fingerprint(null, null, HistoryActivity.Filter.ALL, "",
                Collections.emptyList(), iran, revisions(context), today.key());
            synchronized (LOCK) {
                closeLocked();
                cached = result;
                cachedKey = key;
                installed = true;
            }
        } finally {
            if (!installed) result.close();
        }
        sweepStaleFiles(context);
    }

    /** Drops any cached result, e.g. before a test that needs a deterministic cold start. */
    static void invalidate() {
        synchronized (LOCK) {
            closeLocked();
            needsWarm = true;
        }
    }

    private static void closeLocked() {
        if (cached != null) {
            try {
                cached.close();
            } catch (Exception e) {
                Log.w(TAG, "warm cache cleanup failed", e);
            }
            cached = null;
            cachedKey = null;
        }
    }

    private static String[] revisions(Context context) {
        return new String[]{
            TransactionStore.revision(context),
            MetadataStore.revision(context),
            CommitmentStore.revision(context)};
    }

    private static void sweepStaleFiles(Context context) {
        try {
            File directory = context.getNoBackupFilesDir();
            if (directory == null) return;
            File[] files = directory.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File file : files) {
                if (!file.getName().startsWith("history-timeline-")) continue;
                if (now - file.lastModified() > STALE_FILE_AGE_MS && file.delete()) continue;
                if (now - file.lastModified() > STALE_FILE_AGE_MS) {
                    Log.w(TAG, "stale timeline file could not be deleted");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "timeline sweep failed", e);
        }
    }

    private static final class Fingerprint {
        final String bank;
        final String account;
        final int direction;
        final String from;
        final String to;
        final String query;
        final List<String> tags;
        final boolean iran;
        final String[] revisions;
        final String today;

        Fingerprint(String bank, String account, HistoryActivity.Filter filter, String query,
                List<String> tags, boolean iran, String[] revisions, String today) {
            this.bank = bank;
            this.account = account;
            this.direction = filter == null ? 0 : filter.direction;
            this.from = filter == null || filter.from == null ? null : filter.from.key();
            this.to = filter == null || filter.to == null ? null : filter.to.key();
            this.query = query == null ? "" : query.trim();
            this.tags = tags == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tags));
            this.iran = iran;
            this.revisions = revisions;
            this.today = today;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Fingerprint)) return false;
            Fingerprint other = (Fingerprint) o;
            return Objects.equals(bank, other.bank)
                && Objects.equals(account, other.account)
                && direction == other.direction
                && Objects.equals(from, other.from)
                && Objects.equals(to, other.to)
                && query.equals(other.query)
                && tags.equals(other.tags)
                && iran == other.iran
                && Objects.equals(today, other.today)
                && java.util.Arrays.equals(revisions, other.revisions);
        }

        @Override public int hashCode() {
            return Objects.hash(bank, account, direction, from, to, query, tags, iran, today,
                java.util.Arrays.hashCode(revisions));
        }
    }
}
