package com.ashkanrafiee.balance;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.JsonWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * Encrypted, self-describing backups of the saved balances.
 *
 * <p>The on-disk format is a small plaintext header followed by an AES-256-GCM ciphertext. The header
 * carries every parameter the decryption needs (KDF algorithm, iteration count, salt, key size, cipher
 * algorithm, IV, tag size and the application version that created the file), so a future release that
 * switches to a stronger KDF or cipher can still read older files, and this release can report a clear
 * error instead of guessing when it meets a format it does not know yet.
 *
 * <pre>
 *   "BALNCEBK"             8-byte magic
 *   0x01                   format version
 *   [headerLen:4 BE]       length of the JSON header below
 *   header JSON (plain)    {"format","createdAt","appVersion",
 *                            "kdf":{"algorithm","iterations","salt","keyBits"},
 *                            "cipher":{"algorithm","iv","tagBits"}}
 *   ciphertext             AES-256-GCM(payload JSON), header bytes used as AAD
 * </pre>
 *
 * <p>Key derivation follows the OWASP Password Storage Cheat Sheet: PBKDF2 with HMAC-SHA-256 and
 * 600,000 iterations, a fresh 128-bit random salt per backup and AES-256-GCM with a 128-bit tag.
 */
final class BackupManager {
    private static final byte[] MAGIC = {'B', 'A', 'L', 'N', 'C', 'E', 'B', 'K'};
    private static final int FORMAT_VERSION = 1;
    /** Payload shape: 1 = balances only, 2 = balances + transactions, 3 = balances + transactions +
     *  notes, 4 = those plus the reasons the banks stated, 5 = those plus the channels they stated,
     *  6 = those plus user-created transaction tags, 7 = those plus user-created commitments.
     *  Older backups are still read and missing
     *  metadata sections are left untouched during restore. */
    private static final int PAYLOAD_FORMAT = 7;
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Upper bound on a legacy restore's claimed KDF work. A hostile or corrupt header must never
     *  drive the app into a multi-minute PBKDF2 burn (or a huge derived-key allocation) before the
     *  GCM tag is checked: the value comes from the file, so it is validated before any key
     *  derivation runs. Ours is 600k; anything farther above it is reported as unsupported rather
     *  than attempted. */
    private static final int MAX_ITERATIONS = 6_000_000;
    private static final int MAX_SALT_BYTES = 256;
    private static final int MIN_KEY_BITS = 128;
    private static final int MAX_KEY_BITS = 256;
    /** Legacy creation refuses to emit a backup file larger than this. Framed creation has no
     *  total-size cap; legacy restore streams through the staged path without a record cap. */
    private static final long MAX_BACKUP_BYTES = 10L * 1024 * 1024;

    /** Human-readable error carrying the string resource that describes it. */
    static final class BackupException extends Exception {
        final int resId;
        BackupException(int resId) {
            super(null, null, false, false);
            this.resId = resId;
        }
    }

    /** One restorable section of a backup. The file always carries every section; the user
     *  picks which ones a restore merges. */
    enum Section {
        BALANCES,
        TRANSACTIONS,
        NOTES,
        REASONS,
        CHANNELS,
        TAGS,
        COMMITMENTS,
        SOURCES
    }

    /** All sections, for callers (and tests) that restore a backup whole. */
    static java.util.EnumSet<Section> allSections() {
        return java.util.EnumSet.allOf(Section.class);
    }

    /** Result of a restore merge: per-bank newest-wins accounting. */
    static final class RestoreResult {
        int added;
        int updated;
        int transactionsAdded;
        int commitmentsAdded;
        boolean metadataChanged;
        boolean changed() {
            return added > 0 || updated > 0 || transactionsAdded > 0
                || commitmentsAdded > 0 || metadataChanged;
        }
    }

    private BackupManager() {}

    /** Builds a legacy encrypted backup of the current balances and transaction history and writes
     *  it to {@code uri}. Synchronized on {@link BalanceData} like {@link #restore} so the snapshot
     *  can never interleave with a background {@link BalanceData#scanSms} scan. New backups created
     *  by the app use {@link #createFramed}; this legacy writer remains for compatibility tests and
     *  callers that need the original on-disk layout. */
    static void create(Context context, Uri uri, String password) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);

        JSONObject header = new JSONObject()
            .put("format", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("appVersion", appVersion(context))
            .put("kdf", new JSONObject()
                .put("algorithm", KDF_ALGORITHM)
                .put("iterations", ITERATIONS)
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("keyBits", KEY_BITS))
            .put("cipher", new JSONObject()
                .put("algorithm", CIPHER_ALGORITHM)
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("tagBits", TAG_BITS));
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        File temp = File.createTempFile("balance-backup-", ".tmp", context.getCacheDir());
        try {
            synchronized (BalanceData.class) {
                SecretKey key = deriveKey(KDF_ALGORITHM, password, salt, ITERATIONS, KEY_BITS);
                Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(headerBytes);
                try (FileOutputStream raw = new FileOutputStream(temp)) {
                    raw.write(MAGIC);
                    raw.write(FORMAT_VERSION);
                    raw.write(toIntBytes(headerBytes.length));
                    raw.write(headerBytes);
                    try (Writer writer = new OutputStreamWriter(
                            new CipherOutputStream(raw, cipher), StandardCharsets.UTF_8)) {
                        writeStreamingPayload(context, writer);
                    }
                }
            }
            if (temp.length() > MAX_BACKUP_BYTES) throw new Exception("backup too large");
            copyFileToUri(context, temp, uri);
        } finally {
            temp.delete();
        }
    }

    private static void writeStreamingPayload(Context context, Writer writer) throws Exception {
        writer.write("{\"payloadFormat\":" + PAYLOAD_FORMAT + ",\"balances\":");
        writer.write(BalanceData.serialize(BalanceData.read(context)));
        writer.write(",\"transactions\":{\"transactions\":[");
        final boolean[] first = {true};
        TransactionStore.forEach(context, 256, transaction -> {
            if (!first[0]) writer.write(",");
            first[0] = false;
            writer.write(BalanceData.transactionJson(transaction).toString());
        });
        writer.write("]},\"txNotes\":");
        writer.write(BalanceData.serializeTextMap(BalanceData.readNotes(context)));
        writer.write(",\"txReasons\":");
        writer.write(BalanceData.serializeTextMap(BalanceData.readReasons(context)));
        writer.write(",\"txChannels\":");
        writer.write(BalanceData.serializeTextMap(BalanceData.readChannels(context)));
        writer.write(",\"txTags\":");
        writer.write(BalanceData.serializeTagsMap(BalanceData.readTags(context)));
        writer.write(",\"commitments\":");
        writer.write(BalanceData.serializeCommitments(BalanceData.readCommitments(context)));
        writer.write("}");
    }

    /**
     * Creates the framed v2 backup. The legacy {@link #create} writer remains available while the
     * v2 restore path is staged and verified; new callers should use this method once restore is
     * enabled. Rows are written directly from bounded cursors and the framed footer is committed
     * only after the JSON producer completes.
     */
    static void createFramed(Context context, Uri uri, String password) throws Exception {
        createFramed(context, uri, password, null);
    }

    /** Same as {@link #createFramed(Context, Uri, String)} with advisory progress. */
    static void createFramed(Context context, Uri uri, String password, WorkProgress progress)
            throws Exception {
        if (password == null) throw new IllegalArgumentException("password");
        File temp = File.createTempFile("balance-backup-v2-", ".tmp", context.getCacheDir());
        char[] chars = password.toCharArray();
        try {
            if (progress != null) progress.stage(R.string.backup_stage_reading);
            synchronized (BalanceData.class) {
                try (FileOutputStream raw = new FileOutputStream(temp)) {
                    BackupFrames.BackupOutputStream encrypted =
                        BackupFrames.openOutputStream(raw, chars);
                    try {
                        JsonWriter json = new JsonWriter(new OutputStreamWriter(encrypted,
                            StandardCharsets.UTF_8));
                        writeFramedPayload(context, json);
                        json.flush();
                        encrypted.finish();
                    } finally {
                        encrypted.close();
                    }
                }
            }
            copyFileToUri(context, temp, uri);
        } finally {
            java.util.Arrays.fill(chars, '\0');
            temp.delete();
        }
    }

    private static void writeFramedPayload(Context context, JsonWriter json) throws Exception {
        json.beginObject();
        json.name("schema").value(2);

        json.name("balances").beginArray();
        for (Map.Entry<String, Bank> entry : BalanceData.read(context).entrySet()) {
            Bank bank = entry.getValue();
            json.beginObject().name("key").value(entry.getKey()).name("name").value(bank.name)
                .name("amount").value(bank.amount).name("date").value(bank.date)
                .name("sender").value(bank.sender);
            if (bank.account != null) json.name("account").value(bank.account);
            json.endObject();
        }
        json.endArray();

        json.name("transactions").beginArray();
        TransactionStore.forEach(context, 256, transaction -> writeTransaction(json, transaction));
        json.endArray();

        json.name("metadata").beginObject();
        writeMetadata(context, json, MetadataStore.NOTES, false);
        writeMetadata(context, json, MetadataStore.REASONS, false);
        writeMetadata(context, json, MetadataStore.CHANNELS, false);
        writeMetadata(context, json, MetadataStore.TAGS, true);
        json.endObject();

        json.name("commitments");
        CommitmentStore.writeJsonRecords(context, json);

        json.name("commitmentDeletions");
        CommitmentStore.writeJsonDeletions(context, json);

        json.name("sources").beginArray();
        java.util.List<SourceStore.Source> sources = new java.util.ArrayList<>();
        SourceStore.forEachSource(context, 256, source -> { sources.add(source); });
        for (SourceStore.Source source : sources) {
            json.beginObject();
            json.name("sender").value(source.sender);
            json.name("body").value(source.rawBody);
            json.name("arrival").value(source.arrivalTime);
            json.name("rule").value(source.ruleVersion);
            json.name("transactions").beginArray();
            for (SourceStore.TransactionObservation observation :
                    SourceStore.transactionObservationsForSource(context, source.id)) {
                Transaction transaction = observation.transaction;
                json.beginObject().name("parser").value(observation.parserRevision)
                    .name("bank").value(transaction.bank).name("date").value(transaction.date)
                    .name("amount").value(transaction.amount);
                if (transaction.account != null) json.name("account").value(transaction.account);
                if (transaction.balance != null) json.name("bal").value(transaction.balance);
                if (transaction.sig != null) json.name("sig").value(transaction.sig);
                if (transaction.content != null) json.name("content").value(transaction.content);
                json.endObject();
            }
            json.endArray();
            json.name("balances").beginArray();
            for (SourceStore.BalanceObservation observation :
                    SourceStore.balanceObservationsForSource(context, source.id)) {
                json.beginObject().name("parser").value(observation.parserRevision)
                    .name("bank").value(observation.bank).name("date").value(observation.date)
                    .name("balance").value(observation.balance);
                if (observation.account != null) json.name("account").value(observation.account);
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }

    private static void writeTransaction(JsonWriter json, Transaction t) throws java.io.IOException {
        json.beginObject().name("bank").value(t.bank).name("date").value(t.date)
            .name("amount").value(t.amount);
        if (t.account != null) json.name("account").value(t.account);
        if (t.balance != null) json.name("bal").value(t.balance);
        if (t.sig != null) json.name("sig").value(t.sig);
        if (t.content != null) json.name("content").value(t.content);
        json.endObject();
    }

    private static void writeMetadata(Context context, JsonWriter json, int kind, boolean tags)
            throws Exception {
        String name;
        if (kind == MetadataStore.NOTES) name = "notes";
        else if (kind == MetadataStore.REASONS) name = "reasons";
        else if (kind == MetadataStore.CHANNELS) name = "channels";
        else name = "tags";
        json.name(name).beginArray();
        MetadataStore.forEach(context, kind, 256, row -> {
            json.beginObject().name("key").value(row.key);
            if (tags) {
                json.name("tags").beginArray();
                for (String tag : row.tags) json.value(tag);
                json.endArray();
            } else {
                json.name("text").value(row.text);
            }
            json.endObject();
        });
        json.endArray();
    }

    /** Reads an encrypted backup, merges it with the current balances (newest wins per bank) and
     *  persists the merged result. Returns what the merge changed.
     *
     *  <p>Synchronized on {@link BalanceData} so a restore can never interleave with a background
     *  {@link BalanceData#scanSms} scan: both do a read-modify-write over the shared store, and an
     *  interleaving would let one of them persist a stale snapshot and silently drop the other's
     *  freshly scanned transactions. */
    static RestoreResult restore(Context context, Uri uri, String password) throws Exception {
        return restore(context, uri, password, null);
    }

    /** Same as {@link #restore(Context, Uri, String)} with advisory progress. */
    static RestoreResult restore(Context context, Uri uri, String password, WorkProgress progress)
            throws Exception {
        return restore(context, uri, password, progress, allSections());
    }

    /** Restores only the selected sections; unselected local data is left untouched. */
    static RestoreResult restore(Context context, Uri uri, String password, WorkProgress progress,
            java.util.Set<Section> selection) throws Exception {
        if (selection == null) selection = allSections();
        if (isFramed(context, uri))
            return BackupV2Restore.restore(context, uri, password, progress, selection);
        return BackupV2Restore.restoreLegacy(context, uri, password, progress, selection);
    }

    private static boolean isFramed(Context context, Uri uri) throws Exception {
        byte[] magic = new byte[8];
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("null input stream");
            int offset = 0;
            while (offset < magic.length) {
                int count = input.read(magic, offset, magic.length - offset);
                if (count < 0) return false;
                if (count == 0) continue;
                offset += count;
            }
            return java.util.Arrays.equals(magic, "BALFRM01".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void copyFileToUri(Context context, File source, Uri uri) throws Exception {
        try (InputStream in = new FileInputStream(source);
                OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new Exception("null output stream");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
    }

    private static SecretKey deriveKey(String kdfAlgorithm, String password, byte[] salt,
            int iterations, int keyBits) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(kdfAlgorithm);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        new SecureRandom().nextBytes(out);
        return out;
    }

    private static byte[] toIntBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

}
