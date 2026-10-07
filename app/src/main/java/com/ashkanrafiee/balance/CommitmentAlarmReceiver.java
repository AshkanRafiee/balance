package com.ashkanrafiee.balance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Public manifest endpoint for one commitment reminder alarm. */
public final class CommitmentAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !CommitmentReminders.ACTION_REMIND.equals(intent.getAction())) return;
        String id = intent.getStringExtra(CommitmentReminders.EXTRA_ID);
        if (id != null) CommitmentReminders.fire(context, id);
    }
}
