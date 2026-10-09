package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Strict, bounded-memory restore for the authenticated framed backup format.
 *
 * <p>The authenticated stream is consumed into a disposable SQLite database. Every staged value is
 * encrypted with a fresh row codec; the database contains only ordering columns and keyed indexes.
 * The database is committed only after the JSON document and the authenticated footer have both
 * been consumed. A separate {@link DataGeneration.Stage} receives the merge and is published only
 * after every store operation succeeds.
 */
final class BackupV2Restore {
    private static final int BALANCE = 1;
    private static final int TRANSACTION = 2;
    private static final int NOTES = 3;
    private static final int REASONS = 4;
    private static final int CHANNELS = 5;
    private static final int TAGS = 6;

    private static final int SOURCE = 7;

    private static final int SECTION_SCHEMA = 1;
    private static final int SECTION_BALANCES = 1 << 1;
    private static final int SECTION_TRANSACTIONS = 1 << 2;
    private static final int SECTION_METADATA = 1 << 3;
    private static final int SECTION_COMMITMENTS = 1 << 4;
    private static final int SECTION_SOURCES = 1 << 5;
    /** Tombstones of explicitly deleted commitments. Optional like sources: backups written
     *  before deletions were recorded carry no such section. */
    private static final int SECTION_COMMITMENT_DELETIONS = 1 << 6;
    private static final int ALL_SECTIONS = SECTION_SCHEMA | SECTION_BALANCES
        | SECTION_TRANSACTIONS | SECTION_METADATA | SECTION_COMMITMENTS;

    private static final int COMMITMENT_ID = 1;
    private static final int COMMITMENT_NAME = 1 << 1;
    private static final int COMMITMENT_AMOUNT = 1 << 2;
    private static final int COMMITMENT_FREQUENCY = 1 << 3;
    private static final int COMMITMENT_START = 1 << 4;
    private static final int COMMITMENT_END = 1 << 5;
    private static final int COMMITMENT_DONE = 1 << 6;
    private static final int COMMITMENT_PAID_THROUGH = 1 << 7;
    private static final int COMMITMENT_REMIND = 1 << 8;
    private static final int COMMITMENT_REMIND_BEFORE = 1 << 9;
    private static final int COMMITMENT_PAID = 1 << 10;
    private static final int COMMITMENT_UNPAID = 1 << 11;

    private static final int TRANSACTION_BANK = 1;
    private static final int TRANSACTION_DATE = 1 << 1;
    private static final int TRANSACTION_AMOUNT = 1 << 2;
    private static final int TRANSACTION_ACCOUNT = 1 << 3;
    private static final int TRANSACTION_BALANCE = 1 << 4;
    private static final int TRANSACTION_SIG = 1 << 5;
    private static final int TRANSACTION_CONTENT = 1 << 6;

    private static final String DB_PREFIX = "balance_backup_stage_";
    private static final String INDEX_DOMAIN = "backup-stage-index";

    private static final byte[] LEGACY_MAGIC = {
        'B', 'A', 'L', 'N', 'C', 'E', 'B', 'K'
    };
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final String LEGACY_KDF = "PBKDF2WithHmacSHA256";
    private static final String LEGACY_CIPHER = "AES/GCM/NoPadding";
    private static final int LEGACY_IV_BYTES = 12;
    private static final int LEGACY_TAG_BYTES = 16;
    private static final int LEGACY_MAX_HEADER_BYTES = 4 * 1024 * 1024;
    private static final int LEGACY_MAX_ITERATIONS = 6_000_000;
    private static final int LEGACY_MAX_SALT_BYTES = 256;
    private static final byte[] PLAINTEXT_STAGE_MAGIC = {
        'B', 'A', 'L', 'S', 'T', 'G', '0', '1'
    };
    private static final int PLAINTEXT_STAGE_IV_BYTES = 12;

    private static volatile DataGeneration.PublishHook publishHookForTests;

    private BackupV2Restore() {}

    static void setPublishHookForTests(DataGeneration.PublishHook hook) {
        publishHookForTests = hook;
    }

    static BackupManager.RestoreResult restore(Context context, Uri uri, String password)
            throws Exception {
        return restore(context, uri, password, null);
    }

    /** Same as {@link #restore(Context, Uri, String)} with advisory progress. */
    static BackupManager.RestoreResult restore(Context context, Uri uri, String password,
            WorkProgress progress) throws Exception {
        return restore(context, uri, password, progress, BackupManager.allSections());
    }

    /** Same with a section selection; unselected local data is left untouched. */
    static BackupManager.RestoreResult restore(Context context, Uri uri, String password,
            WorkProgress progress, java.util.Set<BackupManager.Section> selection)
            throws Exception {
        if (password == null) throw new IllegalArgumentException("password");

        Context application = context.getApplicationContext();
        if (application == null) application = context;
        char[] chars = password.toCharArray();
        InputStage input = null;
        try {
            if (progress != null) progress.stage(R.string.backup_progress_restoring);
            input = new InputStage(application);
            readAuthenticated(application, uri, chars, input);
            input.commitInput();

            final BackupManager.RestoreResult result;
            synchronized (BalanceData.class) {
                // DataGeneration copies the committed local selection into an isolated generation.
                // No BalanceData, transaction, metadata or commitment write below may use the live
                // context. Stage.publish() is the only operation that makes the merge visible.
                try (DataGeneration.Stage generation = DataGeneration.beginStage(application)) {
                    generation.setPublishHookForTests(publishHookForTests);
                    if (progress != null) progress.stage(R.string.backup_stage_merging);
                    result = apply(generation.context(), input, selection);
                    generation.publish();
                }
            }
            // Alarm scheduling is an external side effect, not durable application data. It is done
            // only after the selected generation has been published successfully.
            CommitmentReminders.scheduleAll(application);
            return result;
        } catch (BackupFrames.InvalidBackupException e) {
            // Authentication, a missing footer, a damaged late frame and physical trailing bytes all
            // have the same user-facing meaning. The disposable input stage is never published.
            throw new BackupManager.BackupException(R.string.backup_error_password);
        } finally {
            if (input != null) input.close();
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Imports the original AES-GCM backup formats 1 through 7. The old ciphertext is consumed in
     * bounded chunks. Plaintext is first written to an encrypted disposable file and is not parsed
     * until the old GCM tag has been authenticated; parsing then feeds the same encrypted row stage
     * and generation publisher used by framed restore.
     */
    static BackupManager.RestoreResult restoreLegacy(Context context, Uri uri, String password)
            throws Exception {
        return restoreLegacy(context, uri, password, null);
    }

    /** Same as {@link #restoreLegacy(Context, Uri, String)} with advisory progress. */
    static BackupManager.RestoreResult restoreLegacy(Context context, Uri uri, String password,
            WorkProgress progress) throws Exception {
        return restoreLegacy(context, uri, password, progress, BackupManager.allSections());
    }

    /** Same with a section selection; unselected local data is left untouched. */
    static BackupManager.RestoreResult restoreLegacy(Context context, Uri uri, String password,
            WorkProgress progress, java.util.Set<BackupManager.Section> selection)
            throws Exception {
        if (password == null) throw new IllegalArgumentException("password");
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        char[] chars = password.toCharArray();
        byte[] stageKey = randomBytes(32);
        byte[] stageIv = randomBytes(PLAINTEXT_STAGE_IV_BYTES);
        File plaintext = File.createTempFile("balance-legacy-", ".stage", application.getCacheDir());
        InputStage input = null;
        try {
            if (progress != null) progress.stage(R.string.backup_progress_restoring);
            decryptLegacy(application, uri, chars, plaintext, stageKey, stageIv);
            input = new InputStage(application);
            try (InputStream staged = openPlaintextStage(plaintext, stageKey, stageIv);
                    InputStreamReader reader = new InputStreamReader(staged,
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT));
                    JsonReader json = new JsonReader(reader)) {
                json.setLenient(false);
                parseLegacyPayload(json, input);
                if (json.peek() != JsonToken.END_DOCUMENT)
                    throw invalid("trailing legacy payload");
            }
            input.commitInput();

            BackupManager.RestoreResult result;
            synchronized (BalanceData.class) {
                try (DataGeneration.Stage generation = DataGeneration.beginStage(application)) {
                    generation.setPublishHookForTests(publishHookForTests);
                    if (progress != null) progress.stage(R.string.backup_stage_merging);
                    result = apply(generation.context(), input, selection);
                    generation.publish();
                }
            }
            CommitmentReminders.scheduleAll(application);
            return result;
        } finally {
            if (input != null) input.close();
            plaintext.delete();
            Arrays.fill(chars, '\0');
            Arrays.fill(stageKey, (byte) 0);
            Arrays.fill(stageIv, (byte) 0);
        }
    }

    private static void decryptLegacy(Context context, Uri uri, char[] password, File target,
            byte[] stageKey, byte[] stageIv) throws Exception {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new Exception("null input stream");
            byte[] magic = new byte[LEGACY_MAGIC.length];
            if (!readFully(raw, magic)) throw new BackupManager.BackupException(
                R.string.backup_error_not_backup);
            if (!Arrays.equals(magic, LEGACY_MAGIC))
                throw new BackupManager.BackupException(R.string.backup_error_not_backup);

            int version = readUnsignedByte(raw);
            if (version < 0) throw new BackupManager.BackupException(
                R.string.backup_error_not_backup);
            if (version != LEGACY_FORMAT_VERSION)
                throw new BackupManager.BackupException(R.string.backup_error_unsupported);
            byte[] length = new byte[4];
            if (!readFully(raw, length)) throw new BackupManager.BackupException(
                R.string.backup_error_not_backup);
            int headerLength = fromIntBytes(length, 0);
            if (headerLength <= 0 || headerLength > LEGACY_MAX_HEADER_BYTES)
                throw new BackupManager.BackupException(R.string.backup_error_not_backup);
            byte[] headerBytes = new byte[headerLength];
            if (!readFully(raw, headerBytes)) throw new BackupManager.BackupException(
                R.string.backup_error_not_backup);

            JSONObject header;
            try {
                header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
            } catch (Throwable failure) {
                throw new BackupManager.BackupException(R.string.backup_error_not_backup);
            }
            // The format marker is authenticated as part of the header AAD: it is checked only
            // after the GCM tag verifies, so a tampered header reports an authentication failure
            // while a genuinely newer (validly encrypted) format reports unsupported.

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
                JSONObject cipher = header.getJSONObject("cipher");
                cipherAlgorithm = cipher.getString("algorithm");
                iv = Base64.decode(cipher.getString("iv"), Base64.NO_WRAP);
                tagBits = cipher.getInt("tagBits");
            } catch (Exception failure) {
                throw new BackupManager.BackupException(R.string.backup_error_not_backup);
            }
            if (!LEGACY_KDF.equals(kdfAlgorithm) || !LEGACY_CIPHER.equals(cipherAlgorithm))
                throw new BackupManager.BackupException(R.string.backup_error_unsupported);
            if (iterations <= 0 || iterations > LEGACY_MAX_ITERATIONS || keyBits != 256
                    || tagBits != 128 || iv.length != LEGACY_IV_BYTES || salt.length == 0
                    || salt.length > LEGACY_MAX_SALT_BYTES) {
                throw new BackupManager.BackupException(R.string.backup_error_unsupported);
            }

            SecretKey oldKey = deriveLegacyKey(password, salt, iterations, keyBits);
            Cipher oldCipher = Cipher.getInstance(LEGACY_CIPHER);
            oldCipher.init(Cipher.DECRYPT_MODE, oldKey, new GCMParameterSpec(tagBits, iv));
            oldCipher.updateAAD(headerBytes);

            Cipher stageCipher = Cipher.getInstance(LEGACY_CIPHER);
            stageCipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(stageKey, "AES"), new GCMParameterSpec(128, stageIv));
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(PLAINTEXT_STAGE_MAGIC);
                output.write(stageIv);
                byte[] input = new byte[16 * 1024];
                byte[] tail = new byte[LEGACY_TAG_BYTES];
                int tailLength = 0;
                int count;
                while ((count = raw.read(input)) != -1) {
                    if (count == 0) continue;
                    byte[] combined = new byte[tailLength + count];
                    System.arraycopy(tail, 0, combined, 0, tailLength);
                    System.arraycopy(input, 0, combined, tailLength, count);
                    int ciphertextLength = combined.length - LEGACY_TAG_BYTES;
                    if (ciphertextLength > 0) {
                        writeStageBytes(output, stageCipher,
                            oldCipher.update(combined, 0, ciphertextLength));
                    }
                    tailLength = Math.min(LEGACY_TAG_BYTES, combined.length);
                    System.arraycopy(combined, combined.length - tailLength, tail, 0, tailLength);
                }
                if (tailLength != LEGACY_TAG_BYTES)
                    throw new javax.crypto.AEADBadTagException("legacy ciphertext is truncated");
                writeStageBytes(output, stageCipher, oldCipher.doFinal(tail, 0, tailLength));
                if (header.optInt("format", -1) != LEGACY_FORMAT_VERSION)
                    throw new BackupManager.BackupException(R.string.backup_error_unsupported);
                byte[] stageTag = stageCipher.doFinal();
                if (stageTag != null && stageTag.length != 0) output.write(stageTag);
                output.getFD().sync();
            } catch (javax.crypto.AEADBadTagException failure) {
                throw new BackupManager.BackupException(R.string.backup_error_password);
            } catch (javax.crypto.BadPaddingException failure) {
                throw new BackupManager.BackupException(R.string.backup_error_password);
            }
        }
    }

    private static InputStream openPlaintextStage(File file, byte[] key, byte[] iv)
            throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] magic = new byte[PLAINTEXT_STAGE_MAGIC.length];
            if (!readFully(input, magic) || !Arrays.equals(magic, PLAINTEXT_STAGE_MAGIC))
                throw new Exception("invalid plaintext stage");
            byte[] storedIv = new byte[PLAINTEXT_STAGE_IV_BYTES];
            if (!readFully(input, storedIv) || !Arrays.equals(storedIv, iv))
                throw new Exception("invalid plaintext stage iv");
            Cipher cipher = Cipher.getInstance(LEGACY_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, storedIv));
            return new CipherInputStream(input, cipher);
        } catch (Exception failure) {
            input.close();
            throw failure;
        }
    }

    private static void writeStageBytes(OutputStream output, Cipher cipher, byte[] bytes)
            throws Exception {
        if (bytes == null || bytes.length == 0) return;
        byte[] encrypted = cipher.update(bytes);
        if (encrypted != null && encrypted.length != 0) output.write(encrypted);
    }

    private static SecretKey deriveLegacyKey(char[] password, byte[] salt, int iterations,
            int keyBits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(LEGACY_KDF);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static int readUnsignedByte(InputStream input) throws Exception {
        return input.read();
    }

    private static boolean readFully(InputStream input, byte[] destination) throws Exception {
        int offset = 0;
        while (offset < destination.length) {
            int count = input.read(destination, offset, destination.length - offset);
            if (count < 0) return false;
            if (count == 0) continue;
            offset += count;
        }
        return true;
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static int fromIntBytes(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static void readAuthenticated(Context context, Uri uri, char[] password, InputStage stage)
            throws Exception {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new Exception("null input stream");
            try (BackupFrames.AuthenticatedInputStream authenticated =
                         BackupFrames.openInputStream(raw, password);
                    InputStreamReader reader = new InputStreamReader(authenticated,
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT));
                    JsonReader json = new JsonReader(reader)) {
                json.setLenient(false);
                parsePayload(json, stage);

                // Parsing an object is not enough. JsonReader must observe END_DOCUMENT so it reads
                // through all plaintext, and the frame reader must then authenticate the footer and
                // verify physical EOF. A valid JSON prefix is never sufficient.
                if (json.peek() != JsonToken.END_DOCUMENT)
                    throw new Exception("trailing JSON in backup");
                authenticated.finish();
            }
        }
    }

    private static void parseLegacyPayload(JsonReader json, InputStage stage) throws Exception {
        int seen = 0;
        int format = -1;
        json.beginObject();
        while (json.hasNext()) {
            String name = json.nextName();
            int bit;
            switch (name) {
                case "payloadFormat":
                    bit = 1;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy payload format");
                    seen |= bit;
                    long value = exactLong(json);
                    if (value < 1 || value > 7) throw invalid("unsupported legacy payload format");
                    format = (int) value;
                    break;
                case "balances":
                    bit = 1 << 1;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy balances");
                    seen |= bit;
                    parseLegacyBalances(json, stage);
                    break;
                case "transactions":
                    bit = 1 << 2;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy transactions");
                    seen |= bit;
                    parseLegacyTransactions(json, stage);
                    break;
                case "txNotes":
                    bit = 1 << 3;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy notes");
                    seen |= bit;
                    parseLegacyTextMap(json, stage, NOTES);
                    break;
                case "txReasons":
                    bit = 1 << 4;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy reasons");
                    seen |= bit;
                    parseLegacyTextMap(json, stage, REASONS);
                    break;
                case "txChannels":
                    bit = 1 << 5;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy channels");
                    seen |= bit;
                    parseLegacyTextMap(json, stage, CHANNELS);
                    break;
                case "txTags":
                    bit = 1 << 6;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy tags");
                    seen |= bit;
                    parseLegacyTags(json, stage);
                    break;
                case "commitments":
                    bit = 1 << 7;
                    if ((seen & bit) != 0) throw invalid("duplicate legacy commitments");
                    seen |= bit;
                    parseLegacyCommitments(json, stage);
                    break;
                default:
                    throw invalid("unknown legacy payload field");
            }
        }
        json.endObject();
        // Like the original importer, a section the payload omits leaves the local store
        // untouched. Only the format marker and balances are mandatory; unknown fields were
        // already rejected above, and the GCM tag guarantees the payload is complete.
        if (format < 1 || (seen & (1 | (1 << 1))) != (1 | (1 << 1)))
            throw invalid("incomplete legacy payload");
    }

    private static void parseLegacyBalances(JsonReader json, InputStage stage) throws Exception {
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            String name = null;
            String sender = null;
            String account = null;
            long amount = 0;
            long date = 0;
            int seen = 0;
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "amount":
                        seen = once(seen, 1, "duplicate legacy balance amount");
                        amount = exactLong(json);
                        break;
                    case "date":
                        seen = once(seen, 1 << 1, "duplicate legacy balance date");
                        date = exactLong(json);
                        break;
                    case "sender":
                        seen = once(seen, 1 << 2, "duplicate legacy balance sender");
                        sender = requiredString(json);
                        break;
                    case "account":
                        seen = once(seen, 1 << 3, "duplicate legacy balance account");
                        account = optionalString(json);
                        break;
                    case "name":
                        seen = once(seen, 1 << 4, "duplicate legacy balance name");
                        name = requiredString(json);
                        break;
                    default:
                        throw invalid("unknown legacy balance field");
                }
            }
            json.endObject();
            if ((seen & 7) != 7) throw invalid("missing legacy balance field");
            if (name == null) name = BalanceData.bankOfKey(key);
            if (account == null && key != null) account = accountFromStorageKey(key);
            requireBalanceIdentity(key, name, account, sender, date);
            JSONObject row = new JSONObject().put("key", key).put("name", name)
                .put("amount", amount).put("date", date).put("sender", sender);
            if (account != null) row.put("account", account);
            stage.addBalance(key, row.toString());
        }
        json.endObject();
    }

    private static void parseLegacyTransactions(JsonReader json, InputStage stage) throws Exception {
        int seen = 0;
        json.beginObject();
        while (json.hasNext()) {
            String field = json.nextName();
            if (!"transactions".equals(field)) throw invalid("unknown legacy transaction field");
            seen = once(seen, 1, "duplicate legacy transaction array");
            parseTransactionsArray(json, stage);
        }
        json.endObject();
        // An empty object carries no movements, mirroring the original importer.
    }

    private static void parseLegacyTextMap(JsonReader json, InputStage stage, int kind)
            throws Exception {
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            requireMetadataKey(key);
            String text = requiredString(json);
            if (text.isEmpty()) throw invalid("empty legacy metadata text");
            stage.addMetadata(kind, key, new JSONObject().put("key", key)
                .put("text", text).toString());
        }
        json.endObject();
    }

    private static void parseLegacyTags(JsonReader json, InputStage stage) throws Exception {
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            requireMetadataKey(key);
            List<String> tags = readTags(json);
            if (tags.isEmpty()) throw invalid("empty legacy metadata tags");
            JSONArray values = new JSONArray();
            for (String tag : tags) values.put(tag);
            stage.addMetadata(TAGS, key, new JSONObject().put("key", key)
                .put("tags", values).toString());
        }
        json.endObject();
    }

    private static void requireMetadataKey(String key) throws Exception {
        if (key == null || key.isEmpty() || key.length() > 1024)
            throw invalid("invalid metadata key");
    }

    private static String accountFromStorageKey(String key) throws Exception {
        int separator = key.indexOf('|');
        if (separator < 0) return null;
        if (separator == 0 || separator != key.lastIndexOf('|') || separator == key.length() - 1)
            throw invalid("invalid balance key");
        return key.substring(separator + 1);
    }

    private static void parsePayload(JsonReader json, InputStage stage) throws Exception {
        int seen = 0;
        json.beginObject();
        while (json.hasNext()) {
            String name = json.nextName();
            int bit;
            switch (name) {
                case "schema":
                    bit = SECTION_SCHEMA;
                    if ((seen & bit) != 0) throw invalid("duplicate schema");
                    seen |= bit;
                    if (exactLong(json) != 2) throw invalid("unsupported backup schema");
                    break;
                case "balances":
                    bit = SECTION_BALANCES;
                    if ((seen & bit) != 0) throw invalid("duplicate balances section");
                    seen |= bit;
                    parseBalances(json, stage);
                    break;
                case "transactions":
                    bit = SECTION_TRANSACTIONS;
                    if ((seen & bit) != 0) throw invalid("duplicate transactions section");
                    seen |= bit;
                    parseTransactions(json, stage);
                    break;
                case "metadata":
                    bit = SECTION_METADATA;
                    if ((seen & bit) != 0) throw invalid("duplicate metadata section");
                    seen |= bit;
                    parseMetadata(json, stage);
                    break;
                case "commitments":
                    bit = SECTION_COMMITMENTS;
                    if ((seen & bit) != 0) throw invalid("duplicate commitments section");
                    seen |= bit;
                    parseCommitments(json, stage);
                    break;
                case "sources":
                    bit = SECTION_SOURCES;
                    if ((seen & bit) != 0) throw invalid("duplicate sources section");
                    seen |= bit;
                    parseSources(json, stage);
                    break;
                case "commitmentDeletions":
                    bit = SECTION_COMMITMENT_DELETIONS;
                    if ((seen & bit) != 0) throw invalid("duplicate commitment deletions");
                    seen |= bit;
                    parseCommitmentDeletions(json, stage);
                    break;
                default:
                    throw invalid("unknown backup section");
            }
        }
        json.endObject();
        if ((seen & ALL_SECTIONS) != ALL_SECTIONS
                || (seen & ~ (ALL_SECTIONS | SECTION_SOURCES | SECTION_COMMITMENT_DELETIONS)) != 0)
            throw invalid("incomplete backup sections");
    }

    private static void parseBalances(JsonReader json, InputStage stage) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            String key = null;
            String name = null;
            String sender = null;
            String account = null;
            long amount = 0;
            long date = 0;
            int seen = 0;

            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "key":
                        seen = once(seen, 1, "duplicate balance key");
                        key = requiredString(json);
                        break;
                    case "name":
                        seen = once(seen, 1 << 1, "duplicate balance name");
                        name = requiredString(json);
                        break;
                    case "amount":
                        seen = once(seen, 1 << 2, "duplicate balance amount");
                        amount = exactLong(json);
                        break;
                    case "date":
                        seen = once(seen, 1 << 3, "duplicate balance date");
                        date = exactLong(json);
                        break;
                    case "sender":
                        seen = once(seen, 1 << 4, "duplicate balance sender");
                        sender = requiredString(json);
                        break;
                    case "account":
                        seen = once(seen, 1 << 5, "duplicate balance account");
                        account = optionalString(json);
                        break;
                    default:
                        throw invalid("unknown balance field");
                }
            }
            json.endObject();
            if ((seen & 0x1f) != 0x1f) throw invalid("missing balance field");
            requireBalanceIdentity(key, name, account, sender, date);

            JSONObject row = new JSONObject().put("key", key).put("name", name)
                .put("amount", amount).put("date", date).put("sender", sender);
            if (account != null) row.put("account", account);
            stage.addBalance(key, row.toString());
        }
        json.endArray();
    }

    private static void parseTransactions(JsonReader json, InputStage stage) throws Exception {
        parseTransactionsArray(json, stage);
    }

    private static void parseTransactionsArray(JsonReader json, InputStage stage) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            String bank = null;
            String account = null;
            String sig = null;
            String content = null;
            long date = 0;
            long amount = 0;
            Long balance = null;
            int seen = 0;

            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                int bit;
                switch (field) {
                    case "bank": bit = TRANSACTION_BANK; break;
                    case "date": bit = TRANSACTION_DATE; break;
                    case "amount": bit = TRANSACTION_AMOUNT; break;
                    case "account": bit = TRANSACTION_ACCOUNT; break;
                    case "bal": bit = TRANSACTION_BALANCE; break;
                    case "sig": bit = TRANSACTION_SIG; break;
                    case "content": bit = TRANSACTION_CONTENT; break;
                    default: throw invalid("unknown transaction field");
                }
                if ((seen & bit) != 0) throw invalid("duplicate transaction field");
                seen |= bit;
                switch (field) {
                    case "bank": bank = requiredString(json); break;
                    case "date": date = exactLong(json); break;
                    case "amount": amount = exactLong(json); break;
                    case "account": account = optionalString(json); break;
                    case "bal":
                        balance = optionalLong(json);
                        break;
                    case "sig": sig = optionalString(json); break;
                    case "content": content = optionalString(json); break;
                    default: throw new AssertionError(field);
                }
            }
            json.endObject();
            if ((seen & (TRANSACTION_BANK | TRANSACTION_DATE | TRANSACTION_AMOUNT))
                    != (TRANSACTION_BANK | TRANSACTION_DATE | TRANSACTION_AMOUNT)) {
                throw invalid("missing transaction field");
            }
            requireTransaction(bank, account, date, sig, content);
            stage.add(TRANSACTION,
                BalanceData.transactionJson(new Transaction(bank, account, date, amount,
                    balance, sig, content)).toString());
        }
        json.endArray();
    }

    private static void parseMetadata(JsonReader json, InputStage stage) throws Exception {
        int seen = 0;
        json.beginObject();
        while (json.hasNext()) {
            String kind = json.nextName();
            int bit;
            int stageKind;
            if ("notes".equals(kind)) { bit = 1; stageKind = NOTES; }
            else if ("reasons".equals(kind)) { bit = 1 << 1; stageKind = REASONS; }
            else if ("channels".equals(kind)) { bit = 1 << 2; stageKind = CHANNELS; }
            else if ("tags".equals(kind)) { bit = 1 << 3; stageKind = TAGS; }
            else throw invalid("unknown metadata kind");
            if ((seen & bit) != 0) throw invalid("duplicate metadata kind");
            seen |= bit;
            parseMetadataRows(json, stage, stageKind, stageKind == TAGS);
        }
        json.endObject();
        if (seen != 0x0f) throw invalid("incomplete metadata");
    }

    private static void parseMetadataRows(JsonReader json, InputStage stage, int kind,
            boolean tags) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            String key = null;
            String text = null;
            List<String> tagValues = null;
            int seen = 0;
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                if ("key".equals(field)) {
                    seen = once(seen, 1, "duplicate metadata key");
                    key = requiredString(json);
                } else if (!tags && "text".equals(field)) {
                    seen = once(seen, 1 << 1, "duplicate metadata text");
                    text = requiredString(json);
                } else if (tags && "tags".equals(field)) {
                    seen = once(seen, 1 << 1, "duplicate metadata tags");
                    tagValues = readTags(json);
                } else {
                    throw invalid("unknown metadata field");
                }
            }
            json.endObject();
            if ((seen & 3) != 3) throw invalid("invalid metadata row");
            requireMetadataKey(key);
            JSONObject row = new JSONObject().put("key", key);
            if (tags) {
                if (tagValues == null || tagValues.isEmpty()) throw invalid("empty metadata tags");
                JSONArray values = new JSONArray();
                for (String tag : tagValues) values.put(tag);
                row.put("tags", values);
            } else {
                if (text.isEmpty()) throw invalid("empty metadata text");
                row.put("text", text);
            }
            stage.addMetadata(kind, key, row.toString());
        }
        json.endArray();
    }

    private static List<String> readTags(JsonReader json) throws Exception {
        List<String> values = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            String tag = requiredString(json);
            if (tag.trim().isEmpty()) throw invalid("empty metadata tag");
            values.add(tag);
        }
        json.endArray();
        return values;
    }

    private static void parseCommitments(JsonReader json, InputStage stage) throws Exception {
        json.beginArray();
        while (json.hasNext()) parseCommitment(json, stage);
        json.endArray();
    }

    /** Reads explicitly deleted commitment ids into encrypted staging. */
    private static void parseCommitmentDeletions(JsonReader json, InputStage stage) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            String id = requiredString(json);
            if (id.isEmpty()) throw invalid("invalid commitment deletion");
            stage.addCommitmentDeletion(id);
        }
        json.endArray();
    }

    /** Reads legacy commitments in either envelope shape: the {@code {"commitments":[...]}} object
     *  written by old releases and the bare array used by some fixtures. Entries share the v2 shape. */
    private static void parseLegacyCommitments(JsonReader json, InputStage stage) throws Exception {
        if (json.peek() == JsonToken.BEGIN_ARRAY) {
            json.beginArray();
            while (json.hasNext()) parseCommitment(json, stage);
            json.endArray();
            return;
        }
        json.beginObject();
        if (!json.hasNext() || !"commitments".equals(json.nextName()))
            throw invalid("invalid legacy commitments");
        json.beginArray();
        while (json.hasNext()) parseCommitment(json, stage);
        json.endArray();
        if (json.hasNext()) throw invalid("unexpected legacy commitments field");
        json.endObject();
    }

    /** Reads one definition while emitting each settlement date immediately to encrypted staging. */
    private static void parseCommitment(JsonReader json, InputStage stage) throws Exception {
        long ordinal = stage.beginCommitment();
        String id = null;
        String name = null;
        long amount = 0;
        long frequency = 0;
        long start = 0;
        Long end = null;
        boolean done = false;
        long paidThrough = 0;
        boolean remind = false;
        long remindBefore = 0;
        boolean hasPaid = false;
        boolean hasUnpaid = false;
        int seen = 0;

        json.beginObject();
        while (json.hasNext()) {
            String field = json.nextName();
            int bit;
            switch (field) {
                case "id": bit = COMMITMENT_ID; break;
                case "name": bit = COMMITMENT_NAME; break;
                case "amount": bit = COMMITMENT_AMOUNT; break;
                case "freq": bit = COMMITMENT_FREQUENCY; break;
                case "start": bit = COMMITMENT_START; break;
                case "end": bit = COMMITMENT_END; break;
                case "done": bit = COMMITMENT_DONE; break;
                case "paidThrough": bit = COMMITMENT_PAID_THROUGH; break;
                case "remind": bit = COMMITMENT_REMIND; break;
                case "remindBefore": bit = COMMITMENT_REMIND_BEFORE; break;
                case "paid": bit = COMMITMENT_PAID; break;
                case "unpaid": bit = COMMITMENT_UNPAID; break;
                default: throw invalid("unknown commitment field");
            }
            if ((seen & bit) != 0) throw invalid("duplicate commitment field");
            seen |= bit;
            switch (field) {
                case "id": id = requiredString(json); break;
                case "name": name = requiredString(json); break;
                case "amount": amount = exactLong(json); break;
                case "freq": frequency = exactLong(json); break;
                case "start": start = exactLong(json); break;
                case "end": end = optionalLong(json); break;
                case "done": done = requiredBoolean(json); break;
                case "paidThrough": paidThrough = exactLong(json); break;
                case "remind": remind = requiredBoolean(json); break;
                case "remindBefore": remindBefore = exactLong(json); break;
                case "paid":
                    hasPaid = readSettlementDates(json, stage, ordinal, true);
                    break;
                case "unpaid":
                    hasUnpaid = readSettlementDates(json, stage, ordinal, false);
                    break;
                default: throw new AssertionError(field);
            }
        }
        json.endObject();

        int required = COMMITMENT_ID | COMMITMENT_NAME | COMMITMENT_AMOUNT
            | COMMITMENT_FREQUENCY | COMMITMENT_START;
        if ((seen & required) != required) throw invalid("missing commitment field");
        if (id.isEmpty() || name.trim().isEmpty() || frequency < Commitment.ONCE
                || frequency > Commitment.YEARLY || frequency > Integer.MAX_VALUE
                || start <= 0 || paidThrough < 0 || remindBefore < 0) {
            throw invalid("invalid commitment definition");
        }
        if (frequency == Commitment.ONCE && (hasPaid || hasUnpaid
                || paidThrough != 0)) {
            throw invalid("one-time commitment has recurring settlement state");
        }
        if (frequency != Commitment.ONCE && done) {
            throw invalid("recurring commitment has one-time state");
        }

        Commitment definition = Commitment.normalized(new Commitment(id, name, amount,
            (int) frequency, start, end, done, java.util.Collections.emptyList(), paidThrough,
            java.util.Collections.emptyList(), remind, remindBefore));
        if (definition == null) throw invalid("invalid commitment definition");
        stage.addCommitmentDefinition(ordinal, definition);
    }

    private static boolean readSettlementDates(JsonReader json, InputStage stage, long ordinal,
            boolean settled) throws Exception {
        boolean hasDate = false;
        json.beginArray();
        while (json.hasNext()) {
            long date = exactLong(json);
            if (date <= 0) throw invalid("invalid settlement date");
            hasDate = true;
            stage.addCommitmentEvent(ordinal, date, settled);
        }
        json.endArray();
        return hasDate;
    }

    private static void parseSources(JsonReader json, InputStage stage) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            String sender = null;
            String body = null;
            long arrival = -1;
            int rule = -1;
            org.json.JSONObject row = new org.json.JSONObject();
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "sender": sender = requiredString(json); row.put("sender", sender); break;
                    case "body":
                        if (json.peek() == JsonToken.NULL) { json.nextNull(); body = null; }
                        else { body = requiredString(json); }
                        row.put("body", body == null ? org.json.JSONObject.NULL : body);
                        break;
                    case "arrival": arrival = exactLong(json); row.put("arrival", arrival); break;
                    case "rule": rule = (int) exactLong(json); row.put("rule", rule); break;
                    case "transactions":
                        row.put("transactions", readSourceTransactions(json));
                        break;
                    case "balances":
                        row.put("balances", readSourceBalances(json));
                        break;
                    default: throw invalid("unknown source field");
                }
            }
            json.endObject();
            if (sender == null || sender.isEmpty() || arrival < 0 || rule < 0)
                throw invalid("invalid source row");
            stage.add(SOURCE, row.toString());
        }
        json.endArray();
    }

    private static org.json.JSONArray readSourceTransactions(JsonReader json) throws Exception {
        org.json.JSONArray out = new org.json.JSONArray();
        json.beginArray();
        while (json.hasNext()) {
            org.json.JSONObject row = new org.json.JSONObject();
            boolean parser = false, bank = false, date = false, amount = false;
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "parser": row.put("parser", exactLong(json)); parser = true; break;
                    case "bank": row.put("bank", requiredString(json)); bank = true; break;
                    case "date": row.put("date", exactLong(json)); date = true; break;
                    case "amount": row.put("amount", exactLong(json)); amount = true; break;
                    case "account": row.put("account", optionalString(json)); break;
                    case "bal": row.put("bal", optionalLong(json)); break;
                    case "sig": row.put("sig", optionalString(json)); break;
                    case "content": row.put("content", optionalString(json)); break;
                    default: throw invalid("unknown source transaction field");
                }
            }
            json.endObject();
            if (!parser || !bank || !date || !amount) throw invalid("invalid source transaction");
            out.put(row);
        }
        json.endArray();
        return out;
    }

    private static org.json.JSONArray readSourceBalances(JsonReader json) throws Exception {
        org.json.JSONArray out = new org.json.JSONArray();
        json.beginArray();
        while (json.hasNext()) {
            org.json.JSONObject row = new org.json.JSONObject();
            boolean parser = false, bank = false, date = false, balance = false;
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "parser": row.put("parser", exactLong(json)); parser = true; break;
                    case "bank": row.put("bank", requiredString(json)); bank = true; break;
                    case "date": row.put("date", exactLong(json)); date = true; break;
                    case "balance": row.put("balance", exactLong(json)); balance = true; break;
                    case "account": row.put("account", optionalString(json)); break;
                    default: throw invalid("unknown source balance field");
                }
            }
            json.endObject();
            if (!parser || !bank || !date || !balance) throw invalid("invalid source balance");
            out.put(row);
        }
        json.endArray();
        return out;
    }

    private static BackupManager.RestoreResult apply(Context context, InputStage stage,
            java.util.Set<BackupManager.Section> selection) throws Exception {
        if (selection == null) selection = BackupManager.allSections();
        BackupManager.RestoreResult result = new BackupManager.RestoreResult();
        if (selection.contains(BackupManager.Section.SOURCES)) mergeSources(context, stage, result);
        if (selection.contains(BackupManager.Section.BALANCES))
            mergeBalances(context, stage, result);

        if (selection.contains(BackupManager.Section.TRANSACTIONS)) {
            TransactionStore.MergeResult merged = TransactionStore.mergeResult(context,
                visitor -> stage.forEach(TRANSACTION,
                    payload -> visitor.accept(readTransaction(payload))),
                stage::addAlias);
            result.transactionsAdded = Math.toIntExact(merged.added);
        }

        if (selection.contains(BackupManager.Section.NOTES))
            mergeMetadata(context, stage, NOTES, MetadataStore.NOTES, false, result);
        if (selection.contains(BackupManager.Section.REASONS))
            mergeMetadata(context, stage, REASONS, MetadataStore.REASONS, false, result);
        if (selection.contains(BackupManager.Section.CHANNELS))
            mergeMetadata(context, stage, CHANNELS, MetadataStore.CHANNELS, false, result);
        if (selection.contains(BackupManager.Section.TAGS))
            mergeMetadata(context, stage, TAGS, MetadataStore.TAGS, true, result);

        if (selection.contains(BackupManager.Section.COMMITMENTS)) {
            mergeCommitments(context, stage, result);
            mergeCommitmentDeletions(context, stage, result);
        }
        return result;
    }

    private static void mergeSources(Context context, InputStage stage,
            BackupManager.RestoreResult result) throws Exception {
        stage.forEach(SOURCE, payload -> {
            org.json.JSONObject row = new org.json.JSONObject(payload);
            String sender = row.getString("sender");
            String body = row.isNull("body") ? null : row.getString("body");
            long arrival = row.getLong("arrival");
            int rule = row.getInt("rule");
            SourceStore.Source source = SourceStore.capture(context, sender, body, arrival, rule);
            if (source == null) return;
            org.json.JSONArray transactions = row.optJSONArray("transactions");
            if (transactions != null) {
                for (int i = 0; i < transactions.length(); i++) {
                    org.json.JSONObject tx = transactions.getJSONObject(i);
                    Long bal = tx.isNull("bal") ? null : tx.getLong("bal");
                    Transaction transaction = new Transaction(tx.getString("bank"),
                        tx.isNull("account") ? null : tx.getString("account"),
                        tx.getLong("date"), tx.getLong("amount"), bal,
                        tx.isNull("sig") ? null : tx.getString("sig"),
                        tx.isNull("content") ? null : tx.getString("content"));
                    SourceStore.observeTransaction(context, source, tx.getInt("parser"),
                        transaction);
                }
            }
            org.json.JSONArray balances = row.optJSONArray("balances");
            if (balances != null) {
                for (int i = 0; i < balances.length(); i++) {
                    org.json.JSONObject bal = balances.getJSONObject(i);
                    SourceStore.observeBalance(context, source, bal.getInt("parser"),
                        bal.getString("bank"),
                        bal.isNull("account") ? null : bal.getString("account"),
                        bal.getLong("date"), bal.getLong("balance"));
                }
            }
            result.metadataChanged = true;
        });
    }

    private static void mergeBalances(Context context, InputStage stage,
            BackupManager.RestoreResult result) throws Exception {
        LinkedHashMap<String, Bank> current = BalanceData.read(context);
        LinkedHashMap<String, Bank> merged = new LinkedHashMap<>(current);
        stage.forEach(BALANCE, payload -> {
            JSONObject row = new JSONObject(payload);
            String key = row.getString("key");
            String name = row.getString("name");
            String account = row.has("account") && !row.isNull("account")
                ? row.getString("account") : null;
            Bank incoming = new Bank(name, row.getLong("amount"), row.getLong("date"),
                row.getString("sender"), account);
            Bank old = merged.get(key);
            // Later rows do not replace a newer row. Equal dates keep the first occurrence, making
            // duplicate backup keys deterministic while still selecting the newest record.
            if (old == null || incoming.date > old.date) merged.put(key, incoming);
        });

        for (java.util.Map.Entry<String, Bank> entry : merged.entrySet()) {
            String key = entry.getKey();
            Bank incoming = entry.getValue();
            Bank old = current.get(key);
            if (old == null) {
                result.added = Math.incrementExact(result.added);
            } else if (incoming.date > old.date) {
                result.updated = Math.incrementExact(result.updated);
            }
        }
        if (result.added == 0 && result.updated == 0) return;
        BalanceData.write(context, merged);
        LinkedHashMap<String, Bank> written = BalanceData.read(context);
        if (!sameBalances(merged, written)) throw new Exception("balance merge was not persisted");
    }

    private static boolean sameBalances(LinkedHashMap<String, Bank> expected,
            LinkedHashMap<String, Bank> actual) {
        if (expected.size() != actual.size()) return false;
        for (java.util.Map.Entry<String, Bank> entry : expected.entrySet()) {
            Bank a = entry.getValue();
            Bank b = actual.get(entry.getKey());
            if (b == null || !same(a.name, b.name) || !same(a.sender, b.sender)
                    || !same(a.account, b.account) || a.amount != b.amount || a.date != b.date) {
                return false;
            }
        }
        return true;
    }

    private static void mergeMetadata(Context context, InputStage stage, int inputKind,
            int storeKind, boolean tags, BackupManager.RestoreResult result) throws Exception {
        stage.forEach(inputKind, payload -> {
            JSONObject row = new JSONObject(payload);
            String key = stage.resolveAlias(row.getString("key"));
            if (tags) {
                JSONArray values = row.getJSONArray("tags");
                List<String> incoming = new ArrayList<>(values.length());
                for (int i = 0; i < values.length(); i++) incoming.add(values.getString(i));
                result.metadataChanged |= MetadataStore.mergeTagsEntry(context, key, incoming);
            } else {
                result.metadataChanged |= MetadataStore.mergeTextEntry(context, storeKind, key,
                    row.getString("text"));
            }
        });
    }

    /**
     * Merges definitions and settlement events without ever constructing a paid/unpaid history
     * list. The coordinator's streaming helper is intentionally called on the editor: it must
     * inspect only the explicit settlement row for this date, preserving a local explicit state and
     * adding an incoming mark only when no local explicit state exists. A legacy paidThrough
     * watermark is not expanded into history.
     */
    private static void mergeCommitments(Context context, InputStage stage,
            BackupManager.RestoreResult result) throws Exception {
        CommitmentStore.runInTransaction(context, editor -> {
            stage.forEachCommitment((ordinal, definition) -> {
                final boolean[] changed = {false};
                Commitment local = editor.get(definition.id);
                // An explicitly deleted definition stays deleted: delete wins over any older
                // backup, and tombstones travel forward in every newer backup.
                if (local == null && editor.isDeleted(definition.id)) return;
                boolean newDefinition = local == null;
                if (local == null) {
                    editor.upsert(definition);
                    changed[0] = true;
                    local = definition;
                } else if (local.frequency != Commitment.ONCE
                        && definition.frequency != Commitment.ONCE
                        && definition.legacyPaidThrough > local.legacyPaidThrough) {
                    // Keep every local definition field and explicit lazy settlement list, but do
                    // preserve an older backup's recurring watermark additively. The store reuses
                    // the lazy lists rather than materializing them during this upsert.
                    Commitment watermark = new Commitment(local.id, local.name, local.amount,
                        local.frequency, local.start, local.end, local.done, local.paid,
                        definition.legacyPaidThrough, local.unpaid, local.remind,
                        local.remindBeforeMs);
                    editor.upsert(watermark);
                    changed[0] = true;
                    local = watermark;
                }
                // An existing one-time definition is local-owned even if the incoming definition
                // has a different frequency. There are no valid events for a new one-time row; skip
                // incompatible incoming events rather than changing the local definition's meaning.
                if (local.frequency != Commitment.ONCE) {
                    stage.forEachCommitmentEvent(ordinal, (date, settled) -> {
                        if (editor.mergeSettlementIfAbsent(definition.id, date, settled))
                            changed[0] = true;
                    });
                }
                if (newDefinition) result.commitmentsAdded =
                    Math.incrementExact(result.commitmentsAdded);
                if (changed[0]) result.metadataChanged = true;
            });
            return null;
        });
    }

    /**
     * Unions the backup's deletion tombstones into the local store after definitions merge,
     * removing any local definition the tombstone covers. Delete wins: a definition the user
     * erased is never resurrected by an older backup, and the tombstone is kept so still-older
     * backups cannot resurrect it either.
     */
    private static void mergeCommitmentDeletions(Context context, InputStage stage,
            BackupManager.RestoreResult result) throws Exception {
        CommitmentStore.runInTransaction(context, editor -> {
            final boolean[] changed = {false};
            stage.forEachCommitmentDeletion(id -> {
                if (editor.delete(id)) changed[0] = true;
                if (editor.noteDeletion(id)) changed[0] = true;
            });
            if (changed[0]) result.metadataChanged = true;
            return null;
        });
    }

    private static Transaction readTransaction(String payload) throws Exception {
        JSONObject row = new JSONObject(payload);
        String bank = row.getString("bank");
        String account = row.has("account") && !row.isNull("account")
            ? row.getString("account") : null;
        Long balance = row.has("bal") && !row.isNull("bal") ? row.getLong("bal") : null;
        String sig = row.has("sig") && !row.isNull("sig") ? row.getString("sig") : null;
        String content = row.has("content") && !row.isNull("content")
            ? row.getString("content") : null;
        long date = row.getLong("date");
        long amount = row.getLong("amount");
        requireTransaction(bank, account, date, sig, content);
        return new Transaction(bank, account, date, amount, balance, sig, content);
    }

    private static void requireBalanceIdentity(String key, String name, String account,
            String sender, long date) throws Exception {
        if (key == null || key.isEmpty() || name == null || name.isEmpty() || sender == null
                || sender.isEmpty() || date < 0 || name.indexOf('|') >= 0
                || account != null && (account.isEmpty() || account.indexOf('|') >= 0)
                || !key.equals(BalanceData.storageKey(name, account))) {
            throw invalid("invalid balance identity");
        }
    }

    private static void requireTransaction(String bank, String account, long date, String sig,
            String content) throws Exception {
        if (bank == null || bank.isEmpty()) {
            throw invalid("invalid transaction");
        }
        try {
            TransactionStore.requireFieldLength(bank);
            TransactionStore.requireFieldLength(account);
            TransactionStore.requireFieldLength(sig);
            TransactionStore.requireFieldLength(content);
        } catch (IllegalArgumentException e) {
            throw invalid("transaction field too large");
        }
    }

    private static int once(int seen, int bit, String message) throws Exception {
        if ((seen & bit) != 0) throw invalid(message);
        return seen | bit;
    }

    private static String requiredString(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.STRING) throw invalid("expected string");
        return json.nextString();
    }

    private static String optionalString(JsonReader json) throws Exception {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        return requiredString(json);
    }

    /** Accepts only the JSON integer grammar and a value representable by Java long. */
    private static long exactLong(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.NUMBER) throw invalid("expected integer");
        String value = json.nextString();
        if (!value.matches("-?(0|[1-9][0-9]*)")) throw invalid("expected exact integer");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid("integer out of range");
        }
    }

    private static Long optionalLong(JsonReader json) throws Exception {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        return exactLong(json);
    }

    private static boolean requiredBoolean(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.BOOLEAN) throw invalid("expected boolean");
        return json.nextBoolean();
    }

    private static Exception invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private interface RowVisitor { void accept(String payload) throws Exception; }
    private interface CommitmentVisitor {
        void accept(long ordinal, Commitment definition) throws Exception;
    }
    private interface EventVisitor {
        void accept(long date, boolean settled) throws Exception;
    }
    private interface DeletionVisitor { void accept(String id) throws Exception; }

    /** Disposable encrypted input rows. Its only plaintext lifetime is one parser row. */
    private static final class InputStage implements AutoCloseable {
        final Context context;
        final String name;
        final Helper helper;
        final SQLiteDatabase db;
        final EncryptedRowCodec codec;
        final byte[] indexKey;
        final Mac index;
        long nextRow;
        long nextCommitment;
        long nextEvent;
        boolean inputCommitted;
        boolean closed;

        InputStage(Context context) throws Exception {
            this.context = context;
            name = DB_PREFIX + java.util.UUID.randomUUID().toString().replace('-', '_');
            helper = new Helper(context, name);
            SQLiteDatabase opened = null;
            EncryptedRowCodec openedCodec = null;
            byte[] secret = new byte[32];
            try {
                opened = helper.getWritableDatabase();
                opened.beginTransaction();
                openedCodec = EncryptedRowCodec.open(EncryptedRowCodec.createWrappedKey(),
                    INDEX_DOMAIN);
                new SecureRandom().nextBytes(secret);
                Mac madeIndex = Mac.getInstance("HmacSHA256");
                madeIndex.init(new SecretKeySpec(secret, "HmacSHA256"));
                db = opened;
                codec = openedCodec;
                indexKey = secret;
                index = madeIndex;
            } catch (Exception failure) {
                if (openedCodec != null) openedCodec.close();
                Arrays.fill(secret, (byte) 0);
                if (opened != null && opened.inTransaction()) opened.endTransaction();
                helper.close();
                context.deleteDatabase(name);
                throw failure;
            }
        }

        /** Rows larger than a cursor window are split into chunks and reassembled on read, so
         *  one very large record cannot overflow the cursor window. */
        void add(int kind, String payload) throws Exception {
            long ordinal = nextRow;
            nextRow = Math.incrementExact(nextRow);
            int start = 0;
            int seq = 0;
            do {
                int end = Math.min(payload.length(), start + ROW_CHUNK_CHARS);
                ContentValues values = new ContentValues();
                values.put("kind", kind);
                values.put("ordinal", ordinal);
                values.put("seq", seq);
                values.put("payload", codec.encrypt(payload.substring(start, end),
                    rowIdentity(kind, ordinal, seq)));
                db.insertOrThrow("rows", null, values);
                start = end;
                seq = Math.incrementExact(seq);
            } while (start < payload.length());
        }

        void addBalance(String key, String payload) throws Exception {
            ContentValues unique = new ContentValues();
            unique.put("key_digest", digest("balance\n" + key));
            try {
                db.insertOrThrow("balance_keys", null, unique);
            } catch (SQLiteConstraintException e) {
                throw invalid("duplicate balance key");
            }
            add(BALANCE, payload);
        }

        void addMetadata(int kind, String key, String payload) throws Exception {
            ContentValues unique = new ContentValues();
            unique.put("kind", kind);
            unique.put("key_digest", digest("metadata\n" + kind + "\n" + key));
            try {
                db.insertOrThrow("metadata_keys", null, unique);
            } catch (SQLiteConstraintException e) {
                throw invalid("duplicate metadata key");
            }
            add(kind, payload);
        }

        long beginCommitment() throws Exception {
            long ordinal = nextCommitment;
            nextCommitment = Math.incrementExact(nextCommitment);
            return ordinal;
        }

        void addCommitmentDefinition(long ordinal, Commitment definition) throws Exception {
            ContentValues values = new ContentValues();
            values.put("ordinal", ordinal);
            values.put("id_digest", digest("commitment\n" + definition.id));
            values.put("payload", codec.encrypt(definition.definitionJson().toString(),
                commitmentIdentity(ordinal)));
            try {
                db.insertOrThrow("commitment_definitions", null, values);
            } catch (SQLiteConstraintException e) {
                throw invalid("duplicate commitment id");
            }
        }

        void addCommitmentEvent(long commitmentOrdinal, long date, boolean settled)
                throws Exception {
            String eventDigest = digest("event\n" + commitmentOrdinal + "\n" + date);
            try (Cursor cursor = db.query("commitment_events", new String[]{"payload"},
                    "event_digest=?", new String[]{eventDigest}, null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    JSONObject old = new JSONObject(codec.decrypt(cursor.getString(0),
                        eventIdentity(commitmentOrdinal, eventDigest)));
                    if (old.getLong("date") != date || old.getBoolean("settled") != settled)
                        throw invalid("conflicting settlement date");
                    return;
                }
            }
            JSONObject event = new JSONObject().put("date", date).put("settled", settled);
            ContentValues values = new ContentValues();
            long eventOrdinal = nextEvent;
            nextEvent = Math.incrementExact(nextEvent);
            values.put("commitment_ordinal", commitmentOrdinal);
            values.put("ordinal", eventOrdinal);
            values.put("event_digest", eventDigest);
            values.put("payload", codec.encrypt(event.toString(),
                eventIdentity(commitmentOrdinal, eventDigest)));
            db.insertOrThrow("commitment_events", null, values);
        }

        void addCommitmentDeletion(String id) throws Exception {
            ContentValues values = new ContentValues();
            String idDigest = digest("commitment_deletion\n" + id);
            values.put("id_digest", idDigest);
            values.put("payload", codec.encrypt(id, deletionIdentity(idDigest)));
            try {
                db.insertOrThrow("commitment_deletions", null, values);
            } catch (SQLiteConstraintException e) {
                throw invalid("duplicate commitment deletion");
            }
        }

        void addAlias(String from, String to) throws Exception {
            if (from == null || to == null || from.isEmpty() || to.isEmpty())
                throw invalid("invalid transaction alias");
            if (from.equals(to)) return;
            String sourceDigest = digest("alias\n" + from);
            try (Cursor cursor = db.query("aliases", new String[]{"payload"},
                    "source_digest=?", new String[]{sourceDigest}, null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    JSONObject old = new JSONObject(codec.decrypt(cursor.getString(0),
                        aliasIdentity(sourceDigest)));
                    if (!to.equals(old.getString("to")))
                        throw invalid("conflicting transaction alias");
                    return;
                }
            }
            ContentValues values = new ContentValues();
            values.put("source_digest", sourceDigest);
            values.put("payload", codec.encrypt(new JSONObject().put("from", from)
                .put("to", to).toString(), aliasIdentity(sourceDigest)));
            db.insertOrThrow("aliases", null, values);
        }

        String resolveAlias(String source) throws Exception {
            // A merge alias targets the surviving stored row directly. TransactionStore never
            // replaces a survivor, so following chains or retaining an in-memory alias set would
            // both be unnecessary and could remap a valid survivor's own metadata incorrectly.
            String sourceDigest = digest("alias\n" + source);
            try (Cursor cursor = db.query("aliases", new String[]{"payload"},
                    "source_digest=?", new String[]{sourceDigest}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) return source;
                JSONObject alias = new JSONObject(codec.decrypt(cursor.getString(0),
                    aliasIdentity(sourceDigest)));
                if (!source.equals(alias.getString("from")))
                    throw invalid("transaction alias mismatch");
                return alias.getString("to");
            }
        }

        void commitInput() {
            if (inputCommitted) return;
            db.setTransactionSuccessful();
            db.endTransaction();
            inputCommitted = true;
        }

        void forEach(int kind, RowVisitor visitor) throws Exception {
            try (Cursor cursor = db.query("rows", new String[]{"ordinal", "seq", "payload"}, "kind=?",
                    new String[]{Integer.toString(kind)}, null, null, "ordinal ASC, seq ASC")) {
                long current = Long.MIN_VALUE;
                StringBuilder assembled = new StringBuilder();
                int expectedSeq = 0;
                while (cursor.moveToNext()) {
                    long ordinal = cursor.getLong(0);
                    int seq = cursor.getInt(1);
                    if (ordinal != current) {
                        if (current != Long.MIN_VALUE) throw invalid("missing staged row chunk");
                        current = ordinal;
                        expectedSeq = 0;
                    }
                    if (seq != expectedSeq) throw invalid("missing staged row chunk");
                    expectedSeq = Math.incrementExact(expectedSeq);
                    assembled.append(codec.decrypt(cursor.getString(2),
                        rowIdentity(kind, ordinal, seq)));
                    if (cursor.isLast() || peekOrdinal(cursor) != current) {
                        visitor.accept(assembled.toString());
                        assembled.setLength(0);
                        current = Long.MIN_VALUE;
                    }
                }
                if (current != Long.MIN_VALUE) throw invalid("missing staged row chunk");
            }
        }

        private static long peekOrdinal(Cursor cursor) throws Exception {
            if (!cursor.moveToNext()) return Long.MIN_VALUE;
            long next = cursor.getLong(0);
            cursor.moveToPrevious();
            return next;
        }

        void forEachCommitment(CommitmentVisitor visitor) throws Exception {
            try (Cursor cursor = db.query("commitment_definitions",
                    new String[]{"ordinal", "payload"}, null, null, null, null, "ordinal ASC")) {
                while (cursor.moveToNext()) {
                    long ordinal = cursor.getLong(0);
                    JSONObject payload = new JSONObject(codec.decrypt(cursor.getString(1),
                        commitmentIdentity(ordinal)));
                    Commitment definition = Commitment.fromJson(payload);
                    if (definition == null) throw invalid("invalid staged commitment");
                    visitor.accept(ordinal, definition);
                }
            }
        }

        void forEachCommitmentDeletion(DeletionVisitor visitor) throws Exception {
            try (Cursor cursor = db.query("commitment_deletions",
                    new String[]{"id_digest", "payload"}, null, null, null, null,
                    "rowid ASC")) {
                while (cursor.moveToNext()) {
                    String idDigest = cursor.getString(0);
                    String id = codec.decrypt(cursor.getString(1), deletionIdentity(idDigest));
                    if (id == null || id.isEmpty()
                            || !idDigest.equals(digest("commitment_deletion\n" + id)))
                        throw invalid("invalid staged commitment deletion");
                    visitor.accept(id);
                }
            }
        }

        void forEachCommitmentEvent(long commitmentOrdinal, EventVisitor visitor) throws Exception {
            try (Cursor cursor = db.query("commitment_events",
                    new String[]{"event_digest", "payload"},
                    "commitment_ordinal=?", new String[]{Long.toString(commitmentOrdinal)},
                    null, null, "ordinal ASC")) {
                while (cursor.moveToNext()) {
                    String eventDigest = cursor.getString(0);
                    JSONObject event = new JSONObject(codec.decrypt(cursor.getString(1),
                        eventIdentity(commitmentOrdinal, eventDigest)));
                    long date = Commitment.exactLong(event.get("date"));
                    Object state = event.get("settled");
                    if (date <= 0 || !(state instanceof Boolean) || !eventDigest.equals(
                            digest("event\n" + commitmentOrdinal + "\n" + date)))
                        throw invalid("invalid staged settlement");
                    visitor.accept(date, (Boolean) state);
                }
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (!inputCommitted) {
                try { db.endTransaction(); } catch (Exception ignored) { }
            }
            codec.close();
            Arrays.fill(indexKey, (byte) 0);
            helper.close();
            context.deleteDatabase(name);
        }

        private String digest(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            try {
                return Base64.encodeToString(index.doFinal(bytes), Base64.NO_WRAP);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

    }

    /** Staged row chunks stay well under the cursor window; rows reassemble per ordinal. */
    private static final int ROW_CHUNK_CHARS = 256 * 1024;

    private static String rowIdentity(int kind, long ordinal) {
        return "row\n" + kind + "\n" + ordinal + "\n0";
    }

    private static String rowIdentity(int kind, long ordinal, int seq) {
        return "row\n" + kind + "\n" + ordinal + "\n" + seq;
    }

    private static String commitmentIdentity(long ordinal) {
        return "commitment\n" + ordinal;
    }

    private static String eventIdentity(long ordinal, String digest) {
        return "event\n" + ordinal + "\n" + digest;
    }

    private static String deletionIdentity(String digest) {
        return "commitment_deletion\n" + digest;
    }

    private static String aliasIdentity(String digest) {
        return "alias\n" + digest;
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context, String name) { super(context, name, null, 1); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE rows (kind INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + " seq INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind, ordinal, seq))");
            db.execSQL("CREATE TABLE balance_keys (key_digest TEXT PRIMARY KEY)");
            db.execSQL("CREATE TABLE metadata_keys (kind INTEGER NOT NULL,"
                + " key_digest TEXT NOT NULL, PRIMARY KEY(kind, key_digest))");
            db.execSQL("CREATE TABLE commitment_definitions (ordinal INTEGER PRIMARY KEY,"
                + " id_digest TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE commitment_events (commitment_ordinal INTEGER NOT NULL,"
                + " ordinal INTEGER NOT NULL, event_digest TEXT NOT NULL UNIQUE,"
                + " payload TEXT NOT NULL, PRIMARY KEY(commitment_ordinal, ordinal))");
            db.execSQL("CREATE TABLE commitment_deletions (id_digest TEXT PRIMARY KEY,"
                + " payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX commitment_events_owner ON commitment_events(commitment_ordinal, ordinal)");
            db.execSQL("CREATE TABLE aliases (source_digest TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("unsupported backup staging schema");
        }
    }
}
