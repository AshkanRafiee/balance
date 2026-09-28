package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public class FinancialOperationsTest {
    private static final Transaction TX = new Transaction("bank", "account", 7, 9, "sig", "digest");
    private static final String[] OWNED = { "balances", "transactions", "transaction_notes", "history_last_balance",
            "recent_movements", "transaction_reasons", "transaction_channels", "scanned_through",
            "rules_version", "history_through", "history_rules_version", "history_schema", "excluded_banks" };

    private static final class Fixture implements AutoCloseable {
        final File root;
        final SecretKey key;
        final FinancialRepository repo;
        final FinancialOperations ops;
        boolean failPublication;
        int publications;

        Fixture() throws Exception {
            root = new File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getCacheDir(), "financial-operations-" + UUID.randomUUID());
            assertTrue(root.mkdir());
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            key = generator.generateKey();
            EncryptedGenerationStore store = new EncryptedGenerationStore(root, key,
                    EncryptedGenerationStore.Limits.defaults(), step -> {
                        if (step == EncryptedGenerationStore.Step.ACTIVE_SYNCED) {
                            if (failPublication) throw new IOException("injected");
                            publications++;
                        }
                    });
            repo = new FinancialRepository(FinancialRepository.generationBackend(store));
            ops = new FinancialOperations(repo);
        }

        void put(String name, String value) throws IOException {
            repo.transaction(draft -> { draft.put(name, bytes(value)); return null; });
        }

        FinancialRepository.Snapshot reopen() throws IOException {
            return new FinancialRepository(FinancialRepository.generationBackend(
                    new EncryptedGenerationStore(root, key))).snapshot();
        }

        @Override public void close() { delete(root); }
    }

    @Test public void noteIdentityTrimmingCapAndUnknownRecords() throws Exception {
        try (Fixture f = new Fixture()) {
            f.put("transaction_notes", "{\"unknown\":\"\",\"other\":\"kept\"}");
            Transaction legacy = new Transaction("", null, 7, 9, "sig");
            f.ops.setNote(legacy, " legacy ");
            f.ops.setNote(TX, " content ");
            assertEquals("legacy", f.ops.notes().get(BalanceData.noteKey(legacy)));
            assertEquals("content", f.ops.notes().get("c:digest"));
            f.ops.setNote(new Transaction("changed", "changed", 70, 90, "changed", "digest"), "same");
            assertEquals("same", f.ops.notes().get("c:digest"));
            char[] chars = new char[499];
            Arrays.fill(chars, 'x');
            f.ops.setNote(TX, " " + new String(chars) + "\ud83d\ude00z ");
            assertEquals(new String(chars), f.ops.notes().get("c:digest"));
            f.ops.setNote(TX, " \t ");
            assertFalse(f.ops.notes().containsKey("c:digest"));
            assertEquals("legacy", f.ops.notes().get(BalanceData.noteKey(legacy)));
            assertNull(f.ops.notes().get("unknown"));
            assertEquals("kept", f.ops.notes().get("other"));
            f.ops.setNote(legacy, null);
            assertFalse(f.ops.notes().containsKey(BalanceData.noteKey(legacy)));
            f.ops.notes().clear();
            assertEquals(1, f.ops.notes().size());
        }
    }

    @Test public void exclusionsPreserveExactKeysAndEmptySetEncoding() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.setExcluded(new LinkedHashSet<>(Arrays.asList("", "bank", "bank|account")));
            f.ops.toggleExcluded("bank");
            assertEquals(new LinkedHashSet<>(Arrays.asList("", "bank|account")), f.ops.getExcluded());
            f.ops.getExcluded().clear();
            assertEquals(2, f.ops.getExcluded().size());
            f.ops.toggleExcluded("");
            f.ops.toggleExcluded("bank|account");
            assertArrayEquals(bytes("[]"), f.repo.snapshot().get("excluded_banks"));
        }
    }

    @Test public void resetAllowlistAndNestedChangesPublishOneGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.repo.transaction(draft -> {
                for (String name : OWNED) draft.put(name,
                        bytes(name.equals("transaction_notes") ? "{\"seed\":\"note\"}" : "malformed metadata"));
                draft.put("excluded_banks", bytes("[]"));
                draft.put("future_private", bytes("opaque"));
                return null;
            });
            FinancialRepository.Snapshot pinned = f.repo.snapshot();
            int before = f.publications;
            f.repo.transaction(draft -> {
                f.ops.setNote(TX, "kept");
                f.ops.toggleExcluded("bank");
                assertTrue(f.ops.getExcluded().contains("bank"));
                f.ops.reset(false);
                assertTrue(f.ops.getExcluded().isEmpty());
                assertEquals("kept", f.ops.notes().get("c:digest"));
                return null;
            });
            assertEquals(before + 1, f.publications);
            FinancialRepository.Snapshot after = f.reopen();
            assertNotEquals(pinned.revision(), after.revision());
            for (String name : OWNED) {
                if (name.equals("transaction_notes")) {
                    assertNotNull(after.get(name));
                } else {
                    assertNull(after.get(name));
                    assertNotNull(pinned.get(name));
                }
            }
            assertArrayEquals(bytes("opaque"), after.get("future_private"));
            assertEquals(2, after.components().size());
            f.ops.reset(true);
            assertEquals(1, f.reopen().components().size());
            assertTrue(f.ops.notes().isEmpty());
        }
    }

    @Test public void failuresBeforeActivePublicationLeaveEveryComponentUntouched() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.setNote(TX, "original");
            f.ops.setExcluded(new LinkedHashSet<>(Arrays.asList("original")));
            f.put("scanned_through", "42");
            f.put("future_private", "opaque");
            FinancialRepository.Snapshot before = f.reopen();
            f.failPublication = true;
            IoWork[] changes = {
                    () -> f.ops.setNote(TX, "changed"),
                    () -> f.ops.toggleExcluded("new"),
                    () -> f.ops.reset(false),
                    () -> f.ops.reset(true),
                    () -> f.repo.transaction(draft -> {
                        f.ops.reset(true);
                        f.ops.setNote(TX, "changed");
                        f.ops.toggleExcluded("new");
                        return null;
                    }) };
            for (IoWork change : changes) {
                rejected(change);
                same(before, f.reopen());
            }
        }
    }

    @Test public void malformedTouchedComponentsFailWithoutCoercionOrPartialWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            String[] badNotes = { "[]", "{\"private\":4}", "{\"private\":null}",
                    "{\"private\":{}}", "{\"a\":\"x\",\"a\":\"y\"}",
                    "{\"a\":\"x\",\"\\u0061\":\"y\"}", "{} {}", "{a:'x'}",
                    "{\"a\":\"\\q\"}", "{\"a\":\"raw\ncontrol\"}", "{\"a\":\"\\ud800\"}" };
            for (String value : badNotes) {
                f.put("transaction_notes", value);
                FinancialRepository.Snapshot before = f.repo.snapshot();
                rejected(() -> f.ops.notes());
                rejected(() -> f.ops.setNote(TX, "replacement"));
                same(before, f.reopen());
            }
            f.repo.transaction(draft -> {
                draft.put("transaction_notes", new byte[] { (byte) 0xff }); return null;
            });
            rejected(() -> f.ops.notes());
            f.ops.reset(true);
            for (String value : new String[] { "{}", "[1]", "[null]", "[true]", "[[]]", "[] []" }) {
                f.put("excluded_banks", value);
                FinancialRepository.Snapshot before = f.repo.snapshot();
                rejected(() -> f.ops.getExcluded());
                rejected(() -> f.ops.toggleExcluded("bank"));
                rejected(() -> f.ops.setExcluded(new LinkedHashSet<>()));
                rejected(() -> f.repo.transaction(draft -> {
                    f.ops.setNote(TX, "must roll back");
                    f.ops.toggleExcluded("bank");
                    return null;
                }));
                same(before, f.reopen());
            }
        }
    }

    private interface IoWork { void run() throws IOException; }
    private static void rejected(IoWork work) throws IOException {
        try { work.run(); fail("Expected failure"); }
        catch (IOException expected) {
            for (Throwable t = expected; t != null; t = t.getCause())
                assertTrue(t.getMessage().matches("[A-Z_]+"));
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void same(FinancialRepository.Snapshot expected, FinancialRepository.Snapshot actual) {
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.components().keySet(), actual.components().keySet());
        for (Map.Entry<String, byte[]> entry : expected.components().entrySet())
            assertArrayEquals(entry.getValue(), actual.get(entry.getKey()));
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }
}
