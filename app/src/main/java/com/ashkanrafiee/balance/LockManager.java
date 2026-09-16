package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/**
 * The in-app lock that keeps the balances hidden behind a PIN, a password or the device fingerprint.
 *
 * <p>The PIN/password is never stored. Only a PBKDF2-with-HMAC-SHA-256 hash of it is kept (fresh
 * random 128-bit salt, 600k iterations — same work factor as the encrypted backups), so the code
 * can only ever be checked, never recovered, and survives nothing but the device's own preferences
 * file.
 *
 * <p>Fingerprint unlock uses only platform APIs (Android 8.0+, the app's minimum): a symmetric key
 * in AndroidKeyStore is created with {@code setUserAuthenticationRequired(true)} and
 * {@code setInvalidatedByBiometricEnrollment(true)}, and wraps a random challenge via
 * {@code FingerprintManager}. The challenge only decrypts through the system fingerprint dialog, so a
 * verified fingerprint is genuinely required — and if a fingerprint is later enrolled or removed the
 * key is permanently invalidated and the app falls back to the PIN/password. The keystore key and
 * challenge are also removed the moment the lock (or just fingerprint unlock) is disabled.
 *
 * <p>The session is app-wide and static: every protected activity reports its lifecycle through
 * {@link #registerActivityStart()} / {@link #registerActivityStop()}, and the lock engages whenever
 * the last screen leaves the foreground, so reopening the app always asks for the code again.
 */
final class LockManager {
    private static final String TAG = "LockManager";
    private static final String PREFS = BalanceData.PREFS_PREF;

    static final String KEY_LOCK_ENABLED = "lock_enabled";
    static final String KEY_LOCK_PIN = "lock_pin_mode";
    static final String KEY_LOCK_SALT = "lock_salt";
    static final String KEY_LOCK_ITERATIONS = "lock_iterations";
    static final String KEY_LOCK_HASH = "lock_hash";
    private static final String KEY_LOCK_FP_CHAIN = "lock_fp_chain";
    private static final String KEY_LOCK_FP_CHALLENGE = "lock_fp_challenge";

    /** Same OWASP work factor as {@link BackupManager}; verification runs off the UI thread. */
    private static final int LOCK_ITERATIONS = 600_000;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String FP_ALIAS = "balance_lock_fp_key";
    private static final String FP_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Fingerprint binding states reported to the UI: usable, permanently invalidated (a new
     *  fingerprint was enrolled), or absent/unusable (no binding, no sensor, no enrolled prints). */
    static final int FP_OK = 0;
    static final int FP_INVALIDATED = 1;
    static final int FP_UNAVAILABLE = 2;

    /** Set once the whole app is in the background (or the user taps the lock button), cleared on a
     *  successful unlock. Lives in the process, never in preferences. */
    private static volatile boolean sessionLocked = false;
    private static final AtomicInteger activityCount = new AtomicInteger();

    private LockManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ====================================================================
    // Session state
    // ====================================================================

    static boolean isSessionLocked() {
        return sessionLocked;
    }

    static void lockSession() {
        sessionLocked = true;
    }

    static void unlockSession() {
        sessionLocked = false;
    }

    /** Called by every protected activity from {@code onStart}; when the first screen appears after a
     *  pause the session is re-locked, so re-opening the app always asks for the code. */
    static void registerActivityStart(Context c) {
        if (activityCount.getAndIncrement() == 0 && isEnabled(c)) lockSession();
    }

    /** Called by every protected activity from {@code onStop}; when the last screen leaves the
     *  foreground the session locks, ready for the next {@link #registerActivityStart}. */
    static void registerActivityStop() {
        if (activityCount.decrementAndGet() <= 0) lockSession();
    }

    /** Test hook: resets the process-wide session counters so a test starts from a known state. */
    static void resetSessionForTest() {
        activityCount.set(0);
        sessionLocked = false;
    }

    // ====================================================================
    // PIN / password
    // ====================================================================

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_LOCK_ENABLED, false);
    }

    static boolean isPinMode(Context c) {
        return prefs(c).getBoolean(KEY_LOCK_PIN, true);
    }

    /** Validates a prospective code for the given mode: PINs are 4-6 digits, passwords ≥ 6 chars. */
    static boolean validCode(String code, boolean pin) {
        if (code == null) return false;
        if (pin) {
            if (code.length() < 4 || code.length() > 6) return false;
            for (int i = 0; i < code.length(); i++)
                if (!Character.isDigit(code.charAt(i))) return false;
            return true;
        }
        return code.length() >= 6;
    }

    /** Stores a fresh lock code, erasing any previous one, and optionally enables fingerprint
     *  unlock. Derives the PBKDF2 hash synchronously, so call from a worker thread. */
    static void enable(Context c, String code, boolean pin, boolean wantFingerprint) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hash(code, salt, LOCK_ITERATIONS);
        prefs(c).edit()
            .putBoolean(KEY_LOCK_ENABLED, true)
            .putBoolean(KEY_LOCK_PIN, pin)
            .putString(KEY_LOCK_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(KEY_LOCK_ITERATIONS, LOCK_ITERATIONS)
            .putString(KEY_LOCK_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply();
        if (wantFingerprint) setFingerprintEnabled(c, true);
        // The user just set this up in a live session: do not lock it immediately.
        unlockSession();
    }

    /** Replaces the stored code, keeping the mode and any fingerprint binding untouched. */
    static void changeCode(Context c, String code, boolean pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hash(code, salt, LOCK_ITERATIONS);
        prefs(c).edit()
            .putBoolean(KEY_LOCK_PIN, pin)
            .putString(KEY_LOCK_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(KEY_LOCK_ITERATIONS, LOCK_ITERATIONS)
            .putString(KEY_LOCK_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply();
        // Changing the code does not invalidate the fingerprint challenge: that key is independent.
    }

    /** Constant-time check of a code against the stored hash. Runs synchronously — call off-thread. */
    static boolean verify(Context c, String code) {
        if (!isEnabled(c)) return true;
        if (code == null) return false;
        SharedPreferences p = prefs(c);
        String saltB64 = p.getString(KEY_LOCK_SALT, null);
        String hashB64 = p.getString(KEY_LOCK_HASH, null);
        if (saltB64 == null || hashB64 == null) return false;
        try {
            byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
            byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
            int iterations = p.getInt(KEY_LOCK_ITERATIONS, LOCK_ITERATIONS);
            byte[] actual = hash(code, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            Log.w(TAG, "verify failed", e);
            return false;
        }
    }

    /** Forgets the code, the fingerprint binding and the lock flag entirely. */
    static void disable(Context c) {
        deleteFingerprintBinding(c);
        prefs(c).edit()
            .remove(KEY_LOCK_ENABLED)
            .remove(KEY_LOCK_PIN)
            .remove(KEY_LOCK_SALT)
            .remove(KEY_LOCK_ITERATIONS)
            .remove(KEY_LOCK_HASH)
            .apply();
        unlockSession();
    }

    private static byte[] hash(String code, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ====================================================================
    // Fingerprint
    // ====================================================================

    /** True when the device has fingerprint hardware AND at least one enrolled fingerprint. */
    static boolean fingerprintCapable(Context c) {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            FingerprintManager fm = fingerprintManager(c);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    static FingerprintManager fingerprintManager(Context c) {
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            return (FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isFingerprintEnabled(Context c) {
        return prefs(c).getString(KEY_LOCK_FP_CHAIN, null) != null;
    }

    /** Turns fingerprint unlock on (creating a fresh keystore-bound challenge) or off. */
    static boolean setFingerprintEnabled(Context c, boolean on) {
        if (on) {
            boolean ok = createFingerprintBinding(c);
            if (!ok) {
                Log.w(TAG, "fingerprint binding could not be created");
                prefs(c).edit()
                    .remove(KEY_LOCK_FP_CHAIN)
                    .remove(KEY_LOCK_FP_CHALLENGE)
                    .apply();
            }
            return ok;
        }
        deleteFingerprintBinding(c);
        return true;
    }

    /** The usability of the fingerprint binding, for building the entrance screen. */
    static int fpStatus(Context c) {
        if (!isEnabled(c) || prefs(c).getString(KEY_LOCK_FP_CHAIN, null) == null) return FP_UNAVAILABLE;
        if (!fingerprintCapable(c) || fingerprintManager(c) == null) return FP_UNAVAILABLE;
        try {
            byte[] blob = Base64.decode(prefs(c).getString(KEY_LOCK_FP_CHAIN, ""), Base64.NO_WRAP);
            if (blob == null || blob.length < GCM_IV_BYTES + GCM_TAG_BITS / 8) return FP_UNAVAILABLE;
            SecretKey key = fpKey();
            if (key == null) return FP_UNAVAILABLE;
            Cipher dec = Cipher.getInstance(FP_TRANSFORM);
            dec.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES));
            return FP_OK;
        } catch (KeyPermanentlyInvalidatedException e) {
            return FP_INVALIDATED;
        } catch (Exception e) {
            return FP_UNAVAILABLE;
        }
    }

    /** A {@link FingerprintManager.CryptoObject} bound to the stored challenge, for the system
     *  fingerprint dialog. Null when the binding cannot be opened (invalidated, or device locked
     *  since boot without a first unlock — then the PIN/password is the fallback). */
    @SuppressWarnings("deprecation")
    static FingerprintManager.CryptoObject fingerprintCrypto(Context c) {
        String chain = prefs(c).getString(KEY_LOCK_FP_CHAIN, null);
        if (chain == null) return null;
        try {
            byte[] blob = Base64.decode(chain, Base64.NO_WRAP);
            if (blob == null || blob.length < GCM_IV_BYTES + GCM_TAG_BITS / 8) return null;
            SecretKey key = fpKey();
            if (key == null) return null;
            Cipher dec = Cipher.getInstance(FP_TRANSFORM);
            dec.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES));
            return new FingerprintManager.CryptoObject(dec);
        } catch (Exception e) {
            return null;
        }
    }

    /** After a successful fingerprint authentication, proves the stored challenge actually decrypted
     *  with the keystore key — completing the crypto operation the dialog authorized. */
    @SuppressWarnings("deprecation")
    static boolean fingerprintSucceeded(Context c, FingerprintManager.AuthenticationResult result) {
        try {
            String challenge = prefs(c).getString(KEY_LOCK_FP_CHALLENGE, null);
            byte[] blob = Base64.decode(prefs(c).getString(KEY_LOCK_FP_CHAIN, ""), Base64.NO_WRAP);
            if (challenge == null || result == null || result.getCryptoObject() == null
                    || result.getCryptoObject().getCipher() == null || blob == null
                    || blob.length <= GCM_IV_BYTES) return false;
            byte[] plain = result.getCryptoObject().getCipher().doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
            return challenge.equals(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "fingerprint challenge failed", e);
            return false;
        }
    }

    /** Creates the keystore key (auth-required, invalidated on enrollment changes) and seals a fresh
     *  random challenge with it. The plaintext challenge is kept next to the ciphertext only so the
     *  app can recognise the authentic decrypt; it is useless to anyone without the fingerprint. */
    private static boolean createFingerprintBinding(Context c) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(FP_ALIAS, KeyProperties.PURPOSE_ENCRYPT
                    | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setRandomizedEncryptionRequired(true)
                .build());
            SecretKey key = kg.generateKey();
            byte[] challenge = new byte[16];
            new SecureRandom().nextBytes(challenge);
            String challengeB64 = Base64.encodeToString(challenge, Base64.NO_WRAP);
            Cipher enc = Cipher.getInstance(FP_TRANSFORM);
            enc.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = enc.getIV();
            byte[] ct = enc.doFinal(challengeB64.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
            prefs(c).edit()
                .putString(KEY_LOCK_FP_CHAIN, Base64.encodeToString(blob, Base64.NO_WRAP))
                .putString(KEY_LOCK_FP_CHALLENGE, challengeB64)
                .apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "fingerprint binding failed", e);
            return false;
        }
    }

    private static void deleteFingerprintBinding(Context c) {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(FP_ALIAS)) ks.deleteEntry(FP_ALIAS);
        } catch (Exception e) {
            Log.w(TAG, "fingerprint key deletion failed", e);
        }
        prefs(c).edit()
            .remove(KEY_LOCK_FP_CHAIN)
            .remove(KEY_LOCK_FP_CHALLENGE)
            .apply();
    }

    private static SecretKey fpKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(FP_ALIAS)) return null;
        return (SecretKey) ks.getKey(FP_ALIAS, null);
    }
}