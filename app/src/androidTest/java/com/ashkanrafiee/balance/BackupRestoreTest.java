package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Tests for the encrypted backup / restore feature: file format, tamper and wrong-password
 * rejection, and the newest-wins merge used on restore.
 */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreTest {

    private static final long T = 1_000_000_000L;
    private static final String PASSWORD = "correct horse battery staple";

    private Context ctx;

    @Before public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() throws Exception {
        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private File file(String name) {
        File f = new File(ctx.getCacheDir(), name);
        f.delete();
        return f;
    }

    private Uri uri(String name) {
        return Uri.fromFile(file(name));
    }

    private static Bank bank(String name, long amount, long date) {
        return new Bank(name, amount, date, name);
    }

    private LinkedHashMap<String, Bank> map(Bank... banks) {
        LinkedHashMap<String, Bank> m = new LinkedHashMap<>();
        for (Bank b : banks) m.put(b.name, b);
        return m;
    }

    private byte[] readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        }
    }

    // ============================================================
    // Round trip
    // ============================================================

    @Test public void roundTrip_restoresAllBalances() throws Exception {
        BalanceData.write(ctx, map(
            bank("Tejarat", 1_000_000L, T + 1000),
            bank("Saman", 5_000_000L, T + 2000),
            bank("Pasargad", 3_000_000L, T + 3000)));

        Uri u = uri("roundtrip.balance");
        BackupManager.create(ctx, u, PASSWORD);

        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);
        assertEquals(3, res.added);
        assertEquals(0, res.updated);

        LinkedHashMap<String, Bank> out = BalanceData.read(ctx);
        assertEquals(3, out.size());
        assertEquals(1_000_000L, out.get("Tejarat").amount);
        assertEquals(T + 1000, out.get("Tejarat").date);
        assertEquals(5_000_000L, out.get("Saman").amount);
        assertEquals(3_000_000L, out.get("Pasargad").amount);
    }

    @Test public void roundTrip_emptyDataCreatesRestorableFile() throws Exception {
        Uri u = uri("empty.balance");
        BackupManager.create(ctx, u, PASSWORD);

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);
        assertEquals(0, res.added);
        assertEquals(0, res.updated);
        assertTrue(BalanceData.read(ctx).isEmpty());
    }

    @Test public void headerCarriesSelfDescribingEncryptionParameters() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("header.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);

        byte[] bytes = readFile(f);
        assertEquals("BALNCEBK", new String(bytes, 0, 8, StandardCharsets.US_ASCII));
        assertEquals(1, bytes[8] & 0xFF);
        int headerLen = ((bytes[9] & 0xFF) << 24) | ((bytes[10] & 0xFF) << 16)
            | ((bytes[11] & 0xFF) << 8) | (bytes[12] & 0xFF);
        String header = new String(bytes, 13, headerLen, StandardCharsets.UTF_8);
        android.util.Log.i("BackupRestoreTest", "HEADER=" + header);
        org.json.JSONObject h = new org.json.JSONObject(header);
        assertEquals(1, h.getInt("format"));
        assertEquals(600000, h.getJSONObject("kdf").getInt("iterations"));
        assertEquals("PBKDF2WithHmacSHA256", h.getJSONObject("kdf").getString("algorithm"));
        assertEquals(256, h.getJSONObject("kdf").getInt("keyBits"));
        assertEquals("AES/GCM/NoPadding", h.getJSONObject("cipher").getString("algorithm"));
        assertEquals(128, h.getJSONObject("cipher").getInt("tagBits"));
        assertTrue(h.getJSONObject("kdf").has("salt"));
        assertTrue(h.getJSONObject("cipher").has("iv"));
        assertTrue(bytes.length > 13 + headerLen);
    }

    // ============================================================
    // Rejection paths
    // ============================================================

    @Test public void wrongPassword_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        Uri u = uri("wrongpw.balance");
        BackupManager.create(ctx, u, PASSWORD);

        try {
            BackupManager.restore(ctx, u, "not the password");
            fail("wrong password must fail");
        } catch (BackupManager.BackupException e) {
            assertEquals(R.string.backup_error_password, e.resId);
        }
        // The pre-existing balance must be untouched (restore is rejected, nothing merged).
        assertEquals(1, BalanceData.read(ctx).size());
    }

    @Test public void randomFile_isRejected() throws Exception {
        File f = file("random.balance");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write("this is not a balance backup file".getBytes(StandardCharsets.UTF_8));
        }
        try {
            BackupManager.restore(ctx, Uri.fromFile(f), PASSWORD);
            fail("non-backup file must be rejected");
        } catch (BackupManager.BackupException e) {
            assertEquals(R.string.backup_error_not_backup, e.resId);
        }
    }

    @Test public void newerFormatVersion_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("futuristic.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        byte[] bytes = readFile(f);
        bytes[8] = 99;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(bytes);
        }
        try {
            BackupManager.restore(ctx, Uri.fromFile(f), PASSWORD);
            fail("future format must be rejected");
        } catch (BackupManager.BackupException e) {
            assertEquals(R.string.backup_error_unsupported, e.resId);
        }
    }

    @Test public void tamperedCiphertext_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("tampered.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        byte[] bytes = readFile(f);
        int last = bytes.length - 1;
        bytes[last] ^= 0xFF;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(bytes);
        }
        try {
            BackupManager.restore(ctx, Uri.fromFile(f), PASSWORD);
            fail("tampered ciphertext must be rejected");
        } catch (BackupManager.BackupException e) {
            assertEquals(R.string.backup_error_password, e.resId);
        }
    }

    @Test public void tamperedHeader_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("tamperedHeader.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        byte[] bytes = readFile(f);
        int headerLen = ((bytes[9] & 0xFF) << 24) | ((bytes[10] & 0xFF) << 16)
            | ((bytes[11] & 0xFF) << 8) | (bytes[12] & 0xFF);
        // Flip a digit of the iteration count inside the plaintext header.
        int pos = 13;
        while (pos < 13 + headerLen && (bytes[pos] < '0' || bytes[pos] > '9')) pos++;
        if (pos < 13 + headerLen) {
            bytes[pos] = (byte) (bytes[pos] == '0' ? '1' : bytes[pos] - 1);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(bytes);
            }
            try {
                BackupManager.restore(ctx, Uri.fromFile(f), PASSWORD);
                fail("tampered header must be rejected");
            } catch (BackupManager.BackupException e) {
                assertEquals(R.string.backup_error_password, e.resId);
            }
        }
    }

    // ---- hostile header parameters must be bounded before any key derivation runs -------

    private BackupManager.BackupException restoreExpecting(Mutator mutate, File f) throws Exception {
        byte[] bytes = readFile(f);
        byte[] mutated = mutate.apply(bytes);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(mutated);
        }
        try {
            BackupManager.restore(ctx, Uri.fromFile(f), PASSWORD);
            fail("hostile header must be rejected as unsupported");
        } catch (BackupManager.BackupException e) {
            return e;
        }
        return null;
    }

    private interface Mutator { byte[] apply(byte[] b) throws Exception; }

    /** Rewrites a field inside one header section (e.g. "kdf"/"iterations") and re-serializes it. */
    private byte[] rewriteHeaderField(byte[] bytes, String section, String field, Object value) throws Exception {
        int headerLen = ((bytes[9] & 0xFF) << 24) | ((bytes[10] & 0xFF) << 16)
            | ((bytes[11] & 0xFF) << 8) | (bytes[12] & 0xFF);
        org.json.JSONObject h = new org.json.JSONObject(
            new String(bytes, 13, headerLen, StandardCharsets.UTF_8));
        h.getJSONObject(section).put(field, value);
        byte[] newHeader = h.toString().getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(bytes, 0, 9);
        out.write((newHeader.length >> 24) & 0xFF);
        out.write((newHeader.length >> 16) & 0xFF);
        out.write((newHeader.length >> 8) & 0xFF);
        out.write(newHeader.length & 0xFF);
        out.write(newHeader);
        out.write(bytes, 13 + headerLen, bytes.length - (13 + headerLen));
        return out.toByteArray();
    }

    @Test public void hostileIterations_aboveCap_isRejectedBeforeDerivation() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("hostileIter.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        BackupManager.BackupException e = restoreExpecting(
            b -> rewriteHeaderField(b, "kdf", "iterations", Integer.MAX_VALUE / 2), f);
        assertEquals(R.string.backup_error_unsupported, e.resId);
    }

    @Test public void hostileZeroIterations_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("hostileZeroIter.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        assertEquals(R.string.backup_error_unsupported,
            restoreExpecting(b -> rewriteHeaderField(b, "kdf", "iterations", 0), f).resId);
    }

    @Test public void hostileHugeKeyBits_isRejectedBeforeDerivation() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("hostileKeyBits.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        assertEquals(R.string.backup_error_unsupported,
            restoreExpecting(b -> rewriteHeaderField(b, "kdf", "keyBits", Integer.MAX_VALUE), f).resId);
    }

    @Test public void hostileOddKeyBits_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("hostileOddKeyBits.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        assertEquals(R.string.backup_error_unsupported,
            restoreExpecting(b -> rewriteHeaderField(b, "kdf", "keyBits", 123), f).resId);
    }

    @Test public void hostileTagBits_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("hostileTagBits.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        assertEquals(R.string.backup_error_unsupported,
            restoreExpecting(b -> rewriteHeaderField(b, "cipher", "tagBits", 256), f).resId);
    }

    @Test public void hostileIvLength_isRejected() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        File f = file("hostileIv.balance");
        BackupManager.create(ctx, Uri.fromFile(f), PASSWORD);
        assertEquals(R.string.backup_error_unsupported,
            restoreExpecting(b -> rewriteHeaderField(b, "cipher", "iv",
                android.util.Base64.encodeToString(new byte[16], android.util.Base64.NO_WRAP)), f).resId);
    }

    @Test public void oversizedFile_isRejected() throws Exception {
        // Just past the 10 MB cap: a file far larger than any real backup must not be read into memory.
        long over = 10L * 1024 * 1024 + 1;
        File f = file("huge.balance");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(new byte[(int) over]);
        }
        try {
            BackupManager.restore(ctx, Uri.fromFile(f), PASSWORD);
            fail("oversized backup must be rejected");
        } catch (BackupManager.BackupException e) {
            assertEquals(R.string.backup_error_not_backup, e.resId);
        }
    }

    // ============================================================
    // Merge semantics (newest wins per bank, never summed)
    // ============================================================

    @Test public void merge_keepsNewestPerBank_addsUnknownBanks() throws Exception {
        // Backup holds: a bank never seen before (Saman, Pasargad) and one older than current (Tejarat).
        {
            BalanceData.write(ctx, map(
                bank("Tejarat", 2_000_000L, T + 2000),
                bank("Saman", 9_000_000L, T + 3000),
                bank("Pasargad", 3_000_000L, T + 1500)));
            Uri u = uri("merge1.balance");
            BackupManager.create(ctx, u, PASSWORD);

            ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
            // Current holds: an older Tejarat (backup is newer) and a bank missing from the backup.
            BalanceData.write(ctx, map(
                bank("Tejarat", 1_000_000L, T + 1000),
                bank("Melat", 4_000_000L, T + 2500)));

            BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);

            assertEquals(2, res.added);    // Saman + Pasargad appear from the backup
            assertEquals(1, res.updated);  // Tejarat updated to the newer backup value
            LinkedHashMap<String, Bank> out = BalanceData.read(ctx);
            assertEquals(4, out.size());
            assertEquals(2_000_000L, out.get("Tejarat").amount);  // NOT 1M+2M summed
            assertEquals(T + 2000, out.get("Tejarat").date);
            assertEquals(9_000_000L, out.get("Saman").amount);
            assertEquals(3_000_000L, out.get("Pasargad").amount);
            assertEquals(4_000_000L, out.get("Melat").amount);    // current bank untouched
        }
    }

    @Test public void merge_backupNewerThanCurrent_wins() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        Uri u = uri("merge2.balance");
        BackupManager.create(ctx, u, PASSWORD);

        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        BalanceData.write(ctx, map(bank("Tejarat", 500_000L, T + 500)));

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);
        assertEquals(0, res.added);
        assertEquals(1, res.updated);
        assertEquals(1_000_000L, BalanceData.read(ctx).get("Tejarat").amount);
    }

    @Test public void merge_currentNewerThanBackup_keepsCurrent() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        Uri u = uri("merge3.balance");
        BackupManager.create(ctx, u, PASSWORD);

        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        BalanceData.write(ctx, map(bank("Tejarat", 7_000_000L, T + 9999)));

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);
        assertEquals(0, res.added);
        assertEquals(0, res.updated);
        assertTrue(!res.changed());
        assertEquals(7_000_000L, BalanceData.read(ctx).get("Tejarat").amount);
    }

    @Test public void merge_sameDate_keepsCurrent() throws Exception {
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));
        Uri u = uri("merge4.balance");
        BackupManager.create(ctx, u, PASSWORD);

        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        BalanceData.write(ctx, map(bank("Tejarat", 1_000_000L, T + 1000)));

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);
        assertEquals(0, res.added);
        assertEquals(0, res.updated);
        assertEquals(1_000_000L, BalanceData.read(ctx).get("Tejarat").amount);
    }

    // ============================================================
    // Transaction history merge on restore (union, deduped)
    // ============================================================

    @Test public void merge_transactions_dedupesDuplicateSiglessEntries() throws Exception {
        // A backup can hold identical legacy entries (same bank/date/amount, no fingerprint. Restore
        // must not double them even though they carry no identity fingerprint.
        List<Transaction> backupTxs = new ArrayList<>();
        backupTxs.add(new Transaction("Tejarat", T + 1000, -500_000L));
        backupTxs.add(new Transaction("Tejarat", T + 1000, -500_000L));
        BalanceData.writeTransactions(ctx, backupTxs);
        Uri u = uri("txn1.balance");
        BackupManager.create(ctx, u, PASSWORD);

        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);
        List<Transaction> out = BalanceData.readTransactions(ctx);
        assertEquals(1, out.size());
        assertEquals(-500_000L, out.get(0).amount);
    }

    @Test public void merge_transactions_unionKeepsCurrentAndAddsNewMovements() throws Exception {
        // Backup device history: a Tejarat deposit (fingerprinted) that the current device also has,
        // plus a Pasargad movement the current device has never scanned.
        List<Transaction> backupTxs = new ArrayList<>();
        backupTxs.add(new Transaction("Tejarat", T + 100, 200_000L, "sig-A"));
        backupTxs.add(new Transaction("Pasargad", T + 400, 300_000L, "sig-B"));
        BalanceData.writeTransactions(ctx, backupTxs);
        Uri u = uri("txn2.balance");
        BackupManager.create(ctx, u, PASSWORD);

        ctx.getSharedPreferences(BalanceData.PREFS_DATA, Context.MODE_PRIVATE).edit().clear().commit();
        // Current device history: the same Tejarat deposit (same fingerprint) and a local-only Melat
        // withdrawal. Restore must not duplicate Tejarat and must keep Melat.
        BalanceData.writeTransactions(ctx, Arrays.asList(
            new Transaction("Tejarat", T + 100, 200_000L, "sig-A"),
            new Transaction("Melat", T + 200, -50_000L, "sig-C")));

        BackupManager.RestoreResult res = BackupManager.restore(ctx, u, PASSWORD);

        List<Transaction> out = BalanceData.readTransactions(ctx);
        assertEquals(3, out.size());
        long tejarat = 0, pasargad = 0, melat = 0;
        for (Transaction t : out) {
            switch (t.bank) {
                case "Tejarat": tejarat++; break;
                case "Pasargad": pasargad++; break;
                case "Melat": melat++; break;
            }
        }
        assertEquals(1, tejarat);   // deduped across device and backup
        assertEquals(1, pasargad);  // new movement added from the backup
        assertEquals(1, melat);     // current history never dropped
    }
}