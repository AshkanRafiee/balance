package com.ashkanrafiee.balance;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.JsonWriter;
import android.util.Log;
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String TAG = "BackupManager";
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
    /** Upper bound on a restore's claimed KDF work. A hostile or corrupt header must never drive the app
     *  into a multi-minute PBKDF2 burn (or a huge derived-key allocation) before the GCM tag is checked:
     *  the value comes from the file, so it is validated before any key derivation runs. Ours is 600k;
     *  anything farther above it is reported as unsupported rather than attempted. */
    private static final int MAX_ITERATIONS = 6_000_000;
    private static final int MAX_SALT_BYTES = 256;
    private static final int MIN_KEY_BITS = 128;
    private static final int MAX_KEY_BITS = 256;
    /** Restore refuses to read a backup file larger than this. The payload is a handful of balances, so
     *  anything this big is not a genuine backup — and reading it fully into memory would be a DoS. */
    private static final long MAX_BACKUP_BYTES = 10L * 1024 * 1024;
    /** Upper bound on the transactions a restore will merge. The history buffer is read fully into
     *  memory and written back as one blob, so a crafted (but validly encrypted) backup must never be
     *  able to push it past a sane size. A genuine backup holds at most one transaction per SMS, so
     *  anything close to this cap is not a real history. */
    private static final int MAX_TRANSACTIONS = 200_000;

    /** Human-readable error carrying the string resource that describes it. */
    static final class BackupException extends Exception {
        final int resId;
        BackupException(int resId) {
            super(null, null, false, false);
            this.resId = resId;
        }
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

    /** Builds an encrypted backup of the current balances and transaction history and writes it to
     *  {@code uri}. Synchronized on {@link BalanceData} like {@link #restore} so the snapshot can
     *  never interleave with a background {@link BalanceData#scanSms} scan. */
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

    /**
     * Creates the framed v2 backup. The legacy {@link #create} writer remains available while the
     * v2 restore path is staged and verified; new callers should use this method once restore is
     * enabled. Rows are written directly from bounded cursors and the framed footer is committed
     * only after the JSON producer completes.
     */
    static void createFramed(Context context, Uri uri, String password) throws Exception {
        if (password == null) throw new IllegalArgumentException("password");
        File temp = File.createTempFile("balance-backup-v2-", ".tmp", context.getCacheDir());
        char[] chars = password.toCharArray();
        try {
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
        json.endObject();
    }

    private static void writeTransaction(JsonWriter json, Transaction t) throws java.io.IOException {
        json.beginObject().name("bank").value(t.bank).name("date").value(t.date)
            .name("amount").value(t.amount);
        if (t.account != null) json.name("account").value(t.account);
        if (t.balance != null) json.name("balance").value(t.balance);
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

    /** Reads an encrypted backup, merges it with the current balances (newest wins per bank) and
     *  persists the merged result. Returns what the merge changed.
     *
     *  <p>Synchronized on {@link BalanceData} so a restore can never interleave with a background
     *  {@link BalanceData#scanSms} scan: both do a read-modify-write over the shared store, and an
     *  interleaving would let one of them persist a stale snapshot and silently drop the other's
     *  freshly scanned transactions. */
    static RestoreResult restore(Context context, Uri uri, String password) throws Exception {
        synchronized (BalanceData.class) { return restoreLocked(context, uri, password); }
    }

    private static RestoreResult restoreLocked(Context context, Uri uri, String password) throws Exception {
        byte[] file = readUri(context, uri);
        if (file.length < MAGIC.length + 1 + 4) throw new BackupException(R.string.backup_error_not_backup);
        for (int i = 0; i < MAGIC.length; i++)
            if (file[i] != MAGIC[i]) throw new BackupException(R.string.backup_error_not_backup);
        int version = file[MAGIC.length] & 0xFF;
        if (version < 1 || version > FORMAT_VERSION) throw new BackupException(R.string.backup_error_unsupported);
        int headerLen = fromIntBytes(file, MAGIC.length + 1);
        long payloadStart = (long) MAGIC.length + 1 + 4 + headerLen;
        if (headerLen <= 0 || headerLen > MAX_BACKUP_BYTES || payloadStart > file.length)
            throw new BackupException(R.string.backup_error_not_backup);
        byte[] headerBytes = new byte[headerLen];
        int headerStart = MAGIC.length + 1 + 4;
        System.arraycopy(file, headerStart, headerBytes, 0, headerLen);
        byte[] ct = new byte[(int) (file.length - payloadStart)];
        System.arraycopy(file, (int) payloadStart, ct, 0, ct.length);

        JSONObject header;
        try {
            header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            // A crafted file can nest its JSON so deeply that parsing exhausts the stack; that must
            // land on the same "not a backup" path as any other malformed header, not crash.
            throw new BackupException(R.string.backup_error_not_backup);
        }

        String kdfAlgorithm;
        int iterations;
        byte[] salt;
        int keyBits;
        String cipherAlgorithm;
        byte[] iv;
        int tagBits;
        try {
            JSONObject kdf = header.getJSONObject("kdf");
            kdfAlgorithm = kdf.getString("algorithm");
            iterations = kdf.getInt("iterations");
            salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP);
            keyBits = kdf.optInt("keyBits", 256);
            JSONObject cipherParams = header.getJSONObject("cipher");
            cipherAlgorithm = cipherParams.getString("algorithm");
            iv = Base64.decode(cipherParams.getString("iv"), Base64.NO_WRAP);
            tagBits = cipherParams.getInt("tagBits");
        } catch (Exception e) {
            throw new BackupException(R.string.backup_error_not_backup);
        }
        if (!CIPHER_ALGORITHM.equals(cipherAlgorithm))
            throw new BackupException(R.string.backup_error_unsupported);
        if (!KDF_ALGORITHM.equals(kdfAlgorithm))
            throw new BackupException(R.string.backup_error_unsupported);
        // Every one of these arrives with the file: sanity-bound them BEFORE deriving any key, so a
        // crafted header cannot trigger a huge PBKDF2 work factor or an absurd key length.
        if (iterations <= 0 || iterations > MAX_ITERATIONS)
            throw new BackupException(R.string.backup_error_unsupported);
        if (keyBits < MIN_KEY_BITS || keyBits > MAX_KEY_BITS || keyBits % 64 != 0)
            throw new BackupException(R.string.backup_error_unsupported);
        if (tagBits != TAG_BITS)
            throw new BackupException(R.string.backup_error_unsupported);
        if (iv == null || iv.length != IV_BYTES)
            throw new BackupException(R.string.backup_error_unsupported);
        if (salt == null || salt.length == 0 || salt.length > MAX_SALT_BYTES)
            throw new BackupException(R.string.backup_error_unsupported);

        String plain;
        try {
            SecretKey key = deriveKey(kdfAlgorithm, password, salt, iterations, keyBits);
            Cipher cipher = Cipher.getInstance(cipherAlgorithm);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(tagBits, iv));
            cipher.updateAAD(headerBytes);
            plain = new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new BackupException(R.string.backup_error_password);
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException(R.string.backup_error_password);
        }

        LinkedHashMap<String, Bank> backup;
        List<Transaction> backupTxs = new ArrayList<>();
        Map<String, String> backupNotes = new LinkedHashMap<>();
        Map<String, String> backupReasons = new LinkedHashMap<>();
        Map<String, String> backupChannels = new LinkedHashMap<>();
        Map<String, List<String>> backupTags = new LinkedHashMap<>();
        List<Commitment> backupCommitments = new ArrayList<>();
        try {
            JSONObject payload = new JSONObject(plain);
            if (payload.has("balances"))
                backup = BalanceData.deserialize(payload.getJSONObject("balances").toString());
            else
                backup = new LinkedHashMap<>();
            if (payload.has("transactions"))
                backupTxs = BalanceData.deserializeTransactions(
                    payload.getJSONObject("transactions").toString(), MAX_TRANSACTIONS);
            if (payload.has("txNotes"))
                backupNotes = BalanceData.deserializeTextMap(payload.getJSONObject("txNotes").toString());
            if (payload.has("txReasons"))
                backupReasons = BalanceData.deserializeTextMap(
                    payload.getJSONObject("txReasons").toString());
            if (payload.has("txChannels"))
                backupChannels = BalanceData.deserializeTextMap(
                    payload.getJSONObject("txChannels").toString());
            if (payload.has("txTags"))
                backupTags = BalanceData.deserializeTagsMap(payload.getJSONObject("txTags").toString());
            if (payload.has("commitments"))
                backupCommitments = BalanceData.deserializeCommitments(
                    payload.getJSONObject("commitments").toString());
        } catch (Throwable e) {
            // A validly-decrypted but hostile payload can nest its JSON so deeply that parsing
            // exhausts the stack; that must land on the same "wrong password or corrupted backup"
            // path as any other malformed payload, not crash the restore.
            Log.w(TAG, "payload parse failed");
            throw new BackupException(R.string.backup_error_password);
        }

        LinkedHashMap<String, Bank> current = BalanceData.read(context);
        RestoreResult result = new RestoreResult();
        LinkedHashMap<String, Bank> merged = new LinkedHashMap<>();
        merged.putAll(current);
        for (Map.Entry<String, Bank> e : backup.entrySet()) {
            String name = e.getKey();
            Bank incoming = e.getValue();
            Bank existing = current.get(name);
            if (existing == null) {
                merged.put(name, incoming);
                result.added++;
            } else if (incoming.date > existing.date) {
                merged.put(name, incoming);
                result.updated++;
            }
        }
        BalanceData.write(context, merged);

        // Transaction history is merged as a union (deduped), never dropped, so restoring onto the
        // same device does not lose locally-scanned movements and a newer backup cannot destroy older
        // ones. The roster is capped so a hostile backup cannot bloat the in-memory history.
        List<Transaction> currentTxs = BalanceData.readTransactions(context);
        if (backupTxs.size() > MAX_TRANSACTIONS)
            backupTxs = backupTxs.subList(0, MAX_TRANSACTIONS);
        Set<String> seen = new HashSet<>();
        Set<String> seenContent = new HashSet<>();
        for (Transaction t : currentTxs) {
            seen.add(BalanceData.txIdentityKey(t));
            if (t.content != null) seenContent.add(t.content);
        }
        for (Transaction t : backupTxs) {
            if (currentTxs.size() >= MAX_TRANSACTIONS) break;
            if (t.content != null && seenContent.contains(t.content)) continue;
            String sigKey = t.sig != null ? "s:" + t.sig : null;
            String key = BalanceData.txIdentityKey(t);
            if (sigKey != null && seen.contains(sigKey)) continue;
            if (seen.contains(key)) continue;
            if (sigKey != null) seen.add(sigKey);
            seen.add(key);
            if (t.content != null) seenContent.add(t.content);
            currentTxs.add(t);
            result.transactionsAdded++;
        }
        if (!BalanceData.writeTransactions(context, currentTxs))
            throw new Exception("transaction restore could not be persisted");

        // Notes are merged as a union with the local text winning, mirroring the transaction union:
        // a restore must never clobber the note the user typed since the backup was made, and notes
        // that only exist in the backup (for movements brought in by this restore) land here too. An
        // older backup without a notes section leaves the current notes completely untouched.
        if (!backupNotes.isEmpty()) {
            Map<String, String> currentNotes = BalanceData.readNotes(context);
            if (unionLocalFirst(currentNotes, backupNotes)) {
                BalanceData.writeNotes(context, currentNotes);
                result.metadataChanged = true;
            }
        }

        // The reasons the banks stated travel with the movements they describe, merged exactly like the
        // notes: the local text wins, and a reason that only exists in the backup lands here so a
        // movement whose SMS was deleted before the backup still shows why it happened. A backup
        // without a reasons section leaves the current reasons completely untouched.
        if (!backupReasons.isEmpty()) {
            Map<String, String> currentReasons = BalanceData.readReasons(context);
            if (unionLocalFirst(currentReasons, backupReasons)) {
                BalanceData.writeReasons(context, currentReasons);
                result.metadataChanged = true;
            }
        }

        // The channels the banks stated travel the same way, so a movement whose SMS was deleted
        // before the backup still shows how the money moved.
        if (!backupChannels.isEmpty()) {
            Map<String, String> currentChannels = BalanceData.readChannels(context);
            if (unionLocalFirst(currentChannels, backupChannels)) {
                BalanceData.writeChannels(context, currentChannels);
                result.metadataChanged = true;
            }
        }

        // Tags are user-owned and can be multiple per movement. Restore unions both sides so a tag
        // created on either device survives; an older backup without txTags leaves local tags alone.
        if (!backupTags.isEmpty()) {
            Map<String, List<String>> currentTags = BalanceData.readTags(context);
            boolean tagsChanged = remapTagKeys(backupTags, tagKeyAliases(currentTxs, backupTxs));
            tagsChanged |= BalanceData.unionTags(currentTags, backupTags);
            if (tagsChanged) {
                BalanceData.writeTags(context, currentTags);
                result.metadataChanged = true;
            }
        }
        // Commitments are user-owned like tags: a restore unions both sides by id, so a series
        // created on either device survives, and an older backup without the section leaves local
        // commitments alone. The local series always wins an id collision.
        if (!backupCommitments.isEmpty()) {
            List<Commitment> currentCommitments = BalanceData.readCommitments(context);
            Set<String> ids = new HashSet<>();
            for (Commitment c : currentCommitments) ids.add(c.id);
            boolean commitmentsChanged = false;
            for (Commitment c : backupCommitments) {
                if (ids.contains(c.id)) continue;
                if (currentCommitments.size() >= Commitment.MAX_COMMITMENTS) break;
                ids.add(c.id);
                currentCommitments.add(c);
                commitmentsChanged = true;
                result.commitmentsAdded++;
            }
            if (commitmentsChanged) BalanceData.writeCommitments(context, currentCommitments);
            if (commitmentsChanged) CommitmentReminders.scheduleAll(context);
        }
        return result;
    }

    /** Adds every entry of {@code incoming} that {@code current} does not already have, in place, and
     *  reports whether anything was added — so the caller writes the store only when it changed. The
     *  local text always wins: a restore must never overwrite what this device already knows with
     *  what an older backup happened to hold for the same movement. */
    private static boolean unionLocalFirst(Map<String, String> current, Map<String, String> incoming) {
        boolean changed = false;
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            if (current.containsKey(e.getKey())) continue;
            current.put(e.getKey(), e.getValue());
            changed = true;
        }
        return changed;
    }

    /** Maps a backup's legacy identity key to the current content key when both files contain the
     *  same physical movement. This is needed when a pre-content-digest transaction is deduped by its
     *  signature during restore: its tags must follow the surviving row rather than stay orphaned. */
    private static Map<String, String> tagKeyAliases(List<Transaction> current, List<Transaction> backup) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, Transaction> byContent = new HashMap<>();
        Map<String, Transaction> byIdentity = new HashMap<>();
        for (Transaction existing : current) {
            if (existing.content != null) byContent.put(existing.content, existing);
            byIdentity.put(BalanceData.txIdentityKey(existing), existing);
            if (existing.sig != null) byIdentity.put("s:" + existing.sig, existing);
        }
        for (Transaction incoming : backup) {
            Transaction existing = incoming.content == null ? null : byContent.get(incoming.content);
            if (existing == null) existing = byIdentity.get(BalanceData.txIdentityKey(incoming));
            if (existing == null && incoming.sig != null) existing = byIdentity.get("s:" + incoming.sig);
            if (existing == null || !sameMovement(incoming, existing)) continue;
            String from = BalanceData.noteKey(incoming);
            String to = BalanceData.noteKey(existing);
            if (!from.equals(to)) aliases.put(from, to);
        }
        return aliases;
    }

    private static boolean sameMovement(Transaction a, Transaction b) {
        if (a.content != null && b.content != null && a.content.equals(b.content)) return true;
        if (a.sig != null && b.sig != null && a.sig.equals(b.sig)) return true;
        return BalanceData.txIdentityKey(a).equals(BalanceData.txIdentityKey(b));
    }

    /** Applies identity aliases to a metadata map, unioning if a destination already has tags. */
    private static boolean remapTagKeys(Map<String, List<String>> tags,
            Map<String, String> aliases) {
        boolean changed = false;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            List<String> moved = tags.remove(alias.getKey());
            if (moved == null) continue;
            changed = true;
            BalanceData.unionTags(tags,
                java.util.Collections.singletonMap(alias.getValue(), moved));
        }
        return changed;
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

    private static int fromIntBytes(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
            | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void writeUri(Context context, Uri uri, byte[] data) throws Exception {
        try (OutputStream os = context.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new Exception("null output stream");
            os.write(data);
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

    private static byte[] readUri(Context context, Uri uri) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("null input stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > MAX_BACKUP_BYTES)
                    throw new BackupException(R.string.backup_error_not_backup);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
