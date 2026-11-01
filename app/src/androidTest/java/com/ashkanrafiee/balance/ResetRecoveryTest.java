package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

/** Explicit reset remains a recovery route without weakening ordinary store reads. */
@RunWith(AndroidJUnit4.class)
public class ResetRecoveryTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
    }

    @Test public void corruptCommitmentsCanBeResetInPublishedGeneration() throws Exception {
        Commitment commitment = Commitment.create("Example", -100L, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        publish();
        corrupt(DataGeneration.context(context).getDatabasePath(CommitmentStore.DB_NAME));

        BalanceData.reset(context, true);

        assertTrue(BalanceData.readCommitments(context).isEmpty());
        assertTrue(BalanceData.readTransactions(context).isEmpty());
        assertEquals(1, DataGeneration.generationNamesForTests(context).size());
    }

    @Test public void corruptMetadataResetRefreshesGenerationInventory() throws Exception {
        assertTrue(MetadataStore.setNote(context, "example", "kept until reset"));
        publish();
        corrupt(DataGeneration.context(context).getDatabasePath(MetadataStore.DB_NAME));

        BalanceData.reset(context, true);

        assertTrue(MetadataStore.readNotes(context).isEmpty());
        assertTrue(MetadataStore.setNote(context, "new", "usable after recovery"));
        assertEquals("usable after recovery", MetadataStore.getNote(context, "new"));
        assertTrue(BalanceData.read(context).isEmpty());
    }

    @Test public void discardEverythingRecoversInvalidGenerationSelector() throws Exception {
        publish();
        corrupt(DataGeneration.manifestFileForTests(context));

        BalanceData.reset(context, true);

        assertFalse(DataGeneration.manifestFileForTests(context).exists());
        assertTrue(DataGeneration.generationNamesForTests(context).isEmpty());
        assertTrue(BalanceData.read(context).isEmpty());
        assertTrue(MetadataStore.setNote(context, "new", "works"));
    }

    @Test public void keepNotesPreservesCorruptMetadataForRecovery() throws Exception {
        assertTrue(MetadataStore.setNote(context, "example", "must not silently disappear"));
        publish();
        File database = DataGeneration.context(context).getDatabasePath(MetadataStore.DB_NAME);
        corrupt(database);

        try {
            BalanceData.reset(context, false);
            fail("unreadable notes must not be silently discarded");
        } catch (IllegalStateException expected) {
            assertTrue(database.exists());
            assertEquals(4L, database.length());
        }
    }

    @Test public void keepNotesRecoversDeletionBeforeInventoryUpdate() throws Exception {
        assertTrue(MetadataStore.setNote(context, "example", "survives reset"));
        Commitment commitment = Commitment.create("Example", -100L, Commitment.ONCE,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, 0);
        BalanceData.writeCommitments(context, Collections.singletonList(commitment));
        publish();
        Context active = DataGeneration.context(context);
        assertTrue(active.deleteDatabase(CommitmentStore.DB_NAME));
        // Mimic an interrupted reset: the inventory still lists the deleted database.
        context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(BalanceData.KEY_RESET_IN_PROGRESS, true)
            .putBoolean(BalanceData.KEY_RESET_ALSO_NOTES, false).commit();

        BalanceData.reset(context, false);

        assertEquals("survives reset", MetadataStore.getNote(context, "example"));
        assertTrue(BalanceData.readCommitments(context).isEmpty());
    }

    @Test public void keepNotesDoesNotEraseAnUnreadableGeneration() throws Exception {
        assertTrue(MetadataStore.setNote(context, "example", "must not silently disappear"));
        publish();
        File database = DataGeneration.context(context).getDatabasePath(MetadataStore.DB_NAME);
        corrupt(DataGeneration.manifestFileForTests(context));
        try {
            BalanceData.reset(context, false);
            fail("keep-notes reset must stop if notes cannot be located");
        } catch (IllegalStateException expected) {
            assertTrue(database.exists());
            assertFalse(DataGeneration.generationNamesForTests(context).isEmpty());
        }
    }

    @Test public void keepNotesDoesNotTreatMissingMetadataAsResettable() throws Exception {
        assertTrue(MetadataStore.setNote(context, "example", "must not silently disappear"));
        publish();
        Context active = DataGeneration.context(context);
        assertTrue(active.deleteDatabase(MetadataStore.DB_NAME));

        try {
            BalanceData.reset(context, false);
            fail("missing notes must not be treated as a disposable reset store");
        } catch (IllegalStateException expected) {
            assertTrue(DataGeneration.manifestFileForTests(context).exists());
            assertFalse(DataGeneration.generationNamesForTests(context).isEmpty());
        }
    }

    @Test public void keepNotesRecoversTruncatedDisposableStores() throws Exception {
        for (String name : new String[]{CommitmentStore.DB_NAME, TransactionStore.DB_NAME,
                SourceStore.DB_NAME}) {
            BalanceData.reset(context, true);
            assertTrue(MetadataStore.setNote(context, "example", "survives reset"));
            assertTrue(BalanceData.writeTransactions(context, Collections.singletonList(
                new Transaction("Refah", 100L, 10L))));
            SourceStore.capture(context, "Refah", "source fixture", 100L, 1);
            publish();
            File database = DataGeneration.context(context).getDatabasePath(name);
            try (FileOutputStream out = new FileOutputStream(database)) {
                out.getFD().sync();
            }

            BalanceData.reset(context, false);

            assertEquals("survives reset", MetadataStore.getNote(context, "example"));
            assertTrue(BalanceData.readTransactions(context).isEmpty());
            assertTrue(BalanceData.readCommitments(context).isEmpty());
        }
    }

    @Test public void keepNotesDoesNotDiscardTruncatedMetadata() throws Exception {
        assertTrue(MetadataStore.setNote(context, "example", "must not silently disappear"));
        publish();
        File database = DataGeneration.context(context).getDatabasePath(MetadataStore.DB_NAME);
        try (FileOutputStream out = new FileOutputStream(database)) {
            out.getFD().sync();
        }
        try {
            BalanceData.reset(context, false);
            fail("truncated notes must not be discarded by keep-notes reset");
        } catch (IllegalStateException expected) {
            assertTrue(database.exists());
            assertEquals(0L, database.length());
        }
    }

    private void publish() throws Exception {
        synchronized (BalanceData.class) {
            try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
                stage.publish();
            }
        }
    }

    private static void corrupt(File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
            out.getFD().sync();
        }
    }
}
