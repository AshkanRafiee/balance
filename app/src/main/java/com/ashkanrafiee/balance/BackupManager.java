package com.ashkanrafiee.balance;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
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
    /** Payload shape: 1 = balances only, 2 = balances + transactions. Older backups (1) are still read. */
    private static final int PAYLOAD_FORMAT = 2;
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
        boolean changed() { return added > 0 || updated > 0; }
    }

    private BackupManager() {}

    /** Builds an encrypted backup of the current balances and transaction history and writes it to
     *  {@code uri}. */
    static void create(Context context, Uri uri, String password) throws Exception {
        String payload = new JSONObject()
            .put("payloadFormat", PAYLOAD_FORMAT)
            .put("balances", new JSONObject(BalanceData.serialize(BalanceData.read(context))))
            .put("transactions", new JSONObject(
                BalanceData.serializeTransactions(BalanceData.readTransactions(context))))
            .toString();

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

        SecretKey key = deriveKey(KDF_ALGORITHM, password, salt, ITERATIONS, KEY_BITS);
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(headerBytes);
        byte[] ct = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream(headerBytes.length + ct.length + 13);
        out.write(MAGIC);
        out.write(FORMAT_VERSION);
        out.write(toIntBytes(headerBytes.length));
        out.write(headerBytes);
        out.write(ct);
        writeUri(context, uri, out.toByteArray());
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
        if (version > FORMAT_VERSION) throw new BackupException(R.string.backup_error_unsupported);
        int headerLen = fromIntBytes(file, MAGIC.length + 1);
        if (headerLen <= 0 || MAGIC.length + 1 + 4 + headerLen > file.length)
            throw new BackupException(R.string.backup_error_not_backup);
        byte[] headerBytes = new byte[headerLen];
        System.arraycopy(file, MAGIC.length + 1 + 4, headerBytes, 0, headerLen);
        byte[] ct = new byte[file.length - (MAGIC.length + 1 + 4 + headerLen)];
        System.arraycopy(file, MAGIC.length + 1 + 4 + headerLen, ct, 0, ct.length);

        JSONObject header;
        try {
            header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
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
        try {
            JSONObject payload = new JSONObject(plain);
            if (payload.has("balances"))
                backup = BalanceData.deserialize(payload.getJSONObject("balances").toString());
            else
                backup = new LinkedHashMap<>();
            if (payload.has("transactions"))
                backupTxs = BalanceData.deserializeTransactions(
                    payload.getJSONObject("transactions").toString());
        } catch (Exception e) {
            Log.w(TAG, "payload parse failed", e);
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
        for (Transaction t : currentTxs) seen.add(t.bank + "|" + t.date + "|" + t.amount);
        for (Transaction t : backupTxs)
            if (seen.add(t.bank + "|" + t.date + "|" + t.amount)) currentTxs.add(t);
        BalanceData.writeTransactions(context, currentTxs);
        return result;
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