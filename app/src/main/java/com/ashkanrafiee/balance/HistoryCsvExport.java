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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded-memory CSV export for a filtered history.
 *
 * <p>The caller supplies synchronous movement and residual sources. The selected rows first land in
 * a disposable SQLite database with an encrypted payload and a session-only HMAC slot; only then
 * does the exporter walk the date/kind/source-ordinal index and write rows to the destination. This
 * keeps the heap independent of history size while preserving the stable source order used by
 * {@link CsvExport#csv(Context, List, List, CsvExport.Text)}. Residuals have the lower kind ordinal,
 * so a residual at the same instant is emitted before a movement.
 *
 * <p>The destination writer is caller-owned and is never closed. It is flushed exactly once, after
 * all sources, payload checks, row formatting and staging cleanup have reached their successful
 * completion point. Source and writer failures propagate to the caller.
 */
final class HistoryCsvExport {
    static final int DEFAULT_PAGE_SIZE = CsvExport.STREAM_PAGE_SIZE;

    static final String STAGING_FILE_PREFIX = "history-csv-";
    static final String WRAPPED_KEY_FILE_PREFIX = "history-csv-key-";

    private static final String CODEC_DOMAIN = "history-csv-staging";
    private static final int HMAC_KEY_BYTES = 32;
    private static final int KIND_RESIDUAL = 0;
    private static final int KIND_MOVEMENT = 1;
    private static final String ROWS = "history_csv_rows";
    private static final String[] ROW_COLUMNS = {"kind", "slot", "date", "ordinal", "payload"};

    /** A synchronous source of the rows that survived the caller's residual filter. */
    interface ResidualSource {
        void forEach(HistoryResidualReader.Visitor visitor) throws Exception;
    }

    /** Supplies both CSV kinds from a single store pass: movements as they stream, then the
     *  residuals detected over that same pass. Lets the caller stage the bank/account scope once
     *  (e.g. through {@link HistoryResidualReader.Staging}) instead of re-reading the whole store
     *  for the residual walk, so movements and residuals always come from the same snapshot. */
    interface CombinedSource {
        void forEach(TransactionStore.Visitor movements, HistoryResidualReader.Visitor residuals)
            throws Exception;
    }

    private HistoryCsvExport() {}

    /** Writes a filtered history using metadata maps that the caller has already loaded. */
    static void write(Context context, int pageSize, TransactionStore.StreamSource transactions,
            ResidualSource residuals, Writer writer, CsvExport.Text text) throws Exception {
        writeLookup(context, pageSize, transactions, residuals, writer, CsvExport.lookup(text));
    }

    /** Same as {@link #write(Context, int, TransactionStore.StreamSource, ResidualSource, Writer,
     * CsvExport.Text)},
     * using the default staging page size. */
    static void write(Context context, TransactionStore.StreamSource transactions,
            ResidualSource residuals,
            Writer writer, CsvExport.Text text) throws Exception {
        write(context, DEFAULT_PAGE_SIZE, transactions, residuals, writer, text);
    }

    /**
     * Writes a filtered history with point metadata lookups. Each movement asks for only its own
     * note, reason, channel and tags while its encrypted payload is being emitted.
     */
    static void writeLookup(Context context, int pageSize, TransactionStore.StreamSource transactions,
            ResidualSource residuals, Writer writer, CsvExport.TextLookup text) throws Exception {
        writeLookup(context, pageSize, transactions, residuals, writer, text, null);
    }

    /** Same as {@link #writeLookup(Context, int, TransactionStore.StreamSource, ResidualSource,
     *  Writer, CsvExport.TextLookup)} with advisory progress: gathering while staging, then a
     *  determinate write over the staged row count. */
    static void writeLookup(Context context, int pageSize, TransactionStore.StreamSource transactions,
            ResidualSource residuals, Writer writer, CsvExport.TextLookup text,
            WorkProgress progress) throws Exception {
        requireContext(context);
        requirePageSize(pageSize);
        if (transactions == null) throw new NullPointerException("transactions");
        if (writer == null) throw new NullPointerException("writer");
        if (text == null) text = CsvExport.lookup(null);

        Stage stage = Stage.open(context);
        Throwable operationFailure = null;
        try {
            if (progress != null) progress.stage(R.string.export_stage_preparing);
            stage.stageTransactions(transactions);
            if (residuals != null) stage.stageResiduals(residuals);

            emitStaged(context, stage, pageSize, writer, text, progress);
        } catch (Throwable failure) {
            operationFailure = failure;
        }
        Exception cleanupFailure = stage.closeAndDelete();
        if (cleanupFailure != null) {
            if (operationFailure == null) operationFailure = cleanupFailure;
            else operationFailure.addSuppressed(cleanupFailure);
        }
        if (operationFailure != null) throwFailure(operationFailure);
        // Do not flush from a catch/finally path. A flush is the completion signal only after every
        // source row, payload check, row write and temporary-file cleanup succeeds.
        writer.flush();
    }

    /** Writes a filtered history whose movements and residuals arrive from one store pass (see
     *  {@link CombinedSource}), without holding any store monitor across the whole export: every
     *  row still comes from the same snapshot because the caller stages one pass, while each store
     *  keeps its own short per-operation locking. */
    static void writeCombined(Context context, int pageSize, CombinedSource source, Writer writer,
            CsvExport.TextLookup text) throws Exception {
        writeCombined(context, pageSize, source, writer, text, null);
    }

    /** Same as {@link #writeCombined(Context, int, CombinedSource, Writer, CsvExport.TextLookup)}
     *  with advisory progress: gathering while staging, then a determinate write over the staged
     *  row count. */
    static void writeCombined(Context context, int pageSize, CombinedSource source, Writer writer,
            CsvExport.TextLookup text, WorkProgress progress) throws Exception {
        requireContext(context);
        requirePageSize(pageSize);
        if (source == null) throw new NullPointerException("source");
        if (writer == null) throw new NullPointerException("writer");
        if (text == null) text = CsvExport.lookup(null);

        Stage stage = Stage.open(context);
        Throwable operationFailure = null;
        try {
            if (progress != null) progress.stage(R.string.export_stage_preparing);
            stage.stageCombined(source);

            emitStaged(context, stage, pageSize, writer, text, progress);
        } catch (Throwable failure) {
            operationFailure = failure;
        }
        Exception cleanupFailure = stage.closeAndDelete();
        if (cleanupFailure != null) {
            if (operationFailure == null) operationFailure = cleanupFailure;
            else operationFailure.addSuppressed(cleanupFailure);
        }
        if (operationFailure != null) throwFailure(operationFailure);
        // Same completion signal as the two-source path: flush only after every staged row,
        // payload check, row write and temporary-file cleanup succeeds.
        writer.flush();
    }

    private static void emitStaged(Context context, Stage stage, int pageSize, Writer writer,
            CsvExport.TextLookup text, WorkProgress progress) throws Exception {
        CsvExport.CellWriter cells = new CsvExport.CellWriter(context);
        cells.header(writer);
        if (progress != null) progress.stage(R.string.export_stage_writing);
        stage.emit(writer, cells, text, pageSize, progress,
            progress == null ? 0 : stage.count());
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new RuntimeException(failure);
    }

    /** Same point-lookup export using the default staging page size. */
    static void writeLookup(Context context, TransactionStore.StreamSource transactions,
            ResidualSource residuals, Writer writer, CsvExport.TextLookup text) throws Exception {
        writeLookup(context, DEFAULT_PAGE_SIZE, transactions, residuals, writer, text);
    }

    private static void requireContext(Context context) {
        if (context == null) throw new NullPointerException("context");
    }

    private static void requirePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > TransactionStore.MAX_PAGE_SIZE)
            throw new IllegalArgumentException("invalid page size");
    }

    private static void requireOrdinal(long ordinal, String message) {
        if (ordinal < 0) throw new IllegalStateException(message);
    }

    private static void consumeTransactions(TransactionStore.StreamSource source,
            TransactionStore.Visitor sink)
            throws Exception {
        Thread owner = Thread.currentThread();
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            source.forEach(transaction -> {
                if (!open.get() || Thread.currentThread() != owner) {
                    Exception error = new IllegalStateException(
                        "transaction producer must be synchronous");
                    failure.compareAndSet(null, error);
                    throw error;
                }
                if (failure.get() != null) throw failure.get();
                try {
                    sink.accept(transaction);
                } catch (Exception error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
            });
            if (failure.get() != null) throw failure.get();
        } finally {
            open.set(false);
        }
    }

    private static void consumeResiduals(ResidualSource source, HistoryResidualReader.Visitor sink)
            throws Exception {
        Thread owner = Thread.currentThread();
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            source.forEach(residual -> {
                if (!open.get() || Thread.currentThread() != owner) {
                    Exception error = new IllegalStateException(
                        "residual producer must be synchronous");
                    failure.compareAndSet(null, error);
                    throw error;
                }
                if (failure.get() != null) throw failure.get();
                try {
                    sink.accept(residual);
                } catch (Exception error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
            });
            if (failure.get() != null) throw failure.get();
        } finally {
            open.set(false);
        }
    }

    private static void consumeCombined(CombinedSource source, TransactionStore.Visitor movements,
            HistoryResidualReader.Visitor residuals) throws Exception {
        Thread owner = Thread.currentThread();
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            source.forEach(
                transaction -> acceptSynchronous(open, owner, failure, "movement",
                    () -> movements.accept(transaction)),
                residual -> acceptSynchronous(open, owner, failure, "residual",
                    () -> residuals.accept(residual)));
            if (failure.get() != null) throw failure.get();
        } finally {
            open.set(false);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static void acceptSynchronous(AtomicBoolean open, Thread owner,
            AtomicReference<Exception> failure, String kind, ThrowingRunnable accept)
            throws Exception {
        if (!open.get() || Thread.currentThread() != owner) {
            Exception error = new IllegalStateException(kind + " producer must be synchronous");
            failure.compareAndSet(null, error);
            throw error;
        }
        if (failure.get() != null) throw failure.get();
        try {
            accept.run();
        } catch (Exception error) {
            failure.compareAndSet(null, error);
            throw error;
        }
    }

    private static final class Stage implements AutoCloseable {
        SQLiteDatabase db;
        EncryptedRowCodec codec;
        Mac slotMac;
        File databaseFile;
        File wrappedKeyFile;

        static Stage open(Context context) throws Exception {
            Stage stage = new Stage();
            try {
                stage.init(context);
                return stage;
            } catch (Throwable failure) {
                Exception cleanup = stage.closeAndDelete();
                if (cleanup != null) failure.addSuppressed(cleanup);
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new RuntimeException(failure);
            }
        }

        private void init(Context context) throws Exception {
            File directory = context.getNoBackupFilesDir();
            if (directory == null) throw new Exception("no-backup directory unavailable");
            databaseFile = File.createTempFile(STAGING_FILE_PREFIX, ".db", directory);

            wrappedKeyFile = File.createTempFile(WRAPPED_KEY_FILE_PREFIX, ".key", directory);
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
            createTables();

            byte[] hmacKey = new byte[HMAC_KEY_BYTES];
            try {
                new SecureRandom().nextBytes(hmacKey);
                slotMac = Mac.getInstance("HmacSHA256");
                slotMac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            } finally {
                Arrays.fill(hmacKey, (byte) 0);
            }
        }

        private void createTables() {
            db.execSQL("CREATE TABLE " + ROWS + " ("
                + "kind INTEGER NOT NULL CHECK(kind IN (0,1)),"
                + "slot TEXT NOT NULL, date INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + "payload TEXT NOT NULL, PRIMARY KEY(kind, ordinal))");
            db.execSQL("CREATE INDEX history_csv_rows_order ON " + ROWS
                + "(date ASC, kind ASC, ordinal ASC)");
        }

        void stageTransactions(TransactionStore.StreamSource source) throws Exception {
            db.beginTransactionNonExclusive();
            try {
                final long[] nextOrdinal = {0};
                consumeTransactions(source, transaction -> {
                    requireOrdinal(nextOrdinal[0], "movement ordinal exhausted");
                    long ordinal = nextOrdinal[0];
                    nextOrdinal[0] = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
                    insertMovement(ordinal, transaction);
                });
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        void stageResiduals(ResidualSource source) throws Exception {
            db.beginTransactionNonExclusive();
            try {
                final long[] nextOrdinal = {0};
                consumeResiduals(source, residual -> {
                    requireOrdinal(nextOrdinal[0], "residual ordinal exhausted");
                    long ordinal = nextOrdinal[0];
                    nextOrdinal[0] = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
                    insertResidual(ordinal, residual);
                });
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        void stageCombined(CombinedSource source) throws Exception {
            db.beginTransactionNonExclusive();
            try {
                final long[] nextMovement = {0};
                final long[] nextResidual = {0};
                consumeCombined(source,
                    transaction -> {
                        requireOrdinal(nextMovement[0], "movement ordinal exhausted");
                        long ordinal = nextMovement[0];
                        nextMovement[0] = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
                        insertMovement(ordinal, transaction);
                    },
                    residual -> {
                        requireOrdinal(nextResidual[0], "residual ordinal exhausted");
                        long ordinal = nextResidual[0];
                        nextResidual[0] = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
                        insertResidual(ordinal, residual);
                    });
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        private void insertMovement(long ordinal, Transaction transaction) throws Exception {
            if (transaction == null || transaction.bank == null || transaction.bank.isEmpty())
                throw new IllegalArgumentException("transaction must have a bank");
            String slot = slot(transaction.bank, transaction.account);
            ContentValues values = new ContentValues();
            values.put("kind", KIND_MOVEMENT);
            values.put("slot", slot);
            values.put("date", transaction.date);
            values.put("ordinal", ordinal);
            values.put("payload", codec.encrypt(BalanceData.serializeTransactions(
                Collections.singletonList(transaction)), identity(KIND_MOVEMENT, slot,
                    transaction.date, ordinal)));
            db.insertOrThrow(ROWS, null, values);
        }

        private void insertResidual(long ordinal, Residual residual) throws Exception {
            validateResidual(residual);
            String slot = slot(residual.bank, residual.account);
            JSONObject object = new JSONObject()
                .put("version", 1)
                .put("bank", residual.bank)
                .put("account", residual.account == null ? JSONObject.NULL : residual.account)
                .put("fromDate", residual.fromDate)
                .put("toDate", residual.toDate)
                .put("amount", residual.amount)
                .put("movements", residual.movements);
            ContentValues values = new ContentValues();
            values.put("kind", KIND_RESIDUAL);
            values.put("slot", slot);
            values.put("date", residual.toDate);
            values.put("ordinal", ordinal);
            values.put("payload", codec.encrypt(object.toString(), identity(KIND_RESIDUAL, slot,
                residual.toDate, ordinal)));
            db.insertOrThrow(ROWS, null, values);
        }

        void emit(Writer writer, CsvExport.CellWriter cells, CsvExport.TextLookup text,
                int pageSize) throws Exception {
            emit(writer, cells, text, pageSize, null, 0);
        }

        void emit(Writer writer, CsvExport.CellWriter cells, CsvExport.TextLookup text,
                int pageSize, WorkProgress progress, long total) throws Exception {
            CursorPage page = new CursorPage(db, pageSize);
            long done = 0;
            try {
                while (page.next()) {
                    while (page.cursor.moveToNext()) {
                        int kind = page.cursor.getInt(0);
                        String slot = page.cursor.getString(1);
                        long date = page.cursor.getLong(2);
                        long ordinal = page.cursor.getLong(3);
                        String payload = page.cursor.getString(4);
                        if (kind == KIND_RESIDUAL) {
                            Residual residual = decodeResidual(slot, date, ordinal, payload);
                            writer.write('\n');
                            cells.residual(writer, residual);
                        } else if (kind == KIND_MOVEMENT) {
                            Transaction transaction = decodeMovement(slot, date, ordinal, payload);
                            writer.write('\n');
                            cells.movement(writer, transaction, text);
                        } else {
                            throw new Exception("invalid history CSV row kind");
                        }
                        page.remember(date, kind, ordinal);
                        if (progress != null) progress.progress(++done, total);
                    }
                }
            } finally {
                page.close();
            }
        }

        long count() {
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + ROWS, null)) {
                cursor.moveToFirst();
                return cursor.getLong(0);
            }
        }

        private Transaction decodeMovement(String slot, long date, long ordinal, String encoded)
                throws Exception {
            String plain = codec.decrypt(encoded, identity(KIND_MOVEMENT, slot, date, ordinal));
            final Transaction[] found = {null};
            TransactionStore.readJson(new StringReader(plain), transaction -> {
                if (found[0] != null) throw new Exception("multiple staging transactions");
                found[0] = transaction;
            });
            Transaction transaction = found[0];
            if (transaction == null || transaction.date != date
                    || !slot.equals(slot(transaction.bank, transaction.account)))
                throw new Exception("staging transaction identity mismatch");
            return transaction;
        }

        private Residual decodeResidual(String slot, long date, long ordinal, String encoded)
                throws Exception {
            JSONObject object = new JSONObject(codec.decrypt(encoded,
                identity(KIND_RESIDUAL, slot, date, ordinal)));
            if (object.getInt("version") != 1 || object.getLong("toDate") != date)
                throw new Exception("invalid staging residual");
            String bank = object.getString("bank");
            String account = object.isNull("account") ? null : object.getString("account");
            Residual residual = new Residual(bank, account, object.getLong("fromDate"), date,
                object.getLong("amount"), object.getInt("movements"));
            validateResidual(residual);
            if (!slot.equals(slot(residual.bank, residual.account)))
                throw new Exception("staging residual identity mismatch");
            return residual;
        }

        private String slot(String bank, String account) throws Exception {
            if (bank == null || bank.isEmpty()) throw new IllegalArgumentException("row must have a bank");
            byte[] input = BalanceData.storageKey(bank, account).getBytes(StandardCharsets.UTF_8);
            byte[] digest = null;
            try {
                digest = slotMac.doFinal(input);
                return Base64.encodeToString(digest, Base64.NO_WRAP);
            } finally {
                Arrays.fill(input, (byte) 0);
                if (digest != null) Arrays.fill(digest, (byte) 0);
            }
        }

        private static void validateResidual(Residual residual) {
            if (residual == null || residual.bank == null || residual.bank.isEmpty())
                throw new IllegalArgumentException("residual must have a bank");
            if (residual.fromDate >= residual.toDate)
                throw new IllegalArgumentException("residual dates are not ordered");
            if (residual.amount == 0) throw new IllegalArgumentException("residual amount is zero");
            if (residual.movements < 0) throw new IllegalArgumentException("invalid residual movement count");
        }

        static String identity(int kind, String slot, long date, long ordinal) {
            return (kind == KIND_RESIDUAL ? "residual" : "movement")
                + "\nslot\n" + slot + "\ndate\n" + date + "\nordinal\n" + ordinal;
        }

        Exception closeAndDelete() {
            Exception failure = null;
            if (codec != null) {
                try {
                    codec.close();
                } catch (Exception error) {
                    failure = record(failure, error);
                }
                codec = null;
            }
            if (db != null) {
                try {
                    db.close();
                } catch (Exception error) {
                    failure = record(failure, error);
                }
                db = null;
            }
            if (databaseFile != null) {
                failure = delete(databaseFile, failure);
                failure = delete(new File(databaseFile.getPath() + "-wal"), failure);
                failure = delete(new File(databaseFile.getPath() + "-shm"), failure);
                failure = delete(new File(databaseFile.getPath() + "-journal"), failure);
                databaseFile = null;
            }
            if (wrappedKeyFile != null) {
                failure = delete(wrappedKeyFile, failure);
                wrappedKeyFile = null;
            }
            slotMac = null;
            return failure;
        }

        private static Exception record(Exception current, Exception next) {
            if (current == null) return next;
            current.addSuppressed(next);
            return current;
        }

        private static Exception delete(File file, Exception failure) {
            try {
                if (file != null && file.exists() && !file.delete() && file.exists())
                    throw new Exception("temporary history CSV file could not be deleted");
            } catch (Exception error) {
                return record(failure, error);
            }
            return failure;
        }

        @Override public void close() throws Exception {
            Exception failure = closeAndDelete();
            if (failure != null) throw failure;
        }
    }

    /** Keyset cursor over a bounded SQLite page; it never asks CursorWindow for the whole stage. */
    private static final class CursorPage implements AutoCloseable {
        final SQLiteDatabase db;
        final int pageSize;
        Cursor cursor;
        boolean hasLast;
        long lastDate;
        int lastKind;
        long lastOrdinal;

        CursorPage(SQLiteDatabase db, int pageSize) {
            this.db = db;
            this.pageSize = pageSize;
        }

        boolean next() {
            if (cursor != null) {
                cursor.close();
                cursor = null;
            }
            String selection = null;
            String[] args = null;
            if (hasLast) {
                selection = "(date, kind, ordinal) > (?, ?, ?)";
                args = new String[]{Long.toString(lastDate), Integer.toString(lastKind),
                    Long.toString(lastOrdinal)};
            }
            cursor = db.query(ROWS, ROW_COLUMNS, selection, args, null, null,
                "date ASC, kind ASC, ordinal ASC", Integer.toString(pageSize));
            return cursor.getCount() != 0;
        }

        void remember(long date, int kind, long ordinal) throws Exception {
            if (hasLast && compare(date, kind, ordinal, lastDate, lastKind, lastOrdinal) <= 0)
                throw new Exception("invalid history CSV row order");
            hasLast = true;
            lastDate = date;
            lastKind = kind;
            lastOrdinal = ordinal;
        }

        @Override public void close() {
            if (cursor != null) cursor.close();
        }

        private static int compare(long dateA, int kindA, long ordinalA,
                long dateB, int kindB, long ordinalB) {
            int byDate = Long.compare(dateA, dateB);
            if (byDate != 0) return byDate;
            int byKind = Integer.compare(kindA, kindB);
            return byKind != 0 ? byKind : Long.compare(ordinalA, ordinalB);
        }
    }
}
