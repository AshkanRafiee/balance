package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The guarded editor writer must not resurrect a key a reparse has already migrated away. */
@RunWith(AndroidJUnit4.class)
public class AnnotationTargetTest {
    private Context context;
    private final Transaction original = new Transaction("Refah", "1234", 100L, -20L,
        80L, "original-sig", "original-content");

    @Before public void before() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
    }

    @After public void after() { BalanceData.reset(context, true); }

    @Test public void unchangedCurrentTransactionSavesCompleteAnnotation() throws Exception {
        TransactionStore.replace(context, Arrays.asList(original));
        assertTrue(MetadataStore.setCurrentAnnotations(context, original, " current ", Arrays.asList("chosen")));
        assertEquals("current", MetadataStore.getNote(context, original));
        assertEquals(Arrays.asList("chosen"), MetadataStore.getTags(context, original));
    }

    @Test public void reparseAfterEditorOpenRejectsOldKeyAndPreservesMigratedAnnotation() throws Exception {
        TransactionStore.replace(context, Arrays.asList(original));
        MetadataStore.setAnnotations(context, original, "previous note", Arrays.asList("previous tag"));
        Transaction reparsed = new Transaction("Refah", "5678", 90L, -25L, 75L,
            "reparsed-sig", "reparsed-content");
        synchronized (BalanceData.class) {
            TransactionStore.replace(context, Arrays.asList(reparsed));
            MetadataStore.migrateTransactionText(context, Collections.singletonMap(original, reparsed));
        }
        String revision = MetadataStore.revision(context);
        assertStale(original);
        assertNull(MetadataStore.getNote(context, original));
        assertTrue(MetadataStore.getTags(context, original).isEmpty());
        assertEquals("previous note", MetadataStore.getNote(context, reparsed));
        assertEquals(Arrays.asList("previous tag"), MetadataStore.getTags(context, reparsed));
        assertEquals(revision, MetadataStore.revision(context));
        assertTrue(MetadataStore.setCurrentAnnotations(context, reparsed, "retry on updated row",
            Arrays.asList("retry tag")));
        assertEquals("retry on updated row", MetadataStore.getNote(context, reparsed));
    }

    @Test public void changedParseWithSameMetadataKeyStillRejectsSnapshot() throws Exception {
        MetadataStore.setAnnotations(context, original, "previous note", Arrays.asList("previous tag"));
        Transaction changed = new Transaction("Refah", "1234", 100L, -30L, 70L,
            original.sig, original.content);
        assertEquals(BalanceData.noteKey(original), BalanceData.noteKey(changed));
        TransactionStore.replace(context, Arrays.asList(changed));
        assertStale(original);
        assertEquals("previous note", MetadataStore.getNote(context, changed));
        assertEquals(Arrays.asList("previous tag"), MetadataStore.getTags(context, changed));
    }

    @Test public void ambiguousSharedLegacyKeyCannotBeChosenBySimilarity() throws Exception {
        Transaction competing = new Transaction("Refah", "other-account", 200L, -20L, 80L,
            "other-sig", original.content);
        assertEquals(BalanceData.noteKey(original), BalanceData.noteKey(competing));
        TransactionStore.replace(context, Arrays.asList(original, competing));
        MetadataStore.setAnnotations(context, original, "previous note", Arrays.asList("previous tag"));
        assertStale(original);
        assertEquals("previous note", MetadataStore.getNote(context, original));
        assertEquals(Arrays.asList("previous tag"), MetadataStore.getTags(context, original));
    }

    @Test public void removedTransactionRejectsSave_butLowLevelKeyApiRemainsAvailable() throws Exception {
        assertStale(original);
        assertNull(MetadataStore.getNote(context, original));
        MetadataStore.setAnnotations(context, BalanceData.noteKey(original), "imported annotation",
            Arrays.asList("imported tag"));
        assertEquals("imported annotation", MetadataStore.getNote(context, original));
        assertEquals(Arrays.asList("imported tag"), MetadataStore.getTags(context, original));
    }

    @Test public void unchangedIdentitylessLegacyTransactionCanStillBeAnnotated() throws Exception {
        Transaction legacy = new Transaction("Refah", "1234", 100L, -20L, 80L, null, null);
        TransactionStore.replace(context, Arrays.asList(legacy));
        assertTrue(MetadataStore.setCurrentAnnotations(context, legacy, "legacy note", Arrays.asList("legacy")));
        assertEquals("legacy note", MetadataStore.getNote(context, legacy));
    }

    @Test public void cancellationAfterValidationDoesNotPersistQueuedEdits() throws Exception {
        TransactionStore.replace(context, Arrays.asList(original));
        MetadataStore.setAnnotations(context, original, "previous note", Arrays.asList("previous tag"));
        int[] checks = {0};
        assertFalse(MetadataStore.setCurrentAnnotations(context, original, "must not save",
            Arrays.asList("must not save"), () -> ++checks[0] == 1));
        assertEquals(2, checks[0]);
        assertEquals("previous note", MetadataStore.getNote(context, original));
        assertEquals(Arrays.asList("previous tag"), MetadataStore.getTags(context, original));
    }

    private void assertStale(Transaction transaction) throws Exception {
        try {
            MetadataStore.setCurrentAnnotations(context, transaction, "must not be saved", Arrays.asList("lost tag"));
            fail("stale annotation target must be rejected");
        } catch (MetadataStore.StaleAnnotationTargetException expected) { }
    }
}
