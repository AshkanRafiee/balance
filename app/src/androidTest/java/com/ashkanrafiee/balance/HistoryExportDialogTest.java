package com.ashkanrafiee.balance;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Drives {@code HistoryActivity.writeExport} end to end. The worker's stage/progress callbacks
 *  must reach the dialog's own live views: looking them up through the dialog before it is shown
 *  missed, and the export died with a NullPointerException on its very first callback instead of
 *  ever showing progress. A file destination in the cache directory keeps the system file picker
 *  out of the test. */
@RunWith(AndroidJUnit4.class)
public class HistoryExportDialogTest {
    private Context ctx;

    @Before public void setUp() {
        finishAnyResumedHistory();
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        finishAnyResumedHistory();
        BalanceData.reset(ctx, true);
    }

    @Test public void writeExport_writesCsvThroughLiveProgressViews() throws Exception {
        List<Transaction> txs = new ArrayList<>();
        long now = System.currentTimeMillis();
        txs.add(new Transaction("bank_melli", "acc", now - 1000, 10_000L, "s1", "c1"));
        txs.add(new Transaction("bank_melli", "acc", now, -5_000L, "s2", "c2"));
        BalanceData.writeTransactions(ctx, txs);

        Intent i = new Intent(ctx, HistoryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);

        File dest = new File(ctx.getCacheDir(), "export-dialog-test.csv");
        if (dest.exists() && !dest.delete()) fail("stale export destination");
        try {
            runWriteExport(history(), Uri.fromFile(dest));
            // The stage callback posts to the main thread against the dialog's views; a missed
            // lookup crashed the process here instead of writing anything.
            await(() -> dest.exists() && dest.length() > 0, 30_000);
            String csv = readFully(dest);
            assertTrue(csv.contains("bank"));
            assertTrue(csv.contains("10000"));
            assertTrue(csv.contains("-5000"));
        } finally {
            dest.delete();
        }
    }

    /** Invokes the private export entry point on the main thread, as the file picker result does. */
    private static void runWriteExport(final Activity activity, final Uri uri) throws Exception {
        final Exception[] failure = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                java.lang.reflect.Method write =
                    HistoryActivity.class.getDeclaredMethod("writeExport", Uri.class);
                write.setAccessible(true);
                write.invoke(activity, uri);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                failure[0] = cause instanceof Exception
                    ? (Exception) cause : new RuntimeException(cause);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    private static HistoryActivity history() {
        final HistoryActivity[] found = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) found[0] = (HistoryActivity) a;
            }
        });
        assertTrue("history must be on screen", found[0] != null);
        return found[0];
    }

    private static String readFully(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void finishAnyResumedHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (a instanceof HistoryActivity) a.finish();
            }
        });
    }

    private static void await(Callable<Boolean> done, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (done.call()) return; } catch (Exception ignored) { }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for the exported file");
    }
}
