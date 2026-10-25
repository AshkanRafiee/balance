package com.ashkanrafiee.balance;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
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
    /** The only releases that minted the repeating alarm at REQ_ALARM + versionCode * 1000. */
    private static final int[] LEGACY_ALARM_VERSION_CODES = {10400, 10401};
    private static final long MIN_SPIN_DURATION = 700L;
    private static final long REFRESH_FRAME_INTERVAL = 80L;
    private static final int REFRESH_FRAME_COUNT = 8;
    private static final int MAX_REFRESH_FRAME_SIZE = 128;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    /** Serializes full renders and icon-only updates, especially when replacing a locked layout. */
    private static final Object WIDGET_UPDATE_LOCK = new Object();
    /** Guarded by WIDGET_UPDATE_LOCK; a lock or launcher failure stops animation for this refresh. */
    private static boolean refreshAnimation;
    /** Guarded by WIDGET_UPDATE_LOCK; coalesces the delayed mask re-assertions below. */
    private static int maskToggleGen;
    /** Guarded by WIDGET_UPDATE_LOCK; last processed mask toggle, monotonic ms. */
    private static long lastMaskToggleMs;
    /** Bounce window for the mask button: one physical tap must equal one toggle even when the
     *  launcher delivers the tap twice in rapid succession. Intentional re-taps land well after
     *  the list rebind (~1s+) and are unaffected. */
    private static final long MASK_DEBOUNCE_MS = 500L;
    /** Delays before re-asserting a mask toggle, letting slow list rebinds settle first. */
    private static final long MASK_REASSERT_DELAY_MS = 1200L;
    private static final long MASK_REASSERT_DELAY_MS_2 = 1500L;

    private static Context appContext(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    /** An unreadable lock preference must never reveal balances. */
    static boolean widgetLocked(Context context) {
        try {
            return LockManager.isEnabled(appContext(context));
        } catch (Exception e) {
            Log.w(TAG, "widget lock state unreadable", e);
            return true;
        }
    }

    /** Re-scans SMS on a worker thread while spinning the refresh icon, then renders the final widget
     *  without allowing a store, worker, or launcher failure to escape its boundary. While the app
     *  lock is enabled the widget does not rescan and shows the locked state instead. */
    private static void refreshData(Context context) {
        boolean refreshing = false;
        try {
            final Context app = appContext(context);
            if (widgetLocked(app) || app.checkSelfPermission(Manifest.permission.READ_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                // No point scanning without the permission: render the saved values plus the hint row
                // (and never risk the scan thread crashing on the denied read).
                updateAll(app);
                return;
            }
            refreshing = REFRESHING.compareAndSet(false, true);
            if (!refreshing) return;
            new Thread(() -> {
                try {
                    long start = SystemClock.uptimeMillis();
                    synchronized (WIDGET_UPDATE_LOCK) {
                        refreshAnimation = true;
                        // Partial updates need a full render first; ticks never rebuild balances.
                        updateAll(app);
                    }
                    Bitmap[] frames = null;
                    try {
                        frames = refreshFrames(app);
                    } catch (Exception e) {
                        Log.w(TAG, "widget refresh frames unavailable", e);
                    }
                    AtomicBoolean done = new AtomicBoolean(false);
                    new Thread(() -> {
                        try {
                            if (!widgetLocked(app))
                                BalanceData.scanSms(app, BalanceData.read(app));
                        } catch (Exception e) {
                            Log.w(TAG, "widget scan failed", e);
                        } finally {
                            done.set(true);
                        }
                    }, "balance-scan").start();
                    int frame = 0;
                    boolean animating = frames != null;
                    while (!done.get() || SystemClock.uptimeMillis() - start < MIN_SPIN_DURATION) {
                        // Animation failures must not release single-flight while the scan runs.
                        if (animating) {
                            animating = applyRefreshFrame(app, frames[frame]);
                            frame = (frame + 1) % frames.length;
                        }
                        SystemClock.sleep(REFRESH_FRAME_INTERVAL);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "widget refresh failed", e);
                } finally {
                    // Do this even when building RemoteViews or talking to AppWidgetManager failed.
                    synchronized (WIDGET_UPDATE_LOCK) {
                        refreshAnimation = false;
                        try {
                            updateAll(app);
                        } finally {
                            REFRESHING.set(false);
                        }
                    }
                }
            }, "balance-refresh").start();
        } catch (Exception e) {
            Log.w(TAG, "widget refresh could not start", e);
            if (refreshing) {
                synchronized (WIDGET_UPDATE_LOCK) {
                    refreshAnimation = false;
                    REFRESHING.set(false);
                }
            }
            updateAll(context);
        }
    }

    /** Renders the widget again from saved balances; called by the app after its own refresh. */
    static void push(Context context) {
        updateAll(context);
    }

    static void updateAll(Context context) {
        synchronized (WIDGET_UPDATE_LOCK) {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class));
                if (ids.length == 0) return;
                RemoteViews views = buildViews(context);
                boolean locked = widgetLocked(context);
                if (locked) {
                    views = lockedViews(context);
                    // Never target the refresh button after a locked layout replaces the normal one.
                    refreshAnimation = false;
                }
                // Push the safe frame first, even if notifying the launcher fails afterwards.
                for (int id : ids) manager.updateAppWidget(id, views);
                if (!locked) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
            } catch (Exception e) {
                refreshAnimation = false;
                Log.w(TAG, "widget update failed", e);
            }
        }
    }

    /** Re-pushes only the header (total, icons, actions) without touching the list adapter or
     *  notifying a rebind. Used to re-assert a mask toggle after the toggle-triggered rebind has
     *  settled: on some launchers the rebind completion re-displays a stale header, and a second
     *  full update plus notify would only retrigger the same cycle. */
    static void pushHeaderOnly(Context context) {
        synchronized (WIDGET_UPDATE_LOCK) {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class));
                if (ids.length == 0) return;
                RemoteViews views = buildViews(context, false);
                if (widgetLocked(context)) views = lockedViews(context);
                for (int id : ids) manager.updateAppWidget(id, views);
            } catch (Exception e) {
                refreshAnimation = false;
                Log.w(TAG, "widget update failed", e);
            }
        }
    }

    /** One bounded, refresh-local cache: at most 512 KiB of pixels, independent of scan duration. */
    static Bitmap[] refreshFrames(Context context) {
        Drawable icon = context.getDrawable(R.drawable.ic_refresh);
        int size = Math.max(1, Math.min(MAX_REFRESH_FRAME_SIZE,
            Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight())));
        icon.setBounds(0, 0, size, size);
        Bitmap[] frames = new Bitmap[REFRESH_FRAME_COUNT];
        for (int i = 0; i < frames.length; i++) {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.rotate(i * 360f / frames.length, size / 2f, size / 2f);
            icon.draw(canvas);
            frames[i] = bitmap;
        }
        return frames;
    }

    /** A fresh icon-only action list avoids accumulating bitmaps or replaying stale balance data. */
    static RemoteViews refreshFrame(Context context, Bitmap bitmap) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_balance);
        views.setImageViewBitmap(R.id.widget_refresh, bitmap);
        return views;
    }

    private static boolean applyRefreshFrame(Context context, Bitmap bitmap) {
        synchronized (WIDGET_UPDATE_LOCK) {
            if (!refreshAnimation) return false;
            try {
                if (widgetLocked(context)) {
                    refreshAnimation = false;
                    updateAll(context);
                    return false;
                }
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, BalanceWidgetProvider.class));
                if (ids.length == 0) return false;
                manager.partiallyUpdateAppWidget(ids, refreshFrame(context, bitmap));
                return true;
            } catch (Exception e) {
                refreshAnimation = false;
                Log.w(TAG, "widget refresh frame failed", e);
                return false;
            }
        }
    }

    static RemoteViews buildViews(Context context) {
        return buildViews(context, true);
    }

    /** Same header with or without the list-adapter binding. The delayed mask re-assertion
     *  below uses the adapter-free form: the adapter is already bound by the toggle's full
     *  update, and re-setting it plus another notify would only retrigger the list rebind
     *  whose completion is what clobbers the header on some launchers. */
    static RemoteViews buildViews(Context context, boolean includeAdapter) {
        Context c = WidgetTheme.context(context);
        if (widgetLocked(c)) return lockedViews(c);
        boolean hidden = BalanceData.isWidgetHidden(c);
        long total = 0;
        try {
            for (java.util.Map.Entry<String, Bank> e : BalanceData.read(c).entrySet())
                if (!BalanceData.isExcluded(c, e.getKey())) total += e.getValue().amount;
        } catch (Exception e) {
            Log.w(TAG, "widget balance store unreadable", e);
            return unreadableViews(c);
        }
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance);
        if (includeAdapter)
            views.setRemoteAdapter(R.id.widget_list, new Intent(c, BalanceWidgetService.class));
        views.setInt(R.id.widget_root, "setLayoutDirection",
            c.getResources().getConfiguration().getLayoutDirection());
        views.setInt(R.id.widget_root, "setBackgroundResource", WidgetTheme.background(c));
        // Colors go over as values, not resource ids, or the launcher resolves them against its own
        // day/night setting and the theme picked in the app is lost. See WidgetTheme.
        views.setTextColor(R.id.widget_total, ThemeColorHelper.color(c, R.color.accent));
        views.setTextColor(R.id.widget_unit, ThemeColorHelper.color(c, R.color.muted));
        views.setTextColor(R.id.widget_hint, ThemeColorHelper.color(c, R.color.muted));
        boolean smsAllowed = c.checkSelfPermission(Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED;
        views.setViewVisibility(R.id.widget_hint, smsAllowed ? View.GONE : View.VISIBLE);
        if (!smsAllowed) views.setTextViewText(R.id.widget_hint,
            c.getString(R.string.widget_permission_hint));
        views.setTextViewText(R.id.widget_total,
            hidden ? "\u2022\u2022\u2022\u2022\u2022\u2022" : CurrencyHelper.amount(c, total));
        views.setTextViewText(R.id.widget_unit, CurrencyHelper.label(c));
        views.setImageViewResource(R.id.widget_mask,
            hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        // Explicitly restore the drawable when the launcher reapplies the animated layout.
        views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh);
        views.setContentDescription(R.id.widget_mask, c.getString(R.string.widget_action_mask));
        views.setContentDescription(R.id.widget_refresh, c.getString(R.string.widget_action_refresh));
        views.setOnClickPendingIntent(R.id.widget_root, openApp(c));
        views.setOnClickPendingIntent(R.id.widget_mask, pending(c, ACTION_MASK, REQ_MASK));
        views.setOnClickPendingIntent(R.id.widget_refresh, pending(c, ACTION_REFRESH, REQ_REFRESH));
        return views;
    }

    /** The store exists but cannot be decrypted/read; never substitute a numeric zero. */
    static RemoteViews unreadableViews(Context context) {
        Context c = WidgetTheme.context(context);
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance);
        views.setRemoteAdapter(R.id.widget_list, new Intent(c, BalanceWidgetService.class));
        views.setInt(R.id.widget_root, "setLayoutDirection",
            c.getResources().getConfiguration().getLayoutDirection());
        views.setInt(R.id.widget_root, "setBackgroundResource", WidgetTheme.background(c));
        views.setTextColor(R.id.widget_total, ThemeColorHelper.color(c, R.color.accent));
        views.setTextColor(R.id.widget_unit, ThemeColorHelper.color(c, R.color.muted));
        views.setTextColor(R.id.widget_hint, ThemeColorHelper.color(c, R.color.muted));
        views.setTextViewText(R.id.widget_total, "\u2014");
        views.setTextViewText(R.id.widget_unit, "");
        views.setViewVisibility(R.id.widget_hint, View.VISIBLE);
        views.setTextViewText(R.id.widget_hint, c.getString(R.string.status_sms_unreadable));
        views.setImageViewResource(R.id.widget_mask, R.drawable.ic_visibility);
        views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_refresh);
        views.setContentDescription(R.id.widget_mask, c.getString(R.string.widget_action_mask));
        views.setContentDescription(R.id.widget_refresh, c.getString(R.string.widget_action_refresh));
        views.setOnClickPendingIntent(R.id.widget_root, openApp(c));
        views.setOnClickPendingIntent(R.id.widget_mask, pending(c, ACTION_MASK, REQ_MASK));
        views.setOnClickPendingIntent(R.id.widget_refresh, pending(c, ACTION_REFRESH, REQ_REFRESH));
        return views;
    }

    /** The locked widget: a lock glyph and a short message, still tappable to open the app. */
    static RemoteViews lockedViews(Context context) {
        Context c = WidgetTheme.context(context);
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_locked);
        views.setInt(R.id.widget_root, "setLayoutDirection",
            c.getResources().getConfiguration().getLayoutDirection());
        views.setInt(R.id.widget_root, "setBackgroundResource", WidgetTheme.background(c));
        views.setTextColor(R.id.widget_locked_msg, ThemeColorHelper.color(c, R.color.muted));
        views.setOnClickPendingIntent(R.id.widget_root, openApp(c));
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
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            cancelLegacyAlarms(context);
            am.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + REFRESH_INTERVAL,
                REFRESH_INTERVAL, alarmPending(context));
        } catch (Exception e) {
            Log.w(TAG, "widget alarm schedule failed", e);
        }
    }

    /** The widget releases v1.4.0 and v1.4.1 minted the repeating alarm at REQ_ALARM + versionCode * 1000
     *  (10,400,100 and 10,401,100), which survives an upgrade as a stale second alarm. Cancel just those
     *  fixed legacy request codes before re-arming so an upgraded install never fires the old and new
     *  alarms together. Since v1.5.0 the alarm is always minted plain at REQ_ALARM, so nothing else can
     *  leak into this app's request-code space. */
    private static void cancelLegacyAlarms(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int legacyVersion : LEGACY_ALARM_VERSION_CODES) {
            PendingIntent legacy = PendingIntent.getBroadcast(context, REQ_ALARM + legacyVersion * 1000,
                new Intent(context, BalanceWidgetProvider.class).setAction(ACTION_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(legacy);
        }
    }

    private static void cancelAlarm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(alarmPending(context));
        } catch (Exception e) {
            Log.w(TAG, "widget alarm cancel failed", e);
        }
    }

    @Override
    public void onEnabled(Context context) {
        try {
            super.onEnabled(context);
            scheduleAlarm(context);
        } catch (Exception e) {
            Log.w(TAG, "widget enable failed", e);
        }
    }

    @Override
    public void onDisabled(Context context) {
        try {
            super.onDisabled(context);
            cancelAlarm(context);
        } catch (Exception e) {
            Log.w(TAG, "widget disable failed", e);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        try {
            super.onDeleted(context, appWidgetIds);
            if (!hasWidgets(context)) cancelAlarm(context);
        } catch (Exception e) {
            Log.w(TAG, "widget delete failed", e);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        try {
            scheduleAlarm(context);
            refreshData(context);
        } catch (Exception e) {
            Log.w(TAG, "widget update callback failed", e);
        }
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
        try {
            String action = intent.getAction();
            if (action == null) return;
            super.onReceive(context, intent);
            if (ACTION_REFRESH.equals(action) || ACTION_ALARM.equals(action)) {
                if (ACTION_ALARM.equals(action)) scheduleAlarm(context);
                refreshData(context);
            } else if (ACTION_MASK.equals(action)) {
                // The header and the rows update through two separate mechanisms (a pushed
                // RemoteViews vs. an adapter rebind). Some launchers re-display a stale header
                // when that rebind settles, so a mask toggle re-asserts the header twice after
                // the rebind, header-only (no adapter reset, no new notify) so the re-assertion
                // itself cannot retrigger the cycle. Refresh animation ticks are stopped first
                // so none can interleave either.
                final Context app = appContext(context);
                final int toggleGen;
                synchronized (WIDGET_UPDATE_LOCK) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastMaskToggleMs < MASK_DEBOUNCE_MS) return;
                    lastMaskToggleMs = now;
                    refreshAnimation = false;
                    toggleGen = ++maskToggleGen;
                }
                boolean before = BalanceData.isWidgetHidden(app);
                BalanceData.setWidgetHidden(app, !before);
                updateAll(app);
                new Thread(() -> {
                    SystemClock.sleep(MASK_REASSERT_DELAY_MS);
                    synchronized (WIDGET_UPDATE_LOCK) {
                        if (toggleGen != maskToggleGen) return;
                    }
                    pushHeaderOnly(app);
                    SystemClock.sleep(MASK_REASSERT_DELAY_MS_2);
                    synchronized (WIDGET_UPDATE_LOCK) {
                        if (toggleGen != maskToggleGen) return;
                    }
                    pushHeaderOnly(app);
                }, "balance-widget-mask").start();
            } else if (ACTION_BOOT_COMPLETED.equals(action) && hasWidgets(context)) {
                scheduleAlarm(context);
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action) && hasWidgets(context)) {
                updateAll(context);
            }
        } catch (Exception e) {
            Log.w(TAG, "widget receiver failed", e);
            updateAll(context);
        }
    }
}
