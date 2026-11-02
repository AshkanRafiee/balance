package com.ashkanrafiee.balance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Public manifest endpoint that rebuilds reminders after boot or clock/package changes. */
public final class CommitmentBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        boolean retry = CommitmentReminderJobs.RETRY.equals(intent.getAction());
        if (!(retry || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                    .equals(intent.getAction()))) return;
        PendingResult pending = goAsync();
        try {
            // No database work or job execution belongs to this broadcast's deadline.
            if (retry) CommitmentReminderJobs.retry(context, pending::finish);
            else CommitmentReminderJobs.requestPlan(context, pending::finish);
        } catch (RuntimeException rejected) {
            pending.finish();
        }
    }
}
