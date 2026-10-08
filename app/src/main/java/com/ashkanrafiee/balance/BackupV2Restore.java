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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import javax.crypto.Mac;
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

    private static final int SECTION_SCHEMA = 1;
    private static final int SECTION_BALANCES = 1 << 1;
    private static final int SECTION_TRANSACTIONS = 1 << 2;
    private static final int SECTION_METADATA = 1 << 3;
    private static final int SECTION_COMMITMENTS = 1 << 4;
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

    private BackupV2Restore() {}

    static BackupManager.RestoreResult restore(Context context, Uri uri, String password)
            throws Exception {
        if (password == null) throw new IllegalArgumentException("password");

        Context application = context.getApplicationContext();
        if (application == null) application = context;
        char[] chars = password.toCharArray();
        InputStage input = null;
        try {
            input = new InputStage(application);
            readAuthenticated(application, uri, chars, input);
            input.commitInput();

            final BackupManager.RestoreResult result;
            synchronized (BalanceData.class) {
                // DataGeneration copies the committed local selection into an isolated generation.
                // No BalanceData, transaction, metadata or commitment write below may use the live
                // context. Stage.publish() is the only operation that makes the merge visible.
                try (DataGeneration.Stage generation = DataGeneration.beginStage(application)) {
                    result = apply(generation.context(), input);
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
                default:
                    throw invalid("unknown backup section");
            }
        }
        json.endObject();
        if (seen != ALL_SECTIONS) throw invalid("incomplete backup sections");
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
            stage.add(BALANCE, row.toString());
        }
        json.endArray();
    }

    private static void parseTransactions(JsonReader json, InputStage stage) throws Exception {
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
            if ((seen & 3) != 3 || key.isEmpty()) throw invalid("invalid metadata row");
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

    private static BackupManager.RestoreResult apply(Context context, InputStage stage)
            throws Exception {
        BackupManager.RestoreResult result = new BackupManager.RestoreResult();
        mergeBalances(context, stage, result);

        TransactionStore.MergeResult merged = TransactionStore.mergeResult(context,
            visitor -> stage.forEach(TRANSACTION, payload -> visitor.accept(readTransaction(payload))),
            stage::addAlias);
        result.transactionsAdded = Math.toIntExact(merged.added);

        mergeMetadata(context, stage, NOTES, MetadataStore.NOTES, false, result);
        mergeMetadata(context, stage, REASONS, MetadataStore.REASONS, false, result);
        mergeMetadata(context, stage, CHANNELS, MetadataStore.CHANNELS, false, result);
        mergeMetadata(context, stage, TAGS, MetadataStore.TAGS, true, result);

        mergeCommitments(context, stage, result);
        return result;
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

        void add(int kind, String payload) throws Exception {
            long ordinal = nextRow;
            nextRow = Math.incrementExact(nextRow);
            ContentValues values = new ContentValues();
            values.put("kind", kind);
            values.put("ordinal", ordinal);
            values.put("payload", codec.encrypt(payload, rowIdentity(kind, ordinal)));
            db.insertOrThrow("rows", null, values);
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
            try (Cursor cursor = db.query("rows", new String[]{"ordinal", "payload"}, "kind=?",
                    new String[]{Integer.toString(kind)}, null, null, "ordinal ASC")) {
                while (cursor.moveToNext()) {
                    long ordinal = cursor.getLong(0);
                    visitor.accept(codec.decrypt(cursor.getString(1), rowIdentity(kind, ordinal)));
                }
            }
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

    private static String rowIdentity(int kind, long ordinal) {
        return "row\n" + kind + "\n" + ordinal;
    }

    private static String commitmentIdentity(long ordinal) {
        return "commitment\n" + ordinal;
    }

    private static String eventIdentity(long ordinal, String digest) {
        return "event\n" + ordinal + "\n" + digest;
    }

    private static String aliasIdentity(String digest) {
        return "alias\n" + digest;
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context, String name) { super(context, name, null, 1); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE rows (kind INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + " payload TEXT NOT NULL, PRIMARY KEY(kind, ordinal))");
            db.execSQL("CREATE TABLE metadata_keys (kind INTEGER NOT NULL,"
                + " key_digest TEXT NOT NULL, PRIMARY KEY(kind, key_digest))");
            db.execSQL("CREATE TABLE commitment_definitions (ordinal INTEGER PRIMARY KEY,"
                + " id_digest TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE commitment_events (commitment_ordinal INTEGER NOT NULL,"
                + " ordinal INTEGER NOT NULL, event_digest TEXT NOT NULL UNIQUE,"
                + " payload TEXT NOT NULL, PRIMARY KEY(commitment_ordinal, ordinal))");
            db.execSQL("CREATE INDEX commitment_events_owner ON commitment_events(commitment_ordinal, ordinal)");
            db.execSQL("CREATE TABLE aliases (source_digest TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("unsupported backup staging schema");
        }
    }
}
