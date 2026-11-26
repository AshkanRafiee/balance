package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CashBackupTest {
    private static final String PASSWORD = "portable cash backup test";
    private static final long DATE = 1_700_000_000_000L;
    private Context context;
    private File backup;
    private boolean cashWasEnabled;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cashWasEnabled = CashStore.isEnabled(context);
        clearUserData();
        backup = File.createTempFile("balance-cash-test-", ".bak", context.getCacheDir());
    }

    @After public void tearDown() {
        clearUserData();
        CashStore.setEnabled(context, cashWasEnabled);
        if (backup != null) backup.delete();
    }

    @Test public void wholeRestorePreservesLedgerAndAnnotationsAndCanBeRepeated()
            throws Exception {
        CashStore.setBalance(context, 2000);
        Transaction entry = CashStore.add(context, DATE, 1000,
            new CashStore.Annotation("یادداشت خرید", Collections.singletonList("groceries")));
        String wallet = CashStore.exportData(context);
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();
        CashStore.setEnabled(context, false);

        BackupManager.RestoreResult imported = restore();
        assertTrue(imported.cashImported);
        assertTrue(imported.changed());
        assertFalse(imported.cashConflict);
        assertFalse(CashStore.isEnabled(context));
        assertEquals(wallet, CashStore.exportData(context));
        Transaction restored = CashStore.read(context).transactions.get(0);
        assertEquals(entry.sig, restored.sig);
        assertEquals("یادداشت خرید", BalanceData.getNote(context, restored));
        assertEquals(Collections.singletonList("groceries"), BalanceData.getTags(context, restored));
        assertTrue(BalanceData.readTransactions(context).isEmpty());

        BackupManager.RestoreResult repeated = restore();
        assertFalse(repeated.cashImported);
        assertFalse(repeated.cashConflict);
        assertFalse(repeated.changed());
    }

    @Test public void cashConflictDoesNotBlockAnotherSelectedSection() throws Exception {
        CashStore.add(context, DATE, 1000);
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Mellat", new Bank("Mellat", 123, DATE, "bank-sender", null));
        BalanceData.write(context, balances);
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();
        CashStore.add(context, DATE, 2000);
        String localWallet = CashStore.exportData(context);

        BackupManager.RestoreResult result = restore();
        assertTrue(result.cashConflict);
        assertFalse(result.cashImported);
        assertEquals(localWallet, CashStore.exportData(context));
        assertEquals(1, result.added);
        assertEquals(123, BalanceData.read(context).get("Mellat").amount);
    }

    @Test public void snapshotSpanningMultipleChunksIsRestoredWhole() throws Exception {
        JSONArray movements = new JSONArray();
        for (int i = 0; i < 200; i++) {
            movements.put(new JSONObject().put("id", new UUID(0, i).toString())
                .put("date", DATE + i).put("amount", 1));
        }
        String fixture = new JSONObject().put("countedAmount", 0).put("countedAt", 0)
            .put("updatedAt", DATE + movements.length()).put("movements", movements).toString();
        try (DataGeneration.Stage generation = DataGeneration.beginStage(context)) {
            assertEquals(CashStore.RestoreResult.IMPORTED,
                CashStore.restoreData(generation.context(), fixture));
            generation.publish();
        }
        String wallet = CashStore.exportData(context);
        assertTrue(wallet.length() > BackupManager.CASH_CHUNK_CHARS);
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();

        assertTrue(restore().cashImported);
        assertEquals(wallet, CashStore.exportData(context));
    }

    @Test public void cashCanBeSkippedAndAnOlderBackupDoesNotEraseIt() throws Exception {
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        CashStore.add(context, DATE, 1200);
        String localWallet = CashStore.exportData(context);
        BackupManager.RestoreResult old = restore();
        assertFalse(old.cashImported);
        assertFalse(old.cashConflict);
        assertEquals(localWallet, CashStore.exportData(context));

        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();
        BackupManager.RestoreResult skipped = BackupManager.restore(context, Uri.fromFile(backup),
            PASSWORD, null, EnumSet.of(BackupManager.Section.BALANCES));
        assertFalse(skipped.cashImported);
        assertFalse(skipped.cashConflict);
        assertNull(CashStore.exportData(context));
    }

    @Test public void malformedCashDoesNotPublishAnySection() throws Exception {
        String payload = "{\"schema\":2,\"balances\":[{\"key\":\"Mellat\",\"name\":\"Mellat\","
            + "\"amount\":123,\"date\":" + DATE + ",\"sender\":\"bank-sender\"}],"
            + "\"transactions\":[],\"metadata\":{\"notes\":[],\"reasons\":[],"
            + "\"channels\":[],\"tags\":[]},\"commitments\":[],"
            + "\"cash\":[" + JSONObject.quote("{}") + "]}";
        try (FileOutputStream out = new FileOutputStream(backup)) {
            BackupFrames.write(stream -> stream.write(payload.getBytes(StandardCharsets.UTF_8)),
                out, PASSWORD.toCharArray());
        }

        try {
            BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null,
                EnumSet.of(BackupManager.Section.BALANCES));
            fail("Malformed cash snapshot was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid cash snapshot", expected.getMessage());
            assertNull(CashStore.exportData(context));
            assertTrue(BalanceData.read(context).isEmpty());
        }
    }

    @Test public void interruptedPublicationRollsBackOnlySelectedCashImport() throws Exception {
        Transaction entry = CashStore.add(context, DATE, 1000);
        BalanceData.setNote(context, entry, "not selected");
        String wallet = CashStore.exportData(context);
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();
        Transaction deleted = CashStore.add(context, DATE, 500);
        assertTrue(CashStore.remove(context, deleted));
        assertNull(CashStore.exportData(context));
        EnumSet<BackupManager.Section> selection = EnumSet.of(BackupManager.Section.CASH);

        BackupV2Restore.setPublishHookForTests(point -> {
            if (point == DataGeneration.PublishPoint.BEFORE_MANIFEST)
                throw new IOException("injected cash publication failure");
        });
        try {
            BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null, selection);
            fail("Cash restore should not publish");
        } catch (IOException expected) {
            assertNull(CashStore.exportData(context));
        } finally {
            BackupV2Restore.setPublishHookForTests(null);
        }

        assertTrue(BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null,
            selection).cashImported);
        assertEquals(wallet, CashStore.exportData(context));
        assertNull(BalanceData.getNote(context, entry));
    }

    private BackupManager.RestoreResult restore() throws Exception {
        return BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
    }

    private void clearUserData() {
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
        CashStoreTest.clear(context);
    }
}
