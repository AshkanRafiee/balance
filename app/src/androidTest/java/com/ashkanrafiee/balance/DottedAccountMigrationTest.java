package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;

@RunWith(AndroidJUnit4.class)
public class DottedAccountMigrationTest {
    private static final String ACCOUNT = "1234.123456.123456789012.123";
    private static final String PADDED = ACCOUNT + " \t\r\n";
    private static final long T = 1_700_000_000_000L;
    private Context context;
    private boolean usesInbox;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        DataGeneration.clearForTests(context);
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)
            .edit().clear().commit();
        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        try {
            if (usesInbox) clearInbox();
            BalanceData.reset(context, true);
            DataGeneration.clearForTests(context);
        } finally {
            if (usesInbox) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
        }
    }

    @Test public void boundedRepair_acceptsOnlyOldMatcherWhitespaceAndTheseTwoBanks() {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            for (String padding : Arrays.asList("", " ", "\t", "\r", "\n", "\u000B", "\f", " \t\r\n"))
                assertEquals(ACCOUNT, DottedAccountMigration.account(bank, ACCOUNT + padding));
            for (String invalid : Arrays.asList(" " + ACCOUNT, ACCOUNT + "\u00a0",
                    ACCOUNT + "x", ACCOUNT + ".", "12345.1.123456.1 ",
                    "1.1234567.123456.1 ", "1.1.12345.1 ", "1.1.1234567890123.1 ",
                    "1.1.123456.1234 ", "1405.01.01 ", "123456 "))
                assertEquals(invalid, DottedAccountMigration.account(bank, invalid));
            assertNull(DottedAccountMigration.account(bank, null));
        }
        for (String bank : Arrays.asList("Resalat", "Gardeshgari", "Mellat", "wepod"))
            assertEquals(PADDED, DottedAccountMigration.account(bank, PADDED));
        assertEquals("Wepod", DottedAccountMigration.storageKey("Wepod"));
    }

    @Test public void newestBalanceWinsAcrossAliasesAndTiePrefersCanonicalWithoutSumming()
            throws Exception {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            put(balances, bank, ACCOUNT, 100, T);
            put(balances, bank, PADDED, 800, T + 20);
            put(balances, bank, ACCOUNT + "\t", 400, T + 10);
        }
        put(balances, "Wepod", null, 777, T + 100);
        put(balances, "Mellat", PADDED, 300, T);
        assertTrue(BalanceData.write(context, balances));
        assertTrue(DottedAccountMigration.ensure(context));
        Map<String, Bank> after = BalanceData.read(context);
        assertEquals(4, after.size());
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            Bank retained = after.get(BalanceData.storageKey(bank, ACCOUNT));
            assertNotNull(retained);
            assertEquals(800, retained.amount);
            assertEquals(T + 20, retained.date);
            assertEquals(ACCOUNT, retained.account);
            assertFalse(after.containsKey(BalanceData.storageKey(bank, PADDED)));
        }
        assertEquals(777, after.get("Wepod").amount);
        assertNotNull(after.get(BalanceData.storageKey("Mellat", PADDED)));

        try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
            LinkedHashMap<String, Bank> tied = new LinkedHashMap<>();
            put(tied, "Pasargad", PADDED, 500, T);
            put(tied, "Pasargad", ACCOUNT, 200, T);
            assertTrue(BalanceData.write(stage.context(), tied));
            assertTrue(DottedAccountMigration.normalizeStage(stage.context()));
            stage.publish();
        }
        assertEquals(200, BalanceData.read(context).get("Pasargad|" + ACCOUNT).amount);
        List<String> generations = DataGeneration.generationNamesForTests(context);
        String revision = TransactionStore.revision(context);
        assertFalse(DottedAccountMigration.ensure(context));
        assertEquals(generations, DataGeneration.generationNamesForTests(context));
        assertEquals(revision, TransactionStore.revision(context));
    }

    @Test public void retainedHistoryKeepsDistinctEventsSignaturesContentsAndAllMetadata()
            throws Exception {
        List<Transaction> history = new ArrayList<>();
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            Transaction first = tx(bank, PADDED, T, "e2:first-" + bank, "content-first-" + bank);
            Transaction second = tx(bank, PADDED, T, "e2:second-" + bank, "content-second-" + bank);
            Transaction legacy = tx(bank, PADDED, T + 1, null, null);
            Transaction contentOnly = tx(bank, PADDED, T + 2, null, "legacy-content-" + bank);
            history.addAll(Arrays.asList(first, second, legacy, contentOnly));
            for (Transaction row : Arrays.asList(first, second, legacy, contentOnly)) {
                MetadataStore.setNote(context, row, "note-" + history.indexOf(row));
                MetadataStore.setTags(context, row, Arrays.asList("Personal", bank));
                MetadataStore.setText(context, MetadataStore.REASONS, BalanceData.noteKey(row), "reason");
                MetadataStore.setText(context, MetadataStore.CHANNELS, BalanceData.noteKey(row), "channel");
                MetadataStore.setText(context, MetadataStore.MERCHANTS, BalanceData.noteKey(row), "merchant");
            }
        }
        assertTrue(TransactionStore.replace(context, history));
        assertTrue(DottedAccountMigration.ensure(context));
        List<Transaction> after = BalanceData.readTransactions(context);
        assertEquals(8, after.size());
        for (int i = 0; i < history.size(); i++) {
            Transaction before = history.get(i);
            Transaction migrated = after.get(i);
            assertEquals(ACCOUNT, migrated.account);
            assertEquals(before.bank, migrated.bank);
            assertEquals(before.date, migrated.date);
            assertEquals(before.amount, migrated.amount);
            assertEquals(before.balance, migrated.balance);
            assertEquals(before.sig, migrated.sig);
            assertEquals(before.content, migrated.content);
            assertEquals("note-" + i, BalanceData.getNote(context, migrated));
            assertEquals(Arrays.asList("Personal", before.bank), BalanceData.getTags(context, migrated));
            assertEquals("reason", BalanceData.readReasons(context).get(BalanceData.noteKey(migrated)));
            assertEquals("channel", BalanceData.readChannels(context).get(BalanceData.noteKey(migrated)));
            assertEquals("merchant", BalanceData.readMerchants(context).get(BalanceData.noteKey(migrated)));
            assertEquals("note-" + i, BalanceData.getNote(context, before));
        }
    }

    @Test public void conflictsOrphanAnnotationsAndResidualProvenanceAreRetained() throws Exception {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            Transaction old = tx(bank, PADDED, T, null, null);
            Transaction canonical = tx(bank, ACCOUNT, T, null, null);
            MetadataStore.setNote(context, old, "old conflicting words");
            MetadataStore.setNote(context, canonical, "canonical words");
            MetadataStore.setTags(context, old, Arrays.asList("Old", "Shared"));
            MetadataStore.setTags(context, canonical, Arrays.asList("New", "shared"));
            Residual before = new Residual(bank, PADDED, T, T + 20, -500, 1);
            MetadataStore.setNote(context, before.noteKey(), "gap annotation");
            assertTrue(BalanceData.mergeResidualNoteProvenance(context, before));
            Residual conflict = new Residual(bank, PADDED, T + 30, T + 60, -100, 2);
            Residual destination = new Residual(bank, ACCOUNT, T + 30, T + 60, -100, 2);
            MetadataStore.setNote(context, conflict.noteKey(), "original gap words");
            MetadataStore.setNote(context, destination.noteKey(), "new gap words");
            assertTrue(BalanceData.mergeResidualNoteProvenance(context, conflict));
            assertTrue(BalanceData.mergeResidualNoteProvenance(context, destination));
        }
        assertTrue(DottedAccountMigration.ensure(context));
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            Transaction old = tx(bank, PADDED, T, null, null);
            Transaction canonical = tx(bank, ACCOUNT, T, null, null);
            assertEquals("old conflicting words", BalanceData.getNote(context, old));
            assertEquals("canonical words", BalanceData.getNote(context, canonical));
            assertEquals(Arrays.asList("New", "shared", "Old"), BalanceData.getTags(context, canonical));
            assertEquals(Arrays.asList("Old", "Shared"), BalanceData.getTags(context, old));
            Residual before = new Residual(bank, PADDED, T, T + 20, -500, 1);
            Residual after = new Residual(bank, ACCOUNT, T, T + 20, -500, 1);
            assertEquals("gap annotation", BalanceData.getNote(context, before));
            assertEquals("gap annotation", BalanceData.getNote(context, after));
            Map<String, Residual> provenance = BalanceData.readResidualNoteProvenance(context);
            assertEquals(PADDED, provenance.get(before.noteKey()).account);
            assertEquals(ACCOUNT, provenance.get(after.noteKey()).account);
            assertEquals(-500, provenance.get(after.noteKey()).amount);
            assertEquals(1, provenance.get(after.noteKey()).movements);
            Residual conflict = new Residual(bank, PADDED, T + 30, T + 60, -100, 2);
            Residual destination = new Residual(bank, ACCOUNT, T + 30, T + 60, -100, 2);
            assertEquals("original gap words", BalanceData.getNote(context, conflict));
            assertEquals("new gap words", BalanceData.getNote(context, destination));
            assertTrue(provenance.containsKey(conflict.noteKey()));
            assertTrue(provenance.containsKey(destination.noteKey()));
        }
        assertTrue(BalanceData.readTransactions(context).isEmpty());
    }

    @Test public void originalSourceAndObservationsRemainImmutable() throws Exception {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            String sender = "Wepod".equals(bank) ? "Wepod" : "B.Pasargad";
            String body = ACCOUNT + " \t\nبرداشت:100\nمانده:900";
            SourceStore.Source source = SourceStore.capture(context, sender, body, T, 1);
            assertNotNull(source);
            Transaction original = tx(bank, PADDED, T, "original-" + bank, "body-" + bank);
            assertTrue(SourceStore.observeTransaction(context, source, 1, original));
            assertTrue(SourceStore.observeBalance(context, source, 1, bank, PADDED, T, 900L));
        }
        assertFalse(DottedAccountMigration.ensure(context));
        assertEquals(2, SourceStore.sourceCount(context));
        assertEquals(2, SourceStore.transactionObservationCount(context));
        assertEquals(2, SourceStore.balanceObservationCount(context));
        SourceStore.forEachSourceWithObservations(context, 256, (source, transactions, balances) -> {
            assertEquals(ACCOUNT + " \t\nبرداشت:100\nمانده:900", source.rawBody);
            assertEquals(T, source.arrivalTime);
            assertEquals(1, source.ruleVersion);
            assertEquals(1, transactions.size());
            assertEquals(1, balances.size());
            assertEquals(PADDED, transactions.get(0).transaction.account);
            assertEquals("original-" + source.bank, transactions.get(0).transaction.sig);
            assertEquals("body-" + source.bank, transactions.get(0).transaction.content);
            assertEquals(PADDED, balances.get(0).account);
            assertEquals(1, balances.get(0).parserRevision);
        });
    }

    @Test public void exclusionUnionFollowsSameAccountButPlainWepodAndOtherBanksStayPut()
            throws Exception {
        org.json.JSONArray old = new org.json.JSONArray(Arrays.asList("Pasargad|" + PADDED,
            "Pasargad|" + ACCOUNT, "Wepod|" + PADDED, "Wepod", "Mellat|" + PADDED));
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_EXCLUDED, old.toString()).commit();
        assertEquals(new HashSet<>(Arrays.asList("Pasargad|" + ACCOUNT, "Wepod|" + ACCOUNT,
            "Wepod", "Mellat|" + PADDED)), BalanceData.getExcluded(context));
        BalanceData.toggleExcluded(context, "Wepod|" + ACCOUNT);
        assertFalse(BalanceData.isExcluded(context, "Wepod|" + ACCOUNT));
        assertFalse(BalanceData.isExcluded(context, "Wepod|" + PADDED));
        assertTrue(BalanceData.isExcluded(context, "Wepod"));
        assertFalse(BalanceData.isExcluded(context, "Wepod|1.2.123456.3"));
        BalanceData.toggleExcluded(context, "Pasargad|" + PADDED);
        assertFalse(BalanceData.isExcluded(context, "Pasargad|" + ACCOUNT));
    }

    @Test public void failedStageLeavesEveryLiveStoreUnchangedAndRetryIsIdempotent()
            throws Exception {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        put(balances, "Wepod", PADDED, 900, T);
        assertTrue(BalanceData.write(context, balances));
        Transaction original = tx("Wepod", PADDED, T, null, null);
        assertTrue(TransactionStore.replace(context, Arrays.asList(original)));
        MetadataStore.setNote(context, original, "saved words");
        Residual residual = new Residual("Wepod", PADDED, T, T + 10, -100, 1);
        MetadataStore.setNote(context, residual.noteKey(), "saved gap words");
        assertTrue(BalanceData.mergeResidualNoteProvenance(context, residual));
        String oldRevision = TransactionStore.revision(context);
        String metadataRevision = MetadataStore.revision(context);
        try {
            DottedAccountMigration.ensure(context, point -> {
                if (point == DataGeneration.PublishPoint.BEFORE_MANIFEST)
                    throw new java.io.IOException("injected publication failure");
            });
            fail("migration should fail before publication");
        } catch (java.io.IOException expected) { }
        assertEquals(900, BalanceData.read(context).get("Wepod|" + PADDED).amount);
        assertEquals(PADDED, BalanceData.readTransactions(context).get(0).account);
        assertEquals("saved words", BalanceData.getNote(context, original));
        assertNull(BalanceData.getNote(context, DottedAccountMigration.transaction(original)));
        assertEquals(1, BalanceData.readResidualNoteProvenance(context).size());
        assertEquals(oldRevision, TransactionStore.revision(context));
        assertEquals(metadataRevision, MetadataStore.revision(context));
        assertEquals(0, repairStates().length());
        assertFalse(DataGeneration.dataPrefs(context).getBoolean(DottedAccountMigration.KEY_COMPLETE, false));
        assertTrue(DataGeneration.generationNamesForTests(context).isEmpty());
        assertTrue(DottedAccountMigration.ensure(context));
        assertEquals(ACCOUNT, BalanceData.readTransactions(context).get(0).account);
        assertEquals("saved words", BalanceData.getNote(context, DottedAccountMigration.transaction(original)));
        assertFalse(DottedAccountMigration.ensure(context));
    }

    @Test public void excludedPaddedSnapshotStaysExcludedBeforeWorkerMigration() throws Exception {
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        put(balances, "Wepod", PADDED, 900, T);
        put(balances, "Pasargad", PADDED, 800, T);
        put(balances, "Melli", null, 100, T);
        assertTrue(BalanceData.write(context, balances));
        BalanceData.setExcluded(context, new HashSet<>(Arrays.asList(
            "Wepod|" + PADDED, "Pasargad|" + PADDED)));
        java.util.Set<String> excluded = BalanceData.getExcluded(context);
        for (String bank : Arrays.asList("Wepod", "Pasargad")) {
            assertTrue(BalanceData.isExcluded(excluded, bank + "|" + PADDED));
            assertTrue(BalanceData.isExcluded(excluded, bank + "|" + ACCOUNT));
        }
        List<Bank> ordered = BalanceData.orderForDisplay(balances, excluded);
        assertEquals("Melli", ordered.get(0).name);
        assertTrue(BalanceData.isExcluded(excluded,
            BalanceData.storageKey(ordered.get(1).name, ordered.get(1).account)));
        assertTrue(BalanceData.isExcluded(excluded,
            BalanceData.storageKey(ordered.get(2).name, ordered.get(2).account)));
        assertFalse(DataGeneration.dataPrefs(context).getBoolean(
            DottedAccountMigration.KEY_COMPLETE, false));
    }

    @Test public void interruptedAfterPublicationRecoversFromSelectedMarkerWithoutRepeating()
            throws Exception {
        Transaction original = tx("Pasargad", PADDED, T, null, null);
        assertTrue(TransactionStore.replace(context, Arrays.asList(original)));
        MetadataStore.setNote(context, original, "surviving words");
        try {
            DottedAccountMigration.ensure(context, point -> {
                if (point == DataGeneration.PublishPoint.AFTER_MANIFEST)
                    throw new java.io.IOException("injected post-publication interruption");
            });
            fail("expected post-publication interruption");
        } catch (java.io.IOException expected) { }
        List<String> generations = DataGeneration.generationNamesForTests(context);
        assertEquals(1, generations.size());
        assertEquals(ACCOUNT, BalanceData.readTransactions(context).get(0).account);
        assertEquals("surviving words", BalanceData.getNote(context,
            DottedAccountMigration.transaction(original)));
        assertEquals("surviving words", BalanceData.getNote(context, original));
        assertFalse(DottedAccountMigration.ensure(context));
        assertEquals(generations, DataGeneration.generationNamesForTests(context));
    }

    @Test public void pasargadOlderInboxCannotReplaceNewerPaddedRetainedSnapshot() throws Exception {
        checkOlderInbox("Pasargad", "B.Pasargad");
    }

    @Test public void wepodOlderInboxCannotReplaceNewerPaddedRetainedSnapshot() throws Exception {
        checkOlderInbox("Wepod", "Wepod");
    }

    @Test public void sourceProofStillMatchesMigratedProjectionsWithoutChangingOldObservations()
            throws Exception {
        prepareInbox();
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        List<Transaction> retained = new ArrayList<>();
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            String sender = "Wepod".equals(bank) ? "Wepod" : "B.Pasargad";
            String body = ACCOUNT + "\nبرداشت:100\nمانده:900";
            seed(sender, body);
            SourceStore.Source source = SourceStore.capture(context, sender, body, T, 1);
            assertNotNull(source);
            Transaction old = tx(bank, PADDED, T + 60_000, "old-sig-" + bank, "old-body-" + bank);
            retained.add(old);
            assertTrue(SourceStore.observeTransaction(context, source, 1, old));
            assertTrue(SourceStore.observeBalance(context, source, 1, bank, PADDED, old.date, 900L));
            put(saved, bank, PADDED, 900, old.date);
            MetadataStore.setNote(context, old, "source proven words " + bank);
            MetadataStore.setTags(context, old, Arrays.asList("Original", bank));
        }
        awaitInbox(2);
        assertTrue(BalanceData.write(context, saved));
        assertTrue(TransactionStore.replace(context, retained));
        assertEquals(BalanceData.ScanOutcome.Status.SUCCESS,
            BalanceData.scanSmsOutcome(context, saved, null).status);
        assertEquals(2, saved.size());
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            Bank corrected = saved.get(bank + "|" + ACCOUNT);
            assertNotNull(corrected);
            assertEquals(T, corrected.date);
            assertEquals(900, corrected.amount);
        }
        assertEquals(BalanceData.ScanOutcome.Status.SUCCESS,
            BalanceData.scanHistoryOutcome(context, null).status);
        List<Transaction> history = BalanceData.readTransactions(context);
        assertEquals(2, history.size());
        for (Transaction transaction : history) {
            assertEquals(ACCOUNT, transaction.account);
            assertEquals(T, transaction.date);
            assertEquals(-100, transaction.amount);
            assertEquals("source proven words " + transaction.bank, BalanceData.getNote(context, transaction));
            assertEquals(Arrays.asList("Original", transaction.bank), BalanceData.getTags(context, transaction));
        }
        SourceStore.forEachSourceWithObservations(context, 256, (source, transactions, balances) -> {
            boolean oldTransactionFound = false;
            boolean oldBalanceFound = false;
            for (SourceStore.TransactionObservation old : transactions) {
                if (old.parserRevision != 1) continue;
                assertEquals(PADDED, old.transaction.account);
                assertEquals(T + 60_000, old.transaction.date);
                assertEquals("old-sig-" + source.bank, old.transaction.sig);
                assertEquals("old-body-" + source.bank, old.transaction.content);
                oldTransactionFound = true;
            }
            for (SourceStore.BalanceObservation old : balances) {
                if (old.parserRevision != 1) continue;
                assertEquals(PADDED, old.account);
                assertEquals(T + 60_000, old.date);
                oldBalanceFound = true;
            }
            assertTrue(oldTransactionFound);
            assertTrue(oldBalanceFound);
        });
    }

    @Test public void oldPaddedBackupReimportAfterMarkerPreservesConflictAndDoesNotDuplicateHistory()
            throws Exception {
        Transaction padded = tx("Pasargad", PADDED, T, null, null);
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        put(balances, "Pasargad", PADDED, 900, T);
        assertTrue(BalanceData.write(context, balances));
        assertTrue(TransactionStore.replace(context, Arrays.asList(padded)));
        MetadataStore.setNote(context, padded, "backup words");
        MetadataStore.setTags(context, padded, Arrays.asList("Backup"));
        File backup = File.createTempFile("dotted-old-", ".balance", context.getCacheDir());
        try {
            BackupManager.create(context, Uri.fromFile(backup), "test backup password");
            BalanceData.reset(context, true);
            assertFalse(DottedAccountMigration.ensure(context));
            Transaction canonical = DottedAccountMigration.transaction(padded);
            assertTrue(TransactionStore.replace(context, Arrays.asList(canonical)));
            MetadataStore.setNote(context, canonical, "local words");
            MetadataStore.setTags(context, canonical, Arrays.asList("Local"));
            for (int i = 0; i < 2; i++) {
                BackupManager.RestoreResult result = BackupManager.restore(context,
                    Uri.fromFile(backup), "test backup password");
                assertEquals(0, result.transactionsAdded);
                if (i > 0) assertFalse(result.changed());
                assertEquals(1, BalanceData.readTransactions(context).size());
                assertEquals(ACCOUNT, BalanceData.readTransactions(context).get(0).account);
                assertEquals("local words", BalanceData.getNote(context, canonical));
                assertEquals("backup words", BalanceData.getNote(context, padded));
                assertEquals(Arrays.asList("Local", "Backup"), BalanceData.getTags(context, canonical));
                assertEquals(1, BalanceData.read(context).size());
                assertEquals(900, BalanceData.read(context).get("Pasargad|" + ACCOUNT).amount);
                assertTrue(DataGeneration.dataPrefs(context).getBoolean(DottedAccountMigration.KEY_COMPLETE, false));
            }
        } finally {
            backup.delete();
        }
    }

    @Test public void realPaddedEventConflictsSurviveReparseAndClearedAnnotationsNeverReplay()
            throws Exception {
        prepareInbox();
        List<Transaction> retained = new ArrayList<>();
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            String sender = sender(bank);
            String body = paddedBody();
            seed(sender, body);
            Transaction canonical = BalanceData.parseMovement(bank, sender, body, T, false, 0);
            assertNotNull(canonical);
            Transaction old = oldEvent(bank, body);
            retained.add(old);
            retained.add(canonical);
            SourceStore.Source source = SourceStore.capture(context, sender, body, T, 1);
            assertTrue(SourceStore.observeTransaction(context, source, 1, old));
            MetadataStore.setNote(context, old, "old words " + bank);
            MetadataStore.setNote(context, canonical, "canonical words " + bank);
            MetadataStore.setNote(context, "c:" + canonical.content, "old content words " + bank);
            MetadataStore.setTags(context, old, Arrays.asList("Old"));
            MetadataStore.setTags(context, canonical, Arrays.asList("Local"));
            MetadataStore.setTags(context, "c:" + canonical.content, Arrays.asList("Content"));
        }
        awaitInbox(2);
        assertTrue(TransactionStore.replace(context, retained));
        LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            assertTrue(BalanceData.reReadBankMessages(context, balances, null).succeeded());
            assertEquals(2, BalanceData.readTransactions(context).size());
            for (Transaction row : BalanceData.readTransactions(context)) {
                assertEquals("canonical words " + row.bank, BalanceData.getNote(context, row));
                assertTrue(BalanceData.getTags(context, row).containsAll(
                    Arrays.asList("Old", "Local", "Content")));
                assertEquals("old words " + row.bank,
                    BalanceData.getNote(context, oldEvent(row.bank, paddedBody())));
                assertEquals("old content words " + row.bank,
                    MetadataStore.getNote(context, "c:" + row.content));
            }
        }
        for (Transaction row : BalanceData.readTransactions(context)) {
            MetadataStore.setNote(context, row, null);
            MetadataStore.setTags(context, row, Collections.emptyList());
        }
        assertTrue(BalanceData.reReadBankMessages(context, balances, null).succeeded());
        for (Transaction row : BalanceData.readTransactions(context)) {
            assertNull(BalanceData.getNote(context, row));
            assertTrue(BalanceData.getTags(context, row).isEmpty());
            assertEquals("old words " + row.bank,
                BalanceData.getNote(context, oldEvent(row.bank, paddedBody())));
        }
    }

    @Test public void paddedLegacyBackupAttachesSelectedMetadataToSignedSurvivor() throws Exception {
        checkSignedSurvivorRestore(false);
    }

    @Test public void paddedQualifiedBackupPreservesConflictsAndSelectedTagsOnSignedSurvivor()
            throws Exception {
        checkSignedSurvivorRestore(true);
    }

    private void checkSignedSurvivorRestore(boolean qualified) throws Exception {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            Transaction original = qualified ? oldEvent(bank, paddedBody())
                : tx(bank, PADDED, T, null, null);
            assertTrue(TransactionStore.replace(context, Arrays.asList(original)));
            MetadataStore.setNote(context, original, "backup words");
            MetadataStore.setTags(context, original, Arrays.asList("Backup"));
            File backup = File.createTempFile("dotted-signed-", ".balance", context.getCacheDir());
            try {
                BackupManager.create(context, Uri.fromFile(backup), "test backup password");
                for (boolean metadataOnly : Arrays.asList(false, true)) {
                    BalanceData.reset(context, true);
                    DottedAccountMigration.ensure(context);
                    Transaction canonical = BalanceData.parseMovement(bank, sender(bank),
                        paddedBody(), T, false, 0);
                    assertTrue(TransactionStore.replace(context, Arrays.asList(canonical)));
                    MetadataStore.setNote(context, canonical, "local words");
                    MetadataStore.setTags(context, canonical, Arrays.asList("Local"));
                    EnumSet<BackupManager.Section> selection = metadataOnly
                        ? EnumSet.of(BackupManager.Section.NOTES, BackupManager.Section.TAGS)
                        : EnumSet.of(BackupManager.Section.TRANSACTIONS,
                            BackupManager.Section.NOTES, BackupManager.Section.TAGS);
                    for (int pass = 0; pass < 2; pass++) {
                        BackupManager.RestoreResult result = BackupManager.restore(context,
                            Uri.fromFile(backup), "test backup password", null, selection);
                        assertEquals(0, result.transactionsAdded);
                        if (pass > 0) assertFalse(result.changed());
                        assertEquals(1, BalanceData.readTransactions(context).size());
                        assertEquals("local words", BalanceData.getNote(context, canonical));
                        assertEquals("backup words", BalanceData.getNote(context, original));
                        assertEquals(Arrays.asList("Local", "Backup"),
                            BalanceData.getTags(context, canonical));
                    }
                    MetadataStore.setNote(context, canonical, null);
                    MetadataStore.setTags(context, canonical, Collections.emptyList());
                    BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                        EnumSet.of(BackupManager.Section.COMMITMENTS));
                    assertNull(BalanceData.getNote(context, canonical));
                    assertTrue(BalanceData.getTags(context, canonical).isEmpty());
                }
            } finally { backup.delete(); }
        }
    }

    @Test public void unrelatedRestoreDoesNotReplayArchivedLegacyOrResidualAnnotations()
            throws Exception {
        Transaction old = tx("Pasargad", PADDED, T, null, null);
        MetadataStore.setNote(context, old, "archived words");
        MetadataStore.setTags(context, old, Arrays.asList("Archived"));
        Residual residual = new Residual("Pasargad", PADDED, T, T + 10, -100, 1);
        MetadataStore.setNote(context, residual.noteKey(), "archived gap");
        BalanceData.mergeResidualNoteProvenance(context, residual);
        assertTrue(DottedAccountMigration.ensure(context));
        Transaction canonical = DottedAccountMigration.transaction(old);
        Residual gap = new Residual("Pasargad", ACCOUNT, T, T + 10, -100, 1);
        MetadataStore.setNote(context, canonical, null);
        MetadataStore.setTags(context, canonical, Collections.emptyList());
        MetadataStore.setNote(context, gap.noteKey(), null);
        File backup = File.createTempFile("dotted-unrelated-", ".balance", context.getCacheDir());
        try {
            BackupManager.create(context, Uri.fromFile(backup), "test backup password");
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                EnumSet.of(BackupManager.Section.COMMITMENTS));
            assertNull(BalanceData.getNote(context, canonical));
            assertTrue(BalanceData.getTags(context, canonical).isEmpty());
            assertNull(MetadataStore.getNote(context, gap.noteKey()));
            assertEquals("archived words", BalanceData.getNote(context, old));
            assertEquals("archived gap", MetadataStore.getNote(context, residual.noteKey()));
        } finally { backup.delete(); }
    }

    @Test public void metadataOnlyRepairKeepsUnrelatedIncrementalDeltaSeed() throws Exception {
        prepareInbox();
        seed("Saman", "برداشت:100\nمانده:900");
        awaitInbox(1);
        assertTrue(BalanceData.scanHistoryOutcome(context, null).succeeded());
        String seed = DataGeneration.dataPrefs(context)
            .getString(BalanceData.KEY_HISTORY_LAST_BALANCE, null);
        assertNotNull(seed);
        clearInbox();
        MetadataStore.setNote(context, tx("Pasargad", PADDED, T, null, null), "orphan words");
        assertTrue(DataGeneration.dataPrefs(context).edit()
            .remove(DottedAccountMigration.KEY_COMPLETE).commit());
        assertTrue(DottedAccountMigration.ensure(context));
        assertEquals(seed, DataGeneration.dataPrefs(context)
            .getString(BalanceData.KEY_HISTORY_LAST_BALANCE, null));
        seed("Saman", "برداشت انجام شد\nمانده:850", T + 60_000);
        awaitInbox(1);
        assertEquals(1, BalanceData.scanHistory(context));
        boolean delta = false;
        for (Transaction row : BalanceData.readTransactions(context)) {
            if (Long.valueOf(850).equals(row.balance)) {
                assertEquals(-50, row.amount);
                delta = true;
            }
        }
        assertTrue(delta);
    }

    @Test public void cacheRepairKeepsUnrelatedRowsAndNewestDatedDottedSeed() throws Exception {
        JSONObject saman = new JSONObject().put("b", 900).put("d", T);
        JSONObject seeds = new JSONObject().put("Saman", saman)
            .put("Pasargad|" + ACCOUNT, new JSONObject().put("b", 200).put("d", T))
            .put("Pasargad|" + PADDED, new JSONObject().put("b", 500).put("d", T + 10));
        JSONArray one = new JSONArray().put(new JSONObject().put("d", T).put("a", -100)
            .put("b", 900).put("s", "first"));
        JSONArray two = new JSONArray().put(new JSONObject().put("d", T + 10).put("a", -100)
            .put("b", 800).put("s", "second"));
        JSONObject windows = new JSONObject().put("Saman", one)
            .put("Wepod|" + ACCOUNT, one).put("Wepod|" + PADDED, two);
        assertTrue(DataGeneration.dataPrefs(context).edit()
            .putString(BalanceData.KEY_HISTORY_LAST_BALANCE,
                BalanceData.encryptStorePayload(seeds.toString()))
            .putString(BalanceData.KEY_RECENT_MOVEMENTS,
                BalanceData.encryptStorePayload(windows.toString())).commit());
        assertTrue(DottedAccountMigration.ensure(context));
        JSONObject after = new JSONObject(BalanceData.decryptStorePayload(DataGeneration.dataPrefs(context)
            .getString(BalanceData.KEY_HISTORY_LAST_BALANCE, null)));
        assertEquals(2, after.length());
        assertEquals(saman.toString(), after.getJSONObject("Saman").toString());
        assertEquals(500, after.getJSONObject("Pasargad|" + ACCOUNT).getLong("b"));
        assertEquals(T + 10, after.getJSONObject("Pasargad|" + ACCOUNT).getLong("d"));
        JSONObject recent = new JSONObject(BalanceData.decryptStorePayload(DataGeneration.dataPrefs(context)
            .getString(BalanceData.KEY_RECENT_MOVEMENTS, null)));
        assertEquals(1, recent.getJSONArray("Saman").length());
        assertEquals(2, recent.getJSONArray("Wepod|" + ACCOUNT).length());
        assertEquals(T + 10, recent.getJSONArray("Wepod|" + ACCOUNT).getJSONObject(1).getLong("d"));
    }

    @Test public void copyLedgerSurvivesKeepNotesResetButDiscardAllRemovesItsIdentities()
            throws Exception {
        Transaction original = tx("Pasargad", PADDED, T, null, null);
        Transaction canonical = DottedAccountMigration.transaction(original);
        MetadataStore.setNote(context, original, "archived words");
        assertTrue(DottedAccountMigration.ensure(context));
        MetadataStore.setNote(context, canonical, null);
        BalanceData.reset(context, false);
        Map<String, String> route = Collections.singletonMap(
            BalanceData.noteKey(original), BalanceData.noteKey(canonical));
        assertFalse(MetadataStore.migrateKeys(context, route));
        assertNull(BalanceData.getNote(context, canonical));
        assertEquals("archived words", BalanceData.getNote(context, original));

        BalanceData.reset(context, true);
        MetadataStore.setNote(context, original, "fresh source words");
        MetadataStore.setNote(context, canonical, "fresh destination words");
        assertTrue(MetadataStore.migrateKeys(context, route));
        assertNull(BalanceData.getNote(context, original));
        assertEquals("fresh destination words", BalanceData.getNote(context, canonical));
    }

    @Test public void framedAndLegacyRoundTripsKeepClearedCanonicalAnnotationsCleared()
            throws Exception {
        prepareInbox();
        for (boolean framed : Arrays.asList(true, false)) {
            BalanceData.reset(context, true);
            clearInbox();
            List<Transaction> events = new ArrayList<>();
            for (String bank : Arrays.asList("Pasargad", "Wepod")) {
                seed(sender(bank), paddedBody());
                Transaction old = oldEvent(bank, paddedBody());
                Transaction canonical = BalanceData.parseMovement(bank, sender(bank), paddedBody(), T, false, 0);
                events.add(old);
                events.add(canonical);
                SourceStore.Source source = SourceStore.capture(context, sender(bank), paddedBody(), T, 1);
                SourceStore.observeTransaction(context, source, 1, old);
                MetadataStore.setNote(context, old, "archived e2 " + bank);
                MetadataStore.setTags(context, old, Arrays.asList("Archived e2"));
                MetadataStore.setNote(context, "c:" + old.content, "archived content " + bank);
                MetadataStore.setTags(context, "c:" + old.content, Arrays.asList("Archived content"));
                MetadataStore.setNote(context, canonical, "canonical words " + bank);
            }
            awaitInbox(2);
            TransactionStore.replace(context, events);
            // reset(true) deliberately leaves the harmless one-time marker. Re-run the initial
            // repair here to model the original pre-update installation for each writer.
            DataGeneration.dataPrefs(context).edit().remove(DottedAccountMigration.KEY_COMPLETE).commit();
            LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
            assertTrue(BalanceData.reReadBankMessages(context, balances, null).succeeded());
            for (Transaction event : BalanceData.readTransactions(context)) {
                MetadataStore.setNote(context, event, null);
                MetadataStore.setTags(context, event, Collections.emptyList());
            }
            File backup = File.createTempFile("dotted-roundtrip-", ".balance", context.getCacheDir());
            try {
                if (framed) BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
                else BackupManager.create(context, Uri.fromFile(backup), "test backup password");
                BalanceData.reset(context, true);
                for (int pass = 0; pass < 2; pass++) {
                    BackupManager.restore(context, Uri.fromFile(backup), "test backup password");
                    assertTrue(BalanceData.reReadBankMessages(context, balances, null).succeeded());
                    assertEquals(2, BalanceData.readTransactions(context).size());
                    for (Transaction event : BalanceData.readTransactions(context)) {
                        assertNull(BalanceData.getNote(context, event));
                        assertTrue(BalanceData.getTags(context, event).isEmpty());
                        assertEquals("archived e2 " + event.bank,
                            BalanceData.getNote(context, oldEvent(event.bank, paddedBody())));
                        assertEquals("archived content " + event.bank,
                            MetadataStore.getNote(context, "c:" + event.content));
                    }
                }
            } finally { backup.delete(); }
        }
    }

    @Test public void accountRepairStateIsSelectedPerKindAndDoesNotRetireLocalActiveNotes()
            throws Exception {
        Transaction original = oldEvent("Pasargad", paddedBody());
        Transaction canonical = BalanceData.parseMovement("Pasargad", sender("Pasargad"),
            paddedBody(), T, false, 0);
        MetadataStore.setNote(context, original, "archived words");
        MetadataStore.setTags(context, original, Arrays.asList("Archived"));
        MetadataStore.setNote(context, canonical, "canonical words");
        MetadataStore.copyKeysPreservingSources(context, Collections.singletonMap(
            BalanceData.noteKey(original), BalanceData.noteKey(canonical)));
        File backup = File.createTempFile("dotted-selected-", ".balance", context.getCacheDir());
        try {
            BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
            BalanceData.reset(context, true);
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                EnumSet.of(BackupManager.Section.COMMITMENTS));
            assertEquals(0, repairStates().length());
            assertTrue(BalanceData.readNotes(context).isEmpty());
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                EnumSet.of(BackupManager.Section.NOTES));
            JSONArray notes = repairStates();
            assertEquals(2, notes.length());
            for (int i = 0; i < notes.length(); i++)
                assertEquals(MetadataStore.NOTES, notes.getJSONObject(i).getInt("kind"));
            MetadataStore.setNote(context, canonical, null);
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                EnumSet.of(BackupManager.Section.TAGS));
            assertNull(BalanceData.getNote(context, canonical));
            assertEquals(Arrays.asList("Archived"), BalanceData.getTags(context, canonical));

            // Importing an archived flag must not retire a local active value at that key.
            BalanceData.reset(context, true);
            MetadataStore.setNote(context, original, "local active words");
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                EnumSet.of(BackupManager.Section.NOTES));
            String later = "s:e2:later-active:a::" + (T + 1);
            MetadataStore.migrateKeys(context, Collections.singletonMap(BalanceData.noteKey(original), later));
            assertEquals("local active words", MetadataStore.getNote(context, later));
        } finally { backup.delete(); }
    }

    @Test public void activeProtectedEventSurvivesFramedRoundTripAndCanFollowFreshIdentity()
            throws Exception {
        prepareInbox();
        seed(sender("Pasargad"), paddedBody());
        awaitInbox(1);
        Transaction original = oldEvent("Pasargad", paddedBody());
        TransactionStore.replace(context, Arrays.asList(original));
        MetadataStore.setNote(context, original, "active words");
        MetadataStore.setTags(context, original, Arrays.asList("Active"));
        DottedAccountMigration.ensure(context);
        File backup = File.createTempFile("dotted-active-", ".balance", context.getCacheDir());
        try {
            BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
            BalanceData.reset(context, true);
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password");
            assertTrue(BalanceData.reReadBankMessages(context, new LinkedHashMap<>(), null).succeeded());
            Transaction fresh = BalanceData.readTransactions(context).get(0);
            assertFalse(original.sig.equals(fresh.sig));
            assertEquals("active words", BalanceData.getNote(context, fresh));
            assertEquals(Arrays.asList("Active"), BalanceData.getTags(context, fresh));
            MetadataStore.setNote(context, fresh, null);
            MetadataStore.setTags(context, fresh, Collections.emptyList());
            assertTrue(BalanceData.reReadBankMessages(context, new LinkedHashMap<>(), null).succeeded());
            assertNull(BalanceData.getNote(context, fresh));
            assertTrue(BalanceData.getTags(context, fresh).isEmpty());
        } finally { backup.delete(); }
    }

    @Test public void retiredSourceKindNeverCopiesToAnotherDestinationButActiveIntermediaryCan()
            throws Exception {
        String from = BalanceData.noteKey(tx("Pasargad", PADDED, T, null, null));
        String canonical = BalanceData.noteKey(tx("Pasargad", ACCOUNT, T, null, null));
        String fresh = "s:e2:fresh:a::" + T;
        MetadataStore.setNote(context, from, "saved words");
        MetadataStore.copyKeysPreservingSources(context, Collections.singletonMap(from, canonical));
        MetadataStore.migrateKeys(context, Collections.singletonMap(canonical, fresh));
        assertEquals("saved words", MetadataStore.getNote(context, fresh));
        MetadataStore.setNote(context, fresh, null);
        MetadataStore.migrateKeys(context, Collections.singletonMap(from, fresh));
        MetadataStore.migrateKeys(context, Collections.singletonMap(canonical, fresh));
        assertNull(MetadataStore.getNote(context, fresh));
        assertEquals("saved words", MetadataStore.getNote(context, from));
        assertEquals("saved words", MetadataStore.getNote(context, canonical));
    }

    @Test public void invalidOrDuplicateAccountRepairStateFailsBeforePublishing() throws Exception {
        for (String state : Arrays.asList(
                "[{\"kind\":0,\"key\":\"c:test\",\"archived\":true}]",
                "[{\"kind\":1,\"key\":\"c:test\",\"archived\":\"true\"}]",
                "[{\"kind\":1,\"key\":\"c:test\"}]",
                "[{\"kind\":1,\"key\":\"c:test\",\"archived\":true,\"extra\":0}]",
                "[{\"kind\":1,\"key\":\"c:test\",\"archived\":true},"
                    + "{\"kind\":1,\"key\":\"c:test\",\"archived\":false}]")) {
            File backup = File.createTempFile("dotted-invalid-", ".balance", context.getCacheDir());
            try {
                String payload = "{\"schema\":2,\"balances\":[],\"transactions\":[],"
                    + "\"metadata\":{\"notes\":[],\"reasons\":[],\"channels\":[],\"tags\":[],"
                    + "\"accountRepairState\":" + state + "},\"commitments\":[]}";
                try (FileOutputStream raw = new FileOutputStream(backup)) {
                    BackupFrames.BackupOutputStream encrypted = BackupFrames.openOutputStream(
                        raw, "test backup password".toCharArray());
                    try {
                        encrypted.write(payload.getBytes(StandardCharsets.UTF_8));
                        encrypted.finish();
                    } finally { encrypted.close(); }
                }
                List<String> before = DataGeneration.generationNamesForTests(context);
                try {
                    BackupManager.restore(context, Uri.fromFile(backup), "test backup password");
                    fail("invalid repair state should fail");
                } catch (Exception expected) { }
                assertEquals(before, DataGeneration.generationNamesForTests(context));
                assertEquals(0, repairStates().length());
            } finally { backup.delete(); }
        }
    }

    @Test public void repairStateStreamsAcrossMetadataPagesWithOpaquePerSourceIndexes()
            throws Exception {
        Map<String, String> notes = new LinkedHashMap<>();
        for (int i = 0; i < 270; i++)
            notes.put(BalanceData.noteKey(tx("Pasargad", PADDED, T + i, null, null)), "words " + i);
        MetadataStore.replaceText(context, MetadataStore.NOTES, notes);
        DottedAccountMigration.ensure(context);
        assertEquals(540, repairStates().length());
        try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                DataGeneration.context(context).getDatabasePath(MetadataStore.DB_NAME).getPath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                Cursor rows = db.query("store_meta", new String[]{"key"}, "key LIKE ?",
                    new String[]{"dotted_account_v1:%"}, null, null, null)) {
            assertEquals(540, rows.getCount());
            while (rows.moveToNext()) {
                assertFalse(rows.getString(0).contains(ACCOUNT));
                assertFalse(rows.getString(0).contains("Pasargad"));
            }
        }
        File backup = File.createTempFile("dotted-paged-", ".balance", context.getCacheDir());
        try {
            BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
            BalanceData.reset(context, true);
            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null,
                EnumSet.of(BackupManager.Section.NOTES));
            assertEquals(540, repairStates().length());
            assertEquals(540, BalanceData.readNotes(context).size());
            assertEquals("words 269", BalanceData.getNote(context,
                tx("Pasargad", ACCOUNT, T + 269, null, null)));
        } finally { backup.delete(); }
    }

    @Test public void framedFullRestoreImportsCanonicalOriginalBeforePaddedCopies() throws Exception {
        checkConflictingOriginalRestore(true, false);
    }

    @Test public void framedMetadataOnlyRestoreImportsCanonicalOriginalBeforePaddedCopies()
            throws Exception {
        checkConflictingOriginalRestore(true, true);
    }

    @Test public void legacyFullRestoreImportsCanonicalOriginalBeforePaddedCopies() throws Exception {
        checkConflictingOriginalRestore(false, false);
    }

    @Test public void legacyMetadataOnlyRestoreImportsCanonicalOriginalBeforePaddedCopies()
            throws Exception {
        checkConflictingOriginalRestore(false, true);
    }

    private void checkConflictingOriginalRestore(boolean framed, boolean metadataOnly) throws Exception {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            for (boolean canonicalFirst : Arrays.asList(false, true)) {
                BalanceData.reset(context, true);
                Transaction padded = tx(bank, PADDED, T, null, null);
                Transaction canonical = DottedAccountMigration.transaction(padded);
                TransactionStore.replace(context, Arrays.asList(padded, canonical));
                putConflictingMetadata(padded, canonical, canonicalFirst);
                assertEquals(0, repairStates().length());
                File backup = File.createTempFile("dotted-original-order-", ".balance", context.getCacheDir());
                try {
                    if (framed) BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
                    else BackupManager.create(context, Uri.fromFile(backup), "test backup password");
                    BalanceData.reset(context, true);
                    EnumSet<BackupManager.Section> selection = metadataOnly
                        ? allMetadataSections() : BackupManager.allSections();
                    for (int pass = 0; pass < 2; pass++) {
                        BackupManager.RestoreResult result = BackupManager.restore(context,
                            Uri.fromFile(backup), "test backup password", null, selection);
                        assertEquals(!metadataOnly && pass == 0 ? 1 : 0, result.transactionsAdded);
                        if (pass > 0) assertFalse(result.changed());
                        assertEquals(metadataOnly ? 0 : 1, BalanceData.readTransactions(context).size());
                        assertConflictingMetadata(padded, canonical);
                    }
                } finally { backup.delete(); }
            }
        }
    }

    @Test public void oldCanonicalAliasesArchiveConflictsWithoutOverwritingLocalSignedSurvivor()
            throws Exception {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            for (boolean canonicalFirst : Arrays.asList(false, true)) {
                BalanceData.reset(context, true);
                Transaction padded = tx(bank, PADDED, T, null, null);
                Transaction canonical = DottedAccountMigration.transaction(padded);
                Transaction signed = BalanceData.parseMovement(bank, sender(bank), paddedBody(), T, false, 0);
                assertNotNull(signed);
                // The padded annotation is an orphan: only the canonical transaction can prove
                // the route to the local signed survivor, including for the orphan's tags.
                TransactionStore.replace(context, Arrays.asList(canonical));
                putConflictingMetadata(padded, canonical, canonicalFirst);
                File backup = File.createTempFile("dotted-local-order-", ".balance", context.getCacheDir());
                try {
                    BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
                    for (boolean metadataOnly : Arrays.asList(false, true)) {
                        BalanceData.reset(context, true);
                        TransactionStore.replace(context, Arrays.asList(signed));
                        for (int kind : Arrays.asList(MetadataStore.NOTES, MetadataStore.REASONS,
                                MetadataStore.CHANNELS, MetadataStore.MERCHANTS))
                            MetadataStore.setText(context, kind, BalanceData.noteKey(signed), "local words " + kind);
                        MetadataStore.setTags(context, signed, Arrays.asList("Local"));
                        EnumSet<BackupManager.Section> selection = metadataOnly
                            ? allMetadataSections() : BackupManager.allSections();
                        // Failure after all import/copy passes still exposes none of their rows.
                        BackupV2Restore.setPublishHookForTests(point -> {
                            if (point == DataGeneration.PublishPoint.BEFORE_MANIFEST)
                                throw new java.io.IOException("injected restore-order publication failure");
                        });
                        try {
                            BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null, selection);
                            fail("restore should not publish");
                        } catch (java.io.IOException expected) {
                            assertNull(BalanceData.getNote(context, padded));
                            assertNull(BalanceData.getNote(context, canonical));
                            assertEquals(0, repairStates().length());
                            assertEquals("local words " + MetadataStore.NOTES, BalanceData.getNote(context, signed));
                        } finally { BackupV2Restore.setPublishHookForTests(null); }
                        for (int pass = 0; pass < 2; pass++) {
                            BackupManager.RestoreResult result = BackupManager.restore(context,
                                Uri.fromFile(backup), "test backup password", null, selection);
                            assertEquals(0, result.transactionsAdded);
                            if (pass > 0) assertFalse(result.changed());
                            assertConflictingMetadata(padded, canonical, false);
                            for (int kind : Arrays.asList(MetadataStore.NOTES, MetadataStore.REASONS,
                                    MetadataStore.CHANNELS, MetadataStore.MERCHANTS))
                                assertEquals("local words " + kind,
                                    MetadataStore.getText(context, kind, BalanceData.noteKey(signed)));
                            assertEquals(Arrays.asList("Local", "Canonical", "Shared", "Old"),
                                BalanceData.getTags(context, signed));
                        }
                    }
                } finally { backup.delete(); }
            }
        }
    }

    @Test public void protectedCanonicalAliasesReachSignedSurvivorBeforeActivePaddedAliases()
            throws Exception {
        checkProtectedCanonicalRestore(true, false);
    }

    @Test public void framedProtectedCanonicalAliasesKeepPaddedArchivesAndLocalPrecedence()
            throws Exception {
        checkProtectedCanonicalRestore(true, true);
    }

    @Test public void legacyProtectedCanonicalAliasesKeepPaddedArchivesAndLocalPrecedence()
            throws Exception {
        checkProtectedCanonicalRestore(false, true);
    }

    private void checkProtectedCanonicalRestore(boolean framed, boolean archivePadded) throws Exception {
        for (String bank : Arrays.asList("Pasargad", "Wepod")) {
            for (boolean canonicalFirst : Arrays.asList(false, true)) {
                BalanceData.reset(context, true);
                Transaction padded = tx(bank, PADDED, T, null, null);
                Transaction canonical = DottedAccountMigration.transaction(padded);
                Transaction signed = BalanceData.parseMovement(bank, sender(bank), paddedBody(), T, false, 0);
                assertNotNull(signed);
                TransactionStore.replace(context, Arrays.asList(padded, canonical));
                putConflictingMetadata(padded, canonical, canonicalFirst);
                if (archivePadded) MetadataStore.copyKeysPreservingSources(context,
                    Collections.singletonMap(BalanceData.noteKey(padded), BalanceData.noteKey(canonical)));
                else MetadataStore.protectDottedTransactions(context, Arrays.asList(padded, canonical));
                assertEquals(10, repairStates().length());
                File backup = File.createTempFile("dotted-protected-order-", ".balance", context.getCacheDir());
                try {
                    if (framed) BackupManager.createFramed(context, Uri.fromFile(backup), "test backup password");
                    else BackupManager.create(context, Uri.fromFile(backup), "test backup password");
                    for (boolean metadataOnly : Arrays.asList(false, true)) {
                        BalanceData.reset(context, true);
                        TransactionStore.replace(context, Arrays.asList(signed));
                        EnumSet<BackupManager.Section> selection = metadataOnly
                            ? allMetadataSections() : BackupManager.allSections();
                        for (int pass = 0; pass < 2; pass++) {
                            BackupManager.RestoreResult result = BackupManager.restore(context,
                                Uri.fromFile(backup), "test backup password", null, selection);
                            assertEquals(0, result.transactionsAdded);
                            if (pass > 0) assertFalse(result.changed());
                            assertEquals(1, BalanceData.readTransactions(context).size());
                            assertEquals(signed.sig, BalanceData.readTransactions(context).get(0).sig);
                            assertConflictingMetadata(padded, canonical, archivePadded);
                            assertCanonicalText(signed);
                            assertEquals(Arrays.asList("Canonical", "Shared", "Old"),
                                BalanceData.getTags(context, signed));
                        }
                        MetadataStore.setNote(context, signed, "local survivor words");
                        BackupManager.restore(context, Uri.fromFile(backup), "test backup password", null, selection);
                        assertEquals("local survivor words", BalanceData.getNote(context, signed));
                        MetadataStore.setNote(context, signed, null);
                        MetadataStore.setTags(context, signed, Collections.emptyList());
                        BackupManager.RestoreResult repeated = BackupManager.restore(context,
                            Uri.fromFile(backup), "test backup password", null, selection);
                        assertFalse(repeated.changed());
                        assertNull(BalanceData.getNote(context, signed));
                        assertTrue(BalanceData.getTags(context, signed).isEmpty());
                        assertConflictingMetadata(padded, canonical, archivePadded);
                    }
                } finally { backup.delete(); }
            }
        }
    }

    private static EnumSet<BackupManager.Section> allMetadataSections() {
        return EnumSet.of(BackupManager.Section.NOTES, BackupManager.Section.REASONS,
            BackupManager.Section.CHANNELS, BackupManager.Section.TAGS, BackupManager.Section.MERCHANTS);
    }

    private void putConflictingMetadata(Transaction padded, Transaction canonical, boolean canonicalFirst)
            throws Exception {
        for (Transaction row : canonicalFirst ? Arrays.asList(canonical, padded) : Arrays.asList(padded, canonical)) {
            boolean clean = ACCOUNT.equals(row.account);
            for (int kind : Arrays.asList(MetadataStore.NOTES, MetadataStore.REASONS,
                    MetadataStore.CHANNELS, MetadataStore.MERCHANTS))
                MetadataStore.setText(context, kind, BalanceData.noteKey(row),
                    (clean ? "canonical words " : "old words ") + kind);
            MetadataStore.setTags(context, row, Arrays.asList(clean ? "Canonical" : "Old", "Shared"));
        }
    }

    private void assertCanonicalText(Transaction canonical) throws Exception {
        for (int kind : Arrays.asList(MetadataStore.NOTES, MetadataStore.REASONS,
                MetadataStore.CHANNELS, MetadataStore.MERCHANTS))
            assertEquals("canonical words " + kind,
                MetadataStore.getText(context, kind, BalanceData.noteKey(canonical)));
    }

    private void assertConflictingMetadata(Transaction padded, Transaction canonical) throws Exception {
        assertConflictingMetadata(padded, canonical, true);
    }

    private void assertConflictingMetadata(Transaction padded, Transaction canonical, boolean canonicalHasOldTags)
            throws Exception {
        assertCanonicalText(canonical);
        for (int kind : Arrays.asList(MetadataStore.NOTES, MetadataStore.REASONS,
                MetadataStore.CHANNELS, MetadataStore.MERCHANTS))
            assertEquals("old words " + kind, MetadataStore.getText(context, kind, BalanceData.noteKey(padded)));
        assertEquals(Arrays.asList("Old", "Shared"), BalanceData.getTags(context, padded));
        assertEquals(canonicalHasOldTags ? Arrays.asList("Canonical", "Shared", "Old")
            : Arrays.asList("Canonical", "Shared"), BalanceData.getTags(context, canonical));
    }

    private JSONArray repairStates() throws Exception {
        StringWriter text = new StringWriter();
        android.util.JsonWriter writer = new android.util.JsonWriter(text);
        MetadataStore.writeAccountRepairState(context, writer);
        writer.flush();
        return new JSONArray(text.toString());
    }

    private static String sender(String bank) {
        return "Wepod".equals(bank) ? "Wepod" : "B.Pasargad";
    }

    private static String paddedBody() {
        return PADDED + "\nبرداشت:100\nمانده:900";
    }

    private static Transaction oldEvent(String bank, String body) {
        String sender = sender(bank);
        return new Transaction(bank, PADDED, T, -100, 900L,
            "e2:" + BalanceData.messageSig(sender, body, PADDED) + ":a::" + T,
            BalanceData.contentHash(sender, body));
    }

    private void checkOlderInbox(String bank, String sender) throws Exception {
        prepareInbox();
        String body = ACCOUNT + "\nبرداشت:100\nمانده:900";
        seed(sender, body);
        awaitInbox(1);
        LinkedHashMap<String, Bank> saved = new LinkedHashMap<>();
        put(saved, bank, PADDED, 1500, T + 60_000);
        assertTrue(BalanceData.write(context, saved));
        // The retained snapshot's physical SMS and source evidence are both gone.
        assertEquals(0, SourceStore.sourceCount(context));
        BalanceData.ScanOutcome outcome = BalanceData.scanSmsOutcome(context, saved, null);
        assertEquals(BalanceData.ScanOutcome.Status.SUCCESS, outcome.status);
        assertEquals(1, saved.size());
        Bank retained = saved.get(bank + "|" + ACCOUNT);
        assertNotNull(retained);
        assertEquals(1500, retained.amount);
        assertEquals(T + 60_000, retained.date);
        assertEquals(ACCOUNT, retained.account);
        assertFalse(saved.containsKey(bank + "|" + PADDED));
        assertEquals(1, BalanceData.read(context).size());
    }

    private void prepareInbox() throws Exception {
        usesInbox = true;
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        exec("pm grant " + context.getPackageName() + " android.permission.READ_SMS");
        clearInbox();
    }

    private void seed(String sender, String body) throws Exception {
        seed(sender, body, T);
    }

    private void seed(String sender, String body, long arrival) throws Exception {
        String body64 = Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.SEED -e sender " + sender
            + " -e body64 " + body64 + " -e base " + arrival);
    }

    private static Transaction tx(String bank, String account, long date, String sig, String content) {
        return new Transaction(bank, account, date, -100, 900L, sig, content);
    }

    private static void put(LinkedHashMap<String, Bank> map, String bank, String account,
            long amount, long date) {
        map.put(BalanceData.storageKey(bank, account), new Bank(bank, amount, date, bank, account));
    }

    private void exec(String command) throws Exception {
        android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommand(command);
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[2048];
            while (input.read(buffer) >= 0) { }
        }
    }

    private void clearInbox() throws Exception {
        exec("am broadcast -n com.ashkanrafiee.smsinject/.SeedReceiver"
            + " -a com.ashkanrafiee.smsinject.CLEAR");
        awaitInbox(0);
    }

    private void awaitInbox(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms._ID}, null, null, null)) {
                if (cursor != null && cursor.getCount() == count) return;
            }
            Thread.sleep(150);
        }
        fail("SMS inbox did not reach expected row count " + count);
    }
}
