package com.ashkanrafiee.balance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Durable, coalesced handoff. This store contains request IDs/cursors only, never definitions. */
final class CommitmentReminderJobs {
    static final int JOB_ID = 0x42414c;
    static final String RETRY = "com.ashkanrafiee.balance.RETRY_COMMITMENT_JOB";
    private static final String PREFS = "commitment_reminder_jobs";
    private static final String TAG = "CommitmentReminderJobs";
    private static final int MAX_PENDING_IDS = 64;
    private static final long ADMISSION_INTERVAL_MS = 30_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final Object LOCK = new Object();
    private static boolean initialized, scheduled, retryPosted;
    private static Token running;

    interface Scheduler {
        boolean pending(Context context);
        boolean schedule(Context context, long delay);
        void cancel(Context context);
    }

    private static final Scheduler NATIVE = new Scheduler() {
        public boolean pending(Context context) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            return jobs != null && jobs.getPendingJob(JOB_ID) != null;
        }
        public boolean schedule(Context context, long delay) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return false;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, CommitmentReminderJobService.class))
                .setPersisted(true).setMinimumLatency(delay)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            return jobs.schedule(job) == JobScheduler.RESULT_SUCCESS;
        }
        public void cancel(Context context) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs != null) jobs.cancel(JOB_ID);
        }
    };
    private static Scheduler scheduler = NATIVE;

    private CommitmentReminderJobs() { }

    static final class Token {
        private volatile boolean stopped;
        void stop() { stopped = true; }
        boolean stopped() { return stopped || Thread.currentThread().isInterrupted(); }
        void check() { if (stopped()) throw new CancellationException("reminder job stopped"); }
    }

    static final class Request {
        final long revision, generation, cursor;
        final boolean plan, deliver;
        final Set<String> ids, wanted;
        Request(SharedPreferences prefs) {
            revision = prefs.getLong("revision", 0);
            generation = prefs.getLong("generation", 0);
            cursor = prefs.getLong("cursor", -1);
            plan = prefs.getBoolean("plan", false);
            deliver = prefs.getBoolean("deliver", false);
            ids = copy(prefs, "ids");
            wanted = copy(prefs, "wanted");
        }
        boolean empty() { return !plan && ids.isEmpty(); }
    }

    static void requestPlan(Context context) { enqueue(context, null); }
    static void requestFire(Context context, String id) {
        if (id != null && !id.isEmpty()) enqueue(context, id);
    }

    private static void enqueue(Context context, String id) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            synchronized (LOCK) {
                SharedPreferences prefs = prefs(app);
                SharedPreferences.Editor edit = prefs.edit()
                    .putLong("revision", prefs.getLong("revision", 0) + 1);
                Set<String> ids = copy(prefs, "ids");
                if (id != null) ids.add(id);
                if (id == null || ids.size() > MAX_PENDING_IDS) {
                    edit.putBoolean("plan", true).putLong("cursor", -1)
                        .putLong("generation", prefs.getLong("generation", 0) + 1)
                        .remove("wanted");
                    if (ids.size() > MAX_PENDING_IDS) {
                        edit.putBoolean("deliver", true);
                        ids.clear();
                    }
                }
                if (!edit.putStringSet("ids", ids).commit()) Log.w(TAG, "request persistence failed");
                ensureScheduled(app);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "reminder job handoff failed", failure);
        }
    }

    /** Called by the explicit retry alarm; never increments request generations. */
    static void retry(Context context) {
        try {
            synchronized (LOCK) {
                retryPosted = false;
                prefs(context).edit().remove("retry_after").apply();
                if (!new Request(prefs(context)).empty()) ensureScheduled(context);
            }
        } catch (RuntimeException failure) { Log.w(TAG, "reminder retry handoff failed", failure); }
    }

    private static void ensureScheduled(Context context) {
        if (running != null) return;
        if (!initialized) {
            scheduled = scheduler.pending(context);
            initialized = true;
        }
        if (scheduled) return;
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        long retryAt = prefs.getLong("retry_after", 0);
        if (retryAt > now && retryAt - now <= RETRY_MS) {
            postRetry(context, retryAt - now);
            return;
        }
        long last = prefs.getLong("last_schedule", 0);
        long delay = last > 0 ? Math.max(1000L, ADMISSION_INTERVAL_MS - Math.max(0L, now - last)) : 1000L;
        boolean accepted = false;
        try { accepted = scheduler.schedule(context, delay); }
        catch (RuntimeException failure) { Log.w(TAG, "native job scheduling failed", failure); }
        if (accepted) {
            scheduled = true;
            prefs.edit().putLong("last_schedule", now).remove("retry_after").commit();
        } else {
            prefs.edit().putLong("retry_after", now + RETRY_MS).commit();
            postRetry(context, RETRY_MS);
        }
    }

    private static void postRetry(Context context, long delay) {
        if (retryPosted || scheduler != NATIVE) return;
        retryPosted = true;
        Context app = context.getApplicationContext();
        final Context application = app == null ? context : app;
        // Some reset callers own the data monitor. Dispatch this small system-service operation
        // after their call returns; no database work or broadcast PendingResult is retained.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AlarmManager alarms = application.getSystemService(AlarmManager.class);
                if (alarms != null) alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + delay, retryIntent(application));
            } catch (RuntimeException failure) { Log.w(TAG, "retry alarm unavailable; resume/boot will retry", failure); }
        });
    }

    private static PendingIntent retryIntent(Context context) {
        return PendingIntent.getBroadcast(context, JOB_ID,
            new Intent(context, CommitmentBootReceiver.class).setAction(RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void started(Token token) {
        synchronized (LOCK) { initialized = scheduled = true; running = token; }
    }

    /** Callback and admission are serialized: a request near jobFinished cannot be swallowed by
     *  the outgoing job's pending status. Native backoff owns retries, not repeated schedule calls. */
    static void finished(Context context, Token token, boolean retry, Runnable finish) {
        synchronized (LOCK) {
            if (running != token || token.stopped()) return;
            boolean pending = !new Request(prefs(context)).empty();
            scheduled = retry || pending;
            running = null;
            finish.run();
        }
    }

    static void stopped(Token token) {
        token.stop();
        synchronized (LOCK) {
            if (running == token) { running = null; scheduled = true; }
        }
    }

    static boolean run(Context context, Token token) {
        try {
            // A persisted native job can survive an interrupted preference commit. Rebuilding from
            // authoritative definitions is safe even when its small request record is absent.
            synchronized (LOCK) {
                if (new Request(prefs(context)).empty()) {
                    SharedPreferences p = prefs(context);
                    p.edit().putBoolean("plan", true).putLong("cursor", -1)
                        .putLong("generation", p.getLong("generation", 0) + 1).commit();
                }
            }
            for (int batch = 0; batch < 32; batch++) {
                token.check();
                Request request;
                synchronized (LOCK) { request = new Request(prefs(context)); }
                if (request.empty()) return false;
                for (String id : request.ids) {
                    token.check();
                    CommitmentReminders.fireNow(context, id, token);
                    synchronized (LOCK) {
                        SharedPreferences p = prefs(context);
                        if (p.getLong("revision", 0) == request.revision) {
                            Set<String> remaining = copy(p, "ids");
                            remaining.remove(id);
                            if (!p.edit().putStringSet("ids", remaining).commit()) return true;
                        }
                    }
                }
                if (!request.plan) continue;
                CommitmentReminders.PlanPage page = CommitmentReminders.planPage(context, request.cursor,
                    request.wanted, request.deliver, token);
                token.check();
                synchronized (LOCK) {
                    SharedPreferences p = prefs(context);
                    if (p.getLong("generation", 0) != request.generation) continue;
                    if (!page.more) {
                        CommitmentReminders.finishPlan(context, page.wanted, token);
                        if (!p.edit().putBoolean("plan", false).putBoolean("deliver", false)
                                .remove("cursor").remove("wanted").commit()) return true;
                    } else if (!p.edit().putLong("cursor", page.cursor)
                            .putStringSet("wanted", page.wanted).commit()) return true;
                }
            }
            return true; // Bounded run; leave the cursor for the native backoff continuation.
        } catch (CancellationException stopped) { return true; }
        catch (Exception failure) { Log.w(TAG, "reminder worker will retry", failure); return true; }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static Set<String> copy(SharedPreferences prefs, String key) {
        return new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
    }

    static void setSchedulerForTest(Context context, Scheduler replacement) {
        synchronized (LOCK) {
            if (running != null) running.stop();
            NATIVE.cancel(context);
            scheduler = replacement == null ? NATIVE : replacement;
            initialized = scheduled = retryPosted = false;
            running = null;
            prefs(context).edit().clear().commit();
        }
    }
}
