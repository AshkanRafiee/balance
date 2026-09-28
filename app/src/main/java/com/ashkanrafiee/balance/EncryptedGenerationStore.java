package com.ashkanrafiee.balance;

import android.security.keystore.KeyInfo;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Private, no-backup, single-process generation store. The caller supplies an existing dedicated
 * directory and an AES-256 key (including an AndroidKeyStore key); this class never accesses prefs.
 * The caller must durably create that directory (sync its parent) before relying on crash recovery.
 * All instances for a canonical root share a lock. Other processes MUST NOT access that root.
 * No file lock is needed by the application's single-process coordinator; adding another process
 * requires a file-lock protocol covering reads, publication AND cleanup first.
 *
 * Components are opaque bytes. A commit replaces the entire component map, including deletions.
 * Generation/component IDs are random, stable for their lifetime, and never derived from plaintext.
 * Identical bytes at the same logical name reuse the current immutable component. Snapshots own
 * their bytes and remain usable after cleanup. AAD binds format, canonical root, kind, logical name
 * and immutable ID. Moving the directory requires an explicit export/import, not a filesystem copy.
 * Bootstrap adoption at a stable canonical path is the caller/coordinator's responsibility; never
 * rename an encrypted root to adopt it, because that would invalidate its authenticated binding.
 *
 * Files and their directory entries are synced before publication. The active-pointer rename is
 * the visibility boundary. An error after that rename returns COMMIT_UNCERTAIN: reload before
 * retrying. A successful commit includes the final directory sync. Recovery exposes only a fully
 * authenticated previous committed generation and sets recovered=true; it never fabricates empty
 * data. Recovery is not rollback protection against an attacker replaying the entire directory.
 *
 * Cleanup is explicit, validates retained generations first, and only deletes generated files not
 * referenced by active/previous. Writes never delete data to make space. Keep a stable key and limits
 * across instances. Limits reject, never truncate; defaults are capacity guards, not migration policy.
 * A LIMIT or key-provider availability failure is not treated as corruption and does not roll back.
 * Before the first baseline pointer is published, bootstrap interruption fails closed as CORRUPT;
 * disposal of such a never-used root requires an explicit caller decision. After baseline publication,
 * retry using the generation returned by getSnapshot(), including when that baseline is empty.
 */
public final class EncryptedGenerationStore {
    private static final int FORMAT = 1;
    private static final int OVERHEAD = 28; // 96-bit nonce and 128-bit GCM tag
    private static final int POINTER_LIMIT = 256;
    private static final int MAX_FORMAT_COMPONENTS = 65536;
    private static final int MAX_MANIFEST_BYTES = 128 + MAX_FORMAT_COMPONENTS * 180;
    private static final Map<String, RootState> LOCKS = new HashMap<>();
    private static final class RootState { boolean inOperation; }
    private final File root;
    private final String rootBinding;
    private final RootState lock;
    private final SecretKey key;
    private final Limits limits;
    private final FaultInjector faults;

    public enum Code { INVALID_ARGUMENT, KEY, IO, CORRUPT, LIMIT, STALE, REENTRANT,
        COMMIT_UNCERTAIN }

    /** No nested cause: provider/filesystem exceptions may contain private paths or data. */
    public static final class StoreException extends IOException {
        public final Code code;
        private StoreException(Code code) { super(code.name()); this.code = code; }
    }

    public static final class Limits {
        public final int maxComponents;
        public final int maxComponentBytes;
        public final long maxTotalBytes;
        public Limits(int maxComponents, int maxComponentBytes, long maxTotalBytes) {
            if (maxComponents < 1 || maxComponents > 65536 || maxComponentBytes < 0
                    || maxComponentBytes > Integer.MAX_VALUE - OVERHEAD
                    || maxTotalBytes < 0) throw new IllegalArgumentException("INVALID_ARGUMENT");
            this.maxComponents = maxComponents;
            this.maxComponentBytes = maxComponentBytes;
            this.maxTotalBytes = maxTotalBytes;
        }
        public static Limits defaults() { return new Limits(4096, 128 * 1024 * 1024,
                256L * 1024 * 1024); }
    }

    /** Hooks run under the root lock, must not reenter the store, and never receive payloads/paths. */
    public enum Step { COMPONENT_PARTIAL, COMPONENT_SYNCED, COMPONENT_RENAMED, COMPONENT_DURABLE,
        MANIFEST_PARTIAL, MANIFEST_SYNCED, MANIFEST_RENAMED, MANIFEST_DURABLE,
        VALIDATED, RECOVERY_PARTIAL, RECOVERY_SYNCED, RECOVERY_RENAMED, RECOVERY_DURABLE,
        ACTIVE_PARTIAL, ACTIVE_SYNCED, ACTIVE_RENAMED, ACTIVE_DURABLE,
        INITIAL_MANIFEST_PARTIAL, INITIAL_MANIFEST_SYNCED, INITIAL_MANIFEST_RENAMED,
        INITIAL_MANIFEST_DURABLE, INITIAL_RECOVERY_PARTIAL, INITIAL_RECOVERY_SYNCED,
        INITIAL_RECOVERY_RENAMED, INITIAL_RECOVERY_DURABLE, INITIAL_ACTIVE_PARTIAL,
        INITIAL_ACTIVE_SYNCED, INITIAL_ACTIVE_RENAMED, INITIAL_ACTIVE_DURABLE }

    public interface FaultInjector { void at(Step step) throws IOException; }

    public static final class Snapshot {
        private final String generation;
        private final boolean recovered;
        private final Map<String, byte[]> values;
        private final Map<String, String> ids;
        private Snapshot(String generation, boolean recovered, Map<String, byte[]> values,
                Map<String, String> ids) {
            this.generation = generation;
            this.recovered = recovered;
            this.values = copy(values);
            this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
        }
        /** Empty string denotes a pristine, never-committed root. */
        public String generation() { return generation; }
        public boolean recovered() { return recovered; }
        public Map<String, byte[]> components() { return copy(values); }
        public byte[] component(String name) {
            byte[] value = values.get(name);
            return value == null ? null : value.clone();
        }
        public Map<String, String> componentIds() { return ids; }
    }

    public EncryptedGenerationStore(File root, SecretKey key) throws StoreException {
        this(root, key, Limits.defaults(), step -> { });
    }

    public EncryptedGenerationStore(File root, SecretKey key, Limits limits) throws StoreException {
        this(root, key, limits, step -> { });
    }

    public EncryptedGenerationStore(File root, SecretKey key, Limits limits, FaultInjector faults)
            throws StoreException {
        if (root == null || key == null || limits == null || faults == null)
            throw error(Code.INVALID_ARGUMENT);
        try {
            this.root = root.getCanonicalFile();
            if (!this.root.isDirectory()) throw error(Code.INVALID_ARGUMENT);
            rootBinding = this.root.getPath();
            synchronized (LOCKS) { lock = LOCKS.computeIfAbsent(rootBinding, unused -> new RootState()); }
            if (!"AES".equalsIgnoreCase(key.getAlgorithm())) throw error(Code.KEY);
            byte[] encoded = key.getEncoded();
            int bits;
            if (encoded != null) {
                bits = encoded.length * 8;
            } else {
                KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
                        .getKeySpec(key, KeyInfo.class);
                bits = info.getKeySize();
            }
            if (bits != 256) throw error(Code.KEY);
            this.key = key;
            this.limits = limits;
            this.faults = faults;
        } catch (StoreException e) { throw e;
        } catch (GeneralSecurityException e) { throw error(Code.KEY);
        } catch (Exception e) { throw error(Code.IO); }
    }

    public Snapshot getSnapshot() throws StoreException {
        synchronized (lock) {
            enter();
            try { return select().snapshot; }
            catch (StoreException e) { throw e; }
            catch (Exception e) { throw error(Code.IO); }
            finally { lock.inOperation = false; }
        }
    }

    /** Compare-and-swap against the snapshot's generation; even an identical commit gets a new ID. */
    public Snapshot commit(String expectedGeneration, Map<String, byte[]> components)
            throws StoreException {
        synchronized (lock) {
            enter();
            boolean[] activated = { false };
            try {
                if (expectedGeneration == null) throw error(Code.INVALID_ARGUMENT);
                Selection old = select();
                if (!expectedGeneration.equals(old.snapshot.generation())) throw error(Code.STALE);
                Map<String, byte[]> next = checkedCopy(components);
                // Establish a durable empty commit on first use. An interrupted first publication
                // can then recover this real empty baseline, rather than infer emptiness from damage.
                if (old.snapshot.generation().isEmpty()) old = initialize();
                Map<String, String> ids = new TreeMap<>();
                for (Map.Entry<String, byte[]> entry : next.entrySet()) {
                    String name = entry.getKey();
                    String id = old.snapshot.ids.get(name);
                    if (id == null || !Arrays.equals(entry.getValue(), old.snapshot.values.get(name))) {
                        id = id();
                        publish("c-" + id, seal(entry.getValue(), "component", name, id),
                                Step.COMPONENT_PARTIAL, activated);
                    }
                    ids.put(name, id);
                }
                String generation = id();
                byte[] manifest = manifest(generation, old.snapshot.generation(), next, ids);
                publish("m-" + generation, seal(manifest, "manifest", "", generation),
                        Step.MANIFEST_PARTIAL, activated);
                Snapshot validated = load(generation, false);
                faults.at(Step.VALIDATED);
                // Recovery always names a committed state, never the candidate. It is made durable
                // before replacing active, including when the previous active was itself damaged.
                publish("recovery", pointer(old.snapshot.generation(), ""),
                        Step.RECOVERY_PARTIAL, activated);
                publish("active", pointer(generation, old.snapshot.generation()),
                        Step.ACTIVE_PARTIAL, activated);
                return validated;
            } catch (Exception e) {
                if (activated[0]) throw error(Code.COMMIT_UNCERTAIN);
                if (e instanceof StoreException) throw (StoreException) e;
                throw error(Code.IO);
            } finally { lock.inOperation = false; }
        }
    }

    /** Retains active and its complete predecessor; after recovery retains the recovered state. */
    public void cleanup() throws StoreException {
        synchronized (lock) {
            enter();
            try {
                Selection selected = select();
                Set<String> keep = new HashSet<>();
                retain(keep, selected.snapshot);
                if (!selected.previous.isEmpty()) retain(keep, load(selected.previous, false));
                File[] files = root.listFiles();
                if (files == null) throw error(Code.IO);
                for (File file : files) {
                    String name = file.getName();
                    if (generated(name) && !keep.contains(name)) {
                        file(name); // Reject symlink traversal before deleting anything at this name.
                        if (!file.delete()) throw error(Code.IO);
                    }
                }
                syncDirectory();
            } catch (StoreException e) { throw e;
            } catch (Exception e) { throw error(Code.IO);
            } finally { lock.inOperation = false; }
        }
    }

    private void enter() throws StoreException {
        if (lock.inOperation) throw error(Code.REENTRANT);
        lock.inOperation = true;
    }

    private Selection initialize() throws Exception {
        String generation = id();
        Map<String, byte[]> empty = Collections.emptyMap();
        byte[] data = manifest(generation, "", empty, Collections.emptyMap());
        // Before the first durable recovery pointer there is no committed baseline. An interruption
        // there fails closed on reopen, rather than guessing that orphan files mean empty data.
        // This bootstrap failure requires caller-directed disposal of this never-used root.
        boolean[] baselinePublished = { false };
        publish("m-" + generation, seal(data, "manifest", "", generation),
                Step.INITIAL_MANIFEST_PARTIAL, baselinePublished);
        publish("recovery", pointer(generation, ""),
                Step.INITIAL_RECOVERY_PARTIAL, baselinePublished);
        publish("active", pointer(generation, ""),
                Step.INITIAL_ACTIVE_PARTIAL, baselinePublished);
        return new Selection(load(generation, false), "");
    }

    private Selection select() throws Exception {
        // Directory-entry names do not follow links, including dangling pointer symlinks. Validate
        // each pointer only inside its own recovery branch: a bad recovery link cannot block active.
        String[] entries = root.list();
        if (entries == null) throw error(Code.IO);
        boolean active = Arrays.asList(entries).contains("active");
        boolean recovery = Arrays.asList(entries).contains("recovery");
        if (!active && !recovery) {
            // Only a genuinely pristine root is empty. Orphaned encrypted files are not evidence
            // that the user had no data, and must never be silently discarded here.
            if (entries.length != 0) throw error(Code.CORRUPT);
            return new Selection(new Snapshot("", false, Collections.emptyMap(),
                    Collections.emptyMap()), "");
        }
        String[] verifiedActive = null;
        try {
            String[] p = readPointer("active");
            verifiedActive = p;
            Snapshot snapshot = load(p[0], false);
            if (!manifestPrevious(p[0]).equals(p[1])) throw error(Code.CORRUPT);
            return new Selection(snapshot, p[1]);
        } catch (java.io.EOFException | java.io.UTFDataFormatException e) {
            // Authenticated but malformed binary input is corruption, not an empty store.
        } catch (StoreException e) {
            if (e.code != Code.CORRUPT) throw e;
        }
        // Prefer the predecessor attested by the authenticated active pointer. A failed subsequent
        // commit may have advanced recovery to the (now corrupt) current generation already. Keep
        // this pointer usable even if cleanup has removed its damaged current manifest/components.
        // No orphan manifests or predecessor chains are searched. LIMIT/KEY/IO never cause rollback.
        if (verifiedActive != null && !verifiedActive[1].isEmpty()) {
            try {
                return new Selection(load(verifiedActive[1], true), "");
            } catch (java.io.EOFException | java.io.UTFDataFormatException e) {
                // Try the independently authenticated recovery pointer next.
            } catch (StoreException e) {
                if (e.code != Code.CORRUPT) throw e;
            }
        }
        try {
            String[] p = readPointer("recovery");
            if (!p[1].isEmpty()) throw error(Code.CORRUPT);
            return new Selection(load(p[0], true), "");
        } catch (java.io.EOFException | java.io.UTFDataFormatException e) {
            throw error(Code.CORRUPT);
        }
    }

    private static final class Selection {
        final Snapshot snapshot;
        final String previous;
        Selection(Snapshot snapshot, String previous) {
            this.snapshot = snapshot;
            this.previous = previous;
        }
    }

    private Snapshot load(String generation, boolean recovered) throws Exception {
        DataInputStream in = manifestInput(generation);
        readId(in, true); // predecessor; only current components are required to expose this snapshot
        int count = in.readInt();
        if (count < 0 || count > MAX_FORMAT_COMPONENTS) throw error(Code.CORRUPT);
        if (count > limits.maxComponents) throw error(Code.LIMIT);
        Map<String, byte[]> values = new TreeMap<>();
        Map<String, String> ids = new TreeMap<>();
        long total = 0;
        String last = "";
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            if (!validName(name) || name.compareTo(last) <= 0) throw error(Code.CORRUPT);
            last = name;
            String component = readId(in, false);
            int length = in.readInt();
            if (length < 0) throw error(Code.CORRUPT);
            total = checkedTotal(total, length);
            byte[] bytes = open(read("c-" + component, (long) length + OVERHEAD, Code.CORRUPT),
                    "component", name, component);
            if (bytes.length != length) throw error(Code.CORRUPT);
            values.put(name, bytes);
            ids.put(name, component);
        }
        if (in.read() != -1) throw error(Code.CORRUPT);
        return new Snapshot(generation, recovered, values, ids);
    }

    private DataInputStream manifestInput(String generation) throws Exception {
        // The unauthenticated envelope is bounded by the format, not caller policy. Only after
        // authentication may the manifest's count produce LIMIT rather than corruption recovery.
        byte[] bytes = open(read("m-" + generation, (long) MAX_MANIFEST_BYTES + OVERHEAD,
                Code.CORRUPT),
                "manifest", "", generation);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != FORMAT || !readId(in, false).equals(generation))
            throw error(Code.CORRUPT);
        return in;
    }

    private String manifestPrevious(String generation) throws Exception {
        return readId(manifestInput(generation), true);
    }

    private byte[] manifest(String generation, String previous, Map<String, byte[]> values,
            Map<String, String> ids) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(FORMAT);
        out.writeUTF(generation);
        out.writeUTF(previous);
        out.writeInt(values.size());
        for (Map.Entry<String, byte[]> entry : values.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeUTF(ids.get(entry.getKey()));
            out.writeInt(entry.getValue().length);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private byte[] pointer(String current, String previous) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(FORMAT);
        out.writeUTF(current);
        out.writeUTF(previous);
        out.flush();
        // Same envelope for active/recovery: recovery contains an authenticated committed pointer.
        return seal(bytes.toByteArray(), "pointer", "", "");
    }

    private String[] readPointer(String name) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                open(read(name, POINTER_LIMIT, Code.CORRUPT), "pointer", "", "")));
        if (in.readInt() != FORMAT) throw error(Code.CORRUPT);
        String current = readId(in, false);
        String previous = readId(in, true);
        if (in.read() != -1 || current.equals(previous)) throw error(Code.CORRUPT);
        return new String[] { current, previous };
    }

    private Map<String, byte[]> checkedCopy(Map<String, byte[]> input) throws StoreException {
        if (input == null) throw error(Code.INVALID_ARGUMENT);
        if (input.size() > limits.maxComponents) throw error(Code.LIMIT);
        long total = 0;
        Map<String, byte[]> result = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : input.entrySet()) {
            if (!validName(entry.getKey()) || entry.getValue() == null)
                throw error(Code.INVALID_ARGUMENT);
            total = checkedTotal(total, entry.getValue().length);
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return result;
    }

    private long checkedTotal(long total, int length) throws StoreException {
        if (length > limits.maxComponentBytes || total > limits.maxTotalBytes
                || length > limits.maxTotalBytes - total) throw error(Code.LIMIT);
        return total + length;
    }

    private byte[] aad(String kind, String name, String id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("BalanceEncryptedGenerationStore");
        out.writeInt(FORMAT);
        byte[] binding = rootBinding.getBytes(StandardCharsets.UTF_8);
        out.writeInt(binding.length);
        out.write(binding);
        out.writeUTF(kind);
        out.writeUTF(name);
        out.writeUTF(id);
        out.flush();
        return bytes.toByteArray();
    }

    private byte[] seal(byte[] plain, String kind, String name, String id) throws Exception {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Provider-generated fresh IV also supports randomized-encryption-required Keystore keys.
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) throw error(Code.KEY);
            cipher.updateAAD(aad(kind, name, id));
            byte[] body = cipher.doFinal(plain);
            byte[] sealed = Arrays.copyOf(iv, Math.addExact(iv.length, body.length));
            System.arraycopy(body, 0, sealed, iv.length, body.length);
            return sealed;
        } catch (GeneralSecurityException e) { throw error(Code.KEY); }
    }

    private byte[] open(byte[] bytes, String kind, String name, String id) throws Exception {
        if (bytes.length < OVERHEAD) throw error(Code.CORRUPT);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            cipher.updateAAD(aad(kind, name, id));
            return cipher.doFinal(bytes, 12, bytes.length - 12);
        } catch (AEADBadTagException e) { throw error(Code.CORRUPT);
        } catch (GeneralSecurityException e) { throw error(Code.KEY); }
    }

    private byte[] read(String name, long bound, Code oversized) throws Exception {
        File target = file(name);
        if (!target.exists()) throw error(Code.CORRUPT);
        long length = target.length();
        if (!target.isFile() || length < OVERHEAD) throw error(Code.CORRUPT);
        if (length > bound || length > Integer.MAX_VALUE) throw error(oversized);
        byte[] bytes = new byte[(int) length];
        try (DataInputStream in = new DataInputStream(new FileInputStream(target))) {
            in.readFully(bytes);
            if (in.read() != -1) throw error(Code.CORRUPT);
        } catch (java.io.EOFException e) { throw error(Code.CORRUPT); }
        return bytes;
    }

    private void publish(String name, byte[] bytes, Step first, boolean[] activated)
            throws Exception {
        // Even an astronomically unlikely random-ID collision must not overwrite immutable data.
        if ((name.startsWith("c-") || name.startsWith("m-")) && file(name).exists())
            throw error(Code.IO);
        File stage = file(name + ".stage");
        try (FileOutputStream out = new FileOutputStream(stage)) {
            int half = bytes.length / 2;
            out.write(bytes, 0, half);
            faults.at(first);
            out.write(bytes, half, bytes.length - half);
            out.getFD().sync();
        }
        faults.at(Step.values()[first.ordinal() + 1]);
        Os.rename(stage.getPath(), file(name).getPath());
        if ("active".equals(name)) activated[0] = true;
        faults.at(Step.values()[first.ordinal() + 2]);
        syncDirectory();
        faults.at(Step.values()[first.ordinal() + 3]);
    }

    private void syncDirectory() throws Exception {
        // Android's public OsConstants has no O_DIRECTORY. Root is a verified private directory.
        if (!root.isDirectory()) throw error(Code.IO);
        FileDescriptor fd = Os.open(root.getPath(), OsConstants.O_RDONLY, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }

    private File file(String name) throws IOException {
        if (!("active".equals(name) || "recovery".equals(name) || generated(name)))
            throw error(Code.CORRUPT);
        File file = new File(root, name);
        if (!file.getCanonicalFile().equals(file)) throw error(Code.CORRUPT);
        return file;
    }

    private static boolean generated(String name) {
        return name.matches("[cm]-[0-9a-f]{32}(\\.stage)?")
                || "active.stage".equals(name) || "recovery.stage".equals(name);
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    }

    private static String readId(DataInputStream in, boolean optional) throws IOException {
        String id = in.readUTF();
        if (!(optional && id.isEmpty()) && !id.matches("[0-9a-f]{32}"))
            throw error(Code.CORRUPT);
        return id;
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static StoreException error(Code code) { return new StoreException(code); }
    private static Map<String, byte[]> copy(Map<String, byte[]> values) {
        Map<String, byte[]> copy = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : values.entrySet())
            copy.put(entry.getKey(), entry.getValue().clone());
        return copy;
    }
    private static void retain(Set<String> keep, Snapshot snapshot) {
        if (!snapshot.generation.isEmpty()) keep.add("m-" + snapshot.generation);
        for (String id : snapshot.ids.values()) keep.add("c-" + id);
    }
}
