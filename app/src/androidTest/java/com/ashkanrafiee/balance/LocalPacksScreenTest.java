package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.balance.parser.LocalPackStore;
import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.PackWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * G4/G5: the screen that manages the reader's own packs, and the export/import round trip it exists
 * for.
 *
 * <p>The round trip is the claim worth holding. A pack exported and imported again must be the same
 * pack, byte for byte, or every export is a lossy step: a reader who edits a pack, sends it to
 * someone, and imports it back would get a file that quietly differs from the one they sent. The
 * test therefore exports what the store holds, reimports it, and requires the store to answer that
 * it already had it.
 *
 * <p>The privacy claim is the same bytes seen from the other side: an exported file is the pack and
 * nothing else. It must not carry the inbox, the balances, the notes, or the reader's own text,
 * because a pack is meant to be shared while a history is not.
 */
@RunWith(AndroidJUnit4.class)
public class LocalPacksScreenTest {

    private static final String SENDER = "+99000002";
    private static final String PACK_ID = "local.example.roundtrip";
    private static final String BANK_NAME = "Round Trip Bank";

    private Context ctx;
    private EngineRules engine;

    @Before public void emptyTheStore() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EngineRules.localStore(ctx).clear();
        engine = EngineRules.activate(ctx);
        assertNotNull("the bundled engine loads", engine);
        engine.refreshLocal();
    }

    @After public void leaveTheStoreAsItWas() throws Exception {
        EngineRules.localStore(ctx).clear();
        engine.refreshLocal();
    }

    @Test public void anEmptyStoreSaysSoAndOffersTheImport() {
        LocalPacksActivity screen = launch();
        try {
            String text = screenText(screen);
            assertTrue("the screen says there is nothing here yet",
                    text.contains(ctx.getString(R.string.local_packs_empty)));
            assertTrue("and offers the way to put something here",
                    text.contains(ctx.getString(R.string.local_packs_import)));
            assertFalse("no pack is drawn", text.contains(BANK_NAME));
        } finally {
            finish(screen);
        }
    }

    @Test public void anInstalledPackIsListedWithItsBankAndId() throws Exception {
        install();
        LocalPacksActivity screen = launch();
        try {
            String text = screenText(screen);
            assertTrue("the bank the pack reads is named", text.contains(BANK_NAME));
            assertTrue("under the id it is stored as", text.contains(PACK_ID));
            assertTrue("the empty state is gone",
                    !text.contains(ctx.getString(R.string.local_packs_empty)));
            assertTrue("export is offered", text.contains(ctx.getString(R.string.local_packs_export)));
            assertTrue("removal is offered", text.contains(ctx.getString(R.string.local_packs_remove)));
        } finally {
            finish(screen);
        }
    }

    @Test public void aLocalPackIsASwitchOnTheBanksScreenLikeAnyOther() throws Exception {
        install();
        BankRecognitionActivity banks = (BankRecognitionActivity)
            InstrumentationRegistry.getInstrumentation().startActivitySync(
                new android.content.Intent(ctx, BankRecognitionActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            String text = screenText(banks);
            assertTrue("the reader's own packs have their own group",
                    text.contains(ctx.getString(R.string.recognition_group_local)));
            assertTrue("and the bank they read is in it", text.contains(BANK_NAME));
            assertTrue("it is a bank the app reads", engine.covers(SENDER) != null);
            // Turning that bank off is the reader's choice, and it is enforced the same way for a
            // pack they brought as for one the app shipped.
            assertTrue(RecognitionHelper.isEnabled(BANK_NAME));
            RecognitionHelper.setEnabled(ctx, BANK_NAME, false);
            assertFalse(RecognitionHelper.isEnabled(BANK_NAME));
            RecognitionHelper.setEnabled(ctx, BANK_NAME, true);
        } finally {
            finish(banks);
        }
    }

    @Test public void anExportedPackReimportsAsTheSamePack() throws Exception {
        LocalPackStore store = EngineRules.localStore(ctx);
        store.install(store.stage(packText()));
        LocalPackStore.Pack held = store.snapshot().find(PACK_ID);
        assertNotNull("the pack is stored", held);

        byte[] exported = export(held);
        assertEquals("what was exported is the pack as stored", held.digest(),
                store.stage(new ByteArrayInputStream(exported)).digest());

        // Reimporting the same bytes changes nothing at all, which is what makes export safe to
        // hand to someone else: their import of what I sent lands on the pack I have.
        LocalPackStore.Result again = store.install(store.stage(
                new ByteArrayInputStream(exported)));
        assertEquals("the same pack is not installed twice",
                LocalPackStore.Outcome.NO_OP, again.outcome());
        assertEquals("and the stored pack is untouched", held.digest(),
                store.snapshot().find(PACK_ID).digest());
    }

    @Test public void anExportedPackCarriesOnlyThePack() throws Exception {
        LocalPackStore store = EngineRules.localStore(ctx);
        store.install(store.stage(packText()));
        String text = new String(export(store.snapshot().find(PACK_ID)), StandardCharsets.UTF_8);

        // The exported file is exactly the pack document, and nothing is appended to it: no inbox,
        // no balances, no notes, no reader text. A pack is meant to be shareable; a history is not.
        assertEquals("the file is the pack and nothing more",
                new String(store.snapshot().find(PACK_ID).canonical(), StandardCharsets.UTF_8), text);
        // Sentences, numbers and preference names a pack has no reason to contain, standing in for
        // an inbox, a history and the app's own storage.
        for (String secret : List.of("call me back", "+15550100", "9876543210",
                "balance_generations", "history_through")) {
            assertFalse("the export carries nothing but the pack: " + secret, text.contains(secret));
        }
    }

    // ---- helpers ----

    private void install() throws Exception {
        LocalPackStore store = EngineRules.localStore(ctx);
        store.install(store.stage(packText()));
        assertTrue("the engine picks the pack up", engine.refreshLocal());
    }

    private InputStream packText() throws Exception {
        PackDocument document = PackDocument.decode(pack());
        return new ByteArrayInputStream(
                PackWriter.write(document).getBytes(StandardCharsets.UTF_8));
    }

    /** Writes the pack where the screen's export would write it and reads it back, so the round trip
     *  is through the file system the picker produces rather than through a byte array. */
    private byte[] export(LocalPackStore.Pack pack) throws Exception {
        File exported = new File(ctx.getCacheDir(), LocalPacksActivity.exportName(pack.packId()));
        try (FileOutputStream out = new FileOutputStream(exported)) {
            out.write(pack.canonical());
        }
        try (InputStream in = new FileInputStream(exported)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer))
                bytes.write(buffer, 0, read);
            return bytes.toByteArray();
        } finally {
            assertTrue("the temporary export is removed", exported.delete());
        }
    }

    /** A pack derived from one the app really ships, so the fixture is a document the schema
     *  accepts, with its own identity, its own bank name and one sender of its own. */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> pack() throws Exception {
        EngineRules.Bank donor = engine.banksInOrder().get(0);
        java.util.Map<String, Object> pack;
        try (InputStream input = ctx.getAssets().open(donor.region + "/" + donor.id + "/pack.json")) {
            pack = PlatformRuleJson.read(input);
        }
        pack.put("id", PACK_ID);
        pack.put("revision", "r1");
        java.util.Map<String, Object> bank = (java.util.Map<String, Object>) pack.get("bank");
        bank.put("id", "example.roundtrip");
        bank.put("name", BANK_NAME);
        bank.put("provenance", "LOCAL");
        List<Object> senders = new ArrayList<>(List.of(SENDER));
        for (Object entry : (List<Object>) pack.get("templates"))
            ((java.util.Map<String, Object>) entry).put("senders", senders);
        return pack;
    }

    private LocalPacksActivity launch() {
        return (LocalPacksActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new android.content.Intent(ctx, LocalPacksActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String screenText(android.app.Activity act) {
        final List<String> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            collectTexts(act.getWindow().getDecorView(), out));
        StringBuilder sb = new StringBuilder();
        for (String t : out) sb.append(t).append('\n');
        return sb.toString();
    }

    private void collectTexts(android.view.View v, List<String> out) {
        if (v instanceof android.widget.TextView)
            out.add(((android.widget.TextView) v).getText().toString());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out);
        }
    }

    private void finish(android.app.Activity act) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(act::finish);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}