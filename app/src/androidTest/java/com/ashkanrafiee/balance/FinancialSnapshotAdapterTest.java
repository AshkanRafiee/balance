package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

    private static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }
}
