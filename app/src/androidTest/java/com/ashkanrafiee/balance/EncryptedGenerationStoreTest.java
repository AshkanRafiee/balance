package com.ashkanrafiee.balance;

import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.ashkanrafiee.balance.EncryptedGenerationStore.Code;
import com.ashkanrafiee.balance.EncryptedGenerationStore.Limits;
import com.ashkanrafiee.balance.EncryptedGenerationStore.Snapshot;
import com.ashkanrafiee.balance.EncryptedGenerationStore.Step;
import com.ashkanrafiee.balance.EncryptedGenerationStore.StoreException;

/** Synthetic bytes and ephemeral keys only. Never opens preferences, SMS, or financial storage. */
@RunWith(AndroidJUnit4.class)
public class EncryptedGenerationStoreTest {
    private File sandbox;
    private SecretKey key;
    private static final Limits LIMITS = new Limits(8, 4096, 8192);

    @Before public void setUp() throws Exception {
        sandbox = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "encrypted-generation-test-" + UUID.randomUUID());
        assertTrue(sandbox.mkdir());
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @After public void tearDown() throws Exception { delete(sandbox); }

    private File root() throws IOException {
        File root = new File(sandbox, UUID.randomUUID().toString());
        if (!root.mkdir()) throw new IOException("TEST_DIRECTORY");
        return root;
    }

    private EncryptedGenerationStore store(File root) throws StoreException {
        return new EncryptedGenerationStore(root, key, LIMITS);
    }

    private static Map<String, byte[]> data(String revision) {
        Map<String, byte[]> result = new HashMap<>();
        result.put("records", ("records-" + revision).getBytes(StandardCharsets.UTF_8));
        result.put("notes", ("notes-" + revision).getBytes(StandardCharsets.UTF_8));
        result.put("cursor", revision.getBytes(StandardCharsets.UTF_8));
        return result;
    }

    private static void same(Map<String, byte[]> expected, Snapshot actual) {
        assertEquals(expected.keySet(), actual.components().keySet());
        for (String name : expected.keySet()) assertArrayEquals(expected.get(name), actual.component(name));
    }

    private interface Checked { void run() throws Exception; }

    private static void code(Code code, Checked action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (StoreException e) {
            assertEquals(code, e.code);
            assertEquals(code.name(), e.getMessage());
            assertNull(e.getCause());
            assertEquals(0, e.getSuppressed().length);
        }
    }

    @Test public void pristineAndRoundTripAcrossInstances() throws Exception {
        File root = root();
        EncryptedGenerationStore store = store(root);
        assertEquals("", store.getSnapshot().generation());
        assertFalse(store.getSnapshot().recovered());
        assertTrue(store.getSnapshot().components().isEmpty());
        Snapshot saved = store.commit("", data("one"));
        assertTrue(saved.generation().matches("[0-9a-f]{32}"));
        Snapshot reopened = store(root).getSnapshot();
        same(data("one"), reopened);
        assertEquals(saved.generation(), reopened.generation());
        assertFalse(reopened.recovered());
    }

    @Test public void defensiveCopiesAndStableComponentReuse() throws Exception {
        File root = root();
        EncryptedGenerationStore store = store(root);
        Map<String, byte[]> original = data("one");
        Snapshot first = store.commit("", original);
        original.get("notes")[0] ^= 1;
        first.component("notes")[0] ^= 1;
        Map<String, byte[]> exposed = first.components();
        exposed.get("records")[0] ^= 1;
        exposed.clear();
        same(data("one"), first);
        try { first.componentIds().clear(); fail(); }
        catch (UnsupportedOperationException expected) { }
        String recordId = first.componentIds().get("records");
        byte[] ciphertext = bytes(new File(root, "c-" + recordId));
        Map<String, byte[]> edited = data("one");
        edited.put("notes", new byte[] { 1, 2, 3 });
        Snapshot second = store.commit(first.generation(), edited);
        assertEquals(recordId, second.componentIds().get("records"));
        assertArrayEquals(ciphertext, bytes(new File(root, "c-" + recordId)));
        assertNotEquals(first.componentIds().get("notes"), second.componentIds().get("notes"));
        assertNotEquals(first.generation(), second.generation());
        Snapshot third = store.commit(second.generation(), edited);
        assertEquals(second.componentIds(), third.componentIds());
        store.cleanup();
        same(data("one"), first); // owned snapshot survives removal of its on-disk generation
        assertFalse(new File(root, "m-" + first.generation()).exists());
    }

    @Test public void deletionsAndZeroLengthComponentsAreExplicit() throws Exception {
        EncryptedGenerationStore store = store(root());
        Snapshot first = store.commit("", data("one"));
        Map<String, byte[]> next = new HashMap<>();
        next.put("empty", new byte[0]);
        Snapshot second = store.commit(first.generation(), next);
        same(next, second);
        assertNull(second.component("records"));
        Snapshot empty = store.commit(second.generation(), new HashMap<>());
        assertTrue(empty.components().isEmpty());
        assertFalse(empty.generation().isEmpty());
    }

    @Test public void staleCasCannotOverwriteOrStageFiles() throws Exception {
        File root = root();
        EncryptedGenerationStore firstStore = store(root);
        Snapshot first = firstStore.commit("", data("one"));
        Snapshot second = store(root).commit(first.generation(), data("two"));
        Set<String> before = names(root);
        code(Code.STALE, () -> firstStore.commit(first.generation(), data("stale")));
        assertEquals(before, names(root));
        same(data("two"), firstStore.getSnapshot());
        assertEquals(second.generation(), firstStore.getSnapshot().generation());
    }

    @Test public void concurrentInstancesHaveExactlyOneCasWinner() throws Exception {
        File root = root();
        Snapshot base = store(root).commit("", data("base"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> race(root, base.generation(), "a", start));
            Future<Boolean> b = pool.submit(() -> race(root, base.generation(), "b", start));
            start.countDown();
            assertNotEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            Snapshot result = store(root).getSnapshot();
            String revision = new String(result.component("cursor"), StandardCharsets.UTF_8);
            assertTrue(revision.equals("a") || revision.equals("b"));
            same(data(revision), result);
        } finally { pool.shutdownNow(); }
    }

    private boolean race(File root, String expected, String revision, CountDownLatch start) throws Exception {
        start.await();
        try { store(root).commit(expected, data(revision)); return true; }
        catch (StoreException e) { assertEquals(Code.STALE, e.code); return false; }
    }

    @Test public void canonicalRootLockRejectsCrossInstanceReentry() throws Exception {
        File root = root();
        Snapshot base = store(root).commit("", data("one"));
        EncryptedGenerationStore alias = store(new File(root, "."));
        Set<Step> visited = new HashSet<>();
        EncryptedGenerationStore writer = new EncryptedGenerationStore(root, key, LIMITS, step -> {
            visited.add(step);
            try {
                code(Code.REENTRANT, alias::getSnapshot);
                code(Code.REENTRANT, alias::cleanup);
                code(Code.REENTRANT, () -> alias.commit(base.generation(), data("bad")));
            } catch (Exception e) { throw new IOException("TEST_FAILURE"); }
        });
        writer.commit(base.generation(), data("two"));
        assertEquals(17, visited.size());
        same(data("two"), alias.getSnapshot());
    }

    @Test public void allSeventeenPublishInterruptionsExposeExactlyOldOrNew() throws Exception {
        for (Step step : Step.values()) {
            if (step.name().startsWith("INITIAL_")) continue;
            File root = root();
            Snapshot base = store(root).commit("", data("old"));
            EncryptedGenerationStore failing = failAt(root, step);
            boolean afterActivation = step == Step.ACTIVE_RENAMED || step == Step.ACTIVE_DURABLE;
            code(afterActivation ? Code.COMMIT_UNCERTAIN : Code.IO,
                    () -> failing.commit(base.generation(), data("new")));
            Snapshot reopened = store(root).getSnapshot();
            same(data(afterActivation ? "new" : "old"), reopened);
            assertFalse(reopened.recovered());
            if (!afterActivation) assertEquals(base.generation(), reopened.generation());
            store(root).cleanup();
            same(data(afterActivation ? "new" : "old"), store(root).getSnapshot());
            assertNoStages(root);
            // A subsequent commit remains possible after every injected failure.
            same(data("retry"), store(root).commit(reopened.generation(), data("retry")));
        }
    }

    @Test public void firstEverCommitInterruptionsNeverInferEmptyFromOrphans() throws Exception {
        for (Step step : Step.values()) {
            File root = root();
            boolean candidateActivated = step == Step.ACTIVE_RENAMED || step == Step.ACTIVE_DURABLE;
            code(candidateActivated ? Code.COMMIT_UNCERTAIN : Code.IO,
                    () -> failAt(root, step).commit("", data("first")));
            boolean beforeBaseline = step.name().startsWith("INITIAL_MANIFEST_")
                    || step == Step.INITIAL_RECOVERY_PARTIAL || step == Step.INITIAL_RECOVERY_SYNCED;
            if (beforeBaseline) {
                Set<String> before = names(root);
                code(Code.CORRUPT, () -> store(root).getSnapshot());
                code(Code.CORRUPT, () -> store(root).cleanup());
                assertEquals(before, names(root));
            } else {
                Snapshot recovered = store(root).getSnapshot();
                assertFalse(recovered.generation().isEmpty());
                if (candidateActivated) same(data("first"), recovered);
                else assertTrue(recovered.components().isEmpty());
                boolean baselineFallback = step == Step.INITIAL_RECOVERY_RENAMED
                        || step == Step.INITIAL_RECOVERY_DURABLE
                        || step == Step.INITIAL_ACTIVE_PARTIAL || step == Step.INITIAL_ACTIVE_SYNCED;
                assertEquals(baselineFallback, recovered.recovered());
                same(data("retry"), store(root).commit(recovered.generation(), data("retry")));
            }
        }
    }

    private EncryptedGenerationStore failAt(File root, Step wanted) throws StoreException {
        return new EncryptedGenerationStore(root, key, LIMITS, step -> {
            if (step == wanted) throw new IOException("sensitive path or payload must not escape");
        });
    }

    @Test public void corruptActivePointerManifestOrComponentRecoversWholePrevious() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            File root = root();
            Snapshot old = store(root).commit("", data("old"));
            Snapshot active = store(root).commit(old.generation(), data("new"));
            File target = new File(root, kind == 0 ? "active" : kind == 1
                    ? "m-" + active.generation() : "c-" + active.componentIds().get("notes"));
            corrupt(target);
            Snapshot recovered = store(root).getSnapshot();
            assertTrue(recovered.recovered());
            assertEquals(old.generation(), recovered.generation());
            same(data("old"), recovered);
            store(root).cleanup();
            assertEquals(retained(old), immutableNames(root));
            same(data("old"), store(root).getSnapshot());
            Snapshot repaired = store(root).commit(old.generation(), data("repaired"));
            assertFalse(repaired.recovered());
            corrupt(new File(root, "active"));
            same(data("old"), store(root).getSnapshot());
        }
    }

    @Test public void bothCorruptAndSharedComponentCorruptionFailClosed() throws Exception {
        File root = root();
        Snapshot first = store(root).commit("", data("same"));
        store(root).commit(first.generation(), data("same"));
        corrupt(new File(root, "c-" + first.componentIds().get("records")));
        Set<String> before = names(root);
        code(Code.CORRUPT, () -> store(root).getSnapshot());
        code(Code.CORRUPT, () -> store(root).cleanup());
        code(Code.CORRUPT, () -> store(root).commit(first.generation(), data("bad")));
        assertEquals(before, names(root));
    }

    @Test public void interruptedRecoveryPublicationStillAllowsActivePredecessorFallback() throws Exception {
        for (boolean manifest : new boolean[] { true, false }) {
            File root = root();
            Snapshot old = store(root).commit("", data("old"));
            Snapshot active = store(root).commit(old.generation(), data("active"));
            byte[] pointer = bytes(new File(root, "active"));
            code(Code.IO, () -> failAt(root, Step.RECOVERY_DURABLE)
                    .commit(active.generation(), data("candidate")));
            assertArrayEquals(pointer, bytes(new File(root, "active")));
            corrupt(new File(root, manifest ? "m-" + active.generation()
                    : "c-" + active.componentIds().get("notes")));
            Snapshot recovered = store(root).getSnapshot();
            assertTrue(recovered.recovered());
            assertEquals(old.generation(), recovered.generation());
            same(data("old"), recovered);
            store(root).cleanup();
            assertEquals(retained(old), immutableNames(root));
            assertNoStages(root);
            Snapshot reopened = store(root).getSnapshot();
            assertTrue(reopened.recovered());
            assertEquals(old.generation(), reopened.generation());
            same(data("old"), reopened);
            Snapshot repaired = store(root).commit(reopened.generation(), data("repaired"));
            assertFalse(repaired.recovered());
            Snapshot saved = store(root).getSnapshot();
            assertEquals(repaired.generation(), saved.generation());
            assertFalse(saved.recovered());
            same(data("repaired"), saved);
        }
    }

    @Test public void recoveryPointerMustAuthenticateAndReferenceCompleteGeneration() throws Exception {
        for (boolean corruptPointer : new boolean[] { true, false }) {
            File root = root();
            Snapshot first = store(root).commit("", data("old"));
            store(root).commit(first.generation(), data("new"));
            corrupt(new File(root, "active"));
            corrupt(new File(root, corruptPointer ? "recovery"
                    : "c-" + first.componentIds().get("cursor")));
            code(Code.CORRUPT, () -> store(root).getSnapshot());
            code(Code.CORRUPT, () -> store(root).cleanup());
        }
    }

    @Test public void stagedCandidateMustAuthenticateBeforeActivePointerChanges() throws Exception {
        File root = root();
        Snapshot old = store(root).commit("", data("old"));
        Set<String> committed = immutableNames(root);
        byte[] pointer = bytes(new File(root, "active"));
        EncryptedGenerationStore writer = new EncryptedGenerationStore(root, key, LIMITS, step -> {
            if (step == Step.MANIFEST_DURABLE) {
                try {
                    Set<String> candidates = immutableNames(root);
                    candidates.removeAll(committed);
                    boolean corrupted = false;
                    for (String name : candidates) {
                        if (name.startsWith("c-")) {
                            corrupt(new File(root, name));
                            corrupted = true;
                            break;
                        }
                    }
                    assertTrue(corrupted);
                } catch (Exception e) { throw new IOException("TEST_FAILURE"); }
            }
        });
        code(Code.CORRUPT, () -> writer.commit(old.generation(), data("new")));
        assertArrayEquals(pointer, bytes(new File(root, "active")));
        same(data("old"), store(root).getSnapshot());
        store(root).cleanup();
        same(data("old"), store(root).getSnapshot());
    }

    @Test public void missingAndTruncatedActiveFilesRecoverButMissingBothPointersIsNotEmpty() throws Exception {
        File root = root();
        Snapshot old = store(root).commit("", data("old"));
        Snapshot next = store(root).commit(old.generation(), data("new"));
        try (RandomAccessFile file = new RandomAccessFile(new File(root,
                "c-" + next.componentIds().get("records")), "rw")) { file.setLength(3); }
        same(data("old"), store(root).getSnapshot());
        assertTrue(new File(root, "active").delete());
        same(data("old"), store(root).getSnapshot());
        assertTrue(new File(root, "recovery").delete());
        code(Code.CORRUPT, () -> store(root).getSnapshot());
    }

    @Test public void cleanupRetainsExactlyActiveAndPreviousAndLeavesUnownedFiles() throws Exception {
        File root = root();
        Snapshot first = store(root).commit("", data("1"));
        Snapshot second = store(root).commit(first.generation(), data("2"));
        Snapshot third = store(root).commit(second.generation(), data("3"));
        code(Code.IO, () -> failAt(root, Step.MANIFEST_DURABLE)
                .commit(third.generation(), data("orphan")));
        write(new File(root, "unowned.txt"), new byte[] { 9 });
        store(root).cleanup();
        Set<String> expected = retained(second);
        expected.addAll(retained(third));
        assertEquals(expected, immutableNames(root));
        assertNoStages(root);
        assertArrayEquals(new byte[] { 9 }, bytes(new File(root, "unowned.txt")));
        corrupt(new File(root, "active"));
        same(data("2"), store(root).getSnapshot());
    }

    @Test public void cleanupAbortsBeforeDeletingWhenRetainedPreviousIsIncomplete() throws Exception {
        File root = root();
        Snapshot first = store(root).commit("", data("1"));
        store(root).commit(first.generation(), data("2"));
        corrupt(new File(root, "m-" + first.generation()));
        Set<String> before = names(root);
        same(data("2"), store(root).getSnapshot());
        code(Code.CORRUPT, () -> store(root).cleanup());
        assertEquals(before, names(root));
    }

    @Test public void sizeLimitsRejectWithoutDeletingCommittedData() throws Exception {
        File root = root();
        Snapshot base = store(root).commit("", data("old"));
        Set<String> before = names(root);
        Map<String, byte[]> tooBig = data("big");
        tooBig.put("big", new byte[4097]);
        code(Code.LIMIT, () -> store(root).commit(base.generation(), tooBig));
        Map<String, byte[]> total = new HashMap<>();
        total.put("a", new byte[4096]); total.put("b", new byte[4096]);
        total.put("c", new byte[1]);
        code(Code.LIMIT, () -> store(root).commit(base.generation(), total));
        Map<String, byte[]> count = new HashMap<>();
        for (int i = 0; i < 9; i++) count.put("c" + i, new byte[0]);
        code(Code.LIMIT, () -> store(root).commit(base.generation(), count));
        assertEquals(before, names(root));
        same(data("old"), store(root).getSnapshot());
        EncryptedGenerationStore smaller = new EncryptedGenerationStore(root, key, new Limits(1, 1, 1));
        code(Code.LIMIT, smaller::getSnapshot);
        code(Code.LIMIT, smaller::cleanup);
        assertEquals(before, names(root));
    }

    @Test public void oversizedDiskFileIsBoundedAndNotSilentlyReset() throws Exception {
        File root = root();
        Snapshot base = store(root).commit("", data("old"));
        Snapshot next = store(root).commit(base.generation(), data("new"));
        try (RandomAccessFile file = new RandomAccessFile(new File(root,
                "c-" + next.componentIds().get("records")), "rw")) {
            file.setLength(1024 * 1024); // sparse file; rejected before allocation/decryption
        }
        Snapshot recovered = store(root).getSnapshot();
        assertTrue(recovered.recovered());
        same(data("old"), recovered);
        corrupt(new File(root, "recovery"));
        Snapshot viaActive = store(root).getSnapshot();
        assertTrue(viaActive.recovered());
        assertEquals(base.generation(), viaActive.generation());
        same(data("old"), viaActive);
        store(root).cleanup();
        assertEquals(retained(base), immutableNames(root));
        same(data("old"), store(root).getSnapshot());
        corrupt(new File(root, "active"));
        Set<String> before = names(root);
        code(Code.CORRUPT, () -> store(root).getSnapshot());
        code(Code.CORRUPT, () -> store(root).cleanup());
        assertEquals(before, names(root));
    }

    @Test public void authenticatedLargeManifestExceedingReaderPolicyDoesNotRollBack() throws Exception {
        File root = root();
        Map<String, byte[]> small = new HashMap<>();
        small.put("old", new byte[0]);
        Snapshot old = store(root).commit("", small);
        Map<String, byte[]> large = longNamedComponents();
        Snapshot active = store(root).commit(old.generation(), large);
        // Exceeds the old policy-derived envelope cap for maxComponents=1, but is valid format.
        assertTrue(new File(root, "m-" + active.generation()).length() > 128 + 180 + 28);
        EncryptedGenerationStore smaller = new EncryptedGenerationStore(root, key,
                new Limits(1, 4096, 8192));
        Map<String, byte[]> before = new HashMap<>();
        for (String name : names(root)) before.put(name, bytes(new File(root, name)));
        code(Code.LIMIT, smaller::getSnapshot);
        code(Code.LIMIT, smaller::cleanup);
        code(Code.LIMIT, () -> smaller.commit(active.generation(), small));
        assertEquals(before.keySet(), names(root));
        for (String name : before.keySet())
            assertArrayEquals(before.get(name), bytes(new File(root, name)));
        same(large, store(root).getSnapshot());
    }

    @Test public void damagedLargeManifestsAndInvalidFormatCountsRecoverUnderLowerPolicy() throws Exception {
        for (int damage = 0; damage < 5; damage++) {
            File root = root();
            Map<String, byte[]> small = new HashMap<>();
            small.put("old", new byte[0]);
            Snapshot old = store(root).commit("", small);
            Snapshot active = store(root).commit(old.generation(), longNamedComponents());
            File manifest = new File(root, "m-" + active.generation());
            EncryptedGenerationStore smaller = new EncryptedGenerationStore(root, key,
                    new Limits(1, 4096, 8192));
            if (damage == 0) {
                corrupt(manifest);
            } else if (damage == 1 || damage == 2) {
                try (RandomAccessFile file = new RandomAccessFile(manifest, "rw")) {
                    // Truncation stays above the policy cap; sparse oversizing exceeds format cap.
                    file.setLength(damage == 1 ? file.length() - 1 : 128L + 65536L * 180 + 28 + 1);
                    file.getFD().sync();
                }
            } else {
                // Prove the handcrafted envelope authenticates before using an invalid count.
                writeManifestCount(root, active.generation(), old.generation(), 0);
                Snapshot valid = smaller.getSnapshot();
                assertEquals(active.generation(), valid.generation());
                assertFalse(valid.recovered());
                assertTrue(valid.components().isEmpty());
                writeManifestCount(root, active.generation(), old.generation(),
                        damage == 3 ? 65537 : -1);
            }
            if (damage < 3) assertTrue(manifest.length() > 128 + 180 + 28);
            Snapshot recovered = smaller.getSnapshot();
            assertTrue(recovered.recovered());
            assertEquals(old.generation(), recovered.generation());
            same(small, recovered);
            smaller.cleanup();
            assertEquals(retained(old), immutableNames(root));
            Snapshot reopened = new EncryptedGenerationStore(root, key,
                    new Limits(1, 4096, 8192)).getSnapshot();
            assertTrue(reopened.recovered());
            assertEquals(old.generation(), reopened.generation());
            same(small, reopened);
        }
    }

    private static Map<String, byte[]> longNamedComponents() {
        Map<String, byte[]> result = new HashMap<>();
        char[] name = new char[128];
        Arrays.fill(name, 'x');
        for (int i = 0; i < 8; i++) {
            name[0] = (char) ('a' + i);
            result.put(new String(name), new byte[0]);
        }
        return result;
    }

    /** Authenticated format-v1 fixture: invalid count must be corruption, not a policy LIMIT. */
    private void writeManifestCount(File root, String generation, String previous, int count) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(plain)) {
            out.writeInt(1);
            out.writeUTF(generation);
            out.writeUTF(previous);
            out.writeInt(count);
        }
        ByteArrayOutputStream aad = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(aad)) {
            out.writeUTF("BalanceEncryptedGenerationStore");
            out.writeInt(1);
            byte[] binding = root.getCanonicalPath().getBytes(StandardCharsets.UTF_8);
            out.writeInt(binding.length);
            out.write(binding);
            out.writeUTF("manifest");
            out.writeUTF("");
            out.writeUTF(generation);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        assertEquals(12, cipher.getIV().length);
        cipher.updateAAD(aad.toByteArray());
        ByteArrayOutputStream sealed = new ByteArrayOutputStream();
        sealed.write(cipher.getIV());
        sealed.write(cipher.doFinal(plain.toByteArray()));
        write(new File(root, "m-" + generation), sealed.toByteArray());
    }

    @Test public void invalidNamesAndNullPayloadsNeverCreateFiles() throws Exception {
        File root = root();
        for (String name : new String[] { "../escape", "/absolute", "", "a/b", "a\\b" }) {
            Map<String, byte[]> bad = new HashMap<>();
            bad.put(name, new byte[0]);
            code(Code.INVALID_ARGUMENT, () -> store(root).commit("", bad));
        }
        Map<String, byte[]> bad = new HashMap<>();
        bad.put("valid", null);
        code(Code.INVALID_ARGUMENT, () -> store(root).commit("", bad));
        assertTrue(names(root).isEmpty());
    }

    @Test public void ciphertextCannotBeSubstitutedAcrossComponentsOrRoots() throws Exception {
        File source = root();
        Snapshot first = store(source).commit("", data("old"));
        Snapshot second = store(source).commit(first.generation(), data("new"));
        // Same-length encrypted values, different logical name and immutable component ID.
        write(new File(source, "c-" + second.componentIds().get("records")),
                bytes(new File(source, "c-" + first.componentIds().get("records"))));
        Snapshot recovered = store(source).getSnapshot();
        assertTrue(recovered.recovered());
        same(data("old"), recovered);
        File other = root();
        for (String name : names(source)) write(new File(other, name), bytes(new File(source, name)));
        code(Code.CORRUPT, () -> store(other).getSnapshot());
        // No reads or cleanup in one root affect the other.
        Set<String> before = names(other);
        store(source).cleanup();
        assertEquals(before, names(other));
    }

    @Test public void wrongKeyDoesNotReturnEmptyOrDeleteAnything() throws Exception {
        File root = root();
        store(root).commit("", data("one"));
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        EncryptedGenerationStore wrong = new EncryptedGenerationStore(root, generator.generateKey(), LIMITS);
        Set<String> before = names(root);
        code(Code.CORRUPT, wrong::getSnapshot);
        code(Code.CORRUPT, wrong::cleanup);
        assertEquals(before, names(root));
        same(data("one"), store(root).getSnapshot());
    }

    @Test public void snapshotReadsIncludingRecoveryDoNotRewriteOrCleanFiles() throws Exception {
        File root = root();
        Snapshot old = store(root).commit("", data("old"));
        store(root).commit(old.generation(), data("new"));
        for (boolean damageActive : new boolean[] { false, true }) {
            if (damageActive) corrupt(new File(root, "active"));
            Map<String, byte[]> before = new HashMap<>();
            for (String name : names(root)) before.put(name, bytes(new File(root, name)));
            Snapshot snapshot = store(root).getSnapshot();
            same(data(damageActive ? "old" : "new"), snapshot);
            assertEquals(damageActive, snapshot.recovered());
            assertEquals(before.keySet(), names(root));
            for (String name : before.keySet())
                assertArrayEquals(before.get(name), bytes(new File(root, name)));
        }
    }

    @Test public void rejectsNon256BitKeysWithoutWriting() throws Exception {
        File root = root();
        code(Code.KEY, () -> new EncryptedGenerationStore(root,
                new SecretKeySpec(new byte[16], "AES"), LIMITS));
        assertTrue(names(root).isEmpty());
    }

    @Test public void symlinkedComponentCannotEscapeRoot() throws Exception {
        File root = root();
        Snapshot old = store(root).commit("", data("old"));
        Snapshot next = store(root).commit(old.generation(), data("new"));
        File victim = new File(sandbox, "outside");
        write(victim, new byte[] { 7 });
        File component = new File(root, "c-" + next.componentIds().get("notes"));
        assertTrue(component.delete());
        Os.symlink(victim.getPath(), component.getPath());
        same(data("old"), store(root).getSnapshot());
        code(Code.CORRUPT, () -> store(root).cleanup());
        assertArrayEquals(new byte[] { 7 }, bytes(victim));
        assertTrue(component.delete()); // remove link before recursive fixture cleanup
    }

    @Test public void pointerSymlinksStayWithinTheirRecoveryBranch() throws Exception {
        for (boolean dangling : new boolean[] { false, true }) {
            for (boolean activeLink : new boolean[] { false, true }) {
                File root = root();
                Snapshot old = store(root).commit("", data("old"));
                Snapshot active = store(root).commit(old.generation(), data("new"));
                String name = activeLink ? "active" : "recovery";
                File pointer = new File(root, name);
                File outside = new File(sandbox, UUID.randomUUID().toString());
                // Even an authentic pointer outside the root must not be followed.
                byte[] original = bytes(pointer);
                if (!dangling) write(outside, original);
                assertTrue(pointer.delete());
                Os.symlink(outside.getPath(), pointer.getPath());
                Snapshot expected = activeLink ? old : active;
                Snapshot selected = store(root).getSnapshot();
                assertEquals(expected.generation(), selected.generation());
                assertEquals(activeLink, selected.recovered());
                same(expected.components(), selected);
                store(root).cleanup();
                Set<String> keep = retained(old);
                if (!activeLink) keep.addAll(retained(active));
                assertEquals(keep, immutableNames(root));
                Snapshot reopened = store(root).getSnapshot();
                assertEquals(expected.generation(), reopened.generation());
                assertEquals(activeLink, reopened.recovered());
                same(expected.components(), reopened);
                if (!activeLink) {
                    // A bad recovery link must not block the authenticated active predecessor either.
                    corrupt(new File(root, "m-" + active.generation()));
                    Snapshot recovered = store(root).getSnapshot();
                    assertTrue(recovered.recovered());
                    assertEquals(old.generation(), recovered.generation());
                    same(data("old"), recovered);
                    store(root).cleanup();
                    assertEquals(retained(old), immutableNames(root));
                    same(data("old"), store(root).getSnapshot());
                }
                assertTrue(java.nio.file.Files.isSymbolicLink(pointer.toPath()));
                if (dangling) assertFalse(outside.exists());
                else assertArrayEquals(original, bytes(outside));
                assertTrue(pointer.delete());
            }
        }
    }

    @Test public void twoUnusablePointerSymlinksFailClosedWithoutCleanup() throws Exception {
        for (boolean activeDangling : new boolean[] { false, true }) {
            for (boolean recoveryDangling : new boolean[] { false, true }) {
                File root = root();
                Snapshot old = store(root).commit("", data("old"));
                store(root).commit(old.generation(), data("new"));
                File[] targets = new File[2];
                byte[][] originals = new byte[2][];
                String[] pointers = { "active", "recovery" };
                boolean[] dangling = { activeDangling, recoveryDangling };
                for (int i = 0; i < pointers.length; i++) {
                    File pointer = new File(root, pointers[i]);
                    targets[i] = new File(sandbox, UUID.randomUUID().toString());
                    originals[i] = bytes(pointer);
                    if (!dangling[i]) write(targets[i], originals[i]);
                    assertTrue(pointer.delete());
                    Os.symlink(targets[i].getPath(), pointer.getPath());
                }
                Set<String> before = names(root);
                code(Code.CORRUPT, () -> store(root).getSnapshot());
                code(Code.CORRUPT, () -> store(root).cleanup());
                assertEquals(before, names(root));
                for (int i = 0; i < pointers.length; i++) {
                    File pointer = new File(root, pointers[i]);
                    assertTrue(java.nio.file.Files.isSymbolicLink(pointer.toPath()));
                    if (dangling[i]) assertFalse(targets[i].exists());
                    else assertArrayEquals(originals[i], bytes(targets[i]));
                    assertTrue(pointer.delete());
                }
            }
        }
    }

    @Test public void injectedStorageFailureNeverDeletesGoodData() throws Exception {
        File root = root();
        Snapshot old = store(root).commit("", data("old"));
        Map<String, byte[]> good = new HashMap<>();
        for (String name : retained(old)) good.put(name, bytes(new File(root, name)));
        code(Code.IO, () -> failAt(root, Step.COMPONENT_PARTIAL)
                .commit(old.generation(), data("new")));
        for (String name : good.keySet()) assertArrayEquals(good.get(name), bytes(new File(root, name)));
        same(data("old"), store(root).getSnapshot());
    }

    @Test public void freshNoncesAndLogicalNamesAreAuthenticated() throws Exception {
        File root = root();
        Map<String, byte[]> equal = new HashMap<>();
        equal.put("a", new byte[] { 4, 5 });
        equal.put("b", new byte[] { 4, 5 });
        Snapshot base = store(root).commit("", equal);
        byte[] a = bytes(new File(root, "c-" + base.componentIds().get("a")));
        byte[] b = bytes(new File(root, "c-" + base.componentIds().get("b")));
        assertFalse(Arrays.equals(Arrays.copyOf(a, 12), Arrays.copyOf(b, 12)));
        Snapshot next = store(root).commit(base.generation(), equal);
        write(new File(root, "c-" + next.componentIds().get("a")), b);
        // Both generations reuse a; neither can authenticate the substituted component.
        code(Code.CORRUPT, () -> store(root).getSnapshot());
    }

    @Test public void constructorDoesNotMutateCallerKeyBytes() throws Exception {
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 42);
        SecretKey unusual = new SecretKey() {
            @Override public String getAlgorithm() { return "AES"; }
            @Override public String getFormat() { return "RAW"; }
            @Override public byte[] getEncoded() { return material; }
        };
        new EncryptedGenerationStore(root(), unusual, LIMITS);
        for (byte value : material) assertEquals(42, value);
    }

    /**
     * Separate opt-in stage: -e generationCrashTest true. Runs a real ART child with the same UID,
     * APK classpath and synthetic key. SIGKILL occurs inside the hook, bypassing Java cleanup.
     * Device policy must allow app_process; launch/policy failures are test failures, not passes.
     * This tests process death, not kernel/power loss or physical ENOSPC.
     */
    @Test public void actualChildProcessDeathAtPublicationBoundaries() throws Exception {
        assumeTrue("Enable separate process-death stage", "true".equals(
                InstrumentationRegistry.getArguments().getString("generationCrashTest")));
        String classpath = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationInfo().sourceDir + ":"
                + InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo().sourceDir;
        SecretKey synthetic = new SecretKeySpec(new byte[32], "AES");
        for (Step step : new Step[] { Step.COMPONENT_PARTIAL, Step.RECOVERY_DURABLE,
                Step.ACTIVE_SYNCED, Step.ACTIVE_RENAMED }) {
            File root = root();
            EncryptedGenerationStore parent = new EncryptedGenerationStore(root, synthetic, LIMITS);
            Snapshot old = parent.commit("", data("old"));
            Process child = new ProcessBuilder("/system/bin/app_process",
                    "-Djava.class.path=" + classpath, "/system/bin", CrashProcess.class.getName(),
                    root.getPath(), old.generation(), step.name()).redirectErrorStream(true).start();
            try {
                assertTrue("Child must exit within deadline", child.waitFor(30, TimeUnit.SECONDS));
                assertNotEquals("Child must be killed", 0, child.exitValue());
                // Child emits a fixed handshake immediately before killing itself. This prevents a
                // class-loading/SELinux/initialization failure from masquerading as a crash test.
                byte[] output = new byte[8192];
                int used = 0;
                int count;
                while (used < output.length && (count = child.getInputStream()
                        .read(output, used, output.length - used)) != -1) used += count;
                assertTrue("Child reached requested crash boundary", new String(output, 0, used,
                        StandardCharsets.UTF_8).contains("GENERATION_CRASH_READY"));
                Snapshot reopened = new EncryptedGenerationStore(root, synthetic, LIMITS).getSnapshot();
                same(data(step == Step.ACTIVE_RENAMED ? "new" : "old"), reopened);
                parent.cleanup();
                assertNoStages(root);
            } finally { child.destroyForcibly(); }
        }
    }

    /** Only launched by the opt-in synthetic process-death test. No production key is used. */
    public static final class CrashProcess {
        public static void main(String[] args) {
            try {
                Step wanted = Step.valueOf(args[2]);
                EncryptedGenerationStore child = new EncryptedGenerationStore(new File(args[0]),
                        new SecretKeySpec(new byte[32], "AES"), LIMITS, step -> {
                            if (step == wanted) {
                                System.out.println("GENERATION_CRASH_READY");
                                System.out.flush();
                                android.os.Process.killProcess(android.os.Process.myPid());
                                Runtime.getRuntime().halt(73);
                            }
                        });
                child.commit(args[1], data("new"));
                System.exit(2);
            } catch (Throwable ignored) {
                // No raw filesystem/provider errors or command arguments in child output.
                System.exit(3);
            }
        }
    }

    private static Set<String> retained(Snapshot snapshot) {
        Set<String> result = new HashSet<>();
        result.add("m-" + snapshot.generation());
        for (String id : snapshot.componentIds().values()) result.add("c-" + id);
        return result;
    }

    private static Set<String> names(File root) {
        String[] names = root.list();
        assertNotNull(names);
        return new HashSet<>(Arrays.asList(names));
    }

    private static Set<String> immutableNames(File root) {
        Set<String> result = names(root);
        result.removeIf(name -> !name.matches("[cm]-[0-9a-f]{32}"));
        return result;
    }

    private static void assertNoStages(File root) {
        for (String name : names(root)) assertFalse(name.endsWith(".stage"));
    }

    private static void corrupt(File target) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
            long offset = file.length() - 1;
            assertTrue(offset >= 0);
            file.seek(offset);
            int value = file.read();
            file.seek(offset);
            file.write(value ^ 1);
            file.getFD().sync();
        }
    }

    private static byte[] bytes(File file) throws Exception {
        assertTrue(file.length() <= 1024 * 1024);
        byte[] result = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < result.length) {
                int count = in.read(result, offset, result.length - offset);
                assertTrue(count > 0);
                offset += count;
            }
            assertEquals(-1, in.read());
        }
        return result;
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private static void delete(File file) throws Exception {
        if (file == null || (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath()))) return;
        // Do not follow fixture symlinks, even if a test failed before its explicit unlink.
        File resolvedParent = new File(file.getParentFile().getCanonicalFile(), file.getName());
        if (file.getCanonicalFile().equals(resolvedParent) && file.isDirectory()) {
            File[] children = file.listFiles(); assertNotNull(children);
            for (File child : children) delete(child);
        }
        assertTrue(file.delete());
    }
}
