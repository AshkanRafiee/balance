package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.job.JobInfo;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic journal/queue protocol tests; no timing-sensitive native scheduling or data scans. */
@RunWith(AndroidJUnit4.class)
public class CommitmentReminderJobsTest {
    private static final int PLAN = CommitmentReminderJobs.JOB_ID;
    private static final int FIRE = CommitmentReminderJobs.DELIVERY_JOB_ID;
    private static final int FALLBACK = CommitmentReminderJobs.FALLBACK_JOB_ID;
    private Context context;
    private FakeScheduler scheduler;

    private static final class FakeScheduler implements CommitmentReminderJobs.Scheduler {
        final Set<Integer> pending = new HashSet<>(), rejected = new HashSet<>();
        final Map<Integer, Integer> attempts = new HashMap<>();
        final List<Long> retries = new ArrayList<>();

        @Override public synchronized boolean pending(Context context, int jobId) {
            return pending.contains(jobId);
        }
        @Override public synchronized boolean enqueue(Context context, int jobId) {
            attempts.put(jobId, count(jobId) + 1);
            if (rejected.contains(jobId)) return false;
            pending.add(jobId);
            return true;
        }
        @Override public synchronized void retry(Context context, long delay) { retries.add(delay); }
        @Override public synchronized void cancel(Context context) { pending.clear(); }
        synchronized int count(int jobId) { return attempts.getOrDefault(jobId, 0); }
        synchronized void finished(int jobId) { pending.remove(jobId); }
    }

    private static class RecordingWork implements CommitmentReminderJobs.Work {
        final List<String> fired = new ArrayList<>();
        final List<Long> cursors = new ArrayList<>();
        int finishes, deliveryScans;

        @Override public void fire(Context context, String id, CommitmentReminderJobs.Token token)
                throws Exception {
            token.check();
            fired.add(id);
        }
        @Override public CommitmentReminders.PlanPage page(Context context, long cursor,
                Set<String> wanted, boolean deliver, CommitmentReminderJobs.Token token)
                throws Exception {
            token.check();
            cursors.add(cursor);
            if (deliver) deliveryScans++;
            return new CommitmentReminders.PlanPage(false, cursor, new HashSet<>(wanted));
        }
        @Override public void finish(Context context, Set<String> wanted,
                CommitmentReminderJobs.Token token) throws Exception {
            token.check();
            finishes++;
        }
    }

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentReminderJobs.awaitJournalForTest();
        scheduler = new FakeScheduler();
        CommitmentReminderJobs.setSchedulerForTest(context, scheduler);
    }

    @After public void tearDown() throws Exception {
        CommitmentJobIsolationListener.restore(context);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("commitment_reminder_jobs", Context.MODE_PRIVATE);
    }

    private CommitmentReminderJobs.Request request() {
        return new CommitmentReminderJobs.Request(prefs());
    }

    private void journal() throws Exception { CommitmentReminderJobs.awaitJournalForTest(); }

    private boolean drain(int jobId, RecordingWork work) {
        return CommitmentReminderJobs.run(context, new CommitmentReminderJobs.Token(), jobId,
            () -> scheduler.finished(jobId), work);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("background operation did not reach its checkpoint", latch.await(5, TimeUnit.SECONDS));
    }

    @Test public void stableNativeDescriptionsKeepUrgencySeparateFromBulkPlanning() {
        JobInfo plan = CommitmentReminderJobs.jobInfo(context, PLAN);
        JobInfo fire = CommitmentReminderJobs.jobInfo(context, FIRE);
        JobInfo fallback = CommitmentReminderJobs.jobInfo(context, FALLBACK);
        assertFalse(plan.isPersisted());
        assertFalse(fire.isPersisted());
        assertFalse(fallback.isPersisted());
        assertEquals(0, fire.getMinLatencyMillis());
        assertEquals(0, fallback.getMaxExecutionDelayMillis());
        assertEquals(fire, CommitmentReminderJobs.jobInfo(context, FIRE));
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(fire.isExpedited());
            assertFalse(plan.isExpedited());
            assertFalse(fallback.isExpedited());
        } else assertEquals(0, fire.getMaxExecutionDelayMillis());
    }

    @Test public void repeatedPlansAndFiresCoalesceWithoutReplacingNativeJobs() throws Exception {
        for (int i = 0; i < 200; i++) {
            CommitmentReminderJobs.requestPlan(context);
            CommitmentReminderJobs.requestFire(context, "opaque-a");
        }
        journal();
        assertEquals(1, scheduler.count(PLAN));
        assertEquals(1, scheduler.count(FIRE));
        assertEquals(0, scheduler.count(FALLBACK));
        assertEquals(Collections.singleton("opaque-a"), request().ids.keySet());
    }

    @Test public void rapidAwaitCyclesIncludeReentrantCompletionsWithoutQueueBarrierTasks()
            throws Exception {
        AtomicInteger completions = new AtomicInteger();
        for (int i = 0; i < 150; i++) {
            String first = "opaque-cycle-" + i;
            String reentrant = "opaque-reentrant-" + i;
            CommitmentReminderJobs.requestFire(context, first, () -> {
                CommitmentReminderJobs.requestFire(context, reentrant, completions::incrementAndGet);
                completions.incrementAndGet();
            });
            journal();
            assertEquals("await must include callbacks and their newly queued handoffs",
                (i + 1) * 2, completions.get());
            assertEquals(new HashSet<>(Arrays.asList(first, reentrant)), request().ids.keySet());
            RecordingWork work = new RecordingWork();
            assertFalse(drain(FIRE, work));
            assertEquals(2, work.fired.size());
            assertTrue(request().empty());
            journal(); // Exercise idle waits while the production flush task is returning.
        }
    }

    @Test public void deliveryLaneNeverConsumesUnrelatedBulkPlan() throws Exception {
        CommitmentReminderJobs.requestPlan(context);
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        RecordingWork work = new RecordingWork();
        assertFalse(drain(FIRE, work));
        assertEquals(Collections.singletonList("opaque-a"), work.fired);
        assertEquals(0, work.finishes);
        assertTrue(request().plan);
        assertFalse(drain(PLAN, work));
        assertEquals(1, work.finishes);
        assertTrue(request().empty());
    }

    @Test public void dueIdsRunBeforeNextPlanningPage() throws Exception {
        CommitmentReminderJobs.requestPlan(context);
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        RecordingWork work = new RecordingWork() {
            @Override public CommitmentReminders.PlanPage page(Context context, long cursor,
                    Set<String> wanted, boolean deliver, CommitmentReminderJobs.Token token)
                    throws Exception {
                assertEquals(Collections.singletonList("opaque-a"), fired);
                return super.page(context, cursor, wanted, deliver, token);
            }
        };
        assertFalse(drain(PLAN, work));
    }

    @Test public void fireArrivingDuringPageRunsBeforeRemainingPlanningPages() throws Exception {
        CommitmentReminderJobs.requestPlan(context);
        journal();
        RecordingWork work = new RecordingWork() {
            @Override public CommitmentReminders.PlanPage page(Context ctx, long cursor,
                    Set<String> wanted, boolean deliver, CommitmentReminderJobs.Token token)
                    throws Exception {
                cursors.add(cursor);
                if (cursors.size() == 1) {
                    CommitmentReminderJobs.requestFire(ctx, "opaque-mid-page");
                    journal();
                    assertTrue("current bounded page may finish first", fired.isEmpty());
                    return new CommitmentReminders.PlanPage(true, 42, new HashSet<>());
                }
                assertEquals("new delivery must precede the next page",
                    Collections.singletonList("opaque-mid-page"), fired);
                return new CommitmentReminders.PlanPage(cursors.size() == 2, 84, new HashSet<>());
            }
        };
        assertFalse(drain(PLAN, work));
        assertEquals(Arrays.asList(-1L, 42L, 84L), work.cursors);
        assertEquals(1, work.finishes);
        assertTrue(request().empty());
    }

    @Test public void staleIdAcknowledgmentCannotRemoveNewRequestForSameId() throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        RecordingWork work = new RecordingWork() {
            @Override public void fire(Context ctx, String id, CommitmentReminderJobs.Token token)
                    throws Exception {
                super.fire(ctx, id, token);
                if (fired.size() == 1) {
                    CommitmentReminderJobs.requestFire(ctx, id);
                    journal();
                }
            }
        };
        assertFalse(drain(FIRE, work));
        assertEquals(Arrays.asList("opaque-a", "opaque-a"), work.fired);
        assertTrue(request().empty());
        assertEquals(1, scheduler.count(FIRE));
    }

    @Test public void editsDuringScanCoalesceIntoOneFreshPassWithoutResettingEveryPage()
            throws Exception {
        CommitmentReminderJobs.requestPlan(context);
        journal();
        RecordingWork work = new RecordingWork() {
            @Override public CommitmentReminders.PlanPage page(Context ctx, long cursor,
                    Set<String> wanted, boolean deliver, CommitmentReminderJobs.Token token)
                    throws Exception {
                cursors.add(cursor);
                if (cursors.size() == 1) {
                    for (int i = 0; i < 100; i++) CommitmentReminderJobs.requestPlan(ctx);
                    journal();
                    return new CommitmentReminders.PlanPage(true, 42, new HashSet<>());
                }
                return new CommitmentReminders.PlanPage(false, cursor, new HashSet<>());
            }
        };
        assertFalse(drain(PLAN, work));
        assertEquals(Arrays.asList(-1L, 42L, -1L), work.cursors);
        assertEquals("obsolete pass must not cancel alarms", 1, work.finishes);
    }

    @Test public void requestDuringFinalEffectsCannotBeAcknowledgedAsAnOlderPlan() throws Exception {
        CommitmentReminderJobs.requestPlan(context);
        journal();
        RecordingWork work = new RecordingWork() {
            @Override public void finish(Context ctx, Set<String> wanted,
                    CommitmentReminderJobs.Token token) throws Exception {
                super.finish(ctx, wanted, token);
                if (finishes == 1) {
                    CommitmentReminderJobs.requestPlan(ctx);
                    journal(); // Also proves final effects do not hold the journal monitor.
                }
            }
        };
        assertFalse(drain(PLAN, work));
        assertEquals(2, work.finishes);
        assertTrue(request().empty());
    }

    @Test public void overflowUsesDeliveringDefinitionScanInsteadOfDroppingAlarmIds()
            throws Exception {
        for (int i = 0; i < 150; i++) CommitmentReminderJobs.requestFire(context, "opaque-" + i);
        journal();
        assertTrue(request().plan);
        assertTrue(request().deliveryPlan);
        assertTrue(request().ids.size() <= 64);
        RecordingWork work = new RecordingWork();
        assertFalse(drain(FIRE, work));
        assertTrue("eligible definitions must be visited for delivery", work.deliveryScans > 0);
        assertTrue(request().empty());
    }

    @Test public void expeditedRejectionUsesDistinctStableFallbackWithoutRepeatedQuotaCalls()
            throws Exception {
        scheduler.rejected.add(FIRE);
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        for (int i = 0; i < 100; i++) CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        assertEquals(1, scheduler.count(FIRE));
        assertEquals(1, scheduler.count(FALLBACK));
        assertTrue(scheduler.pending(context, FALLBACK));
        assertFalse(drain(FALLBACK, new RecordingWork()));
        assertTrue(request().empty());
    }

    @Test public void recoveredExpeditedQuotaRestoresUrgencyWhileRegularFallbackRemainsPending()
            throws Exception {
        scheduler.rejected.add(FIRE);
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        assertEquals(1, scheduler.count(FIRE));
        assertEquals(1, scheduler.count(FALLBACK));
        assertTrue(scheduler.pending(context, FALLBACK));

        scheduler.rejected.remove(FIRE);
        CommitmentReminderJobs.requestFire(context, "opaque-b");
        journal();
        assertEquals("quota recovery must still respect the admission cooldown", 1,
            scheduler.count(FIRE));
        assertEquals(1, scheduler.count(FALLBACK));

        prefs().edit().remove("retry_after:" + FIRE).commit();
        CommitmentReminderJobs.requestFire(context, "opaque-c");
        journal();
        assertEquals(2, scheduler.count(FIRE));
        assertEquals("the pending fallback must not be replaced", 1, scheduler.count(FALLBACK));
        assertTrue(scheduler.pending(context, FIRE));
        assertTrue(scheduler.pending(context, FALLBACK));

        CommitmentReminderJobs.requestFire(context, "opaque-d");
        journal();
        assertEquals("accepted expedited work must still coalesce", 2, scheduler.count(FIRE));
        RecordingWork work = new RecordingWork();
        assertFalse(drain(FIRE, work));
        assertEquals(new HashSet<>(Arrays.asList("opaque-a", "opaque-b", "opaque-c", "opaque-d")),
            new HashSet<>(work.fired));
        assertFalse(drain(FALLBACK, work));
        assertEquals("the surviving fallback must not replay acknowledged delivery", 4,
            work.fired.size());
        assertTrue(request().empty());
    }

    @Test public void bothRejectionsRetainRequestsAndUseBoundedAlarmBackoff() throws Exception {
        scheduler.rejected.add(FIRE);
        scheduler.rejected.add(FALLBACK);
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        long sequence = prefs().getLong("sequence", 0);
        for (int i = 0; i < 100; i++) CommitmentReminderJobs.retry(context);
        journal();
        assertEquals(sequence, prefs().getLong("sequence", 0));
        assertEquals(1, scheduler.count(FIRE));
        assertEquals(1, scheduler.count(FALLBACK));
        assertTrue(request().ids.containsKey("opaque-a"));
        assertFalse(scheduler.retries.isEmpty());
        assertTrue(scheduler.retries.get(0) >= 10_000L);
        prefs().edit().remove("retry_after:" + FIRE).remove("retry_after:" + FALLBACK).commit();
        CommitmentReminderJobs.retry(context);
        journal();
        assertEquals(2, scheduler.count(FIRE));
        assertEquals(2, scheduler.count(FALLBACK));
        assertEquals(sequence, prefs().getLong("sequence", 0));
    }

    @Test public void processRestartReusesPendingSignalAndRebuildsMissingNativeSignal()
            throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        long sequence = prefs().getLong("sequence", 0);
        CommitmentReminderJobs.forgetProcessForTest();
        CommitmentReminderJobs.retry(context);
        journal();
        assertEquals(1, scheduler.count(FIRE));
        scheduler.finished(FIRE); // Native queue is non-persisted and disappears at reboot.
        CommitmentReminderJobs.forgetProcessForTest();
        CommitmentReminderJobs.retry(context);
        journal();
        assertEquals(2, scheduler.count(FIRE));
        assertEquals(sequence, prefs().getLong("sequence", 0));
        assertTrue(request().ids.containsKey("opaque-a"));
    }

    @Test public void removedNativeJobsAreRepairedWithoutRequiringProcessRestart() throws Exception {
        CommitmentReminderJobs.requestPlan(context);
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        assertEquals(1, scheduler.count(PLAN));
        assertEquals(1, scheduler.count(FIRE));

        scheduler.finished(FIRE); // Simulate native removal without an onStopJob callback.
        CommitmentReminderJobs.requestFire(context, "opaque-b");
        journal();
        assertEquals(2, scheduler.count(FIRE));
        assertEquals(1, scheduler.count(PLAN));
        assertTrue(request().ids.keySet().containsAll(Arrays.asList("opaque-a", "opaque-b")));

        scheduler.finished(PLAN);
        long sequence = prefs().getLong("sequence", 0);
        CommitmentReminderJobs.retry(context);
        journal();
        assertEquals(2, scheduler.count(PLAN));
        assertEquals("an existing delivery job must not be replaced", 2, scheduler.count(FIRE));
        assertEquals(sequence, prefs().getLong("sequence", 0));
        assertTrue(scheduler.pending(context, PLAN));
        assertTrue(scheduler.pending(context, FIRE));
    }

    @Test public void workerFailureLeavesSpecificGenerationForNativeRetry() throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        RecordingWork failure = new RecordingWork() {
            @Override public void fire(Context ctx, String id, CommitmentReminderJobs.Token token) {
                throw new IllegalStateException("provider unavailable");
            }
        };
        long generation = request().ids.get("opaque-a");
        assertTrue(drain(FIRE, failure));
        assertEquals(generation, request().ids.get("opaque-a").longValue());
        assertTrue(scheduler.pending(context, FIRE));
        assertFalse(drain(FIRE, new RecordingWork()));
    }

    @Test public void stoppedWorkerCannotAcknowledgeRequestOrFinishQueue() throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
        AtomicBoolean queueFinished = new AtomicBoolean();
        RecordingWork work = new RecordingWork() {
            @Override public void fire(Context ctx, String id, CommitmentReminderJobs.Token current)
                    throws Exception {
                super.fire(ctx, id, current);
                CommitmentReminderJobs.stopped(current);
            }
        };
        assertTrue(CommitmentReminderJobs.run(context, token, FIRE,
            () -> queueFinished.set(true), work));
        assertTrue(request().ids.containsKey("opaque-a"));
        assertFalse(queueFinished.get());
        assertFalse(drain(FIRE, new RecordingWork()));
    }

    @Test public void queueErrorsRetryWithoutInventingOrLosingDurableRequests() throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        RecordingWork work = new RecordingWork();
        assertTrue(CommitmentReminderJobs.run(context, new CommitmentReminderJobs.Token(), FIRE,
            () -> { throw new IllegalStateException("queue unavailable"); }, work));
        assertTrue(request().empty());
        assertFalse(drain(FIRE, work));
        assertEquals(Collections.singletonList("opaque-a"), work.fired);
    }

    @Test public void enqueueAtAtomicCompletionGetsNewSignalAndCallerNeverWaitsForQueue()
            throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean enqueued = new AtomicBoolean();
        RecordingWork work = new RecordingWork();
        assertFalse(CommitmentReminderJobs.run(context, new CommitmentReminderJobs.Token(), FIRE,
            () -> {
                Thread caller = new Thread(() -> {
                    CommitmentReminderJobs.requestFire(context, "opaque-b");
                    enqueued.set(true);
                    returned.countDown();
                });
                caller.start();
                try { await(returned); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                scheduler.finished(FIRE); // dequeueWork()==null atomically ends the old job.
            }, work));
        journal();
        assertTrue(enqueued.get());
        assertEquals(2, scheduler.count(FIRE));
        assertTrue(scheduler.pending(context, FIRE));
        assertEquals(Collections.singleton("opaque-b"), request().ids.keySet());
    }

    /** Intercept only this test's private journal, preserving Android's real preference semantics. */
    private Context interceptedJournal(CommitInterceptor interceptor) {
        SharedPreferences actual = prefs();
        SharedPreferences wrapped = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
                if (!method.getName().equals("edit")) return method.invoke(actual, args);
                SharedPreferences.Editor editor = actual.edit();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.Editor.class}, (editorProxy, editMethod, values) -> {
                        if (editMethod.getName().equals("commit")) return interceptor.commit(editor);
                        Object result = editMethod.invoke(editor, values);
                        return result == editor ? editorProxy : result;
                    });
            });
        return new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("commitment_reminder_jobs") ? wrapped
                    : super.getSharedPreferences(name, mode);
            }
        };
    }

    private interface CommitInterceptor { boolean commit(SharedPreferences.Editor editor) throws Exception; }

    @Test public void blockedPreferenceDiskNeverBlocksRequestOrStopLifecycle() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        Context blocked = interceptedJournal(editor -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                await(release);
            }
            return editor.commit();
        });
        try {
            CommitmentReminderJobs.requestPlan(blocked);
            await(entered);
            CountDownLatch returned = new CountDownLatch(1);
            Thread callbacks = new Thread(() -> {
                CommitmentReminderJobs.requestFire(blocked, "opaque-a");
                CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
                CommitmentReminderJobs.stopped(token);
                assertTrue(token.stopped());
                assertTrue(new CommitmentReminderJobService().onStopJob(null));
                returned.countDown();
            });
            callbacks.start();
            await(returned);
        } finally {
            release.countDown();
            journal();
        }
    }

    @Test public void receiverCompletionRetainsOnlyJournalNotDatabaseWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        Context blocked = interceptedJournal(editor -> {
            if (first.getAndSet(false)) { entered.countDown(); await(release); }
            return editor.commit();
        });
        AtomicBoolean handedOff = new AtomicBoolean();
        try {
            CommitmentReminderJobs.requestFire(blocked, "opaque-a", () -> handedOff.set(true));
            await(entered);
            assertFalse(handedOff.get());
        } finally { release.countDown(); journal(); }
        assertTrue(handedOff.get());
        assertTrue("handoff must not run database delivery", request().ids.containsKey("opaque-a"));
    }

    @Test public void failedRequestCommitDoesNotAdmitNativeWorkUntilAFlushSucceeds()
            throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        Context failing = interceptedJournal(editor -> {
            boolean result = editor.commit();
            return first.getAndSet(false) ? false : result;
        });
        CommitmentReminderJobs.requestFire(failing, "opaque-a");
        journal();
        assertEquals(0, scheduler.count(FIRE));
        assertTrue(request().ids.containsKey("opaque-a"));
        long sequence = prefs().getLong("sequence", 0);
        CommitmentReminderJobs.retry(context);
        journal();
        assertEquals(1, scheduler.count(FIRE));
        assertEquals(sequence, prefs().getLong("sequence", 0));
    }

    @Test public void failedAcknowledgmentMustFlushBeforeAtomicNativeCompletion() throws Exception {
        CommitmentReminderJobs.requestFire(context, "opaque-a");
        journal();
        AtomicBoolean fail = new AtomicBoolean(true);
        Context failing = interceptedJournal(editor -> {
            boolean result = editor.commit();
            return fail.get() ? false : result;
        });
        AtomicBoolean finished = new AtomicBoolean();
        RecordingWork work = new RecordingWork();
        assertTrue(CommitmentReminderJobs.run(failing, new CommitmentReminderJobs.Token(), FIRE,
            () -> finished.set(true), work));
        assertFalse(finished.get());
        assertEquals(1, work.fired.size());
        assertTrue(CommitmentReminderJobs.run(failing, new CommitmentReminderJobs.Token(), FIRE,
            () -> finished.set(true), work));
        assertFalse(finished.get());
        fail.set(false);
        assertFalse(CommitmentReminderJobs.run(failing, new CommitmentReminderJobs.Token(), FIRE,
            () -> finished.set(true), work));
        assertTrue(finished.get());
        assertEquals("successful effects are not replayed after a failed acknowledgment", 1,
            work.fired.size());
    }
}
