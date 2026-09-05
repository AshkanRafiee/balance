package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;

public class BalanceWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    private static final class Factory implements RemoteViewsService.RemoteViewsFactory {
        private static final int TYPE_BANK = 0, TYPE_EMPTY = 1;
        private final Context context;
        private volatile List<Bank> banks = new ArrayList<>();

        Factory(Context context) { this.context = context; }

        @Override public void onCreate() { }

        @Override public void onDataSetChanged() {
            banks = new ArrayList<>(BalanceData.read(LocaleHelper.wrap(context)).values());
        }

        @Override public int getCount() {
            return banks.isEmpty() ? 1 : banks.size();
        }

        @Override public RemoteViews getViewAt(int i) {
            List<Bank> snapshot = banks;
            if (snapshot.isEmpty() || i < 0 || i >= snapshot.size()) return emptyViews();
            return bankViews(snapshot.get(i));
        }

        private RemoteViews bankViews(Bank b) {
            Context c = LocaleHelper.wrap(context);
            int dir = c.getResources().getConfiguration().getLayoutDirection();
            boolean rtl = dir == View.LAYOUT_DIRECTION_RTL;
            boolean hidden = BalanceData.isHidden(c);
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_item);
            views.setInt(R.id.widget_item_root, "setLayoutDirection", dir);
            views.setTextViewText(R.id.bank_name, BankRules.displayName(c, b.name));
            views.setTextViewText(R.id.bank_amount,
                hidden ? "\u2022\u2022\u2022\u2022\u2022\u2022" : BalanceData.toman(c, b.amount));
            views.setInt(R.id.bank_name, "setGravity", Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
            views.setInt(R.id.bank_amount, "setGravity", Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT));
            views.setOnClickPendingIntent(R.id.widget_item_root,
                BalanceWidgetProvider.pending(c, BalanceWidgetProvider.ACTION_TAP, BalanceWidgetProvider.REQ_ITEM_TAP));
            return views;
        }

        private RemoteViews emptyViews() {
            Context c = LocaleHelper.wrap(context);
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_balance_empty);
            views.setInt(R.id.widget_empty, "setLayoutDirection",
                c.getResources().getConfiguration().getLayoutDirection());
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            views.setOnClickPendingIntent(R.id.widget_empty,
                BalanceWidgetProvider.pending(c, BalanceWidgetProvider.ACTION_TAP, BalanceWidgetProvider.REQ_ITEM_TAP));
            return views;
        }

        @Override public long getItemId(int i) { return i; }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 2; }

        @Override public boolean hasStableIds() { return false; }

        @Override public void onDestroy() { }
    }
}