package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests encrypted, uncapped metadata rows, migration, ownership and bounded cursors. */
@RunWith(AndroidJUnit4.class)
public class MetadataStoreTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MetadataStore.reset(context, true);
    }

    @After public void tearDown() throws Exception {
        MetadataStore.reset(context, true);
    }

    @Test public void textAndTagsRoundTripInEncryptedRows() throws Exception {
        assertTrue(MetadataStore.setNote(context, "note-key", "  private note  "));
        assertTrue(MetadataStore.mergeReasons(context,
            singletonText("reason-key", "bank reason")));
        assertTrue(MetadataStore.mergeChannels(context,
            singletonText("channel-key", "bank channel")));
        assertTrue(MetadataStore.setTags(context, "tag-key", Arrays.asList(" food ", "Bills", "FOOD")));

        assertEquals("private note", MetadataStore.getNote(context, "note-key"));
        assertEquals("bank reason", MetadataStore.readReasons(context).get("reason-key"));
        assertEquals("bank channel", MetadataStore.readChannels(context).get("channel-key"));
        assertEquals(Arrays.asList("food", "Bills"), MetadataStore.getTags(context, "tag-key"));

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(MetadataStore.DB_NAME).getPath(), null,
            SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor cursor = db.query(MetadataStore.TABLE, new String[]{"payload"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String payload = cursor.getString(0);
                    assertFalse(payload.contains("private note"));
                    assertFalse(payload.contains("bank reason"));
                    assertFalse(payload.contains("bank channel"));
                    assertFalse(payload.contains("food"));
                }
            }
        } finally {
            db.close();
        }
    }

    @Test public void replacementHasNoLegacyEntryCountCap() throws Exception {
        Map<String, String> notes = new LinkedHashMap<>();
        int count = BalanceData.MAX_TAG_ENTRIES + 1;
        for (int i = 0; i < count; i++) notes.put("key-" + i, "note-" + i);

        assertTrue(MetadataStore.writeNotes(context, notes));
        assertEquals(count, MetadataStore.readNotes(context).size());
    }

    @Test public void legacyPlaintextAndEncryptedMapsMigrateAndDisappear() throws Exception {
        Map<String, String> notes = singletonText("legacy-note", "old note");
        Map<String, String> reasons = singletonText("legacy-reason", "old reason");
        Map<String, String> channels = singletonText("legacy-channel", "old channel");
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("legacy-tags", Arrays.asList("one", "two"));

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(BalanceData.KEY_TX_NOTES, BalanceData.serializeTextMap(notes))
            .putString(BalanceData.KEY_TX_REASONS,
                BalanceData.encryptStorePayload(BalanceData.serializeTextMap(reasons)))
            .putString(BalanceData.KEY_TX_CHANNELS, BalanceData.serializeTextMap(channels))
            .putString(BalanceData.KEY_TX_TAGS, BalanceData.serializeTagsMap(tags))
            .commit();

        assertEquals("old note", MetadataStore.readNotes(context).get("legacy-note"));
        assertEquals("old reason", MetadataStore.readReasons(context).get("legacy-reason"));
        assertEquals("old channel", MetadataStore.readChannels(context).get("legacy-channel"));
        assertEquals(Arrays.asList("one", "two"), MetadataStore.readTags(context).get("legacy-tags"));

        assertNull(legacyValue(BalanceData.KEY_TX_NOTES));
        assertNull(legacyValue(BalanceData.KEY_TX_REASONS));
        assertNull(legacyValue(BalanceData.KEY_TX_CHANNELS));
        assertNull(legacyValue(BalanceData.KEY_TX_TAGS));
    }

    @Test public void pageReadsOnlyTheRequestedWindow() throws Exception {
        Map<String, String> notes = new LinkedHashMap<>();
        for (int i = 0; i < MetadataStore.MAX_PAGE_SIZE + 37; i++)
            notes.put("page-key-" + i, "value-" + i);
        assertTrue(MetadataStore.writeNotes(context, notes));

        int total = 0;
        MetadataStore.Page page = MetadataStore.page(context, 0, 37);
        boolean sawMore;
        do {
            assertTrue(page.rows.size() <= 37);
            total += page.rows.size();
            sawMore = page.hasMore;
            if (sawMore) {
                assertTrue(page.nextId > 0);
                page = MetadataStore.page(context, page, 37);
            }
        } while (sawMore);
        assertEquals(notes.size(), total);
    }

    @Test public void missingTokenRecoversAndWrongTokenBlocksWithoutDeletingRows() throws Exception {
        assertTrue(MetadataStore.setNote(context, "old", "old value"));
        String oldToken = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(MetadataStore.KEY_METADATA_STORE_TOKEN, null);
        assertTrue(oldToken != null && !oldToken.isEmpty());

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .remove(MetadataStore.KEY_METADATA_STORE_TOKEN).commit();
        assertEquals("old value", MetadataStore.getNote(context, "old"));

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(MetadataStore.KEY_METADATA_STORE_TOKEN, "wrong-token").commit();
        try {
            MetadataStore.readNotes(context);
            throw new AssertionError("a different owner must not expose or replace rows");
        } catch (IllegalStateException expected) {
            // The rows remain inaccessible until the original token is restored.
        }

        context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
            .putString(MetadataStore.KEY_METADATA_STORE_TOKEN, oldToken).commit();
        assertEquals("old value", MetadataStore.getNote(context, "old"));
    }

    @Test public void resetKeepsNotesAndTagsUnlessRequested() throws Exception {
        assertTrue(MetadataStore.setNote(context, "key", "note"));
        assertTrue(MetadataStore.mergeReasons(context, singletonText("key", "reason")));
        assertTrue(MetadataStore.mergeChannels(context, singletonText("key", "channel")));
        assertTrue(MetadataStore.setTags(context, "key", Arrays.asList("tag")));

        MetadataStore.reset(context, false);
        assertEquals("note", MetadataStore.getNote(context, "key"));
        assertEquals(Arrays.asList("tag"), MetadataStore.getTags(context, "key"));
        assertTrue(MetadataStore.readReasons(context).isEmpty());
        assertTrue(MetadataStore.readChannels(context).isEmpty());

        MetadataStore.reset(context, true);
        assertTrue(MetadataStore.readNotes(context).isEmpty());
        assertTrue(MetadataStore.readTags(context).isEmpty());
    }

    private Map<String, String> singletonText(String key, String value) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(key, value);
        return out;
    }

    private String legacyValue(String key) {
        return context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(key, null);
    }
}
