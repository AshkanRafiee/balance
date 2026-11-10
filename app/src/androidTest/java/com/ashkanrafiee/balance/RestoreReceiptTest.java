package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A restore of unrelated data must not turn a lost source store into a fresh install. */
@RunWith(AndroidJUnit4.class)
public class RestoreReceiptTest {
    private static final String PASSWORD = "correct horse battery staple";
    private static final String RECEIPT = "balance_sources.present";
    private static final long T = 1_700_000_000_000L;

    private Context context;
    private final List<File> backups = new ArrayList<>();

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        try {
            BalanceData.reset(context, true);
        } finally {
            DataGeneration.clearForTests(context);
            for (File backup : backups) backup.delete();
        }
    }

    @Test public void framedRestore_balancesPreserveMissingSourceReceipt() throws Exception {
        assertBalancesRestorePreservesReceipt(true);
    }

    @Test public void legacyRestore_balancesPreserveMissingSourceReceipt() throws Exception {
        assertBalancesRestorePreservesReceipt(false);
    }

    @Test public void framedRestore_withSourcesRejectsMissingLocalStoreAtomically()
            throws Exception {
        BalanceData.write(context, incomingBalances());
        assertNotNull(SourceStore.capture(context, "Refah", "backup source", T,
            BankRules.VERSION));
        File backup = backupFile("restore-receipt-sources.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);
        prepareMissingLocalSources();
        Context before = DataGeneration.context(context);
        assertFalse(DataGeneration.manifestFileForTests(context).exists());

        try {
            BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
            fail("restoring source rows must not silently replace a missing local source store");
        } catch (IllegalStateException expected) {
            assertEquals("source database is missing", expected.getMessage());
        }

        assertEquals(before.getNoBackupFilesDir(),
            DataGeneration.context(context).getNoBackupFilesDir());
        assertFalse("a failed merge must not publish a selector",
            DataGeneration.manifestFileForTests(context).exists());
        assertTrue("the failed restore must discard its generation",
            DataGeneration.generationNamesForTests(context).isEmpty());
        LinkedHashMap<String, Bank> balances = BalanceData.read(context);
        assertEquals(2, balances.size());
        assertEquals(10L, balances.get("Shared").amount);
        assertEquals(T, balances.get("Shared").date);
        assertEquals(30L, balances.get("Local").amount);
        assertFalse(balances.containsKey("Incoming"));
        assertMissingSourcesRemainClosed();
    }

    private void assertBalancesRestorePreservesReceipt(boolean framed) throws Exception {
        BalanceData.write(context, incomingBalances());
        File backup = backupFile(framed ? "restore-receipt-v2.bin" : "restore-receipt-v1.bin");
        // Both real writers use their current KDF; the backup contains no captured source rows.
        if (framed) BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        else BackupManager.create(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);
        prepareMissingLocalSources();
        File previousNoBackup = DataGeneration.context(context).getNoBackupFilesDir();

        BackupManager.RestoreResult result = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD);

        assertEquals(1, result.added);
        assertEquals(1, result.updated);
        LinkedHashMap<String, Bank> balances = BalanceData.read(context);
        assertEquals(3, balances.size());
        assertEquals(20L, balances.get("Shared").amount);
        assertEquals(T + 1, balances.get("Shared").date);
        assertEquals(40L, balances.get("Incoming").amount);
        assertEquals(30L, balances.get("Local").amount);
        assertTrue("restore must publish a generation",
            DataGeneration.manifestFileForTests(context).isFile());
        assertFalse("the receipt must be in the newly selected generation, not just the root",
            previousNoBackup.equals(DataGeneration.context(context).getNoBackupFilesDir()));
        assertMissingSourcesRemainClosed();
    }

    private void prepareMissingLocalSources() throws Exception {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Shared", new Bank("Shared", 10L, T, "sender"));
        balances.put("Local", new Bank("Local", 30L, T, "sender"));
        BalanceData.write(context, balances);
        assertNotNull(SourceStore.capture(context, "Refah", "lost local source", T,
            BankRules.VERSION));
        Context active = DataGeneration.context(context);
        assertTrue(new File(active.getNoBackupFilesDir(), RECEIPT).isFile());
        // Delete before publication: this exercises the store receipt, not the published DB inventory.
        assertTrue(active.deleteDatabase(SourceStore.DB_NAME));
        assertMissingSourcesRemainClosed();
    }

    private void assertMissingSourcesRemainClosed() throws Exception {
        Context active = DataGeneration.context(context);
        File database = active.getDatabasePath(SourceStore.DB_NAME);
        File receipt = new File(active.getNoBackupFilesDir(), RECEIPT);
        assertTrue("the selected no-backup directory must retain the receipt", receipt.isFile());
        assertEquals(1L, receipt.length());
        assertFalse(database.exists());
        try {
            SourceStore.forEachSource(context, 256, source -> fail("lost sources are not empty"));
            fail("visiting a missing source store must fail");
        } catch (IllegalStateException expected) {
            assertEquals("source database is missing", expected.getMessage());
        }
        assertFalse("a read must not recreate the database", database.exists());
        try {
            SourceStore.capture(context, "Refah", "new source", T + 2, BankRules.VERSION);
            fail("capture must not recreate a missing source store");
        } catch (IllegalStateException expected) {
            assertEquals("source database is missing", expected.getMessage());
        }
        assertFalse("a write must not recreate the database", database.exists());
        assertTrue(receipt.isFile());
    }

    private LinkedHashMap<String, Bank> incomingBalances() {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Shared", new Bank("Shared", 20L, T + 1, "sender"));
        balances.put("Incoming", new Bank("Incoming", 40L, T + 1, "sender"));
        return balances;
    }

    private File backupFile(String name) {
        File backup = new File(context.getCacheDir(), name);
        backup.delete();
        backups.add(backup);
        return backup;
    }
}
