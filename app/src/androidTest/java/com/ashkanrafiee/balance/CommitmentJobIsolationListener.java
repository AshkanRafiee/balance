package com.ashkanrafiee.balance;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

/** Supply with AndroidJUnitRunner's -e listener argument; production code never detects tests. */
public final class CommitmentJobIsolationListener extends RunListener {
    private static boolean installed;
    private static final CommitmentReminderJobs.Scheduler ISOLATED =
        new CommitmentReminderJobs.Scheduler() {
            @Override public boolean pending(Context context, int jobId) { return false; }
            @Override public boolean enqueue(Context context, int jobId) { return true; }
            @Override public void retry(Context context, long delay) { }
            @Override public void cancel(Context context) { }
        };

    @Override public void testRunStarted(Description description) throws Exception {
        installed = true;
        restore(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    static void restore(Context context) throws Exception {
        CommitmentReminderJobs.awaitJournalForTest();
        CommitmentReminderJobs.setSchedulerForTest(context, installed ? ISOLATED : null);
    }

    @Override public void testRunFinished(Result result) throws Exception {
        installed = false;
        restore(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }
}
