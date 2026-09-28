package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public class FinancialRepositoryTest {
    private static final class Fake implements FinancialRepository.Backend {
        String revision = "0";
        Map<String, byte[]> values = new LinkedHashMap<>();
        int loads;
        int commits;
        boolean fail;
        boolean failLoad;

        @Override public FinancialRepository.Snapshot load() throws IOException {
            loads++;
            if (failLoad) throw new IOException("UNEXPECTED_READ");
            return new FinancialRepository.Snapshot(revision, values);
        }

        @Override public void commit(FinancialRepository.Snapshot base, Map<String, byte[]> next)
                throws IOException {
            if (!base.revision().equals(revision)) throw new IOException("STALE");
            if (fail) throw new IOException("secret-path-must-not-escape");
            values = new LinkedHashMap<>(next);
            revision = Integer.toString(++commits);
        }
    }

    @Test public void groupsWritesAndKeepsBytesPrivate() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        byte[] original = { 1, 2 };
        repository.transaction(draft -> { draft.put("a", original); draft.put("b", new byte[] { 3 }); return null; });
        original[0] = 9;
        assertArrayEquals(new byte[] { 1, 2 }, repository.snapshot().get("a"));
        assertEquals(1, fake.commits);
    }

    @Test public void nestedWorkSharesDraftAndCaughtFailureRollsBack() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        try {
            repository.transaction(draft -> {
                draft.put("before", new byte[] { 1 });
                try { repository.transaction(inner -> { inner.put("bad", new byte[] { 2 }); throw new Exception(); }); }
                catch (IOException expected) { }
                return null;
            });
            fail();
        } catch (IOException expected) { assertEquals("TRANSACTION_ROLLBACK", expected.getMessage()); }
        assertEquals(0, fake.commits);
        assertNull(repository.snapshot().get("before"));
    }

    @Test public void noOpDoesNotCommit() throws Exception {
        Fake fake = new Fake();
        fake.values.put("a", new byte[] { 1 });
        FinancialRepository repository = new FinancialRepository(fake);
        repository.transaction(draft -> { draft.put("a", new byte[] { 1 }); return null; });
        assertEquals(0, fake.commits);
    }

    @Test public void successfulCommitReturnsWithoutRereading() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        String result = repository.transaction(draft -> {
            draft.put("a", new byte[] { 1 });
            fake.failLoad = true;
            return "saved";
        });
        assertEquals("saved", result);
        assertEquals(1, fake.loads);
        assertEquals(1, fake.commits);
        assertArrayEquals(new byte[] { 1 }, fake.values.get("a"));
    }

    @Test public void snapshotsStayStableWhenEscapedDraftChanges() throws Exception {
        Fake fake = new Fake();
        fake.values.put("a", new byte[] { 1 });
        fake.values.put("keep", new byte[] { 2 });
        FinancialRepository repository = new FinancialRepository(fake);
        FinancialRepository.Snapshot before = repository.snapshot();
        FinancialRepository.Snapshot[] during = new FinancialRepository.Snapshot[1];
        FinancialRepository.Draft escaped = repository.transaction(draft -> {
            draft.get("keep")[0] = 9;
            draft.componentsCopy().get("keep")[0] = 9;
            draft.components().clear();
            byte[] input = { 3 };
            draft.put("a", input);
            input[0] = 9;
            during[0] = repository.snapshot();
            draft.put("a", new byte[] { 4 });
            return draft;
        });
        FinancialRepository.Snapshot after = repository.snapshot();
        escaped.put("a", new byte[] { 5 });
        escaped.remove("keep");
        escaped.put("new", new byte[] { 6 });
        during[0].get("a")[0] = 9;
        during[0].components().get("keep")[0] = 9;
        assertArrayEquals(new byte[] { 1 }, before.get("a"));
        assertArrayEquals(new byte[] { 3 }, during[0].get("a"));
        assertArrayEquals(new byte[] { 4 }, after.get("a"));
        for (FinancialRepository.Snapshot snapshot :
                new FinancialRepository.Snapshot[] { before, during[0], after }) {
            assertArrayEquals(new byte[] { 2 }, snapshot.get("keep"));
            assertNull(snapshot.get("new"));
        }
        assertArrayEquals(new byte[] { 4 }, fake.values.get("a"));
        assertArrayEquals(new byte[] { 2 }, fake.values.get("keep"));
        assertEquals(1, fake.commits);
    }

    @Test public void publicSnapshotDefensivelyCopiesInput() {
        byte[] input = { 1 };
        Map<String, byte[]> values = new LinkedHashMap<>();
        values.put("a", input);
        FinancialRepository.Snapshot snapshot = new FinancialRepository.Snapshot("base", values);
        input[0] = 9;
        values.clear();
        assertEquals("base", snapshot.revision());
        assertArrayEquals(new byte[] { 1 }, snapshot.get("a"));
    }

    @Test public void backendCannotMutateDraftOrSnapshotThroughCommitValues() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        FinancialRepository.Snapshot[] during = new FinancialRepository.Snapshot[1];
        FinancialRepository.Draft escaped = repository.transaction(draft -> {
            draft.put("a", new byte[] { 1 });
            during[0] = repository.snapshot();
            return draft;
        });
        // The backend retains the arrays passed to commit, then mutates them.
        fake.values.get("a")[0] = 9;
        fake.values.clear();
        assertArrayEquals(new byte[] { 1 }, escaped.get("a"));
        assertArrayEquals(new byte[] { 1 }, during[0].get("a"));
    }

    @Test public void successfulNestedWorkReadsAndUpdatesSharedDraft() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        String result = repository.transaction(draft -> {
            draft.put("a", new byte[] { 1 });
            String nested = repository.transaction(inner -> {
                assertSame(draft, inner);
                assertArrayEquals(new byte[] { 1 }, repository.snapshot().get("a"));
                inner.put("b", new byte[] { 2 });
                return "nested";
            });
            assertArrayEquals(new byte[] { 2 }, repository.snapshot().get("b"));
            assertEquals(0, fake.commits);
            return nested;
        });
        assertEquals("nested", result);
        assertEquals(1, fake.loads);
        assertEquals(1, fake.commits);
        assertArrayEquals(new byte[] { 1 }, fake.values.get("a"));
        assertArrayEquals(new byte[] { 2 }, fake.values.get("b"));
    }

    @Test public void caughtNestedErrorMarksRollbackOnlyAndCleansUp() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        AssertionError failure = new AssertionError("nested");
        try {
            repository.transaction(draft -> {
                draft.put("before", new byte[] { 1 });
                try {
                    repository.transaction(inner -> {
                        inner.put("bad", new byte[] { 2 });
                        throw failure;
                    });
                    fail("Nested error was not rethrown");
                } catch (AssertionError caught) {
                    assertSame(failure, caught);
                }
                draft.put("after", new byte[] { 3 });
                return null;
            });
            fail("Rollback-only transaction committed");
        } catch (IOException expected) {
            assertEquals("TRANSACTION_ROLLBACK", expected.getMessage());
        }
        assertEquals(0, fake.commits);
        assertTrue(repository.snapshot().components().isEmpty());
        repository.transaction(draft -> { draft.put("ok", new byte[] { 4 }); return null; });
        assertEquals(1, fake.commits);
        assertEquals(1, fake.values.size());
    }

    @Test public void outerErrorPropagatesAndCleansUp() throws Exception {
        Fake fake = new Fake();
        FinancialRepository repository = new FinancialRepository(fake);
        AssertionError failure = new AssertionError("outer");
        try {
            repository.transaction(draft -> { draft.put("bad", new byte[] { 1 }); throw failure; });
            fail("Error was not rethrown");
        } catch (AssertionError caught) {
            assertSame(failure, caught);
        }
        assertEquals(0, fake.commits);
        assertTrue(repository.snapshot().components().isEmpty());
        repository.transaction(draft -> { draft.put("ok", new byte[] { 2 }); return null; });
        assertEquals(1, fake.commits);
        assertEquals(1, fake.values.size());
    }

    @Test public void swallowedBackendReentryCannotReadOrRunWork() throws Exception {
        Fake fake = new Fake();
        FinancialRepository[] repository = new FinancialRepository[1];
        int[] rejected = { 0 };
        repository[0] = new FinancialRepository(new FinancialRepository.Backend() {
            private void attemptReentry() throws IOException {
                try {
                    repository[0].snapshot();
                    fail("Backend reentered snapshot");
                } catch (IOException expected) {
                    assertEquals("REENTRANT", expected.getMessage());
                    rejected[0]++;
                }
                try {
                    repository[0].transaction(draft -> {
                        fail("Backend callback work ran");
                        return null;
                    });
                    fail("Backend reentered transaction");
                } catch (IOException expected) {
                    assertEquals("REENTRANT", expected.getMessage());
                    rejected[0]++;
                }
            }

            @Override public FinancialRepository.Snapshot load() throws IOException {
                attemptReentry();
                return fake.load();
            }

            @Override public void commit(FinancialRepository.Snapshot base, Map<String, byte[]> values)
                    throws IOException {
                attemptReentry();
                fake.commit(base, values);
            }
        });
        // A backend may swallow rejection; its original operation can still complete.
        repository[0].transaction(draft -> { draft.put("a", new byte[] { 1 }); return null; });
        assertEquals(4, rejected[0]);
        assertEquals(1, fake.loads);
        assertEquals(1, fake.commits);
        assertArrayEquals(new byte[] { 1 }, repository[0].snapshot().get("a"));
        assertEquals(6, rejected[0]);
    }

    @Test public void backendFailureIsSanitizedAndNotReplayed() throws Exception {
        Fake fake = new Fake();
        fake.fail = true;
        FinancialRepository repository = new FinancialRepository(fake);
        try {
            repository.transaction(draft -> { draft.put("a", new byte[] { 1 }); return null; });
            fail();
        } catch (IOException failure) {
            assertEquals("BACKEND_IO", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertEquals(0, fake.commits);
    }

    @Test public void differentRepositoryCannotCommitInsideAnOpenTransaction() throws Exception {
        Fake first = new Fake();
        Fake second = new Fake();
        FinancialRepository a = new FinancialRepository(first);
        FinancialRepository b = new FinancialRepository(second);
        try {
            a.transaction(draft -> {
                draft.put("a", new byte[] { 1 });
                b.transaction(other -> { other.put("b", new byte[] { 2 }); return null; });
                return null;
            });
            fail();
        } catch (IOException expected) {
            // The outer transaction deliberately sanitizes nested work failures.
            assertEquals("TRANSACTION_FAILED", expected.getMessage());
        }
        assertEquals(0, first.commits);
        assertEquals(0, second.commits);
    }

    @Test public void generationBackendCommitsOneOperation() throws Exception {
        File root = new File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir(), "financial-repository-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        try {
            EncryptedGenerationStore store = new EncryptedGenerationStore(root, key);
            FinancialRepository repository = new FinancialRepository(
                    FinancialRepository.generationBackend(store));
            repository.transaction(draft -> {
                draft.put("balances", new byte[] { 4 });
                draft.put("transactions", new byte[] { 5 });
                return null;
            });
            assertArrayEquals(new byte[] { 4 }, repository.snapshot().get("balances"));
            assertArrayEquals(new byte[] { 5 }, repository.snapshot().get("transactions"));
        } finally {
            delete(root);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }
}
