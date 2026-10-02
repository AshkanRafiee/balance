package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.Test;

public class FinancialSnapshotAdapterTest {
    private static final String[] JSON = { FinancialSnapshotAdapter.BALANCES,
            FinancialSnapshotAdapter.TRANSACTIONS, FinancialSnapshotAdapter.TRANSACTION_NOTES,
            FinancialSnapshotAdapter.TRANSACTION_REASONS, FinancialSnapshotAdapter.TRANSACTION_CHANNELS,
            FinancialSnapshotAdapter.RECENT_MOVEMENTS, FinancialSnapshotAdapter.HISTORY_LAST_BALANCE };
    private static final String[] LONGS = { FinancialSnapshotAdapter.SCANNED_THROUGH,
            FinancialSnapshotAdapter.HISTORY_THROUGH };
    private static final String[] INTS = { FinancialSnapshotAdapter.RULES_VERSION,
            FinancialSnapshotAdapter.HISTORY_RULES_VERSION, FinancialSnapshotAdapter.HISTORY_SCHEMA };

    private static final class Fake implements FinancialRepository.Backend {
        String revision = "r0";
        Map<String, byte[]> values = new LinkedHashMap<>();
        int commits;

        @Override public FinancialRepository.Snapshot load() { return new FinancialRepository.Snapshot(revision, values); }
        @Override public void commit(FinancialRepository.Snapshot base, Map<String, byte[]> next)
                throws IOException {
            if (!revision.equals(base.revision())) throw new IOException("STALE");
            values = new LinkedHashMap<>(next);
            revision = "r" + (++commits);
        }
    }

    @Test public void preservesAllRecognizedBytesUnknownsAndEmptyPresentValues() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        Map<String, byte[]> expected = new LinkedHashMap<>();
        for (int i = 0; i < JSON.length; i++) expected.put(JSON[i], (i & 1) == 0 ? bytes("{}") : bytes("[]"));
        expected.put(LONGS[0], bytes("0"));
        expected.put(LONGS[1], bytes("-9223372036854775808"));
        expected.put(INTS[0], bytes("2147483647"));
        expected.put(INTS[1], bytes("-1"));
        expected.put(INTS[2], bytes("0"));
        expected.put("future_component", new byte[0]);
        adapter.transaction(draft -> { for (Map.Entry<String, byte[]> e : expected.entrySet()) draft.put(e.getKey(), e.getValue()); return null; });

        FinancialSnapshotAdapter.Snapshot snapshot = adapter.snapshot();
        assertEquals("r1", snapshot.revision());
        assertEquals(expected.keySet(), snapshot.components().keySet());
        for (String name : expected.keySet()) assertArrayEquals(expected.get(name), snapshot.get(name));
        assertTrue(snapshot.contains("future_component"));
        assertFalse(snapshot.contains("absent"));
        assertNull(snapshot.get("absent"));
    }

    @Test public void unrelatedMutationPreservesUnknownExtension() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> { draft.put("future", new byte[] { 9, 8, 7 }); return null; });
        adapter.transaction(draft -> { draft.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("1")); return null; });
        assertArrayEquals(new byte[] { 9, 8, 7 }, adapter.snapshot().get("future"));
        assertEquals(2, fake.commits);
    }

    @Test public void rejectsInvalidNumericWithoutCommit() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        try {
            adapter.transaction(draft -> { draft.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("01")); return null; });
            fail();
        } catch (IOException expected) { assertNotNull(expected); }
        assertEquals(0, fake.commits);
        assertFalse(adapter.snapshot().contains(FinancialSnapshotAdapter.RULES_VERSION));
    }

    @Test public void strictlyValidatesExcludedArrayAndCompleteJsonDocument() throws Exception {
        for (String value : new String[] { "{} trailing", "{}{}", "{\"x\":1,\"\\u0078\":2}",
                "[1]", "[\"\\uD800\"]" }) {
            Fake fake = new Fake();
            FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
            try {
                adapter.transaction(draft -> {
                    draft.put(FinancialSnapshotAdapter.EXCLUDED_BANKS, bytes(value));
                    return null;
                });
                fail("accepted invalid JSON: " + value);
            } catch (IOException expected) { }
            assertEquals(0, fake.commits);
        }
    }

    @Test public void nestedTransactionsShareDraftAndCommitOnce() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(outer -> {
            outer.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("1"));
            adapter.transaction(inner -> { inner.put("future", new byte[] { 4 }); return null; });
            assertArrayEquals(new byte[] { 4 }, outer.get("future"));
            return null;
        });
        assertEquals(1, fake.commits);
        assertArrayEquals(new byte[] { 4 }, adapter.snapshot().get("future"));
    }

    @Test public void publicationUpdatesBalanceScanComponentsOnceAndPreservesOmittedData() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put("future", new byte[] { 9, 8 });
            draft.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("{\"transactions\":[]}"));
            return null;
        });
        int commits = fake.commits;
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.BALANCES, bytes("{}"));
        updates.put(FinancialSnapshotAdapter.SCANNED_THROUGH, bytes("42"));
        updates.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("7"));
        adapter.publish(updates);
        assertEquals(commits + 1, fake.commits);
        assertArrayEquals(bytes("{}"), adapter.snapshot().get(FinancialSnapshotAdapter.BALANCES));
        assertArrayEquals(bytes("42"), adapter.snapshot().get(FinancialSnapshotAdapter.SCANNED_THROUGH));
        assertArrayEquals(bytes("{\"transactions\":[]}"),
                adapter.snapshot().get(FinancialSnapshotAdapter.TRANSACTIONS));
        assertArrayEquals(new byte[] { 9, 8 }, adapter.snapshot().get("future"));
    }

    @Test public void invalidPublicationDoesNotCommitAnyUpdate() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.publish(Collections.singletonMap(FinancialSnapshotAdapter.BALANCES, bytes("{}")));
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"ok\":1}"));
        updates.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("01"));
        try { adapter.publish(updates); fail("invalid publication committed"); }
        catch (IOException expected) { }
        assertEquals(1, fake.commits);
        assertArrayEquals(bytes("{}"), adapter.snapshot().get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void historyPublicationCommitsOnceAndPreservesBalanceWatermark() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        Map<String, byte[]> balance = new LinkedHashMap<>();
        balance.put(FinancialSnapshotAdapter.BALANCES, bytes("{}"));
        balance.put(FinancialSnapshotAdapter.SCANNED_THROUGH, bytes("42"));
        balance.put(FinancialSnapshotAdapter.RULES_VERSION, bytes("7"));
        adapter.publishBalance(balance);
        int commits = fake.commits;

        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("[]"));
        updates.put(FinancialSnapshotAdapter.HISTORY_THROUGH, bytes("99"));
        updates.put(FinancialSnapshotAdapter.HISTORY_RULES_VERSION, bytes("2"));
        updates.put(FinancialSnapshotAdapter.HISTORY_SCHEMA, bytes("1"));
        adapter.publishHistory(updates);

        assertEquals(commits + 1, fake.commits);
        assertArrayEquals(bytes("99"), adapter.snapshot().get(FinancialSnapshotAdapter.HISTORY_THROUGH));
        assertArrayEquals(bytes("42"), adapter.snapshot().get(FinancialSnapshotAdapter.SCANNED_THROUGH));
        assertArrayEquals(bytes("{}"), adapter.snapshot().get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void historyPublicationRejectsUnknownKeyBeforeTransaction() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("[]"));
        updates.put(FinancialSnapshotAdapter.SCANNED_THROUGH, bytes("42"));
        try {
            adapter.publishHistory(updates);
            fail("accepted balance-owned key");
        } catch (IllegalArgumentException expected) { }
        assertEquals(0, fake.commits);
    }

    @Test public void historyPublicationWithInvalidUpdateDoesNotPartiallyCommit() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        Map<String, byte[]> updates = new LinkedHashMap<>();
        updates.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("[]"));
        updates.put(FinancialSnapshotAdapter.HISTORY_SCHEMA, bytes("01"));
        try {
            adapter.publishHistory(updates);
            fail("invalid history publication committed");
        } catch (IOException expected) { }
        assertEquals(0, fake.commits);
        assertFalse(adapter.snapshot().contains(FinancialSnapshotAdapter.TRANSACTIONS));
    }

    @Test public void emptyHistoryPublicationIsAValidatedNoOp() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.publishHistory(Collections.emptyMap());
        assertEquals(0, fake.commits);
        assertTrue(adapter.snapshot().components().isEmpty());
    }

    @Test public void typedComponentCodecsRoundTripThroughOneDraft() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            LinkedHashMap<String, Bank> balances = new LinkedHashMap<>();
            balances.put("Bank|001", new Bank("Bank", 123L, 456L, "sender", "001"));
            draft.putBalances(balances);
            List<Transaction> transactions = new ArrayList<>();
            transactions.add(new Transaction("Bank", "001", 456L, -10L, 113L, "sig", "content"));
            draft.putTransactions(transactions);
            Map<String, String> notes = new LinkedHashMap<>();
            notes.put("c:content", "note");
            draft.putTransactionNotes(notes);
            Map<String, List<Reconcile.Entry>> movements = new LinkedHashMap<>();
            List<Reconcile.Entry> entries = new ArrayList<>();
            entries.add(new Reconcile.Entry(456L, -10L, 113L, "sig"));
            movements.put("Bank|001", entries);
            draft.putRecentMovements(movements);
            Map<String, Long> last = new LinkedHashMap<>();
            last.put("Bank|001", 113L);
            draft.putHistoryLastBalances(last);
            draft.putScannedThrough(9L);
            draft.putRulesVersion(3);
            return null;
        });

        FinancialSnapshotAdapter.Snapshot snapshot = adapter.snapshot();
        assertEquals(123L, snapshot.balances().get("Bank|001").amount);
        assertEquals(1, snapshot.transactions().size());
        assertEquals("note", snapshot.transactionNotes().get("c:content"));
        assertEquals(1, snapshot.recentMovements().get("Bank|001").size());
        assertEquals(Long.valueOf(113L), snapshot.historyLastBalances().get("Bank|001"));
        assertEquals(9L, snapshot.scannedThrough());
        assertEquals(3, snapshot.rulesVersion());
        assertEquals(1, fake.commits);
    }

    @Test public void typedEmptyWritesRemoveLegacyEmptyComponents() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.TRANSACTIONS, bytes("{\"transactions\":[]}"));
            draft.put(FinancialSnapshotAdapter.TRANSACTION_NOTES, bytes("{}"));
            return null;
        });
        adapter.transaction(draft -> {
            draft.putTransactions(Collections.emptyList());
            draft.putTransactionNotes(Collections.emptyMap());
            return null;
        });
        assertFalse(adapter.snapshot().contains(FinancialSnapshotAdapter.TRANSACTIONS));
        assertFalse(adapter.snapshot().contains(FinancialSnapshotAdapter.TRANSACTION_NOTES));
    }

    @Test public void restrictedPublicationCanRemoveOwnedComponentsAtomically() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.publishBalance(new LinkedHashMap<String, byte[]>() {{
            put(FinancialSnapshotAdapter.BALANCES, bytes("{}"));
            put(FinancialSnapshotAdapter.SCANNED_THROUGH, bytes("4"));
            put(FinancialSnapshotAdapter.RULES_VERSION, bytes("1"));
        }});
        int before = fake.commits;
        adapter.publishBalance(Collections.singletonMap(FinancialSnapshotAdapter.BALANCES, bytes("{}")),
                Collections.singleton(FinancialSnapshotAdapter.SCANNED_THROUGH));
        assertEquals(before + 1, fake.commits);
        assertFalse(adapter.snapshot().contains(FinancialSnapshotAdapter.SCANNED_THROUGH));
        assertTrue(adapter.snapshot().contains(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void worksWithEncryptedGenerationStore() throws Exception {
        File root = new File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "financial-adapter-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        try {
            FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(
                    FinancialRepository.generationBackend(new EncryptedGenerationStore(root, key))));
            adapter.transaction(draft -> { draft.put("future", new byte[0]); return null; });
            assertTrue(adapter.snapshot().contains("future"));
            assertArrayEquals(new byte[0], adapter.snapshot().get("future"));
        } finally { delete(root); }
    }

    @Test public void aRepeatReadOfOneGenerationIsCheckedOnlyOnce() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":1}"));
            return null;
        });
        // A generation names itself and never changes its bytes, so the second read of the same one
        // is looking at components that were already decrypted, copied and validated. The dashboard
        // reads several times per refresh and pays for the whole store every time.
        FinancialSnapshotAdapter.Snapshot first = adapter.snapshot();
        assertSame("the same generation must not be checked twice", first, adapter.snapshot());
        assertSame("nor again", first, adapter.snapshot());
        assertArrayEquals(bytes("{\"a\":1}"), first.get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void aCommitIsVisibleOnTheNextRead() throws Exception {
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":1}"));
            return null;
        });
        FinancialSnapshotAdapter.Snapshot first = adapter.snapshot();
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":2}"));
            return null;
        });
        FinancialSnapshotAdapter.Snapshot second = adapter.snapshot();
        assertNotSame("a new generation must not be answered from the old one", first, second);
        assertArrayEquals(bytes("{\"a\":2}"), second.get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void anIdenticalCommitIsStillANewGeneration() throws Exception {
        // Even a commit that writes back what it read gets a fresh id from the real store, so a
        // reader may not treat "the bytes are equal" as "nothing happened".
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":1}"));
            return null;
        });
        FinancialSnapshotAdapter.Snapshot first = adapter.snapshot();
        fake.revision = first.revision();
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":2}"));
            return null;
        });
        assertArrayEquals(bytes("{\"a\":2}"),
                adapter.snapshot().get(FinancialSnapshotAdapter.BALANCES));
    }

    @Test public void aFailedTransactionLeavesNoDraftBehindToBeServed() throws Exception {
        // The one thing the checked-read cache must never keep: a read taken from inside a
        // transaction sees that transaction's uncommitted work under the revision the transaction
        // started from. If that draft were remembered under that revision, a transaction that then
        // failed would leave work that was never written being served as the committed state.
        Fake fake = new Fake();
        FinancialSnapshotAdapter adapter = new FinancialSnapshotAdapter(new FinancialRepository(fake));
        adapter.transaction(draft -> {
            draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":1}"));
            return null;
        });
        try {
            adapter.transaction(draft -> {
                draft.put(FinancialSnapshotAdapter.BALANCES, bytes("{\"a\":2}"));
                // Read from inside the transaction: the uncommitted value is what it must see.
                assertArrayEquals(bytes("{\"a\":2}"),
                        adapter.snapshot().get(FinancialSnapshotAdapter.BALANCES));
                throw new IOException("FAILED");
            });
            fail("a failed transaction committed");
        } catch (IOException expected) { }

        assertArrayEquals("a draft that was never committed must not be served afterwards",
                bytes("{\"a\":1}"), adapter.snapshot().get(FinancialSnapshotAdapter.BALANCES));
    }

    private static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }
}
