package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.lang.reflect.Proxy;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reminder projections are bounded, current, cancellable and free of locked system effects. */
@RunWith(AndroidJUnit4.class)
public class CommitmentReminderPlanningTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentReminders.cancelAllForTest(context);
        DataGeneration.clearForTests(context);
        CommitmentStore.clear(context);
        reminderPrefs().edit().clear().commit();
        CommitmentReminders.ensureChannel(context);
    }

    @After public void tearDown() throws Exception {
        try {
            CommitmentReminders.cancelAllForTest(context);
            CommitmentStore.clear(context);
            reminderPrefs().edit().clear().commit();
        } finally {
            DataGeneration.clearForTests(context);
        }
    }

    @Test public void reverseOverdueSearchKeepsTheExactCapAndChecksOnlyLatestMembership() {
        CountingDates paid = new CountingDates(Collections.emptySet());
        CountingDates unpaid = new CountingDates(Collections.emptySet());
        Commitment definition = new Commitment("old", "Old daily", -1, Commitment.DAILY,
            inDays(-4096), null, false, paid, 0, unpaid, true, 0);
        long now = System.currentTimeMillis();
        List<Long> window = overdueWindow(definition, now);
        assertEquals(CommitmentsActivity.MAX_OVERDUE_OCCURRENCES, window.size());

        Long due = select(definition, now, Collections.emptySet());

        assertEquals(window.get(window.size() - 1), due);
        assertEquals("only the newest overdue due needs paid membership", 1, paid.lookups);
        assertEquals("only the newest overdue due needs unpaid membership", 1, unpaid.lookups);
    }

    @Test public void reverseSelectionMatchesAscendingForSettledFiredAndReopenedWatermark() {
        long now = System.currentTimeMillis();
        Commitment seed = new Commitment("mixed", "Mixed", -1, Commitment.DAILY,
            inDays(-4096), null, false, null, true, 0);
        List<Long> window = overdueWindow(seed, now);
        long latest = window.get(window.size() - 1);
        long firedDue = window.get(window.size() - 2);
        long reopened = window.get(window.size() - 3);
        long olderReopened = window.get(3);
        CountingDates paid = new CountingDates(Collections.singleton(latest));
        CountingDates unpaid = new CountingDates(new HashSet<>(
            java.util.Arrays.asList(reopened, olderReopened)));
        Commitment definition = new Commitment(seed.id, seed.name, seed.amount, seed.frequency,
            seed.start, null, false, paid, latest, unpaid, true, 0);
        Set<String> fired = Collections.singleton(seed.id + "|" + firedDue);
        Long expected = null;
        for (long at : window)
            if (!definition.isSettled(at) && !fired.contains(seed.id + "|" + at)) expected = at;
        paid.lookups = unpaid.lookups = 0;

        assertEquals(Long.valueOf(reopened), expected);
        assertEquals(expected, select(definition, now, fired));
        assertEquals("fired dates must not query settlement membership", 2, unpaid.lookups);
        assertEquals(1, paid.lookups);
    }

    @Test public void overduePlanningSharesOneActiveStoreSession() throws Exception {
        long now = System.currentTimeMillis();
        Commitment seed = new Commitment("session", "Session", -1, Commitment.DAILY,
            inDays(-4096), null, false, null, true, 0);
        List<Long> window = overdueWindow(seed, now);
        long latest = window.get(window.size() - 1);
        long reopened = window.get(window.size() - 3);
        CommitmentStore.upsert(context, new Commitment(seed.id, seed.name, seed.amount,
            seed.frequency, seed.start, null, false, Collections.singletonList(latest), latest,
            Collections.singletonList(reopened), true, 0));
        CountingContext counted = new CountingContext(context);

        Long due = CommitmentStore.withDefinition(counted, seed.id, definition ->
            select(definition, now, Collections.emptySet()));

        assertEquals(Long.valueOf(reopened), due);
        assertEquals("all settlement reads must share one key/session access", 1,
            counted.dataPreferenceReads);
        assertFalse(Thread.holdsLock(BalanceData.class));
    }

    @Test public void staleAlarmCannotNotifyTheNextFutureDueBeforeItsLeadWindow() throws Exception {
        Commitment definition = Commitment.create("Future private name", -500,
            Commitment.ONCE, inDays(5), null, true, 86_400_000L);
        CommitmentStore.upsert(context, definition);

        CommitmentReminders.fireNow(new CountingContext(context), definition.id,
            new CommitmentReminderJobs.Token());

        assertNull(notification(definition.id));
        assertTrue(reminderPrefs().getStringSet("fired_due", Collections.emptySet()).isEmpty());
        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));
    }

    @Test public void settledRecurringDueRearmsWithoutPrematurelyNotifyingItsSuccessor()
            throws Exception {
        Commitment definition = Commitment.create("Recurring", -500, Commitment.DAILY,
            inDays(5), null, true, 0);
        CommitmentStore.upsert(context, definition);
        CommitmentStore.settle(context, definition.id, definition.start);

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        assertNull(notification(definition.id));
        assertTrue(reminderPrefs().getStringSet("fired_due", Collections.emptySet()).isEmpty());
        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));
    }

    @Test public void distantDueKeepsItsCheckpointWithoutNotifyingEarly() throws Exception {
        Commitment definition = Commitment.create("Distant", -500, Commitment.ONCE,
            inDays(1200), null, true, 0);
        CommitmentStore.upsert(context, definition);

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        assertNull(notification(definition.id));
        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));
        assertTrue(reminderPrefs().getStringSet("fired_due", Collections.emptySet()).isEmpty());
    }

    @Test public void boundedPlanPagesPreserveAllRemindersAndCancelDeletedOwners()
            throws Exception {
        List<Commitment> definitions = new ArrayList<>();
        for (int i = 0; i < 35; i++) definitions.add(Commitment.create("Page " + i, -1,
            Commitment.ONCE, inDays(5), null, true, 0));
        CommitmentStore.replace(context, definitions);
        CommitmentReminders.PlanPage first = CommitmentReminders.planPage(context, -1,
            Collections.emptySet(), false, new CommitmentReminderJobs.Token());
        assertTrue(first.more);
        assertTrue(first.cursor > 0);
        assertTrue(first.wanted.size() < definitions.size());
        plan();
        assertEquals(definitions.size(), CommitmentReminders.scheduledIds(context).size());

        CommitmentStore.delete(context, definitions.get(0).id);
        plan();
        assertEquals(definitions.size() - 1, CommitmentReminders.scheduledIds(context).size());
        assertFalse(CommitmentReminders.scheduledIds(context).contains(definitions.get(0).id));
    }

    @Test public void oldCleanupCannotCancelAnAlarmArmedByANewerRequest() throws Exception {
        Commitment definition = Commitment.create("New owner", -500, Commitment.ONCE,
            inDays(5), null, true, 0);
        CommitmentStore.upsert(context, definition);
        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));

        // An older completed scan did not see this definition. Cleanup must use current state.
        CommitmentReminders.finishPlan(context, Collections.emptySet(),
            new CommitmentReminderJobs.Token());

        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));
    }

    @Test public void deliveredOneTimeReminderStaysPrivateAndVisibleUntilSettled() throws Exception {
        Commitment definition = Commitment.create("Private loan", -500, Commitment.ONCE,
            inDays(-1), null, true, 0);
        CommitmentStore.upsert(context, definition);
        CommitmentReminders.fireNow(new CountingContext(context), definition.id,
            new CommitmentReminderJobs.Token());
        plan();

        Notification shown = notification(definition.id);
        assertNotNull("removing the consumed alarm must not remove its notification", shown);
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility);
        assertNotNull(shown.publicVersion);
        assertEquals(context.getString(R.string.app_name),
            shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertNull(shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertFalse(CommitmentReminders.scheduledIds(context).contains(definition.id));

        CommitmentStore.settle(context, definition.id, definition.start);
        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        assertNull(notification(definition.id));
    }

    @Test public void deletedDeliveredReminderIsCancelledEvenWithoutAnotherAlarm() throws Exception {
        Commitment definition = Commitment.create("Deleted", -500, Commitment.ONCE,
            inDays(-1), null, true, 0);
        CommitmentStore.upsert(context, definition);
        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        assertNotNull(notification(definition.id));
        CommitmentStore.delete(context, definition.id);

        plan();

        assertNull(notification(definition.id));
        assertTrue(CommitmentReminders.scheduledIds(context).isEmpty());
    }

    @Test public void disabledReminderCancelsItsAlarm() throws Exception {
        Commitment definition = Commitment.create("Disabled", -500, Commitment.ONCE,
            inDays(5), null, true, 0);
        CommitmentStore.upsert(context, definition);
        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));
        CommitmentStore.upsert(context, new Commitment(definition.id, definition.name,
            definition.amount, definition.frequency, definition.start, null, false, null, false, 0));

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        assertFalse(CommitmentReminders.scheduledIds(context).contains(definition.id));
        assertNull(notification(definition.id));
    }

    @Test public void endedOverdueSeriesDoesNotRepeatOlderDebtsAfterLatestWasDelivered()
            throws Exception {
        Commitment definition = Commitment.create("Ended daily", -500, Commitment.DAILY,
            inDays(-100), inDays(-1), true, 0);
        CommitmentStore.upsert(context, definition);

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        assertEquals(1, reminderPrefs().getStringSet("fired_due", Collections.emptySet()).size());
        assertTrue(reminderPrefs().getStringSet("overdue_fired", Collections.emptySet())
            .contains(definition.id));
        assertNotNull(notification(definition.id));
        assertFalse(CommitmentReminders.scheduledIds(context).contains(definition.id));
    }

    @Test public void manyDefinitionsDoNotEvictEachOthersDeliverySuppression() throws Exception {
        Set<String> fired = new HashSet<>();
        long due = inDays(-1);
        for (int i = 0; i < 8192; i++) fired.add("other-" + i + "|" + due);
        reminderPrefs().edit().putStringSet("fired_due", fired).commit();
        Commitment definition = Commitment.create("Another owner", -500, Commitment.ONCE,
            due, null, true, 0);
        CommitmentStore.upsert(context, definition);

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        Set<String> actual = reminderPrefs().getStringSet("fired_due", Collections.emptySet());
        assertEquals("the obsolete 500-definition limit must not drop other owners' tokens",
            8193, actual.size());
        assertTrue(actual.containsAll(fired));
        assertTrue(actual.contains(definition.id + "|" + due));
    }

    @Test public void stopWhileWaitingForDataMonitorDoesNoDatabaseWorkAfterAcquisition()
            throws Exception {
        CountingContext counted = new CountingContext(context);
        CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
        CountDownLatch starting = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            starting.countDown();
            try {
                CommitmentReminders.fireNow(counted, "cancelled", token);
                result.set(new AssertionError("stopped work must not complete"));
            } catch (Throwable failure) { result.set(failure); }
        });
        try {
            synchronized (BalanceData.class) {
                worker.start();
                assertTrue(starting.await(2, TimeUnit.SECONDS));
                long deadline = SystemClock.elapsedRealtime() + 2000;
                while (worker.getState() != Thread.State.BLOCKED
                        && worker.isAlive() && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(5);
                assertEquals(Thread.State.BLOCKED, worker.getState());
                token.stop();
            }
        } finally {
            token.stop();
            worker.join(3000);
        }
        assertFalse(worker.isAlive());
        assertTrue(result.get() instanceof CancellationException);
        assertEquals("check cancellation before opening the data store", 0,
            counted.dataPreferenceReads);
    }

    @Test public void deletionAfterProjectionPreventsStaleAlarmEffects() throws Exception {
        Commitment definition = Commitment.create("Changed", -500, Commitment.ONCE,
            inDays(5), null, true, 0);
        CommitmentStore.upsert(context, definition);
        Context changed = new CountingContext(context) {
            boolean deleted;
            @Override public Object getSystemService(String name) {
                if (ALARM_SERVICE.equals(name) && !deleted) {
                    deleted = true;
                    try { CommitmentStore.delete(context, definition.id); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return super.getSystemService(name);
            }
        };
        try {
            CommitmentReminders.fireNow(changed, definition.id, new CommitmentReminderJobs.Token());
            fail("a changed projection must retry, not arm its stale alarm");
        } catch (CancellationException expected) { }
        assertTrue(CommitmentReminders.scheduledIds(context).isEmpty());
        assertNull(notification(definition.id));
    }

    @Test public void notificationServiceFailureDoesNotAcknowledgeDelivery() throws Exception {
        Commitment definition = Commitment.create("Retry", -500, Commitment.ONCE,
            inDays(-1), null, true, 0);
        CommitmentStore.upsert(context, definition);
        Context failing = new CountingContext(context) {
            int notificationCalls;
            @Override public Object getSystemService(String name) {
                if (NOTIFICATION_SERVICE.equals(name) && ++notificationCalls == 4)
                    throw new IllegalStateException("notification service temporarily unavailable");
                return super.getSystemService(name);
            }
        };
        try {
            CommitmentReminders.fireNow(failing, definition.id, new CommitmentReminderJobs.Token());
            fail("notification failure must propagate to the durable dispatcher retry");
        } catch (IllegalStateException expected) { }
        assertTrue(reminderPrefs().getStringSet("fired_due", Collections.emptySet()).isEmpty());
        assertNull(notification(definition.id));

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        assertNotNull(notification(definition.id));
    }

    @Test public void scheduledStateDiskFlushDoesNotBlockUiSuppressionReset() throws Exception {
        assertUiResetDoesNotWaitForDisk(false);
    }

    @Test public void deliveryStateDiskFlushDoesNotBlockOrOverwriteUiSuppressionReset()
            throws Exception {
        assertUiResetDoesNotWaitForDisk(true);
    }

    private void assertUiResetDoesNotWaitForDisk(boolean deliver) throws Exception {
        Commitment definition = Commitment.create("Disk wait", -500, Commitment.ONCE,
            inDays(deliver ? -1 : 5), null, true, 0);
        CommitmentStore.upsert(context, definition);
        reminderPrefs().edit().putStringSet("fired_due", Collections.singleton(
            definition.id + "|" + inDays(-2))).commit();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        Context blocked = interceptedReminderState(editor -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("test did not release the disk flush");
            }
            return editor.commit();
        });
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> uiFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                CommitmentReminders.fireNow(blocked, definition.id,
                    new CommitmentReminderJobs.Token());
            } catch (Throwable failure) { workerFailure.set(failure); }
        });
        CountDownLatch uiReturned = new CountDownLatch(1);
        try {
            worker.start();
            assertTrue("worker must reach its durable flush", entered.await(5, TimeUnit.SECONDS));
            // Mirror settlement's state change before its UI-side suppression reset.
            CommitmentStore.settle(context, definition.id, definition.start);
            new Handler(Looper.getMainLooper()).post(() -> {
                try { CommitmentReminders.resetFired(context, definition.id); }
                catch (Throwable failure) { uiFailure.set(failure); }
                finally { uiReturned.countDown(); }
            });
            assertTrue("UI reset must not wait for worker disk I/O",
                uiReturned.await(2, TimeUnit.SECONDS));
            assertNull(uiFailure.get());
            assertTrue(reminderPrefs().getStringSet("fired_due", Collections.emptySet()).isEmpty());
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(workerFailure.get());
        assertTrue("the older worker flush cannot overwrite the newer UI reset",
            reminderPrefs().getStringSet("fired_due", Collections.emptySet()).isEmpty());
    }

    @Test public void failedDeliveryStateFlushPropagatesAndCanBeRetried() throws Exception {
        Commitment definition = Commitment.create("Flush retry", -500, Commitment.ONCE,
            inDays(-1), null, true, 0);
        CommitmentStore.upsert(context, definition);
        AtomicBoolean first = new AtomicBoolean(true);
        Context failing = interceptedReminderState(editor ->
            first.getAndSet(false) ? false : editor.commit());
        try {
            CommitmentReminders.fireNow(failing, definition.id, new CommitmentReminderJobs.Token());
            fail("a failed durable flush must remain a failed dispatcher request");
        } catch (IllegalStateException expected) { }
        assertNotNull(notification(definition.id));
        assertTrue(reminderPrefs().getStringSet("fired_due", Collections.emptySet())
            .contains(definition.id + "|" + definition.start));

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        assertEquals("retry must preserve successful delivery suppression", 1,
            reminderPrefs().getStringSet("fired_due", Collections.emptySet()).size());
        assertFalse(CommitmentReminders.scheduledIds(context).contains(definition.id));
    }

    @Test public void failedScheduledStateFlushPropagatesAndCanBeRetried() throws Exception {
        Commitment definition = Commitment.create("Alarm flush retry", -500, Commitment.ONCE,
            inDays(5), null, true, 0);
        CommitmentStore.upsert(context, definition);
        Context failing = interceptedReminderState(editor -> false);
        try {
            CommitmentReminders.fireNow(failing, definition.id, new CommitmentReminderJobs.Token());
            fail("an alarm-state flush failure must propagate for native retry");
        } catch (IllegalStateException expected) { }

        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());

        assertTrue(CommitmentReminders.scheduledIds(context).contains(definition.id));
        assertNull(notification(definition.id));
    }

    @Test public void resetHookRemovesOnlyReminderNotificationsWhileAdmissionIsBlocked()
            throws Exception {
        Commitment definition = Commitment.create("Reset private data", -500, Commitment.ONCE,
            inDays(-1), null, true, 0);
        CommitmentStore.upsert(context, definition);
        CommitmentReminders.fireNow(context, definition.id, new CommitmentReminderJobs.Token());
        assertNotNull(notification(definition.id));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.notify("unrelated-test", 91, new Notification.Builder(context,
            CommitmentReminders.CHANNEL_ID).setSmallIcon(R.drawable.ic_balance_monochrome)
                .setContentTitle(context.getString(R.string.app_name)).build());
        CommitmentStore.clear(context);
        CountDownLatch admission = new CountDownLatch(1), release = new CountDownLatch(1);
        CommitmentReminderJobs.awaitJournalForTest();
        CommitmentReminderJobs.setSchedulerForTest(context, new CommitmentReminderJobs.Scheduler() {
            @Override public boolean pending(Context ctx, int jobId) { return false; }
            @Override public boolean enqueue(Context ctx, int jobId) {
                admission.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("test did not release native admission");
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
                return true;
            }
            @Override public void retry(Context ctx, long delay) { }
            @Override public void cancel(Context ctx) { }
        });
        CountingContext counted = new CountingContext(context);
        AtomicReference<Throwable> hookFailure = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        try {
            CommitmentReminderJobs.requestPlan(context);
            assertTrue(admission.await(5, TimeUnit.SECONDS));
            new Handler(Looper.getMainLooper()).post(() -> {
                try { CommitmentReminders.afterReset(counted); }
                catch (Throwable failure) { hookFailure.set(failure); }
                finally { returned.countDown(); }
            });
            assertTrue("reset privacy cleanup cannot wait for job admission",
                returned.await(2, TimeUnit.SECONDS));
            assertNull(hookFailure.get());
            assertNull(notification(definition.id));
            assertEquals("reset cleanup must never read the financial store", 0,
                counted.dataPreferenceReads);
            boolean unrelatedPresent = false;
            for (StatusBarNotification shown : manager.getActiveNotifications())
                if ("unrelated-test".equals(shown.getTag())) unrelatedPresent = true;
            assertTrue("unrelated app notifications must be preserved", unrelatedPresent);
        } finally {
            release.countDown();
            CommitmentJobIsolationListener.restore(context);
            manager.cancel("unrelated-test", 91);
        }
    }

    @Test public void resetHookServiceFailureDoesNotCrashOrSkipPlanning() throws Exception {
        CountingContext unavailable = new CountingContext(context) {
            @Override public Object getSystemService(String name) {
                if (NOTIFICATION_SERVICE.equals(name))
                    throw new IllegalStateException("notification service unavailable");
                return super.getSystemService(name);
            }
        };

        CommitmentReminders.afterReset(unavailable);
        CommitmentReminderJobs.awaitJournalForTest();

        assertEquals(0, unavailable.dataPreferenceReads);
        assertTrue(context.getSharedPreferences("commitment_reminder_jobs", Context.MODE_PRIVATE)
            .getBoolean("plan", false));
    }

    private interface CommitInterceptor {
        boolean commit(SharedPreferences.Editor editor) throws Exception;
    }

    private Context interceptedReminderState(CommitInterceptor interceptor) {
        SharedPreferences actual = reminderPrefs();
        SharedPreferences wrapped = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
                if (!"edit".equals(method.getName())) return method.invoke(actual, args);
                SharedPreferences.Editor editor = actual.edit();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.Editor.class}, (editorProxy, editMethod, values) -> {
                        if ("commit".equals(editMethod.getName())) return interceptor.commit(editor);
                        Object result = editMethod.invoke(editor, values);
                        return result == editor ? editorProxy : result;
                    });
            });
        return new CountingContext(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "commitment_reminders".equals(name) ? wrapped
                    : super.getSharedPreferences(name, mode);
            }
        };
    }

    private SharedPreferences reminderPrefs() {
        return context.getSharedPreferences("commitment_reminders", Context.MODE_PRIVATE);
    }

    private Notification notification(String id) {
        for (StatusBarNotification shown : context.getSystemService(NotificationManager.class)
                .getActiveNotifications())
            if (("commitment:" + id).equals(shown.getTag())) return shown.getNotification();
        return null;
    }

    private void plan() throws Exception {
        long cursor = -1;
        Set<String> wanted = new HashSet<>();
        CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
        while (true) {
            CommitmentReminders.PlanPage page = CommitmentReminders.planPage(context, cursor,
                wanted, false, token);
            cursor = page.cursor;
            wanted = page.wanted;
            if (!page.more) break;
        }
        CommitmentReminders.finishPlan(context, wanted, token);
    }

    private static Long select(Commitment definition, long now, Set<String> fired) {
        return CommitmentReminders.reminderDue(definition, CalendarSystem.GREGORIAN, now,
            fired, false, new CommitmentReminderJobs.Token());
    }

    /** The unchanged old ascending overdue window, including its first-N cap. */
    private static List<Long> overdueWindow(Commitment definition, long now) {
        long end = Commitment.startOfDay(now) - 1;
        long span = CommitmentsActivity.MAX_OVERDUE_OCCURRENCES * 86_400_000L;
        long from = Math.max(Commitment.startOfDay(definition.start),
            Commitment.startOfDay(end > span ? end - span : 0));
        List<Long> dates = new ArrayList<>();
        Commitment.visitOccurrences(definition, CalendarSystem.GREGORIAN, from, end,
            dates::add, CommitmentsActivity.MAX_OVERDUE_OCCURRENCES);
        return dates;
    }

    private static long inDays(int delta) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(Commitment.startOfDay(System.currentTimeMillis()));
        day.add(Calendar.DAY_OF_MONTH, delta);
        return day.getTimeInMillis();
    }

    private static final class CountingDates extends Commitment.SettlementList {
        final Set<Long> dates;
        int lookups;
        CountingDates(Set<Long> dates) { this.dates = dates; }
        @Override public int size() { return dates.size(); }
        @Override public Long get(int index) { throw new AssertionError("must use exact membership"); }
        @Override public boolean contains(Object value) { lookups++; return dates.contains(value); }
    }

    private static class CountingContext extends ContextWrapper {
        int dataPreferenceReads;
        CountingContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (BalanceData.PREFS_DATA.equals(name)) dataPreferenceReads++;
            return super.getSharedPreferences(name, mode);
        }
        @Override public Object getSystemService(String name) {
            if (ALARM_SERVICE.equals(name) || NOTIFICATION_SERVICE.equals(name))
                assertFalse("system-service effects cannot retain the data monitor",
                    Thread.holdsLock(BalanceData.class));
            return super.getSystemService(name);
        }
    }
}
