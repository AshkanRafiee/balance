package com.ashkanrafiee.balance;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Map;

import javax.crypto.SecretKey;

/**
 * Opens the application's canonical financial generation store.
 *
 * <p>The directory is deliberately dedicated and lives below no-backup storage. The provider does
 * not cache a store or preference snapshot: each open reloads the authenticated migration journal,
 * so callers can recover from a publication whose result was uncertain. Callers must hold the
 * BalanceData monitor while opening and while using the returned store.</p>
 */
final class FinancialStoreProvider {
    private static final String DIRECTORY = "financial-generations";
    private final File parent;
    private final LegacyGenerationMigration.Source source;
    private final KeyAccess keys;
    private final EncryptedGenerationStore.Limits limits;

    interface KeyAccess {
        /** Returns null only when the configured key alias is conclusively absent. */
        SecretKey existing() throws IOException;
        /** Must reuse an existing alias, never overwrite an unavailable existing key. */
        SecretKey create() throws IOException;
    }

    private FinancialStoreProvider(File parent, KeyAccess keys,
            LegacyGenerationMigration.Source source,
            EncryptedGenerationStore.Limits limits) throws IOException {
        if (parent == null || keys == null || source == null || limits == null)
            throw new IllegalArgumentException("ARGUMENT");
        this.parent = parent.getAbsoluteFile();
        this.keys = keys;
        this.source = source;
        this.limits = limits;
    }

    static EncryptedGenerationStore open(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("ARGUMENT");
        synchronized (BalanceData.class) {
            File noBackup = context.getNoBackupFilesDir();
            if (noBackup == null) throw failure("NO_BACKUP_STORAGE");
            // Android may return a platform alias for the app storage root. Trust that root,
            // but never canonicalize away a symlink at our owned child directory.
            File parent = new File(noBackup.getCanonicalFile(), DIRECTORY);
            KeyAccess keys = new KeyAccess() {
                @Override public SecretKey existing() throws IOException {
                    try {
                        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                        store.load(null);
                        if (!store.containsAlias(BalanceData.KEY_ALIAS)) return null;
                        java.security.Key key = store.getKey(BalanceData.KEY_ALIAS, null);
                        if (!(key instanceof SecretKey)) throw failure("KEY");
                        return (SecretKey) key;
                    } catch (IOException failure) {
                        throw failure;
                    } catch (Exception unavailable) {
                        throw failure("KEY");
                    }
                }
                @Override public SecretKey create() throws IOException {
                    return BalanceData.storageKey();
                }
            };
            return create(context, parent, keys,
                    new LegacyFinancialSource(context.getSharedPreferences(
                            BalanceData.PREFS_DATA, Context.MODE_PRIVATE),
                            context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE)),
                    EncryptedGenerationStore.Limits.defaults()).open();
        }
    }

    /** Test/package entry point; production uses {@link #open(Context)}. */
    static FinancialStoreProvider create(Context context, File parent, KeyAccess keys,
            LegacyGenerationMigration.Source source,
            EncryptedGenerationStore.Limits limits) throws IOException {
        if (context == null) throw new IllegalArgumentException("ARGUMENT");
        return new FinancialStoreProvider(parent, keys, source, limits);
    }

    /** The directory holding the store and its migration journal — everything {@link #open} reads.
     *  Only for tests that need to put the device back to a fresh-install state. */
    static File directory(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("ARGUMENT");
        synchronized (BalanceData.class) {
            File noBackup = context.getNoBackupFilesDir();
            if (noBackup == null) throw failure("NO_BACKUP_STORAGE");
            return new File(noBackup.getCanonicalFile(), DIRECTORY);
        }
    }

    EncryptedGenerationStore open() throws IOException {
        synchronized (BalanceData.class) {
            prepareDirectory();
            String[] entries = parent.list();
            if (entries == null) throw failure("IO");
            boolean pristine = entries.length == 0;
            // The production source resolves legacy keys fetch-only. Encrypted input with a
            // missing/unavailable key therefore fails here, before creation is even considered.
            Map<String, byte[]> snapshot = pristine ? source.readValidatedSnapshot() : null;
            SecretKey key = keys.existing();
            if (key == null) {
                if (!pristine) throw failure("KEY");
                key = keys.create();
            }
            if (key == null) throw failure("KEY");
            LegacyGenerationMigration migration = new LegacyGenerationMigration(
                    parent, key, limits);
            return migration.open(pristine ? () -> snapshot : source);
        }
    }

    private void prepareDirectory() throws IOException {
        if (!parent.equals(parent.getCanonicalFile())) throw failure("PATH");
        File container = parent.getParentFile();
        if (container == null || !container.isDirectory()) throw failure("PATH");
        try {
            // lstat also detects dangling symlinks, which File.exists() cannot do.
            try {
                if (!OsConstants.S_ISDIR(Os.lstat(parent.getPath()).st_mode))
                    throw failure("PATH");
            } catch (android.system.ErrnoException missing) {
                if (missing.errno != OsConstants.ENOENT) throw failure("IO");
                if (!parent.mkdir()) throw failure("IO");
            }
            // Also retry this sync on later opens after an earlier uncertain mkdir/sync.
            FileDescriptor fd = Os.open(container.getPath(), OsConstants.O_RDONLY, 0);
            try { Os.fsync(fd); } finally { Os.close(fd); }
        } catch (android.system.ErrnoException ignored) { throw failure("IO"); }
    }

    private static IOException failure(String code) { return new IOException(code); }
}
