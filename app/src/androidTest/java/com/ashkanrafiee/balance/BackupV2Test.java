package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration coverage for authenticated framed restore and its isolated merge generation. */
@RunWith(AndroidJUnit4.class)
public class BackupV2Test {
    private static final String PASSWORD = "correct horse battery staple";
    private static final long DAY = 86_400_000L;
    private static final long T = 1_700_000_000_000L;

    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DataGeneration.clearForTests(context);
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
        DataGeneration.clearForTests(context);
    }

    @Test public void roundTrip_preservesEveryFieldAndLargeIndividualMetadataValues()
            throws Exception {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Mellat|1110000222", new Bank("Mellat", -12_345L, T + 3_000,
            "bank-sender", "1110000222"));
        BalanceData.write(context, balances);

        Transaction transaction = new Transaction("Mellat", "1110000222", T + 4_000,
            -500_000L, 1_234_567_890L, "signature", "content-digest");
        BalanceData.writeTransactions(context, Collections.singletonList(transaction));
        String key = BalanceData.noteKey(transaction);
        String largeNote = repeated('n', 70_000);
        String largeTag = repeated('t', 70_000);
        BalanceData.setNote(context, transaction, largeNote);
        BalanceData.mergeReasons(context, Collections.singletonMap(key, "bank reason"));
        BalanceData.mergeChannels(context, Collections.singletonMap(key, "mobile channel"));
        BalanceData.setTags(context, transaction, Arrays.asList("first tag", largeTag));

        List<Long> paid = Arrays.asList(T + DAY, T + 2 * DAY);
        List<Long> unpaid = Collections.singletonList(T + 3 * DAY);
        Commitment recurring = new Commitment("rent-id", "Monthly rent", -1_000L,
            Commitment.MONTHLY, T, T + 120 * DAY, false, paid, T + DAY, unpaid, true,
            3_600_000L);
        Commitment once = new Commitment("once-id", "One-time bill", 99L, Commitment.ONCE,
            T, null, true, Collections.emptyList(), 0, Collections.emptyList(), false, 0);
        BalanceData.writeCommitments(context, Arrays.asList(recurring, once));

        File backup = file("v2-roundtrip.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);

        BackupManager.RestoreResult result = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD);
        assertEquals(1, result.added);
        assertEquals(1, result.transactionsAdded);
        assertEquals(2, result.commitmentsAdded);

        Bank restoredBank = BalanceData.read(context).get("Mellat|1110000222");
        assertNotNull(restoredBank);
        assertEquals("Mellat", restoredBank.name);
        assertEquals("1110000222", restoredBank.account);
        assertEquals(-12_345L, restoredBank.amount);
        assertEquals(T + 3_000, restoredBank.date);
        assertEquals("bank-sender", restoredBank.sender);

        Transaction restoredTransaction = BalanceData.readTransactions(context).get(0);
        assertEquals("1110000222", restoredTransaction.account);
        assertEquals(-500_000L, restoredTransaction.amount);
        assertEquals(Long.valueOf(1_234_567_890L), restoredTransaction.balance);
        assertEquals("signature", restoredTransaction.sig);
        assertEquals("content-digest", restoredTransaction.content);
        assertEquals(largeNote, BalanceData.getNote(context, restoredTransaction));
        assertEquals("bank reason", BalanceData.readReasons(context).get(key));
        assertEquals("mobile channel", BalanceData.readChannels(context).get(key));
        assertEquals(Arrays.asList("first tag", largeTag),
            BalanceData.getTags(context, restoredTransaction));

        List<Commitment> commitments = BalanceData.readCommitments(context);
        assertEquals(2, commitments.size());
        Commitment restoredRecurring = commitments.get(0);
        assertEquals("rent-id", restoredRecurring.id);
        assertEquals("Monthly rent", restoredRecurring.name);
        assertEquals(-1_000L, restoredRecurring.amount);
        assertEquals(Commitment.MONTHLY, restoredRecurring.frequency);
        assertEquals(T, restoredRecurring.start);
        assertEquals(Long.valueOf(T + 120 * DAY), restoredRecurring.end);
        assertTrue(restoredRecurring.remind);
        assertEquals(3_600_000L, restoredRecurring.remindBeforeMs);
        assertEquals(T + DAY, restoredRecurring.legacyPaidThrough);
        assertTrue(restoredRecurring.paid.contains(T + 2 * DAY));
        assertTrue(restoredRecurring.unpaid.contains(T + 3 * DAY));
        assertTrue(commitments.get(1).done);
    }

    @Test public void newFramedPayloadCarriesNumericProducerVersionCode() throws Exception {
        File backup = file("v2-versioned-payload.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);

        try (java.io.FileInputStream raw = new java.io.FileInputStream(backup);
                BackupFrames.AuthenticatedInputStream decrypted = BackupFrames.openInputStream(raw,
                    PASSWORD.toCharArray());
                java.io.InputStreamReader reader = new java.io.InputStreamReader(decrypted,
                    StandardCharsets.UTF_8)) {
            org.json.JSONObject payload = new org.json.JSONObject(
                readAll(reader));
            assertEquals(BackupManager.appVersionCode(context),
                payload.getInt("appVersionCode"));
        }
    }

    @Test public void restore_isUncappedForCommitmentsAndSettlementEvents() throws Exception {
        List<Commitment> source = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            List<Long> paid = new ArrayList<>();
            for (int j = 0; j < 5; j++) paid.add(T + i * 10 * DAY + j * DAY + 1);
            source.add(new Commitment("large-" + i, "Definition " + i, -1L,
                Commitment.DAILY, T + i * 10 * DAY, null, false, paid, 0,
                Collections.emptyList(), false, 0));
        }
        BalanceData.writeCommitments(context, source);
        File backup = file("v2-large.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);

        BackupManager.RestoreResult result = BackupManager.restore(context,
            Uri.fromFile(backup), PASSWORD);
        assertEquals(501, result.commitmentsAdded);
        assertEquals(501, BalanceData.readCommitments(context).size());

        final long[] settlements = {0};
        CommitmentStore.forEachDefinition(context, definition ->
            CommitmentStore.forEachSettlement(context, definition.id, 256,
                settlement -> settlements[0]++));
        assertEquals(2_505L, settlements[0]);
    }

    @Test public void restore_remapsAllMetadataKindsThroughIncomingTransactionAliases()
            throws Exception {
        // The backup row has the legacy identity key. The local row is the same movement after a
        // parser revision added a content digest, so TransactionStore reports a metadata alias.
        Transaction backupTransaction = new Transaction("AliasBank", "account", T + 10,
            -42L, null, null, null);
        BalanceData.writeTransactions(context, Collections.singletonList(backupTransaction));
        String incomingKey = BalanceData.noteKey(backupTransaction);
        BalanceData.setNote(context, backupTransaction, "backup note");
        BalanceData.mergeReasons(context, Collections.singletonMap(incomingKey, "backup reason"));
        BalanceData.mergeChannels(context, Collections.singletonMap(incomingKey, "backup channel"));
        BalanceData.setTags(context, backupTransaction, Collections.singletonList("backup tag"));
        File backup = file("v2-alias.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);

        BalanceData.reset(context, true);
        Transaction localTransaction = new Transaction("AliasBank", "account", T + 10,
            -42L, null, null, "new-content-digest");
        BalanceData.writeTransactions(context, Collections.singletonList(localTransaction));
        String localKey = BalanceData.noteKey(localTransaction);
        BalanceData.setNote(context, localTransaction, "local note");
        BalanceData.mergeReasons(context, Collections.singletonMap(localKey, "local reason"));
        BalanceData.mergeChannels(context, Collections.singletonMap(localKey, "local channel"));
        BalanceData.setTags(context, localTransaction, Collections.singletonList("local tag"));

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertEquals("local note", BalanceData.getNote(context, localTransaction));
        assertEquals("local reason", BalanceData.readReasons(context).get(localKey));
        assertEquals("local channel", BalanceData.readChannels(context).get(localKey));
        assertEquals(Arrays.asList("local tag", "backup tag"),
            BalanceData.getTags(context, localTransaction));
        assertFalse(BalanceData.readNotes(context).containsKey(incomingKey));
        assertFalse(BalanceData.readReasons(context).containsKey(incomingKey));
        assertFalse(BalanceData.readChannels(context).containsKey(incomingKey));
    }

    @Test public void restore_unionsSettlementEventsAndPreservesLocalExplicitConflict()
            throws Exception {
        long first = T + DAY;
        long second = T + 2 * DAY;
        long third = T + 3 * DAY;
        long fourth = T + 4 * DAY;
        String id = "settlement-union";
        Commitment backupDefinition = new Commitment(id, "Union", -5L, Commitment.DAILY, T,
            null, false, Arrays.asList(first, second), 0,
            Collections.singletonList(third), false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(backupDefinition));
        File backup = file("v2-settlement-union.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);

        BalanceData.reset(context, true);
        Commitment localDefinition = new Commitment(id, "Local definition", -5L,
            Commitment.DAILY, T, null, false, Collections.singletonList(fourth), 0,
            Collections.singletonList(first), false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(localDefinition));

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertFalse(CommitmentStore.isSettled(context, id, first));
        assertTrue(CommitmentStore.isSettled(context, id, second));
        assertFalse(CommitmentStore.isSettled(context, id, third));
        assertTrue(CommitmentStore.isSettled(context, id, fourth));
        assertEquals("Local definition", CommitmentStore.get(context, id).name);
    }

    @Test public void sources_roundTripThroughFramedBackup() throws Exception {
        SourceStore.Source source = SourceStore.capture(context, "Refah", "source body", T, 7);
        assertNotNull(source);
        Transaction observed = new Transaction("Refah", "account", T + 1, -100L, 900L,
            "observed-sig", "observed-content");
        assertTrue(SourceStore.observeTransaction(context, source, 7, observed));
        assertTrue(SourceStore.observeBalance(context, source, 7, "Refah", "account", T + 1, 900L));

        assertEquals(1L, SourceStore.sourceCount(context));
        File backup = file("v2-sources.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        try (java.io.FileInputStream raw = new java.io.FileInputStream(backup);
                BackupFrames.AuthenticatedInputStream decrypted = BackupFrames.openInputStream(raw,
                    PASSWORD.toCharArray());
                java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = decrypted.read(buffer)) != -1) plain.write(buffer, 0, count);
            String json = plain.toString("UTF-8");
            assertTrue("framed backup must carry sources, got: "
                + json.substring(0, Math.min(400, json.length())), json.contains("\"sources\""));
            org.json.JSONObject payload = new org.json.JSONObject(json);
            assertEquals("backup must carry the captured source",
                1, payload.getJSONArray("sources").length());
        }
        BalanceData.reset(context, true);
        assertEquals(0L, SourceStore.sourceCount(context));

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertEquals(1L, SourceStore.sourceCount(context));
        final int[] transactions = {0};
        SourceStore.forEachTransaction(context, 256, observation -> transactions[0]++);
        assertEquals(1, transactions[0]);
        final int[] balances = {0};
        SourceStore.forEachBalance(context, 256, observation -> balances[0]++);
        assertEquals(1, balances[0]);
    }

    @Test public void restore_selectionMergesOnlySelectedSections() throws Exception {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        balances.put("Mellat", new Bank("Mellat", 5L, T, "sender"));
        BalanceData.write(context, balances);
        Transaction transaction = new Transaction("Mellat", T + 1, -1L, "selection-sig");
        BalanceData.writeTransactions(context, Collections.singletonList(transaction));
        BalanceData.setNote(context, transaction, "selection-note");
        Commitment commitment = Commitment.create("selection", -100L, Commitment.ONCE,
            Commitment.startOfDay(T), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));

        File backup = file("v2-selection.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null,
            java.util.EnumSet.of(BackupManager.Section.BALANCES));
        assertEquals(5L, (long) BalanceData.read(context).get("Mellat").amount);
        assertTrue("unselected transactions must stay untouched",
            BalanceData.readTransactions(context).isEmpty());
        assertNull(BalanceData.getNote(context, transaction));
        assertTrue(BalanceData.readCommitments(context).isEmpty());

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null,
            java.util.EnumSet.of(BackupManager.Section.TRANSACTIONS,
                BackupManager.Section.NOTES, BackupManager.Section.COMMITMENTS));
        assertEquals(1, BalanceData.readTransactions(context).size());
        assertEquals("selection-note", BalanceData.getNote(context, transaction));
        assertEquals(1, BalanceData.readCommitments(context).size());
    }

    @Test public void restore_skipsDefinitionsDeletedAfterTheBackup() throws Exception {
        Commitment commitment = Commitment.create("Doomed", -100L, Commitment.ONCE,
            Commitment.startOfDay(T), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        File backup = file("v2-deletion-skip.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);

        assertTrue(CommitmentStore.delete(context, commitment.id));
        assertTrue(BalanceData.readCommitments(context).isEmpty());

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertTrue("an older backup must not resurrect an explicit delete",
            BalanceData.readCommitments(context).isEmpty());
    }

    @Test public void deletions_travelWithBackupsUntilReset() throws Exception {
        Commitment commitment = Commitment.create("Gone", -50L, Commitment.ONCE,
            Commitment.startOfDay(T), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        assertTrue(CommitmentStore.delete(context, commitment.id));

        File backup = file("v2-deletions.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertTrue(BalanceData.readCommitments(context).isEmpty());

        // The tombstone travelled with the backup, so an even older backup — one that
        // still carries the definition — cannot resurrect it either.
        File older = file("v2-deletions-older.bin");
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        BackupManager.createFramed(context, Uri.fromFile(older), PASSWORD);
        assertTrue(CommitmentStore.delete(context, commitment.id));
        BackupManager.restore(context, Uri.fromFile(older), PASSWORD);
        assertTrue(BalanceData.readCommitments(context).isEmpty());

        // Reset is the explicit full erase: afterwards the same old backup restores again.
        BalanceData.reset(context, true);
        BackupManager.restore(context, Uri.fromFile(older), PASSWORD);
        assertEquals(1, BalanceData.readCommitments(context).size());
        assertEquals("Gone", BalanceData.readCommitments(context).get(0).name);
    }

    @Test public void restore_prunesSupersededGenerationsButKeepsData() throws Exception {
        Commitment commitment = Commitment.create("Kept", -100L, Commitment.ONCE,
            Commitment.startOfDay(T), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        File backup = file("v2-generations.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        BalanceData.reset(context, true);
        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        List<String> generations = DataGeneration.generationNamesForTests(context);
        assertTrue("only the current plus rollback generations may remain, got " + generations,
            generations.size() <= 2);
        assertEquals(1, BalanceData.readCommitments(context).size());
        assertEquals("Kept", BalanceData.readCommitments(context).get(0).name);
    }

    @Test public void restore_includeDeleted_bringsBackDeletedAndForgetsTombstone()
            throws Exception {
        Commitment commitment = Commitment.create("Back", -75L, Commitment.ONCE,
            Commitment.startOfDay(T), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        File backup = file("v2-include-deleted.bin");
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD);
        assertTrue(CommitmentStore.delete(context, commitment.id));
        assertTrue(BalanceData.readCommitments(context).isEmpty());

        // Default restores keep the deletion.
        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertTrue(BalanceData.readCommitments(context).isEmpty());

        // Opting in brings the definition back and forgets the tombstone, so a later
        // default restore of the same backup keeps it instead of deleting it again.
        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD, null,
            BackupManager.allSections(), true);
        assertEquals(1, BalanceData.readCommitments(context).size());
        assertEquals("Back", BalanceData.readCommitments(context).get(0).name);

        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertEquals(1, BalanceData.readCommitments(context).size());
    }

    @Test public void wrongPasswordLateFrameTrailingAndMalformedInputLeaveLiveDataUnchanged()
            throws Exception {
        LinkedHashMap<String, Bank> live = new LinkedHashMap<>();
        live.put("Live", new Bank("Live", 10L, T, "sender"));
        BalanceData.write(context, live);
        Transaction liveTransaction = new Transaction("Live", T + 1, -1L, "live-signature");
        BalanceData.writeTransactions(context, Collections.singletonList(liveTransaction));
        File valid = file("v2-invalid-base.bin");
        BackupManager.createFramed(context, Uri.fromFile(valid), PASSWORD);

        expectRestoreFailure(valid, "wrong password", "wrong password");
        assertLiveData(liveTransaction);

        byte[] late = read(valid);
        late[late.length - 1] ^= 0x01;
        File lateFile = file("v2-late-frame.bin");
        write(lateFile, late);
        expectRestoreFailure(lateFile, "damaged late frame", PASSWORD);
        assertLiveData(liveTransaction);

        byte[] trailing = Arrays.copyOf(read(valid), (int) valid.length() + 1);
        trailing[trailing.length - 1] = 7;
        File trailingFile = file("v2-trailing.bin");
        write(trailingFile, trailing);
        expectRestoreFailure(trailingFile, "trailing bytes", PASSWORD);
        assertLiveData(liveTransaction);

        File malformed = file("v2-malformed.bin");
        writePayload(malformed, "{\"schema\":2,\"schema\":2}");
        expectRestoreFailure(malformed, "malformed JSON", PASSWORD);
        assertLiveData(liveTransaction);

        File conflictingSettlements = file("v2-conflicting-settlements.bin");
        writePayload(conflictingSettlements,
            "{\"schema\":2,\"balances\":[],\"transactions\":[],"
                + "\"metadata\":{\"notes\":[],\"reasons\":[],\"channels\":[],\"tags\":[]},"
                + "\"commitments\":[{\"id\":\"conflict\",\"name\":\"Conflict\","
                + "\"amount\":-1,\"freq\":1,\"start\":" + T
                + ",\"paid\":[" + (T + DAY) + "],\"unpaid\":[" + (T + DAY) + "]}]}" );
        expectRestoreFailure(conflictingSettlements, "conflicting settlement states", PASSWORD);
        assertLiveData(liveTransaction);
    }

    @Test public void newerFramedProducerVersion_isRejectedBeforePublication() throws Exception {
        LinkedHashMap<String, Bank> live = new LinkedHashMap<>();
        live.put("Live", new Bank("Live", 10L, T, "sender"));
        BalanceData.write(context, live);

        File future = file("v2-newer-producer.bin");
        writePayload(future, "{\"schema\":2,\"appVersionCode\":"
            + (BackupManager.appVersionCode(context) + 1)
            + ",\"balances\":[],\"transactions\":[],"
            + "\"metadata\":{\"notes\":[],\"reasons\":[],\"channels\":[],\"tags\":[]},"
            + "\"commitments\":[]}");

        try {
            BackupManager.restore(context, Uri.fromFile(future), PASSWORD);
            fail("a newer framed producer must be rejected");
        } catch (BackupManager.BackupException e) {
            assertEquals(R.string.backup_error_newer_version, e.resId);
        }
        assertEquals(10L, BalanceData.read(context).get("Live").amount);
        assertTrue(BalanceData.readTransactions(context).isEmpty());
    }

    @Test public void framedPayloadWithoutVersionMetadata_remainsCompatible() throws Exception {
        File old = file("v2-without-producer-version.bin");
        writePayload(old, "{\"schema\":2,\"balances\":[],\"transactions\":[],"
            + "\"metadata\":{\"notes\":[],\"reasons\":[],\"channels\":[],\"tags\":[]},"
            + "\"commitments\":[]}");

        BackupManager.RestoreResult result = BackupManager.restore(context,
            Uri.fromFile(old), PASSWORD);
        assertEquals(0, result.added);
        assertTrue(BalanceData.read(context).isEmpty());
    }

    @Test public void largeSourceStore_backupCompletesWithDeterminateProgressAndRoundTrips()
            throws Exception {
        // A real inbox holds thousands of recognized SMS; the sources section must stream them
        // in linear time with live progress instead of stalling the backup dialog.
        int sources = 1200;
        for (int i = 0; i < sources; i++) {
            SourceStore.Source source = SourceStore.capture(context, "Refah",
                "perf body " + i + " balance 1,000,000", T + i, BankRules.VERSION);
            assertNotNull(source);
            assertTrue(SourceStore.observeTransaction(context, source, 1,
                new Transaction("Refah", null, T + i, -100L - i, 900L,
                    "sig-" + i, "content-" + i)));
            assertTrue(SourceStore.observeBalance(context, source, 1, "Refah", null,
                T + i, 900L));
        }
        assertEquals((long) sources, SourceStore.sourceCount(context));

        final List<Integer> stages = new ArrayList<>();
        final long[] last = {0, 0};
        final long[] calls = {0};
        WorkProgress progress = new WorkProgress() {
            @Override public void stage(int stageResId) { stages.add(stageResId); }
            @Override public void progress(long done, long total) {
                calls[0]++;
                last[0] = done;
                last[1] = total;
            }
        };
        File backup = file("v2-large-sources.bin");
        long start = System.currentTimeMillis();
        BackupManager.createFramed(context, Uri.fromFile(backup), PASSWORD, progress);
        android.util.Log.i("BackupV2Test",
            "large backup: " + sources + " sources in "
                + (System.currentTimeMillis() - start) + "ms");
        assertTrue(backup.length() > 0);
        assertTrue("backup must announce its sources section",
            stages.contains(R.string.backup_stage_writing));
        assertEquals((long) sources, calls[0]);
        assertEquals((long) sources, last[0]);
        assertEquals((long) sources, last[1]);

        BalanceData.reset(context, true);
        BackupManager.restore(context, Uri.fromFile(backup), PASSWORD);
        assertEquals((long) sources, SourceStore.sourceCount(context));
        assertEquals((long) sources, SourceStore.transactionObservationCount(context));
        assertEquals((long) sources, SourceStore.balanceObservationCount(context));
    }

    private void expectRestoreFailure(File file, String description, String password)
            throws Exception {
        try {
            BackupManager.restore(context, Uri.fromFile(file), password);
            fail(description + " must be rejected");
        } catch (Exception expected) {
            // The important property is that the generation was never published. Wrong-password
            // and frame failures are exposed as BackupException; structural failures may retain
            // their precise parser exception for diagnostics.
        }
    }

    private void assertLiveData(Transaction transaction) {
        Bank bank = BalanceData.read(context).get("Live");
        assertNotNull(bank);
        assertEquals(10L, bank.amount);
        assertEquals(1, BalanceData.readTransactions(context).size());
        assertEquals(transaction.content, BalanceData.readTransactions(context).get(0).content);
    }

    private File file(String name) {
        File file = new File(context.getCacheDir(), name);
        file.delete();
        return file;
    }

    private static String repeated(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static byte[] read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            assertEquals(bytes.length, offset);
        }
        return bytes;
    }

    private static String readAll(java.io.Reader reader) throws Exception {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) out.append(buffer, 0, count);
        return out.toString();
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    private static void writePayload(File file, String payload) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            BackupFrames.write((OutputStream plaintext) ->
                plaintext.write(payload.getBytes(StandardCharsets.UTF_8)), output,
                PASSWORD.toCharArray());
        }
    }
}
