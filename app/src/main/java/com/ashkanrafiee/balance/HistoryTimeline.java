package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Encrypted, disposable history row timeline.
 *
 * <p>The history summary can be larger than the part currently visible. Keeping the complete
 * filtered row set in this private SQLite spool makes that fact explicit without retaining the
 * rows or their metadata in the heap. Pages use the stable display order ({@code date DESC,
 * tie ASC}) and keyset cursors, so a page is never a first-N truncation of an unsorted input.
 * Transactions and residuals have separate encrypted payload identities; the database contains
 * only an opaque day key, ordering columns, type and ciphertext.
 *
 * <p>A snapshot is handed to the caller only after its staging transaction commits. The caller owns
 * it and must close it when a newer history render replaces it.
 */
final class HistoryTimeline implements AutoCloseable {
    static final int DEFAULT_PAGE_SIZE = 128;

    private static final String TABLE = "history_timeline";
    private static final String FILE_PREFIX = "history-timeline-";
    private static final String KEY_PREFIX = "history-timeline-key-";
    private static final String CODEC_DOMAIN = "history-timeline";
    private static final int TRANSACTION = 1;
    private static final int RESIDUAL = 0;

    /** The immutable keyset position of a displayed row. */
    static final class CursorKey {
        final long date;
        final long tie;

        CursorKey(long date, long tie) {
            this.date = date;
            this.tie = tie;
        }
    }

    /** One decoded row in a page. Exactly one payload is non-null. */
    static final class Entry {
        final long date;
        final long tie;
        final Transaction transaction;
        final Residual residual;

        Entry(long date, long tie, Transaction transaction, Residual residual) {
            this.date = date;
            this.tie = tie;
            this.transaction = transaction;
            this.residual = residual;
        }

        CursorKey cursor() {
            return new CursorKey(date, tie);
        }
    }

    /** One day page and the controls needed to request its adjacent pages. */
    static final class Page {
        final String dayKey;
        final List<Entry> entries;
        final CursorKey first;
        final CursorKey last;
        final boolean hasPrevious;
        final boolean hasMore;

        Page(String dayKey, List<Entry> entries, boolean hasPrevious, boolean hasMore) {
            this.dayKey = dayKey;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.first = entries.isEmpty() ? null : entries.get(0).cursor();
            this.last = entries.isEmpty() ? null : entries.get(entries.size() - 1).cursor();
            this.hasPrevious = hasPrevious;
            this.hasMore = hasMore;
        }
    }

    /** Creates an encrypted staging transaction. */
    static Builder open(Context context) throws Exception {
        if (context == null) throw new NullPointerException("context");
        return Builder.open(context);
    }

    /** A writer that accepts arbitrary input order and creates the committed timeline snapshot. */
    static final class Builder implements AutoCloseable {
        private SQLiteDatabase db;
        private EncryptedRowCodec codec;
        private File databaseFile;
        private File wrappedKeyFile;
        private boolean transactionOpen;
        private boolean finished;

        private Builder() {}

        static Builder open(Context context) throws Exception {
            Builder builder = new Builder();
            try {
                builder.init(context);
                return builder;
            } catch (Throwable failure) {
                Exception cleanup = builder.closeAndDelete();
                if (cleanup != null) failure.addSuppressed(cleanup);
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new RuntimeException(failure);
            }
        }

        private void init(Context context) throws Exception {
            File directory = context.getNoBackupFilesDir();
            if (directory == null) throw new Exception("no-backup directory unavailable");
            databaseFile = File.createTempFile(FILE_PREFIX, ".db", directory);
            wrappedKeyFile = File.createTempFile(KEY_PREFIX, ".key", directory);
            String wrappedKey = EncryptedRowCodec.createWrappedKey();
            byte[] encoded = wrappedKey.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(wrappedKeyFile)) {
                output.write(encoded);
                output.getFD().sync();
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
            codec = EncryptedRowCodec.open(wrappedKey, CODEC_DOMAIN);
            db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
            db.execSQL("PRAGMA cache_size=-2048");
            db.execSQL("PRAGMA temp_store=FILE");
            db.execSQL("CREATE TABLE " + TABLE + " ("
                + "day TEXT NOT NULL, date INTEGER NOT NULL, tie INTEGER NOT NULL,"
                + "type INTEGER NOT NULL, payload TEXT NOT NULL,"
                + "PRIMARY KEY(day, date, tie))");
            db.execSQL("CREATE INDEX history_timeline_order ON " + TABLE
                + "(day, date DESC, tie ASC)");
            db.beginTransactionNonExclusive();
            transactionOpen = true;
        }

        void addTransaction(String dayKey, Transaction transaction, long sourceOrdinal)
                throws Exception {
            if (transaction == null) throw new NullPointerException("transaction");
            insert(dayKey, transaction.date, tie(TRANSACTION, sourceOrdinal), TRANSACTION,
                BalanceData.serializeTransactions(Collections.singletonList(transaction)));
        }

        void addResidual(String dayKey, Residual residual, long sourceOrdinal) throws Exception {
            if (residual == null) throw new NullPointerException("residual");
            JSONObject payload = new JSONObject()
                .put("version", 1)
                .put("bank", residual.bank)
                .put("account", residual.account == null ? JSONObject.NULL : residual.account)
                .put("fromDate", residual.fromDate)
                .put("toDate", residual.toDate)
                .put("amount", residual.amount)
                .put("movements", residual.movements);
            insert(dayKey, residual.toDate, tie(RESIDUAL, sourceOrdinal), RESIDUAL,
                payload.toString());
        }

        private void insert(String dayKey, long date, long tie, int type, String plain)
                throws Exception {
            if (finished || !transactionOpen) throw new IllegalStateException("timeline is closed");
            if (dayKey == null || dayKey.isEmpty()) throw new IllegalArgumentException("invalid day");
            ContentValues values = new ContentValues();
            values.put("day", dayKey);
            values.put("date", date);
            values.put("tie", tie);
            values.put("type", type);
            values.put("payload", codec.encrypt(plain, identity(dayKey, date, tie, type)));
            if (db.insertOrThrow(TABLE, null, values) < 0)
                throw new Exception("history timeline insert failed");
        }

        /** Commits the spool and transfers file ownership to the returned snapshot. */
        HistoryTimeline finish() throws Exception {
            if (finished || !transactionOpen) throw new IllegalStateException("timeline is closed");
            db.setTransactionSuccessful();
            db.endTransaction();
            transactionOpen = false;
            finished = true;
            HistoryTimeline snapshot = new HistoryTimeline(db, codec, databaseFile, wrappedKeyFile);
            db = null;
            codec = null;
            databaseFile = null;
            wrappedKeyFile = null;
            return snapshot;
        }

        private static long tie(int type, long ordinal) {
            if (ordinal < 0 || ordinal > (Long.MAX_VALUE - type) / 2)
                throw new IllegalStateException("history timeline ordinal exhausted");
            return ordinal * 2 + type;
        }

        private Exception closeAndDelete() {
            Exception failure = null;
            if (transactionOpen && db != null) {
                try { db.endTransaction(); } catch (Exception e) { failure = e; }
                transactionOpen = false;
            }
            if (codec != null) {
                try { codec.close(); } catch (Exception e) { failure = append(failure, e); }
                codec = null;
            }
            if (db != null) {
                try { db.close(); } catch (Exception e) { failure = append(failure, e); }
                db = null;
            }
            failure = delete(wrappedKeyFile, failure);
            wrappedKeyFile = null;
            if (databaseFile != null) {
                failure = delete(databaseFile, failure);
                failure = delete(new File(databaseFile.getPath() + "-wal"), failure);
                failure = delete(new File(databaseFile.getPath() + "-shm"), failure);
                failure = delete(new File(databaseFile.getPath() + "-journal"), failure);
                databaseFile = null;
            }
            return failure;
        }

        @Override public void close() throws Exception {
            if (!finished) {
                Exception failure = closeAndDelete();
                if (failure != null) throw failure;
                finished = true;
            }
        }
    }

    private final SQLiteDatabase db;
    private final EncryptedRowCodec codec;
    private File databaseFile;
    private File wrappedKeyFile;
    private boolean closed;

    private HistoryTimeline(SQLiteDatabase db, EncryptedRowCodec codec, File databaseFile,
            File wrappedKeyFile) {
        this.db = db;
        this.codec = codec;
        this.databaseFile = databaseFile;
        this.wrappedKeyFile = wrappedKeyFile;
    }

    synchronized Page firstPage(String dayKey, int limit) throws Exception {
        return page(dayKey, null, false, limit);
    }

    synchronized Page nextPage(String dayKey, CursorKey after, int limit) throws Exception {
        if (after == null) throw new NullPointerException("after cursor");
        return page(dayKey, after, false, limit);
    }

    synchronized Page previousPage(String dayKey, CursorKey before, int limit) throws Exception {
        if (before == null) throw new NullPointerException("before cursor");
        return page(dayKey, before, true, limit);
    }

    private Page page(String dayKey, CursorKey cursor, boolean before, int limit) throws Exception {
        checkOpen();
        if (dayKey == null || dayKey.isEmpty()) throw new IllegalArgumentException("invalid day");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("invalid page size");

        String where = "day=?";
        List<String> args = new ArrayList<>();
        args.add(dayKey);
        if (cursor != null) {
            if (before) {
                where += " AND (date > ? OR (date=? AND tie < ?))";
            } else {
                where += " AND (date < ? OR (date=? AND tie > ?))";
            }
            args.add(Long.toString(cursor.date));
            args.add(Long.toString(cursor.date));
            args.add(Long.toString(cursor.tie));
        }
        List<Entry> entries = new ArrayList<>(limit);
        boolean extra = false;
        String order = before ? "date ASC, tie DESC" : "date DESC, tie ASC";
        try (Cursor rows = db.query(TABLE, new String[]{"date", "tie", "type", "payload"},
                where, args.toArray(new String[0]), null, null, order,
                Integer.toString(limit + 1))) {
            while (rows.moveToNext()) {
                if (entries.size() == limit) {
                    extra = true;
                    break;
                }
                long date = rows.getLong(0);
                long tie = rows.getLong(1);
                int type = rows.getInt(2);
                String payload = rows.getString(3);
                entries.add(decode(dayKey, date, tie, type, payload));
            }
        }
        if (before) java.util.Collections.reverse(entries);

        boolean hasPrevious;
        boolean hasMore;
        if (before) {
            hasPrevious = extra;
            hasMore = cursor != null && !entries.isEmpty();
        } else {
            hasPrevious = cursor != null && !entries.isEmpty();
            hasMore = extra;
        }
        return new Page(dayKey, entries, hasPrevious, hasMore);
    }

    private Entry decode(String dayKey, long date, long tie, int type, String payload)
            throws Exception {
        String plain = codec.decrypt(payload, identity(dayKey, date, tie, type));
        if (type == TRANSACTION) {
            final Transaction[] found = {null};
            TransactionStore.readJson(new StringReader(plain), transaction -> {
                if (found[0] != null) throw new Exception("multiple timeline transactions");
                found[0] = transaction;
            });
            Transaction transaction = found[0];
            if (transaction == null || transaction.date != date)
                throw new Exception("invalid timeline transaction");
            return new Entry(date, tie, transaction, null);
        }
        if (type == RESIDUAL) {
            JSONObject object = new JSONObject(plain);
            if (object.getInt("version") != 1 || object.getLong("toDate") != date)
                throw new Exception("invalid timeline residual");
            String bank = object.getString("bank");
            String account = object.isNull("account") ? null : object.getString("account");
            Residual residual = new Residual(bank, account, object.getLong("fromDate"), date,
                object.getLong("amount"), object.getInt("movements"));
            return new Entry(date, tie, null, residual);
        }
        throw new Exception("invalid timeline row type");
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("history timeline is closed");
    }

    private static String identity(String dayKey, long date, long tie, int type) {
        return "day\n" + dayKey + "\ndate\n" + date + "\ntie\n" + tie + "\ntype\n" + type;
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        Exception failure = null;
        try { codec.close(); } catch (Exception e) { failure = e; }
        try { db.close(); } catch (Exception e) { failure = append(failure, e); }
        failure = delete(wrappedKeyFile, failure);
        wrappedKeyFile = null;
        if (databaseFile != null) {
            failure = delete(databaseFile, failure);
            failure = delete(new File(databaseFile.getPath() + "-wal"), failure);
            failure = delete(new File(databaseFile.getPath() + "-shm"), failure);
            failure = delete(new File(databaseFile.getPath() + "-journal"), failure);
            databaseFile = null;
        }
        if (failure != null) throw failure;
    }

    private static Exception append(Exception first, Exception next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static Exception delete(File file, Exception failure) {
        try {
            if (file != null && file.exists() && !file.delete() && file.exists())
                throw new Exception("history timeline file could not be deleted");
        } catch (Exception e) {
            return append(failure, e);
        }
        return failure;
    }
}
