package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G3: a pack the user brought is read by the same engine, on the same terms, and only where it
 * does not displace what the app already ships.
 *
 * <p>The three claims worth holding are that composition reaches the store at all, that a bundled
 * pack keeps a sender it already claims, and that a store change is visible to a running process
 * without a restart. The first two are what keeps a fork from becoming a takeover, and the third
 * is what makes an import worth doing: without it the pack would be stored and never read until the
 * app was next launched.
 */
@RunWith(AndroidJUnit4.class)
public class LocalPackCompositionTest {

    private Context context;
    private EngineRules engine;
    private String bundledSender;

    /** A sender the app ships coverage for, taken from the loaded catalog rather than written out
     *  here, so the collision this test forces is one against a sender that really is covered. */
    private static final String LOCAL_ONLY_SENDER = "+99000001";

    @Before public void clearLocalPacks() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EngineRules.localStore(context).clear();
        engine = EngineRules.activate(context);
        assertNotNull("the bundled engine must load", engine);
        bundledSender = engine.banksInOrder().get(0).templates.get(0).senders().iterator().next();
        engine.refreshLocal();
    }

    @After public void restoreBundledComposition() throws Exception {
        EngineRules.localStore(context).clear();
        engine.refreshLocal();
        assertTrue("the bundled composition is what is left", engine.localBanks().isEmpty());
        assertTrue("nothing local is reported unreadable", engine.unreadableLocal().isEmpty());
    }

    @Test public void aLocalPackIsComposedAlongsideTheShippedOnes() throws Exception {
        int shipped = engine.bankCount();
        assertTrue("this test needs a shipped sender to collide with", shipped > 0);

        LocalPackStore.Result installed = install(pack("example.composition", LOCAL_ONLY_SENDER));
        assertEquals("a new pack is installed", LocalPackStore.Outcome.INSTALLED,
                installed.outcome());
        assertEquals("the engine sees the change without a restart", true, engine.refreshLocal());

        assertEquals("every pack the engine reads is counted", shipped + 1, engine.bankCount());
        assertEquals("one local pack is composed", 1, engine.localBanks().size());
        assertEquals("the screen's bank list grows with it", shipped + 1,
                engine.allBanksInOrder().size());

        EngineRules.Bank local = engine.covers(LOCAL_ONLY_SENDER);
        assertNotNull("the local pack answers for its own sender", local);
        assertTrue("and is marked as the user's own", local.local());
        assertEquals("under the namespaced id it was stored as", "local.example.composition", local.id);
        assertEquals(PackDocument.Bank.Provenance.LOCAL, local.provenance);
        assertEquals("and its bank is addressable by the name storage keys on",
                "Composition Bank", engine.bankNameOf("local.example.composition"));

        assertNotNull("and the engine routes that sender's messages to it",
                engine.parse("local", LOCAL_ONLY_SENDER, "Balance=1,000\nDate=2026/04/04\n",
                        java.time.Instant.parse("2026-04-04T12:00:00Z"),
                        java.time.ZoneId.of("UTC")));
    }

    @Test public void aBundledPackKeepsTheSendersItAlreadyClaims() throws Exception {
        EngineRules.Bank before = engine.covers(bundledSender);
        assertNotNull("the fixture sender is covered by a shipped pack", before);
        assertFalse("which is a shipped pack", before.local());

        // The same sender, claimed by a pack the user wrote: a fork the user added, not a way to
        // replace the coverage they did not ask to replace.
        install(pack("example.fork", bundledSender, LOCAL_ONLY_SENDER));
        assertTrue("the change is noticed", engine.refreshLocal());

        EngineRules.Bank after = engine.covers(bundledSender);
        assertSame("a shipped pack keeps a sender it already claims", before, after);
        assertNotNull("and the new pack still answers for a sender of its own",
                engine.covers(LOCAL_ONLY_SENDER));
    }

    @Test public void aRemovedPackStopsBeingRead() throws Exception {
        install(pack("example.removable", LOCAL_ONLY_SENDER));
        assertTrue(engine.refreshLocal());
        assertNotNull("the pack is composed", engine.covers(LOCAL_ONLY_SENDER));

        assertTrue("the store removes it", EngineRules.localStore(context).remove(
                "local.example.removable"));
        assertTrue("the change is noticed", engine.refreshLocal());
        assertNull("and nothing answers for its sender any more", engine.covers(LOCAL_ONLY_SENDER));
    }

    @Test public void oneDamagedFileCostsThatPackOnly() throws Exception {
        install(pack("example.kept", LOCAL_ONLY_SENDER));
        assertTrue(engine.refreshLocal());

        // A file the store wrote nothing like: written straight into the directory, as a partial
        // write from another version of the app, or by hand, would look.
        java.io.File damaged = new java.io.File(EngineRules.packsDirectory(context).toFile(),
                "local.example.damaged.pack.json");
        try (java.io.OutputStream out = new java.io.FileOutputStream(damaged)) {
            out.write("{\"schema\": \"prototype-1\", \"id\":".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue("the change is noticed", engine.refreshLocal());
        assertEquals("the readable pack is still composed", 1, engine.localBanks().size());
        assertNotNull("and still answers", engine.covers(LOCAL_ONLY_SENDER));
        assertEquals("the damaged one is reported by name", List.of("local.example.damaged"),
                engine.unreadableLocal());
        assertTrue(damaged.delete());
    }

    @Test public void anUnchangedStoreIsNotReread() throws Exception {
        int generation = engine.localGeneration();
        assertEquals("an absent store is not a change", false, engine.refreshLocal());
        assertEquals("so the generation stands", generation, engine.localGeneration());

        install(pack("example.counted", LOCAL_ONLY_SENDER));
        assertTrue(engine.refreshLocal());
        assertEquals("a pack arriving is a change", generation + 1, engine.localGeneration());

        // The same pack again is a no-op at the store, and so nothing moves at the engine either.
        assertEquals("an identical import changes nothing",
                LocalPackStore.Outcome.NO_OP,
                install(pack("example.counted", LOCAL_ONLY_SENDER)).outcome());
        assertEquals("the generation stands", generation + 1, engine.localGeneration());
        assertEquals("and the composition is not rebuilt", false, engine.refreshLocal());
    }

    // ---- fixtures ----

    private LocalPackStore.Result install(Map<String, Object> pack) throws Exception {
        LocalPackStore store = EngineRules.localStore(context);
        String text = PackWriter.write(PackDocument.decode(pack));
        LocalPackStore.Pack staged = store.stage(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        return store.install(staged);
    }

    /** A fork of a pack the app really ships, with its identity and its senders replaced.
     *
     *  <p>Built from a real pack rather than from a hand-written document so the fixture cannot
     *  drift from what the schema accepts, and so "an imported pack is a legal pack" is what the
     *  test is actually about: this is the pack a user would export, edit and import back. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pack(String packId, String... senders) throws Exception {
        EngineRules.Bank donor = engine.banksInOrder().get(0);
        Map<String, Object> pack;
        try (InputStream input = context.getAssets()
                .open(donor.region + "/" + donor.id + "/pack.json")) {
            pack = PlatformRuleJson.read(input);
        }
        pack.put("id", packId);
        pack.put("revision", "r1");
        Map<String, Object> bank = (Map<String, Object>) pack.get("bank");
        bank.put("id", packId.substring(packId.indexOf('.') + 1));
        bank.put("name", "Composition Bank");
        bank.put("provenance", "COMMUNITY");
        List<Object> aliases = new ArrayList<>(List.of(senders));
        for (Object entry : (List<Object>) pack.get("templates"))
            ((Map<String, Object>) entry).put("senders", aliases);
        return pack;
    }
}
