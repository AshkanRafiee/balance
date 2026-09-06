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
    private static final String TAG = "BalanceWidget";
    private static final long REFRESH_INTERVAL = 10 * 60 * 1000L;
    /** Request-code offsets for the interactive widget actions; scoped by versionCode so app updates
     *  mint fresh PendingIntents. The repeating alarm uses the stable REQ_ALARM below instead, so it is
     *  reused rather than re-minted on updates. */
    static final int REQ_MASK = 1, REQ_REFRESH = 2, REQ_OPEN = 3;
    private static final int REQ_ALARM = 100;
    private static final long MIN_SPIN_DURATION = 700L;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile float spin;
    /** Widget frame cached for the spin loop so each frame only re-applies the rotation instead of
     *  rebuilding (and re-decrypting) the whole widget on every tick. */
    private static volatile RemoteViews spinBase;

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
                spinBase = buildViews(context);
                int step = 0;
                while (!done.get()) {
                    step++;
                    spin = (float) ((step * 45) % 360);
                    applySpin(context);
                    SystemClock.sleep(60);
                }
                while (SystemClock.uptimeMillis() - start < MIN_SPIN_DURATION) SystemClock.sleep(40);
                spin = 0f;
                applySpin(context);
                spinBase = null;
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
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class));
        if (ids.length == 0) return;
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
        for (int id : ids) manager.updateAppWidget(id, buildViews(context));
    }

    /** Rotates the refresh icon on the fully rendered widget, so no other element is ever dropped. */
    private static void applySpin(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class));
        if (ids.length == 0) return;
        RemoteViews views = spinBase;
        if (views == null) return;
        views.setFloat(R.id.widget_refresh, "setRotation", spin);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    static RemoteViews buildViews(Context context) {
        Context c = LocaleHelper.wrap(context);
        boolean hidden = BalanceData.isHidden(c);
        long total = 0;
        for (Bank b : BalanceData.read(c).values()) total += b.amount;
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance);
        views.setRemoteAdapter(R.id.widget_list, new Intent(c, BalanceWidgetService.class));
        views.setInt(R.id.widget_root, "setLayoutDirection",
            c.getResources().getConfiguration().getLayoutDirection());
        views.setTextViewText(R.id.widget_total,
            hidden ? "\u2022\u2022\u2022\u2022\u2022\u2022" : BalanceData.toman(c, total));
        views.setTextViewText(R.id.widget_unit, c.getString(R.string.unit_toman));
        views.setImageViewResource(R.id.widget_mask,
            hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        views.setContentDescription(R.id.widget_mask, c.getString(R.string.widget_action_mask));
        views.setContentDescription(R.id.widget_refresh, c.getString(R.string.widget_action_refresh));
        views.setFloat(R.id.widget_refresh, "setRotation", spin);
        views.setOnClickPendingIntent(R.id.widget_root, openApp(c));
        views.setOnClickPendingIntent(R.id.widget_mask, pending(c, ACTION_MASK, REQ_MASK));
        views.setOnClickPendingIntent(R.id.widget_refresh, pending(c, ACTION_REFRESH, REQ_REFRESH));
        return views;
    }

    /** Launches the main screen; used by the widget root and every list row, so a freshly added widget
     *  (or one whose SMS permission was revoked) still has a working path to the permission UI. */
    static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, REQ_OPEN + versionBase(context), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent pending(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, BalanceWidgetProvider.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode + versionBase(context), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int versionCode(Context context) {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int versionBase(Context context) {
        return versionCode(context) * 1000;
    }

    private static boolean hasWidgets(Context context) {
        return AppWidgetManager.getInstance(context)
            .getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class)).length > 0;
    }

    private static PendingIntent alarmPending(Context context) {
        Intent intent = new Intent(context, BalanceWidgetProvider.class);
        intent.setAction(ACTION_ALARM);
        return pendingRepeating(context, intent, REQ_ALARM);
    }

    /** Request code without the version base, so the repeating alarm is reused (not re-minted) on updates. */
    private static PendingIntent pendingRepeating(Context context, Intent intent, int requestCode) {
        return PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        cancelLegacyAlarms(context);
        am.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + REFRESH_INTERVAL,
            REFRESH_INTERVAL, alarmPending(context));
    }

    /** Versions up to 1.4.1 minted the repeating alarm at REQ_ALARM + versionCode * 1000, which survives
     *  an upgrade as a stale second alarm. Cancel those legacy request codes before re-arming so an
     *  upgraded install never fires the old and new alarms together. */
    private static void cancelLegacyAlarms(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int vc = versionCode(context);
        for (int v = vc; v >= Math.max(10000, vc - 4); v--) {
            PendingIntent legacy = PendingIntent.getBroadcast(context, REQ_ALARM + v * 1000,
                new Intent(context, BalanceWidgetProvider.class).setAction(ACTION_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(legacy);
        }
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

    /**
     * Delivers the widget actions (refresh, mask, alarm), the periodic-alarm wake-ups and the system
     * events (APPWIDGET_UPDATE, BOOT_COMPLETED, CONFIGURATION_CHANGED).
     *
     * The receiver is NOT exported, so only the system (AppWidgetService, alarms fired from our own
     * PendingIntents) and our own process can ever reach this code. No same-device app can craft an
     * explicit broadcast to force an SMS rescan, toggle the privacy mask, or spam the periodic alarm -
     * with a delivered broadcast the receiver thread carries the device-local uid anyway, so a
     * Binder.getCallingUid() check inside onReceive cannot be trusted to reflect the sender. Explicit
     * broadcasts from other apps fail to deliver outright, and unexported is the Google-documented
     * configuration for BOOT_COMPLETED + app-widget receivers (system delivery is unaffected by it).
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        super.onReceive(context, intent);
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
        } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action) && hasWidgets(context)) {
            updateAll(context);
        }
    }
}