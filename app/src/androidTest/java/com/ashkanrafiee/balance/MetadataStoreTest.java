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
        assertTrue(MetadataStore.mergeMerchants(context,
            singletonText("merchant-key", "فروشگاه نمونه")));
        assertTrue(MetadataStore.setTags(context, "tag-key", Arrays.asList(" food ", "Bills", "FOOD")));

        assertEquals("private note", MetadataStore.getNote(context, "note-key"));
        assertEquals("bank reason", MetadataStore.readReasons(context).get("reason-key"));
        assertEquals("bank channel", MetadataStore.readChannels(context).get("channel-key"));
        assertEquals("فروشگاه نمونه", MetadataStore.readMerchants(context).get("merchant-key"));
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
                    assertFalse(payload.contains("فروشگاه نمونه"));
                    assertFalse(payload.contains("food"));
                }
            }
        } finally {
            db.close();
        }
    }

    @Test public void lookupSession_matchesPointLookupsInBatches() throws Exception {
        assertTrue(MetadataStore.setNote(context, "note-key", "private note"));
        assertTrue(MetadataStore.setTags(context, "tag-key", Arrays.asList("food", "Bills")));
        List<String> keys = Arrays.asList("note-key", "tag-key", "absent-key", "note-key");
        try (MetadataStore.LookupSession session = MetadataStore.LookupSession.open(context)) {
            Map<String, String> notes = session.texts(MetadataStore.NOTES, keys);
            assertEquals("private note", notes.get("note-key"));
            assertEquals(1, notes.size());
            assertEquals("private note",
                session.text(MetadataStore.NOTES, "note-key"));
            assertNull(session.text(MetadataStore.NOTES, "absent-key"));

            Map<String, List<String>> tags = session.tagsFor(keys);
            assertEquals(Arrays.asList("food", "Bills"), tags.get("tag-key"));
            assertEquals(1, tags.size());
            assertEquals(Arrays.asList("food", "Bills"), session.tags("tag-key"));
            assertTrue(session.tags("absent-key").isEmpty());
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

    @Test public void merchantSchemaUpgradePreservesExistingMetadataAndReopens() throws Exception {
        assertTrue(MetadataStore.setNote(context, "old-note", "note before merchant schema"));
        assertTrue(MetadataStore.mergeReasons(context,
            singletonText("old-reason", "reason before merchant schema")));
        assertTrue(MetadataStore.mergeChannels(context,
            singletonText("old-channel", "channel before merchant schema")));
        assertTrue(MetadataStore.setTags(context, "old-tags", Arrays.asList("one", "two")));

        // Recreate the actual v1 table shape. Merchant rows did not exist yet, so only the four
        // original kinds can be copied into the old CHECK constraint before opening v2 again.
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(MetadataStore.DB_NAME).getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        try {
            db.execSQL("CREATE TABLE metadata_v1 (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " kind INTEGER NOT NULL CHECK(kind BETWEEN 1 AND 4),"
                + " lookup TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)");
            db.execSQL("INSERT INTO metadata_v1(id,kind,lookup,payload)"
                + " SELECT id,kind,lookup,payload FROM " + MetadataStore.TABLE);
            db.execSQL("DROP INDEX metadata_kind_order");
            db.execSQL("CREATE INDEX metadata_kind_order ON metadata_v1(kind, id)");
            db.execSQL("DROP TABLE " + MetadataStore.TABLE);
            db.execSQL("ALTER TABLE metadata_v1 RENAME TO " + MetadataStore.TABLE);
            db.setVersion(1);
        } finally {
            db.close();
        }

        // The next access reopens the file and runs the v1 -> v2 table migration.
        assertEquals("note before merchant schema", MetadataStore.readNotes(context).get("old-note"));
        assertEquals("reason before merchant schema",
            MetadataStore.readReasons(context).get("old-reason"));
        assertEquals("channel before merchant schema",
            MetadataStore.readChannels(context).get("old-channel"));
        assertEquals(Arrays.asList("one", "two"), MetadataStore.readTags(context).get("old-tags"));

        assertTrue(MetadataStore.mergeMerchants(context,
            singletonText("new-merchant", "merchant after schema upgrade")));
        assertEquals("merchant after schema upgrade",
            MetadataStore.readMerchants(context).get("new-merchant"));
    }

    @Test public void migrateTransactionMovesAllKindsLocalFirstIncludingMerchants() throws Exception {
        Transaction source = new Transaction("AliasBank", "account", 100L, -42L,
            null, null, null);
        Transaction destination = new Transaction("AliasBank", "account", 100L, -42L,
            null, null, "parser-v2-content");
        String sourceKey = BalanceData.noteKey(source);
        String destinationKey = BalanceData.noteKey(destination);

        assertTrue(MetadataStore.setNote(context, sourceKey, "source note"));
        assertTrue(MetadataStore.setNote(context, destinationKey, "local note"));
        assertTrue(MetadataStore.mergeReasons(context,
            singletonText(sourceKey, "source reason")));
        assertTrue(MetadataStore.mergeReasons(context,
            singletonText(destinationKey, "local reason")));
        assertTrue(MetadataStore.mergeChannels(context,
            singletonText(sourceKey, "source channel")));
        assertTrue(MetadataStore.mergeChannels(context,
            singletonText(destinationKey, "local channel")));
        assertTrue(MetadataStore.mergeMerchants(context,
            singletonText(sourceKey, "source merchant")));
        assertTrue(MetadataStore.mergeMerchants(context,
            singletonText(destinationKey, "local merchant")));
        assertTrue(MetadataStore.setTags(context, sourceKey, Arrays.asList("source", "shared")));
        assertTrue(MetadataStore.setTags(context, destinationKey,
            Arrays.asList("local", "SHARED")));

        Map<Transaction, Transaction> replaced = new LinkedHashMap<>();
        replaced.put(source, destination);
        assertTrue(MetadataStore.migrateTransactionText(context, replaced));

        assertEquals("local note", MetadataStore.readNotes(context).get(destinationKey));
        assertEquals("local reason", MetadataStore.readReasons(context).get(destinationKey));
        assertEquals("local channel", MetadataStore.readChannels(context).get(destinationKey));
        assertEquals("local merchant", MetadataStore.readMerchants(context).get(destinationKey));
        assertEquals(Arrays.asList("local", "SHARED", "source"),
            MetadataStore.getTags(context, destinationKey));
        assertFalse(MetadataStore.readNotes(context).containsKey(sourceKey));
        assertFalse(MetadataStore.readReasons(context).containsKey(sourceKey));
        assertFalse(MetadataStore.readChannels(context).containsKey(sourceKey));
        assertFalse(MetadataStore.readMerchants(context).containsKey(sourceKey));
        assertFalse(MetadataStore.readTags(context).containsKey(sourceKey));
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
        assertTrue(MetadataStore.mergeMerchants(context, singletonText("key", "merchant")));
        assertTrue(MetadataStore.setTags(context, "key", Arrays.asList("tag")));

        MetadataStore.reset(context, false);
        assertEquals("note", MetadataStore.getNote(context, "key"));
        assertEquals(Arrays.asList("tag"), MetadataStore.getTags(context, "key"));
        assertTrue(MetadataStore.readReasons(context).isEmpty());
        assertTrue(MetadataStore.readChannels(context).isEmpty());
        assertTrue(MetadataStore.readMerchants(context).isEmpty());

        MetadataStore.reset(context, true);
        assertTrue(MetadataStore.readNotes(context).isEmpty());
        assertTrue(MetadataStore.readTags(context).isEmpty());
    }

    @Test public void scanMerchantReplacementCorrectsOnlyTheSourcedValue() throws Exception {
        assertTrue(MetadataStore.mergeMerchants(context, singletonText("key", "old merchant")));
        assertTrue(MetadataStore.replaceMerchants(context,
            singletonText("key", "corrected merchant")));
        assertEquals("corrected merchant", MetadataStore.readMerchants(context).get("key"));
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
