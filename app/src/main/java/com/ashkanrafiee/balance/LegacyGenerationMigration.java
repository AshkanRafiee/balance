package com.ashkanrafiee.balance;

import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import com.ashkanrafiee.balance.EncryptedGenerationStore.Limits;
import com.ashkanrafiee.balance.EncryptedGenerationStore.Snapshot;

/**
 * Single-process migration at a permanent canonical path; never moves an encrypted store.
 * The caller durably creates a dedicated private/no-backup parent and supplies a stable AES-256
 * key and limits. No other code/process may manipulate its files during this operation.
 * Hold the caller's data lock throughout open(), including validation and adoption. Afterwards,
 * ordinary store access must use that same caller lock and the returned store's fixed root.
 *
 * Source must be read-only: return ONE fully validated legacy snapshot (including all components),
 * or throw. A successful return explicitly asserts that legacy is still intact and authorizes
 * disposal of an unpublished PREPARING destination. This class NEVER removes legacy data.
 * An empty map is an explicit valid snapshot, not an error recovery policy.
 *
 * ADOPTED never calls Source. Double publication plus cleanup removes the empty bootstrap and
 * retains two full manifests (unchanged component files are shared, not redundant copies).
 * Its journal records the first full generation as provenance, not a restriction on later commits.
 * Corrupt adopted data propagates; absence of the journal with ANY artifacts fails closed.
 * A state-less staging interruption is recoverable only when staging is the sole artifact and
 * the complete legacy snapshot validates before that unauthenticated stage is discarded.
 * Authentication is not rollback protection against replay of the entire directory.
 */
public final class LegacyGenerationMigration {
    private static final String STATE = "migration.state", STAGE = "migration.state.stage";
    private static final String DESTINATION = "generations";
    private static final Map<String, Gate> GATES = new HashMap<>();
    private static final class Gate { boolean entered; }
    private final File parent, root;
    private final SecretKey key;
    private final Limits limits;
    private final FaultInjector faults;
    private final EncryptedGenerationStore.FaultInjector storeFaults;
    private final Gate gate;

    public interface Source { Map<String, byte[]> readValidatedSnapshot() throws IOException; }
    public enum Step { PREPARING_PARTIAL, PREPARING_SYNCED, PREPARING_RENAMED,
        PREPARING_DURABLE, DESTINATION_CREATED, DESTINATION_DURABLE,
        FIRST_COMMITTED, SECOND_COMMITTED, CLEANED,
        ADOPTED_PARTIAL, ADOPTED_SYNCED, ADOPTED_RENAMED, ADOPTED_DURABLE }
    public interface FaultInjector { void at(Step step) throws IOException; }

    public LegacyGenerationMigration(File parent, SecretKey key, Limits limits) throws IOException {
        this(parent, key, limits, step -> { }, step -> { });
    }

    public LegacyGenerationMigration(File parent, SecretKey key, Limits limits,
            FaultInjector faults, EncryptedGenerationStore.FaultInjector storeFaults)
            throws IOException {
        if (parent == null || key == null || limits == null || faults == null || storeFaults == null)
            throw failure("INVALID_ARGUMENT");
        this.parent = parent.getCanonicalFile();
        if (!this.parent.isDirectory()) throw failure("INVALID_ARGUMENT");
        // Reuse the store's key validation without reading/writing a snapshot or creating a root.
        new EncryptedGenerationStore(this.parent, key, limits);
        this.root = new File(this.parent, DESTINATION);
        this.key = key;
        this.limits = limits;
        this.faults = faults;
        this.storeFaults = storeFaults;
        synchronized (GATES) {
            gate = GATES.computeIfAbsent(this.parent.getPath(), unused -> new Gate());
        }
    }

    /** Reloads journal on EVERY invocation, including after publication/commit uncertainty. */
    public EncryptedGenerationStore open(Source source) throws IOException {
        synchronized (gate) {
            if (gate.entered) throw failure("REENTRANT");
            gate.entered = true;
            try {
                String[] entries = inventory();
                boolean hasState = Arrays.asList(entries).contains(STATE);
                boolean adoptingPreparingStage = !hasState && entries.length == 1
                        && STAGE.equals(entries[0]);
                // A stage is unauthenticated until its source has been read and fully
                // validated.  It is recoverable only as the sole artifact: in particular,
                // never infer authority for a missing journal from a destination directory.
                if (!hasState && entries.length != 0 && !adoptingPreparingStage)
                    throw failure("MISSING_STATE");
                byte[] state = hasState ? readState() : null;
                if (hasState) sync(parent); // Resolve a previous rename's uncertain directory sync.
                if (state != null && state[0] == 2) {
                    if (!root.isDirectory()) throw failure("CORRUPT");
                    EncryptedGenerationStore store = store();
                    if (store.getSnapshot().generation().isEmpty()) throw failure("CORRUPT");
                    return store;
                }
                if (source == null) throw failure("INVALID_ARGUMENT");
                Map<String, byte[]> values = validatedCopy(source.readValidatedSnapshot());
                if (adoptingPreparingStage) {
                    // Do not inspect or authenticate the old bytes.  Validation of the
                    // complete legacy snapshot is the only authorization to discard this
                    // interrupted, unauthenticated journal staging file.
                    File stage = new File(parent, STAGE);
                    if (!stage.delete()) throw failure("IO");
                    sync(parent);
                }
                if (state == null) publish(new byte[] { 1 }, Step.PREPARING_PARTIAL);
                // Only authenticated PREPARING + successful intact-source validation permits this.
                discardDestination();
                if (!root.mkdir()) throw failure("IO");
                faults.at(Step.DESTINATION_CREATED);
                sync(parent);
                faults.at(Step.DESTINATION_DURABLE);
                EncryptedGenerationStore store = store();
                Snapshot first = store.commit("", values);
                verify(values, first);
                faults.at(Step.FIRST_COMMITTED);
                Snapshot second = store.commit(first.generation(), values);
                verify(values, second);
                if (first.generation().equals(second.generation())) throw failure("CORRUPT");
                faults.at(Step.SECOND_COMMITTED);
                // cleanup authenticates both retained generations and fsyncs bootstrap removal.
                store.cleanup();
                Snapshot loaded = store.getSnapshot();
                verify(values, loaded);
                if (loaded.recovered() || !loaded.generation().equals(second.generation()))
                    throw failure("CORRUPT");
                faults.at(Step.CLEANED);
                byte[] adopted = new byte[33];
                adopted[0] = 2;
                System.arraycopy(first.generation().getBytes(StandardCharsets.US_ASCII),
                        0, adopted, 1, 32);
                publish(adopted, Step.ADOPTED_PARTIAL);
                return store;
            } catch (IOException e) {
                // In particular, COMMIT_UNCERTAIN exits without retrying mutation or publishing state.
                throw e;
            } catch (Exception e) {
                throw failure("IO");
            } finally { gate.entered = false; }
        }
    }

    private EncryptedGenerationStore store() throws IOException {
        return new EncryptedGenerationStore(root, key, limits, storeFaults);
    }

    private Map<String, byte[]> validatedCopy(Map<String, byte[]> input) throws IOException {
        if (input == null) throw failure("INVALID_SOURCE");
        if (input.size() > limits.maxComponents) throw failure("LIMIT");
        Map<String, byte[]> copy = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, byte[]> entry : input.entrySet()) {
            String name = entry.getKey();
            byte[] value = entry.getValue();
            if (name == null || !name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || value == null)
                throw failure("INVALID_SOURCE");
            if (value.length > limits.maxComponentBytes || value.length > limits.maxTotalBytes - total)
                throw failure("LIMIT");
            total += value.length;
            copy.put(name, value.clone());
        }
        return copy;
    }

    private static void verify(Map<String, byte[]> expected, Snapshot actual) throws IOException {
        Map<String, byte[]> values = actual.components();
        if (!expected.keySet().equals(values.keySet())) throw failure("CORRUPT");
        for (String name : expected.keySet())
            if (!Arrays.equals(expected.get(name), values.get(name))) throw failure("CORRUPT");
    }

    private byte[] aad() {
        return ("BalanceLegacyGenerationMigration\u0000v1\u0000state\u0000" + parent.getPath())
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] readState() throws Exception {
        File file = new File(parent, STATE);
        long size = file.length();
        if (size != 29 && size != 61) throw failure("CORRUPT");
        byte[] sealed = new byte[(int) size];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(file))) {
            in.readFully(sealed);
            if (in.read() != -1) throw failure("CORRUPT");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
        cipher.updateAAD(aad());
        byte[] state;
        try { state = cipher.doFinal(sealed, 12, sealed.length - 12); }
        catch (javax.crypto.AEADBadTagException e) { throw failure("CORRUPT"); }
        if (state.length == 1 && state[0] == 1) return state;
        if (state.length == 33 && state[0] == 2
                && new String(state, 1, 32, StandardCharsets.US_ASCII).matches("[0-9a-f]{32}"))
            return state;
        throw failure("CORRUPT");
    }

    private void publish(byte[] state, Step first) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != 12) throw failure("KEY");
        cipher.updateAAD(aad());
        byte[] body = cipher.doFinal(state);
        File stage = new File(parent, STAGE);
        try (FileOutputStream out = new FileOutputStream(stage)) {
            out.write(iv);
            faults.at(first);
            out.write(body);
            out.getFD().sync();
        }
        faults.at(Step.values()[first.ordinal() + 1]);
        Os.rename(stage.getPath(), new File(parent, STATE).getPath());
        faults.at(Step.values()[first.ordinal() + 2]);
        sync(parent);
        faults.at(Step.values()[first.ordinal() + 3]);
    }

    /** Check the entire owned namespace before any deletion; never follow even dangling links. */
    private String[] inventory() throws Exception {
        String[] entries = parent.list();
        if (entries == null) throw failure("IO");
        for (String name : entries) {
            File file = new File(parent, name);
            if (DESTINATION.equals(name)) {
                android.system.StructStat stat = Os.lstat(file.getPath());
                if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != android.os.Process.myUid())
                    throw failure("CORRUPT");
                destinationEntries();
            } else if (STATE.equals(name) || STAGE.equals(name)) regular(file);
            else throw failure("FOREIGN_ENTRY");
        }
        return entries;
    }

    private File[] destinationEntries() throws Exception {
        File[] files = root.listFiles();
        if (files == null) throw failure("IO");
        for (File file : files) {
            if (!file.getName().matches("([cm]-[0-9a-f]{32}|active|recovery)(\\.stage)?"))
                throw failure("FOREIGN_ENTRY");
            regular(file);
        }
        return files;
    }

    private static void regular(File file) throws Exception {
        android.system.StructStat stat = Os.lstat(file.getPath());
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1
                || stat.st_uid != android.os.Process.myUid()) throw failure("CORRUPT");
    }

    private void discardDestination() throws Exception {
        if (!root.exists()) return;
        File[] files = destinationEntries();
        for (File file : files) if (!file.delete()) throw failure("IO");
        if (!root.delete()) throw failure("IO");
        sync(parent);
    }

    private static void sync(File directory) throws Exception {
        FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }

    private static IOException failure(String code) { return new IOException(code); }
}
