package com.ashkanrafiee.balance;

import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.ashkanrafiee.balance.EncryptedGenerationStore.Limits;
import com.ashkanrafiee.balance.EncryptedGenerationStore.Snapshot;
import com.ashkanrafiee.balance.LegacyGenerationMigration.Step;

import static org.junit.Assert.*;

/** Synthetic private-cache data only; no preferences, SMS, or live BalanceData access. */
@RunWith(AndroidJUnit4.class)
public class LegacyGenerationMigrationTest {
    private static final Limits LIMITS = new Limits(8, 4096, 8192);
    private File sandbox, legacy;
    private SecretKey key;
    private int reads;

    @Before public void setUp() throws Exception {
        sandbox = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "migration-test-" + UUID.randomUUID());
        assertTrue(sandbox.mkdir());
        legacy = new File(sandbox, "legacy");
        assertTrue(legacy.mkdir());
        for (Map.Entry<String, byte[]> entry : sample().entrySet())
            write(new File(legacy, entry.getKey()), entry.getValue());
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @After public void tearDown() throws Exception { delete(sandbox); }

    private File parent() throws Exception {
        File parent = new File(sandbox, UUID.randomUUID().toString());
        assertTrue(parent.mkdir());
        return parent;
    }

    private LegacyGenerationMigration migration(File parent) throws IOException {
        return new LegacyGenerationMigration(parent, key, LIMITS);
    }

    private static Map<String, byte[]> sample() {
        Map<String, byte[]> values = new HashMap<>();
        for (String name : Arrays.asList("notes", "history", "exclusions", "checkpoints"))
            values.put(name, (name + "-synthetic-\u2603").getBytes(StandardCharsets.UTF_8));
        return values;
    }

    private Map<String, byte[]> source() throws IOException {
        reads++;
        Map<String, byte[]> values = new HashMap<>();
        for (String name : sample().keySet()) values.put(name, bytes(new File(legacy, name)));
        return values;
    }

    private Map<String, byte[]> forbidden() {
        fail("Adopted migration must never reimport");
        return null;
    }

    private void untouched() throws IOException {
        assertEquals(sample().size(), legacy.list().length);
        for (String name : sample().keySet())
            assertArrayEquals(sample().get(name), bytes(new File(legacy, name)));
    }

    private static void same(Map<String, byte[]> expected, Snapshot actual) {
        assertEquals(expected.keySet(), actual.components().keySet());
        for (String name : expected.keySet()) assertArrayEquals(expected.get(name), actual.component(name));
    }

    private interface Checked { void run() throws Exception; }
    private static IOException fails(Checked action) throws Exception {
        try { action.run(); fail("Expected failure"); }
        catch (IOException expected) { return expected; }
        throw new AssertionError();
    }

    @Test public void adoptedRoundTripCanonicalAliasAndOwnedSnapshot() throws Exception {
        File parent = parent();
        Map<String, byte[]> input = sample();
        Snapshot first = migration(parent).open(() -> { reads++; return input; }).getSnapshot();
        input.get("notes")[0] ^= 1;
        same(sample(), first);
        Snapshot again = migration(new File(parent, ".")).open(this::forbidden).getSnapshot();
        same(sample(), again);
        assertEquals(first.generation(), again.generation());
        assertEquals(1, reads);
        assertEquals(2, manifests(parent).length); // bootstrap removed before ADOPTED
        assertTrue(new File(parent, "generations").isDirectory());
        untouched();
    }

    @Test public void everyCoordinatorBoundaryRestartsOrFailsClosedBeforeFirstMarker() throws Exception {
        for (Step step : Step.values()) {
            File parent = parent();
            boolean[] hit = { false };
            LegacyGenerationMigration migration = new LegacyGenerationMigration(parent, key, LIMITS,
                    at -> { if (at == step) { hit[0] = true; throw new IOException("FAULT"); } },
                    at -> { });
            fails(() -> migration.open(this::source));
            assertTrue(step.name(), hit[0]);
            int before = reads;
            if (step == Step.PREPARING_PARTIAL || step == Step.PREPARING_SYNCED) {
                assertEquals("MISSING_STATE",
                        fails(() -> migration(parent).open(this::forbidden)).getMessage());
                assertFalse(new File(parent, "generations").exists());
            } else {
                boolean adopted = step == Step.ADOPTED_RENAMED || step == Step.ADOPTED_DURABLE;
                same(sample(), migration(parent).open(adopted ? this::forbidden : this::source)
                        .getSnapshot());
                assertEquals(before + (adopted ? 0 : 1), reads);
                same(sample(), migration(parent).open(this::forbidden).getSnapshot());
                assertEquals(2, manifests(parent).length);
            }
            untouched();
        }
    }

    @Test public void everyStoreBoundaryIncludingBootstrapCanRestartFromIntactSource() throws Exception {
        for (EncryptedGenerationStore.Step step : EncryptedGenerationStore.Step.values()) {
            File parent = parent();
            boolean[] hit = { false };
            LegacyGenerationMigration migration = new LegacyGenerationMigration(parent, key, LIMITS,
                    at -> { }, at -> {
                        if (at == step) { hit[0] = true; throw new IOException("FAULT"); }
                    });
            fails(() -> migration.open(this::source));
            assertTrue(step.name(), hit[0]);
            same(sample(), migration(parent).open(this::source).getSnapshot());
            same(sample(), migration(parent).open(this::forbidden).getSnapshot());
            assertEquals(2, manifests(parent).length);
            untouched();
        }
    }

    @Test public void secondCommitUncertaintyAndSameInstanceAdoptionUncertaintyReload() throws Exception {
        File parent = parent();
        boolean[] second = { false };
        LegacyGenerationMigration migration = new LegacyGenerationMigration(parent, key, LIMITS,
                at -> { if (at == Step.FIRST_COMMITTED) second[0] = true; }, at -> {
                    if (second[0] && at == EncryptedGenerationStore.Step.ACTIVE_RENAMED)
                        throw new IOException("FAULT");
                });
        IOException uncertain = fails(() -> migration.open(this::source));
        assertTrue(uncertain instanceof EncryptedGenerationStore.StoreException);
        assertEquals(EncryptedGenerationStore.Code.COMMIT_UNCERTAIN,
                ((EncryptedGenerationStore.StoreException) uncertain).code);
        same(sample(), migration(parent).open(this::source).getSnapshot());

        File other = parent();
        LegacyGenerationMigration sameInstance = new LegacyGenerationMigration(other, key, LIMITS,
                at -> { if (at == Step.ADOPTED_RENAMED) throw new IOException("FAULT"); }, at -> { });
        fails(() -> sameInstance.open(this::source));
        same(sample(), sameInstance.open(this::forbidden).getSnapshot());
        untouched();
    }

    @Test public void everySecondPublicationBoundaryRestartsWithoutAdoptingBootstrap() throws Exception {
        for (EncryptedGenerationStore.Step step : EncryptedGenerationStore.Step.values()) {
            // The identical second commit reuses components and does not bootstrap again.
            if (step.name().startsWith("INITIAL_") || step.name().startsWith("COMPONENT_")) continue;
            File parent = parent();
            boolean[] second = { false }, hit = { false };
            fails(() -> new LegacyGenerationMigration(parent, key, LIMITS,
                    at -> { if (at == Step.FIRST_COMMITTED) second[0] = true; }, at -> {
                        if (second[0] && at == step) {
                            hit[0] = true;
                            throw new IOException("FAULT");
                        }
                    }).open(this::source));
            assertTrue(step.name(), hit[0]);
            same(sample(), migration(parent).open(this::source).getSnapshot());
            same(sample(), migration(parent).open(this::forbidden).getSnapshot());
            assertEquals(2, manifests(parent).length);
            untouched();
        }
    }

    @Test public void adoptedCurrentDamageRecoversFullSnapshotBothManifestsFailClosed() throws Exception {
        File parent = parent();
        Snapshot snapshot = migration(parent).open(this::source).getSnapshot();
        write(new File(parent, "generations/m-" + snapshot.generation()), new byte[] { 0 });
        Snapshot recovered = migration(parent).open(this::forbidden).getSnapshot();
        assertTrue(recovered.recovered());
        same(sample(), recovered);
        write(new File(parent, "generations/m-" + recovered.generation()), new byte[] { 0 });
        fails(() -> migration(parent).open(this::forbidden));
        assertEquals(1, reads);
        untouched();
    }

    @Test public void sharedComponentDamageAndMissingDestinationNeverReimport() throws Exception {
        File parent = parent();
        Snapshot snapshot = migration(parent).open(this::source).getSnapshot();
        write(new File(parent, "generations/c-" + snapshot.componentIds().get("notes")), new byte[0]);
        fails(() -> migration(parent).open(this::forbidden));
        delete(new File(parent, "generations"));
        fails(() -> migration(parent).open(this::forbidden));
        assertTrue(new File(parent, "generations").mkdir());
        fails(() -> migration(parent).open(this::forbidden)); // pristine cannot replace adopted data
        untouched();
    }

    @Test public void explicitlyEmptySourceIsAdoptedAndRecoveredAsEmpty() throws Exception {
        File parent = parent();
        Snapshot snapshot = migration(parent).open(() -> {
            reads++;
            return Collections.emptyMap();
        }).getSnapshot();
        assertFalse(snapshot.generation().isEmpty());
        assertEquals(2, manifests(parent).length);
        write(new File(parent, "generations/m-" + snapshot.generation()), new byte[0]);
        Snapshot recovered = migration(parent).open(this::forbidden).getSnapshot();
        assertTrue(recovered.recovered());
        assertTrue(recovered.components().isEmpty());
        assertEquals(1, reads);
    }

    @Test public void missingCorruptOversizedOrWrongKeyJournalNeverReimports() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            File parent = parent();
            migration(parent).open(this::source);
            File state = new File(parent, "migration.state");
            if (mode == 0) assertTrue(state.delete());
            if (mode == 1) {
                byte[] bytes = bytes(state);
                bytes[bytes.length - 1] ^= 1;
                write(state, bytes);
            }
            if (mode == 2) write(state, new byte[1024]);
            SecretKey openKey = mode == 3 ? new SecretKeySpec(new byte[32], "AES") : key;
            fails(() -> new LegacyGenerationMigration(parent, openKey, LIMITS).open(this::forbidden));
            assertEquals(2, manifests(parent).length);
            untouched();
        }
    }

    @Test public void sourceLimitsAndKeyErrorsHaveNoFreshSideEffects() throws Exception {
        File parent = parent();
        fails(() -> migration(parent).open(() -> { throw new IOException("SOURCE"); }));
        fails(() -> migration(parent).open(() -> null));
        fails(() -> migration(parent).open(() -> Collections.singletonMap("../bad", new byte[0])));
        fails(() -> migration(parent).open(() -> Collections.singletonMap("notes", new byte[4097])));
        fails(() -> new LegacyGenerationMigration(parent, key, new Limits(1, 4096, 8192))
                .open(this::source));
        fails(() -> new LegacyGenerationMigration(parent, key, new Limits(8, 4096, 1))
                .open(this::source));
        fails(() -> new LegacyGenerationMigration(parent, new SecretKeySpec(new byte[16], "AES"), LIMITS));
        assertEquals(0, parent.list().length);
        same(sample(), migration(parent).open(this::source).getSnapshot());
        fails(() -> new LegacyGenerationMigration(parent, key, new Limits(1, 4096, 8192))
                .open(this::forbidden));
        untouched();
    }

    @Test public void preparingSourceErrorCannotDiscardCandidate() throws Exception {
        File parent = preparing();
        Map<String, byte[]> before = directoryBytes(new File(parent, "generations"));
        fails(() -> migration(parent).open(() -> { throw new IOException("SOURCE"); }));
        Map<String, byte[]> after = directoryBytes(new File(parent, "generations"));
        assertEquals(before.keySet(), after.keySet());
        for (String name : before.keySet()) assertArrayEquals(before.get(name), after.get(name));
        same(sample(), migration(parent).open(this::source).getSnapshot());
        untouched();
    }

    @Test public void foreignEntriesAndSymlinksAreRejectedBeforeDeletion() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            File parent = preparing();
            File root = new File(parent, "generations");
            File entry = new File(root, "c-00000000000000000000000000000000");
            if (mode == 0) write(new File(root, "foreign"), new byte[] { 7 });
            if (mode == 1) assertTrue(entry.mkdir());
            if (mode == 2) Os.symlink(new File(legacy, "notes").getPath(), entry.getPath());
            if (mode == 3) {
                delete(root);
                Os.symlink(legacy.getPath(), root.getPath());
            }
            fails(() -> migration(parent).open(this::forbidden));
            if (mode < 3) assertTrue(manifests(parent).length > 0);
            untouched();
        }
    }

    @Test public void canonicalParentBindingRejectsRenameAndCrossInstanceReentrance() throws Exception {
        File parent = parent();
        LegacyGenerationMigration alias = migration(new File(parent, "."));
        same(sample(), migration(parent).open(() -> {
            try {
                assertEquals("REENTRANT", fails(() -> alias.open(this::forbidden)).getMessage());
            } catch (Exception e) { throw new IOException(e); }
            return source();
        }).getSnapshot());
        File moved = new File(sandbox, "moved");
        Os.rename(parent.getPath(), moved.getPath());
        fails(() -> migration(moved).open(this::forbidden));
        untouched();
    }

    @Test public void concurrentCanonicalInstancesImportOnlyOnce() throws Exception {
        File parent = parent();
        LegacyGenerationMigration first = migration(parent);
        LegacyGenerationMigration second = migration(new File(parent, "."));
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Snapshot> a = executor.submit(() -> first.open(() -> {
                reading.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("TIMEOUT");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("INTERRUPTED");
                }
                return source();
            }).getSnapshot());
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            Future<Snapshot> b = executor.submit(() -> second.open(this::forbidden).getSnapshot());
            release.countDown();
            Snapshot one = a.get(30, TimeUnit.SECONDS), two = b.get(30, TimeUnit.SECONDS);
            same(sample(), one);
            same(sample(), two);
            assertEquals(one.generation(), two.generation());
            assertEquals(1, reads);
            untouched();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private File preparing() throws Exception {
        File parent = parent();
        fails(() -> new LegacyGenerationMigration(parent, key, LIMITS,
                at -> { if (at == Step.FIRST_COMMITTED) throw new IOException("FAULT"); }, at -> { })
                .open(this::source));
        return parent;
    }

    private static File[] manifests(File parent) {
        File[] files = new File(parent, "generations").listFiles(
                (dir, name) -> name.matches("m-[0-9a-f]{32}"));
        assertNotNull(files);
        return files;
    }

    private static Map<String, byte[]> directoryBytes(File root) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        File[] files = root.listFiles();
        assertNotNull(files);
        for (File file : files) result.put(file.getName(), bytes(file));
        return result;
    }

    private static byte[] bytes(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(file))) {
            in.readFully(bytes);
            assertEquals(-1, in.read());
        }
        return bytes;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private static void delete(File file) throws Exception {
        if (file == null) return;
        // lstat ensures test cleanup also never traverses a symlink to legacy or another directory.
        if (android.system.OsConstants.S_ISDIR(Os.lstat(file.getPath()).st_mode)) {
            File[] children = file.listFiles();
            assertNotNull(children);
            for (File child : children) delete(child);
        }
        assertTrue(file.delete());
    }
}
