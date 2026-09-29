package com.ashkanrafiee.balance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.JsonWriter;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Opt-in Phase 1 storage experiment, not a production repository or a scan benchmark.
 * All inputs are synthetic; only a unique target-app cache directory is used.
 * The in-memory key is never persisted. No preferences, Keystore or SMS provider is opened.
 *
 * Generation candidate: immutable encrypted records plus an encrypted note/records reference,
 * selected by AtomicFile. A note edit reuses the records file. Whole-blob candidate: the same
 * records and note encrypted together, Base64-wrapped in a minimal preferences-like XML file.
 * This models serialization/rewrite cost, not SharedPreferences implementation/locking overhead.
 * Read/decrypt metrics cover changed blobs only (a note-only read is not a full ledger load).
 *
 * Interruption cases close streams and reopen fresh AtomicFile objects at explicit boundaries;
 * they do not kill a process or prove a production crash/power-loss/rollback protocol.
 */
@RunWith(AndroidJUnit4.class)
public class ParserStorageExperimentTest {
    private static final String TAG = "BalanceStorageExperiment";
    private static final long DISK_LIMIT = 64L * 1024 * 1024;
    private static final int BLOB_LIMIT = 12 * 1024 * 1024;
    private static final long BUDGET_MS = 100_000;
    private static final String XML_START = "<map><string name=\"synthetic\">";
    private static final String XML_END = "</string></map>";

    @Test public void encryptedStorageExperiment() throws Exception {
        assumeTrue("Enable with -e storageExperiment true", "true".equals(
                InstrumentationRegistry.getArguments().getString("storageExperiment")));
        long started = SystemClock.elapsedRealtime();
        File root = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "parser-storage-experiment-" + UUID.randomUUID());
        assertTrue("Create isolated experiment directory", root.mkdir());
        int completed = 0;
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey key = generator.generateKey();
            interruptionChecks(root, key);
            emit("recovery simulated_checkpoints=6 corrupt_candidate_rejected=true"
                    + " result=passed power_loss_proven=false");
            outer:
            for (int count : new int[]{1_000, 10_000, 50_000}) {
                for (int sample = 1; sample <= 5; sample++) {
                    // Cooperative budget, not a timing assertion. Slow I/O cannot be preempted safely.
                    if (SystemClock.elapsedRealtime() - started >= BUDGET_MS) break outer;
                    File dir = new File(root, "sample");
                    assertTrue(dir.mkdir());
                    try {
                        File generation = new File(dir, "generation");
                        File blob = new File(dir, "blob");
                        assertTrue(generation.mkdir());
                        assertTrue(blob.mkdir());
                        // Alternate ordering to reduce systematic cache/JIT order bias.
                        if ((sample & 1) == 1) {
                            generationSample(root, generation, key, count, sample);
                            blobSample(root, blob, key, count, sample);
                        } else {
                            blobSample(root, blob, key, count, sample);
                            generationSample(root, generation, key, count, sample);
                        }
                        completed++;
                    } finally {
                        deleteTree(dir);
                    }
                }
            }
            emit("summary completed_size_samples=" + completed + " requested_size_samples=15"
                    + " complete=" + (completed == 15) + " elapsed_ms="
                    + (SystemClock.elapsedRealtime() - started)
                    + " disk_limit_bytes=" + DISK_LIMIT + " read_scope=changed_blobs");
        } finally {
            deleteTree(root);
        }
    }

    private static void generationSample(File root, File dir, SecretKey key, int count,
            int sample) throws Exception {
        Metrics full = new Metrics("generation_full", count, sample);
        long t = now();
        byte[] records = records(count, null);
        byte[] note = note(0);
        full.serialize = now() - t;
        File data = new File(dir, "records.enc");
        File first = new File(dir, "note0.enc");
        roundTrip(root, data, records, key, false, full);
        roundTrip(root, first, note, key, false, full);
        t = now();
        setPointer(dir, first.getName());
        full.publish += now() - t;
        full.written += first.getName().getBytes(StandardCharsets.UTF_8).length;
        full.peak = Math.max(full.peak, diskBytes(dir));
        assertEquals("note0.enc", selected(dir));
        full.report();

        // No record serialization/encryption/write in this incremental candidate.
        Metrics reuse = new Metrics("generation_note_reuse", count, sample);
        t = now();
        byte[] changed = note(1);
        reuse.serialize = now() - t;
        File second = new File(dir, "note1.enc");
        roundTrip(root, second, changed, key, false, reuse);
        t = now();
        setPointer(dir, second.getName());
        reuse.publish += now() - t;
        reuse.written += second.getName().getBytes(StandardCharsets.UTF_8).length;
        reuse.reused = data.length();
        reuse.peak = Math.max(reuse.peak, diskBytes(dir));
        assertEquals("note1.enc", selected(dir));
        // Outside the timing: verify the referenced immutable records still decrypt exactly.
        assertArrayEquals(records, decrypt(read(data), key));
        reuse.report();
    }

    private static void blobSample(File root, File dir, SecretKey key, int count,
            int sample) throws Exception {
        for (int revision = 0; revision <= 1; revision++) {
            Metrics m = new Metrics(revision == 0 ? "blob_full" : "blob_note_rewrite",
                    count, sample);
            long t = now();
            byte[] plain = records(count, revision);
            m.serialize = now() - t;
            roundTrip(root, new File(dir, "whole.xml"), plain, key, true, m);
            m.report();
        }
    }

    private static void roundTrip(File root, File file, byte[] plain, SecretKey key,
            boolean xml, Metrics m) throws Exception {
        long t = now();
        byte[] encrypted = encrypt(plain, key);
        m.encrypt += now() - t;
        t = now();
        byte[] disk = xml ? (XML_START + Base64.encodeToString(encrypted, Base64.NO_WRAP)
                + XML_END).getBytes(StandardCharsets.UTF_8) : encrypted;
        m.envelope += now() - t;
        m.plain += plain.length;
        m.cipher += encrypted.length;
        m.written += disk.length;
        // The old target remains during staging; account for both before creating the stage.
        assertTrue("Bound experiment temporary disk", diskBytes(root) + disk.length + 4096 <= DISK_LIMIT);
        // Compare each candidate's own files; the other candidate is only in the safety cap.
        long peak = diskBytes(file.getParentFile()) + disk.length;
        m.peak = Math.max(m.peak, peak + 4096); // allowance for pointer/AtomicFile sidecars
        t = now();
        durablePublish(file, disk);
        m.publish += now() - t;
        t = now();
        byte[] loaded = read(file);
        m.read += now() - t;
        m.readBytes += loaded.length;
        t = now();
        if (xml) {
            String text = new String(loaded, StandardCharsets.UTF_8);
            assertTrue(text.startsWith(XML_START) && text.endsWith(XML_END));
            loaded = Base64.decode(text.substring(XML_START.length(),
                    text.length() - XML_END.length()), Base64.NO_WRAP);
        }
        m.unwrap += now() - t;
        t = now();
        byte[] recovered = decrypt(loaded, key);
        m.decrypt += now() - t;
        assertArrayEquals(plain, recovered);
    }

    private static byte[] records(int count, Integer noteRevision) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(count * 160);
        try (JsonWriter json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(bytes,
                StandardCharsets.UTF_8), 8192))) {
            json.beginObject().name("records").beginArray();
            for (int i = 0; i < count; i++) {
                json.beginObject().name("source").value(i).name("output").value("movement")
                        .name("ledger").value("synthetic").name("currency").value("IRR")
                        .name("amount").value(250L).name("balance").value(1_000_000L - i)
                        .name("arrival").value(1_789_905_600_000L + i)
                        .name("revision").value(1).endObject();
            }
            json.endArray();
            if (noteRevision != null) json.name("note").value("synthetic-" + noteRevision);
            json.endObject();
        }
        assertTrue("Bound synthetic payload", bytes.size() <= BLOB_LIMIT);
        return bytes.toByteArray();
    }

    private static byte[] note(int revision) {
        return ("{\"recordsFile\":\"records.enc\",\"note\":\"synthetic-" + revision + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encrypt(byte[] plain, SecretKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key); // Provider generates a fresh nonce for every blob.
        byte[] iv = cipher.getIV();
        assertEquals(12, iv.length);
        byte[] body = cipher.doFinal(plain);
        byte[] result = Arrays.copyOf(iv, iv.length + body.length);
        System.arraycopy(body, 0, result, iv.length, body.length);
        return result;
    }

    private static byte[] decrypt(byte[] encrypted, SecretKey key)
            throws GeneralSecurityException {
        if (encrypted.length < 28) throw new GeneralSecurityException("Short experiment blob");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    private static void durablePublish(File target, byte[] data) throws Exception {
        File stage = new File(target.getParentFile(), target.getName() + ".stage");
        writeSynced(stage, data);
        Os.rename(stage.getPath(), target.getPath());
        syncDirectory(target.getParentFile());
    }

    private static void writeSynced(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.getFD().sync();
        }
    }

    private static void syncDirectory(File dir) throws Exception {
        assertTrue("Experiment sync target is a directory", dir.isDirectory());
        // O_DIRECTORY is not exposed by the public Android SDK. These paths are
        // controlled directories under our unique private cache root.
        FileDescriptor fd = Os.open(dir.getPath(), OsConstants.O_RDONLY, 0);
        try {
            Os.fsync(fd);
        } finally {
            Os.close(fd);
        }
    }

    private static void setPointer(File dir, String name) throws Exception {
        AtomicFile pointer = new AtomicFile(new File(dir, "active"));
        FileOutputStream out = pointer.startWrite();
        try {
            out.write(name.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            pointer.finishWrite(out);
        } catch (Exception e) {
            pointer.failWrite(out);
            throw e;
        }
        syncDirectory(dir);
    }

    private static String selected(File dir) throws IOException {
        return new String(new AtomicFile(new File(dir, "active")).readFully(),
                StandardCharsets.UTF_8);
    }

    private static void interruptionChecks(File root, SecretKey key) throws Exception {
        File dir = new File(root, "interruptions");
        assertTrue(dir.mkdir());
        try {
            byte[] old = note(0);
            byte[] next = note(1);
            File candidate = new File(dir, "new.enc");
            durablePublish(new File(dir, "old.enc"), encrypt(old, key));
            setPointer(dir, "old.enc");
            checkSelected(dir, key, "old.enc", old); // before staging

            byte[] encrypted = encrypt(next, key);
            File stage = new File(dir, "new.enc.stage");
            writeSynced(stage, Arrays.copyOf(encrypted, encrypted.length / 2));
            checkSelected(dir, key, "old.enc", old); // interrupted partial stage
            writeSynced(stage, encrypted);
            checkSelected(dir, key, "old.enc", old); // complete stage, not published
            Os.rename(stage.getPath(), candidate.getPath());
            syncDirectory(dir);
            checkSelected(dir, key, "old.enc", old); // durable file, pointer still old

            // Abandon an AtomicFile write without finishWrite/failWrite, then reopen it.
            AtomicFile pointer = new AtomicFile(new File(dir, "active"));
            try (FileOutputStream abandoned = pointer.startWrite()) {
                abandoned.write("new.enc".getBytes(StandardCharsets.UTF_8));
                abandoned.getFD().sync();
            }
            checkSelected(dir, key, "old.enc", old); // interrupted pointer transaction
            validateAndActivate(dir, candidate, key, next);
            checkSelected(dir, key, "new.enc", next); // committed pointer

            setPointer(dir, "old.enc");
            encrypted[encrypted.length - 1] ^= 1;
            durablePublish(candidate, encrypted);
            try {
                validateAndActivate(dir, candidate, key, next);
                fail("Corrupt candidate must not activate");
            } catch (GeneralSecurityException expected) {
                // Authentication failure must occur before touching the active pointer.
            }
            checkSelected(dir, key, "old.enc", old);
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateAndActivate(File dir, File candidate, SecretKey key,
            byte[] expected) throws Exception {
        assertArrayEquals(expected, decrypt(read(candidate), key));
        setPointer(dir, candidate.getName());
    }

    private static void checkSelected(File dir, SecretKey key, String name, byte[] expected) throws Exception {
        assertEquals(name, selected(dir));
        assertArrayEquals(expected, decrypt(read(new File(dir, selected(dir))), key));
    }

    private static byte[] read(File file) throws IOException {
        assertTrue("Bound experiment read", file.length() <= 2L * BLOB_LIMIT);
        try (FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 2 * BLOB_LIMIT) throw new IOException("Experiment read limit");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static long diskBytes(File file) throws IOException {
        if (!file.isDirectory()) return file.length();
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Cannot list experiment directory");
        long total = 0;
        for (File child : children) total += diskBytes(child);
        return total;
    }

    private static void deleteTree(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list experiment cleanup directory");
            for (File child : children) deleteTree(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Experiment cleanup failed");
    }

    private static long now() { return SystemClock.elapsedRealtimeNanos(); }

    private static void emit(String message) {
        Log.i(TAG, message);
        Bundle status = new Bundle();
        status.putString("storageExperiment", message);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private static final class Metrics {
        final String mode;
        final int count, sample;
        long serialize, encrypt, envelope, publish, read, unwrap, decrypt;
        long plain, cipher, written, readBytes, reused, peak;

        Metrics(String mode, int count, int sample) {
            this.mode = mode;
            this.count = count;
            this.sample = sample;
        }

        void report() {
            emit("mode=" + mode + " records=" + count + " sample=" + sample
                    + " serialize_ns=" + serialize + " encrypt_ns=" + encrypt
                    + " envelope_ns=" + envelope + " durable_publish_ns=" + publish
                    + " read_ns=" + read + " unwrap_ns=" + unwrap + " decrypt_ns=" + decrypt
                    + " plain_bytes=" + plain + " cipher_bytes=" + cipher
                    + " written_bytes=" + written + " read_bytes=" + readBytes
                    + " reused_bytes=" + reused + " peak_disk_upper_bytes=" + peak);
        }
    }
}
