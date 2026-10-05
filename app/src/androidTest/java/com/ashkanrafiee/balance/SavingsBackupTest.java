package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Password-backup coverage for savings holdings and legacy payload compatibility. */
@RunWith(AndroidJUnit4.class)
public class SavingsBackupTest {
    private static final String PASSWORD = "savings backup test password";
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.writeSavingsAssets(context, Collections.emptyList());
    }

    @After public void tearDown() { BalanceData.writeSavingsAssets(context, Collections.emptyList()); }

    @Test public void encryptedRoundTripRestoresManualHolding() throws Exception {
        SavingsAsset asset = new SavingsAsset("backup-holding", "USD", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 250_000, 500_000L);
        BalanceData.saveSavingsAsset(context, asset);
        File file = new File(context.getCacheDir(), "savings-backup.balance");
        file.delete();
        BackupManager.create(context, Uri.fromFile(file), PASSWORD);
        BalanceData.writeSavingsAssets(context, Collections.emptyList());
        BackupManager.RestoreResult result = BackupManager.restore(context, Uri.fromFile(file), PASSWORD);
        assertEquals(1, result.savingsAdded);
        assertEquals(1, BalanceData.readSavingsAssets(context).size());
        assertEquals(250_000L, BalanceData.readSavingsAssets(context).get(0).quantityScaled);
        assertEquals(500_000L, BalanceData.readSavingsAssets(context).get(0).unitValueRial);
    }

    @Test public void restoreAddsNewHoldingsButLocalIdWins() throws Exception {
        SavingsAsset backupCollision = new SavingsAsset("same", "Backup", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_000, 20L);
        SavingsAsset backupOnly = new SavingsAsset("backup-only", "Backup", SavingsAsset.Kind.CURRENCY,
            0, "", "EUR", 1_000, 30L);
        BalanceData.writeSavingsAssets(context, Arrays.asList(backupCollision, backupOnly));
        File file = new File(context.getCacheDir(), "savings-merge.balance");
        file.delete();
        BackupManager.create(context, Uri.fromFile(file), PASSWORD);

        SavingsAsset local = new SavingsAsset("same", "Local", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_000, 10L);
        BalanceData.writeSavingsAssets(context, Collections.singletonList(local));
        BackupManager.RestoreResult result = BackupManager.restore(context, Uri.fromFile(file), PASSWORD);

        assertEquals(1, result.savingsAdded);
        assertEquals(2, BalanceData.readSavingsAssets(context).size());
        assertEquals(10L, BalanceData.readSavingsAssets(context).get(0).unitValueRial);
        assertEquals(40L, SavingsAsset.totalRial(BalanceData.readSavingsAssets(context)));
    }

    @Test public void oldBackupWithoutSavingsPreservesLocalHoldings() throws Exception {
        SavingsAsset local = new SavingsAsset("keep", "Local", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", 1_000, 99L);
        BalanceData.writeSavingsAssets(context, Collections.singletonList(local));
        File file = new File(context.getCacheDir(), "legacy-without-savings.balance");
        file.delete();
        writeLegacyBackup(file, "{\"payloadFormat\":6,\"balances\":{}}", PASSWORD);

        BackupManager.RestoreResult result = BackupManager.restore(context, Uri.fromFile(file), PASSWORD);
        assertEquals(0, result.savingsAdded);
        assertEquals(1, BalanceData.readSavingsAssets(context).size());
        assertEquals(99L, BalanceData.readSavingsAssets(context).get(0).unitValueRial);
    }

    /** Writes the pre-savings encrypted format used by older releases. */
    private static void writeLegacyBackup(File file, String payload, String password) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        JSONObject header = new JSONObject()
            .put("format", 1)
            .put("createdAt", 1_000_000_000L)
            .put("appVersion", "1.0.0")
            .put("kdf", new JSONObject()
                .put("algorithm", "PBKDF2WithHmacSHA256")
                .put("iterations", 600000)
                .put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                .put("keyBits", 256))
            .put("cipher", new JSONObject()
                .put("algorithm", "AES/GCM/NoPadding")
                .put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .put("tagBits", 128));
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 600000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        SecretKey key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(headerBytes);
        byte[] ciphertext = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("BALNCEBK".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        out.write((headerBytes.length >>> 24) & 0xFF);
        out.write((headerBytes.length >>> 16) & 0xFF);
        out.write((headerBytes.length >>> 8) & 0xFF);
        out.write(headerBytes.length & 0xFF);
        out.write(headerBytes);
        out.write(ciphertext);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(out.toByteArray());
        }
    }
}
