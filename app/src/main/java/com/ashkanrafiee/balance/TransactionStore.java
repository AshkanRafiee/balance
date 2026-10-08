package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable transaction rows behind the legacy {@link BalanceData} façade.
 *
 * <p>Only one encrypted transaction payload is held per database row. The database can therefore be
 * paged later without changing the transaction JSON shape or exposing financial fields in indexes.
 */
final class TransactionStore {
    private static final String DB_NAME = "balance_transactions.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "transactions";
    private static final String META = "store_meta";
    private static final String READY = "ready";
    private static final String OWNER = "owner";

    private TransactionStore() {}

    static final class Page {
        final List<Transaction> rows;
        final int nextOrdinal;
        final boolean hasMore;

        Page(List<Transaction> rows, int nextOrdinal, boolean hasMore) {
            this.rows = rows;
            this.nextOrdinal = nextOrdinal;
            this.hasMore = hasMore;
        }
    }

    static List<Transaction> read(Context context) throws Exception {
        try (Helper helper = new Helper(context)) {
            SQLiteDatabase db = helper.getReadableDatabase();
            if (!isReady(db) || !isOwnedBy(context, db)) return null;
            List<Transaction> out = new ArrayList<>();
            try (Cursor cursor = db.query(TABLE, new String[]{"payload"}, null, null, null,
                    null, "ordinal ASC")) {
                while (cursor.moveToNext()) {
                    List<Transaction> row = BalanceData.deserializeTransactions(
                        BalanceData.decryptStorePayload(cursor.getString(0)));
                    if (row.size() != 1) throw new Exception("invalid transaction row");
                    out.add(row.get(0));
                }
            }
            return out;
        }
    }

    /** Reads one bounded page after {@code afterOrdinal}; no earlier rows are materialized. */
    static Page page(Context context, int afterOrdinal, int limit) throws Exception {
        if (limit <= 0) throw new IllegalArgumentException("page size must be positive");
        try (Helper helper = new Helper(context)) {
            SQLiteDatabase db = helper.getReadableDatabase();
            if (!isReady(db) || !isOwnedBy(context, db)) return null;
            List<Transaction> rows = new ArrayList<>();
            int next = afterOrdinal;
            boolean hasMore = false;
            try (Cursor cursor = db.query(TABLE, new String[]{"ordinal", "payload"},
                    "ordinal > ?", new String[]{Integer.toString(afterOrdinal)}, null, null,
                    "ordinal ASC", Integer.toString(limit + 1))) {
                while (cursor.moveToNext()) {
                    int ordinal = cursor.getInt(0);
                    if (rows.size() >= limit) {
                        hasMore = true;
                        break;
                    }
                    List<Transaction> decoded = BalanceData.deserializeTransactions(
                        BalanceData.decryptStorePayload(cursor.getString(1)));
                    if (decoded.size() != 1) throw new Exception("invalid transaction row");
                    rows.add(decoded.get(0));
                    next = ordinal;
                }
            }
            return new Page(rows, next, hasMore);
        }
    }

    static boolean replace(Context context, List<Transaction> transactions) throws Exception {
        try (Helper helper = new Helper(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ensureOwner(context, db);
            db.beginTransaction();
            try {
                db.delete(TABLE, null, null);
                for (int i = 0; i < transactions.size(); i++) {
                    ContentValues values = new ContentValues();
                    values.put("ordinal", i);
                    values.put("payload", BalanceData.encryptStorePayload(
                        BalanceData.serializeTransactions(java.util.Collections.singletonList(
                            transactions.get(i)))));
                    if (db.insertOrThrow(TABLE, null, values) < 0)
                        throw new Exception("transaction insert failed");
                }
                ContentValues ready = new ContentValues();
                ready.put("key", READY);
                ready.put("value", "1");
                db.insertWithOnConflict(META, null, ready, SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    static void clear(Context context) {
        try {
            replace(context, java.util.Collections.emptyList());
        } catch (Exception ignored) {
            // Reset is best-effort; the old stores are still removed by BalanceData.reset().
        }
    }

    private static boolean isReady(SQLiteDatabase db) {
        try (Cursor cursor = db.query(META, new String[]{"value"}, "key=?",
                new String[]{READY}, null, null, null)) {
            return cursor.moveToFirst() && "1".equals(cursor.getString(0));
        }
    }

    private static boolean isOwnedBy(Context context, SQLiteDatabase db) {
        String owner = meta(db, OWNER);
        String token = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTION_STORE_TOKEN, null);
        return owner != null && owner.equals(token);
    }

    private static void ensureOwner(Context context, SQLiteDatabase db) {
        String owner = meta(db, OWNER);
        String token = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE)
            .getString(BalanceData.KEY_TRANSACTION_STORE_TOKEN, null);
        if (owner == null || token == null || !owner.equals(token)) {
            db.delete(TABLE, null, null);
            String fresh = UUID.randomUUID().toString();
            ContentValues values = new ContentValues();
            values.put("key", OWNER);
            values.put("value", fresh);
            db.insertWithOnConflict(META, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit()
                .putString(BalanceData.KEY_TRANSACTION_STORE_TOKEN, fresh).commit();
        }
    }

    private static String meta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(META, new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, DB_NAME, null, DB_VERSION); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE
                + " (id INTEGER PRIMARY KEY AUTOINCREMENT, ordinal INTEGER NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX transactions_order ON " + TABLE + "(ordinal)");
            db.execSQL("CREATE TABLE " + META + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // The first schema has no prior on-device database. Future changes must migrate rows,
            // never drop them.
        }
    }
}
