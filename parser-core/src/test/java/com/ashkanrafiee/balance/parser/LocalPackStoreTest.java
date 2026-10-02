package com.ashkanrafiee.balance.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds the local pack store to what a user is owed when a pack of their own is imported: it never
 * takes a catalog pack's identity, never changes what an id means without being told, survives a
 * crash with the previous pack intact, and never lets one unreadable file cost the user the rest.
 *
 * <p>The identity table is the point of the gate, so it is exercised row by row against a real
 * directory rather than a stub: the same pack twice is a no-op, a higher revision updates, the same
 * revision with different content is refused, a lower revision needs an explicit revert, and a
 * bundled id arriving by import becomes a local fork rather than a replacement. Every stored file is
 * read back through the same strict reader the app uses, and compared by digest against the canonical
 * text the encoder produces, so a pack the store wrote is a pack the app can load unchanged.
 *
 * <p>Dependency-free executable main like its sibling suites: parser-core has no test framework,
 * and the store is plain Java over a directory, which is what makes it testable at all.
 */
public final class LocalPackStoreTest {
    private static int checks;
    private static int stores;
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("local-pack-store");
        try {
            namespacing();
            identityTable();
            storedBytesAreCanonical();
            crashSafety();
            damagedFileIsIsolated();
            removal();
            bounds();
            idsCannotEscapeTheDirectory();
            idsTheStoreCannotNameAreRefused();
            aFileTheStoreWouldNotNameIsIsolated();
        } finally {
            deleteTree(root);
        }
        System.out.println("LocalPackStoreTest: " + checks + " checks passed");
    }

    /** A local pack is never a catalog pack: the pack id is prefixed, the bank id follows, and the
     *  provenance becomes LOCAL whatever the imported file claimed. A pack already in the local
     *  namespace keeps its own name, so importing an export is a no-op rather than a second copy
     *  under a longer id, and a fork's upstream reference stays readable in the id itself. */
    private static void namespacing() throws Exception {
        LocalPackStore store = open();
        LocalPackStore.Pack forked = store.stage(document("official.ir.tejarat", "ir.tejarat", "r1", "OFFICIAL", null));
        equal(forked.packId(), "local.official.ir.tejarat");
        equal(forked.document().bank().id(), "local.ir.tejarat");
        equal(forked.document().bank().provenance(), PackDocument.Bank.Provenance.LOCAL);
        equal(forked.forks("official.ir.tejarat"), true);
        equal(forked.forks("official.ir.ansar"), false);

        LocalPackStore.Pack claimed = store.stage(document("community.it.cartabcc", "it.cartabcc", "it-1", "OFFICIAL", null));
        equal(claimed.document().bank().provenance(), PackDocument.Bank.Provenance.LOCAL,
            "a declared provenance is not honoured");
        equal(claimed.packId(), "local.community.it.cartabcc");

        LocalPackStore.Pack already = store.stage(document("local.example.pack", "local.example.bank", "r1", "LOCAL", null));
        equal(already.packId(), "local.example.pack", "a local id is kept as it stands");
        equal(already.document().bank().id(), "local.example.bank");

        // The templates follow the new ids, so a fork's template keys cannot point at the catalog.
        for (Rules.Template template : forked.document().templates()) {
            equal(template.packId(), "local.official.ir.tejarat");
            equal(template.bankId(), "local.ir.tejarat");
        }
    }

    /** Every row of the import table, against a store that already holds the pack each time. */
    private static void identityTable() throws Exception {
        LocalPackStore store = open();
        LocalPackStore.Result installed = store.install(stage(store, "example.bank", "r1", null));
        equal(installed.outcome(), LocalPackStore.Outcome.INSTALLED);
        equal(installed.replacedRevision(), null);

        // Identical content, a second time: nothing changes, and the caller is told so.
        LocalPackStore.Result again = store.install(stage(store, "example.bank", "r1", null));
        equal(again.outcome(), LocalPackStore.Outcome.NO_OP);
        equal(store.snapshot().size(), 1, "a no-op install stored nothing");

        // A higher revision of the same pack updates it.
        LocalPackStore.Result updated = store.install(stage(store, "example.bank", "r2", "Changed"));
        equal(updated.outcome(), LocalPackStore.Outcome.UPDATED);
        equal(updated.replacedRevision(), "r1");
        equal(store.snapshot().find("local.example.bank").revision(), "r2");

        // The same revision with different content is two meanings for one id, refused outright.
        equal(refused(store, document("example.bank", "r2", "Other"),
            LocalPackStore.Code.CONFLICT), true, "same revision, different content refused");
        equal(store.snapshot().find("local.example.bank").revision(), "r2", "revision unchanged");

        // A lower revision is a revert, which is a decision rather than an accident.
        equal(refused(store, document("example.bank", "r1", "Changed"),
            LocalPackStore.Code.DOWNGRADE), true, "a silent downgrade is refused");
        LocalPackStore.Result reverted = store.install(
                stage(store, "example.bank", "r1", "Changed"), true);
        equal(reverted.outcome(), LocalPackStore.Outcome.UPDATED);
        equal(reverted.replacedRevision(), "r2");
        equal(store.snapshot().find("local.example.bank").revision(), "r1", "reverted");

        // Revisions of one prefix order by their number, and any other pair orders as text.
        equal(LocalPackStore.compareRevisions("r2", "r10") < 0, true, "numbers, not characters");
        equal(LocalPackStore.compareRevisions("ir-1", "it-1") < 0, true, "different prefixes");
        equal(LocalPackStore.compareRevisions("r1", "draft-1") > 0, true, "text order");
        equal(LocalPackStore.compareRevisions("r1", "r1"), 0, "equal revisions");

        // A malformed document never reaches the directory.
        equal(refused(store, raw("{\"schema\": \"prototype-1\"}"), LocalPackStore.Code.MALFORMED), true,
            "malformed refused");
        equal(refused(store, raw("{\"schema\": \"prototype-1\", \"id\": \"a\", \"id\": \"b\"}"),
            LocalPackStore.Code.MALFORMED), true,
            "a duplicate key is malformed");
        equal(store.snapshot().size(), 1, "a refused install stored nothing");
    }

    /** What lands on disk is the canonical text, so a digest means the same thing tomorrow, and
     *  reading a stored pack back gives the document the stage gave. */
    private static void storedBytesAreCanonical() throws Exception {
        LocalPackStore store = open();
        LocalPackStore.Pack staged = stage(store, "example.canonical", "r1", null);
        store.install(staged);
        byte[] onDisk = Files.readAllBytes(store.pathOf("local.example.canonical"));
        equal(PackWriter.write(staged.document()), new String(onDisk, StandardCharsets.UTF_8),
            "canonical text on disk");
        equal(LocalPackStore.digest(onDisk), staged.digest(), "the stored bytes hash to the digest");
        try (var entries = Files.list(store.directory())) {
            equal(entries.count(), 1L, "one file per pack");
        }

        LocalPackStore.Snapshot snapshot = store.snapshot();
        equal(snapshot.packs().size(), 1);
        equal(snapshot.unreadable().isEmpty(), true);
        equal(snapshot.packs().get(0).digest(), staged.digest(), "the digest survives the round trip");
        equal(snapshot.packs().get(0).document(), staged.document());
        equal(PackDocument.decode(StrictJson.object(
                StrictJson.parse(new String(onDisk, StandardCharsets.UTF_8)), "stored")),
            staged.document(), "the stored document decodes");

        // The canonical form of the same pack is one text whichever order it was built in, so a
        // digest never turns on how a document happened to be assembled.
        equal(store.stage(document("example.canonical", "r1", null)).digest(), staged.digest(),
            "the digest does not depend on member order");
    }

    /** A write that did not finish leaves the previous pack, and its leftovers are swept rather
     *  than offered as packs: what a crash left in the directory is not a pack the user installed. */
    private static void crashSafety() throws Exception {
        LocalPackStore store = open();
        store.install(stage(store, "example.crash", "r1", "One"));
        Path interrupted = store.directory().resolve("local.example.crash.pack.json.tmp");
        Files.writeString(interrupted, "{\"schema\": \"prototype-1\", \"id\": \"local.example.hal");

        // Reading the store while a temp file sits there sees the real pack and nothing else.
        LocalPackStore reopened = LocalPackStore.open(store.directory(), StrictJson::read);
        equal(Files.exists(interrupted), false, "a temp file is swept on open");
        equal(reopened.snapshot().size(), 1, "a temp file is not a pack");
        equal(reopened.snapshot().packs().get(0).revision(), "r1");

        // An interrupted update is replaced whole: the held pack is the old one until the rename,
        // and the new one after it, with nothing in between to read.
        LocalPackStore.Pack next = reopened.stage(document("example.crash", "r2", "Two"));
        reopened.install(next);
        equal(reopened.snapshot().find("local.example.crash").revision(), "r2");
        equal(reopened.snapshot().find("local.example.crash").document(), next.document());
    }

    /** One damaged file costs the user that pack, not the rest of their rules, and it is named so
     *  the app can tell them which one. */
    private static void damagedFileIsIsolated() throws Exception {
        LocalPackStore store = open();
        store.install(stage(store, "example.good", "r1", null));
        store.install(stage(store, "example.bad", "r1", null));
        Files.writeString(store.pathOf("local.example.bad"), "{ not a pack");

        LocalPackStore.Snapshot snapshot = store.snapshot();
        equal(snapshot.packs().size(), 1);
        equal(snapshot.packs().get(0).packId(), "local.example.good");
        equal(snapshot.unreadable(), List.of("local.example.bad"));

        // A file whose name is not the id it carries is not the pack the store believes it holds.
        Files.writeString(store.pathOf("local.example.mislabelled"),
            PackWriter.write(stage(store, "example.other", "r1", null).document()));
        equal(store.snapshot().unreadable(), List.of("local.example.bad", "local.example.mislabelled"),
            "a mislabelled file is refused");

        // And an install over a damaged file still works, because what counts is the held pack.
        equal(store.install(stage(store, "example.bad", "r1", null)).outcome(),
            LocalPackStore.Outcome.INSTALLED);
        equal(store.snapshot().unreadable(), List.of("local.example.mislabelled"));
    }

    private static void removal() throws Exception {
        LocalPackStore store = open();
        store.install(stage(store, "example.keep", "r1", null));
        store.install(stage(store, "example.drop", "r1", null));
        equal(store.remove("local.example.drop"), true);
        equal(store.remove("local.example.drop"), false, "removing twice is not an error");
        equal(store.snapshot().size(), 1);
        equal(store.snapshot().find("local.example.keep").packId(), "local.example.keep");
        store.clear();
        equal(store.snapshot().packs().isEmpty(), true);
    }

    /** The bounds a store declares are the bounds it keeps: the pack count stops the scan path from
     *  growing without end, and a document past the reader's own limit is refused rather than
     *  truncated. A path cannot be steered outside the store either. */
    private static void bounds() throws Exception {
        LocalPackStore store = open();
        for (int i = 0; i < LocalPackStore.MAX_PACKS; i++)
            store.install(stage(store, "example.bound" + i, "r1", null));
        equal(store.snapshot().size(), LocalPackStore.MAX_PACKS);
        equal(refused(store, document("example.overflow", "r1", null),
            LocalPackStore.Code.FULL), true, "the pack count is bounded");
        equal(store.snapshot().size(), LocalPackStore.MAX_PACKS, "the refused pack was not stored");

        equal(refused(store, raw("{\"pad\": \"" + "x".repeat(JsonLexicalGuard.MAX_DOCUMENT_BYTES) + "\"}"),
            LocalPackStore.Code.MALFORMED), true, "an oversized document is refused");
        equal(store.pathOf("local.example.bound0").getParent(), store.directory());
    }

    /** A pack id is the only name a caller supplies, and it names one file inside the directory or
     *  nothing: nothing can be reached with a path, and deletion refuses what it does not own. */
    private static void idsCannotEscapeTheDirectory() throws Exception {
        LocalPackStore store = open();
        for (String id : List.of("example.bank", "LOCAL.example.bank", "local.Example.bank",
                "local.example.bank/../escape", "local..bank", "local.example bank", "local.",
                "local.example.bank/")) {
            equal(LocalPackStore.isStoredId(id), false, "not a stored id: " + id);
            equal(store.remove(id), false, "nothing removed for: " + id);
            throwsIllegalArgument(() -> store.pathOf(id), id);
        }
        for (String id : List.of("local.example.bank", "local.official.ir.tejarat",
                "local.example.b-2", "local.a"))
            equal(LocalPackStore.isStoredId(id), true, "a stored id: " + id);

        // An id may end in a segment that reads like one of our suffixes, because it names its own
        // file by appending one: it lands on a path the sweep and the reader both treat as a pack.
        store.install(stage(store, "example.tmp", "r1", null));
        store.install(stage(store, "example.pack.json", "r1", null));
        LocalPackStore.Snapshot snapshot = store.snapshot();
        equal(snapshot.size(), 2, "both stored");
        equal(snapshot.unreadable().isEmpty(), true, "neither swept nor unreadable");
        equal(store.remove("local.example.tmp"), true);
        equal(store.remove("local.example.pack.json"), true);
    }

    private static void throwsIllegalArgument(Runnable action, String id) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("pathOf accepted " + id);
    }

    // ---- helpers ----

    private static LocalPackStore open() throws IOException {
        Path directory = Files.createDirectories(root.resolve("store-" + stores++));
        return LocalPackStore.open(directory, StrictJson::read);
    }

    /** Stages the canonical text of a synthetic pack, which is exactly what an import hands the
     *  store: an encoder wrote it, so it is a text the encoder would produce again. */
    private static LocalPackStore.Pack stage(LocalPackStore store, String packId, String revision,
            String anchor) throws IOException {
        return store.stage(document(packId, revision, anchor));
    }

    /** Text as it arrived, for the documents no encoder would write: what a broken or hostile
     *  import looks like. */
    private static InputStream raw(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The canonical text of a pack whose bank shares the tail of its pack id, which is how every
     *  shipped pack is named ({@code official.ir.ansar} for bank {@code ir.ansar}). */
    private static InputStream document(String packId, String revision, String anchor) {
        return document(packId, packId.substring(packId.indexOf('.') + 1), revision, "COMMUNITY",
            anchor);
    }

    private static InputStream document(String packId, String bankId, String revision,
            String provenance, String anchor) {
        String text = PackWriter.write(PackDocument.decode(
                SyntheticPack.named(packId, bankId, revision, provenance, anchor)));
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether the store refused this input with this code, which is the shape every refusal in the
     *  identity table takes. */
    private static boolean refused(LocalPackStore store, InputStream document,
            LocalPackStore.Code expected) throws IOException {
        try {
            store.install(store.stage(document));
            return false;
        } catch (LocalPackStore.Failure e) {
            equal(e.code, expected, "refusal code");
            return true;
        }
    }

    /** An id the reader accepts but this store cannot spell is refused where the local identity is
     *  derived, not left to fail later. {@code Rules.id} allows {@code _} and the published schema
     *  allows it too, so a pack built to the documented schema can carry an id no stored file may be
     *  named -- and staging it used to succeed, preview as an install, and then throw an unchecked
     *  IllegalArgumentException from pathOf the moment the user confirmed. */
    private static void idsTheStoreCannotNameAreRefused() throws Exception {
        for (String id : List.of("my_pack", "example.pack_")) {
            LocalPackStore store = open();
            equal(refused(store, document(id, "r1", null), LocalPackStore.Code.MALFORMED), true,
                "refused at the door rather than crashing on confirm: " + id);
        }

        // Only the pack id names a file. The same character in the bank id, which names no file, is
        // still perfectly storable, and refusing that too would refuse packs the schema allows.
        LocalPackStore store = open();
        equal(store.install(store.stage(document("example.bankname", "my_bank", "r1", "COMMUNITY", "anchor")))
                .outcome(), LocalPackStore.Outcome.INSTALLED,
            "an underscore in the bank id alone is no reason to refuse a pack");
    }

    /** A file this store would never name is still only one bad file. Its stem is not a stored id, so
     *  pathOf refuses it; before this, that refusal escaped as an unchecked throw out of snapshot()
     *  and clear(), and one stray file took the whole engine down rather than costing itself. */
    private static void aFileTheStoreWouldNotNameIsIsolated() throws Exception {
        LocalPackStore store = open();
        store.install(stage(store, "example.good", "r1", null));
        Files.writeString(store.directory().resolve("Evil.pack.json"), "{ not a pack");

        LocalPackStore.Snapshot snapshot = store.snapshot();
        equal(snapshot.packs().size(), 1, "the good pack is still composed");
        equal(snapshot.packs().get(0).packId(), "local.example.good");

        store.clear();
        equal(store.snapshot().packs().size(), 0, "and the store can still be emptied");
    }

    /** Deletes what the gate created, so a run leaves nothing behind. */
    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var entries = Files.walk(directory)) {
            List<Path> paths = new ArrayList<>();
            entries.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--) Files.deleteIfExists(paths.get(i));
        }
    }

    private static void equal(Object actual, Object expected) {
        equal(actual, expected, "check");
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected))
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
}