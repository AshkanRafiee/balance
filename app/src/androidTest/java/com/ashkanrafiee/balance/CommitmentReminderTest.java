package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** The reminder bookkeeping: arming, cancelling and firing without touching a view. */
@RunWith(AndroidJUnit4.class)
public class CommitmentReminderTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentReminders.cancelAllForTest(ctx);
        CommitmentStore.clear(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear()
            .commit();
        ctx.getSharedPreferences("commitment_reminders", Context.MODE_PRIVATE).edit().clear()
            .commit();
    }

    @After public void tearDown() throws Exception {
        CommitmentReminders.cancelAllForTest(ctx);
        CommitmentStore.clear(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear()
            .commit();
        ctx.getSharedPreferences("commitment_reminders", Context.MODE_PRIVATE).edit().clear()
            .commit();
    }

    private static long inDays(int delta) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Commitment.startOfDay(System.currentTimeMillis()));
        c.add(Calendar.DAY_OF_MONTH, delta);
        return c.getTimeInMillis();
    }

    @Test public void scheduleAll_armsRemindingAndSkipsQuiet() throws Exception {
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(Commitment.create("rent", -5000, Commitment.MONTHLY, inDays(5), null,
            true, 86400000L));
        commitments.add(Commitment.create("quiet", -5000, Commitment.MONTHLY, inDays(5), null,
            false, 0));
        CommitmentStore.replace(ctx, commitments);
        plan();
        List<String> armed = CommitmentReminders.scheduledIds(ctx);
        assertEquals(1, armed.size());
        assertEquals(commitments.get(0).id, armed.get(0));
    }

    @Test public void scheduleAll_cancelsDeletedAndSettled() throws Exception {
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(Commitment.create("rent", -5000, Commitment.MONTHLY, inDays(5), null,
            true, 86400000L));
        CommitmentStore.replace(ctx, commitments);
        plan();
        assertEquals(1, CommitmentReminders.scheduledIds(ctx).size());
        CommitmentStore.replace(ctx, new ArrayList<Commitment>());
        plan();
        assertTrue(CommitmentReminders.scheduledIds(ctx).isEmpty());
    }

    @Test public void fire_unknownIdIsANoOpThatCleansUp() throws Exception {
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(Commitment.create("rent", -5000, Commitment.MONTHLY, inDays(5), null,
            true, 86400000L));
        CommitmentStore.replace(ctx, commitments);
        plan();
        CommitmentReminders.fireNow(ctx, "no-such-commitment", new CommitmentReminderJobs.Token());
        assertEquals(1, CommitmentReminders.scheduledIds(ctx).size());
    }

    @Test public void ensureChannel_doesNotCrash() {
        CommitmentReminders.ensureChannel(ctx);
    }

    @Test public void cancelAllForTest_clearsArmedRemindersAndRearmsOnNextSchedule() throws Exception {
        // Covers the notifications-unavailable branch of scheduleAll (cancel everything,
        // then re-arm on the next pass) without simulating unavailable notifications:
        // revoking the permission kills the app process by design, taking the in-process
        // test runner with it, and a disabled channel keeps its importance across
        // delete/recreate, so neither can be used and restored inside one run.
        Commitment c = Commitment.create("rent", -5000, Commitment.ONCE, inDays(5), null,
            true, 0);
        CommitmentStore.replace(ctx, java.util.Collections.singletonList(c));
        plan();
        assertTrue("a reminder must arm while notifications are available",
            CommitmentReminders.scheduledIds(ctx).contains(c.id));
        CommitmentReminders.cancelAllForTest(ctx);
        assertTrue("cancelling must leave no armed reminders",
            CommitmentReminders.scheduledIds(ctx).isEmpty());
        plan();
        assertTrue("the next schedule must rearm the reminder",
            CommitmentReminders.scheduledIds(ctx).contains(c.id));
    }

    @Test public void notificationContent_namesEachSameTimeDue() {
        long due = inDays(5);
        Commitment rent = Commitment.create("rent", -5000, Commitment.MONTHLY, due, null,
            true, 0);
        Commitment loan = Commitment.create("loan", -9000, Commitment.MONTHLY, due, null,
            true, 0);
        String rentTitle = CommitmentReminders.notificationTitle(rent);
        String loanTitle = CommitmentReminders.notificationTitle(loan);
        assertEquals("rent", rentTitle);
        assertEquals("loan", loanTitle);
        String rentDetail = CommitmentReminders.notificationDetail(ctx, rent, due);
        String loanDetail = CommitmentReminders.notificationDetail(ctx, loan, due);
        assertTrue(rentDetail.contains(NotificationAmounts.format(ctx, -5000)));
        assertTrue(loanDetail.contains(NotificationAmounts.format(ctx, -9000)));
        assertFalse("same-time dues must stay distinguishable", rentTitle.equals(loanTitle)
            && rentDetail.equals(loanDetail));
    }

    /** Exercise the worker core synchronously; public requests deliberately do not drain jobs. */
    private void plan() throws Exception {
        CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
        long cursor = -1;
        Set<String> wanted = new HashSet<>();
        while (true) {
            CommitmentReminders.PlanPage page = CommitmentReminders.planPage(ctx, cursor,
                wanted, false, token);
            wanted = page.wanted;
            cursor = page.cursor;
            if (!page.more) break;
        }
        CommitmentReminders.finishPlan(ctx, wanted, token);
    }
}
