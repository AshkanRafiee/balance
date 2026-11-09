package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AnnotationAtomicityTest {
    private Context context;

    @Before public void before() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MetadataStore.reset(context, true);
    }

    @After public void after() throws Exception {
        sql("DROP TRIGGER IF EXISTS fail_annotation");
        MetadataStore.reset(context, true);
    }

    @Test public void completeSaveNormalizesAndClearsIndependently() throws Exception {
        MetadataStore.setAnnotations(context, "key", "  note  ", Arrays.asList(" Bills ", "bills", "خوراک"));
        assertEquals("note", MetadataStore.getNote(context, "key"));
        assertEquals(Arrays.asList("Bills", "خوراک"), MetadataStore.getTags(context, "key"));
        MetadataStore.setAnnotations(context, "key", null, Arrays.asList("Bills"));
        assertNull(MetadataStore.getNote(context, "key"));
        assertEquals(Arrays.asList("Bills"), MetadataStore.getTags(context, "key"));
        MetadataStore.setAnnotations(context, "key", "kept", Collections.emptyList());
        assertEquals("kept", MetadataStore.getNote(context, "key"));
        assertTrue(MetadataStore.getTags(context, "key").isEmpty());
    }

    @Test public void failedTagUpdateRollsBackNoteAndRevision() throws Exception {
        MetadataStore.setAnnotations(context, "key", "previous", Arrays.asList("old"));
        String revision = MetadataStore.revision(context);
        failTags("UPDATE", "OLD");
        assertSaveFails("replacement", Arrays.asList("new"));
        assertEquals("previous", MetadataStore.getNote(context, "key"));
        assertEquals(Arrays.asList("old"), MetadataStore.getTags(context, "key"));
        assertEquals(revision, MetadataStore.revision(context));
    }

    @Test public void failedTagInsertRollsBackNewNote() throws Exception {
        MetadataStore.setAnnotations(context, "other", "unrelated", Collections.emptyList());
        failTags("INSERT", "NEW");
        assertSaveFails("new note", Arrays.asList("new"));
        assertNull(MetadataStore.getNote(context, "key"));
        assertTrue(MetadataStore.getTags(context, "key").isEmpty());
        assertEquals("unrelated", MetadataStore.getNote(context, "other"));
    }

    @Test public void failedTagDeleteRollsBackClearedNote() throws Exception {
        MetadataStore.setAnnotations(context, "key", "previous", Arrays.asList("old"));
        failTags("DELETE", "OLD");
        assertSaveFails(null, Collections.emptyList());
        assertEquals("previous", MetadataStore.getNote(context, "key"));
        assertEquals(Arrays.asList("old"), MetadataStore.getTags(context, "key"));
    }

    private void assertSaveFails(String note, java.util.List<String> tags) throws Exception {
        try {
            MetadataStore.setAnnotations(context, "key", note, tags);
            fail("write must fail");
        } catch (android.database.sqlite.SQLiteException expected) { }
    }

    private void failTags(String operation, String row) {
        sql("CREATE TRIGGER fail_annotation BEFORE " + operation + " ON metadata WHEN "
            + row + ".kind=4 BEGIN SELECT RAISE(ABORT, 'test failure'); END");
    }

    private void sql(String sql) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                DataGeneration.context(context).getDatabasePath(MetadataStore.DB_NAME).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL(sql);
        }
    }
}
