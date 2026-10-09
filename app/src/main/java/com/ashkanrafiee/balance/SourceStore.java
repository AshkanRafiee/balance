package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Durable evidence for recognized-bank SMS messages and their parser results.
 *
 * <p>The source table is deliberately separate from the current transaction and balance
 * projections. A source is inserted once for each exact physical message identity (sender, exact
 * body and exact arrival time). Parser observations are append-only: the same parser revision may
 * record more than one interpretation, while an exact duplicate interpretation is ignored. A
 * newer revision never replaces an older one.</p>
 *
 * <p>Only sender values resolved by {@link BankRules#resolve(String)} enter this store. This keeps
 * the retention boundary explicit: a known bank's unparsed messages are retained, but unknown
 * senders are not. Raw values and parser evidence are encrypted with an independent session data
 * key. The SQLite indexes contain only HMACs and local relational IDs.</p>
 *
 * <p>This class does not own a preference token. The database and its small no-backup receipt are
 * authoritative. A missing database in the presence of the receipt is an error, never an empty
 * store. Deletion is available only through {@link #clear(Context)}.</p>
 */
final class SourceStore {
    static final String DB_NAME = "balance_sources.db";
    static final String TABLE = "sources";
    static final String TRANSACTION_TABLE = "transaction_observations";
    static final String BALANCE_TABLE = "balance_observations";
    static final String SOURCES_TABLE = TABLE;
    static final String TRANSACTIONS_TABLE = TRANSACTION_TABLE;
    static final String BALANCES_TABLE = BALANCE_TABLE;
    static final int MAX_PAGE_SIZE = 1_000;

    private static final int DB_VERSION = 1;
    private static final String META = "store_meta";
    private static final String READY = "ready";
    private static final String INDEX_KEY = "index_key";
    private static final String ROW_KEY = "row_key";
    private static final String REVISION = "revision";
    private static final String ROW_DOMAIN = "sources";
    private static final String RECEIPT = "balance_sources.present";
    private static final String[] SOURCE_COLUMNS = {
        "id", "physical_digest", "content_digest", "payload"
    };
    private static final String[] OBSERVATION_COLUMNS = {
        "id", "source_id", "source_digest", "dedupe_digest", "payload"
    };
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private SourceStore() {}

    /** One immutable recognized-bank source message. All text is decoded only after the row is read. */
    static final class Source {
        final long id;
        final String physicalIdentity;
        final String contentIdentity;
        final String bank;
        final String sender;
        final long arrivalTime;
        final String rawBody;
        final int ruleVersion;

        private Source(long id, String physicalIdentity, String contentIdentity, String bank,
                String sender, long arrivalTime, String rawBody, int ruleVersion) {
            this.id = id;
            this.physicalIdentity = physicalIdentity;
            this.contentIdentity = contentIdentity;
            this.bank = bank;
            this.sender = sender;
            this.arrivalTime = arrivalTime;
            this.rawBody = rawBody;
            this.ruleVersion = ruleVersion;
        }
    }

    /** One immutable transaction parse, linked to the source that produced it. */
    static final class TransactionObservation {
        final long id;
        final long sourceId;
        final String sourceIdentity;
        final int parserRevision;
        final Transaction transaction;

        private TransactionObservation(long id, long sourceId, String sourceIdentity,
                int parserRevision, Transaction transaction) {
            this.id = id;
            this.sourceId = sourceId;
            this.sourceIdentity = sourceIdentity;
            this.parserRevision = parserRevision;
            this.transaction = transaction;
        }
    }

    /** One immutable current-balance parse, linked to the source that produced it. */
    static final class BalanceObservation {
        final long id;
        final long sourceId;
        final String sourceIdentity;
        final int parserRevision;
        final String bank;
        final String account;
        final long date;
        final long balance;

        private BalanceObservation(long id, long sourceId, String sourceIdentity,
                int parserRevision, String bank, String account, long date, long balance) {
            this.id = id;
            this.sourceId = sourceId;
            this.sourceIdentity = sourceIdentity;
            this.parserRevision = parserRevision;
            this.bank = bank;
            this.account = account;
            this.date = date;
            this.balance = balance;
        }
    }

    static final class SourcePage {
        final List<Source> rows;
        final long nextId;
        final boolean hasMore;
        final String revision;

        private SourcePage(List<Source> rows, long nextId, boolean hasMore, String revision) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.nextId = nextId;
            this.hasMore = hasMore;
            this.revision = revision;
        }
    }

    static final class TransactionPage {
        final List<TransactionObservation> rows;
        final long nextId;
        final boolean hasMore;
        final String revision;

        private TransactionPage(List<TransactionObservation> rows, long nextId, boolean hasMore,
                String revision) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.nextId = nextId;
            this.hasMore = hasMore;
            this.revision = revision;
        }
    }

    static final class BalancePage {
        final List<BalanceObservation> rows;
        final long nextId;
        final boolean hasMore;
        final String revision;

        private BalancePage(List<BalanceObservation> rows, long nextId, boolean hasMore,
                String revision) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.nextId = nextId;
            this.hasMore = hasMore;
            this.revision = revision;
        }
    }

    interface TransactionWork<T> {
        T run(Editor editor) throws Exception;
    }

    interface SourceVisitor {
        void accept(Source source) throws Exception;
    }

    interface TransactionVisitor {
        void accept(TransactionObservation observation) throws Exception;
    }

    interface BalanceVisitor {
        void accept(BalanceObservation observation) throws Exception;
    }

    /**
     * Runs one synchronous SQLite transaction. A scan should use one call for its bounded source
     * batch and call {@link Editor#capture(String, String, long, int)} for each SMS in that batch.
     * The callback must not escape the editor or invoke this store recursively.
     */
    static <T> T runInTransaction(Context context, TransactionWork<T> work) throws Exception {
        if (context == null || work == null) throw new NullPointerException("context/work");
        synchronized (BalanceData.class) {
            Context dataContext = DataGeneration.context(context);
            return access(dataContext, work);
        }
    }

    /** Captures one recognized source, returning null for an unknown sender. */
    static Source capture(Context context, String sender, String body, long arrival,
            int ruleVersion) throws Exception {
        return runInTransaction(context,
            editor -> editor.capture(sender, body, arrival, ruleVersion));
    }

    /** Appends one transaction parse when a transaction is present; null is a no-op. */
    static boolean observeTransaction(Context context, Source source, int parserRevision,
            Transaction transaction) throws Exception {
        if (transaction == null) return false;
        return runInTransaction(context, editor ->
            editor.observeTransaction(source, parserRevision, transaction));
    }

    /** Appends one balance parse when a balance is present; null is a no-op. */
    static boolean observeBalance(Context context, Source source, int parserRevision,
            String bank, String account, long date, Long balance) throws Exception {
        if (balance == null) return false;
        return runInTransaction(context, editor ->
            editor.observeBalance(source, parserRevision, bank, account, date, balance));
    }

    /** Convenience overload for a balance belonging to the source's resolved bank. */
    static boolean observeBalance(Context context, Source source, int parserRevision, long date,
            Long balance) throws Exception {
        if (balance == null) return false;
        return runInTransaction(context, editor ->
            editor.observeBalance(source, parserRevision, source.bank, null, date, balance));
    }

    /** Number of retained physical source rows. The read validates every encrypted source row. */
    static long sourceCount(Context context) throws Exception {
        return read(context, session -> {
            long[] count = {0};
            try (Cursor cursor = session.db.query(TABLE, SOURCE_COLUMNS, null, null, null, null,
                    "id ASC")) {
                while (cursor.moveToNext()) {
                    session.decodeSource(cursor);
                    count[0]++;
                }
            }
            return count[0];
        });
    }

    static long countSources(Context context) throws Exception {
        return sourceCount(context);
    }

    static long count(Context context) throws Exception {
        return sourceCount(context);
    }

    /** Number of retained transaction observations. */
    static long transactionObservationCount(Context context) throws Exception {
        return read(context, session -> {
            long[] count = {0};
            try (Cursor cursor = session.db.query(TRANSACTION_TABLE, OBSERVATION_COLUMNS, null,
                    null, null, null, "id ASC")) {
                while (cursor.moveToNext()) {
                    session.decodeTransaction(cursor);
                    count[0]++;
                }
            }
            return count[0];
        });
    }

    static long countTransactions(Context context) throws Exception {
        return transactionObservationCount(context);
    }

    /** Number of retained balance observations. */
    static long balanceObservationCount(Context context) throws Exception {
        return read(context, session -> {
            long[] count = {0};
            try (Cursor cursor = session.db.query(BALANCE_TABLE, OBSERVATION_COLUMNS, null,
                    null, null, null, "id ASC")) {
                while (cursor.moveToNext()) {
                    session.decodeBalance(cursor);
                    count[0]++;
                }
            }
            return count[0];
        });
    }

    static long countBalances(Context context) throws Exception {
        return balanceObservationCount(context);
    }

    /** Streams sources from one read transaction without retaining a dataset-sized list. */
    static void forEachSource(Context context, int pageSize, SourceVisitor visitor) throws Exception {
        requirePageSize(pageSize);
        if (visitor == null) throw new NullPointerException("visitor");
        read(context, session -> {
            visitSources(session, pageSize, visitor);
            return null;
        });
    }

    /** Streams transaction observations from one read transaction without retaining a full history. */
    static void forEachTransaction(Context context, int pageSize, TransactionVisitor visitor)
            throws Exception {
        requirePageSize(pageSize);
        if (visitor == null) throw new NullPointerException("visitor");
        read(context, session -> {
            visitTransactions(session, pageSize, visitor);
            return null;
        });
    }

    /** Streams balance observations from one read transaction without retaining a full history. */
    static void forEachBalance(Context context, int pageSize, BalanceVisitor visitor)
            throws Exception {
        requirePageSize(pageSize);
        if (visitor == null) throw new NullPointerException("visitor");
        read(context, session -> {
            visitBalances(session, pageSize, visitor);
            return null;
        });
    }

    /** Plural aliases make the three independent backup streams self-documenting at call sites. */
    static void forEachSources(Context context, int pageSize, SourceVisitor visitor) throws Exception {
        forEachSource(context, pageSize, visitor);
    }

    static void forEachTransactions(Context context, int pageSize, TransactionVisitor visitor)
            throws Exception {
        forEachTransaction(context, pageSize, visitor);
    }

    static void forEachBalances(Context context, int pageSize, BalanceVisitor visitor)
            throws Exception {
        forEachBalance(context, pageSize, visitor);
    }

    /**
     * Streams all three source-owned collections from one stable read transaction. Backup should
     * prefer this over three independent calls when it needs one mutually consistent snapshot.
     * Each visitor receives at most {@code pageSize} decoded objects retained by this store at a
     * time; the callback itself owns any bytes it chooses to frame.
     */
    static void forEachAll(Context context, int pageSize, SourceVisitor sources,
            TransactionVisitor transactions, BalanceVisitor balances) throws Exception {
        requirePageSize(pageSize);
        if (sources == null || transactions == null || balances == null)
            throw new NullPointerException("backup visitors");
        read(context, session -> {
            visitSources(session, pageSize, sources);
            visitTransactions(session, pageSize, transactions);
            visitBalances(session, pageSize, balances);
            return null;
        });
    }

    /** Keyset page of sources. Start with {@code afterId == -1}. */
    static SourcePage pageSources(Context context, long afterId, int limit) throws Exception {
        requirePage(limit, afterId);
        return read(context, session -> session.sourcePage(afterId, limit));
    }

    static SourcePage pageSources(Context context, SourcePage previous, int limit) throws Exception {
        if (previous == null) throw new NullPointerException("previous page");
        requirePage(limit, previous.nextId);
        return read(context, session -> {
            requireRevision(previous.revision, session.revision);
            return session.sourcePage(previous.nextId, limit);
        });
    }

    /** Keyset page of transaction observations. Start with {@code afterId == -1}. */
    static TransactionPage pageTransactions(Context context, long afterId, int limit)
            throws Exception {
        requirePage(limit, afterId);
        return read(context, session -> session.transactionPage(afterId, limit));
    }

    static TransactionPage pageTransactions(Context context, TransactionPage previous, int limit)
            throws Exception {
        if (previous == null) throw new NullPointerException("previous page");
        requirePage(limit, previous.nextId);
        return read(context, session -> {
            requireRevision(previous.revision, session.revision);
            return session.transactionPage(previous.nextId, limit);
        });
    }

    /** Keyset page of balance observations. Start with {@code afterId == -1}. */
    static BalancePage pageBalances(Context context, long afterId, int limit) throws Exception {
        requirePage(limit, afterId);
        return read(context, session -> session.balancePage(afterId, limit));
    }

    static BalancePage pageBalances(Context context, BalancePage previous, int limit)
            throws Exception {
        if (previous == null) throw new NullPointerException("previous page");
        requirePage(limit, previous.nextId);
        return read(context, session -> {
            requireRevision(previous.revision, session.revision);
            return session.balancePage(previous.nextId, limit);
        });
    }

    /** Transaction observations for one source row; per-SMS counts stay small. */
    static List<TransactionObservation> transactionObservationsForSource(Context context,
            long sourceId) throws Exception {
        return read(context, session -> {
            List<TransactionObservation> out = new ArrayList<>();
            try (Cursor cursor = session.db.query(TRANSACTION_TABLE, OBSERVATION_COLUMNS,
                    "source_id=?", new String[]{Long.toString(sourceId)}, null, null, "id ASC")) {
                while (cursor.moveToNext()) out.add(session.decodeTransaction(cursor));
            }
            return out;
        });
    }

    /** Balance observations for one source row; per-SMS counts stay small. */
    static List<BalanceObservation> balanceObservationsForSource(Context context,
            long sourceId) throws Exception {
        return read(context, session -> {
            List<BalanceObservation> out = new ArrayList<>();
            try (Cursor cursor = session.db.query(BALANCE_TABLE, OBSERVATION_COLUMNS,
                    "source_id=?", new String[]{Long.toString(sourceId)}, null, null, "id ASC")) {
                while (cursor.moveToNext()) out.add(session.decodeBalance(cursor));
            }
            return out;
        });
    }

    /** Explicit destructive reset. It does not open a missing/corrupt database. */
    static void clear(Context context) {
        if (context == null) throw new NullPointerException("context");
        if (Boolean.TRUE.equals(ACTIVE.get()))
            throw new IllegalStateException("source store callback cannot reenter the store");
        synchronized (BalanceData.class) {
            Context dataContext = DataGeneration.context(context);
            Exception failure = null;
            boolean databaseCleared = false;
            try {
                dataContext.deleteDatabase(DB_NAME);
                FileSet files = new FileSet(dataContext);
                for (java.io.File file : files.all()) {
                    if (file.exists() && !file.delete() && file.exists())
                        throw new Exception("source database could not be deleted");
                }
                databaseCleared = true;
            } catch (Exception e) {
                failure = e;
            }
            if (!databaseCleared)
                throw new IllegalStateException("source store could not be cleared", failure);
            java.io.File receipt = receiptFile(dataContext);
            if (receipt.exists() && !receipt.delete() && receipt.exists())
                throw new IllegalStateException("source store receipt could not be deleted");
            try { DataGeneration.refreshMarker(dataContext); }
            catch (Exception e) {
                throw new IllegalStateException("source generation marker could not be refreshed", e);
            }
        }
    }

    static void reset(Context context) {
        clear(context);
    }

    /** A transaction-scoped source writer used by scanner batches. */
    static final class Editor {
        private final Session session;

        private Editor(Session session) {
            this.session = session;
        }

        /** Unknown senders are deliberately ignored; known-bank unparsed bodies are retained. */
        Source capture(String sender, String body, long arrival, int ruleVersion) throws Exception {
            if (sender == null || sender.isEmpty()) return null;
            if (arrival < 0) throw new IllegalArgumentException("negative SMS arrival");
            if (ruleVersion < 0) throw new IllegalArgumentException("negative rule version");
            String bank = BankRules.resolve(sender);
            if (bank == null) return null;

            String physical = session.lookup.physical(sender, body, arrival);
            try (Cursor cursor = session.db.query(TABLE, SOURCE_COLUMNS, "physical_digest=?",
                    new String[]{physical}, null, null, null, "1")) {
                if (cursor.moveToFirst()) return session.decodeSource(cursor);
            }

            String content = session.lookup.content(sender, body);
            Source source = new Source(0, physical, content, bank, sender, arrival, body, ruleVersion);
            ContentValues values = new ContentValues();
            values.put("physical_digest", physical);
            values.put("content_digest", content);
            values.put("payload", session.rows.encrypt(sourceJson(source).toString(),
                sourceIdentity(physical)));
            long id = session.db.insertOrThrow(TABLE, null, values);
            session.changed = true;
            return new Source(id, physical, content, bank, sender, arrival, body, ruleVersion);
        }

        /**
         * Replays a source from a backup. The source digest is intentionally recomputed under the
         * destination store's HMAC key; the exported identity is a stream correlation value, not a
         * portable lookup key.
         */
        Source importSource(Source source) throws Exception {
            if (source == null) throw new NullPointerException("source");
            return capture(source.sender, source.rawBody, source.arrivalTime, source.ruleVersion);
        }

        boolean observeTransaction(Source source, int parserRevision, Transaction transaction)
                throws Exception {
            if (transaction == null) return false;
            requireParserRevision(parserRevision);
            Source stored = session.requireSource(source);
            requireTransaction(transaction);
            if (!stored.bank.equals(transaction.bank))
                throw new IllegalArgumentException("transaction bank mismatch");
            String digest = transactionDigest(session.lookup, stored.physicalIdentity,
                parserRevision, transaction);
            try (Cursor cursor = session.db.query(TRANSACTION_TABLE, new String[]{"id"},
                    "dedupe_digest=?", new String[]{digest}, null, null, null, "1")) {
                if (cursor.moveToFirst()) return false;
            }
            ContentValues values = observationValues(stored, digest,
                transactionJson(parserRevision, transaction), session.rows,
                transactionIdentity(digest));
            long id = session.db.insertOrThrow(TRANSACTION_TABLE, null, values);
            if (id <= 0) throw new SQLiteException("transaction observation insert failed");
            session.changed = true;
            return true;
        }

        boolean importTransactionObservation(Source source, TransactionObservation observation)
                throws Exception {
            if (observation == null) return false;
            return observeTransaction(source, observation.parserRevision, observation.transaction);
        }

        boolean observeBalance(Source source, int parserRevision, String bank, String account,
                long date, long balance) throws Exception {
            requireParserRevision(parserRevision);
            Source stored = session.requireSource(source);
            if (bank == null || bank.isEmpty()) bank = stored.bank;
            if (!stored.bank.equals(bank)) throw new IllegalArgumentException("balance bank mismatch");
            String digest = balanceDigest(session.lookup, stored.physicalIdentity, parserRevision,
                bank, account, date, balance);
            try (Cursor cursor = session.db.query(BALANCE_TABLE, new String[]{"id"},
                    "dedupe_digest=?", new String[]{digest}, null, null, null, "1")) {
                if (cursor.moveToFirst()) return false;
            }
            ContentValues values = observationValues(stored, digest,
                balanceJson(parserRevision, bank, account, date, balance), session.rows,
                balanceIdentity(digest));
            long id = session.db.insertOrThrow(BALANCE_TABLE, null, values);
            if (id <= 0) throw new SQLiteException("balance observation insert failed");
            session.changed = true;
            return true;
        }

        boolean importBalanceObservation(Source source, BalanceObservation observation)
                throws Exception {
            if (observation == null) return false;
            return observeBalance(source, observation.parserRevision, observation.bank,
                observation.account, observation.date, observation.balance);
        }
    }

    private interface SessionWork<T> {
        T run(Session session) throws Exception;
    }

    private static <T> T access(Context context, TransactionWork<T> work) throws Exception {
        if (Boolean.TRUE.equals(ACTIVE.get()))
            throw new IllegalStateException("source store callback cannot reenter the store");
        ACTIVE.set(true);
        try {
            ensurePresentOrFresh(context);
            T result;
            try (Helper helper = new Helper(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransactionNonExclusive();
                Session session = null;
                try {
                    session = new Session(db);
                    result = work.run(new Editor(session));
                    if (session.changed) session.touch();
                    db.setTransactionSuccessful();
                } finally {
                    if (session != null) session.close();
                    db.endTransaction();
                }
            }
            publishReceipt(context);
            return result;
        } finally {
            ACTIVE.remove();
        }
    }

    private static <T> T read(Context context, SessionWork<T> work) throws Exception {
        synchronized (BalanceData.class) {
            context = DataGeneration.context(context);
            if (Boolean.TRUE.equals(ACTIVE.get()))
                throw new IllegalStateException("source store callback cannot reenter the store");
            ACTIVE.set(true);
            try {
                ensurePresentOrFresh(context);
                T result;
                try (Helper helper = new Helper(context)) {
                    SQLiteDatabase db = helper.getWritableDatabase();
                    db.beginTransactionNonExclusive();
                    Session session = null;
                    try {
                        session = new Session(db);
                        result = work.run(session);
                        db.setTransactionSuccessful();
                    } finally {
                        if (session != null) session.close();
                        db.endTransaction();
                    }
                }
                publishReceipt(context);
                return result;
            } finally {
                ACTIVE.remove();
            }
        }
    }

    private static void ensurePresentOrFresh(Context context) throws Exception {
        java.io.File database = context.getDatabasePath(DB_NAME);
        java.io.File receipt = receiptFile(context);
        if (!database.isFile() && (receipt.isFile() || new FileSet(context).hasSidecar()))
            throw new IllegalStateException("source database is missing");
    }

    private static void publishReceipt(Context context) throws Exception {
        java.io.File receipt = receiptFile(context);
        if (receipt.exists()) return;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(receipt)) {
            output.write(1);
            output.getFD().sync();
        }
    }

    private static java.io.File receiptFile(Context context) {
        return new java.io.File(context.getNoBackupFilesDir(), RECEIPT);
    }

    private static void visitSources(Session session, int pageSize, SourceVisitor visitor)
            throws Exception {
        try (Cursor cursor = session.db.query(TABLE, SOURCE_COLUMNS, null, null, null, null,
                "id ASC")) {
            List<Source> batch = new ArrayList<>(pageSize);
            while (cursor.moveToNext()) {
                batch.add(session.decodeSource(cursor));
                if (batch.size() == pageSize) {
                    for (Source source : batch) visitor.accept(source);
                    batch.clear();
                }
            }
            for (Source source : batch) visitor.accept(source);
        }
    }

    private static void visitTransactions(Session session, int pageSize, TransactionVisitor visitor)
            throws Exception {
        try (Cursor cursor = session.db.query(TRANSACTION_TABLE, OBSERVATION_COLUMNS, null, null,
                null, null, "id ASC")) {
            List<TransactionObservation> batch = new ArrayList<>(pageSize);
            while (cursor.moveToNext()) {
                batch.add(session.decodeTransaction(cursor));
                if (batch.size() == pageSize) {
                    for (TransactionObservation row : batch) visitor.accept(row);
                    batch.clear();
                }
            }
            for (TransactionObservation row : batch) visitor.accept(row);
        }
    }

    private static void visitBalances(Session session, int pageSize, BalanceVisitor visitor)
            throws Exception {
        try (Cursor cursor = session.db.query(BALANCE_TABLE, OBSERVATION_COLUMNS, null, null,
                null, null, "id ASC")) {
            List<BalanceObservation> batch = new ArrayList<>(pageSize);
            while (cursor.moveToNext()) {
                batch.add(session.decodeBalance(cursor));
                if (batch.size() == pageSize) {
                    for (BalanceObservation row : batch) visitor.accept(row);
                    batch.clear();
                }
            }
            for (BalanceObservation row : batch) visitor.accept(row);
        }
    }

    private static void requirePageSize(int size) {
        if (size <= 0 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("invalid source page size");
    }

    private static void requirePage(int limit, long afterId) {
        requirePageSize(limit);
        if (afterId < -1) throw new IllegalArgumentException("invalid source cursor");
    }

    private static void requireRevision(String expected, String actual) {
        if (expected == null || !expected.equals(actual))
            throw new IllegalStateException("source store changed; restart paging");
    }

    private static void requireParserRevision(int revision) {
        if (revision < 0) throw new IllegalArgumentException("negative parser revision");
    }

    private static void requireTransaction(Transaction transaction) {
        if (transaction.bank == null || transaction.bank.isEmpty())
            throw new IllegalArgumentException("transaction must have a bank");
    }

    private static String sourceIdentity(String physical) {
        return "source\n" + physical;
    }

    private static String transactionIdentity(String digest) {
        return "transaction\n" + digest;
    }

    private static String balanceIdentity(String digest) {
        return "balance\n" + digest;
    }

    private static ContentValues observationValues(Source source, String digest, JSONObject json,
            EncryptedRowCodec rows, String rowIdentity) throws Exception {
        ContentValues values = new ContentValues();
        values.put("source_id", source.id);
        values.put("source_digest", source.physicalIdentity);
        values.put("dedupe_digest", digest);
        values.put("payload", rows.encrypt(json.toString(), rowIdentity));
        return values;
    }

    private static JSONObject sourceJson(Source source) throws Exception {
        JSONObject json = new JSONObject();
        json.put("bank", source.bank);
        json.put("sender", source.sender);
        json.put("arrival", source.arrivalTime);
        json.put("body", source.rawBody == null ? JSONObject.NULL : source.rawBody);
        json.put("rule", source.ruleVersion);
        return json;
    }

    private static JSONObject transactionJson(int parserRevision, Transaction transaction)
            throws Exception {
        JSONObject json = new JSONObject();
        json.put("parser", parserRevision);
        json.put("bank", transaction.bank);
        json.put("account", transaction.account == null ? JSONObject.NULL : transaction.account);
        json.put("date", transaction.date);
        json.put("amount", transaction.amount);
        json.put("balance", transaction.balance == null ? JSONObject.NULL : transaction.balance);
        json.put("sig", transaction.sig == null ? JSONObject.NULL : transaction.sig);
        json.put("content", transaction.content == null ? JSONObject.NULL : transaction.content);
        return json;
    }

    private static JSONObject balanceJson(int parserRevision, String bank, String account,
            long date, long balance) throws Exception {
        JSONObject json = new JSONObject();
        json.put("parser", parserRevision);
        json.put("bank", bank);
        json.put("account", account == null ? JSONObject.NULL : account);
        json.put("date", date);
        json.put("balance", balance);
        return json;
    }

    private static String transactionDigest(Lookup lookup, String source, int parser,
            Transaction transaction) {
        return lookup.digest("transaction", source, Integer.toString(parser), transaction.bank,
            transaction.account, Long.toString(transaction.date), Long.toString(transaction.amount),
            transaction.balance == null ? null : Long.toString(transaction.balance), transaction.sig,
            transaction.content);
    }

    private static String balanceDigest(Lookup lookup, String source, int parser, String bank,
            String account, long date, long balance) {
        return lookup.digest("balance", source, Integer.toString(parser), bank, account,
            Long.toString(date), Long.toString(balance));
    }

    private static String meta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(META, new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        if (db.insertWithOnConflict(META, null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new SQLiteException("source metadata insert failed");
    }

    private static final class Session {
        final SQLiteDatabase db;
        final Lookup lookup;
        final EncryptedRowCodec rows;
        String revision;
        boolean changed;

        Session(SQLiteDatabase db) throws Exception {
            this.db = db;
            if (!"1".equals(meta(db, READY))) throw new Exception("incomplete source store");
            String encodedIndex = meta(db, INDEX_KEY);
            String encodedRows = meta(db, ROW_KEY);
            revision = meta(db, REVISION);
            if (encodedIndex == null || encodedRows == null || revision == null
                    || revision.isEmpty()) throw new Exception("incomplete source store");
            byte[] secret = null;
            EncryptedRowCodec opened = null;
            try {
                secret = Base64.decode(BalanceData.decryptStorePayload(encodedIndex), Base64.NO_WRAP);
                if (secret.length != 32) throw new Exception("invalid source lookup key");
                lookup = new Lookup(secret);
                opened = EncryptedRowCodec.open(encodedRows, ROW_DOMAIN);
                rows = opened;
                opened = null;
            } finally {
                if (secret != null) Arrays.fill(secret, (byte) 0);
                if (opened != null) opened.close();
            }
        }

        void touch() {
            revision = UUID.randomUUID().toString();
            putMeta(db, REVISION, revision);
        }

        Source requireSource(Source source) throws Exception {
            if (source == null) throw new NullPointerException("source");
            try (Cursor cursor = db.query(TABLE, SOURCE_COLUMNS, "id=? AND physical_digest=?",
                    new String[]{Long.toString(source.id), source.physicalIdentity}, null, null,
                    null, "1")) {
                if (!cursor.moveToFirst()) throw new IllegalStateException("source is not retained");
                return decodeSource(cursor);
            }
        }

        Source decodeSource(Cursor cursor) throws Exception {
            long id = cursor.getLong(0);
            String physical = requiredString(cursor.getString(1), "physical digest");
            String indexedContent = requiredString(cursor.getString(2), "content digest");
            String plain = rows.decrypt(cursor.getString(3), sourceIdentity(physical));
            JSONObject json = new JSONObject(plain);
            checkFields(json, "bank", "sender", "arrival", "body", "rule");
            String bank = requiredJsonString(json, "bank");
            String sender = requiredJsonString(json, "sender");
            long arrival = requiredJsonLong(json, "arrival");
            String body = nullableJsonString(json, "body");
            int rule = requiredJsonInt(json, "rule");
            if (!bank.equals(BankRules.resolve(sender))) throw new Exception("source bank mismatch");
            String expectedPhysical = lookup.physical(sender, body, arrival);
            String expectedContent = lookup.content(sender, body);
            if (!physical.equals(expectedPhysical) || !indexedContent.equals(expectedContent))
                throw new Exception("source identity does not match payload");
            return new Source(id, physical, indexedContent, bank, sender, arrival, body, rule);
        }

        TransactionObservation decodeTransaction(Cursor cursor) throws Exception {
            long id = cursor.getLong(0);
            long sourceId = cursor.getLong(1);
            String sourceDigest = requiredString(cursor.getString(2), "transaction source digest");
            String dedupe = requiredString(cursor.getString(3), "transaction dedupe digest");
            Source source = sourceForObservation(sourceId, sourceDigest);
            JSONObject json = new JSONObject(rows.decrypt(cursor.getString(4),
                transactionIdentity(dedupe)));
            checkFields(json, "parser", "bank", "account", "date", "amount", "balance", "sig",
                "content");
            int parser = requiredJsonInt(json, "parser");
            Transaction transaction = new Transaction(requiredJsonString(json, "bank"),
                nullableJsonString(json, "account"), requiredJsonLong(json, "date"),
                requiredJsonLong(json, "amount"), nullableJsonLong(json, "balance"),
                nullableJsonString(json, "sig"), nullableJsonString(json, "content"));
            requireTransaction(transaction);
            if (!source.bank.equals(transaction.bank)) throw new Exception("transaction bank mismatch");
            if (!dedupe.equals(transactionDigest(lookup, source.physicalIdentity, parser, transaction)))
                throw new Exception("transaction observation identity mismatch");
            return new TransactionObservation(id, sourceId, sourceDigest, parser, transaction);
        }

        BalanceObservation decodeBalance(Cursor cursor) throws Exception {
            long id = cursor.getLong(0);
            long sourceId = cursor.getLong(1);
            String sourceDigest = requiredString(cursor.getString(2), "balance source digest");
            String dedupe = requiredString(cursor.getString(3), "balance dedupe digest");
            Source source = sourceForObservation(sourceId, sourceDigest);
            JSONObject json = new JSONObject(rows.decrypt(cursor.getString(4),
                balanceIdentity(dedupe)));
            checkFields(json, "parser", "bank", "account", "date", "balance");
            int parser = requiredJsonInt(json, "parser");
            String bank = requiredJsonString(json, "bank");
            String account = nullableJsonString(json, "account");
            long date = requiredJsonLong(json, "date");
            long balance = requiredJsonLong(json, "balance");
            if (!source.bank.equals(bank)) throw new Exception("balance bank mismatch");
            if (!dedupe.equals(balanceDigest(lookup, source.physicalIdentity, parser, bank, account,
                    date, balance))) throw new Exception("balance observation identity mismatch");
            return new BalanceObservation(id, sourceId, sourceDigest, parser, bank, account, date,
                balance);
        }

        private Source sourceForObservation(long sourceId, String sourceDigest) throws Exception {
            try (Cursor cursor = db.query(TABLE, SOURCE_COLUMNS, "id=? AND physical_digest=?",
                    new String[]{Long.toString(sourceId), sourceDigest}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) throw new Exception("observation source is missing");
                return decodeSource(cursor);
            }
        }

        SourcePage sourcePage(long afterId, int limit) throws Exception {
            List<Source> rows = new ArrayList<>(limit);
            long next = afterId;
            boolean more = false;
            try (Cursor cursor = db.query(TABLE, SOURCE_COLUMNS, "id>?",
                    new String[]{Long.toString(afterId)}, null, null, "id ASC",
                    Integer.toString(limit + 1))) {
                while (cursor.moveToNext()) {
                    if (rows.size() == limit) {
                        more = true;
                        break;
                    }
                    Source source = decodeSource(cursor);
                    if (source.id <= next) throw new Exception("invalid source order");
                    rows.add(source);
                    next = source.id;
                }
            }
            return new SourcePage(rows, next, more, revision);
        }

        TransactionPage transactionPage(long afterId, int limit) throws Exception {
            List<TransactionObservation> rows = new ArrayList<>(limit);
            long next = afterId;
            boolean more = false;
            try (Cursor cursor = db.query(TRANSACTION_TABLE, OBSERVATION_COLUMNS, "id>?",
                    new String[]{Long.toString(afterId)}, null, null, "id ASC",
                    Integer.toString(limit + 1))) {
                while (cursor.moveToNext()) {
                    if (rows.size() == limit) {
                        more = true;
                        break;
                    }
                    TransactionObservation row = decodeTransaction(cursor);
                    if (row.id <= next) throw new Exception("invalid transaction observation order");
                    rows.add(row);
                    next = row.id;
                }
            }
            return new TransactionPage(rows, next, more, revision);
        }

        BalancePage balancePage(long afterId, int limit) throws Exception {
            List<BalanceObservation> rows = new ArrayList<>(limit);
            long next = afterId;
            boolean more = false;
            try (Cursor cursor = db.query(BALANCE_TABLE, OBSERVATION_COLUMNS, "id>?",
                    new String[]{Long.toString(afterId)}, null, null, "id ASC",
                    Integer.toString(limit + 1))) {
                while (cursor.moveToNext()) {
                    if (rows.size() == limit) {
                        more = true;
                        break;
                    }
                    BalanceObservation row = decodeBalance(cursor);
                    if (row.id <= next) throw new Exception("invalid balance observation order");
                    rows.add(row);
                    next = row.id;
                }
            }
            return new BalancePage(rows, next, more, revision);
        }

        void close() {
            rows.close();
        }
    }

    private static final class Lookup {
        private final Mac mac;

        Lookup(byte[] key) throws Exception {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
        }

        String physical(String sender, String body, long arrival) {
            return digest("physical", sender, Long.toString(arrival), body);
        }

        String content(String sender, String body) {
            return digest("content", sender, body);
        }

        String digest(String kind, String... values) {
            try {
                update(mac, kind);
                for (String value : values) update(mac, value);
                byte[] result = mac.doFinal();
                try {
                    return Base64.encodeToString(result, Base64.NO_WRAP);
                } finally {
                    Arrays.fill(result, (byte) 0);
                }
            } catch (Exception e) {
                throw new IllegalStateException("source lookup digest failed", e);
            }
        }

        private static void update(Mac mac, String value) {
            if (value == null) {
                mac.update((byte) 0);
                mac.update(new byte[]{0, 0, 0, 0});
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            try {
                mac.update((byte) 1);
                int length = bytes.length;
                mac.update((byte) (length >>> 24));
                mac.update((byte) (length >>> 16));
                mac.update((byte) (length >>> 8));
                mac.update((byte) length);
                mac.update(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static void checkFields(JSONObject object, String... allowed) throws Exception {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!names.contains(key)) throw new Exception("unknown source payload field");
        }
        for (String key : allowed) if (!object.has(key))
            throw new Exception("missing source payload field");
    }

    private static String requiredString(String value, String name) throws Exception {
        if (value == null || value.isEmpty()) throw new Exception("missing " + name);
        return value;
    }

    private static String requiredJsonString(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty())
            throw new Exception("invalid source " + key);
        return (String) value;
    }

    private static String nullableJsonString(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (value == JSONObject.NULL) return null;
        if (!(value instanceof String)) throw new Exception("invalid source " + key);
        return (String) value;
    }

    private static long requiredJsonLong(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new Exception("invalid source " + key);
        if (value instanceof Double || value instanceof Float) {
            double decimal = ((Number) value).doubleValue();
            if (Double.isNaN(decimal) || Double.isInfinite(decimal)
                    || decimal != Math.rint(decimal)
                    || decimal < Long.MIN_VALUE || decimal > Long.MAX_VALUE)
                throw new Exception("invalid source " + key);
        }
        long result = ((Number) value).longValue();
        if (value instanceof java.math.BigDecimal
                && ((java.math.BigDecimal) value).longValueExact() != result)
            throw new Exception("invalid source " + key);
        return result;
    }

    private static Long nullableJsonLong(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (value == JSONObject.NULL) return null;
        if (!(value instanceof Number)) throw new Exception("invalid source " + key);
        return ((Number) value).longValue();
    }

    private static int requiredJsonInt(JSONObject object, String key) throws Exception {
        long value = requiredJsonLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new Exception("invalid source " + key);
        return (int) value;
    }

    private static final class FileSet {
        private final java.io.File database;

        FileSet(Context context) {
            database = context.getDatabasePath(DB_NAME);
        }

        java.io.File[] all() {
            return new java.io.File[]{database, new java.io.File(database.getPath() + "-wal"),
                new java.io.File(database.getPath() + "-shm"),
                new java.io.File(database.getPath() + "-journal")};
        }

        boolean hasSidecar() {
            for (java.io.File file : all()) if (!file.equals(database) && file.exists()) return true;
            return false;
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            // The default handler may delete a corrupt database. Preserve it for recovery instead.
            super(context, DB_NAME, null, DB_VERSION, db -> {
                throw new SQLiteException("source database is corrupt; preserved for recovery");
            });
        }

        @Override public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " physical_digest TEXT NOT NULL, content_digest TEXT NOT NULL,"
                + " payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE " + TRANSACTION_TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " source_id INTEGER NOT NULL, source_digest TEXT NOT NULL,"
                + " dedupe_digest TEXT NOT NULL, payload TEXT NOT NULL,"
                + " FOREIGN KEY(source_id) REFERENCES " + TABLE + "(id) ON DELETE CASCADE)");
            db.execSQL("CREATE TABLE " + BALANCE_TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " source_id INTEGER NOT NULL, source_digest TEXT NOT NULL,"
                + " dedupe_digest TEXT NOT NULL, payload TEXT NOT NULL,"
                + " FOREIGN KEY(source_id) REFERENCES " + TABLE + "(id) ON DELETE CASCADE)");
            db.execSQL("CREATE TABLE " + META + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX sources_physical ON " + TABLE + "(physical_digest)");
            db.execSQL("CREATE INDEX sources_content ON " + TABLE + "(content_digest)");
            db.execSQL("CREATE UNIQUE INDEX transactions_dedupe ON " + TRANSACTION_TABLE
                + "(dedupe_digest)");
            db.execSQL("CREATE UNIQUE INDEX balances_dedupe ON " + BALANCE_TABLE
                + "(dedupe_digest)");
            db.execSQL("CREATE INDEX transactions_source ON " + TRANSACTION_TABLE + "(source_id, id)");
            db.execSQL("CREATE INDEX balances_source ON " + BALANCE_TABLE + "(source_id, id)");
            byte[] key = new byte[32];
            try {
                new SecureRandom().nextBytes(key);
                putMeta(db, INDEX_KEY, BalanceData.encryptStorePayload(
                    Base64.encodeToString(key, Base64.NO_WRAP)));
                putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
                putMeta(db, REVISION, UUID.randomUUID().toString());
                putMeta(db, READY, "1");
            } catch (Exception failure) {
                SQLiteException error = new SQLiteException("source store initialization failed");
                error.initCause(failure);
                throw error;
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new SQLiteException("unsupported source database version");
        }

        @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new SQLiteException("source database downgrade is not supported");
        }
    }
}
