package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests encrypted, multi-value transaction tags and their history-rebuild behavior. */
@RunWith(AndroidJUnit4.class)
public class TransactionTagTest {
    private static final long T = 1_000_000_000L;
    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MetadataStore.reset(ctx, true);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        MetadataStore.reset(ctx, true);
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Transaction tx(String content) {
        return new Transaction("Tejarat", null, T, 500_000L, "sig-" + content, content);
    }

    @Test public void tags_saveMultipleTrimDuplicatesAndRead() {
        Transaction t = tx("content-A");
        BalanceData.setTags(ctx, t, Arrays.asList(" groceries ", "Bills", "GROCERIES", " ", null));

        assertEquals(Arrays.asList("groceries", "Bills"), BalanceData.getTags(ctx, t));
        assertEquals(Arrays.asList("groceries", "Bills"), BalanceData.readTagNames(ctx));
    }

    @Test public void tags_areUncappedAndBlankInputRemovesAssignment() {
        Transaction t = tx("content-A");
        StringBuilder longTag = new StringBuilder();
        for (int i = 0; i < BalanceData.MAX_TAG_LENGTH + 20; i++) longTag.append('x');
        List<String> many = new ArrayList<>();
        many.add(longTag.toString());
        for (int i = 0; i < BalanceData.MAX_TAGS_PER_TRANSACTION + 4; i++) many.add("tag-" + i);

        BalanceData.setTags(ctx, t, many);
        List<String> saved = BalanceData.getTags(ctx, t);
        assertEquals(many.size(), saved.size());
        assertEquals(longTag.length(), saved.get(0).length());

        BalanceData.setTags(ctx, t, Arrays.asList(" "));
        assertTrue(BalanceData.getTags(ctx, t).isEmpty());
        assertTrue(BalanceData.readTags(ctx).isEmpty());
    }

    @Test public void tags_followContentIdentityAndNeverChangeTransactionIdentity() {
        Transaction fresh = tx("same-content");
        Transaction reparsed = new Transaction("Melli", "1110000222", T + 20, -9L, "sig-new",
            "same-content");
        String identity = BalanceData.txIdentityKey(fresh);
        BalanceData.setTags(ctx, fresh, Arrays.asList("recurring"));

        assertEquals(Arrays.asList("recurring"), BalanceData.getTags(ctx, reparsed));
        assertEquals(identity, BalanceData.txIdentityKey(fresh));
    }

    @Test public void tags_migrateLegacyKeyAndUnionDestinationTags() {
        Transaction legacy = new Transaction("Tejarat", null, T, 500_000L, null, null);
        Transaction fresh = tx("content-A");
        BalanceData.setTags(ctx, legacy, Arrays.asList("old", "shared"));
        BalanceData.setTags(ctx, fresh, Arrays.asList("new", "SHARED"));

        Map<Transaction, Transaction> replaced = new LinkedHashMap<>();
        replaced.put(legacy, fresh);
        BalanceData.migrateTransactionText(ctx, replaced);

        assertTrue(BalanceData.getTags(ctx, legacy).isEmpty());
        assertEquals(Arrays.asList("new", "SHARED", "old"), BalanceData.getTags(ctx, fresh));
    }

    @Test public void tags_resetKeepsByDefaultAndDeletesWhenRequested() {
        Transaction t = tx("content-A");
        BalanceData.setTags(ctx, t, Arrays.asList("keep"));

        BalanceData.reset(ctx, false);
        assertEquals(Arrays.asList("keep"), BalanceData.getTags(ctx, t));

        BalanceData.reset(ctx, true);
        assertTrue(BalanceData.getTags(ctx, t).isEmpty());
    }

    @Test public void tags_serializeRoundTripKeepsNamesAndOrder() throws Exception {
        Transaction t = tx("content-A");
        Map<String, List<String>> original = new LinkedHashMap<>();
        original.put(BalanceData.noteKey(t), Arrays.asList("one", "two"));
        String json = BalanceData.serializeTagsMap(original);
        assertEquals(Arrays.asList("one", "two"),
            BalanceData.deserializeTagsMap(json).get(BalanceData.noteKey(t)));
        assertFalse(json.contains("transaction_tags"));
    }
}
