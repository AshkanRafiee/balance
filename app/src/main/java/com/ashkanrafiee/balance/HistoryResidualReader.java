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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded-heap residual detection for the transaction store.
 *
 * <p>The main API is {@link #forEach(Context, String, String, int, Visitor)}. It first copies the
 * selected rows into a disposable SQLite database, then walks a date/opaque-slot/ordinal cursor.
 * Transaction payloads, per-slot walk state and residual payloads are encrypted with a
 * session-scoped {@link EncryptedRowCodec}; the SQL columns contain only an HMAC slot digest,
 * dates, row/run/position ordinals and ciphertext. The transaction store is read through its stable
 * {@link TransactionStore#forEach} snapshot, while the residual walk never retains a map of all
 * account slots in Java.
 *
 * <p>The residual output is an external sort. Fixed-size sorted runs and fixed-fan-in merge passes
 * live on disk, so input size affects temporary disk usage and work, not Java heap usage. The only
 * in-memory collections are a transaction-store page, one same-slot/same-date aggregate, a sort
 * batch and a fixed number of merge heads. SQLite uses a fixed schema, a bounded page cache and
 * file-backed temporary storage. There is no transaction or residual count cap on the
 * streaming API. {@link #read(Context, String, String, int, int)} is the explicitly bounded list
 * compatibility method and fails instead of truncating when its result bound is exceeded.
 *
 * <p>All temporary database, journal/WAL sidecar and wrapped-key files are removed in a finally
 * block, including when parsing, staging, sorting or the consumer fails. Files are in private
 * no-backup storage. Disk usage is unbounded with respect to history size; disk/SQLite failures
 * propagate. Memory bounds are row-count bounds, not limits on an individual payload's size. The
 * store's legacy single-value migration can also require memory proportional to that legacy value.
 *
 * <p>Callbacks are synchronous on the calling thread, after the source store snapshot is released.
 * The consumer owns any output it retains. If it throws, previously delivered residuals cannot be
 * rolled back; discard partial consumer results when a read fails. Process termination cannot run
 * finally cleanup, so this API does not promise deletion after a crash or secure filesystem erasure.
 * Apply direction/date/search/tag filtering in the consumer, after detection; only bank/account
 * scope belongs in this reader. Run this disk/crypto-intensive operation off the UI thread.
 */
final class HistoryResidualReader {
    static final int DEFAULT_PAGE_SIZE = 256;

    /** A residual consumer may stop the read by throwing; that failure is propagated unchanged. */
    interface Visitor {
        void accept(Residual residual) throws Exception;
    }

    /** Thrown by the compatibility list API instead of silently returning a partial result. */
    static final class ResultLimitExceededException extends Exception {
        ResultLimitExceededException(int limit) {
            super("residual result limit exceeded: " + limit);
        }
    }

    private static final String STAGING_FILE_PREFIX = "history-residual-";
    private static final String WRAPPED_KEY_FILE_PREFIX = "history-residual-key-";
    private static final String CODEC_DOMAIN = "history-residual-staging";
    private static final int HMAC_KEY_BYTES = 32;
    private static final int MERGE_FAN_IN = 8;

    private static final String TRANSACTIONS = "residual_transactions";
    private static final String STATES = "residual_states";
    private static final String CANDIDATES = "residual_candidates";
    private static final String RUNS_A = "residual_runs_a";
    private static final String RUNS_B = "residual_runs_b";
    private static final String[] ROW_COLUMNS = {"slot", "date", "ordinal", "payload"};

    private HistoryResidualReader() {}

    /** Streams scoped residuals with the default page size (pass null/null for all slots). */
    static void forEach(Context context, String bank, String account, Visitor visitor)
            throws Exception {
        forEach(context, bank, account, DEFAULT_PAGE_SIZE, visitor);
    }

    /**
     * Streams residuals in exact {@link Residual#between(List)} order.
     *
     * <p>{@code bank == null} and {@code account == null} mean no restriction. A non-null account
     * without a bank is matched across banks, just like the history reader's scope predicate.
     */
    static void forEach(Context context, String bank, String account, int pageSize, Visitor visitor)
            throws Exception {
        requireContext(context);
        requirePageSize(pageSize);
        if (visitor == null) throw new NullPointerException("visitor");

        try (Stage stage = Stage.open(context)) {
            stageTransactions(stage, context, bank, account, pageSize);
            generateCandidates(stage, pageSize);
            long runs = makeInitialRuns(stage, pageSize);
            emitSortedRuns(stage, runs, visitor);
        }
    }

    /**
     * Feeds scoped transactions from an external store pass and emits residuals, without
     * re-reading the transaction store. The caller stages every row in the detection scope
     * (bank/account) before narrowing filters run: dropping a row here would corrupt the
     * bracketing walk exactly like a page-local residual calculation would.
     */
    static final class Staging implements AutoCloseable {
        private final Stage stage;
        private long nextOrdinal;
        private boolean stagingOpen;
        private boolean emitted;
        private boolean closed;

        private Staging(Stage stage) {
            this.stage = stage;
        }

        static Staging open(Context context) throws Exception {
            requireContext(context);
            Stage stage = Stage.open(context);
            Staging staging = new Staging(stage);
            try {
                stage.db.beginTransactionNonExclusive();
                staging.stagingOpen = true;
                return staging;
            } catch (Throwable failure) {
                Exception cleanup = staging.closeAndDelete();
                if (cleanup != null) failure.addSuppressed(cleanup);
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new RuntimeException(failure);
            }
        }

        void add(Transaction transaction) throws Exception {
            if (transaction == null) throw new NullPointerException("transaction");
            if (closed || emitted || !stagingOpen)
                throw new IllegalStateException("residual staging is not accepting rows");
            if (nextOrdinal < 0) throw new IllegalStateException("transaction ordinal exhausted");
            long ordinal = nextOrdinal;
            nextOrdinal = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
            stage.insertTransaction(stage.slot(transaction), transaction.date, ordinal, transaction);
        }

        void emit(Visitor visitor, int pageSize) throws Exception {
            requirePageSize(pageSize);
            if (visitor == null) throw new NullPointerException("visitor");
            if (closed || emitted || !stagingOpen)
                throw new IllegalStateException("residual staging is not emitting");
            emitted = true;
            stage.db.setTransactionSuccessful();
            stage.db.endTransaction();
            stagingOpen = false;
            generateCandidates(stage, pageSize);
            long runs = makeInitialRuns(stage, pageSize);
            emitSortedRuns(stage, runs, visitor);
        }

        private Exception closeAndDelete() {
            Exception failure = null;
            if (stagingOpen) {
                try { stage.db.endTransaction(); } catch (Exception e) { failure = e; }
                stagingOpen = false;
            }
            Exception cleanup = stage.closeAndDelete();
            if (cleanup != null) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
            return failure;
        }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            Exception failure = closeAndDelete();
            if (failure != null) throw failure;
        }
    }

    /**
     * Explicitly bounded compatibility read. A bound of zero is valid and requires no residuals;
     * if one is found, {@link ResultLimitExceededException} is thrown.
     */
    static List<Residual> read(Context context, String bank, String account, int pageSize,
            int maxResults) throws Exception {
        if (maxResults < 0) throw new IllegalArgumentException("invalid residual result limit");
        List<Residual> out = new ArrayList<>();
        forEach(context, bank, account, pageSize, residual -> {
            if (out.size() >= maxResults) throw new ResultLimitExceededException(maxResults);
            out.add(residual);
        });
        return out;
    }

    /** Explicitly bounded compatibility read using {@link #DEFAULT_PAGE_SIZE}. */
    static List<Residual> read(Context context, String bank, String account, int maxResults)
            throws Exception {
        return read(context, bank, account, DEFAULT_PAGE_SIZE, maxResults);
    }

    private static void requireContext(Context context) {
        if (context == null) throw new NullPointerException("context");
    }

    private static void requirePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > TransactionStore.MAX_PAGE_SIZE)
            throw new IllegalArgumentException("invalid page size");
    }

    private static boolean inScope(Transaction transaction, String bank, String account) {
        if (transaction == null) return false;
        if (bank != null && !bank.equals(transaction.bank)) return false;
        return account == null || account.equals(transaction.account);
    }

    private static void stageTransactions(Stage stage, Context context, String bank, String account,
            int pageSize) throws Exception {
        stage.db.beginTransactionNonExclusive();
        try {
            final long[] nextOrdinal = {0};
            TransactionStore.forEach(context, pageSize, transaction -> {
                if (nextOrdinal[0] < 0) throw new IllegalStateException("transaction ordinal exhausted");
                long ordinal = nextOrdinal[0];
                nextOrdinal[0] = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
                if (!inScope(transaction, bank, account)) return;
                String slot = stage.slot(transaction);
                stage.insertTransaction(slot, transaction.date, ordinal, transaction);
            });
            stage.db.setTransactionSuccessful();
        } finally {
            stage.db.endTransaction();
        }
    }

    /**
     * Applies the Residual walk one SQL group at a time. The open bracket is kept in an encrypted
     * state row, not in a Java map, because the date-first external order interleaves slots.
     */
    private static void generateCandidates(Stage stage, int pageSize) throws Exception {
        stage.db.beginTransactionNonExclusive();
        try {
            Group group = null;
            long[] nextResidualOrdinal = {0};
            try (CursorPage page = new CursorPage(stage.db, TRANSACTIONS, pageSize)) {
                while (page.next()) {
                    while (page.cursor.moveToNext()) {
                        String slot = page.cursor.getString(0);
                        long date = page.cursor.getLong(1);
                        long ordinal = page.cursor.getLong(2);
                        String payload = page.cursor.getString(3);
                        if (group == null || !group.same(slot, date)) {
                            if (group != null) finishGroup(stage, group, nextResidualOrdinal);
                            group = new Group(stage, slot, date);
                        }
                        group.add(ordinal, payload);
                        page.remember(slot, date, ordinal);
                    }
                }
            }
            if (group != null) finishGroup(stage, group, nextResidualOrdinal);
            stage.db.setTransactionSuccessful();
        } finally {
            stage.db.endTransaction();
        }
    }

    private static void finishGroup(Stage stage, Group group, long[] nextResidualOrdinal)
            throws Exception {
        if (group.state == null) {
            if (group.balances == 1) {
                stage.putState(group.slot, new WalkState(group.closer, group.closerOrdinal,
                    0, 0, true));
            }
            return;
        }

        if (group.balances > 1) {
            // Residual.walk drops the bracket and all same-time movements when several closers exist.
            stage.deleteState(group.slot);
            return;
        }

        if (group.balances == 0) {
            stage.putState(group.slot, new WalkState(group.state.open, group.state.openOrdinal,
                group.inside, group.count, group.exact));
            return;
        }

        if (group.exact) {
            long gap;
            try {
                gap = Math.subtractExact(
                    Math.subtractExact(group.closer.balance, group.state.open.balance),
                    group.inside);
            } catch (ArithmeticException overflow) {
                gap = 0;
            }
            if (gap != 0) {
                if (nextResidualOrdinal[0] < 0)
                    throw new IllegalStateException("residual ordinal exhausted");
                long ordinal = nextResidualOrdinal[0];
                nextResidualOrdinal[0] = ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
                Residual residual = new Residual(group.closer.bank, group.closer.account,
                    group.state.open.date, group.closer.date, gap, group.count);
                stage.insertResidual(group.slot, residual.toDate, ordinal, residual);
            }
        }

        // Closing balances re-anchor even after an overflow-suppressed window.
        stage.putState(group.slot, new WalkState(group.closer, group.closerOrdinal,
            0, 0, true));
    }

    private static long makeInitialRuns(Stage stage, int pageSize) throws Exception {
        long runCount = 0;
        List<ResidualRecord> batch = new ArrayList<>(pageSize);
        try (CursorPage page = new CursorPage(stage.db, CANDIDATES, pageSize)) {
            while (page.next()) {
                while (page.cursor.moveToNext()) {
                    String slot = page.cursor.getString(0);
                    long date = page.cursor.getLong(1);
                    long ordinal = page.cursor.getLong(2);
                    String payload = page.cursor.getString(3);
                    batch.add(stage.decodeResidual(slot, date, ordinal, payload));
                    page.remember(slot, date, ordinal);
                    if (batch.size() == pageSize) {
                        runCount = writeRun(stage, runCount, batch);
                        batch.clear();
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            runCount = writeRun(stage, runCount, batch);
            batch.clear();
        }
        stage.db.delete(CANDIDATES, null, null);
        return runCount;
    }

    private static long writeRun(Stage stage, long index, List<ResidualRecord> rows) throws Exception {
        if (index < 0) throw new IllegalStateException("residual run ordinal exhausted");
        rows.sort(ResidualRecord.ORDER);
        stage.db.beginTransactionNonExclusive();
        try {
            long position = 0;
            for (ResidualRecord row : rows) stage.insertRunRow(RUNS_A, index, position++, row);
            stage.db.setTransactionSuccessful();
        } finally {
            stage.db.endTransaction();
        }
        return index == Long.MAX_VALUE ? -1 : index + 1;
    }

    private static void emitSortedRuns(Stage stage, long runCount, Visitor visitor) throws Exception {
        if (runCount < 0) throw new IllegalStateException("residual run count exhausted");
        String input = RUNS_A;
        String output = RUNS_B;
        while (runCount > MERGE_FAN_IN) {
            long nextCount = 0;
            long start = 0;
            while (start < runCount) {
                long remaining = runCount - start;
                int inputCount = (int) Math.min((long) MERGE_FAN_IN, remaining);
                mergeGroup(stage, input, start, inputCount, output, nextCount, null);
                if (nextCount == Long.MAX_VALUE)
                    throw new IllegalStateException("residual run count exhausted");
                nextCount++;
                start += inputCount;
            }
            stage.db.delete(input, null, null);
            String swap = input;
            input = output;
            output = swap;
            runCount = nextCount;
        }
        if (runCount != 0) {
            mergeGroup(stage, input, 0, (int) runCount, null, 0, visitor);
        }
    }

    /** Fixed schema and fan-in: run count never increases schema or cursor memory. */
    private static void mergeGroup(Stage stage, String input, long start, int inputCount,
            String output, long outputRun, Visitor visitor) throws Exception {
        RunHead[] heads = new RunHead[inputCount];
        try {
            for (int i = 0; i < inputCount; i++) {
                heads[i] = new RunHead(stage, input, start + i);
                heads[i].advance();
            }

            if (output != null) stage.db.beginTransactionNonExclusive();
            try {
                long position = 0;
                while (true) {
                    int best = -1;
                    for (int i = 0; i < inputCount; i++) {
                        if (heads[i].row == null) continue;
                        if (best < 0 || ResidualRecord.ORDER.compare(
                                heads[i].row, heads[best].row) < 0)
                            best = i;
                    }
                    if (best < 0) break;
                    ResidualRecord row = heads[best].row;
                    if (output != null) {
                        if (position < 0) throw new IllegalStateException("run position exhausted");
                        stage.insertRunRow(output, outputRun, position, row);
                        position = position == Long.MAX_VALUE ? -1 : position + 1;
                    } else {
                        visitor.accept(row.residual);
                    }
                    heads[best].advance();
                }
                if (output != null) stage.db.setTransactionSuccessful();
            } finally {
                if (output != null) stage.db.endTransaction();
            }
        } finally {
            Arrays.fill(heads, null);
        }
    }

    /** One decrypted head per run; LIMIT 1 avoids a CursorWindow holding the whole run. */
    private static final class RunHead {
        final Stage stage;
        final String table;
        final long run;
        long after = -1;
        ResidualRecord row;

        RunHead(Stage stage, String table, long run) {
            this.stage = stage;
            this.table = table;
            this.run = run;
        }

        void advance() throws Exception {
            try (Cursor cursor = stage.db.query(table,
                    new String[]{"slot", "date", "ordinal", "payload", "position_ordinal"},
                    "run_ordinal=? AND position_ordinal>?",
                    new String[]{Long.toString(run), Long.toString(after)}, null, null,
                    "position_ordinal ASC", "1")) {
                ResidualRecord next = cursor.moveToFirst() ? stage.decodeResidual(cursor.getString(0),
                    cursor.getLong(1), cursor.getLong(2), cursor.getString(3)) : null;
                if (next != null) {
                    long position = cursor.getLong(4);
                    if (after == Long.MAX_VALUE || position != after + 1
                            || (row != null && ResidualRecord.ORDER.compare(row, next) > 0))
                        throw new Exception("invalid residual run order");
                    after = position;
                }
                row = next;
            }
        }
    }

    private static final class CursorPage implements AutoCloseable {
        final SQLiteDatabase db;
        final String table;
        final int pageSize;
        Cursor cursor;
        boolean hasLast;
        long lastDate;
        String lastSlot;
        long lastOrdinal;

        CursorPage(SQLiteDatabase db, String table, int pageSize) {
            this.db = db;
            this.table = table;
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
                selection = "(date, slot, ordinal) > (?, ?, ?)";
                args = new String[]{Long.toString(lastDate), lastSlot, Long.toString(lastOrdinal)};
            }
            cursor = db.query(table, ROW_COLUMNS, selection, args, null, null,
                "date ASC, slot ASC, ordinal ASC", Integer.toString(pageSize));
            // getCount fills at most the requested page; keep the cursor before its first row.
            return cursor.getCount() != 0;
        }

        void remember(String slot, long date, long ordinal) {
            hasLast = true;
            lastSlot = slot;
            lastDate = date;
            lastOrdinal = ordinal;
        }

        @Override public void close() {
            if (cursor != null) cursor.close();
        }
    }

    private static final class Group {
        final Stage stage;
        final String slot;
        final long date;
        final WalkState state;
        String key;
        int balances;
        Transaction closer;
        long closerOrdinal;
        long inside;
        int count;
        boolean exact;

        Group(Stage stage, String slot, long date) throws Exception {
            this.stage = stage;
            this.slot = slot;
            this.date = date;
            state = stage.readState(slot);
            if (state == null) {
                inside = 0;
                count = 0;
                exact = true;
            } else {
                key = BalanceData.storageKey(state.open.bank, state.open.account);
                if (date <= state.open.date)
                    throw new Exception("staging state is not before transaction group");
                inside = state.inside;
                count = state.count;
                exact = state.exact;
            }
        }

        boolean same(String otherSlot, long otherDate) {
            return date == otherDate && slot.equals(otherSlot);
        }

        void add(long ordinal, String payload) throws Exception {
            Transaction transaction = stage.decodeTransaction(slot, date, ordinal, payload);
            String transactionKey = BalanceData.storageKey(transaction.bank, transaction.account);
            if (key == null) key = transactionKey;
            else if (!key.equals(transactionKey)) throw new Exception("staging slot digest collision");
            if (transaction.balance != null) {
                balances++;
                closer = transaction;
                closerOrdinal = ordinal;
            }
            if (state != null) {
                if (exact) {
                    try {
                        inside = Math.addExact(inside, transaction.amount);
                    } catch (ArithmeticException overflow) {
                        exact = false;
                    }
                }
                count++;
            }
        }
    }

    private static final class WalkState {
        final Transaction open;
        final long openOrdinal;
        final long inside;
        final int count;
        final boolean exact;

        WalkState(Transaction open, long openOrdinal, long inside, int count, boolean exact) {
            this.open = open;
            this.openOrdinal = openOrdinal;
            this.inside = inside;
            this.count = count;
            this.exact = exact;
        }
    }

    private static final class ResidualRecord {
        static final Comparator<ResidualRecord> ORDER = (a, b) -> {
            int byDate = Long.compare(a.residual.toDate, b.residual.toDate);
            if (byDate != 0) return byDate;
            int byKey = a.residual.key().compareTo(b.residual.key());
            return byKey != 0 ? byKey : Long.compare(a.ordinal, b.ordinal);
        };

        final String slot;
        final long date;
        final long ordinal;
        final String payload;
        final Residual residual;

        ResidualRecord(String slot, long date, long ordinal, String payload, Residual residual) {
            this.slot = slot;
            this.date = date;
            this.ordinal = ordinal;
            this.payload = payload;
            this.residual = residual;
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
            db.execSQL("CREATE TABLE " + TRANSACTIONS + " ("
                + "slot TEXT NOT NULL, date INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + "payload TEXT NOT NULL, PRIMARY KEY(slot, date, ordinal))");
            db.execSQL("CREATE INDEX residual_transactions_order ON " + TRANSACTIONS
                + "(date ASC, slot ASC, ordinal ASC)");
            db.execSQL("CREATE TABLE " + STATES + " ("
                + "slot TEXT PRIMARY KEY, date INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + "payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE " + CANDIDATES + " ("
                + "slot TEXT NOT NULL, date INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + "payload TEXT NOT NULL, PRIMARY KEY(slot, date, ordinal))");
            db.execSQL("CREATE INDEX residual_candidates_order ON " + CANDIDATES
                + "(date ASC, slot ASC, ordinal ASC)");
            createRunTable(RUNS_A);
            createRunTable(RUNS_B);
        }

        String slot(Transaction transaction) throws Exception {
            if (transaction == null || transaction.bank == null || transaction.bank.isEmpty())
                throw new IllegalArgumentException("transaction must have a bank");
            byte[] input = BalanceData.storageKey(transaction.bank, transaction.account)
                .getBytes(StandardCharsets.UTF_8);
            byte[] digest = null;
            try {
                digest = slotMac.doFinal(input);
                return Base64.encodeToString(digest, Base64.NO_WRAP);
            } finally {
                Arrays.fill(input, (byte) 0);
                if (digest != null) Arrays.fill(digest, (byte) 0);
            }
        }

        void insertTransaction(String slot, long date, long ordinal, Transaction transaction)
                throws Exception {
            ContentValues values = new ContentValues();
            values.put("slot", slot);
            values.put("date", date);
            values.put("ordinal", ordinal);
            values.put("payload", codec.encrypt(BalanceData.serializeTransactions(
                Collections.singletonList(transaction)), identity("transaction", slot, date, ordinal)));
            db.insertOrThrow(TRANSACTIONS, null, values);
        }

        Transaction decodeTransaction(String slot, long date, long ordinal, String encoded)
                throws Exception {
            String plain = codec.decrypt(encoded, identity("transaction", slot, date, ordinal));
            final Transaction[] found = {null};
            TransactionStore.readJson(new StringReader(plain), transaction -> {
                if (found[0] != null) throw new Exception("multiple staging transactions");
                found[0] = transaction;
            });
            Transaction transaction = found[0];
            if (transaction == null || transaction.date != date || !slot.equals(slot(transaction)))
                throw new Exception("staging transaction identity mismatch");
            return transaction;
        }

        WalkState readState(String slot) throws Exception {
            try (Cursor cursor = db.query(STATES,
                    new String[]{"date", "ordinal", "payload"}, "slot=?",
                    new String[]{slot}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) return null;
                long date = cursor.getLong(0);
                long ordinal = cursor.getLong(1);
                JSONObject object = new JSONObject(codec.decrypt(cursor.getString(2),
                    identity("state", slot, date, ordinal)));
                if (object.getInt("version") != 1 || object.getLong("date") != date
                        || object.getLong("openOrdinal") != ordinal)
                    throw new Exception("invalid staging state");
                String bank = object.getString("bank");
                String account = object.isNull("account") ? null : object.getString("account");
                long balance = object.getLong("balance");
                long inside = object.getLong("inside");
                int count = object.getInt("count");
                boolean exact = object.getBoolean("exact");
                Transaction open = new Transaction(bank, account, date, 0, balance, null, null);
                if (!slot.equals(slot(open))) throw new Exception("staging state identity mismatch");
                return new WalkState(open, ordinal, inside, count, exact);
            }
        }

        void putState(String slot, WalkState state) throws Exception {
            JSONObject object = new JSONObject()
                .put("version", 1)
                .put("bank", state.open.bank)
                .put("account", state.open.account == null ? JSONObject.NULL : state.open.account)
                .put("date", state.open.date)
                .put("openOrdinal", state.openOrdinal)
                .put("balance", state.open.balance.longValue())
                .put("inside", state.inside)
                .put("count", state.count)
                .put("exact", state.exact);
            ContentValues values = new ContentValues();
            values.put("slot", slot);
            values.put("date", state.open.date);
            values.put("ordinal", state.openOrdinal);
            values.put("payload", codec.encrypt(object.toString(),
                identity("state", slot, state.open.date, state.openOrdinal)));
            if (db.insertWithOnConflict(STATES, null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new Exception("staging state write failed");
        }

        void deleteState(String slot) {
            db.delete(STATES, "slot=?", new String[]{slot});
        }

        void insertResidual(String slot, long date, long ordinal, Residual residual)
                throws Exception {
            JSONObject object = new JSONObject()
                .put("version", 1)
                .put("bank", residual.bank)
                .put("account", residual.account == null ? JSONObject.NULL : residual.account)
                .put("fromDate", residual.fromDate)
                .put("toDate", residual.toDate)
                .put("amount", residual.amount)
                .put("movements", residual.movements);
            ContentValues values = new ContentValues();
            values.put("slot", slot);
            values.put("date", date);
            values.put("ordinal", ordinal);
            values.put("payload", codec.encrypt(object.toString(),
                identity("residual", slot, date, ordinal)));
            db.insertOrThrow(CANDIDATES, null, values);
        }

        ResidualRecord decodeResidual(String slot, long date, long ordinal, String encoded)
                throws Exception {
            JSONObject object = new JSONObject(codec.decrypt(encoded,
                identity("residual", slot, date, ordinal)));
            if (object.getInt("version") != 1 || object.getLong("toDate") != date)
                throw new Exception("invalid staging residual");
            String bank = object.getString("bank");
            String account = object.isNull("account") ? null : object.getString("account");
            Residual residual = new Residual(bank, account, object.getLong("fromDate"), date,
                object.getLong("amount"), object.getInt("movements"));
            if (residual.fromDate >= date || residual.amount == 0 || !slot.equals(slot(
                    new Transaction(bank, account, date, 0, null, null, null))))
                throw new Exception("staging residual identity mismatch");
            return new ResidualRecord(slot, date, ordinal, encoded, residual);
        }

        void createRunTable(String table) {
            db.execSQL("CREATE TABLE " + table + " ("
                + "run_ordinal INTEGER NOT NULL, position_ordinal INTEGER NOT NULL,"
                + "slot TEXT NOT NULL, date INTEGER NOT NULL, ordinal INTEGER NOT NULL,"
                + "payload TEXT NOT NULL, PRIMARY KEY(run_ordinal, position_ordinal))");
        }

        void insertRunRow(String table, long run, long position, ResidualRecord row) {
            ContentValues values = new ContentValues();
            values.put("run_ordinal", run);
            values.put("position_ordinal", position);
            values.put("slot", row.slot);
            values.put("date", row.date);
            values.put("ordinal", row.ordinal);
            values.put("payload", row.payload);
            db.insertOrThrow(table, null, values);
        }

        static String identity(String kind, String slot, long date, long ordinal) {
            return kind + "\nslot\n" + slot + "\ndate\n" + date + "\nordinal\n" + ordinal;
        }

        Exception closeAndDelete() {
            Exception failure = null;
            // Delete the wrapped key first even if closing/deleting SQLite later fails.
            if (wrappedKeyFile != null) {
                failure = delete(wrappedKeyFile, failure);
                wrappedKeyFile = null;
            }
            if (codec != null) {
                try {
                    codec.close();
                } catch (Exception e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
                codec = null;
            }
            if (db != null) {
                try {
                    db.close();
                } catch (Exception e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
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
            slotMac = null;
            return failure;
        }

        private static Exception delete(File file, Exception failure) {
            try {
                if (file != null && file.exists() && !file.delete() && file.exists())
                    throw new Exception("temporary residual file could not be deleted");
            } catch (Exception e) {
                if (failure == null) return e;
                failure.addSuppressed(e);
            }
            return failure;
        }

        @Override public void close() throws Exception {
            Exception failure = closeAndDelete();
            if (failure != null) throw failure;
        }
    }
}
