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
        HistoryActivity.exportDoneHookForTest = null;
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocaleHelper.setLanguage(ctx, "en");
        CurrencyHelper.setCurrency(ctx, CurrencyHelper.CURRENCY_RIAL);
    }

    @After public void tearDown() {
        HistoryActivity.exportDoneHookForTest = null;
        finishAnyResumedHistory();
        BalanceData.reset(ctx, true);
    }

    /** Runs one export and joins its worker: the completion hook fires after the file is flushed,
     *  so no worker (and no staging file) is ever left running into the next test. */
    private static void exportAndJoin(HistoryActivity activity, Uri uri) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        HistoryActivity.exportDoneHookForTest = done::countDown;
        try {
            runWriteExport(activity, uri);
            assertTrue("export worker did not finish",
                done.await(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            HistoryActivity.exportDoneHookForTest = null;
        }
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
            // The stage callback posts to the main thread against the dialog's views; a missed
            // lookup crashed the process here instead of writing anything.
            exportAndJoin(history(), Uri.fromFile(dest));
            String csv = readFully(dest);
            assertTrue(csv.contains("bank"));
            assertTrue(csv.contains("10000"));
            assertTrue(csv.contains("-5000"));
        } finally {
            dest.delete();
        }
    }

    @Test public void writeExport_filteredBySearch_exportsOnlyMatches() throws Exception {
        List<Transaction> txs = new ArrayList<>();
        long now = System.currentTimeMillis();
        txs.add(new Transaction("bank_melli", "acc", now - 1000, 10_000L, "s1", "c1"));
        txs.add(new Transaction("bank_melli", "acc", now, -20_000L, "s2", "c2"));
        BalanceData.writeTransactions(ctx, txs);
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        notes.put("c:c1", "groceries");
        notes.put("c:c2", "salary");
        BalanceData.writeNotes(ctx, notes);

        Intent i = new Intent(ctx, HistoryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        HistoryActivity activity = history();
        setSearchQuery(activity, "groceries");

        File dest = new File(ctx.getCacheDir(), "export-dialog-search-test.csv");
        if (dest.exists() && !dest.delete()) fail("stale export destination");
        try {
            exportAndJoin(activity, Uri.fromFile(dest));
            String csv = readFully(dest);
            assertTrue(csv.contains("10000"));
            assertTrue(!csv.contains("-20000"));
        } finally {
            dest.delete();
        }
    }

    @Test public void writeExport_destroyedMidExport_leaksNoWindow() throws Exception {
        List<Transaction> txs = new ArrayList<>();
        long now = System.currentTimeMillis();
        txs.add(new Transaction("bank_melli", "acc", now - 1000, 10_000L, "s1", "c1"));
        txs.add(new Transaction("bank_melli", "acc", now, -5_000L, "s2", "c2"));
        BalanceData.writeTransactions(ctx, txs);

        Intent i = new Intent(ctx, HistoryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        HistoryActivity activity = history();

        File dest = new File(ctx.getCacheDir(), "export-dialog-destroy-test.csv");
        if (dest.exists() && !dest.delete()) fail("stale export destination");
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        HistoryActivity.exportDoneHookForTest = done::countDown;
        try {
            runWriteExport(activity, Uri.fromFile(dest));
            // Leave while the worker is still running: the dialog must be dismissed by the
            // destroy path (a leaked window fails the run) and the late completion must skip
            // its UI instead of touching the dead screen (any such touch fails the run).
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
            // Join the headless worker: its completion fires the hook even on the stale
            // generation, so nothing is left running into the next test.
            assertTrue("export worker did not finish",
                done.await(60, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(dest.exists() && dest.length() > 0);
        } finally {
            HistoryActivity.exportDoneHookForTest = null;
            dest.delete();
        }
    }

    @Test public void writeExport_manyRows_writesEveryRow() throws Exception {
        List<Transaction> txs = new ArrayList<>();
        long now = System.currentTimeMillis();
        final int rows = 300;
        for (int k = 0; k < rows; k++) {
            txs.add(new Transaction("bank_melli", "acc-" + k, now + k, k + 1,
                "sig-" + k, "content-" + k));
        }
        BalanceData.writeTransactions(ctx, txs);

        Intent i = new Intent(ctx, HistoryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);

        File dest = new File(ctx.getCacheDir(), "export-dialog-many-test.csv");
        if (dest.exists() && !dest.delete()) fail("stale export destination");
        try {
            exportAndJoin(history(), Uri.fromFile(dest));
            // The join fires after the completion flush, so the file is final here: exactly the
            // staged rows plus the header.
            String csv = readFully(dest);
            assertTrue(csv.split("\n", -1).length == rows + 1);
            assertTrue(csv.contains(String.valueOf(rows)));
        } finally {
            dest.delete();
        }
    }

    /** Sets the private free-text query, as typing it would (without the debounce wait). */
    private static void setSearchQuery(final Activity activity, final String query)
            throws Exception {
        final Exception[] failure = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                java.lang.reflect.Field field =
                    HistoryActivity.class.getDeclaredField("searchQuery");
                field.setAccessible(true);
                field.set(activity, query);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
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
}
