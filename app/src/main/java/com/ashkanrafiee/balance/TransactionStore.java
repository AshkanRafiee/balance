package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Database-owned, encrypted transaction rows. Preferences are migration inputs, never ownership.
 * Reads fail on corruption instead of returning a partial history or falling back to stale data.
 *
 * <p>Replacement, migration and streaming merge are atomic. Visitors/producers run synchronously
 * inside the transaction and must not reenter this store. Traversal holds one cursor and transaction;
 * concurrent writers wait for it to finish. Separate pages are live reads unless continued with the
 * Page overload, which rejects a changed revision. List reads are compatibility helpers only.
 */
final class TransactionStore {
    static final String DB_NAME = "balance_transactions.db";
    static final String TABLE = "transactions";
    static final int MAX_PAGE_SIZE = 1_000;
    private static final int DEFAULT_PAGE_SIZE = 256;
    private static final int DB_VERSION = 2;
    private static final String META = "store_meta";
    private static final String READY = "ready";
    /** Ownership metadata from the original v1 schema; its presence identifies a legacy DB. */
    private static final String LEGACY_OWNER = "owner";
    private static final String INDEX_KEY = "index_key";
    private static final String ROW_KEY = "row_key";
    private static final String REVISION = "revision";
    private static final String ROW_DOMAIN = "transactions";
    private static final String PRESENCE_FILE = "balance_transactions.present";
    private static final String LEGACY_MANIFEST = "transactions_manifest_v2";
    private static final String LEGACY_PAGE_PREFIX = "transactions_page_v2_";
    private static final String[] ROW_COLUMNS = {"ordinal", "payload", "sig_digest",
        "content_digest", "legacy_digest"};
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private TransactionStore() {}

    static final class Page {
        final List<Transaction> rows;
        /** Long keyset cursor; nextOrdinal is retained for older callers. */
        final long nextId;
        final long nextOrdinal;
        final boolean hasMore;
        final String revision;

        private Page(List<Transaction> rows, long nextOrdinal, boolean hasMore, String revision) {
            this.rows = Collections.unmodifiableList(rows);
            this.nextId = nextOrdinal;
            this.nextOrdinal = nextOrdinal;
            this.hasMore = hasMore;
            this.revision = revision;
        }
    }

    interface Visitor { void accept(Transaction transaction) throws Exception; }
    interface StreamSource { void forEach(Visitor visitor) throws Exception; }
    /** Provisional metadata aliases: emitted before commit; discard them if merge throws. */
    interface AliasVisitor { void accept(String incomingKey, String survivingKey) throws Exception; }
    private interface Work<T> { T run(Session session) throws Exception; }

    static final class MergeResult {
        final long added;
        final long aliases;
        final long transactionsAdded;
        final long aliasesAdded;

        private MergeResult(long added, long aliases) {
            this.added = added;
            this.aliases = aliases;
            this.transactionsAdded = added;
            this.aliasesAdded = aliases;
        }
    }

    /** Strict compatibility read; null means no committed store or legacy input exists yet. */
    static List<Transaction> read(Context context) throws Exception {
        return access(context, false, true, false, s -> {
            if (s == null) return null;
            List<Transaction> rows = new ArrayList<>();
            s.visit(DEFAULT_PAGE_SIZE, rows::add);
            return rows;
        });
    }

    static long count(Context context) throws Exception {
        return access(context, false, true, false, s -> {
            if (s == null) return 0L;
            // COUNT(*) only validates SQLite's row structure. Walk the bounded batches as well so
            // callers cannot mistake a corrupt payload or lookup index for a valid count.
            s.visit(DEFAULT_PAGE_SIZE, transaction -> {});
            return rowCount(s.db);
        });
    }

    /** Live keyset page; use the Page overload when continuing a snapshot. Start at -1. */
    static Page page(Context context, int afterOrdinal, int limit) throws Exception {
        return page(context, (long) afterOrdinal, limit);
    }

    static Page page(Context context, long afterOrdinal, int limit) throws Exception {
        requirePageSize(limit);
        return access(context, false, true, false, s -> s == null ? null : s.page(afterOrdinal, limit));
    }

    /** Rejects changes (including clear, replacement and database recreation) before decoding. */
    static Page page(Context context, Page previous, int limit) throws Exception {
        if (previous == null) throw new NullPointerException("previous page");
        requirePageSize(limit);
        return access(context, false, true, false, s -> {
            if (s == null) throw new IllegalStateException("transaction store disappeared");
            if (!previous.revision.equals(s.revision))
                throw new IllegalStateException("transaction store changed; restart paging");
            return s.page(previous.nextOrdinal, limit);
        });
    }

    /** A single stable snapshot; at most pageSize decrypted transactions are retained. */
    static void forEach(Context context, int pageSize, Visitor visitor) throws Exception {
        requirePageSize(pageSize);
        if (visitor == null) throw new NullPointerException("visitor");
        access(context, false, true, false, s -> {
            if (s == null) return null;
            s.visit(pageSize, visitor);
            return null;
        });
    }

    static boolean replace(Context context, List<Transaction> transactions) throws Exception {
        if (transactions == null) throw new NullPointerException("transactions");
        return replace(context, visitor -> {
            for (Transaction transaction : transactions) visitor.accept(transaction);
        });
    }

    /** Consumes each row once, without a history-sized list, cap or plaintext staging table. */
    static boolean replace(Context context, StreamSource source) throws Exception {
        if (source == null) throw new NullPointerException("source");
        return access(context, true, false, false, s -> {
            s.db.delete(TABLE, null, null);
            final long[] next = {0};
            consume(source, transaction -> s.insert(next[0]++, transaction));
            s.touch();
            return true;
        });
    }

    static long merge(Context context, StreamSource source) throws Exception {
        return mergeResult(context, source, null).added;
    }

    /**
     * Local-first union, indexed by content, signature and legacy bank/date/amount/account identity.
     * Legacy matching applies when either row has no signature; different known signatures are not
     * collapsed merely because their parsed fields coincide. New rows append in source order and
     * also participate in deduplication. Callback/source failure rolls back the entire import.
     */
    static long merge(Context context, StreamSource source, AliasVisitor aliases) throws Exception {
        return mergeResult(context, source, aliases).added;
    }

    static MergeResult mergeResult(Context context, StreamSource source) throws Exception {
        return mergeResult(context, source, null);
    }

    static MergeResult mergeResult(Context context, StreamSource source, AliasVisitor aliases)
            throws Exception {
        if (source == null) throw new NullPointerException("source");
        return access(context, true, true, false, s -> {
            // Validate local payloads and their indexes before trusting them to deduplicate a backup.
            s.visit(DEFAULT_PAGE_SIZE, transaction -> {});
            final long[] next = {s.nextOrdinal()};
            final long[] added = {0};
            final long[] aliasCount = {0};
            consume(source, incoming -> {
                requireTransaction(incoming);
                Transaction existing = s.find(incoming);
                if (existing == null) {
                    if (next[0] < 0) throw new IllegalStateException("transaction ordinal exhausted");
                    s.insert(next[0], incoming);
                    next[0] = next[0] == Long.MAX_VALUE ? -1 : next[0] + 1;
                    added[0]++;
                } else {
                    String from = BalanceData.noteKey(incoming);
                    String to = BalanceData.noteKey(existing);
                    if (!from.equals(to)) {
                        aliasCount[0]++;
                        if (aliases != null) aliases.accept(from, to);
                    }
                }
            });
            if (added[0] != 0) s.touch();
            return new MergeResult(added[0], aliasCount[0]);
        });
    }

    static MergeResult importRows(Context context, StreamSource source, AliasVisitor aliases)
            throws Exception {
        return mergeResult(context, source, aliases);
    }

    static MergeResult importRows(Context context, StreamSource source) throws Exception {
        return mergeResult(context, source, null);
    }

    /** Migrates legacy input only when no committed store exists; never replaces existing rows. */
    static boolean migrateLegacy(Context context) throws Exception {
        return access(context, false, true, false, s -> s != null && s.migrated);
    }

    /** Explicit reset. Failure is surfaced to the caller rather than silently retaining old rows. */
    static void clear(Context context) {
        if (Boolean.TRUE.equals(ACTIVE.get()))
            throw new IllegalStateException("transaction store callback cannot reenter the store");
        synchronized (BalanceData.class) {
            clearLocked(DataGeneration.context(context));
        }
    }

    private static void clearLocked(Context context) {
        Exception failure = null;
        boolean databaseCleared = false;
        try {
            // Reset is destructive by definition. Do not open the helper here: opening a missing
            // database would recreate it, and opening a corrupt one can invoke SQLite's recovery
            // path before the explicit reset gets a chance to remove it.
            context.deleteDatabase(DB_NAME);
            File database = context.getDatabasePath(DB_NAME);
            File[] databaseFiles = {
                database,
                new File(database.getPath() + "-wal"),
                new File(database.getPath() + "-shm"),
                new File(database.getPath() + "-journal")
            };
            for (File file : databaseFiles) {
                if (file.exists() && !file.delete() && file.exists())
                    throw new Exception("transaction database could not be deleted");
            }
            databaseCleared = true;
        } catch (Exception e) {
            failure = e;
        }
        // Keep the marker and legacy inputs when the authoritative database could not be removed;
        // they are still needed to find/recover the previous state after the surfaced failure.
        if (!databaseCleared)
            throw new IllegalStateException("transaction store could not be cleared", failure);
        try {
            File presence = new File(context.getNoBackupFilesDir(), PRESENCE_FILE);
            if (presence.exists() && !presence.delete() && presence.exists())
                throw new Exception("transaction store marker could not be deleted");
        } catch (Exception e) {
            if (failure == null) failure = e;
        }
        try {
            File[] files = context.getFilesDir().listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().startsWith(LEGACY_PAGE_PREFIX)
                            && file.exists() && !file.delete() && file.exists())
                        throw new Exception("legacy transaction page could not be deleted");
                }
            }
        } catch (Exception e) {
            if (failure == null) failure = e;
        }
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(
                BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
                .remove(BalanceData.KEY_TRANSACTIONS)
                .remove(LEGACY_MANIFEST)
                .remove(BalanceData.KEY_TRANSACTION_STORE_TOKEN);
            if (!editor.commit()) throw new Exception("legacy transaction cleanup failed");
        } catch (Exception e) {
            if (failure == null) failure = e;
        }
        if (failure != null) {
            throw new IllegalStateException("transaction store could not be cleared", failure);
        }
        try { DataGeneration.refreshMarker(context); }
        catch (Exception e) { throw new IllegalStateException("transaction generation marker could not be refreshed", e); }
    }

    /** Writes the legacy/backup JSON envelope incrementally; the caller owns the writer. */
    static void writeJson(Context context, int pageSize, Writer writer) throws Exception {
        if (writer == null) throw new NullPointerException("writer");
        requirePageSize(pageSize);
        writer.write("{\"transactions\":[");
        final boolean[] first = {true};
        forEach(context, pageSize, transaction -> {
            if (!first[0]) writer.write(",");
            first[0] = false;
            writer.write(BalanceData.transactionJson(transaction).toString());
        });
        writer.write("]}");
    }

    /** Strict streaming JSON producer for replace/merge. The caller owns the reader. */
    static StreamSource jsonSource(Reader reader) {
        if (reader == null) throw new NullPointerException("reader");
        return visitor -> readJson(reader, visitor);
    }

    static void readJson(Reader reader, Visitor visitor) throws Exception {
        if (reader == null || visitor == null) throw new NullPointerException("reader/visitor");
        JsonReader json = new JsonReader(reader);
        json.setLenient(false);
        json.beginObject();
        if (!json.hasNext() || !BalanceData.KEY_TRANSACTIONS.equals(json.nextName()))
            throw new Exception("invalid transaction envelope");
        json.beginArray();
        while (json.hasNext()) visitor.accept(readTransaction(json));
        json.endArray();
        if (json.hasNext()) throw new Exception("unexpected transaction envelope field");
        json.endObject();
        if (json.peek() != JsonToken.END_DOCUMENT) throw new Exception("trailing transaction data");
    }

    // Guard escaped/off-thread visitors and failures swallowed by a producer: neither may result in
    // a partial commit. Producers must finish all validation before returning normally.
    private static void consume(StreamSource source, Visitor writer) throws Exception {
        Thread thread = Thread.currentThread();
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            source.forEach(transaction -> {
                if (!open.get() || Thread.currentThread() != thread) {
                    Exception error = new IllegalStateException("transaction producer must be synchronous");
                    failure.compareAndSet(null, error);
                    throw error;
                }
                if (failure.get() != null) throw failure.get();
                try { writer.accept(transaction); }
                catch (Exception e) { failure.compareAndSet(null, e); throw e; }
            });
            if (failure.get() != null) throw failure.get();
        } finally {
            open.set(false);
        }
    }

    private static <T> T access(Context context, boolean write, boolean migrate, boolean allowMissing,
            Work<T> work) throws Exception {
        synchronized (BalanceData.class) {
            // Resolve once. In particular, a staging context is pinned and must not jump to the
            // selector that is published after this operation starts.
            Context dataContext = DataGeneration.context(context);
            return accessLocked(dataContext, write, migrate, allowMissing, work);
        }
    }

    private static <T> T accessLocked(Context context, boolean write, boolean migrate,
            boolean allowMissing, Work<T> work) throws Exception {
        if (Boolean.TRUE.equals(ACTIVE.get()))
            throw new IllegalStateException("transaction store callback cannot reenter the store");
        ACTIVE.set(true);
        try {
            File presence = new File(context.getNoBackupFilesDir(), PRESENCE_FILE);
            SharedPreferences prefs = context.getSharedPreferences(
                BalanceData.PREFS_DATA, Context.MODE_PRIVATE);
            // An old preference token is only evidence that a missing DB is not a fresh install.
            // Its value/type/mismatch never hides or deletes an existing database.
            if (!allowMissing && !context.getDatabasePath(DB_NAME).exists()
                    && (presence.exists() || prefs.contains(BalanceData.KEY_TRANSACTION_STORE_TOKEN)))
                throw new IllegalStateException("transaction database is missing");
            Legacy legacy = null;
            Legacy cleanupLegacy = write && !migrate ? new Legacy(context, prefs) : null;
            T result;
            try (Helper helper = new Helper(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransactionNonExclusive();
                try {
                    String ready = meta(db, READY);
                    if (ready == null) {
                        if (rowCount(db) != 0 || meta(db, INDEX_KEY) != null
                                || meta(db, REVISION) != null || (!allowMissing && presence.exists()))
                            throw new Exception("incomplete transaction store");
                        if (migrate) legacy = new Legacy(context, prefs);
                        if (!write && (legacy == null || !legacy.hasData())) {
                            db.setTransactionSuccessful();
                            return work.run(null);
                        }
                        initialize(db);
                    } else if (!"1".equals(ready)) {
                        throw new Exception("invalid transaction store state");
                    } else if (migrate && rowCount(db) == 0 && meta(db, LEGACY_OWNER) != null) {
                        // Only an original v1-owned empty database is eligible for this recovery
                        // path. A current empty replacement is authoritative, even if an old
                        // preference happens to remain after a crash.
                        legacy = new Legacy(context, prefs);
                    }
                    ensureRowKey(db);
                    Session session = new Session(db);
                    try {
                        if (legacy != null && legacy.hasData()) {
                            final long[] next = {0};
                            legacy.forEach(transaction -> session.insert(next[0]++, transaction));
                            session.migrated = true;
                        }
                        result = work.run(session);
                        // The v1 owner marker is no longer an ownership source. Removing it inside
                        // the same transaction prevents a crash after commit from re-importing an
                        // old preference into a deliberately empty current store.
                        if (meta(db, LEGACY_OWNER) != null)
                            db.delete(META, "key=?", new String[]{LEGACY_OWNER});
                        db.setTransactionSuccessful();
                    } finally {
                        session.close();
                    }
                } finally {
                    db.endTransaction();
                }
            }
            // A non-sensitive, independent receipt detects loss even after all preferences clear.
            // It is published only after commit; publishing failure never erases committed rows.
            if (!presence.exists()) {
                try (FileOutputStream out = new FileOutputStream(presence)) {
                    out.write(1);
                    out.getFD().sync();
                }
            }
            if (legacy != null && legacy.hasData()) legacy.cleanup(prefs);
            if (cleanupLegacy != null) cleanupLegacy.discard(prefs);
            return result;
        } finally {
            ACTIVE.remove();
        }
    }

    private static final class Session {
        final SQLiteDatabase db;
        final Mac index;
        final EncryptedRowCodec rows;
        String revision;
        boolean migrated;

        Session(SQLiteDatabase db) throws Exception {
            this.db = db;
            revision = meta(db, REVISION);
            String encoded = meta(db, INDEX_KEY);
            if (revision == null || revision.isEmpty() || encoded == null)
                throw new Exception("incomplete transaction store");
            byte[] secret = Base64.decode(BalanceData.decryptStorePayload(encoded), Base64.NO_WRAP);
            try {
                if (secret.length != 32) throw new Exception("invalid transaction lookup key");
                index = Mac.getInstance("HmacSHA256");
                index.init(new SecretKeySpec(secret, "HmacSHA256"));
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
            String encodedRows = meta(db, ROW_KEY);
            if (encodedRows == null) throw new Exception("incomplete transaction store");
            rows = EncryptedRowCodec.open(encodedRows, ROW_DOMAIN);
        }

        String digest(String kind, String value) {
            return value == null ? null : Base64.encodeToString(index.doFinal(
                (kind + "\n" + value).getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        }

        ContentValues indexes(Transaction transaction) {
            ContentValues values = new ContentValues();
            values.put("sig_digest", digest("sig", transaction.sig));
            values.put("content_digest", digest("content", transaction.content));
            values.put("legacy_digest", digest("legacy", legacyIdentity(transaction)));
            return values;
        }

        void insert(long ordinal, Transaction transaction) throws Exception {
            if (ordinal < 0) throw new IllegalStateException("transaction ordinal exhausted");
            requireTransaction(transaction);
            ContentValues values = indexes(transaction);
            values.put("ordinal", ordinal);
            values.put("payload", rows.encrypt(
                BalanceData.serializeTransactions(Collections.singletonList(transaction)),
                rowIdentity(ordinal)));
            if (db.insertOrThrow(TABLE, null, values) < 0)
                throw new Exception("transaction insert failed");
        }

        Transaction decode(Cursor cursor) throws Exception {
            long ordinal = cursor.getLong(0);
            if (ordinal < 0) throw new Exception("invalid transaction ordinal");
            Transaction row = decodePayload(cursor.getString(1), rows, rowIdentity(ordinal));
            if (!same(digest("sig", row.sig), cursor.getString(2))
                    || !same(digest("content", row.content), cursor.getString(3))
                    || !same(digest("legacy", legacyIdentity(row)), cursor.getString(4)))
                throw new Exception("transaction index does not match payload");
            return row;
        }

        void visit(int pageSize, Visitor visitor) throws Exception {
            try (Cursor cursor = db.query(TABLE, ROW_COLUMNS, null, null, null, null,
                    "ordinal ASC")) {
                List<Transaction> batch = new ArrayList<>(pageSize);
                long previous = -1;
                while (cursor.moveToNext()) {
                    long ordinal = cursor.getLong(0);
                    if (ordinal <= previous) throw new Exception("invalid transaction order");
                    previous = ordinal;
                    batch.add(decode(cursor));
                    if (batch.size() == pageSize) {
                        for (Transaction row : batch) visitor.accept(row);
                        batch.clear();
                    }
                }
                for (Transaction row : batch) visitor.accept(row);
            }
        }

        Page page(long after, int limit) throws Exception {
            if (after < -1) throw new IllegalArgumentException("invalid transaction cursor");
            List<Transaction> rows = new ArrayList<>(limit);
            long next = after;
            boolean more = false;
            try (Cursor cursor = db.query(TABLE, ROW_COLUMNS, "ordinal > ?",
                    new String[]{Long.toString(after)}, null, null, "ordinal ASC",
                    Integer.toString(limit + 1))) {
                while (cursor.moveToNext()) {
                    if (rows.size() == limit) { more = true; break; }
                    long ordinal = cursor.getLong(0);
                    if (ordinal <= next) throw new Exception("invalid transaction order");
                    rows.add(decode(cursor));
                    next = ordinal;
                }
            }
            return new Page(rows, next, more, revision);
        }

        Transaction find(Transaction incoming) throws Exception {
            Transaction row = incoming.content == null ? null
                : find("content_digest=?", digest("content", incoming.content));
            if (row == null && incoming.sig != null)
                row = find("sig_digest=?", digest("sig", incoming.sig));
            if (row == null) row = find("legacy_digest=?"
                + (incoming.sig == null ? "" : " AND sig_digest IS NULL"),
                digest("legacy", legacyIdentity(incoming)));
            return row;
        }

        private Transaction find(String where, String digest) throws Exception {
            try (Cursor cursor = db.query(TABLE, ROW_COLUMNS, where, new String[]{digest},
                    null, null, "ordinal ASC", "1")) {
                return cursor.moveToFirst() ? decode(cursor) : null;
            }
        }

        long nextOrdinal() {
            try (Cursor cursor = db.rawQuery("SELECT MAX(ordinal) FROM " + TABLE, null)) {
                cursor.moveToFirst();
                if (cursor.isNull(0)) return 0;
                long last = cursor.getLong(0);
                return last == Long.MAX_VALUE ? -1 : last + 1;
            }
        }

        void touch() {
            revision = UUID.randomUUID().toString();
            putMeta(db, REVISION, revision);
        }

        void close() {
            rows.close();
        }
    }

    private static Transaction decodePayload(String encrypted) throws Exception {
        return decodePayload(encrypted, null, null);
    }

    private static Transaction decodePayload(String encrypted, EncryptedRowCodec rows,
            String identity) throws Exception {
        final Transaction[] transaction = {null};
        String plain = rows == null ? BalanceData.decryptStorePayload(encrypted)
            : rows.decrypt(encrypted, identity);
        readJson(new StringReader(plain), row -> {
            if (transaction[0] != null) throw new Exception("multiple transactions in a store row");
            transaction[0] = row;
        });
        if (transaction[0] == null) throw new Exception("empty transaction row");
        return transaction[0];
    }

    private static Transaction readTransaction(JsonReader json) throws Exception {
        String bank = null, account = null, sig = null, content = null;
        long date = 0, amount = 0;
        Long balance = null;
        int seen = 0;
        json.beginObject();
        while (json.hasNext()) {
            String field = json.nextName();
            int bit;
            switch (field) {
                case "bank": bit = 1; break;
                case "date": bit = 2; break;
                case "amount": bit = 4; break;
                case "account": bit = 8; break;
                case "bal": bit = 16; break;
                case "sig": bit = 32; break;
                case "content": bit = 64; break;
                default: throw new Exception("unknown transaction field");
            }
            if ((seen & bit) != 0) throw new Exception("duplicate transaction field");
            seen |= bit;
            switch (field) {
                case "bank": bank = string(json, false); break;
                case "date": date = integer(json); break;
                case "amount": amount = integer(json); break;
                case "account": account = string(json, true); break;
                case "sig": sig = string(json, true); break;
                case "content": content = string(json, true); break;
                case "bal":
                    if (json.peek() == JsonToken.NULL) json.nextNull();
                    else balance = integer(json);
                    break;
            }
        }
        json.endObject();
        if ((seen & 7) != 7) throw new Exception("missing transaction field");
        Transaction transaction = new Transaction(bank, account, date, amount, balance, sig, content);
        requireTransaction(transaction);
        return transaction;
    }

    private static String string(JsonReader json, boolean nullable) throws Exception {
        if (nullable && json.peek() == JsonToken.NULL) { json.nextNull(); return null; }
        if (json.peek() != JsonToken.STRING) throw new Exception("invalid transaction string");
        return json.nextString();
    }

    private static long integer(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.NUMBER) throw new Exception("invalid transaction number");
        String number = json.nextString();
        if (!number.matches("-?(0|[1-9][0-9]*)")) throw new Exception("non-integer transaction number");
        return Long.parseLong(number);
    }

    private static void requireTransaction(Transaction transaction) {
        if (transaction == null || transaction.bank == null || transaction.bank.isEmpty())
            throw new IllegalArgumentException("transaction must have a bank");
    }

    private static void requirePageSize(int size) {
        if (size <= 0 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("invalid page size");
    }

    private static String legacyIdentity(Transaction transaction) {
        // JSON framing distinguishes null accounts and embedded separators without plaintext indexes.
        return new org.json.JSONArray()
            .put(transaction.bank).put(transaction.date).put(transaction.amount)
            .put(transaction.account == null ? JSONObject.NULL : transaction.account).toString();
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String rowIdentity(long ordinal) { return "ordinal\n" + ordinal; }

    private static long rowCount(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private static String meta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(META, new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        if (db.insertWithOnConflict(META, null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new SQLiteException("transaction metadata insert failed");
    }

    private static void initialize(SQLiteDatabase db) throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try {
            putMeta(db, INDEX_KEY, BalanceData.encryptStorePayload(
                Base64.encodeToString(secret, Base64.NO_WRAP)));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
        putMeta(db, REVISION, UUID.randomUUID().toString());
        putMeta(db, READY, "1");
    }

    /** Adds the independent row key to an already committed store without touching its rows. */
    private static void ensureRowKey(SQLiteDatabase db) throws Exception {
        if (meta(db, ROW_KEY) != null) return;
        try (Cursor cursor = db.query(TABLE, new String[]{"payload"}, null, null,
                null, null, null)) {
            while (cursor.moveToNext()) {
                if (EncryptedRowCodec.isVersioned(cursor.getString(0)))
                    throw new Exception("missing transaction row key");
            }
        }
        putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
    }

    /** Single legacy preference value, or one encrypted legacy page at a time; no decoded row list. */
    private static final class Legacy implements StreamSource {
        final Context context;
        final String manifest;
        final String value;
        String generation;
        long pages;

        Legacy(Context context, SharedPreferences prefs) {
            this.context = context;
            manifest = prefs.getString(LEGACY_MANIFEST, null);
            value = prefs.getString(BalanceData.KEY_TRANSACTIONS, null);
        }

        boolean hasData() { return manifest != null || value != null; }

        @Override public void forEach(Visitor visitor) throws Exception {
            if (manifest == null) {
                if (value != null) readJson(new StringReader(value.trim().startsWith("{")
                    ? value : BalanceData.decryptStorePayload(value)), visitor);
                return;
            }
            JSONObject object = new JSONObject(BalanceData.decryptStorePayload(manifest));
            if (manifestInteger(object, "version") != 1) throw new Exception("unknown legacy store");
            Object name = object.get("generation");
            if (!(name instanceof String) || !((String) name).matches("[A-Za-z0-9-]+"))
                throw new Exception("invalid legacy generation");
            generation = (String) name;
            pages = manifestInteger(object, "pages");
            long expected = manifestInteger(object, "count");
            if (expected < 0 || pages < 0 || pages != expected / MAX_PAGE_SIZE
                    + (expected % MAX_PAGE_SIZE == 0 ? 0 : 1))
                throw new Exception("invalid legacy manifest");
            final long[] count = {0};
            for (long page = 0; page < pages; page++) {
                String encrypted;
                try (FileInputStream in = new FileInputStream(file(page));
                        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    encrypted = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
                final int[] pageCount = {0};
                readJson(new StringReader(BalanceData.decryptStorePayload(encrypted)), transaction -> {
                    if (++pageCount[0] > MAX_PAGE_SIZE || ++count[0] > expected)
                        throw new Exception("invalid legacy page count");
                    visitor.accept(transaction);
                });
                long expectedPageCount = Math.min(MAX_PAGE_SIZE, expected - page * MAX_PAGE_SIZE);
                if (pageCount[0] != expectedPageCount) throw new Exception("incomplete legacy page");
            }
            if (count[0] != expected) throw new Exception("incomplete legacy generation");
        }

        File file(long page) {
            return new File(context.getFilesDir(), LEGACY_PAGE_PREFIX + generation + "_" + page);
        }

        void cleanup(SharedPreferences prefs) throws Exception {
            SharedPreferences.Editor editor = prefs.edit();
            boolean changed = false;
            if (value != null && value.equals(prefs.getString(BalanceData.KEY_TRANSACTIONS, null))) {
                editor.remove(BalanceData.KEY_TRANSACTIONS);
                changed = true;
            }
            if (manifest != null && manifest.equals(prefs.getString(LEGACY_MANIFEST, null))) {
                editor.remove(LEGACY_MANIFEST);
                changed = true;
            }
            if (prefs.contains(BalanceData.KEY_TRANSACTION_STORE_TOKEN)) {
                editor.remove(BalanceData.KEY_TRANSACTION_STORE_TOKEN);
                changed = true;
            }
            if (changed && !editor.commit()) throw new Exception("legacy transaction cleanup failed");
            // Delete only this committed generation, without allocating a files-directory listing.
            if (generation != null && !prefs.contains(LEGACY_MANIFEST))
                for (long page = 0; page < pages; page++) file(page).delete();
        }

        /** Drops legacy inputs after a successful explicit replacement became authoritative. */
        void discard(SharedPreferences prefs) throws Exception {
            boolean sameManifest = manifest != null
                    && manifest.equals(prefs.getString(LEGACY_MANIFEST, null));
            boolean sameValue = value != null
                    && value.equals(prefs.getString(BalanceData.KEY_TRANSACTIONS, null));
            SharedPreferences.Editor editor = prefs.edit();
            boolean changed = false;
            if (sameValue) {
                editor.remove(BalanceData.KEY_TRANSACTIONS);
                changed = true;
            }
            if (sameManifest) {
                editor.remove(LEGACY_MANIFEST);
                changed = true;
            }
            if (prefs.contains(BalanceData.KEY_TRANSACTION_STORE_TOKEN)) {
                editor.remove(BalanceData.KEY_TRANSACTION_STORE_TOKEN);
                changed = true;
            }
            if (changed && !editor.commit()) throw new Exception("legacy transaction cleanup failed");
            if (sameManifest) {
                File[] files = context.getFilesDir().listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().startsWith(LEGACY_PAGE_PREFIX)
                                && file.exists() && !file.delete() && file.exists())
                            throw new Exception("legacy transaction page could not be deleted");
                    }
                }
            }
        }
    }

    private static long manifestInteger(JSONObject object, String field) throws Exception {
        Object value = object.get(field);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new Exception("invalid legacy manifest number");
        return ((Number) value).longValue();
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            // The default Android corruption handler deletes the DB. Preserve it for recovery.
            super(context, DB_NAME, null, DB_VERSION, db -> {
                throw new SQLiteException("transaction database is corrupt; preserved for recovery");
            });
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " ordinal INTEGER NOT NULL, payload TEXT NOT NULL, sig_digest TEXT,"
                + " content_digest TEXT, legacy_digest TEXT NOT NULL)");
            db.execSQL("CREATE TABLE " + META + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            createIndexes(db);
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion != 1 || newVersion != DB_VERSION)
                throw new SQLiteException("unsupported transaction database version");
            // SQLiteOpenHelper wraps this whole upgrade in a transaction, including backfill. The
            // encrypted payload/id/ordinal remain untouched, and failure preserves the v1 schema.
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN sig_digest TEXT");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN content_digest TEXT");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN legacy_digest TEXT");
            createIndexes(db);
            try {
                String ready = meta(db, READY);
                if (ready == null && rowCount(db) == 0) return;
                if (!"1".equals(ready)) throw new Exception("incomplete legacy transaction store");
                initialize(db);
                Session session = new Session(db);
                try {
                    try (Cursor cursor = db.query(TABLE, new String[]{"id", "ordinal", "payload"},
                            null, null, null, null, "ordinal ASC")) {
                        long previous = -1;
                        while (cursor.moveToNext()) {
                            long ordinal = cursor.getLong(1);
                            if (ordinal <= previous) throw new Exception("invalid legacy transaction order");
                            previous = ordinal;
                            ContentValues values = session.indexes(decodePayload(cursor.getString(2)));
                            if (db.update(TABLE, values, "id=?",
                                    new String[]{Long.toString(cursor.getLong(0))}) != 1)
                                throw new Exception("transaction index migration failed");
                        }
                    }
                } finally {
                    session.close();
                }
            } catch (Exception e) {
                SQLiteException failure = new SQLiteException("transaction store migration failed");
                failure.initCause(e);
                throw failure;
            }
        }

        private static void createIndexes(SQLiteDatabase db) {
            db.execSQL("CREATE UNIQUE INDEX transactions_order_v2 ON " + TABLE + "(ordinal)");
            db.execSQL("CREATE INDEX transactions_sig ON " + TABLE + "(sig_digest)");
            db.execSQL("CREATE INDEX transactions_content ON " + TABLE + "(content_digest)");
            db.execSQL("CREATE INDEX transactions_legacy ON " + TABLE + "(legacy_digest)");
        }
    }
}
