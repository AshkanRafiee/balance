package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BalanceWidgetService extends RemoteViewsService {
    private static final String TAG = "BalanceWidget";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    /** The ordered list the widget shows: only included entries, one row per bank entry — a
     *  multi-account bank contributes a row per account, exactly as on the main screen — in the
     *  same order the app shows them (persisted sort mode), with excluded accounts dropped entirely
     *  so the widget stays a glanceable summary of the total. */
    static List<Bank> widgetBanks(Context context) {
        if (BalanceWidgetProvider.widgetLocked(context)) return new ArrayList<>();
        Context c = WidgetTheme.context(context);
        Set<String> excluded = BalanceData.getExcluded(c);
        List<Bank> included = new ArrayList<>();
        for (List<Bank> block : BalanceData.groupedForDisplay(
                BalanceData.read(c), excluded, BalanceData.getSort(c))) {
            for (Bank b : block) {
                if (excluded.contains(BalanceData.storageKey(b.name, b.account))) continue;
                included.add(new Bank(b.name, b.amount, b.date, b.sender, b.account));
            }
        }
        return included;
    }

    /** The locked list entry. Static and package-visible, like {@link #widgetBanks}, so the colors
     *  it carries can be checked by applying it the way a launcher does. */
    static RemoteViews lockedViews(Context context) {
        Context c = WidgetTheme.context(context);
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_locked);
        views.setInt(R.id.widget_root, "setLayoutDirection",
            c.getResources().getConfiguration().getLayoutDirection());
        views.setInt(R.id.widget_root, "setBackgroundResource", WidgetTheme.background(c));
        views.setTextColor(R.id.widget_locked_msg, c.getColor(R.color.muted));
        views.setOnClickPendingIntent(R.id.widget_root, BalanceWidgetProvider.openApp(c));
        return views;
    }

    /** One list row. Static and package-visible so its colors can be checked the same way. */
    static RemoteViews bankViews(Context context, Bank b) {
        Context c = WidgetTheme.context(context);
        int dir = c.getResources().getConfiguration().getLayoutDirection();
        boolean hidden = BalanceData.isWidgetHidden(c);
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_item);
        views.setInt(R.id.widget_item_root, "setLayoutDirection", dir);
        views.setTextViewText(R.id.bank_name, BankRules.displayName(c, b.name));
        views.setTextViewText(R.id.bank_amount,
            hidden ? "\u2022\u2022\u2022\u2022\u2022\u2022" : CurrencyHelper.amount(c, b.amount));
        // Literal colors, not resource ids, or the launcher re-resolves them in its own palette and
        // the app's theme stops reaching the rows. See WidgetTheme.
        views.setTextColor(R.id.bank_name, c.getColor(R.color.bank_name));
        views.setTextColor(R.id.bank_account, c.getColor(R.color.muted));
        views.setTextColor(R.id.bank_amount, c.getColor(R.color.muted));
        if (BalanceData.isStale(c, b.date) && !hidden)
            views.setTextColor(R.id.bank_amount, c.getColor(R.color.warn));
        if (b.account != null) {
            views.setTextViewText(R.id.bank_account, hidden
                ? "\u2022\u2022\u2022\u2022\u2022\u2022" : accountLabel(c, b.account));
            views.setViewVisibility(R.id.bank_account, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.bank_account, View.GONE);
        }
        int iconRes = BankIcon.iconFor(b.name);
        views.setViewVisibility(R.id.bank_icon, iconRes != 0 ? View.VISIBLE : View.GONE);
        if (iconRes != 0) views.setImageViewResource(R.id.bank_icon, iconRes);
        views.setOnClickPendingIntent(R.id.widget_item_root, BalanceWidgetProvider.openApp(c));
        return views;
    }

    /** "Account 30101…" with the digits in the app's language, matching the app's cards. */
    private static String accountLabel(Context c, String account) {
        String digits = LocaleHelper.isPersian(c)
                ? HistoryActivity.faDigitsString(account) : account;
        return c.getString(R.string.account_label) + " " + digits;
    }

    /** The "no balances yet" row. */
    static RemoteViews emptyViews(Context context) {
        return messageViews(context, R.string.widget_empty);
    }

    /** The list counterpart of the summary's unknown total. */
    static RemoteViews unreadableViews(Context context) {
        return messageViews(context, R.string.status_sms_unreadable);
    }

    private static RemoteViews messageViews(Context context, int message) {
        Context c = WidgetTheme.context(context);
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_empty);
        views.setInt(R.id.widget_empty, "setLayoutDirection",
            c.getResources().getConfiguration().getLayoutDirection());
        views.setTextColor(R.id.widget_empty, c.getColor(R.color.empty));
        views.setTextViewText(R.id.widget_empty, c.getString(message));
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
        views.setOnClickPendingIntent(R.id.widget_empty, BalanceWidgetProvider.openApp(c));
        return views;
    }

    static final class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private List<Bank> banks = new ArrayList<>();
        private boolean locked = true;
        private boolean unreadable;

        Factory(Context context) {
            Context app = context.getApplicationContext();
            this.context = app == null ? context : app;
        }

        @Override public void onCreate() { }

        @Override public synchronized void onDataSetChanged() {
            // Drop the previous snapshot even when loading the replacement fails.
            banks = new ArrayList<>();
            unreadable = false;
            locked = BalanceWidgetProvider.widgetLocked(context);
            if (locked) return;
            try {
                banks = widgetBanks(context);
            } catch (Exception e) {
                Log.w(TAG, "widget dataset load failed", e);
                unreadable = true;
            }
        }

        @Override public synchronized int getCount() {
            if (locked) return 1;
            return banks.isEmpty() ? 1 : banks.size();
        }

        @Override public synchronized RemoteViews getViewAt(int i) {
            try {
                if (locked || BalanceWidgetProvider.widgetLocked(context)) return lockedViews(context);
                if (unreadable) return unreadableViews(context);
                List<Bank> snapshot = banks;
                if (snapshot.isEmpty() || i < 0 || i >= snapshot.size()) return emptyViews(context);
                return bankViews(context, snapshot.get(i));
            } catch (Exception e) {
                Log.w(TAG, "widget row build failed", e);
                // A null row is mishandled by some launchers; the error row is always safe.
                return unreadableViews(context);
            }
        }

        @Override public long getItemId(int i) { return i; }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 3; }

        @Override public boolean hasStableIds() { return false; }

        @Override public void onDestroy() { }
    }
}
