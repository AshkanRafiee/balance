package com.ashkanrafiee.balance;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native lifecycle owns execution time. No receiver or UI waits for financial-store work. */
public final class CommitmentReminderJobService extends JobService {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30L,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }
    private CommitmentReminderJobs.Token active;
    private Future<?> future;

    @Override public boolean onStartJob(JobParameters parameters) {
        CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
        active = token;
        CommitmentReminderJobs.started(token);
        try {
            future = WORKER.submit(() -> {
                boolean retry = CommitmentReminderJobs.run(getApplicationContext(), token);
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (active != token || token.stopped()) return;
                    active = null;
                    CommitmentReminderJobs.finished(getApplicationContext(), token, retry,
                        () -> jobFinished(parameters, retry));
                });
            });
        } catch (RuntimeException unavailable) {
            active = null;
            CommitmentReminderJobs.finished(getApplicationContext(), token, true,
                () -> jobFinished(parameters, true));
        }
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        CommitmentReminderJobs.Token token = active;
        active = null;
        if (token != null) CommitmentReminderJobs.stopped(token);
        if (future != null) future.cancel(true);
        WORKER.purge();
        return true;
    }
}
