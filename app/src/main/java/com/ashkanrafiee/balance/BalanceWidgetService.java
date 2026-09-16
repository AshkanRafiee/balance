package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BalanceWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    /** The ordered bank list the widget shows: only included banks, in exactly the same order the main
     *  app shows them (persisted sort mode), with excluded banks dropped entirely so the widget stays
     *  a glanceable summary of the total. */
    static List<Bank> widgetBanks(Context context) {
        Context c = LocaleHelper.wrap(context);
        Set<String> excluded = BalanceData.getExcluded(c);
        List<Bank> ordered = new ArrayList<>(BalanceData.orderForDisplay(
            BalanceData.read(c), excluded, BalanceData.getSort(c)));
        List<Bank> included = new ArrayList<>();
        for (Bank b : ordered) if (!excluded.contains(b.name)) included.add(b);
        return included;
    }

    private static final class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private volatile List<Bank> banks = new ArrayList<>();
        private volatile boolean locked;

        Factory(Context context) { this.context = context; }

        @Override public void onCreate() { }

        @Override public void onDataSetChanged() {
            locked = LockManager.isEnabled(context);
            banks = widgetBanks(context);
        }

        @Override public int getCount() {
            if (locked) return 1;
            return banks.isEmpty() ? 1 : banks.size();
        }

        @Override public RemoteViews getViewAt(int i) {
            if (locked) return lockedViews();
            List<Bank> snapshot = banks;
            if (snapshot.isEmpty() || i < 0 || i >= snapshot.size()) return emptyViews();
            return bankViews(snapshot.get(i));
        }

        private RemoteViews lockedViews() {
            Context c = LocaleHelper.wrap(context);
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_locked);
            views.setInt(R.id.widget_root, "setLayoutDirection",
                c.getResources().getConfiguration().getLayoutDirection());
            views.setOnClickPendingIntent(R.id.widget_root, BalanceWidgetProvider.openApp(c));
            return views;
        }

        private RemoteViews bankViews(Bank b) {
            Context c = LocaleHelper.wrap(context);
            int dir = c.getResources().getConfiguration().getLayoutDirection();
            boolean hidden = BalanceData.isHidden(c);
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_item);
            views.setInt(R.id.widget_item_root, "setLayoutDirection", dir);
            views.setTextViewText(R.id.bank_name, BankRules.displayName(c, b.name));
            views.setTextViewText(R.id.bank_amount,
                hidden ? "\u2022\u2022\u2022\u2022\u2022\u2022" : BalanceData.toman(c, b.amount));
            views.setInt(R.id.bank_name, "setGravity", Gravity.CENTER_VERTICAL | Gravity.START);
            views.setInt(R.id.bank_amount, "setGravity", Gravity.CENTER_VERTICAL | Gravity.END);
            int iconRes = BankIcon.iconFor(b.name);
            views.setViewVisibility(R.id.bank_icon, iconRes != 0 ? View.VISIBLE : View.GONE);
            if (iconRes != 0) views.setImageViewResource(R.id.bank_icon, iconRes);
            views.setOnClickPendingIntent(R.id.widget_item_root, BalanceWidgetProvider.openApp(c));
            return views;
        }

        private RemoteViews emptyViews() {
            Context c = LocaleHelper.wrap(context);
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_empty);
            views.setInt(R.id.widget_empty, "setLayoutDirection",
                c.getResources().getConfiguration().getLayoutDirection());
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            views.setOnClickPendingIntent(R.id.widget_empty, BalanceWidgetProvider.openApp(c));
            return views;
        }

        @Override public long getItemId(int i) { return i; }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 3; }

        @Override public boolean hasStableIds() { return false; }

        @Override public void onDestroy() { }
    }
}