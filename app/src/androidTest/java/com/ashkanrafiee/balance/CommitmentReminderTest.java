package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
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

/** The reminder bookkeeping: arming, cancelling and firing without touching a view. */
@RunWith(AndroidJUnit4.class)
public class CommitmentReminderTest {

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommitmentStore.clear(ctx);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear()
            .commit();
        ctx.getSharedPreferences("commitment_reminders", Context.MODE_PRIVATE).edit().clear()
            .commit();
    }

    @After public void tearDown() throws Exception {
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

    @Test public void scheduleAll_armsRemindingAndSkipsQuiet() {
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(Commitment.create("rent", -5000, Commitment.MONTHLY, inDays(5), null,
            true, 86400000L));
        commitments.add(Commitment.create("quiet", -5000, Commitment.MONTHLY, inDays(5), null,
            false, 0));
        BalanceData.writeCommitments(ctx, commitments);
        CommitmentReminders.scheduleAll(ctx);
        List<String> armed = CommitmentReminders.scheduledIds(ctx);
        assertEquals(1, armed.size());
        assertEquals(commitments.get(0).id, armed.get(0));
    }

    @Test public void scheduleAll_cancelsDeletedAndSettled() {
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(Commitment.create("rent", -5000, Commitment.MONTHLY, inDays(5), null,
            true, 86400000L));
        BalanceData.writeCommitments(ctx, commitments);
        CommitmentReminders.scheduleAll(ctx);
        assertEquals(1, CommitmentReminders.scheduledIds(ctx).size());
        BalanceData.writeCommitments(ctx, new ArrayList<Commitment>());
        CommitmentReminders.scheduleAll(ctx);
        assertTrue(CommitmentReminders.scheduledIds(ctx).isEmpty());
    }

    @Test public void fire_unknownIdIsANoOpThatCleansUp() {
        List<Commitment> commitments = new ArrayList<>();
        commitments.add(Commitment.create("rent", -5000, Commitment.MONTHLY, inDays(5), null,
            true, 86400000L));
        BalanceData.writeCommitments(ctx, commitments);
        CommitmentReminders.scheduleAll(ctx);
        CommitmentReminders.fire(ctx, "no-such-commitment");
        assertEquals(1, CommitmentReminders.scheduledIds(ctx).size());
    }

    @Test public void ensureChannel_doesNotCrash() {
        CommitmentReminders.ensureChannel(ctx);
    }
}
