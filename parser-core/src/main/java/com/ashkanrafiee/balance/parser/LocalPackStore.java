package com.ashkanrafiee.balance.parser;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The packs a user brought or wrote themselves: one canonical JSON document each, held in an
 * app-private directory, named by their own ids.
 *
 * <p>A local pack is not a bundled pack with a different label. It gets its own namespace, so an
 * imported {@code official.ir.tejarat} is stored as {@code local.official.ir.tejarat} and can
 * never displace, shadow or inherit the catalog pack it came from; the upstream name stays
 * readable in the local id, which keeps the reference visible without a second table of our own.
 * The stored provenance is LOCAL whatever the file said, so a pack cannot buy catalog standing by
 * declaring it.
 *
 * <p>Installing follows one identity table, and the caller is told the outcome rather than asked
 * to choose:
 *
 * <table>
 * <caption>Import outcomes</caption>
 * <tr><th>What arrived</th><th>Result</th></tr>
 * <tr><td>Same pack, same bytes</td><td>{@link Outcome#NO_OP}</td></tr>
 * <tr><td>A pack this store does not hold</td><td>{@link Outcome#INSTALLED}</td></tr>
 * <tr><td>Same pack, higher revision</td><td>{@link Outcome#UPDATED}</td></tr>
 * <tr><td>Same pack and revision, different content</td><td>{@link Code#CONFLICT}</td></tr>
 * <tr><td>Same pack, lower revision</td><td>{@link Code#DOWNGRADE}, unless revert was asked for</td></tr>
 * </table>
 *
 * <p>Nothing is written until the whole document has been validated, namespaced and canonicalised,
 * and the write itself is a temp file that is forced to disk and then renamed over the old one, so
 * a crash leaves either the previous pack or the new one. A pack this store wrote is therefore
 * always readable, and a temp file left behind by a crash is swept on the next open rather than
 * offered as a pack.
 *
 * <p>One unreadable file does not hide the rest: {@link #snapshot()} returns what could be read
 * together with the file names that could not, so a damaged pack costs the user that pack rather
 * than their other rules, and the composition layer can say so.
 *
 * <p>The store is plain Java over a directory and reads through a {@link Codec} it is handed, so
 * the same strict reader that accepts a bundled pack is the one that reads a stored one and the
 * one that reads an imported one. Where the directory comes from — app-private files on Android,
 * a temporary directory in the gates — is the caller's business.
 */
public final class LocalPackStore {
    /** The namespace every stored pack id and bank id carries, so a local pack can never be
     *  mistaken for a catalog one. */
    public static final String NAMESPACE = "local.";

    /** How many local packs one store holds. A pack is a few kilobytes and every scan indexes all
     *  of them, so this bound protects the parse path rather than the disk. */
    public static final int MAX_PACKS = 64;

    /** The suffix a stored pack is written under, which is also how a temp file is recognised. */
    public static final String SUFFIX = ".pack.json";
    public static final String TEMPORARY = ".tmp";

    /** The strict JSON reader a caller supplies: decoded documents in, lexical limits and
     *  duplicate keys rejected, one tree out. */
    public interface Codec {
        Map<String, Object> read(InputStream input) throws IOException;
    }

    /** Why an install did not happen, or why a stored file could not be read. No pack text, ids,
     *  revisions or paths appear in the message: a failed import is reported to a person, and a
     *  log is not the place for the document that caused it. */
    public enum Code { MALFORMED, CONFLICT, DOWNGRADE, FULL, UNREADABLE }

    public static final class Failure extends IOException {
        public final Code code;

        Failure(Code code) {
            super("Local pack " + code.name().toLowerCase(Locale.ROOT));
            this.code = code;
        }
    }

    /** What an install did. NO_OP means the pack was already held, byte for byte: an import run
     *  twice changes nothing the second time. */
    public enum Outcome { NO_OP, INSTALLED, UPDATED }

    public static final class Result {
        private final Outcome outcome;
        private final Pack pack;
        private final String replacedRevision;

        Result(Outcome outcome, Pack pack, String replacedRevision) {
            this.outcome = outcome;
            this.pack = pack;
            this.replacedRevision = replacedRevision;
        }

        public Outcome outcome() { return outcome; }
        public Pack pack() { return pack; }
        /** The revision this install replaced, or null when it replaced nothing. */
        public String replacedRevision() { return replacedRevision; }
    }

    /** One stored pack: the document, the exact bytes it is stored as, and the digest that says
     *  whether an incoming import is the same pack. */
    public static final class Pack {
        private final PackDocument document;
        private final byte[] canonical;
        private final String digest;

        Pack(PackDocument document, byte[] canonical, String digest) {
            this.document = document;
            this.canonical = canonical;
            this.digest = digest;
        }

        public PackDocument document() { return document; }
        public String packId() { return document.id(); }
        public String revision() { return document.revision(); }
        public String digest() { return digest; }
        /** The bytes on disk. A copy, so a caller cannot change what the store believes it wrote. */
        public byte[] canonical() { return canonical.clone(); }
        /** Whether this pack is a local copy of a catalog pack, named by that upstream id. */
        public boolean forks(String bundledPackId) {
            return bundledPackId != null && document.id().equals(NAMESPACE + bundledPackId);
        }
    }

    /** What the directory holds right now. The two lists are disjoint and together cover every
     *  pack file present, so a caller composes from one of them and reports the other. */
    public static final class Snapshot {
        private final List<Pack> packs;
        private final List<String> unreadable;

        Snapshot(List<Pack> packs, List<String> unreadable) {
            this.packs = Collections.unmodifiableList(packs);
            this.unreadable = Collections.unmodifiableList(unreadable);
        }

        public List<Pack> packs() { return packs; }
        /** File names this store could not read back. A file name is a pack id and nothing else. */
        public List<String> unreadable() { return unreadable; }
        public Pack find(String packId) {
            for (Pack pack : packs) if (pack.packId().equals(packId)) return pack;
            return null;
        }
        public int size() { return packs.size(); }
    }

    private final Path directory;
    private final Codec codec;

    private LocalPackStore(Path directory, Codec codec) {
        this.directory = directory;
        this.codec = codec;
    }

    /** Opens a store over {@code directory}, creating it and sweeping temp files a crash left
     *  behind. A temp file is a write that did not finish, so it is discarded rather than offered
     *  as a pack. */
    public static LocalPackStore open(Path directory, Codec codec) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Path canonical = directory.toAbsolutePath().normalize();
        Files.createDirectories(canonical);
        LocalPackStore store = new LocalPackStore(canonical, codec);
        store.sweep();
        return store;
    }

    public Path directory() { return directory; }

    /** The path a pack is stored at, a function of its id alone. An id that is not one of this
     *  namespace's own pack ids is refused rather than resolved: a caller passing a file name, a
     *  relative path or {@code ..} would otherwise be able to name a file the store did not write,
     *  and deletion is the one operation that takes a bare id. */
    public Path pathOf(String packId) {
        if (!isStoredId(packId))
            throw new IllegalArgumentException("Not a local pack id: " + packId);
        return directory.resolve(packId + SUFFIX);
    }

    /** Whether a string is one of this store's pack ids: the namespace, then lowercase dotted
     *  segments. That alphabet cannot contain a separator, a dot-dot or a case-fold collision, so
     *  an accepted id names exactly one file inside the directory. */
    static boolean isStoredId(String id) {
        if (id == null || id.length() <= NAMESPACE.length() || !id.startsWith(NAMESPACE))
            return false;
        int start = NAMESPACE.length();
        while (start < id.length()) {
            int dot = id.indexOf('.', start);
            int end = dot < 0 ? id.length() : dot;
            if (end == start) return false;
            for (int i = start; i < end; i++) {
                char c = id.charAt(i);
                boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
                if (!allowed) return false;
            }
            if (dot < 0) break;
            start = dot + 1;
        }
        return true;
    }

    /** Validates a document and stages it for installation: namespaced, LOCAL, canonical and
     *  digested. Staging touches nothing, so a previewed activation can show exactly what would be
     *  stored before any of it is. */
    public Pack stage(InputStream document) throws Failure {
        Map<String, Object> decoded;
        try {
            decoded = codec.read(document);
        } catch (IOException | RuntimeException e) {
            throw new Failure(Code.MALFORMED);
        }
        PackDocument validated;
        try {
            validated = PackDocument.decode(decoded);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new Failure(Code.MALFORMED);
        }
        // The reader's id alphabet is wider than the one a stored file may be named from, and the
        // published schema permits the difference. An id this store could not spell is refused here,
        // as a malformed pack, rather than previewing as an install and then throwing an unchecked
        // IllegalArgumentException from pathOf once the user has already confirmed it.
        if (!isStoredId(localPackId(validated.id())))
            throw new Failure(Code.MALFORMED);
        PackDocument local = validated.withIdentity(localPackId(validated.id()),
                localBankId(validated.bank().id()), PackDocument.Bank.Provenance.LOCAL);
        byte[] bytes;
        try {
            bytes = PackWriter.write(local).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A document the reader accepts but the writer cannot express is malformed for our
            // purposes: this store only ever holds canonical text.
            throw new Failure(Code.MALFORMED);
        }
        return new Pack(local, bytes, digest(bytes));
    }

    /** Installs a staged pack under the identity table: an identical pack is a no-op, a higher
     *  revision replaces the held one, and anything that would quietly change what an id means is
     *  refused instead of applied. */
    public Result install(Pack staged) throws IOException {
        return install(staged, false);
    }

    /** What installing this pack would do, asked without doing it: the outcome it would report, or
     *  the refusal it would raise, against the directory as it is right now.
     *
     *  <p>This is what a screen shows before it asks, so the words on the confirmation and the
     *  decision that follows it come from one implementation of the table rather than from a second
     *  reading of it in a UI that could drift from the store. */
    public Outcome preview(Pack staged, boolean revert) throws IOException {
        return decide(staged, revert).outcome;
    }

    /** As {@link #install(Pack)}, but a lower revision is taken as the explicit revert the import
     *  table asks for. Reverting is a person's decision, so the caller has to say so. */
    public synchronized Result install(Pack staged, boolean revert) throws IOException {
        Decision decision = decide(staged, revert);
        if (decision.outcome == Outcome.NO_OP) return new Result(Outcome.NO_OP, decision.held, null);
        write(staged);
        return new Result(decision.outcome, staged, decision.replaced);
    }

    /** The identity table itself, with no writing: what this pack is against the one already held. */
    private Decision decide(Pack staged, boolean revert) throws IOException {
        Snapshot snapshot = snapshot();
        Pack held = snapshot.find(staged.packId());
        if (held == null) {
            if (snapshot.size() >= MAX_PACKS) throw new Failure(Code.FULL);
            return new Decision(Outcome.INSTALLED, null, null);
        }
        if (held.digest().equals(staged.digest())) return new Decision(Outcome.NO_OP, held, null);
        if (held.revision().equals(staged.revision())) throw new Failure(Code.CONFLICT);
        int order = compareRevisions(staged.revision(), held.revision());
        // Two revisions neither of which is higher are not an update; they are two meanings for one
        // id, which is exactly what a new revision or a new id is for.
        if (order == 0) throw new Failure(Code.CONFLICT);
        if (order < 0 && !revert) throw new Failure(Code.DOWNGRADE);
        return new Decision(Outcome.UPDATED, held, held.revision());
    }

    /** What the table decided, before anything is written. */
    private static final class Decision {
        final Outcome outcome;
        final Pack held;
        final String replaced;

        Decision(Outcome outcome, Pack held, String replaced) {
            this.outcome = outcome;
            this.held = held;
            this.replaced = replaced;
        }
    }

    /** Removes a pack, reporting whether one was there. Removing an absent pack is not an error:
     *  a screen offering "delete" should not fail on a second tap. */
    public synchronized boolean remove(String packId) throws IOException {
        if (!isStoredId(packId)) return false;
        return Files.deleteIfExists(pathOf(packId));
    }

    /** Removes every local pack, for a user asking the app to forget the rules they brought. */
    public synchronized void clear() throws IOException {
        for (String packId : names()) Files.deleteIfExists(pathOf(packId));
    }

    /** One read pass over the directory: the packs that decode, sorted by id, and the file names
     *  that did not. */
    public synchronized Snapshot snapshot() throws IOException {
        List<Pack> packs = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String packId : names()) {
            try {
                packs.add(read(packId));
            } catch (Failure e) {
                unreadable.add(packId);
            }
        }
        return new Snapshot(packs, unreadable);
    }

    /** Only stems this store could have written. A file named by hand, or left by another version,
     *  is not something pathOf may be asked about, and letting that refusal escape as an unchecked
     *  throw would let one stray file cost every pack rather than itself. */
    private List<String> names() throws IOException {
        List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.endsWith(SUFFIX) && !name.endsWith(TEMPORARY)
                        && isStoredId(name.substring(0, name.length() - SUFFIX.length())))
                    found.add(name.substring(0, name.length() - SUFFIX.length()));
            }
        }
        Collections.sort(found);
        return found;
    }

    private Pack read(String packId) throws IOException {
        Pack pack;
        try (InputStream input = Files.newInputStream(pathOf(packId))) {
            pack = stage(input);
        } catch (IOException e) {
            throw new Failure(Code.UNREADABLE);
        }
        // A stored pack's own name is its id. A file that says otherwise is not the pack this
        // store believes it holds, and honouring one of the two would let one copy stand in for
        // another.
        if (!pack.packId().equals(packId)) throw new Failure(Code.UNREADABLE);
        return pack;
    }

    /** Writes a pack so that a crash leaves either the previous pack or this one: a temp file in
     *  the same directory, forced to disk, then renamed over the old name, then the directory
     *  itself forced so the rename survives a power loss. */
    private void write(Pack pack) throws IOException {
        Path target = pathOf(pack.packId());
        Path temporary = directory.resolve(pack.packId() + SUFFIX + TEMPORARY);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                InputStream bytes = new ByteArrayInputStream(pack.canonical)) {
            copy(bytes, channel);
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Only reachable on a filesystem without rename. The replace is still ordered, so a
            // crash can lose the pack but cannot leave half of one in its place.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        syncDirectory();
    }

    private static void copy(InputStream source, FileChannel target) throws IOException {
        byte[] buffer = new byte[8192];
        for (int read = source.read(buffer); read > 0; read = source.read(buffer))
            target.write(ByteBuffer.wrap(buffer, 0, read));
    }

    private void syncDirectory() {
        try (FileChannel handle = FileChannel.open(directory, StandardOpenOption.READ)) {
            handle.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Directory syncing is refused on some filesystems. The rename above is still atomic,
            // so this only weakens durability against power loss, never correctness.
        }
    }

    private void sweep() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + TEMPORARY)) {
            for (Path entry : entries) Files.deleteIfExists(entry);
        }
    }

    /** The local id for an imported pack: a pack already in the namespace keeps its own name, and
     *  anything else is prefixed so that the upstream reference stays readable in the id. */
    static String localPackId(String packId) {
        return packId.startsWith(NAMESPACE) ? packId : NAMESPACE + packId;
    }

    static String localBankId(String bankId) {
        return bankId.startsWith(NAMESPACE) ? bankId : NAMESPACE + bankId;
    }

    /** Total order over revisions, so "higher" means one thing everywhere. A revision names a
     *  prefix and a number ({@code ir-1}, {@code r1}, {@code draft-1}), which is how every shipped
     *  pack spells it; two that share a prefix compare by that number, and any other pair compares
     *  as text. */
    static int compareRevisions(String left, String right) {
        Integer leftNumber = trailingNumber(left);
        Integer rightNumber = trailingNumber(right);
        if (leftNumber != null && rightNumber != null
                && samePrefix(left, leftNumber, right, rightNumber))
            return Integer.compare(leftNumber, rightNumber);
        return left.compareTo(right);
    }

    /** Whether both revisions name the same prefix before their trailing number, which is what
     *  makes those two numbers comparable at all. */
    private static boolean samePrefix(String left, int leftNumber, String right, int rightNumber) {
        String leftPrefix = left.substring(0, left.length() - String.valueOf(leftNumber).length());
        String rightPrefix = right.substring(0, right.length() - String.valueOf(rightNumber).length());
        return leftPrefix.equals(rightPrefix);
    }

    /** The trailing decimal of a revision, or null when it does not end in one. */
    private static Integer trailingNumber(String revision) {
        int start = revision.length();
        while (start > 0 && Character.isDigit(revision.charAt(start - 1))) start--;
        if (start == revision.length()) return null;
        try {
            return Integer.valueOf(revision.substring(start));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String digest(byte[] canonical) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to identify a pack", e);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : sha.digest(canonical)) out.append(Character.forDigit((b >> 4) & 0xf, 16))
                .append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }
}