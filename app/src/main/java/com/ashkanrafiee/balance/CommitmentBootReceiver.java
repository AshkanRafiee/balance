package com.ashkanrafiee.balance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Public manifest endpoint that rebuilds reminders after boot or clock/package changes. */
public final class CommitmentBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        CommitmentReminders.scheduleAll(context);
    }
}
