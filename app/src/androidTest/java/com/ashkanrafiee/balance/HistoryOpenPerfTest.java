package com.ashkanrafiee.balance;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** TEMPORARY diagnostic: where the fixed cost of opening the history screen goes. */
@RunWith(AndroidJUnit4.class)
public class HistoryOpenPerfTest {

    @Test public void measureOpen_againstRowCount() throws Exception {
        int[] sizes = {0, 70, 300, 1500};
        for (int n : sizes) {
            seed(n);
            long open = openAndAwaitSettled();
            System.out.println("PERF rows=" + n + " openMs=" + open);
        }
    }

    private android.content.Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void seed(int n) {
        List<Transaction> txs = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -14);
        for (int i = 0; i < n; i++) {
            c.add(Calendar.HOUR_OF_DAY, 20);
            txs.add(new Transaction("Tejarat", "10001", c.getTimeInMillis(),
                (i % 2 == 0 ? 1 : -1) * (100000L + i * 1000L), "sig" + i, "content" + i));
        }
        BalanceData.writeTransactions(ctx(), txs);
    }

    /** Milliseconds until the view tree stops growing. */
    private long openAndAwaitSettled() throws Exception {
        Intent i = new Intent(ctx(), HistoryActivity.class)
            .putExtra(HistoryActivity.EXTRA_BANK, "Tejarat")
            .putExtra(HistoryActivity.EXTRA_ACCOUNT, "10001")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        long t0 = System.nanoTime();
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        int last = -1, stable = 0;
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline && stable < 5) {
            int seen = viewCount();
            if (seen == last && seen > 0) stable++; else stable = 0;
            last = seen;
            Thread.sleep(25);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) a.finish();
            }
        });
        Thread.sleep(300);
        return ms;
    }

    private int viewCount() {
        AtomicInteger out = new AtomicInteger(0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) out.set(countViews(a.getWindow().getDecorView()));
            }
        });
        return out.get();
    }

    private int countViews(View v) {
        if (!(v instanceof ViewGroup)) return 1;
        ViewGroup g = (ViewGroup) v;
        int n = 0;
        for (int i = 0; i < g.getChildCount(); i++) n += countViews(g.getChildAt(i));
        return n + 1;
    }
}
