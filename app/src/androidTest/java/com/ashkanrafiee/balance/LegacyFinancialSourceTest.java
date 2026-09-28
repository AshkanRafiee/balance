package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.*;

/** Only UUID-prefixed synthetic preferences/cache fixtures; never opens live preferences/keystore. */
@RunWith(AndroidJUnit4.class)
public class LegacyFinancialSourceTest {
    private Context context;
    private SharedPreferences data, settings;
    private String prefix;
    private File sandbox;
    private SecretKey key;
    private int resolutions;
    private static final String[] DATA = { "balances", "transactions", "transaction_notes",
            "transaction_reasons", "transaction_channels", "recent_movements", "history_last_balance" };
    private static final String[] SETTINGS = { "scanned_through", "rules_version", "history_through",
            "history_rules_version", "history_schema", "excluded_banks" };

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "legacy-financial-source-test-" + UUID.randomUUID();
        data = context.getSharedPreferences(prefix + "-data", Context.MODE_PRIVATE);
        settings = context.getSharedPreferences(prefix + "-settings", Context.MODE_PRIVATE);
        sandbox = new File(context.getCacheDir(), prefix);
        assertTrue(sandbox.mkdir());
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @After public void tearDown() {
        if (data != null) assertTrue(data.edit().clear().commit());
        if (settings != null) assertTrue(settings.edit().clear().commit());
        if (context != null && prefix != null) {
            context.deleteSharedPreferences(prefix + "-data");
            context.deleteSharedPreferences(prefix + "-settings");
        }
        delete(sandbox);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }

    // Enforces the read allowlist as well as snapshot equality: even reading a privacy key fails.
    private SharedPreferences guarded(SharedPreferences prefs, String[] allowed) {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] { SharedPreferences.class }, (proxy, method, args) -> {
                    assertTrue(Arrays.asList("contains", "getString", "getLong", "getInt")
                            .contains(method.getName()));
                    assertTrue(names.contains((String) args[0]));
                    try { return method.invoke(prefs, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }

    private LegacyFinancialSource source() {
        return source(() -> { resolutions++; return key; }, LegacyFinancialSource.Limits.defaults());
    }

    private LegacyFinancialSource source(LegacyFinancialSource.ExistingKey provider,
            LegacyFinancialSource.Limits limits) {
        return new LegacyFinancialSource(guarded(data, DATA), guarded(settings, SETTINGS), provider, limits);
    }

    private Map<String, byte[]> read(LegacyFinancialSource source) throws IOException {
        synchronized (BalanceData.class) { return source.readValidatedSnapshot(); }
    }

    private String encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plain);
        byte[] sealed = new byte[12 + encrypted.length];
        assertEquals(12, cipher.getIV().length);
        System.arraycopy(cipher.getIV(), 0, sealed, 0, 12);
        System.arraycopy(encrypted, 0, sealed, 12, encrypted.length);
        return Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private Map<String, String> json() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("balances", "{\"بانک|۰۰۱\": {\"amount\":9223372036854775807,"
                + "\"date\":-9223372036854775808,\"sender\":\"بانک\",\"account\":\"۰۰۱\","
                + "\"future\":{\"flag\":true,\"values\":[null,1.25,1e100]}}}");
        values.put("transactions", "{\"transactions\":[{\"bank\":\"بانک\",\"date\":9007199254740993,"
                + "\"amount\":-9223372036854775808,\"account\":\"۰۰۱\",\"bal\":9223372036854775807,"
                + "\"sig\":\"  original|sig  \",\"content\":\"digest\"}],\"future\":false}");
        values.put("transaction_notes", "{\"c:digest\":\"  یادداشت 🌍\\n\\tkeep spaces  \"}\n");
        values.put("transaction_reasons", "{\"old|sig\":\"خرید\"}");
        values.put("transaction_channels", "{\"c:digest\":\"ATM\"}");
        values.put("recent_movements", "{\"بانک|۰۰۱\":[{\"d\":9007199254740993,\"a\":-12,"
                + "\"b\":9223372036854775807,\"s\":\"original sig\"},"
                + "{\"d\":0,\"a\":0,\"b\":0,\"s\":null}]}");
        values.put("history_last_balance", "{\"بانک|۰۰۱\":9223372036854775807,\"old\":-1}");
        return values;
    }

    private Map<String, byte[]> seed(int mode) throws Exception {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        SharedPreferences.Editor editor = data.edit().clear();
        int index = 0;
        for (Map.Entry<String, String> entry : json().entrySet()) {
            byte[] plain = bytes(entry.getValue());
            boolean encrypted = mode == 1 || mode == 2 && index++ % 2 == 0;
            editor.putString(entry.getKey(), encrypted ? encrypt(plain) : entry.getValue());
            expected.put(entry.getKey(), plain);
        }
        // Deliberately wrong-store financial keys and nonfinancial, wrong-type values are ignored.
        editor.putBoolean("excluded_banks", true).putString("lock_pin", "synthetic-only")
                .putInt("privacy_mode", 7);
        assertTrue(editor.commit());
        assertTrue(settings.edit().clear().putLong("scanned_through", Long.MAX_VALUE)
                .putInt("rules_version", Integer.MAX_VALUE).putLong("history_through", Long.MIN_VALUE)
                .putInt("history_rules_version", -1).putInt("history_schema", 0)
                .putString("excluded_banks", "[\"بانک|۰۰۱\", \"old\"]\n")
                .putBoolean("balances", true).putString("lock_pin", "synthetic-only")
                .putBoolean("hide_balances", true).commit());
        expected.put("scanned_through", bytes(Long.toString(Long.MAX_VALUE)));
        expected.put("rules_version", bytes(Integer.toString(Integer.MAX_VALUE)));
        expected.put("history_through", bytes(Long.toString(Long.MIN_VALUE)));
        expected.put("history_rules_version", bytes("-1"));
        expected.put("history_schema", bytes("0"));
        expected.put("excluded_banks", bytes("[\"بانک|۰۰۱\", \"old\"]\n"));
        return expected;
    }

    private static void same(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) assertArrayEquals(name, expected.get(name), actual.get(name));
    }

    @Test public void fullThirteenPlainEncryptedAndMixedPreserveBytesAndPreferences() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Map<String, byte[]> expected = seed(mode);
            Map<String, ?> beforeData = data.getAll(), beforeSettings = settings.getAll();
            resolutions = 0;
            same(expected, read(source()));
            assertEquals(mode == 0 ? 0 : 1, resolutions);
            assertEquals(beforeData, data.getAll());
            assertEquals(beforeSettings, settings.getAll());
        }
    }

    @Test public void emptyAndHistoricalOptionalFieldsNeedNoKey() throws Exception {
        LegacyFinancialSource source = source(() -> { throw new AssertionError("Key lookup"); },
                LegacyFinancialSource.Limits.defaults());
        assertTrue(read(source).isEmpty());
        assertTrue(data.edit().putString("balances", "{\"old\":{\"amount\":-1,\"date\":0,\"sender\":\"s\"}}")
                .putString("transactions", "{\"transactions\":[{\"bank\":\"old\",\"amount\":-1,\"date\":0},"
                        + "{\"bank\":\"old\",\"amount\":0,\"date\":0,\"account\":null,\"bal\":null,"
                        + "\"sig\":null,\"content\":null}]}")
                .putString("recent_movements", "{\"old\":[{\"d\":0,\"a\":-1,\"b\":0}]}")
                .putString("transaction_notes", "{}").commit());
        assertEquals(4, read(source).size());
    }

    private void rejected(LegacyFinancialSource source) throws Exception {
        Map<String, ?> beforeData = data.getAll(), beforeSettings = settings.getAll();
        try { read(source); fail("Invalid source accepted"); }
        catch (IOException expected) { assertEquals("INVALID_SOURCE", expected.getMessage());
            assertNull(expected.getCause()); }
        assertEquals(beforeData, data.getAll());
        assertEquals(beforeSettings, settings.getAll());
    }

    @Test public void everyComponentFailsClosedOnWrongPreferenceType() throws Exception {
        for (String name : DATA) {
            seed(0);
            assertTrue(data.edit().putLong(name, 1).commit());
            rejected(source());
        }
        for (String name : SETTINGS) {
            seed(0);
            assertTrue(settings.edit().putBoolean(name, true).commit());
            rejected(source());
        }
        seed(0);
        assertTrue(settings.edit().putLong("rules_version", 1).commit());
        rejected(source());
        seed(0);
        assertTrue(settings.edit().putInt("scanned_through", 1).commit());
        rejected(source());
    }

    @Test public void rejectsMalformedJsonDuplicatesCoercionsAndInvalidShapes() throws Exception {
        String[][] cases = {
                {"balances", "{\"b\":{\"amount\":1,\"date\":0}}"},
                {"balances", "{\"b\":{\"amount\":1,\"date\":0,\"sender\":7}}"},
                {"transactions", "{}"}, {"transactions", "{\"transactions\":{}}"},
                {"transactions", "{\"transactions\":[null]}"},
                {"transactions", "{\"transactions\":[{\"bank\":\"b\",\"date\":0,\"amount\":1,\"bal\":\"2\"}]}"},
                {"transactions", "{\"transactions\":[{\"bank\":\"b\",\"date\":0,\"amount\":1,\"sig\":false}]}"},
                {"transaction_notes", "{\"n\":null}"}, {"transaction_reasons", "{\"n\":5}"},
                {"transaction_channels", "{\"n\":[]}"},
                {"recent_movements", "{\"b\":null}"},
                {"recent_movements", "{\"b\":[{\"d\":0,\"a\":1}]}"},
                {"recent_movements", "{\"b\":[{\"d\":0,\"a\":1,\"b\":2,\"s\":3}]}"},
                {"history_last_balance", "{\"b\":9223372036854775808}"},
                {"history_last_balance", "{\"b\":-9223372036854775809}"},
                {"history_last_balance", "{\"b\":9007199254740993.0}"},
                {"history_last_balance", "{\"b\":1e0}"},
                {"history_last_balance", "{\"b\":\"1\"}"},
                {"history_last_balance", "{\"b\":01}"},
                {"history_last_balance", "{\"b\":NaN}"},
                {"transaction_notes", "{\"x\":\"a\",\"\\u0078\":\"b\"}"},
                {"transaction_notes", "{\"x\":\"line\nfeed\"}"},
                {"transaction_notes", "{\"x\":\"\\'\"}"},
                {"transaction_notes", "{\"x\":\"\\uD800\"}"},
                {"transaction_notes", "{\"x\":\"\uD800\"}"},
                {"transaction_notes", "{\"x\":\"unterminated}"},
                {"transaction_notes", "{} trailing"}, {"transaction_notes", "{}{}"},
                {"transaction_notes", "{\"x\":\"y\",}"}, {"transaction_notes", "{/*comment*/}"},
                {"balances", "{\"b\":{\"amount\":1,\"date\":0,\"sender\":\"s\","
                        + "\"future\":{\"x\":null,\"x\":0}}}"}
        };
        for (String[] item : cases) {
            assertTrue(data.edit().clear().putString(item[0], item[1]).commit());
            rejected(source());
        }
        assertTrue(data.edit().clear().commit());
        for (String value : Arrays.asList("{}", "[1]", "[null]", "[\"b\",]", "[\"b\"]x", "")) {
            assertTrue(settings.edit().putString("excluded_banks", value).commit());
            rejected(source());
        }
    }

    @Test public void missingKeyAuthenticationTruncationAndInvalidUtf8AreFatal() throws Exception {
        seed(1);
        rejected(source(() -> null, LegacyFinancialSource.Limits.defaults()));
        rejected(source(() -> { throw new IOException("sensitive provider detail"); },
                LegacyFinancialSource.Limits.defaults()));
        String valid = encrypt(bytes("{}"));
        byte[] corrupt = Base64.decode(valid, Base64.NO_WRAP);
        corrupt[corrupt.length - 1] ^= 1;
        for (String blob : Arrays.asList("", "!not-base64!", valid.substring(0, 12),
                Base64.encodeToString(corrupt, Base64.NO_WRAP), encrypt(bytes("{\"x\":")),
                encrypt(new byte[] { '{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}' }))) {
            assertTrue(data.edit().clear().putString("transaction_notes", blob).commit());
            assertTrue(settings.edit().clear().commit());
            rejected(source());
        }
    }

    @Test public void limitsCoverPlainEncryptedTotalDepthAndNodes() throws Exception {
        LegacyFinancialSource.Limits tiny = new LegacyFinancialSource.Limits(16, 16, 2, 8);
        LegacyFinancialSource source = source(() -> key, tiny);
        for (String text : Arrays.asList("{\"long-name\":\"long-value\"}",
                encrypt(bytes("{\"long-name\":\"long-value\"}")))) {
            assertTrue(data.edit().clear().putString("transaction_notes", text).commit());
            rejected(source);
        }
        assertTrue(data.edit().clear().putString("transaction_notes", "{\"a\":\"123\"}")
                .putString("transaction_reasons", "{\"a\":\"123\"}").commit());
        rejected(source);
        assertTrue(data.edit().clear().putString("transactions",
                "{\"transactions\":[],\"future\":[[[]]]}").commit());
        rejected(source(() -> key, new LegacyFinancialSource.Limits(1024, 1024, 2, 100)));
        assertTrue(data.edit().clear().putString("transaction_notes", "{\"a\":\"b\",\"c\":\"d\"}").commit());
        rejected(source(() -> key, new LegacyFinancialSource.Limits(1024, 1024, 32, 4)));
    }

    @Test public void byteBudgetsPreflightUnicodeAndEncryptedRemainingTotal() throws Exception {
        String text = "{\"n\":\"界🌍\"}";
        int length = bytes(text).length;
        assertTrue(data.edit().putString("transaction_notes", text).commit());
        rejected(source(() -> { throw new AssertionError("Unexpected key lookup"); },
                new LegacyFinancialSource.Limits(length - 1, 1024, 32, 100)));
        assertArrayEquals(bytes(text), read(source(() -> key,
                new LegacyFinancialSource.Limits(length, length, 32, 100))).get("transaction_notes"));
        assertTrue(data.edit().clear().putString("balances", "{}")
                .putString("transaction_notes", encrypt(bytes(text))).commit());
        rejected(source(() -> { throw new AssertionError("Over-budget ciphertext reached key lookup"); },
                new LegacyFinancialSource.Limits(1024, length + 1, 32, 100)));
        assertArrayEquals(bytes(text), read(source(() -> key,
                new LegacyFinancialSource.Limits(1024, length + 2, 32, 100))).get("transaction_notes"));
    }

    @Test public void actualLegacySerializersPreserveExactPayloads() throws Exception {
        LinkedHashMap<String, Bank> banks = new LinkedHashMap<>();
        banks.put("Mellat", new Bank("Mellat", Long.MAX_VALUE, 123, "synthetic"));
        banks.put("Mellat|001", new Bank("Mellat", 0, 124, "synthetic", "001"));
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("legacy|sig", "  یادداشت 🌍\nline  ");
        Map<String, String> serialized = new LinkedHashMap<>();
        serialized.put("balances", BalanceData.serialize(banks));
        serialized.put("transactions", BalanceData.serializeTransactions(Arrays.asList(
                new Transaction("Mellat", 123, Long.MIN_VALUE),
                new Transaction("Mellat", "001", 124, 1, "sig", null))));
        serialized.put("transaction_notes", BalanceData.serializeTextMap(notes));
        serialized.put("transaction_reasons", BalanceData.serializeTextMap(notes));
        serialized.put("transaction_channels", BalanceData.serializeTextMap(notes));
        Map<String, byte[]> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : serialized.entrySet()) {
            expected.put(entry.getKey(), bytes(entry.getValue()));
            assertTrue(data.edit().putString(entry.getKey(), encrypt(bytes(entry.getValue()))).commit());
        }
        same(expected, read(source()));
    }

    @Test public void migrationAdoptsAllBytesOnlyAfterCompleteValidation() throws Exception {
        Map<String, byte[]> expected = seed(2);
        Map<String, ?> beforeData = data.getAll(), beforeSettings = settings.getAll();
        EncryptedGenerationStore.Limits limits = new EncryptedGenerationStore.Limits(13, 8192, 65536);
        LegacyGenerationMigration migration = new LegacyGenerationMigration(sandbox, key, limits);
        // The final component is corrupt: a valid prefix must never authorize adoption.
        assertTrue(settings.edit().putString("excluded_banks", "[\"truncated\"").commit());
        synchronized (BalanceData.class) {
            try { migration.open(source()); fail("Adopted partial source"); }
            catch (IOException expectedFailure) { assertNull(expectedFailure.getCause()); }
        }
        assertEquals(0, sandbox.list().length);
        assertEquals(beforeData, data.getAll());
        assertEquals("[\"truncated\"", settings.getString("excluded_banks", null));
        assertTrue(settings.edit().putString("excluded_banks", new String(expected.get("excluded_banks"),
                StandardCharsets.UTF_8)).commit());
        synchronized (BalanceData.class) {
            same(expected, migration.open(source()).getSnapshot().components());
        }
        assertEquals(beforeData, data.getAll());
        assertEquals(beforeSettings, settings.getAll());
    }

    @Test public void corruptionInAnyComponentPreventsMigrationAndLeavesSourceIntact() throws Exception {
        LegacyGenerationMigration migration = new LegacyGenerationMigration(sandbox, key,
                new EncryptedGenerationStore.Limits(13, 8192, 65536));
        for (String name : DATA) {
            seed(2);
            // Authenticated but truncated JSON must fail just as decisively as bad ciphertext.
            assertTrue(data.edit().putString(name, encrypt(bytes("{\"truncated\":"))).commit());
            migrationRejectedWithoutWrites(migration);
        }
        for (String name : SETTINGS) {
            seed(2);
            assertTrue(settings.edit().putBoolean(name, true).commit());
            migrationRejectedWithoutWrites(migration);
        }
        seed(2);
        byte[] damaged = Base64.decode(data.getString("balances", null), Base64.NO_WRAP);
        damaged[damaged.length - 1] ^= 1;
        assertTrue(data.edit().putString("balances", Base64.encodeToString(damaged, Base64.NO_WRAP)).commit());
        migrationRejectedWithoutWrites(migration);
    }

    private void migrationRejectedWithoutWrites(LegacyGenerationMigration migration) throws Exception {
        Map<String, ?> beforeData = data.getAll(), beforeSettings = settings.getAll();
        synchronized (BalanceData.class) {
            try { migration.open(source()); fail("Adopted corrupt source"); }
            catch (IOException expected) { assertNull(expected.getCause()); }
        }
        assertEquals(0, sandbox.list().length);
        assertEquals(beforeData, data.getAll());
        assertEquals(beforeSettings, settings.getAll());
    }

    @Test public void callerMustHoldDataMonitor() throws Exception {
        try { source().readValidatedSnapshot(); fail("Unlocked read"); }
        catch (IOException expected) { assertEquals("LOCK", expected.getMessage()); }
    }
}
