package com.ashkanrafiee.balance;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.os.Handler;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native lifecycle owns execution time. No receiver or UI waits for financial-store work. */
public final class CommitmentReminderJobService extends JobService {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30L,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(3), new ThreadPoolExecutor.AbortPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }

    private static final class Execution {
        final CommitmentReminderJobs.Token token = new CommitmentReminderJobs.Token();
        Future<?> future;
    }

    // Lifecycle callbacks run on the main thread. Keep exact parameter identity: a delayed stop
    // for an old execution must never cancel, finish or acknowledge a newer execution.
    private final Map<JobParameters, Execution> executions = new IdentityHashMap<>();

    @Override public boolean onStartJob(JobParameters parameters) {
        Execution execution = new Execution();
        Execution previous = executions.put(parameters, execution);
        if (previous != null) {
            CommitmentReminderJobs.stopped(previous.token);
            if (previous.future != null) previous.future.cancel(true);
            WORKER.purge();
        }
        CommitmentReminderJobs.started(execution.token);
        Handler main = new Handler(getMainLooper());
        try {
            execution.future = WORKER.submit(() -> {
                boolean retry = CommitmentReminderJobs.run(getApplicationContext(), execution.token,
                    parameters.getJobId(),
                    () -> {
                        JobWorkItem item;
                        while ((item = parameters.dequeueWork()) != null) {
                            execution.token.check();
                            parameters.completeWork(item);
                        }
                    });
                main.post(() -> complete(parameters, execution, retry));
            });
        } catch (RuntimeException rejected) {
            // Retry through the native job's backoff, never by repeatedly rescheduling here.
            main.post(() -> complete(parameters, execution, true));
        }
        return true;
    }

    private void complete(JobParameters parameters, Execution execution, boolean retry) {
        if (executions.get(parameters) != execution) return;
        executions.remove(parameters);
        CommitmentReminderJobs.completed(execution.token);
        if (execution.token.stopped()) return;
        // dequeueWork()==null already atomically finished successful work. Calling jobFinished
        // afterwards can finish work enqueued concurrently for the same stable job ID.
        if (retry) jobFinished(parameters, true);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Execution execution = executions.remove(parameters);
        if (execution != null) {
            CommitmentReminderJobs.stopped(execution.token);
            if (execution.future != null) execution.future.cancel(true);
            WORKER.purge();
        }
        return true;
    }

    @Override public void onDestroy() {
        for (Execution execution : executions.values()) {
            CommitmentReminderJobs.stopped(execution.token);
            if (execution.future != null) execution.future.cancel(true);
        }
        executions.clear();
        WORKER.purge();
        super.onDestroy();
    }
}
