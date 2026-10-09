package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;
import android.util.JsonWriter;

import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Uncapped definitions and settlement exceptions in native SQLite, with session-scoped AES-GCM row
 * keys wrapped by the Keystore bridge. Names, identities, amounts, dates and flags are encrypted;
 * lookup indexes are keyed digests. SQLite only sees row ids and relationships. No additional
 * database library is needed. Existing rows remain readable through the legacy payload format.
 *
 * <p>The database is authoritative. An absent preference token is repaired, never interpreted as a
 * request to erase data. Only clear(), delete() and explicit replacement remove records. All writes,
 * initialization and legacy import run in one transaction. The legacy preference is removed only
 * after commit; its durable digest prevents crash retries from resurrecting stale settlements.
 *
 * <p>read()/replace() are full-state compatibility helpers for BalanceData. get(), definition pages
 * and visitors return immutable lazy paid/unpaid lists: isSettled() looks up one exact date, without
 * copying history. Pages and lazy lists fail on a changed database revision instead of combining
 * snapshots. Window pages scan encrypted rows with bounded memory (dates are not plaintext indexes).
 * Do not mix the preference writers with this store; integrate the facade/reset together.
 */
final class CommitmentStore {
    static final String DB_NAME = "balance_commitments.db";
    static final String KEY_COMMITMENT_STORE_TOKEN = "commitment_store_token";
    static final String KEY_STORE_TOKEN = KEY_COMMITMENT_STORE_TOKEN;
    static final int MAX_PAGE_SIZE = 1000;
    private static final int PAGE_SIZE = 256;
    private static final String DEFINITIONS = "definitions";
    private static final String SETTLEMENTS = "settlements";
    private static final String META = "store_meta";
    private static final String OWNER = "owner";
    private static final String INDEX_KEY = "index_key";
    private static final String ROW_KEY = "row_key";
    private static final String REVISION = "revision";
    private static final String MIGRATED = "legacy_digest";
    /** Prefix for per-deletion META tombstones; the value keeps the deleted definition id. */
    private static final String DELETED_PREFIX = "deleted:";
    private static final String ROW_DOMAIN = "commitments";
    static final String TABLE = DEFINITIONS;
    static final String SETTLEMENT_TABLE = SETTLEMENTS;
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();

    private CommitmentStore() {}

    interface DefinitionVisitor { void accept(Commitment definition) throws Exception; }
    interface SettlementVisitor { void accept(Settlement settlement) throws Exception; }
    interface Transaction<T> { T run(Editor editor) throws Exception; }
    private interface Work<T> { T run(Session session) throws Exception; }

    static final class DefinitionPage {
        final List<Commitment> rows;
        final List<Commitment> definitions;
        final long nextId;
        final long nextOrdinal;
        final boolean hasMore;
        private final String revision;

        private DefinitionPage(List<Commitment> rows, long nextId, boolean hasMore,
                String revision) {
            this.rows = Collections.unmodifiableList(rows);
            this.definitions = this.rows;
            this.nextId = nextId;
            this.nextOrdinal = nextId;
            this.hasMore = hasMore;
            this.revision = revision;
        }
    }

    /** One explicit state, not an expansion of the legacy watermark. */
    static final class Settlement {
        final long id;
        final String definitionId;
        final long date;
        final boolean settled;

        private Settlement(long id, String definitionId, long date, boolean settled) {
            this.id = id;
            this.definitionId = definitionId;
            this.date = date;
            this.settled = settled;
        }
    }

    static final class SettlementPage {
        final List<Settlement> rows;
        final List<Settlement> settlements;
        final long nextId;
        final long nextOrdinal;
        final boolean hasMore;
        private final String definitionId;
        private final long from;
        private final long to;
        private final Boolean settled;
        private final String revision;

        private SettlementPage(List<Settlement> rows, long nextId, boolean hasMore,
                String definitionId, long from, long to, Boolean settled, String revision) {
            this.rows = Collections.unmodifiableList(rows);
            this.settlements = this.rows;
            this.nextId = nextId;
            this.nextOrdinal = nextId;
            this.hasMore = hasMore;
            this.definitionId = definitionId;
            this.from = from;
            this.to = to;
            this.settled = settled;
            this.revision = revision;
        }
    }

    /** A mutable detached compatibility snapshot, including all explicit paid/unpaid dates. */
    static List<Commitment> read(Context context) throws Exception {
        return access(context, true, s -> {
            List<Commitment> result = new ArrayList<>();
            try (Cursor cursor = s.db.query(DEFINITIONS, new String[]{"id", "lookup", "payload"},
                    null, null, null, null, "id ASC")) {
                while (cursor.moveToNext()) result.add(s.decodeDefinition(cursor, false).value);
            }
            return result;
        });
    }

    /** Compatibility spelling used by the old BalanceData façade. */
    static List<Commitment> readCommitments(Context context) throws Exception {
        return read(context);
    }

    /** Strict, atomic full replacement. Invalid/null/duplicate definitions fail, never get dropped.
     * Staging encrypted rows first makes replacing a list containing lazy get/page results safe. */
    static boolean replace(Context context, Collection<Commitment> definitions) throws Exception {
        if (definitions == null) throw new NullPointerException("definitions");
        return access(context, true, s -> { s.replace(definitions); return true; });
    }

    /** Compatibility spelling used by the old BalanceData façade. */
    static boolean writeCommitments(Context context, Collection<Commitment> definitions)
            throws Exception {
        return replace(context, definitions);
    }

    static boolean write(Context context, Collection<Commitment> definitions) throws Exception {
        return replace(context, definitions);
    }

    static Commitment get(Context context, String id) throws Exception {
        requireId(id);
        return access(context, true, s -> {
            Definition found = s.find(id, true);
            return found == null ? null : found.value;
        });
    }

    /** Full-state upsert, retaining the row's position. Reusing its lazy paid/unpaid lists leaves
     * those rows in place, so editing a definition does not copy its history. */
    static boolean upsert(Context context, Commitment definition) throws Exception {
        return access(context, true, s -> s.upsert(definition));
    }

    /** Local-first definition merge; settlement marks from an incoming ID are unioned explicitly. */
    static boolean mergeDefinition(Context context, Commitment incoming) throws Exception {
        if (incoming == null) throw new NullPointerException("incoming");
        return access(context, true, s -> s.mergeDefinition(incoming));
    }

    static boolean delete(Context context, String id) throws Exception {
        requireId(id);
        return access(context, true, s -> s.delete(id));
    }

    static boolean settle(Context context, String id, long date) throws Exception {
        requireId(id);
        requireDate(date);
        return access(context, true, s -> s.settle(id, date, true));
    }

    static boolean settle(Context context, Commitment definition, long date) throws Exception {
        if (definition == null) throw new NullPointerException("definition");
        return settle(context, definition.id, date);
    }

    static boolean undo(Context context, String id, long date) throws Exception {
        requireId(id);
        requireDate(date);
        return access(context, true, s -> s.settle(id, date, false));
    }

    static boolean undo(Context context, Commitment definition, long date) throws Exception {
        if (definition == null) throw new NullPointerException("definition");
        return undo(context, definition.id, date);
    }

    static boolean isSettled(Context context, String id, long date) throws Exception {
        requireId(id);
        requireDate(date);
        return access(context, true, s -> s.isSettled(id, date));
    }

    static boolean isSettled(Context context, Commitment definition, long date) throws Exception {
        if (definition == null) return false;
        return isSettled(context, definition.id, date);
    }

    /** Explicit clear also discards a malformed legacy source and resets store metadata. */
    static boolean clear(Context context) throws Exception {
        synchronized (BalanceData.class) {
            context = DataGeneration.context(context);
            if (ACTIVE.get() != null) throw new IllegalStateException("use the transaction editor");
            SharedPreferences prefs = context.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE);
            String legacy = prefs.getString(BalanceData.KEY_COMMITMENTS, null);
            try (Helper helper = new Helper(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    db.delete(DEFINITIONS, null, null);
                    db.delete(SETTLEMENTS, null, null);
                    db.delete(META, null, null);
                    if (legacy != null) putMeta(db, MIGRATED, digest(legacy));
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            if (!prefs.edit()
                    .remove(KEY_COMMITMENT_STORE_TOKEN).remove(BalanceData.KEY_COMMITMENTS).commit())
                throw new IllegalStateException("commitment clear cleanup failed");
            return true;
        }
    }

    /** Multi-operation helper: every editor operation and legacy migration commits or rolls back
     * together. Throwing from the callback rolls back. The editor cannot escape its callback. */
    static <T> T runInTransaction(Context context, Transaction<T> transaction) throws Exception {
        if (transaction == null) throw new NullPointerException("transaction");
        return access(context, true, s -> {
            Editor editor = new Editor(s);
            try { return transaction.run(editor); }
            finally { editor.active = false; }
        });
    }

    static final class Editor {
        private final Session session;
        private boolean active = true;

        private Editor(Session session) { this.session = session; }
        private Session session() {
            if (!active || ACTIVE.get() != session) throw new IllegalStateException("closed editor");
            return session;
        }
        Commitment get(String id) throws Exception {
            requireId(id);
            Definition found = session().find(id, false);
            return found == null ? null : found.value;
        }
        boolean upsert(Commitment definition) throws Exception { return session().upsert(definition); }
        boolean mergeDefinition(Commitment definition) throws Exception {
            return session().mergeDefinition(definition);
        }
        boolean delete(String id) throws Exception { requireId(id); return session().delete(id); }
        boolean noteDeletion(String id) throws Exception {
            requireId(id); return session().noteDeletion(id);
        }
        boolean isDeleted(String id) throws Exception {
            requireId(id); return session().isDeleted(id);
        }
        boolean clearDeletion(String id) throws Exception {
            requireId(id); return session().clearDeletion(id);
        }
        boolean settle(String id, long date) throws Exception {
            requireId(id); requireDate(date); return session().settle(id, date, true);
        }
        boolean undo(String id, long date) throws Exception {
            requireId(id); requireDate(date); return session().settle(id, date, false);
        }
        boolean mergeSettlementIfAbsent(String id, long date, boolean settled) throws Exception {
            requireId(id); requireDate(date);
            return session().mergeSettlementIfAbsent(id, date, settled);
        }
        boolean isSettled(String id, long date) throws Exception {
            requireId(id); requireDate(date); return session().isSettled(id, date);
        }
    }

    /** Current store revision for cache validation; null when absent or unreadable. Read-only:
     *  it never creates the database, so a missing store stays missing. */
    static String revision(Context context) {
        try {
            Context resolved = DataGeneration.context(context);
            java.io.File file = resolved.getDatabasePath(DB_NAME);
            if (file == null || !file.isFile()) return null;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY)) {
                return meta(db, REVISION);
            }
        } catch (Exception e) {
            return null;
        }
    }

    static DefinitionPage pageDefinitions(Context context, long afterId, int limit) throws Exception {
        requirePageSize(limit);
        return access(context, true, s -> s.definitionPage(afterId, limit));
    }

    static DefinitionPage page(Context context, long afterId, int limit) throws Exception {
        return pageDefinitions(context, afterId, limit);
    }

    static DefinitionPage pageDefinitions(Context context, DefinitionPage previous, int limit)
            throws Exception {
        if (previous == null) throw new NullPointerException("previous");
        requirePageSize(limit);
        return access(context, true, s -> {
            s.checkRevision(previous.revision);
            return s.definitionPage(previous.nextId, limit);
        });
    }

    static void forEachDefinition(Context context, DefinitionVisitor visitor) throws Exception {
        forEachDefinition(context, PAGE_SIZE, visitor);
    }

    static void forEachDefinition(Context context, int pageSize, DefinitionVisitor visitor)
            throws Exception {
        if (visitor == null) throw new NullPointerException("visitor");
        DefinitionPage page = pageDefinitions(context, 0, pageSize);
        while (true) {
            for (Commitment definition : page.rows) visitor.accept(definition);
            if (!page.hasMore) return;
            page = pageDefinitions(context, page, pageSize);
        }
    }

    static SettlementPage pageSettlements(Context context, String id, long afterId, int limit)
            throws Exception {
        return pageSettlements(context, id, 1, Long.MAX_VALUE, afterId, limit);
    }

    /** Inclusive exact-millisecond window; results are in row-id order, not date order. */
    static SettlementPage pageSettlements(Context context, String id, long from, long to,
            long afterId, int limit) throws Exception {
        requireId(id);
        requirePageSize(limit);
        if (from > to) throw new IllegalArgumentException("invalid window");
        return access(context, true, s -> s.settlementPage(id, from, to, afterId, limit, null));
    }

    static SettlementPage pageSettlements(Context context, SettlementPage previous, int limit)
            throws Exception {
        if (previous == null) throw new NullPointerException("previous");
        requirePageSize(limit);
        return access(context, true, s -> {
            s.checkRevision(previous.revision);
            return s.settlementPage(previous.definitionId, previous.from, previous.to,
                previous.nextId, limit, previous.settled);
        });
    }

    static SettlementPage windowPage(Context context, String id, long from, long to,
            long afterId, int limit) throws Exception {
        return pageSettlements(context, id, from, to, afterId, limit);
    }

    static SettlementPage windowPage(Context context, String id, long from, long to, int limit)
            throws Exception {
        return pageSettlements(context, id, from, to, 0, limit);
    }

    static SettlementPage window(Context context, String id, long from, long to,
            long afterId, int limit) throws Exception {
        return pageSettlements(context, id, from, to, afterId, limit);
    }

    static SettlementPage window(Context context, String id, long from, long to, int limit)
            throws Exception {
        return pageSettlements(context, id, from, to, 0, limit);
    }

    static void forEachSettlement(Context context, String id, int pageSize,
            SettlementVisitor visitor) throws Exception {
        if (visitor == null) throw new NullPointerException("visitor");
        SettlementPage page = pageSettlements(context, id, 0, pageSize);
        while (true) {
            for (Settlement row : page.rows) visitor.accept(row);
            if (!page.hasMore) return;
            page = pageSettlements(context, page, pageSize);
        }
    }

    static List<Settlement> exportSettlements(Context context, String id) throws Exception {
        List<Settlement> result = new ArrayList<>();
        forEachSettlement(context, id, PAGE_SIZE, result::add);
        return result;
    }

    static List<Settlement> exportSettlements(Context context) throws Exception {
        List<Settlement> result = new ArrayList<>();
        forEachDefinition(context, PAGE_SIZE, definition -> {
            for (Settlement mark : exportSettlements(context, definition.id)) result.add(mark);
        });
        return result;
    }

    /** Streams the existing {commitments:[...]} backup shape without materializing histories.
     * A consistent transaction is held during export; the caller owns and closes the writer. */
    static void export(Context context, Writer writer) throws Exception {
        if (writer == null) throw new NullPointerException("writer");
        access(context, true, s -> {
            writer.write("{\"" + BalanceData.KEY_COMMITMENTS + "\":[");
            boolean first = true;
            try (Cursor cursor = s.db.query(DEFINITIONS, new String[]{"id", "lookup", "payload"},
                    null, null, null, null, "id ASC")) {
                while (cursor.moveToNext()) {
                    Definition definition = s.decodeDefinition(cursor, true);
                    if (!first) writer.write(',');
                    first = false;
                    String json = definition.value.definitionJson().toString();
                    writer.write(json, 0, json.length() - 1);
                    writeDates(s, definition.rowId, writer, true);
                    writeDates(s, definition.rowId, writer, false);
                    writer.write('}');
                }
            }
            writer.write("]}");
            return null;
        });
    }

    /** Streams definitions and every explicit settlement mark without building one large JSON row. */
    static void writeJsonRecords(Context context, JsonWriter writer) throws Exception {
        if (writer == null) throw new NullPointerException("writer");
        writer.beginArray();
        forEachDefinition(context, PAGE_SIZE, definition -> writeJsonRecord(writer, definition));
        writer.endArray();
    }

    /** Streams the ids of explicitly deleted definitions, oldest tombstone first. */
    static void writeJsonDeletions(Context context, JsonWriter writer) throws Exception {
        if (writer == null) throw new NullPointerException("writer");
        access(context, true, s -> {
            writer.beginArray();
            try (Cursor cursor = s.db.query(META, new String[]{"value"},
                    "key LIKE ?", new String[]{DELETED_PREFIX + "%"}, null, null, "key ASC")) {
                while (cursor.moveToNext()) writer.value(cursor.getString(0));
            }
            writer.endArray();
            return null;
        });
    }

    private static void writeJsonRecord(JsonWriter writer, Commitment c) throws IOException {
        writer.beginObject();
        writer.name("id").value(c.id);
        writer.name("name").value(c.name);
        writer.name("amount").value(c.amount);
        writer.name("freq").value(c.frequency);
        writer.name("start").value(c.start);
        if (c.end != null) writer.name("end").value(c.end);
        if (c.done) writer.name("done").value(true);
        if (c.legacyPaidThrough > 0) writer.name("paidThrough").value(c.legacyPaidThrough);
        if (c.remind) writer.name("remind").value(true);
        if (c.remindBeforeMs > 0) writer.name("remindBefore").value(c.remindBeforeMs);
        writer.name("paid").beginArray();
        for (Long date : c.paid) writer.value(date);
        writer.endArray();
        writer.name("unpaid").beginArray();
        for (Long date : c.unpaid) writer.value(date);
        writer.endArray();
        writer.endObject();
    }

    /** Streams the explicit paid/unpaid arrays plus paidThrough, without expanding that watermark. */
    static void exportSettlements(Context context, String id, Writer writer) throws Exception {
        requireId(id);
        if (writer == null) throw new NullPointerException("writer");
        access(context, true, s -> {
            Definition definition = s.find(id, true);
            if (definition == null) throw new IllegalArgumentException("unknown commitment");
            writer.write("{\"paidThrough\":" + definition.value.legacyPaidThrough);
            writeDates(s, definition.rowId, writer, true);
            writeDates(s, definition.rowId, writer, false);
            writer.write('}');
            return null;
        });
    }

    private static void writeDates(Session s, long rowId, Writer writer, boolean settled)
            throws Exception {
        writer.write(settled ? ",\"paid\":[" : ",\"unpaid\":[");
        boolean first = true;
        try (Cursor cursor = s.db.query(SETTLEMENTS, new String[]{"id", "lookup", "payload"},
                "definition_id=?", new String[]{Long.toString(rowId)}, null, null, "id ASC")) {
            while (cursor.moveToNext()) {
                Settlement row = s.decodeSettlement(cursor, rowId);
                if (row.settled != settled) continue;
                if (!first) writer.write(',');
                first = false;
                writer.write(Long.toString(row.date));
            }
        }
        writer.write(']');
    }

    static String serialize(Collection<Commitment> definitions) throws Exception {
        if (definitions == null) throw new NullPointerException("definitions");
        JSONArray array = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (Commitment definition : definitions) {
            requireDefinition(definition);
            if (!ids.add(definition.id)) throw new IllegalArgumentException("duplicate commitment");
            array.put(definition.toJson());
        }
        return new JSONObject().put(BalanceData.KEY_COMMITMENTS, array).toString();
    }

    /** Strict parser for migration/restore: malformed fields fail the entire input. */
    static List<Commitment> deserialize(String json) throws Exception {
        JSONArray array = new JSONObject(json).getJSONArray(BalanceData.KEY_COMMITMENTS);
        List<Commitment> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Commitment definition = Commitment.fromJson(array.getJSONObject(i));
            if (definition == null || !ids.add(definition.id))
                throw new IllegalArgumentException("invalid legacy commitment");
            definitions.add(definition);
        }
        return definitions;
    }

    private static <T> T access(Context context, boolean migrate, Work<T> work) throws Exception {
        synchronized (BalanceData.class) {
            // Resolve once per operation. The stage wrapper is fixed and is never re-resolved to
            // the live selector while a restore is being applied.
            context = DataGeneration.context(context);
            if (ACTIVE.get() != null) throw new IllegalStateException("use the transaction editor");
            SharedPreferences prefs = context.getSharedPreferences(BalanceData.PREFS_DATA,
                Context.MODE_PRIVATE);
            String legacy = prefs.getString(BalanceData.KEY_COMMITMENTS, null);
            String owner;
            T result;
            try (Helper helper = new Helper(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    owner = meta(db, OWNER);
                    String token = prefs.getString(KEY_COMMITMENT_STORE_TOKEN, null);
                    if (owner != null && (owner.isEmpty() || (token != null &&
                            (token.isEmpty() || !owner.equals(token)))))
                        throw new IllegalStateException("commitment store ownership unavailable");
                    if (owner == null) {
                        if (rowCount(db, DEFINITIONS) != 0 || rowCount(db, SETTLEMENTS) != 0)
                            throw new IllegalStateException("missing commitment store metadata");
                        if (token != null && token.isEmpty())
                            throw new IllegalStateException("invalid commitment store ownership");
                        owner = token == null ? UUID.randomUUID().toString() : token;
                        byte[] key = new byte[32];
                        new SecureRandom().nextBytes(key);
                        try {
                            putMeta(db, INDEX_KEY, BalanceData.encryptStorePayload(
                                Base64.encodeToString(key, Base64.NO_WRAP)));
                        } finally { Arrays.fill(key, (byte) 0); }
                        putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
                        putMeta(db, OWNER, owner);
                        putMeta(db, REVISION, UUID.randomUUID().toString());
                    }
                    ensureRowKey(db);
                    Session session = new Session(context, db, owner);
                    ACTIVE.set(session);
                    try {
                        String digest = legacy == null ? null : digest(legacy);
                        if (migrate && legacy != null && !digest.equals(meta(db, MIGRATED))) {
                            String plain = legacy.trim().startsWith("{") ? legacy
                                : BalanceData.decryptStorePayload(legacy);
                            // Strict parsing completes before any import writes. Conflicts fail instead
                            // of silently overwriting one source with the other.
                            for (Commitment definition : deserialize(plain)) {
                                if (session.find(definition.id, true) != null)
                                    throw new IllegalStateException("legacy commitment conflicts with database");
                                session.upsert(definition);
                            }
                        }
                        if (legacy != null) putMeta(db, MIGRATED, digest);
                        result = work.run(session);
                        db.setTransactionSuccessful();
                    } finally {
                        session.close();
                    }
                } finally {
                    ACTIVE.remove();
                    db.endTransaction();
                }
            }
            SharedPreferences.Editor cleanup = prefs.edit();
            boolean changed = !owner.equals(prefs.getString(KEY_COMMITMENT_STORE_TOKEN, null));
            if (changed) cleanup.putString(KEY_COMMITMENT_STORE_TOKEN, owner);
            if (legacy != null && legacy.equals(prefs.getString(BalanceData.KEY_COMMITMENTS, null)))
                { cleanup.remove(BalanceData.KEY_COMMITMENTS); changed = true; }
            // A cleanup failure leaves committed data and the source intact. The digest makes retry
            // idempotent; a failed database transaction never reaches preference cleanup.
            if (changed && !cleanup.commit()) throw new IllegalStateException("commitment preference cleanup failed");
            return result;
        }
    }

    private static final class Definition {
        final long rowId;
        final Commitment value;
        Definition(long rowId, Commitment value) { this.rowId = rowId; this.value = value; }
    }

    private static final class Session {
        final Context context;
        final SQLiteDatabase db;
        final String owner;
        final Mac index;
        final EncryptedRowCodec rows;
        String revision;
        boolean touched;

        Session(Context context, SQLiteDatabase db, String owner) throws Exception {
            this.context = context;
            this.db = db;
            this.owner = owner;
            revision = meta(db, REVISION);
            String encrypted = meta(db, INDEX_KEY);
            if (encrypted == null || revision == null) throw new IllegalStateException("incomplete commitment store");
            byte[] key = Base64.decode(BalanceData.decryptStorePayload(encrypted), Base64.NO_WRAP);
            try {
                if (key.length != 32) throw new IllegalStateException("invalid commitment index key");
                index = Mac.getInstance("HmacSHA256");
                index.init(new SecretKeySpec(key, "HmacSHA256"));
            } finally { Arrays.fill(key, (byte) 0); }
            String encodedRows = meta(db, ROW_KEY);
            if (encodedRows == null) throw new IllegalStateException("incomplete commitment store");
            rows = EncryptedRowCodec.open(encodedRows, ROW_DOMAIN);
        }

        String lookup(String value) {
            return Base64.encodeToString(index.doFinal(value.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
        }

        String definitionLookup(String id) { return lookup("definition\n" + id); }
        String dateLookup(long rowId, long date) { return lookup("settlement\n" + rowId + "\n" + date); }

        void touch() {
            if (touched) return;
            revision = UUID.randomUUID().toString();
            putMeta(db, REVISION, revision);
            touched = true;
        }

        void checkRevision(String expected) {
            if (!revision.equals(expected)) throw new IllegalStateException("commitments changed; restart read");
        }

        Definition find(String id, boolean lazy) throws Exception {
            try (Cursor cursor = db.query(DEFINITIONS, new String[]{"id", "lookup", "payload"},
                    "lookup=?", new String[]{definitionLookup(id)}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) return null;
                Definition definition = decodeDefinition(cursor, lazy);
                if (!id.equals(definition.value.id)) throw new IllegalStateException("commitment lookup mismatch");
                return definition;
            }
        }

        Definition decodeDefinition(Cursor cursor, boolean lazy) throws Exception {
            long rowId = cursor.getLong(0);
            String lookup = cursor.getString(1);
            JSONObject envelope = new JSONObject(rows.decrypt(cursor.getString(2),
                definitionIdentity(lookup)));
            if (!owner.equals(envelope.getString("owner")) || envelope.getInt("version") != 1)
                throw new IllegalStateException("invalid commitment envelope");
            JSONObject object = envelope.getJSONObject("definition");
            if (object.has("paid") || object.has("unpaid")) throw new IllegalStateException("inline settlements");
            Commitment c = Commitment.fromJson(object);
            if (c == null || !definitionLookup(c.id).equals(lookup))
                throw new IllegalStateException("invalid commitment row");
            List<Long> paid, unpaid;
            if (lazy) {
                paid = new LazyDates(context, owner, revision, rowId, c.id, true);
                unpaid = new LazyDates(context, owner, revision, rowId, c.id, false);
            } else {
                paid = new ArrayList<>();
                unpaid = new ArrayList<>();
                try (Cursor marks = db.query(SETTLEMENTS, new String[]{"id", "lookup", "payload"},
                        "definition_id=?", new String[]{Long.toString(rowId)}, null, null, "id ASC")) {
                    while (marks.moveToNext()) {
                        Settlement mark = decodeSettlement(marks, rowId);
                        (mark.settled ? paid : unpaid).add(mark.date);
                    }
                }
                paid.sort(null);
                unpaid.sort(null);
            }
            return new Definition(rowId, new Commitment(c.id, c.name, c.amount, c.frequency, c.start,
                c.end, c.done, paid, c.legacyPaidThrough, unpaid, c.remind, c.remindBeforeMs));
        }

        String definitionPayload(Commitment c) throws Exception {
            String lookup = definitionLookup(c.id);
            return rows.encrypt(new JSONObject().put("version", 1)
                .put("owner", owner).put("definition", c.definitionJson()).toString(),
                definitionIdentity(lookup));
        }

        ContentValues settlementValues(long rowId, long date, boolean settled) throws Exception {
            ContentValues values = new ContentValues();
            values.put("definition_id", rowId);
            String lookup = dateLookup(rowId, date);
            values.put("lookup", lookup);
            values.put("payload", rows.encrypt(new JSONObject().put("version", 1)
                .put("owner", owner).put("definition_id", rowId).put("date", date)
                .put("settled", settled).toString(), settlementIdentity(rowId, lookup)));
            return values;
        }

        Settlement decodeSettlement(Cursor cursor, long rowId) throws Exception {
            String lookup = cursor.getString(1);
            JSONObject object = new JSONObject(rows.decrypt(cursor.getString(2),
                settlementIdentity(rowId, lookup)));
            long date = Commitment.exactLong(object.get("date"));
            Object state = object.get("settled");
            if (date <= 0 || !(state instanceof Boolean) || object.getInt("version") != 1
                    || !owner.equals(object.getString("owner"))
                    || Commitment.exactLong(object.get("definition_id")) != rowId
                    || !dateLookup(rowId, date).equals(lookup))
                throw new IllegalStateException("invalid settlement row");
            return new Settlement(cursor.getLong(0), null, date, (Boolean) state);
        }

        void close() {
            rows.close();
        }

        Settlement mark(long rowId, long date) throws Exception {
            try (Cursor cursor = db.query(SETTLEMENTS, new String[]{"id", "lookup", "payload"},
                    "definition_id=? AND lookup=?", new String[]{Long.toString(rowId), dateLookup(rowId, date)},
                    null, null, null, "1")) {
                return cursor.moveToFirst() ? decodeSettlement(cursor, rowId) : null;
            }
        }

        boolean isSettled(String id, long date) throws Exception {
            Definition definition = find(id, true);
            if (definition == null) return false;
            Commitment c = definition.value;
            if (c.frequency == Commitment.ONCE) return c.done;
            Settlement mark = mark(definition.rowId, date);
            return mark != null ? mark.settled : c.legacyPaidThrough > 0 && date <= c.legacyPaidThrough;
        }

        boolean settle(String id, long date, boolean settled) throws Exception {
            Definition definition = find(id, true);
            if (definition == null) return false;
            Commitment c = definition.value;
            if (c.frequency == Commitment.ONCE) {
                if (c.done == settled) return false;
                Commitment updated = new Commitment(c.id, c.name, c.amount, c.frequency, c.start,
                    c.end, settled, null, c.legacyPaidThrough, null, c.remind, c.remindBeforeMs);
                ContentValues values = new ContentValues();
                values.put("payload", definitionPayload(updated));
                db.update(DEFINITIONS, values, "id=?", new String[]{Long.toString(definition.rowId)});
                // Drop any orphan marks (e.g. from an older restore): the done flag alone is
                // the state, and leftovers would resurrect if this ever became recurring.
                db.delete(SETTLEMENTS, "definition_id=?",
                    new String[]{Long.toString(definition.rowId)});
            } else {
                Settlement old = mark(definition.rowId, date);
                boolean implicit = c.legacyPaidThrough > 0 && date <= c.legacyPaidThrough;
                boolean current = old == null ? implicit : old.settled;
                if (current == settled) return false;
                if (!settled && !implicit) {
                    db.delete(SETTLEMENTS, "id=?", new String[]{Long.toString(old.id)});
                } else if (settled && implicit) {
                    // Removing the exception reuses the watermark; never expand its history.
                    if (old != null) db.delete(SETTLEMENTS, "id=?", new String[]{Long.toString(old.id)});
                } else {
                    putMark(SETTLEMENTS, definition.rowId, date, settled);
                }
            }
            touch();
            return true;
        }

        boolean mergeDefinition(Commitment incoming) throws Exception {
            Definition existing = find(incoming.id, true);
            if (existing == null && isDeleted(incoming.id)) return false;
            boolean changed = false;
            if (existing == null) {
                upsert(incoming);
                return true;
            }
            Commitment local = existing.value;
            for (Long date : incoming.paid) {
                if (!isSettled(incoming.id, date)) changed |= settle(incoming.id, date, true);
            }
            for (Long date : incoming.unpaid) {
                if (isSettled(incoming.id, date)) changed |= settle(incoming.id, date, false);
            }
            return changed;
        }

        boolean mergeSettlementIfAbsent(String id, long date, boolean settled) throws Exception {
            Definition definition = find(id, true);
            if (definition == null || definition.value.frequency == Commitment.ONCE) return false;
            if (mark(definition.rowId, date) != null) return false;
            putMark(SETTLEMENTS, definition.rowId, date, settled);
            touch();
            return true;
        }

        void putMark(String table, long rowId, long date, boolean settled) throws Exception {
            ContentValues values = settlementValues(rowId, date, settled);
            // Preserve the row id so date updates cannot move behind a keyset cursor.
            if (db.update(table, values, "definition_id=? AND lookup=?",
                    new String[]{Long.toString(rowId), dateLookup(rowId, date)}) == 0)
                db.insertOrThrow(table, null, values);
        }

        /** Stages a complete replacement without allowing paid and unpaid to overwrite each other. */
        void putIncomingMark(String table, long rowId, long date, boolean settled) throws Exception {
            try (Cursor cursor = db.query(table, new String[]{"id", "lookup", "payload"},
                    "definition_id=? AND lookup=?",
                    new String[]{Long.toString(rowId), dateLookup(rowId, date)},
                    null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    Settlement old = decodeSettlement(cursor, rowId);
                    if (old.settled != settled)
                        throw new IllegalArgumentException("conflicting settlement state");
                    return;
                }
            }
            db.insertOrThrow(table, null, settlementValues(rowId, date, settled));
        }

        boolean upsert(Commitment c) throws Exception {
            c = canonical(c);
            Definition old = find(c.id, true);
            long rowId;
            if (old == null) {
                ContentValues values = new ContentValues();
                values.put("lookup", definitionLookup(c.id));
                values.put("payload", definitionPayload(c));
                rowId = db.insertOrThrow(DEFINITIONS, null, values);
            } else rowId = old.rowId;

            boolean reuse = lazyBelongs(c.paid, rowId, true) && lazyBelongs(c.unpaid, rowId, false);
            if (!reuse) {
                db.execSQL("CREATE TEMP TABLE IF NOT EXISTS incoming_marks (id INTEGER PRIMARY KEY,"
                    + " definition_id INTEGER NOT NULL, lookup TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)");
                db.delete("incoming_marks", null, null);
                // A one-time definition's state is its done flag alone: marks for it would be
                // invisible orphans today and resurrected history if it ever became recurring.
                if (c.frequency != Commitment.ONCE) {
                    for (Long date : c.paid) putIncomingMark("incoming_marks", rowId, date, true);
                    for (Long date : c.unpaid)
                        putIncomingMark("incoming_marks", rowId, date, false);
                }
                db.delete(SETTLEMENTS, "definition_id=?", new String[]{Long.toString(rowId)});
                db.execSQL("INSERT INTO " + SETTLEMENTS + " (definition_id,lookup,payload)"
                    + " SELECT definition_id,lookup,payload FROM incoming_marks ORDER BY id");
            }
            if (old != null) {
                ContentValues values = new ContentValues();
                values.put("payload", definitionPayload(c));
                db.update(DEFINITIONS, values, "id=?", new String[]{Long.toString(rowId)});
            }
            touch();
            return true;
        }

        boolean lazyBelongs(List<Long> values, long rowId, boolean settled) {
            if (!(values instanceof LazyDates)) return false;
            LazyDates lazy = (LazyDates) values;
            lazy.check(this);
            return lazy.rowId == rowId && lazy.settled == settled;
        }

        void replace(Collection<Commitment> definitions) throws Exception {
            db.execSQL("CREATE TEMP TABLE incoming_definitions (id INTEGER PRIMARY KEY, lookup TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE TEMP TABLE replacement_marks (id INTEGER PRIMARY KEY, definition_id INTEGER NOT NULL, lookup TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)");
            long rowId = nextDefinitionId();
            for (Commitment c : definitions) {
                c = canonical(c);
                ContentValues values = new ContentValues();
                values.put("id", rowId);
                values.put("lookup", definitionLookup(c.id));
                values.put("payload", definitionPayload(c));
                db.insertOrThrow("incoming_definitions", null, values);
                // An id in the authoritative new set is by definition not deleted: drop its
                // tombstone so backups taken afterwards stay self-consistent. Ids absent from
                // the set keep their tombstones.
                db.delete(META, "key=?", new String[]{DELETED_PREFIX + definitionLookup(c.id)});
                // Marks on a one-time definition would be invisible orphans (its state is the
                // done flag alone) and resurrected history if it ever became recurring.
                if (c.frequency != Commitment.ONCE) {
                    for (Long date : c.paid)
                        putIncomingMark("replacement_marks", rowId, date, true);
                    for (Long date : c.unpaid)
                        putIncomingMark("replacement_marks", rowId, date, false);
                }
                rowId = Math.incrementExact(rowId);
            }
            db.delete(DEFINITIONS, null, null);
            db.execSQL("INSERT INTO " + DEFINITIONS + " (id,lookup,payload) SELECT id,lookup,payload FROM incoming_definitions ORDER BY id");
            db.execSQL("INSERT INTO " + SETTLEMENTS + " (definition_id,lookup,payload) SELECT definition_id,lookup,payload FROM replacement_marks ORDER BY id");
            touch();
        }

        long nextDefinitionId() {
            try (Cursor cursor = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name=?",
                    new String[]{DEFINITIONS})) {
                return cursor.moveToFirst() ? Math.incrementExact(cursor.getLong(0)) : 1;
            }
        }

        boolean delete(String id) {
            int count = db.delete(DEFINITIONS, "lookup=?", new String[]{definitionLookup(id)});
            if (count == 0) return false;
            noteDeletion(id);
            touch();
            return true;
        }

        /**
         * Records an explicit deletion so restoring an older backup cannot resurrect the
         * definition. Ids are random per creation, so a tombstone can never match a future
         * definition. Tombstones live in META: no schema change, and reset wipes them with
         * everything else.
         */
        boolean noteDeletion(String id) {
            String key = DELETED_PREFIX + definitionLookup(id);
            if (meta(db, key) != null) return false;
            putMeta(db, key, id);
            touch();
            return true;
        }

        boolean isDeleted(String id) {
            return meta(db, DELETED_PREFIX + definitionLookup(id)) != null;
        }

        /** Forgets a deletion tombstone, used when a restore explicitly brings back deleted items. */
        boolean clearDeletion(String id) {
            int count = db.delete(META, "key=?",
                new String[]{DELETED_PREFIX + definitionLookup(id)});
            if (count == 0) return false;
            touch();
            return true;
        }

        DefinitionPage definitionPage(long afterId, int limit) throws Exception {
            List<Commitment> rows = new ArrayList<>();
            long next = Math.max(0, afterId);
            boolean more = false;
            try (Cursor cursor = db.query(DEFINITIONS, new String[]{"id", "lookup", "payload"},
                    "id>?", new String[]{Long.toString(next)}, null, null, "id ASC", Integer.toString(limit + 1))) {
                while (cursor.moveToNext()) {
                    if (rows.size() == limit) { more = true; break; }
                    rows.add(decodeDefinition(cursor, true).value);
                    next = cursor.getLong(0);
                }
            }
            return new DefinitionPage(rows, next, more, revision);
        }

        SettlementPage settlementPage(String id, long from, long to, long afterId, int limit,
                Boolean state) throws Exception {
            List<Settlement> rows = new ArrayList<>();
            Definition definition = find(id, true);
            long next = Math.max(0, afterId);
            boolean more = false;
            if (definition != null) {
                try (Cursor cursor = db.query(SETTLEMENTS, new String[]{"id", "lookup", "payload"},
                        "definition_id=? AND id>?", new String[]{Long.toString(definition.rowId), Long.toString(next)},
                        null, null, "id ASC")) {
                    while (cursor.moveToNext()) {
                        Settlement row = decodeSettlement(cursor, definition.rowId);
                        boolean match = row.date >= from && row.date <= to
                            && (state == null || state.booleanValue() == row.settled);
                        if (match && rows.size() == limit) { more = true; break; }
                        next = row.id;
                        if (match) rows.add(new Settlement(row.id, id, row.date, row.settled));
                    }
                }
            }
            return new SettlementPage(rows, next, more, id, from, to, state, revision);
        }
    }

    /** Bounded iterators preserve List compatibility without get(index)'s repeated prefix scans. */
    private static final class LazyDates extends Commitment.SettlementList {
        final Context context;
        final String owner;
        final String revision;
        final long rowId;
        final String definitionId;
        final boolean settled;

        LazyDates(Context context, String owner, String revision, long rowId, String definitionId,
                boolean settled) {
            Context application = context.getApplicationContext();
            this.context = application == null ? context : application;
            this.owner = owner;
            this.revision = revision;
            this.rowId = rowId;
            this.definitionId = definitionId;
            this.settled = settled;
        }

        void check(Session s) {
            if (!owner.equals(s.owner)) throw new IllegalStateException("commitment store changed");
            s.checkRevision(revision);
        }

        private <T> T read(Work<T> work) {
            try {
                Session active = ACTIVE.get();
                if (active != null) { check(active); return work.run(active); }
                return access(context, true, s -> { check(s); return work.run(s); });
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException("settlement read failed", e); }
        }

        @Override public boolean contains(Object date) {
            if (!(date instanceof Long) || (Long) date <= 0) return false;
            return read(s -> {
                Settlement mark = s.mark(rowId, (Long) date);
                return mark != null && mark.settled == settled;
            });
        }

        @Override public int size() {
            return read(s -> {
                int count = 0;
                try (Cursor cursor = s.db.query(SETTLEMENTS, new String[]{"id", "lookup", "payload"},
                        "definition_id=?", new String[]{Long.toString(rowId)}, null, null, "id ASC")) {
                    while (cursor.moveToNext()) if (s.decodeSettlement(cursor, rowId).settled == settled)
                        count = Math.incrementExact(count);
                }
                return count;
            });
        }

        @Override public Long get(int index) {
            if (index < 0) throw new IndexOutOfBoundsException();
            return read(s -> {
                int seen = 0;
                try (Cursor cursor = s.db.query(SETTLEMENTS, new String[]{"id", "lookup", "payload"},
                        "definition_id=?", new String[]{Long.toString(rowId)}, null, null, "id ASC")) {
                    while (cursor.moveToNext()) {
                        Settlement row = s.decodeSettlement(cursor, rowId);
                        if (row.settled == settled && seen++ == index) return row.date;
                    }
                }
                throw new IndexOutOfBoundsException();
            });
        }

        @Override public Iterator<Long> iterator() {
            return new Iterator<Long>() {
                SettlementPage page;
                int offset;

                private void checkCurrentRevision() {
                    read(s -> { check(s); return null; });
                }

                private void advance() {
                    if (page == null || offset == page.rows.size() && page.hasMore) {
                        long after = page == null ? 0 : page.nextId;
                        page = read(s -> s.settlementPage(definitionId, 1, Long.MAX_VALUE,
                            after, PAGE_SIZE, settled));
                        offset = 0;
                    }
                }
                @Override public boolean hasNext() {
                    checkCurrentRevision();
                    advance();
                    return offset < page.rows.size();
                }
                @Override public Long next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return page.rows.get(offset++).date;
                }
            };
        }
    }

    private static void requireDefinition(Commitment c) {
        if (c == null || c.id == null || c.id.isEmpty() || c.name == null || c.name.trim().isEmpty()
                || c.amount == 0 || c.frequency < Commitment.ONCE || c.frequency > Commitment.YEARLY
                || c.start <= 0 || c.end != null && c.end < Commitment.startOfDay(c.start)
                || c.legacyPaidThrough < 0 || c.remindBeforeMs < 0)
            throw new IllegalArgumentException("invalid commitment");
        // Lazy collections are already validated encrypted rows. Validation must not copy them.
        if (!(c.paid instanceof LazyDates)) for (Long date : c.paid) {
            if (date == null) throw new IllegalArgumentException("invalid settlement date");
            requireDate(date);
        }
        if (!(c.unpaid instanceof LazyDates)) for (Long date : c.unpaid) {
            if (date == null) throw new IllegalArgumentException("invalid settlement date");
            requireDate(date);
        }
        if (!(c.paid instanceof LazyDates) && !(c.unpaid instanceof LazyDates)
                && !Collections.disjoint(c.paid, c.unpaid))
            throw new IllegalArgumentException("conflicting settlement state");
    }

    private static Commitment canonical(Commitment c) {
        requireDefinition(c);
        Commitment normalized = Commitment.normalized(c);
        if (normalized == null) throw new IllegalArgumentException("invalid commitment");
        return normalized;
    }

    private static void requireId(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("invalid commitment id");
    }
    private static void requireDate(long date) {
        if (date <= 0) throw new IllegalArgumentException("invalid settlement date");
    }
    private static void requirePageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("invalid page size");
    }
    private static long rowCount(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }
    private static String digest(String value) throws Exception {
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    private static String definitionIdentity(String lookup) {
        return "definition\n" + lookup;
    }
    private static String settlementIdentity(long rowId, String lookup) {
        return "settlement\n" + rowId + "\n" + lookup;
    }
    private static String meta(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(META, new String[]{"value"}, "key=?", new String[]{key},
                null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }
    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        if (db.insertWithOnConflict(META, null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new IllegalStateException("commitment metadata write failed");
    }

    private static void ensureRowKey(SQLiteDatabase db) throws Exception {
        if (meta(db, ROW_KEY) != null) return;
        try (Cursor definitions = db.query(DEFINITIONS, new String[]{"payload"}, null, null,
                null, null, null); Cursor settlements = db.query(SETTLEMENTS,
                new String[]{"payload"}, null, null, null, null, null)) {
            while (definitions.moveToNext()) {
                if (EncryptedRowCodec.isVersioned(definitions.getString(0)))
                    throw new Exception("missing commitment row key");
            }
            while (settlements.moveToNext()) {
                if (EncryptedRowCodec.isVersioned(settlements.getString(0)))
                    throw new Exception("missing commitment row key");
            }
        }
        putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            // Keep corrupt authoritative data available for diagnosis/recovery instead of allowing
            // Android's default corruption handler to delete it.
            super(context, DB_NAME, null, 1, db -> {
                throw new SQLiteException("commitment database is corrupt; preserved for recovery");
            });
        }
        @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + DEFINITIONS + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " lookup TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE " + SETTLEMENTS + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " definition_id INTEGER NOT NULL REFERENCES definitions(id) ON DELETE CASCADE,"
                + " lookup TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(definition_id,lookup))");
            db.execSQL("CREATE INDEX settlement_order ON " + SETTLEMENTS + "(definition_id,id)");
            db.execSQL("CREATE TABLE " + META + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("unsupported commitment schema upgrade");
        }
    }
}
