package com.ashkanrafiee.balance;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteException;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Uncapped encrypted rows for notes, bank reasons/channels and ordered user tags.
 *
 * <p>New row values use a session-scoped AES-GCM data key, wrapped once by BalanceData's Keystore
 * bridge. Existing rows remain readable through the legacy Keystore-backed format. Indexes contain
 * only the kind, row id and a keyed lookup digest; the random lookup key is itself encrypted.
 * Existing per-value length/tag limits still apply, but there is no limit on the number of rows.
 *
 * <p>All operations share BalanceData's scan/restore monitor. A separate preference token prevents a
 * leftover database from becoming visible after preferences are cleared. Reset preserves that token
 * and keeps notes/tags unless requested otherwise. Errors propagate, rather than presenting a failed
 * read as an empty map that a caller could accidentally write over good data.
 *
 * <p>First access migrates the requested legacy kind in a SQLite transaction. A durable migration
 * marker makes cleanup retryable without resurrecting deleted rows after a crash. Do not mix this
 * store with the old BalanceData metadata writers: integrate the facade and reset together before
 * using it in the application. Map reads are compatibility helpers; use pages/visitors for bounded
 * reads. Initial legacy migration necessarily parses the existing single JSON preference value.
 */
final class MetadataStore {
    static final int NOTES = 1;
    static final int REASONS = 2;
    static final int CHANNELS = 3;
    static final int TAGS = 4;
    private static final int ALL = 0;

    static final String DB_NAME = "balance_metadata.db";
    static final String KEY_METADATA_STORE_TOKEN = "metadata_store_token";
    static final int MAX_PAGE_SIZE = 1_000;
    private static final int DEFAULT_PAGE_SIZE = 256;
    private static final int MAX_KEY_LENGTH = 1_024;
    private static final int DB_VERSION = 1;
    static final String TABLE = "metadata";
    private static final String META = "store_meta";
    private static final String OWNER = "owner";
    private static final String INDEX_KEY = "index_key";
    private static final String ROW_KEY = "row_key";
    private static final String REVISION = "revision";
    private static final String ROW_DOMAIN = "metadata";
    private static final String[] LEGACY_KEYS = {null, BalanceData.KEY_TX_NOTES,
        BalanceData.KEY_TX_REASONS, BalanceData.KEY_TX_CHANNELS, BalanceData.KEY_TX_TAGS};

    private MetadataStore() {}

    static final class Row {
        final long id;
        final int kind;
        final String key;
        final String text;
        final List<String> tags;

        private Row(long id, int kind, String key, String text, Collection<String> tags) {
            this.id = id;
            this.kind = kind;
            this.key = key;
            this.text = text;
            this.tags = tags == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tags));
        }
    }

    static final class Page {
        final List<Row> rows;
        final long nextId;
        /** Alias matching TransactionStore's cursor terminology. */
        final long nextOrdinal;
        final boolean hasMore;
        private final int kind;
        private final String owner;
        private final String revision;

        private Page(List<Row> rows, long nextId, boolean hasMore, int kind,
                String owner, String revision) {
            this.rows = Collections.unmodifiableList(rows);
            this.nextId = nextId;
            this.nextOrdinal = nextId;
            this.hasMore = hasMore;
            this.kind = kind;
            this.owner = owner;
            this.revision = revision;
        }
    }

    interface Visitor { void accept(Row row) throws Exception; }
    private interface Work<T> { T run(Session session) throws Exception; }

    /** One pinned read connection serving many point lookups. Opening the store (database open
     *  plus key unwraps) happens once instead of once per row, which is what makes history search
     *  and page metadata affordable. Not thread-safe: use from a single worker thread and close it
     *  when the pass ends. Reads see committed state; like the point lookup helpers it replaces,
     *  it does not pin a store revision. */
    static final class LookupSession implements AutoCloseable {
        private final Helper helper;
        private final Session session;
        private boolean closed;

        private LookupSession(Helper helper, Session session) {
            this.helper = helper;
            this.session = session;
        }

        /** Opens the session, running any pending legacy migration first. */
        static LookupSession open(Context context) throws Exception {
            if (context == null) throw new NullPointerException("context");
            synchronized (BalanceData.class) {
                // Migrate through the normal path first so this session only ever reads the
                // current row format; afterwards it owns one connection for every lookup.
                access(context, ALL, false, true, s -> null);
                Context resolved = DataGeneration.context(context);
                SharedPreferences prefs = resolved.getSharedPreferences(
                    BalanceData.PREFS_DATA, Context.MODE_PRIVATE);
                Helper helper = new Helper(resolved);
                SQLiteDatabase db = null;
                boolean ok = false;
                try {
                    db = helper.getReadableDatabase();
                    String owner = meta(db, OWNER);
                    String token = prefs.getString(KEY_METADATA_STORE_TOKEN, null);
                    if (owner != null && token != null && !owner.equals(token))
                        throw new IllegalStateException("metadata store ownership unavailable");
                    Session session = new Session(db, owner);
                    LookupSession out = new LookupSession(helper, session);
                    helper = null;
                    ok = true;
                    return out;
                } finally {
                    if (!ok) {
                        if (db != null && db.isOpen()) {
                            try { db.close(); } catch (Exception ignored) { }
                        }
                        if (helper != null) helper.close();
                    }
                }
            }
        }

        /** The stored text for one key, or null when absent. */
        String text(int kind, String key) throws Exception {
            requireTextKind(kind);
            requireKey(key);
            checkOpen();
            Row row = session.find(kind, key);
            return row == null ? null : row.text;
        }

        /** A mutable copy of the tags for one key, empty when absent. */
        List<String> tags(String key) throws Exception {
            requireKey(key);
            checkOpen();
            Row row = session.find(TAGS, key);
            return row == null ? new ArrayList<>() : new ArrayList<>(row.tags);
        }

        private void checkOpen() {
            if (closed) throw new IllegalStateException("metadata lookup session is closed");
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                session.close();
            } finally {
                helper.close();
            }
        }
    }

    static Map<String, String> readNotes(Context context) throws Exception {
        return readText(context, NOTES);
    }

    static Map<String, String> readReasons(Context context) throws Exception {
        return readText(context, REASONS);
    }

    static Map<String, String> readChannels(Context context) throws Exception {
        return readText(context, CHANNELS);
    }

    static boolean writeNotes(Context context, Map<String, String> values) throws Exception {
        return replaceText(context, NOTES, values);
    }

    static boolean writeReasons(Context context, Map<String, String> values) throws Exception {
        return replaceText(context, REASONS, values);
    }

    static boolean writeChannels(Context context, Map<String, String> values) throws Exception {
        return replaceText(context, CHANNELS, values);
    }

    /** Compatibility read; the returned map owns fresh, mutable tag lists. */
    static Map<String, List<String>> readTags(Context context) throws Exception {
        return access(context, TAGS, false, true, s -> {
            Map<String, List<String>> out = new LinkedHashMap<>();
            visit(s, TAGS, row -> out.put(row.key, new ArrayList<>(row.tags)));
            return out;
        });
    }

    static Map<String, String> readText(Context context, int kind) throws Exception {
        requireTextKind(kind);
        return access(context, kind, false, true, s -> {
            Map<String, String> out = new LinkedHashMap<>();
            visit(s, kind, row -> out.put(row.key, row.text));
            return out;
        });
    }

    static String getText(Context context, int kind, String key) throws Exception {
        requireTextKind(kind);
        requireKey(key);
        return access(context, kind, false, true, s -> {
            Row row = s.find(kind, key);
            return row == null ? null : row.text;
        });
    }

    static String getNote(Context context, Transaction transaction) throws Exception {
        return getText(context, NOTES, BalanceData.noteKey(transaction));
    }

    static String getNote(Context context, String key) throws Exception {
        return getText(context, NOTES, key);
    }

    static boolean setNote(Context context, Transaction transaction, String text) throws Exception {
        return setNote(context, BalanceData.noteKey(transaction), text);
    }

    static boolean setNote(Context context, String key, String text) throws Exception {
        return setText(context, NOTES, key, text == null ? null : text.trim());
    }

    /** Map writes preserve whitespace, matching the legacy serializer; setNote trims user input. */
    static boolean setText(Context context, int kind, String key, String text) throws Exception {
        requireTextKind(kind);
        requireKey(key);
        String value = normalizeText(text);
        return access(context, kind, true, true, s -> {
            s.put(kind, key, value, null);
            return true;
        });
    }

    static List<String> getTags(Context context, Transaction transaction) throws Exception {
        return getTags(context, BalanceData.noteKey(transaction));
    }

    static List<String> getTags(Context context, String key) throws Exception {
        requireKey(key);
        return access(context, TAGS, false, true, s -> {
            Row row = s.find(TAGS, key);
            return row == null ? new ArrayList<>() : new ArrayList<>(row.tags);
        });
    }

    static boolean setTags(Context context, Transaction transaction, Collection<String> tags)
            throws Exception {
        return setTags(context, BalanceData.noteKey(transaction), tags);
    }

    static boolean setTags(Context context, String key, Collection<String> input) throws Exception {
        requireKey(key);
        List<String> tags = normalizeTags(input);
        return access(context, TAGS, true, true, s -> {
            s.put(TAGS, key, null, tags);
            return true;
        });
    }

    /** Replaces only one kind. An empty/null map clears it without affecting the other kinds. */
    static boolean replaceText(Context context, int kind, Map<String, String> values)
            throws Exception {
        requireTextKind(kind);
        return access(context, kind, true, true, s -> {
                    s.clear(kind);
            if (values != null) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    requireKey(entry.getKey());
                    s.putNew(kind, entry.getKey(), normalizeText(entry.getValue()), null);
                }
            }
            return true;
        });
    }

    static boolean writeTags(Context context, Map<String, List<String>> values) throws Exception {
        return access(context, TAGS, true, true, s -> {
            s.clear(TAGS);
            if (values != null) {
                for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                    requireKey(entry.getKey());
                    s.putNew(TAGS, entry.getKey(), null, normalizeTags(entry.getValue()));
                }
            }
            return true;
        });
    }

    static boolean mergeReasons(Context context, Map<String, String> values) throws Exception {
        return mergeText(context, REASONS, values);
    }

    static boolean mergeChannels(Context context, Map<String, String> values) throws Exception {
        return mergeText(context, CHANNELS, values);
    }

    static boolean mergeTextEntry(Context context, int kind, String key, String value)
            throws Exception {
        requireTextKind(kind);
        requireKey(key);
        String normalized = normalizeText(value);
        if (normalized == null) return false;
        return access(context, kind, true, true, s -> {
            if (s.find(kind, key) != null) return false;
            return s.put(kind, key, normalized, null);
        });
    }

    /** Additive local-first merge for scans and backup restore; false means no change. */
    static boolean mergeText(Context context, int kind, Map<String, String> values) throws Exception {
        requireTextKind(kind);
        if (values == null || values.isEmpty()) return false;
        return access(context, kind, true, true, s -> {
            boolean changed = false;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                requireKey(entry.getKey());
                String text = normalizeText(entry.getValue());
                if (text != null && s.find(kind, entry.getKey()) == null) {
                    changed |= s.put(kind, entry.getKey(), text, null);
                }
            }
            return changed;
        });
    }

    static boolean mergeTags(Context context, Map<String, List<String>> values) throws Exception {
        if (values == null || values.isEmpty()) return false;
        return access(context, TAGS, true, true, s -> {
            boolean changed = false;
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                requireKey(entry.getKey());
                Row row = s.find(TAGS, entry.getKey());
                List<String> tags = unionTags(row == null ? Collections.emptyList() : row.tags,
                    normalizeTags(entry.getValue()));
                changed |= s.put(TAGS, entry.getKey(), null, tags);
            }
            return changed;
        });
    }

    static boolean mergeTagsEntry(Context context, String key, Collection<String> values)
            throws Exception {
        requireKey(key);
        List<String> incoming = normalizeTags(values);
        if (incoming.isEmpty()) return false;
        return access(context, TAGS, true, true, s -> {
            Row row = s.find(TAGS, key);
            List<String> merged = unionTags(row == null ? Collections.emptyList() : row.tags, incoming);
            return s.put(TAGS, key, null, merged);
        });
    }

    /** Destination text wins; tags union destination-first. All four kinds move atomically. */
    static boolean migrateTransactionText(Context context, Map<Transaction, Transaction> replaced)
            throws Exception {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (replaced != null) {
            for (Map.Entry<Transaction, Transaction> entry : replaced.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                aliases.put(BalanceData.noteKey(entry.getKey()), BalanceData.noteKey(entry.getValue()));
            }
        }
        return migrateKeys(context, aliases);
    }

    static boolean migrateKeys(Context context, Map<String, String> aliases) throws Exception {
        if (aliases == null || aliases.isEmpty()) return false;
        return access(context, ALL, true, true, s -> {
            boolean changed = false;
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                String from = alias.getKey();
                String to = alias.getValue();
                requireKey(from);
                requireKey(to);
                if (from.equals(to)) continue;
                for (int kind = NOTES; kind <= TAGS; kind++) {
                    Row source = s.find(kind, from);
                    if (source == null) continue;
                    Row destination = s.find(kind, to);
                    if (kind == TAGS) {
                        s.put(TAGS, to, null, unionTags(destination == null
                            ? Collections.emptyList() : destination.tags, source.tags));
                    } else if (destination == null) {
                        s.put(kind, to, source.text, null);
                    }
                    s.remove(source);
                    changed = true;
                }
            }
            return changed;
        });
    }

    /** A live keyset cursor. Use the Page overload for generation-checked continuations. */
    static Page page(Context context, long afterId, int limit) throws Exception {
        return page(context, ALL, afterId, limit);
    }

    /** Kind-specific SQL filtering avoids decrypting unrelated metadata. Start at id 0 or -1. */
    static Page page(Context context, int kind, long afterId, int limit) throws Exception {
        requireKindOrAll(kind);
        requirePageSize(limit);
        return access(context, kind, false, true, s -> s.page(kind, afterId, limit));
    }

    /** Rejects a continuation if any metadata changed, including reset or token invalidation. */
    static Page page(Context context, Page previous, int limit) throws Exception {
        if (previous == null) throw new NullPointerException("previous page");
        requirePageSize(limit);
        return access(context, previous.kind, false, true, s -> {
            if (!same(previous.owner, s.owner) || !same(previous.revision, s.revision))
                throw new IllegalStateException("metadata changed; restart paging");
            return s.page(previous.kind, previous.nextId, limit);
        });
    }

    static void forEach(Context context, int pageSize, Visitor visitor) throws Exception {
        forEach(context, ALL, pageSize, visitor);
    }

    /** Visitor calls happen outside the database transaction; a concurrent write invalidates paging. */
    static void forEach(Context context, int kind, int pageSize, Visitor visitor) throws Exception {
        if (visitor == null) throw new NullPointerException("visitor");
        Page current = page(context, kind, 0, pageSize);
        while (true) {
            for (Row row : current.rows) visitor.accept(row);
            if (!current.hasMore) return;
            current = page(context, current, pageSize);
        }
    }

    /** Keeps notes/tags by default and always discards re-detectable reasons/channels. */
    static boolean reset(Context context, boolean alsoNotes) throws Exception {
        // Reset is an explicit destructive operation. It must remain possible when a preference
        // token was lost, and must not be confused with normal ownership recovery. Deleting by
        // kind requires no plaintext key and therefore does not weaken the normal wrong-token
        // fail-closed path.
        synchronized (BalanceData.class) {
            context = DataGeneration.context(context);
            SharedPreferences prefs = context.getSharedPreferences(BalanceData.PREFS_DATA,
                Context.MODE_PRIVATE);
            try (Helper helper = new Helper(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    if (alsoNotes) {
                        db.delete(TABLE, null, null);
                        db.delete(META, null, null);
                    } else {
                        db.delete(TABLE, "kind IN (?,?)",
                            new String[]{Integer.toString(REASONS), Integer.toString(CHANNELS)});
                        putMeta(db, migrationKey(REASONS), "absent");
                        putMeta(db, migrationKey(CHANNELS), "absent");
                        putMeta(db, REVISION, UUID.randomUUID().toString());
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            SharedPreferences.Editor cleanup = prefs.edit()
                .remove(BalanceData.KEY_TX_REASONS)
                .remove(BalanceData.KEY_TX_CHANNELS);
            if (alsoNotes) {
                cleanup.remove(BalanceData.KEY_TX_NOTES)
                    .remove(BalanceData.KEY_TX_TAGS)
                    .remove(KEY_METADATA_STORE_TOKEN);
            }
            if (!cleanup.commit()) throw new IllegalStateException("metadata reset cleanup failed");
            return true;
        }
    }

    // One transaction covers owner initialization, migration and each operation. Preference cleanup
    // follows its commit; the migration marker prevents a retry from importing a stale snapshot.
    private static <T> T access(Context context, int kind, boolean write, boolean migrate,
            Work<T> work) throws Exception {
        synchronized (BalanceData.class) {
            // Resolve once per public operation. A stage context is intentionally returned as-is,
            // so restore merges cannot be redirected by a later manifest publication.
            context = DataGeneration.context(context);
            SharedPreferences prefs = context.getSharedPreferences(
                BalanceData.PREFS_DATA, Context.MODE_PRIVATE);
            Map<Integer, String> legacy = new LinkedHashMap<>();
            for (int k = NOTES; k <= TAGS; k++) {
                if (kind == ALL || kind == k) legacy.put(k, prefs.getString(LEGACY_KEYS[k], null));
            }
            boolean hasLegacy = false;
            for (String value : legacy.values()) hasLegacy |= value != null;
            T result;
            try (Helper helper = new Helper(context)) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    String owner = meta(db, OWNER);
                    String token = prefs.getString(KEY_METADATA_STORE_TOKEN, null);
                    boolean owned = owner != null && (token == null || owner.equals(token));
                    if (owner != null && token != null && !owner.equals(token))
                        throw new IllegalStateException("metadata store ownership unavailable");
                    if (!owned && (write || hasLegacy)) {
                        db.delete(TABLE, null, null);
                        db.delete(META, null, null);
                        owner = UUID.randomUUID().toString();
                        byte[] secret = new byte[32];
                        new SecureRandom().nextBytes(secret);
                        try {
                            putMeta(db, OWNER, owner);
                            putMeta(db, INDEX_KEY, BalanceData.encryptStorePayload(
                                Base64.encodeToString(secret, Base64.NO_WRAP)));
                        } finally {
                            java.util.Arrays.fill(secret, (byte) 0);
                        }
                        putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
                        putMeta(db, REVISION, UUID.randomUUID().toString());
                        if (!prefs.edit().putString(KEY_METADATA_STORE_TOKEN, owner).commit())
                            throw new Exception("metadata owner could not be persisted");
                        owned = true;
                    }
                    if (owned) ensureRowKey(db);
                    Session session = new Session(db, owned ? owner : null);
                    try {
                        if (owned && migrate) {
                            for (Map.Entry<Integer, String> entry : legacy.entrySet()) {
                                String marker = migrationKey(entry.getKey());
                                if (!legacyMarker(entry.getValue()).equals(meta(db, marker))) {
                                    if (entry.getValue() != null)
                                        importLegacy(session, entry.getKey(), entry.getValue());
                                    putMeta(db, marker, legacyMarker(entry.getValue()));
                                }
                            }
                        }
                        result = work.run(session);
                        db.setTransactionSuccessful();
                    } finally {
                        session.close();
                    }
                } finally {
                    db.endTransaction();
                }
            }
            // A writer on the legacy API must not lose a value changed during migration cleanup.
            SharedPreferences.Editor cleanup = prefs.edit();
            boolean remove = false;
            for (Map.Entry<Integer, String> entry : legacy.entrySet()) {
                if (entry.getValue() != null && entry.getValue().equals(
                        prefs.getString(LEGACY_KEYS[entry.getKey()], null))) {
                    cleanup.remove(LEGACY_KEYS[entry.getKey()]);
                    remove = true;
                }
            }
            if (remove && !cleanup.commit()) throw new Exception("legacy metadata cleanup failed");
            return result;
        }
    }

    private static void importLegacy(Session session, int kind, String stored) throws Exception {
        String json = stored.trim().startsWith("{") ? stored
            : BalanceData.decryptStorePayload(stored);
        JSONObject object = new JSONObject(json);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            requireKey(key);
            Object value = object.get(key);
            if (value == JSONObject.NULL) continue;
            if (kind == TAGS) {
                if (!(value instanceof JSONArray)) throw new Exception("invalid legacy tag row");
                List<String> tags = parseTags((JSONArray) value);
                Row existing = session.find(TAGS, key);
                session.put(TAGS, key, null, unionTags(existing == null
                    ? Collections.emptyList() : existing.tags, tags));
            } else {
                if (!(value instanceof String)) throw new Exception("invalid legacy text row");
                if (session.find(kind, key) == null)
                    session.put(kind, key, normalizeText((String) value), null);
            }
        }
    }

    private static void visit(Session session, int kind, Visitor visitor) throws Exception {
        long after = 0;
        while (true) {
            Page page = session.page(kind, after, DEFAULT_PAGE_SIZE);
            for (Row row : page.rows) visitor.accept(row);
            if (!page.hasMore) return;
            after = page.nextId;
        }
    }

    private static final class Session {
        final SQLiteDatabase db;
        final String owner;
        final Mac index;
        final EncryptedRowCodec rows;
        String revision;
        boolean touched;

        Session(SQLiteDatabase db, String owner) throws Exception {
            this.db = db;
            this.owner = owner;
            revision = owner == null ? null : meta(db, REVISION);
            if (owner == null) {
                index = null;
                rows = null;
            } else {
                String encoded = meta(db, INDEX_KEY);
                if (encoded == null || revision == null) throw new Exception("incomplete metadata store");
                byte[] secret = Base64.decode(BalanceData.decryptStorePayload(encoded), Base64.NO_WRAP);
                try {
                    if (secret.length != 32) throw new Exception("invalid metadata lookup key");
                    index = Mac.getInstance("HmacSHA256");
                    index.init(new SecretKeySpec(secret, "HmacSHA256"));
                } finally {
                    java.util.Arrays.fill(secret, (byte) 0);
                }
                String encodedRows = meta(db, ROW_KEY);
                if (encodedRows == null) throw new Exception("incomplete metadata store");
                rows = EncryptedRowCodec.open(encodedRows, ROW_DOMAIN);
            }
        }

        String lookup(int kind, String key) {
            return Base64.encodeToString(index.doFinal((kind + "\n" + key)
                .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        }

        Row find(int kind, String key) throws Exception {
            if (owner == null) return null;
            try (Cursor cursor = db.query(TABLE, new String[]{"id", "kind", "lookup", "payload"},
                    "kind=? AND lookup=?", new String[]{Integer.toString(kind), lookup(kind, key)},
                    null, null, null, "1")) {
                if (!cursor.moveToFirst()) return null;
                Row row = decode(cursor);
                if (!key.equals(row.key)) throw new Exception("metadata lookup mismatch");
                return row;
            }
        }

        boolean put(int kind, String key, String text, List<String> tags) throws Exception {
            Row old = find(kind, key);
            boolean empty = kind == TAGS ? tags.isEmpty() : text == null;
            if (empty) {
                if (old == null) return false;
                remove(old);
                return true;
            }
            if (old != null && (kind == TAGS ? old.tags.equals(tags) : old.text.equals(text)))
                return false;
            JSONObject payload = new JSONObject().put("version", 1).put("owner", owner)
                .put("kind", kind).put("key", key);
            if (kind == TAGS) {
                JSONArray array = new JSONArray();
                for (String tag : tags) array.put(tag);
                payload.put("tags", array);
            } else payload.put("text", text);
            ContentValues values = new ContentValues();
            values.put("kind", kind);
            String lookup = lookup(kind, key);
            values.put("lookup", lookup);
            values.put("payload", rows.encrypt(payload.toString(), rowIdentity(kind, lookup)));
            if (old == null) db.insertOrThrow(TABLE, null, values);
            else if (db.update(TABLE, values, "id=?", new String[]{Long.toString(old.id)}) != 1)
                throw new Exception("metadata row disappeared");
            touch();
            return true;
        }

        void putNew(int kind, String key, String text, List<String> tags) throws Exception {
            boolean empty = kind == TAGS ? tags == null || tags.isEmpty() : text == null;
            if (empty) return;
            JSONObject payload = new JSONObject().put("version", 1).put("owner", owner)
                .put("kind", kind).put("key", key);
            if (kind == TAGS) {
                JSONArray array = new JSONArray();
                for (String tag : tags) array.put(tag);
                payload.put("tags", array);
            } else payload.put("text", text);
            ContentValues values = new ContentValues();
            values.put("kind", kind);
            String lookup = lookup(kind, key);
            values.put("lookup", lookup);
            values.put("payload", rows.encrypt(payload.toString(), rowIdentity(kind, lookup)));
            db.insertOrThrow(TABLE, null, values);
            touch();
        }

        void remove(Row row) {
            db.delete(TABLE, "id=?", new String[]{Long.toString(row.id)});
            touch();
        }

        void clear(int kind) {
            int deleted = kind == ALL ? db.delete(TABLE, null, null)
                : db.delete(TABLE, "kind=?", new String[]{Integer.toString(kind)});
            if (deleted > 0) touch();
        }

        void touch() {
            if (touched) return;
            revision = UUID.randomUUID().toString();
            putMeta(db, REVISION, revision);
            touched = true;
        }

        Page page(int kind, long afterId, int limit) throws Exception {
            List<Row> rows = new ArrayList<>(limit);
            long next = Math.max(0, afterId);
            boolean more = false;
            if (owner != null) {
                String selection = kind == ALL ? "id > ?" : "kind=? AND id > ?";
                String[] args = kind == ALL ? new String[]{Long.toString(next)}
                    : new String[]{Integer.toString(kind), Long.toString(next)};
                try (Cursor cursor = db.query(TABLE,
                        new String[]{"id", "kind", "lookup", "payload"}, selection, args,
                        null, null, "id ASC", Integer.toString(limit + 1))) {
                    while (cursor.moveToNext()) {
                        if (rows.size() == limit) { more = true; break; }
                        Row row = decode(cursor);
                        rows.add(row);
                        next = row.id;
                    }
                }
            }
            return new Page(rows, next, more, kind, owner, revision);
        }

        Row decode(Cursor cursor) throws Exception {
            int kind = cursor.getInt(1);
            String lookup = cursor.getString(2);
            JSONObject object = new JSONObject(rows.decrypt(cursor.getString(3),
                rowIdentity(kind, lookup)));
            String key = object.getString("key");
            requireKey(key);
            requireKindOrAll(kind);
            if (kind == ALL || object.getInt("version") != 1 || object.getInt("kind") != kind
                    || !owner.equals(object.getString("owner"))
                    || !lookup(kind, key).equals(lookup))
                throw new Exception("invalid metadata row");
            if (kind == TAGS) {
                List<String> tags = parseTags(object.getJSONArray("tags"));
                if (tags.isEmpty()) throw new Exception("empty metadata tags");
                return new Row(cursor.getLong(0), kind, key, null, tags);
            }
            Object value = object.get("text");
            if (!(value instanceof String)) throw new Exception("invalid metadata text");
            String text = normalizeText((String) value);
            if (text == null) throw new Exception("empty metadata text");
            return new Row(cursor.getLong(0), kind, key, text, null);
        }

        void close() {
            if (rows != null) rows.close();
        }
    }

    private static String normalizeText(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static List<String> parseTags(JSONArray array) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value == JSONObject.NULL) continue;
            if (!(value instanceof String)) throw new Exception("invalid metadata tag");
            addTag(out, (String) value);
        }
        return out;
    }

    private static List<String> normalizeTags(Collection<String> input) {
        List<String> out = new ArrayList<>();
        if (input != null) {
            for (String tag : input) {
                addTag(out, tag);
            }
        }
        return out;
    }

    private static List<String> unionTags(List<String> current, List<String> incoming) {
        List<String> out = new ArrayList<>(current);
        for (String tag : incoming) addTag(out, tag);
        return out;
    }

    private static void addTag(List<String> out, String raw) {
        if (raw == null) return;
        String tag = raw.trim();
        if (tag.isEmpty()) return;
        for (String existing : out) if (BalanceData.sameTag(existing, tag)) return;
        out.add(tag);
    }

    private static String cap(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

    private static void requireKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH)
            throw new IllegalArgumentException("invalid metadata key");
    }

    private static void requireTextKind(int kind) {
        if (kind < NOTES || kind > CHANNELS) throw new IllegalArgumentException("not a text kind");
    }

    private static void requireKindOrAll(int kind) {
        if (kind < ALL || kind > TAGS) throw new IllegalArgumentException("invalid metadata kind");
    }

    private static void requirePageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("invalid page size");
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String rowIdentity(int kind, String lookup) {
        return "kind\n" + kind + "\nlookup\n" + lookup;
    }
    private static String migrationKey(int kind) { return "migrated_" + kind; }

    private static String legacyMarker(String stored) throws Exception {
        if (stored == null) return "absent";
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(stored.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(digest, Base64.NO_WRAP);
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
            throw new IllegalStateException("metadata state write failed");
    }

    private static void ensureRowKey(SQLiteDatabase db) throws Exception {
        if (meta(db, ROW_KEY) != null) return;
        try (Cursor cursor = db.query(TABLE, new String[]{"payload"}, null, null,
                null, null, null)) {
            while (cursor.moveToNext()) {
                if (EncryptedRowCodec.isVersioned(cursor.getString(0)))
                    throw new Exception("missing metadata row key");
            }
        }
        putMeta(db, ROW_KEY, EncryptedRowCodec.createWrappedKey());
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            // Preserve a corrupt authoritative file for recovery; Android's default handler
            // deletes it before the store can fail closed.
            super(context, DB_NAME, null, DB_VERSION, db -> {
                throw new SQLiteException("metadata database is corrupt; preserved for recovery");
            });
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " kind INTEGER NOT NULL CHECK(kind BETWEEN 1 AND 4),"
                + " lookup TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)");
            db.execSQL("CREATE INDEX metadata_kind_order ON " + TABLE + "(kind, id)");
            db.execSQL("CREATE TABLE " + META + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Future schema changes must migrate rows, never drop user-created notes or tags.
            throw new IllegalStateException("unsupported metadata schema upgrade");
        }
    }
}
