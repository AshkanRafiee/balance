package com.ashkanrafiee.balance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Durable coalesced requests. Only opaque IDs, generations and scan progress are stored here. */
final class CommitmentReminderJobs {
    static final int JOB_ID = 0x42414c;
    static final int DELIVERY_JOB_ID = JOB_ID + 1;
    static final int FALLBACK_JOB_ID = JOB_ID + 2;
    static final String RETRY = "com.ashkanrafiee.balance.RETRY_COMMITMENT_JOB";
    private static final String PREFS = "commitment_reminder_jobs";
    private static final String TAG = "CommitmentReminderJobs";
    private static final int MAX_PENDING_IDS = 64;
    private static final long RETRY_MS = 10_000L;
    private static final long MAX_RETRY_MS = 5 * 60_000L;
    /** Background admission/journal only. Lifecycle callbacks and request callers never take it. */
    private static final Object LOCK = new Object();
    private static final Set<Integer> initialized = new HashSet<>(), admitted = new HashSet<>();
    private static final Set<Token> executions = ConcurrentHashMap.newKeySet();
    private static boolean needsFlush;
    private static final Object INBOX_LOCK = new Object();
    private static final Set<String> incomingIds = new HashSet<>();
    private static boolean incomingPlan, incomingOverflow, incomingRetry, dispatching;
    private static Runnable incomingCompletion;
    private static final Object RETRY_LOCK = new Object();
    private static long retryAlarmAt;
    private static final ThreadPoolExecutor JOURNAL = new ThreadPoolExecutor(1, 1, 30L,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
    static { JOURNAL.allowCoreThreadTimeOut(true); }

    interface Scheduler {
        boolean pending(Context context, int jobId);
        boolean enqueue(Context context, int jobId);
        void retry(Context context, long delay);
        void cancel(Context context);
    }

    private static final Scheduler NATIVE = new Scheduler() {
        @Override public boolean pending(Context context, int jobId) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            return jobs != null && jobs.getPendingJob(jobId) != null;
        }

        @Override public boolean enqueue(Context context, int jobId) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return false;
            // Keep this specification identical on every enqueue: changing it replaces running
            // work. Work items cannot be persisted; boot/app-open repair uses our durable requests.
            return jobs.enqueue(jobInfo(context, jobId), new JobWorkItem(new Intent()))
                == JobScheduler.RESULT_SUCCESS;
        }

        @Override public void retry(Context context, long delay) {
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms == null) return;
            long trigger = SystemClock.elapsedRealtime() + delay;
            PendingIntent intent = retryIntent(context);
            try {
                if (CommitmentReminders.canScheduleExact(context)) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        trigger, intent);
                    return;
                }
            } catch (SecurityException permissionChanged) { /* Fall back if permission changed. */ }
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, intent);
        }

        @Override public void cancel(Context context) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs != null) {
                jobs.cancel(JOB_ID);
                jobs.cancel(DELIVERY_JOB_ID);
                jobs.cancel(FALLBACK_JOB_ID);
            }
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms != null) alarms.cancel(retryIntent(context));
        }
    };
    private static volatile Scheduler scheduler = NATIVE;

    private CommitmentReminderJobs() { }

    static final class Token {
        private volatile boolean stopped;
        void stop() { stopped = true; }
        boolean stopped() { return stopped || Thread.currentThread().isInterrupted(); }
        void check() { if (stopped()) throw new CancellationException("reminder job stopped"); }
    }

    static final class Request {
        final long generation, scanGeneration, cursor;
        final boolean plan, deliver, deliveryPlan;
        final Map<String, Long> ids = new HashMap<>();
        final Set<String> wanted;

        Request(SharedPreferences prefs) {
            generation = prefs.getLong("generation", 0);
            scanGeneration = prefs.getLong("scan_generation", generation);
            cursor = prefs.getLong("cursor", -1);
            plan = prefs.getBoolean("plan", false);
            deliver = prefs.getBoolean("scan_deliver", prefs.getBoolean("deliver", false));
            deliveryPlan = prefs.getBoolean("deliver", false);
            for (String id : copy(prefs, "ids")) ids.put(id, prefs.getLong(idKey(id), 0));
            wanted = copy(prefs, "wanted");
        }

        boolean empty() { return !plan && ids.isEmpty(); }
    }

    static void requestPlan(Context context) { enqueue(context, null, false, null); }
    static void requestPlan(Context context, Runnable handedOff) {
        enqueue(context, null, false, handedOff);
    }

    static void requestFire(Context context, String id) {
        requestFire(context, id, null);
    }

    static void requestFire(Context context, String id, Runnable handedOff) {
        if (id != null && !id.isEmpty()) enqueue(context, id, false, handedOff);
        else complete(handedOff);
    }

    private static void enqueue(Context context, String id, boolean retry, Runnable handedOff) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        boolean start;
        Runnable redundant = null;
        synchronized (INBOX_LOCK) {
            if (retry) incomingRetry = true;
            else if (id == null) incomingPlan = true;
            else if (!incomingOverflow) {
                incomingIds.add(id);
                if (incomingIds.size() > MAX_PENDING_IDS) {
                    incomingIds.clear();
                    incomingOverflow = true;
                }
            }
            // One retained PendingResult per batch is sufficient to retain the process while
            // journaling. Further callbacks finish immediately, avoiding an unbounded callback list.
            if (incomingCompletion == null) incomingCompletion = handedOff;
            else redundant = handedOff;
            start = !dispatching;
            dispatching = true;
        }
        complete(redundant);
        if (start) {
            Context application = app;
            try { JOURNAL.execute(() -> flushInbox(application)); }
            catch (RuntimeException unavailable) {
                Runnable abandoned;
                synchronized (INBOX_LOCK) {
                    dispatching = false;
                    abandoned = incomingCompletion;
                    incomingCompletion = null;
                }
                complete(abandoned);
                Log.w(TAG, "reminder journal unavailable; next request will repair");
            }
        }
    }

    private static void flushInbox(Context context) {
        while (true) {
            Set<String> ids;
            boolean plan, overflow, retry;
            Runnable handedOff;
            synchronized (INBOX_LOCK) {
                if (!incomingPlan && !incomingOverflow && !incomingRetry && incomingIds.isEmpty()) {
                    dispatching = false;
                    return;
                }
                ids = new HashSet<>(incomingIds);
                plan = incomingPlan;
                overflow = incomingOverflow;
                retry = incomingRetry;
                handedOff = incomingCompletion;
                incomingIds.clear();
                incomingPlan = incomingOverflow = incomingRetry = false;
                incomingCompletion = null;
            }
            try { journal(context, ids, plan, overflow, retry); }
            finally { complete(handedOff); }
        }
    }

    private static void complete(Runnable handedOff) {
        if (handedOff != null) {
            try { handedOff.run(); }
            catch (RuntimeException ignored) { /* An expired broadcast must not break journaling. */ }
        }
    }

    private static void journal(Context app, Set<String> incoming, boolean plan, boolean overflow,
            boolean retry) {
        try {
            synchronized (LOCK) {
                SharedPreferences prefs = prefs(app);
                long sequence = prefs.getLong("sequence", 0)
                    + (plan || overflow || !incoming.isEmpty() ? 1 : 0);
                SharedPreferences.Editor edit = prefs.edit().putLong("sequence", sequence);
                if (retry) {
                    synchronized (RETRY_LOCK) { retryAlarmAt = 0; }
                    long now = System.currentTimeMillis();
                    for (int jobId : new int[] {JOB_ID, DELIVERY_JOB_ID, FALLBACK_JOB_ID}) {
                        if (prefs.getLong("retry_after:" + jobId, 0) <= now)
                            edit.remove("retry_after:" + jobId);
                    }
                }
                Set<String> ids = copy(prefs, "ids");
                for (String id : incoming) {
                    ids.add(id);
                    edit.putLong(idKey(id), sequence);
                }
                overflow |= ids.size() > MAX_PENDING_IDS;
                if (plan || overflow) {
                    // Do not repeatedly reset a scan already in progress. Requests arriving during
                    // it coalesce into one fresh pass, and an obsolete pass cannot cancel alarms.
                    edit.putBoolean("plan", true).putLong("generation", sequence);
                    if (!prefs.getBoolean("plan", false)) {
                        edit.putLong("scan_generation", sequence).putLong("cursor", -1)
                            .putBoolean("scan_deliver", overflow || prefs.getBoolean("deliver", false))
                            .remove("wanted");
                    }
                    if (overflow) {
                        edit.putBoolean("deliver", true);
                        for (String pending : ids) edit.remove(idKey(pending));
                        ids.clear(); // A delivering definition scan replaces, never drops, these IDs.
                    }
                }
                // Persist before native handoff. A failed commit still leaves Android's in-memory
                // preference view changed; retry explicitly flushes it before admitting work.
                if (!persist(edit.putStringSet("ids", ids))) {
                    postRetry(app, RETRY_MS);
                    return;
                }
                ensureScheduled(app);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "reminder request unavailable; app-open/boot will repair");
            postRetry(app, RETRY_MS);
        }
    }

    /** Explicit retry alarm: retain the same generations and never create a scheduling loop. */
    static void retry(Context context) {
        enqueue(context, null, true, null);
    }

    static void retry(Context context, Runnable handedOff) {
        enqueue(context, null, true, handedOff);
    }

    private static void ensureScheduled(Context context) {
        Request request = new Request(prefs(context));
        if (!request.ids.isEmpty() || prefs(context).getBoolean("deliver", false)) {
            // A fallback may remain pending through Doze after expedited quota recovers. Retry
            // expedited admission after its cooldown without replacing/cancelling that fallback.
            if (!ensureLane(context, DELIVERY_JOB_ID, false))
                ensureLane(context, FALLBACK_JOB_ID, true);
        }
        if (request.plan) ensureLane(context, JOB_ID, true);
    }

    private static boolean isAdmitted(Context context, int jobId) {
        // Android can remove an accepted job while this process survives. Validate positive
        // admission on every request/repair, without consuming another enqueue quota entry.
        if (initialized.add(jobId) || admitted.contains(jobId)) {
            if (scheduler.pending(context, jobId)) admitted.add(jobId);
            else admitted.remove(jobId);
        }
        return admitted.contains(jobId);
    }

    private static boolean ensureLane(Context context, int jobId, boolean alarmOnRejection) {
        if (isAdmitted(context, jobId)) return true;
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        long retryAt = prefs.getLong("retry_after:" + jobId, 0);
        if (retryAt > now && retryAt - now <= MAX_RETRY_MS) {
            if (alarmOnRejection) postRetry(context, retryAt - now);
            return false;
        }
        boolean accepted = false;
        try { accepted = scheduler.enqueue(context, jobId); }
        catch (RuntimeException failure) { Log.w(TAG, "native reminder admission unavailable"); }
        if (accepted) {
            admitted.add(jobId);
            // A failure here is harmless: request state was already durable before enqueue.
            persist(prefs.edit().remove("retry_after:" + jobId).remove("retry_delay:" + jobId));
        } else {
            long delay = Math.min(MAX_RETRY_MS,
                Math.max(RETRY_MS, prefs.getLong("retry_delay:" + jobId, RETRY_MS / 2) * 2));
            persist(prefs.edit().putLong("retry_after:" + jobId, now + delay)
                .putLong("retry_delay:" + jobId, delay));
            if (alarmOnRejection) postRetry(context, delay);
        }
        return accepted;
    }

    private static void postRetry(Context context, long delay) {
        long at = SystemClock.elapsedRealtime() + delay;
        synchronized (RETRY_LOCK) {
            if (retryAlarmAt > SystemClock.elapsedRealtime() && retryAlarmAt <= at) return;
            retryAlarmAt = at;
        }
        try { scheduler.retry(context, delay); }
        catch (RuntimeException failure) {
            synchronized (RETRY_LOCK) { if (retryAlarmAt == at) retryAlarmAt = 0; }
            Log.w(TAG, "retry alarm unavailable; app-open/boot will repair");
        }
    }

    private static PendingIntent retryIntent(Context context) {
        return PendingIntent.getBroadcast(context, JOB_ID,
            new Intent(context, CommitmentBootReceiver.class).setAction(RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static JobInfo jobInfo(Context context, int jobId) {
        JobInfo.Builder builder = new JobInfo.Builder(jobId,
            new ComponentName(context, CommitmentReminderJobService.class))
            .setBackoffCriteria(RETRY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (jobId == DELIVERY_JOB_ID && Build.VERSION.SDK_INT >= 31) builder.setExpedited(true);
        else builder.setOverrideDeadline(0);
        return builder.build();
    }

    /** Lifecycle bookkeeping is memory-only; never take journal/data monitors on the main thread. */
    static void started(Token token) { executions.add(token); }
    static void completed(Token token) { executions.remove(token); }
    static void stopped(Token token) {
        token.stop();
        executions.remove(token);
    }

    interface WorkQueue {
        /** Drain/complete native signals through dequeueWork()==null, which atomically ends the job. */
        void finishEmpty();
    }

    interface Work {
        void fire(Context context, String id, Token token) throws Exception;
        CommitmentReminders.PlanPage page(Context context, long cursor, Set<String> wanted,
            boolean deliver, Token token) throws Exception;
        void finish(Context context, Set<String> wanted, Token token) throws Exception;
    }

    private static final Work REMINDERS = new Work() {
        @Override public void fire(Context context, String id, Token token) throws Exception {
            CommitmentReminders.fireNow(context, id, token);
        }
        @Override public CommitmentReminders.PlanPage page(Context context, long cursor,
                Set<String> wanted, boolean deliver, Token token) throws Exception {
            return CommitmentReminders.planPage(context, cursor, wanted, deliver, token);
        }
        @Override public void finish(Context context, Set<String> wanted, Token token) throws Exception {
            CommitmentReminders.finishPlan(context, wanted, token);
        }
    };

    static boolean run(Context context, Token token, int jobId, WorkQueue queue) {
        return run(context, token, jobId, queue, REMINDERS);
    }

    /** Returns true only for native backoff; successful exhaustion must NOT call jobFinished. */
    static boolean run(Context context, Token token, int jobId, WorkQueue queue, Work work) {
        try {
            while (true) {
                token.check();
                Request request;
                synchronized (LOCK) {
                    token.check();
                    if (needsFlush && !persist(prefs(context).edit())) return true;
                    request = new Request(prefs(context));
                    if (request.empty() || (jobId != JOB_ID && request.ids.isEmpty()
                            && !request.deliveryPlan)) {
                        // Admission cannot observe an outgoing job as pending after its last
                        // dequeue. A concurrent request either joins this pass or enqueues a new
                        // native signal after null has atomically finished this execution.
                        queue.finishEmpty();
                        admitted.remove(jobId);
                        return false;
                    }
                }
                for (Map.Entry<String, Long> item : request.ids.entrySet()) {
                    token.check();
                    work.fire(context, item.getKey(), token);
                    synchronized (LOCK) {
                        token.check();
                        SharedPreferences prefs = prefs(context);
                        if (prefs.getLong(idKey(item.getKey()), 0) == item.getValue()) {
                            Set<String> remaining = copy(prefs, "ids");
                            remaining.remove(item.getKey());
                            if (!persist(prefs.edit().putStringSet("ids", remaining)
                                    .remove(idKey(item.getKey())))) return true;
                        }
                    }
                }
                if (!request.plan || (jobId != JOB_ID && !request.deliveryPlan)) continue;
                CommitmentReminders.PlanPage page = work.page(context, request.cursor,
                    request.wanted, request.deliver, token);
                token.check();
                boolean finish;
                synchronized (LOCK) {
                    token.check();
                    SharedPreferences prefs = prefs(context);
                    if (prefs.getLong("scan_generation", request.scanGeneration)
                            != request.scanGeneration) continue;
                    finish = !page.more && prefs.getLong("generation", 0) == request.scanGeneration;
                    if (!finish) {
                        SharedPreferences.Editor edit = prefs.edit();
                        if (page.more) {
                            edit.putLong("cursor", page.cursor).putStringSet("wanted", page.wanted);
                        } else {
                            edit.putLong("scan_generation", prefs.getLong("generation", 0))
                                .putBoolean("scan_deliver", prefs.getBoolean("deliver", false))
                                .putLong("cursor", -1).remove("wanted");
                        }
                        if (!persist(edit)) return true;
                    }
                }
                if (!finish) continue;
                // No dispatcher monitor while acquiring core locks or touching AlarmManager.
                work.finish(context, page.wanted, token);
                synchronized (LOCK) {
                    token.check();
                    SharedPreferences prefs = prefs(context);
                    if (prefs.getLong("generation", 0) == request.scanGeneration) {
                        if (!persist(prefs.edit().putBoolean("plan", false).putBoolean("deliver", false)
                                .remove("scan_deliver").remove("cursor").remove("wanted")
                                .remove("scan_generation"))) return true;
                    } else if (!persist(prefs.edit()
                            .putLong("scan_generation", prefs.getLong("generation", 0))
                            .putBoolean("scan_deliver", prefs.getBoolean("deliver", false))
                            .putLong("cursor", -1).remove("wanted"))) return true;
                }
            }
        } catch (CancellationException stopped) { return true; }
        catch (Exception failure) {
            // Exceptions can contain financial values from providers. Do not log their messages.
            Log.w(TAG, "reminder work incomplete; native backoff will retry");
            return true;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String idKey(String id) { return "id:" + id; }

    private static boolean persist(SharedPreferences.Editor editor) {
        needsFlush = true;
        boolean persisted = editor.commit();
        needsFlush = !persisted;
        return persisted;
    }

    private static Set<String> copy(SharedPreferences prefs, String key) {
        return new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
    }

    static void setSchedulerForTest(Context context, Scheduler replacement) {
        for (Token token : executions) token.stop();
        executions.clear();
        synchronized (LOCK) {
            NATIVE.cancel(context);
            scheduler = replacement == null ? NATIVE : replacement;
            initialized.clear();
            admitted.clear();
            synchronized (RETRY_LOCK) { retryAlarmAt = 0; }
            persist(prefs(context).edit().clear());
        }
    }

    /** Simulates process recreation without touching durable state or native pending work. */
    static void forgetProcessForTest() {
        synchronized (LOCK) {
            initialized.clear();
            admitted.clear();
        }
    }

    static void awaitJournalForTest() throws Exception {
        JOURNAL.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }
}
