package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FinancialStoreProviderTest {
    @Test public void firstOpenAdoptsSourceAndLaterOpenNeverReimports() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent = directory(context);
        assertTrue(parent.mkdir());
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("transactions", bytes("history"));
        expected.put("transaction_notes", bytes("notes"));
        int[] reads = { 0 };
        try {
            FinancialStoreProvider provider = FinancialStoreProvider.create(context, parent, new Keys(key),
                    () -> { reads[0]++; return expected; },
                    EncryptedGenerationStore.Limits.defaults());
            EncryptedGenerationStore first = provider.open();
            assertEquals(1, reads[0]);
            same(expected, first.getSnapshot().components());
            EncryptedGenerationStore second = provider.open();
            assertEquals(1, reads[0]);
            same(expected, second.getSnapshot().components());
            assertTrue(new File(parent, "migration.state").isFile());
            assertTrue(new File(parent, "generations").isDirectory());
        } finally { delete(parent); }
    }

    @Test public void invalidSourceDoesNotCreateAnAdoptedDestination() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent = directory(context);
        assertTrue(parent.mkdir());
        Keys keys = new Keys(null);
        try {
            FinancialStoreProvider provider = FinancialStoreProvider.create(context, parent,
                    keys, () -> { throw new IOException("INVALID_SOURCE"); },
                    EncryptedGenerationStore.Limits.defaults());
            try { provider.open(); fail("invalid source adopted"); }
            catch (IOException expected) { assertEquals("INVALID_SOURCE", expected.getMessage()); }
            assertFalse(new File(parent, "migration.state").exists());
            assertFalse(new File(parent, "generations").exists());
            assertEquals(0, keys.creates);
        } finally { delete(parent); }
    }

    @Test public void pristinePlaintextIsValidatedBeforeKeyCreation() throws Exception {
        legacyWithoutKey(false);
    }

    @Test public void pristineValidPlaintextWithUnavailableKeyDoesNotCreate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent = directory(context);
        Keys keys = new Keys(null);
        keys.unavailable = true;
        try {
            FinancialStoreProvider provider = FinancialStoreProvider.create(context, parent, keys,
                    () -> {
                        Map<String, byte[]> snapshot = new LinkedHashMap<>();
                        snapshot.put("transaction_notes", bytes("{}"));
                        return snapshot;
                    }, EncryptedGenerationStore.Limits.defaults());
            try { provider.open(); fail("unavailable key accepted"); }
            catch (IOException expected) { assertEquals("KEY", expected.getMessage()); }
            assertEquals(0, keys.creates);
        } finally { delete(parent); }
    }

    @Test public void encryptedLegacyWithoutKeyNeverCreatesReplacement() throws Exception {
        legacyWithoutKey(true);
    }

    private static void legacyWithoutKey(boolean encrypted) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent = directory(context);
        String prefsName = "financial-provider-source-" + UUID.randomUUID();
        SharedPreferences data = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        SharedPreferences settings = context.getSharedPreferences(prefsName + "-settings",
                Context.MODE_PRIVATE);
        Keys keys = new Keys(null);
        int[] reads = { 0 };
        try {
            assertTrue(data.edit().putString("transaction_notes", encrypted
                    ? Base64.encodeToString(new byte[28], Base64.NO_WRAP) : "{}").commit());
            LegacyFinancialSource source = new LegacyFinancialSource(data, settings, keys::existing,
                    LegacyFinancialSource.Limits.defaults());
            FinancialStoreProvider provider = FinancialStoreProvider.create(context, parent, keys,
                    () -> {
                        assertEquals(0, keys.creates);
                        reads[0]++;
                        return source.readValidatedSnapshot();
                    }, EncryptedGenerationStore.Limits.defaults());
            if (encrypted) {
                try { provider.open(); fail("missing legacy key accepted"); }
                catch (IOException expected) { assertEquals("INVALID_SOURCE", expected.getMessage()); }
                assertEquals(0, keys.creates);
                assertArrayEquals(new String[0], parent.list());
            } else {
                assertArrayEquals(bytes("{}"), provider.open().getSnapshot().components()
                        .get("transaction_notes"));
                assertEquals(1, keys.creates);
                provider.open();
                assertEquals(1, keys.creates);
            }
            assertEquals(1, reads[0]);
        } finally {
            assertTrue(data.edit().clear().commit());
            assertTrue(settings.edit().clear().commit());
            delete(parent);
        }
    }

    @Test public void adoptedStoreWithLostKeyNeverCreatesOrReadsSource() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent = directory(context);
        Keys keys = new Keys(null);
        int[] reads = { 0 };
        try {
            FinancialStoreProvider provider = FinancialStoreProvider.create(context, parent, keys,
                    () -> { reads[0]++; return new LinkedHashMap<>(); },
                    EncryptedGenerationStore.Limits.defaults());
            provider.open();
            keys.key = null;
            keys.unavailable = false;
            try { provider.open(); fail("lost adopted key replaced"); }
            catch (IOException expected) { assertEquals("KEY", expected.getMessage()); }
            assertEquals(1, keys.creates);
            assertEquals(1, reads[0]);
            assertTrue(new File(parent, "migration.state").isFile());
        } finally { delete(parent); }
    }

    @Test public void symlinkDirectoryAndDanglingSymlinkAreRejected() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = directory(context);
        File link = directory(context);
        Keys keys = new Keys(null);
        assertTrue(target.mkdir());
        try {
            Os.symlink(target.getPath(), link.getPath());
            assertPathRejected(context, link, keys);
            assertTrue(target.delete());
            assertPathRejected(context, link, keys);
            assertEquals(0, keys.creates);
        } finally {
            link.delete();
            delete(target);
        }
    }

    @Test public void missingAncestorIsNotCreated() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ancestor = directory(context);
        Keys keys = new Keys(null);
        assertPathRejected(context, new File(ancestor, "child"), keys);
        assertFalse(ancestor.exists());
        assertEquals(0, keys.creates);
    }

    private static void assertPathRejected(Context context, File parent, Keys keys) throws Exception {
        FinancialStoreProvider provider = FinancialStoreProvider.create(context, parent, keys,
                () -> { throw new AssertionError("source read through invalid path"); },
                EncryptedGenerationStore.Limits.defaults());
        try { provider.open(); fail("invalid path accepted"); }
        catch (IOException expected) { assertEquals("PATH", expected.getMessage()); }
    }

    private static File directory(Context context) throws IOException {
        return new File(context.getCacheDir().getCanonicalFile(),
                "financial-provider-" + UUID.randomUUID());
    }

    private static final class Keys implements FinancialStoreProvider.KeyAccess {
        SecretKey key;
        int creates;
        boolean unavailable;

        Keys(SecretKey key) { this.key = key; }

        @Override public SecretKey existing() throws IOException {
            if (unavailable) throw new IOException("KEY");
            return key;
        }

        @Override public SecretKey create() throws IOException {
            creates++;
            if (key != null) return key;
            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                return key = generator.generateKey();
            } catch (Exception ignored) { throw new IOException("KEY"); }
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void same(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) assertArrayEquals(expected.get(name), actual.get(name));
    }

    private static void delete(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }
}
