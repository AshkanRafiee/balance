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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @Test public void cashClockRollbackDoesNotBlockBalanceRestore() throws Exception {
        long beforeRollback = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
        String snapshot = new JSONObject().put("countedAmount", 1000)
            .put("countedAt", beforeRollback).put("updatedAt", beforeRollback)
            .put("movements", new JSONArray()).toString();
        assertTrue(DataGeneration.dataPrefs(context).edit()
            .putString(CashStore.DATA, BalanceData.encryptStorePayload(snapshot)).commit());
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Mellat", new Bank("Mellat", 123, DATE, "bank-sender", null));
        BalanceData.write(context, balances);
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();
        CashStore.add(context, DATE, 2000);
        String localWallet = CashStore.exportData(context);

        BackupManager.RestoreResult balancesOnly = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD, null, EnumSet.of(BackupManager.Section.BALANCES));
        assertFalse(balancesOnly.cashConflict);
        assertFalse(balancesOnly.cashImported);
        assertEquals(localWallet, CashStore.exportData(context));
        assertEquals(1, balancesOnly.added);
        assertEquals(123, BalanceData.read(context).get("Mellat").amount);

        clearUserData();
        BackupManager.RestoreResult withCash = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD, null,
            EnumSet.of(BackupManager.Section.BALANCES, BackupManager.Section.CASH));
        assertTrue(withCash.cashConflict);
        assertFalse(withCash.cashImported);
        assertNull(CashStore.exportData(context));
        assertEquals(1, withCash.added);
        assertEquals(123, BalanceData.read(context).get("Mellat").amount);
    }

    @Test public void conflictingCashWithSameMovementIdKeepsLocalAnnotations() throws Exception {
        Transaction edited = CashStore.add(context, DATE, 1000,
            new CashStore.Annotation("backup cash note", Collections.singletonList("backup cash tag")));
        Transaction cleared = CashStore.add(context, DATE + 1, 500,
            new CashStore.Annotation("cleared cash note", Collections.singletonList("cleared cash tag")));
        Transaction bank = new Transaction("Mellat", DATE, 1000, "bank-signature");
        BalanceData.writeTransactions(context, Collections.singletonList(bank));
        BalanceData.setNote(context, bank, "backup bank note");
        BalanceData.setTags(context, bank, Collections.singletonList("backup bank tag"));
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);

        Transaction local = CashStore.update(context, edited, DATE, 2000);
        assertEquals(edited.sig, local.sig);
        BalanceData.setNote(context, local, "local cash note");
        BalanceData.setTags(context, local, Collections.singletonList("local cash tag"));
        BalanceData.setNote(context, cleared, null);
        BalanceData.setTags(context, cleared, Collections.emptyList());
        BalanceData.setNote(context, bank, null);
        BalanceData.setTags(context, bank, Collections.singletonList("local bank tag"));
        String localWallet = CashStore.exportData(context);

        BackupManager.RestoreResult conflict = restore();
        assertTrue(conflict.cashConflict);
        assertFalse(conflict.cashImported);
        assertEquals(localWallet, CashStore.exportData(context));
        assertEquals("local cash note", BalanceData.getNote(context, local));
        assertEquals(Collections.singletonList("local cash tag"), BalanceData.getTags(context, local));
        assertNull(BalanceData.getNote(context, cleared));
        assertTrue(BalanceData.getTags(context, cleared).isEmpty());
        assertEquals("backup bank note", BalanceData.getNote(context, bank));
        assertEquals(Arrays.asList("local bank tag", "backup bank tag"),
            BalanceData.getTags(context, bank));

        BackupManager.RestoreResult annotationsOnly = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD, null,
            EnumSet.of(BackupManager.Section.NOTES, BackupManager.Section.TAGS));
        assertFalse(annotationsOnly.cashConflict);
        assertFalse(annotationsOnly.cashImported);
        assertEquals(localWallet, CashStore.exportData(context));
        assertEquals("local cash note", BalanceData.getNote(context, local));
        assertEquals(Arrays.asList("local cash tag", "backup cash tag"),
            BalanceData.getTags(context, local));
        assertEquals("cleared cash note", BalanceData.getNote(context, cleared));
        assertEquals(Collections.singletonList("cleared cash tag"), BalanceData.getTags(context, cleared));
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
        JSONObject movement = new JSONObject().put("id", new UUID(0, 1).toString())
            .put("date", DATE).put("amount", 1);
        String snapshot = new JSONObject().put("countedAmount", 0).put("countedAt", 0)
            .put("updatedAt", DATE).put("movements", new JSONArray().put(movement).put(movement))
            .toString();
        writeCashPayload(new JSONArray().put(snapshot));

        try {
            restoreWithoutPublishing(EnumSet.of(BackupManager.Section.BALANCES));
            fail("Duplicate cash movement ID was accepted when Cash was unselected");
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid cash snapshot", expected.getMessage());
            assertNull(CashStore.exportData(context));
            assertTrue(BalanceData.read(context).isEmpty());
        }
    }

    @Test public void shortNonFinalCashChunkDoesNotPublishAnySection() throws Exception {
        CashStore.add(context, DATE, 1000);
        String snapshot = CashStore.exportData(context);
        writeCashPayload(new JSONArray().put(snapshot.substring(0, 1)).put(snapshot.substring(1)));
        clearUserData();

        try {
            restoreWithoutPublishing(BackupManager.allSections());
            fail("A short non-final cash chunk was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid cash snapshot chunk", expected.getMessage());
            assertNull(CashStore.exportData(context));
            assertTrue(BalanceData.read(context).isEmpty());
        }
    }

    @Test public void damagedCashBackupFooterDoesNotPublishAnySection() throws Exception {
        CashStore.add(context, DATE, 1000,
            new CashStore.Annotation("backup cash note", Collections.singletonList("backup tag")));
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Mellat", new Bank("Mellat", 123, DATE, "bank-sender", null));
        BalanceData.write(context, balances);
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        clearUserData();
        balances.clear();
        balances.put("Melli", new Bank("Melli", 456, DATE, "local-sender", null));
        BalanceData.write(context, balances);
        try (RandomAccessFile file = new RandomAccessFile(backup, "rw")) {
            long offset = file.length() - 1;
            file.seek(offset);
            int lastByte = file.readUnsignedByte();
            file.seek(offset);
            file.writeByte(lastByte ^ 1);
        }

        try {
            restoreWithoutPublishing(BackupManager.allSections());
            fail("A damaged cash backup footer was accepted");
        } catch (BackupManager.BackupException expected) {
            assertEquals(R.string.backup_error_password, expected.resId);
            assertEquals(Collections.singleton("Melli"), BalanceData.read(context).keySet());
            assertEquals(456, BalanceData.read(context).get("Melli").amount);
            assertNull(CashStore.exportData(context));
            assertTrue(BalanceData.readNotes(context).isEmpty());
            assertTrue(BalanceData.readTags(context).isEmpty());
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

    private void writeCashPayload(JSONArray chunks) throws Exception {
        String payload = "{\"schema\":2,\"balances\":[{\"key\":\"Mellat\",\"name\":\"Mellat\","
            + "\"amount\":123,\"date\":" + DATE + ",\"sender\":\"bank-sender\"}],"
            + "\"transactions\":[],\"metadata\":{\"notes\":[],\"reasons\":[],"
            + "\"channels\":[],\"tags\":[]},\"commitments\":[],\"cash\":" + chunks + "}";
        try (FileOutputStream out = new FileOutputStream(backup)) {
            BackupFrames.write(stream -> stream.write(payload.getBytes(StandardCharsets.UTF_8)),
                out, PASSWORD.toCharArray());
        }
    }

    private void restoreWithoutPublishing(EnumSet<BackupManager.Section> selection) throws Exception {
        BackupV2Restore.setPublishHookForTests(point -> fail("Invalid backup reached publication"));
        try {
            BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null, selection);
        } finally {
            BackupV2Restore.setPublishHookForTests(null);
        }
    }

    private void clearUserData() {
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
        CashStoreTest.clear(context);
    }
}
