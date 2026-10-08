package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.util.Base64;

/** Focused coverage for the software row envelope and its legacy bridge. */
@RunWith(AndroidJUnit4.class)
public class EncryptedRowCodecTest {
    @Test public void roundTripUsesRandomNoncesAndBindsIdentity() throws Exception {
        String wrapped = EncryptedRowCodec.createWrappedKey();
        try (EncryptedRowCodec codec = EncryptedRowCodec.open(wrapped, "transactions")) {
            String first = codec.encrypt("private row", "ordinal\n7");
            String second = codec.encrypt("private row", "ordinal\n7");

            assertTrue(first.startsWith(EncryptedRowCodec.PREFIX));
            assertNotEquals(first, second);
            assertEquals("private row", codec.decrypt(first, "ordinal\n7"));
            assertEquals("private row", codec.decrypt(second, "ordinal\n7"));
            assertFails(() -> codec.decrypt(first, "ordinal\n8"));
        }
    }

    @Test public void domainSeparationRejectsCrossStoreRows() throws Exception {
        String wrapped = EncryptedRowCodec.createWrappedKey();
        try (EncryptedRowCodec transactions = EncryptedRowCodec.open(wrapped, "transactions");
                EncryptedRowCodec metadata = EncryptedRowCodec.open(wrapped, "metadata")) {
            String encoded = transactions.encrypt("value", "ordinal\n1");
            assertFails(() -> metadata.decrypt(encoded, "ordinal\n1"));
        }
    }

    @Test public void tamperingAndMalformedVersionFailClosed() throws Exception {
        String wrapped = EncryptedRowCodec.createWrappedKey();
        try (EncryptedRowCodec codec = EncryptedRowCodec.open(wrapped, "commitments")) {
            String encoded = codec.encrypt("value", "definition\nlookup");
            byte[] bytes = Base64.decode(encoded.substring(EncryptedRowCodec.PREFIX.length()),
                Base64.NO_WRAP);
            bytes[bytes.length - 1] ^= 1;
            String tampered = EncryptedRowCodec.PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP);
            assertFails(() -> codec.decrypt(tampered, "definition\nlookup"));
            assertFails(() -> codec.decrypt("ER2:" + encoded.substring(
                EncryptedRowCodec.PREFIX.length()), "definition\nlookup"));
        }
    }

    @Test public void legacyKeystoreRowsRemainReadable() throws Exception {
        String legacy = BalanceData.encryptStorePayload("legacy row");
        String wrapped = EncryptedRowCodec.createWrappedKey();
        try (EncryptedRowCodec codec = EncryptedRowCodec.open(wrapped, "transactions")) {
            assertEquals("legacy row", codec.decrypt(legacy, "ordinal\n0"));
        }
    }

    private static void assertFails(ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("expected authenticated decryption failure");
        } catch (Exception expected) {
            // Authentication, malformed input and identity mismatch all fail closed.
        }
    }

    private interface ThrowingAction { void run() throws Exception; }
}
