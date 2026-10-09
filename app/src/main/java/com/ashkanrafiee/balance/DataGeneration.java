package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable selector for the local data generation.
 *
 * <p>The absence of the selector is the original, legacy data layout. Once a selector has been
 * published, it is authoritative: an invalid selector or incomplete generation is an error and
 * never falls back to the legacy paths. A stage is a fixed context, so all of the store operations
 * used while building it stay in that directory even if a different generation is published by
 * another caller later. The caller must hold BalanceData.class from local migration through
 * beginStage, all merges and publish to prevent a scan/write between the copy and publication.
 */
final class DataGeneration {
    private static final String TAG = "BalanceGenerations";
    /** Package-private for instrumentation tests that need to corrupt the selector deliberately. */
    static final String MANIFEST_NAME = "balance_data_generation";
    static final String GENERATIONS_DIRECTORY = ".balance-generations";

    private static final String MARKER_NAME = "generation.marker";
    private static final String PREFS_GENERATION_KEY = "__balance_data_generation";
    private static final String PREFS_PREFIX = BalanceData.PREFS_DATA + ".generation.";
    private static final String LEGACY_PAGE_PREFIX = "transactions_page_v2_";
    private static final String PRESENCE_FILE = "balance_transactions.present";
    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024;
    private static final int MAX_MARKER_BYTES = 4 * 1024;
    private static final String[] DATABASES = {
        TransactionStore.DB_NAME,
        MetadataStore.DB_NAME,
        CommitmentStore.DB_NAME,
        SourceStore.DB_NAME
    };

    private DataGeneration() {}

    enum PublishPoint { BEFORE_MANIFEST, AFTER_MANIFEST }
    interface PublishHook { void at(PublishPoint point) throws Exception; }

    /** Resolves the generation at the start of a normal store/facade operation. */
    static Context context(Context input) {
        if (input == null) throw new NullPointerException("context");
        synchronized (BalanceData.class) {
            if (input instanceof GenerationContext) {
                GenerationContext generation = (GenerationContext) input;
                generation.checkUsable();
                if (generation.stage) return generation;
                input = generation.root;
            }
            final Context root = applicationContext(input);
            Manifest manifest = readManifest(root);
            if (manifest == null) return root;
            return openGeneration(root, manifest.generation, false, null);
        }
    }

    /** Shared access point for balances and all other data preferences. */
    static SharedPreferences dataPrefs(Context input) {
        synchronized (BalanceData.class) {
            return context(input).getSharedPreferences(BalanceData.PREFS_DATA,
                Context.MODE_PRIVATE);
        }
    }

    /**
     * Removes superseded generation directories, keeping the newest superseded one: the manifest
     * fallback can still roll back to it if the fresh selector ever fails to read. Steady state
     * is therefore at most two generations no matter how many restores happen. Best-effort and
     * never load-bearing — an uncertain manifest keeps everything, and failures are logged, never
     * thrown — so publishing cannot fail because cleanup did.
     */
    static void pruneOldGenerations(Context input) {
        synchronized (BalanceData.class) {
            try {
                Context root = rootContext(input);
                Manifest manifest;
                try {
                    manifest = readManifest(root);
                } catch (Exception invalid) {
                    return;
                }
                if (manifest == null) return;
                File[] children = generationsDirectory(root).listFiles();
                if (children == null) return;
                List<File> superseded = new ArrayList<>();
                for (File child : children) {
                    if (!child.isDirectory() || child.getName().equals(manifest.generation))
                        continue;
                    try {
                        parseGeneration(child.getName());
                    } catch (Exception notAGeneration) {
                        continue;
                    }
                    superseded.add(child);
                }
                // Newest first; a same-device publish always creates a newer directory.
                superseded.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 1; i < superseded.size(); i++) {
                    File victim = superseded.get(i);
                    root.deleteSharedPreferences(prefsName(victim.getName()));
                    deleteRecursively(victim);
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "generation prune failed", e);
            }
        }
    }

    /** Test seam: sorted names of retained generation directories. */
    static List<String> generationNamesForTests(Context input) {
        synchronized (BalanceData.class) {
            Context root = rootContext(input);
            File[] children = generationsDirectory(root).listFiles();
            List<String> names = new ArrayList<>();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) names.add(child.getName());
                }
            }
            Collections.sort(names);
            return names;
        }
    }

    /** Test isolation seam; production resets intentionally keep the published generation. */
    static void clearForTests(Context input) {
        synchronized (BalanceData.class) {
            Context root = rootContext(input);
            Manifest manifest = null;
            try { manifest = readManifest(root); } catch (Exception ignored) { }
            if (manifest != null) root.deleteSharedPreferences(prefsName(manifest.generation));
            deleteRecursively(generationsDirectory(root));
            File manifestFile = manifestFile(root);
            if (manifestFile.exists()) manifestFile.delete();
            File backup = new File(manifestFile.getPath() + ".bak");
            if (backup.exists()) backup.delete();
        }
    }

    /** Refreshes the fail-closed database inventory after an explicit store reset. */
    static void refreshMarker(Context input) throws Exception {
        if (!(input instanceof GenerationContext)) return;
        GenerationContext generation = (GenerationContext) input;
        generation.checkUsable();
        String state = generation.stage ? "stage" : "ready";
        writeMarker(generation.directory, marker(state, generation.generation,
            generation.getDatabasePath("unused").getParentFile()));
        syncTree(generation.directory);
    }

    /** Begins a disposable copy of the currently published data. */
    static Stage beginStage(Context input) throws Exception {
        if (input == null) throw new NullPointerException("context");
        synchronized (BalanceData.class) {
            if (input instanceof GenerationContext && ((GenerationContext) input).stage)
                throw new IllegalArgumentException("cannot stage from a staging context");
            Context root = rootContext(input);
            Context active = context(root);
            Stage stage = new Stage(root, active);
            try {
                stage.copy();
                return stage;
            } catch (Exception failure) {
                stage.abortLocked();
                throw failure;
            }
        }
    }

    /**
     * Package-private file seams keep failure-mode tests from depending on an implementation's
     * private directory walk. They do not expose credentials or data values.
     */
    static File manifestFileForTests(Context input) {
        synchronized (BalanceData.class) {
            return manifestFile(rootContext(input));
        }
    }

    static File generationDirectoryForTests(Context input, String generation) {
        synchronized (BalanceData.class) {
            UUID id = parseGeneration(generation);
            return generationDirectory(rootContext(input), id.toString());
        }
    }

    private static Context rootContext(Context input) {
        if (input instanceof GenerationContext) return ((GenerationContext) input).root;
        return applicationContext(input);
    }

    private static Context applicationContext(Context input) {
        Context application = input.getApplicationContext();
        return application == null ? input : application;
    }

    private static File generationsDirectory(Context root) {
        return new File(root.getNoBackupFilesDir(), GENERATIONS_DIRECTORY);
    }

    private static File generationDirectory(Context root, String generation) {
        return new File(generationsDirectory(root), generation);
    }

    private static File manifestFile(Context root) {
        return new File(root.getNoBackupFilesDir(), MANIFEST_NAME);
    }

    private static File markerFile(File generationDirectory) {
        return new File(generationDirectory, MARKER_NAME);
    }

    private static String prefsName(String generation) {
        return PREFS_PREFIX + generation;
    }

    private static Manifest readManifest(Context root) {
        File file = manifestFile(root);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
        try {
            String json;
            AtomicFile atomic = new AtomicFile(file);
            try (InputStream input = atomic.openRead()) {
                json = new String(readBytes(input, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
            }
            boolean versionSeen = false;
            String generation = null;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setLenient(false);
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if ("version".equals(name)) {
                        if (versionSeen || reader.peek() != JsonToken.NUMBER
                                || !"1".equals(reader.nextString()))
                            throw new IOException("invalid generation manifest version");
                        versionSeen = true;
                    } else if ("generation".equals(name)) {
                        if (generation != null || reader.peek() != JsonToken.STRING)
                            throw new IOException("invalid generation manifest selector");
                        generation = reader.nextString();
                    } else throw new IOException("unknown generation manifest field");
                }
                reader.endObject();
                if (!versionSeen || generation == null || reader.peek() != JsonToken.END_DOCUMENT)
                    throw new IOException("incomplete generation manifest");
            }
            return new Manifest(parseGeneration(generation));
        } catch (Exception failure) {
            throw new IllegalStateException("data generation manifest is invalid", failure);
        }
    }

    private static GenerationContext openGeneration(Context root, String generation,
            boolean stage, Stage owner) {
        File directory = generationDirectory(root, generation);
        java.util.Set<String> databases = stage ? new HashSet<>()
            : validatePublishedGeneration(root, directory, generation);
        return new GenerationContext(root, directory, generation, stage, owner, databases);
    }

    private static java.util.Set<String> validatePublishedGeneration(Context root, File directory,
            String generation) {
        if (!directory.isDirectory())
            throw new IllegalStateException("published data generation is missing");
        File databases = new File(directory, "databases");
        File files = new File(directory, "files");
        File noBackup = new File(directory, "no_backup");
        if (!databases.isDirectory() || !files.isDirectory() || !noBackup.isDirectory())
            throw new IllegalStateException("published data generation is incomplete");
        try {
            String marker = new String(readBytes(new FileInputStream(markerFile(directory)),
                MAX_MARKER_BYTES), StandardCharsets.UTF_8);
            java.util.Set<String> expectedDatabases = validateMarker(marker, "ready", generation,
                databases);
            SharedPreferences prefs = root.getSharedPreferences(prefsName(generation),
                Context.MODE_PRIVATE);
            File preferencesFile = preferenceFile(root, prefsName(generation));
            if (!preferencesFile.isFile()) throw new Exception("generation preference file is missing");
            if (!generation.equals(prefs.getString(PREFS_GENERATION_KEY, null)))
                throw new Exception("generation preferences are missing");
            return expectedDatabases;
        } catch (Exception failure) {
            throw new IllegalStateException("published data generation is incomplete", failure);
        }
    }

    private static String marker(String state, String generation, File databases) {
        StringBuilder names = new StringBuilder();
        for (String name : DATABASES) {
            if (!new File(databases, name).isFile()) continue;
            if (names.length() != 0) names.append(',');
            names.append(name);
        }
        return "version=1\n" + "generation=" + generation + "\n" + "state=" + state
            + "\ndatabases=" + names + "\n";
    }

    private static java.util.Set<String> validateMarker(String value, String state, String generation,
            File databases) throws Exception {
        String[] lines = value.split("\\n", -1);
        if (lines.length != 5 || !"version=1".equals(lines[0])
                || !("generation=" + generation).equals(lines[1])
                || !("state=" + state).equals(lines[2])
                || !lines[3].startsWith("databases=") || !lines[4].isEmpty())
            throw new Exception("invalid generation marker");
        String list = lines[3].substring("databases=".length());
        java.util.Set<String> seen = new HashSet<>();
        if (list.isEmpty()) return seen;
        String[] names = list.split(",", -1);
        java.util.Set<String> allowed = new HashSet<>(Arrays.asList(DATABASES));
        for (String name : names) {
            File database = new File(databases, name);
            if (!allowed.contains(name) || !seen.add(name)
                    || !database.isFile() || database.length() == 0)
                throw new Exception("published generation database is missing");
        }
        return seen;
    }

    private static UUID parseGeneration(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new Exception("non-canonical generation id");
            return id;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid data generation id", failure);
        }
    }

    private static final class Manifest {
        final String generation;

        Manifest(UUID generation) {
            this.generation = generation.toString();
        }
    }

    /** A staged generation whose context is deliberately pinned to its private directory. */
    static final class Stage implements AutoCloseable {
        private final Context root;
        private final Context active;
        private final String generation;
        private final File directory;
        private final GenerationContext context;
        private boolean published;
        private boolean publicationAttempted;
        private boolean closed;
        private PublishHook publishHook;

        private Stage(Context root, Context active) throws IOException {
            this.root = root;
            this.active = active;
            generation = UUID.randomUUID().toString();
            directory = generationDirectory(root, generation);
            if (!generationsDirectory(root).exists() && !generationsDirectory(root).mkdirs())
                throw new IOException("generation directory could not be created");
            if (!directory.mkdirs()) throw new IOException("stage directory could not be created");
            context = openGeneration(root, generation, true, this);
            try {
                mkdirs(context.getDatabasePath("unused").getParentFile());
                mkdirs(context.getFilesDir());
                mkdirs(context.getNoBackupFilesDir());
            } catch (RuntimeException failure) {
                deleteRecursively(directory);
                throw failure;
            }
        }

        Context context() {
            synchronized (BalanceData.class) {
                checkOpen();
                return context;
            }
        }

        /** Per-stage fault injection; no static hook can survive an app data clear. */
        void setPublishHookForTests(PublishHook hook) {
            synchronized (BalanceData.class) {
                checkMutable();
                publishHook = hook;
            }
        }

        /** Publishes the complete stage with one durable selector replacement. */
        void publish() throws Exception {
            synchronized (BalanceData.class) {
                checkMutable();
                // commit() waits for any preceding apply() on this namespace and creates its
                // preference file before the selector can point at this generation.
                SharedPreferences prefs = context.getSharedPreferences(BalanceData.PREFS_DATA,
                    Context.MODE_PRIVATE);
                if (!prefs.edit().putString(PREFS_GENERATION_KEY, generation).commit())
                    throw new IOException("generation preferences could not be committed");
                syncPreferenceFile(root, prefsName(generation));
                checkpointAndSyncDatabases(context);
                writeMarker(directory, marker("ready", generation,
                    context.getDatabasePath("unused").getParentFile()));
                syncTree(directory);
                syncDirectory(generationsDirectory(root));
                syncDirectory(root.getNoBackupFilesDir());
                if (publishHook != null) publishHook.at(PublishPoint.BEFORE_MANIFEST);
                publicationAttempted = true;
                publishManifest(root, generation, publishHook);
                published = true;
            }
        }

        /** Explicitly discards a stage. It is safe to call more than once. */
        void abort() {
            synchronized (BalanceData.class) {
                abortLocked();
            }
        }

        private void copy() throws Exception {
            copyDatabases();
            copyPreferences();
            copyLegacyFiles();
            copyPresenceMarker();
            writeMarker(directory, marker("stage", generation,
                context.getDatabasePath("unused").getParentFile()));
            syncTree(directory);
            syncPreferenceFile(root, prefsName(generation));
            syncDirectory(generationsDirectory(root));
            syncDirectory(root.getNoBackupFilesDir());
        }

        private void copyDatabases() throws Exception {
            File targetDirectory = context.getDatabasePath("unused").getParentFile();
            for (String name : DATABASES) {
                File source = active.getDatabasePath(name);
                File target = new File(targetDirectory, name);
                deleteDatabaseFiles(target);
                if (!source.exists()) {
                    for (File sidecar : databaseFiles(source)) {
                        if (sidecar.exists())
                            throw new IOException("database is missing its main file");
                    }
                    continue;
                }
                if (!source.isFile()) throw new IOException("invalid source database");
                checkpoint(source);
                copyFile(source, target);
                syncFile(target);
            }
            syncDirectory(targetDirectory);
        }

        private void copyPreferences() throws Exception {
            SharedPreferences source = active.getSharedPreferences(BalanceData.PREFS_DATA,
                Context.MODE_PRIVATE);
            // Flush pending apply() writes in the active namespace before taking its snapshot.
            if (!source.edit().commit()) throw new IOException("local preferences could not be committed");
            SharedPreferences destination = context.getSharedPreferences(BalanceData.PREFS_DATA,
                Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = destination.edit().clear();
            for (Map.Entry<String, ?> entry : source.getAll().entrySet())
                put(editor, entry.getKey(), entry.getValue());
            editor.putString(PREFS_GENERATION_KEY, generation);
            if (!editor.commit()) throw new IOException("generation preferences could not be copied");
        }

        private void copyLegacyFiles() throws Exception {
            File sourceDirectory = active.getFilesDir();
            File targetDirectory = context.getFilesDir();
            File[] files = sourceDirectory.listFiles();
            if (files == null) throw new IOException("legacy files directory could not be read");
            for (File source : files) {
                if (!source.isFile() || !source.getName().startsWith(LEGACY_PAGE_PREFIX)) continue;
                copyFile(source, new File(targetDirectory, source.getName()));
            }
        }

        private void copyPresenceMarker() throws Exception {
            File source = new File(active.getNoBackupFilesDir(), PRESENCE_FILE);
            if (source.exists()) copyFile(source, new File(context.getNoBackupFilesDir(),
                PRESENCE_FILE));
        }

        private void abortLocked() {
            if (closed) return;
            closed = true;
            // Once a selector write has been attempted its outcome can be ambiguous on I/O
            // failure. Retain the directory/namespace rather than delete a possible active target.
            if (!published && !publicationAttempted) {
                root.deleteSharedPreferences(prefsName(generation));
                deleteRecursively(directory);
            }
        }

        private void checkOpen() {
            if (closed) throw new IllegalStateException("data generation stage is closed");
        }

        private void checkMutable() {
            checkOpen();
            if (published || publicationAttempted)
                throw new IllegalStateException("data generation stage is already published");
        }

        @Override public void close() {
            synchronized (BalanceData.class) {
                abortLocked();
            }
        }
    }

    private static final class GenerationContext extends ContextWrapper {
        final Context root;
        private final File directory;
        private final String generation;
        final boolean stage;
        private final Stage owner;
        private final java.util.Set<String> expectedDatabases;

        GenerationContext(Context root, File directory, String generation, boolean stage,
                Stage owner, java.util.Set<String> expectedDatabases) {
            super(root);
            this.root = root;
            this.directory = directory;
            this.generation = generation;
            this.stage = stage;
            this.owner = owner;
            this.expectedDatabases = expectedDatabases;
        }

        void checkUsable() {
            if (owner != null) owner.checkOpen();
        }

        @Override public Context getApplicationContext() {
            // Keeping the wrapper here is essential for lazy store readers and for nested calls:
            // an operation started with a stage must never jump back to the selector.
            checkUsable();
            return this;
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            checkUsable();
            return super.getBaseContext().getSharedPreferences(
                BalanceData.PREFS_DATA.equals(name) ? prefsName(generation) : name, mode);
        }

        @Override public File getDatabasePath(String name) {
            checkUsable();
            if (name == null || name.isEmpty() || !new File(name).getName().equals(name)
                    || ".".equals(name) || "..".equals(name))
                throw new IllegalArgumentException("invalid generation database name");
            return new File(new File(directory, "databases"), name);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory) {
            return openOrCreateDatabase(name, mode, factory, corruptDatabaseHandler());
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            checkUsable();
            File file = getDatabasePath(name);
            if (!stage && expectedDatabases.contains(name) && !file.isFile())
                throw new SQLiteException("published generation database is missing");
            mkdirs(file.getParentFile());
            int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
            if ((mode & Context.MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0)
                flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
            if ((mode & Context.MODE_NO_LOCALIZED_COLLATORS) != 0)
                flags |= SQLiteDatabase.NO_LOCALIZED_COLLATORS;
            return SQLiteDatabase.openDatabase(file.getPath(), factory,
                flags, corruptDatabaseHandler());
        }

        @Override public boolean deleteDatabase(String name) {
            checkUsable();
            File file = getDatabasePath(name);
            boolean deleted = false;
            for (File candidate : databaseFiles(file)) {
                if (candidate.exists()) {
                    deleted = true;
                    if (!candidate.delete() && candidate.exists()) return false;
                }
            }
            return deleted || !file.exists();
        }

        @Override public File getFilesDir() {
            checkUsable();
            return new File(directory, "files");
        }

        @Override public File getNoBackupFilesDir() {
            checkUsable();
            return new File(directory, "no_backup");
        }

        private DatabaseErrorHandler corruptDatabaseHandler() {
            return db -> { throw new SQLiteException("staged database is corrupt; preserved"); };
        }
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof java.util.Set) {
            @SuppressWarnings("unchecked") java.util.Set<String> strings =
                (java.util.Set<String>) value;
            editor.putStringSet(key, new HashSet<>(strings));
        } else throw new IllegalStateException("unsupported data preference type");
    }

    private static void checkpointAndSyncDatabases(Context generation) throws Exception {
        File databaseDirectory = generation.getDatabasePath("unused").getParentFile();
        for (String name : DATABASES) {
            File file = new File(databaseDirectory, name);
            if (!file.exists()) continue;
            checkpoint(file);
        }
        syncTree(databaseDirectory);
    }

    private static void checkpoint(File file) throws Exception {
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE, db -> {
                    throw new SQLiteException("database is corrupt; preserved");
                });
            try (android.database.Cursor cursor = database.rawQuery(
                    "PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                if (cursor.moveToFirst() && cursor.getInt(0) != 0)
                    throw new IOException("database checkpoint is busy");
            }
        } finally {
            if (database != null) database.close();
        }
        // Never copy SHM/journal/WAL sidecars. A non-empty WAL after the final close means a
        // checkpoint/connection did not release all state, so copying the main file is unsafe.
        File wal = new File(file.getPath() + "-wal");
        if (wal.exists() && wal.length() != 0)
            throw new IOException("database WAL was not completely checkpointed");
        File journal = new File(file.getPath() + "-journal");
        if (journal.exists() && journal.length() != 0)
            throw new IOException("database journal was not closed");
        syncFile(file);
        for (File sidecar : databaseFiles(file)) {
            if (sidecar.equals(file) || !sidecar.exists()) continue;
            if (!sidecar.delete() && sidecar.exists())
                throw new IOException("database sidecar could not be removed");
        }
        syncDirectory(file.getParentFile());
    }

    private static File[] databaseFiles(File database) {
        return new File[] {
            database,
            new File(database.getPath() + "-wal"),
            new File(database.getPath() + "-shm"),
            new File(database.getPath() + "-journal")
        };
    }

    private static void publishManifest(Context root, String generation, PublishHook hook)
            throws Exception {
        File parent = root.getNoBackupFilesDir();
        mkdirs(parent);
        AtomicFile atomic = new AtomicFile(manifestFile(root));
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            byte[] bytes = new JSONObject().put("version", 1).put("generation", generation)
                .toString().getBytes(StandardCharsets.UTF_8);
            output.write(bytes);
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
        } catch (Exception failure) {
            if (output != null) atomic.failWrite(output);
            throw failure;
        }
        Manifest selected = readManifest(root);
        if (selected == null || !generation.equals(selected.generation))
            throw new IOException("generation selector was not published");
                if (hook != null) hook.at(PublishPoint.AFTER_MANIFEST);
                syncFile(manifestFile(root));
                syncDirectory(parent);
                pruneOldGenerations(root);
    }

    private static void writeMarker(File directory, String value) throws Exception {
        File marker = markerFile(directory);
        AtomicFile atomic = new AtomicFile(marker);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
        } catch (Exception failure) {
            if (output != null) atomic.failWrite(output);
            throw failure;
        }
        syncFile(marker);
        syncDirectory(directory);
    }

    private static void syncPreferenceFile(Context root, String name) throws Exception {
        File sharedPreferences = preferenceFile(root, name);
        if (!sharedPreferences.isFile()) throw new IOException("generation preferences are missing");
        syncFile(sharedPreferences);
        syncDirectory(sharedPreferences.getParentFile());
    }

    private static File preferenceFile(Context root, String name) {
        return new File(new File(root.getApplicationInfo().dataDir, "shared_prefs"), name + ".xml");
    }

    private static byte[] readBytes(InputStream input, int max) throws IOException {
        try (InputStream source = new BufferedInputStream(input);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count > max - total) throw new IOException("file is too large");
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        mkdirs(target.getParentFile());
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static void syncFile(File file) throws IOException {
        if (!file.isFile()) throw new IOException("file to sync is missing");
        try (FileInputStream input = new FileInputStream(file)) {
            input.getFD().sync();
        }
    }

    private static void syncTree(File directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("directory to sync is missing");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("directory could not be read");
        for (File file : files) {
            if (file.isDirectory()) syncTree(file);
            else syncFile(file);
        }
        syncDirectory(directory);
    }

    private static void syncDirectory(File directory) throws IOException {
        if (directory == null || !directory.isDirectory())
            throw new IOException("directory to sync is missing");
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
            Os.fsync(descriptor);
        } catch (ErrnoException failure) {
            throw new IOException("directory sync failed", failure);
        } finally {
            if (descriptor != null) {
                try { Os.close(descriptor); }
                catch (ErrnoException failure) { throw new IOException("directory close failed", failure); }
            }
        }
    }

    private static void mkdirs(File directory) {
        if (directory == null) throw new IllegalStateException("missing data directory");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IllegalStateException("data directory could not be created");
    }

    private static void deleteDatabaseFiles(File database) {
        for (File file : databaseFiles(database)) deleteRecursively(file);
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
