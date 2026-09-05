package com.ashkanrafiee.balance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;
import java.util.concurrent.atomic.AtomicBoolean;

public class BalanceWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.ashkanrafiee.balance.WIDGET_REFRESH";
    static final String ACTION_MASK = "com.ashkanrafiee.balance.WIDGET_MASK";
    private static final String ACTION_ALARM = "com.ashkanrafiee.balance.WIDGET_ALARM";
    private static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    private static final long REFRESH_INTERVAL = 10 * 60 * 1000L;
    static final int REQ_MASK = 1, REQ_REFRESH = 2;
    private static final int REQ_ALARM = 100;
    private static final long MIN_SPIN_DURATION = 700L;
    private static final long MAX_SPIN_DURATION = 2500L;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile float spin;

    /** Re-scans SMS on a worker thread while spinning the refresh icon, then renders the final widget. */
    private static void refreshData(Context context) {
        new Thread(() -> {
            if (!REFRESHING.compareAndSet(false, true)) return;
            try {
                AtomicBoolean done = new AtomicBoolean(false);
                new Thread(() -> {
                    try {
                        BalanceData.scanSms(context, BalanceData.read(context));
                    } finally {
                        done.set(true);
                    }
                }, "balance-scan").start();

                long start = SystemClock.uptimeMillis();
                int step = 0;
                while (!done.get() && SystemClock.uptimeMillis() - start < MAX_SPIN_DURATION) {
                    step++;
                    spin = (float) ((step * 45) % 360);
                    updateAll(context);
                    SystemClock.sleep(60);
                }
                while (SystemClock.uptimeMillis() - start < MIN_SPIN_DURATION) SystemClock.sleep(40);
                spin = 0f;
                updateAll(context);
            } finally {
                REFRESHING.set(false);
            }
        }, "balance-refresh").start();
    }

    /** Renders the widget again from saved balances; called by the app after its own refresh. */
    static void push(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class)).length > 0)
            updateAll(context);
    }

    static void updateAll(Context context) {
        updateAll(context, 0f);
    }

    static void updateAll(Context context, float rotation) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class));
        if (ids.length == 0) return;
        for (int id : ids) manager.updateAppWidget(id, buildViews(context));
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
    }

    static RemoteViews buildViews(Context context) {
        Context c = LocaleHelper.wrap(context);
        boolean hidden = BalanceData.isHidden(c);
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance);
        views.setRemoteAdapter(R.id.widget_list, new Intent(c, BalanceWidgetService.class));
        views.setImageViewResource(R.id.widget_mask,
            hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        views.setContentDescription(R.id.widget_mask, c.getString(R.string.widget_action_mask));
        views.setContentDescription(R.id.widget_refresh, c.getString(R.string.widget_action_refresh));
        views.setFloat(R.id.widget_refresh, "setRotation", spin);
        views.setOnClickPendingIntent(R.id.widget_mask, pending(c, ACTION_MASK, REQ_MASK));
        views.setOnClickPendingIntent(R.id.widget_refresh, pending(c, ACTION_REFRESH, REQ_REFRESH));
        return views;
    }

    static PendingIntent pending(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, BalanceWidgetProvider.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean hasWidgets(Context context) {
        return AppWidgetManager.getInstance(context)
            .getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class)).length > 0;
    }

    private static PendingIntent alarmPending(Context context) {
        Intent intent = new Intent(context, BalanceWidgetProvider.class);
        intent.setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(context, REQ_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + REFRESH_INTERVAL,
            REFRESH_INTERVAL, alarmPending(context));
    }

    private static void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmPending(context));
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        scheduleAlarm(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        cancelAlarm(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        if (!hasWidgets(context)) cancelAlarm(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        scheduleAlarm(context);
        refreshData(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action) || ACTION_ALARM.equals(action)) {
            if (ACTION_ALARM.equals(action)) scheduleAlarm(context);
            refreshData(context);
        } else if (ACTION_MASK.equals(action)) {
            boolean hidden = !BalanceData.isHidden(context);
            context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(BalanceData.KEY_HIDDEN, hidden).apply();
            updateAll(context);
        } else if (ACTION_BOOT_COMPLETED.equals(action) && hasWidgets(context)) {
            scheduleAlarm(context);
        }
    }
}