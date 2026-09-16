package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
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
 * random 128-bit salt, 100k iterations, so composing and unlocking stay fast; the encrypted
 * backups keep the much stronger 600k factor because they guard data at rest). A lock code hash
 * left over from an older build is re-derived in the background the next time it verifies. The
 * code can only ever be checked, never recovered.
 *
 * <p>Fingerprint unlock uses only platform APIs (Android 8.0+, the app's minimum): a symmetric key
 * in AndroidKeyStore is created with {@code setInvalidatedByBiometricEnrollment(true)} and
 * {@code setUserAuthenticationRequired(true)} (or {@code setUserAuthenticationParameters} on
 * Android 11+), and wraps a random challenge. On Android 9+ the challenge decrypts through the
 * modern {@code BiometricPrompt} system dialog; older builds fall back to the legacy
 * {@code FingerprintManager}. A verified fingerprint is genuinely required either way — and if a
 * fingerprint is later enrolled or removed the key is permanently invalidated and the app falls
 * back to the PIN/password. The keystore key and challenge are also removed the moment the lock (or
 * just fingerprint unlock) is disabled.
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

    /** PBKDF2 work factor for the lock code: 100k keeps enabling and verifying snappy on phones.
     *  Stored per-lock, so an existing stronger factor keeps working and is re-derived lazily
     *  after a successful verify. */
    static final int LOCK_ITERATIONS = 100_000;
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

    /** While a system activity we launched (e.g. the backup file picker) is in front, the session
     *  must not lock, or the user would face the entrance again on return. The hold expires on its
     *  own after {@link #HOLD_GRACE_MS}, so a user who walks away while the picker is open still
     *  re-engages the lock on a later return. */
    static final long HOLD_GRACE_MS = 60_000L;
    private static volatile boolean holdUnlock = false;
    private static volatile long holdSince = 0;

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
        if (activityCount.getAndIncrement() == 0 && isEnabled(c) && !holdingUnlock()) lockSession();
    }

    /** Called by every protected activity from {@code onStop}. When the last screen leaves the
     *  foreground the session locks, ready for the next {@link #registerActivityStart}. Returns
     *  whether this stop ended the foreground session, so the caller can flip its overlay to the
     *  lock entrance for the exit frame. */
    static boolean registerActivityStop() {
        int left = activityCount.decrementAndGet();
        boolean last = left <= 0 && !holdingUnlock();
        if (last) lockSession();
        return last;
    }

    /** Marks the imminent takeover by a system activity (the backup/restore file picker), so the
     *  session stays open until the user returns or the hold expires. Call before launching the
     *  document intent; the hold needs no explicit clearing. */
    static void holdUnlock() {
        holdUnlock = true;
        holdSince = android.os.SystemClock.elapsedRealtime();
    }

    private static boolean holdingUnlock() {
        return holdUnlock
            && android.os.SystemClock.elapsedRealtime() - holdSince < HOLD_GRACE_MS;
    }

    /** Test hook: ages the hold out so a caller can exercise the post-grace path without waiting. */
    static void expireHoldForTest() {
        holdSince = 0;
    }

    /** Test hook: resets the process-wide session counters so a test starts from a known state. */
    static void resetSessionForTest() {
        activityCount.set(0);
        sessionLocked = false;
        holdUnlock = false;
        holdSince = 0;
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
            boolean ok = MessageDigest.isEqual(expected, actual);
            // A lock created by an older build may carry a different work factor; bring it up to
            // the current one in the background after a successful verify, keeping the same salt.
            if (ok && iterations != LOCK_ITERATIONS) rehashToCurrentFactor(c, code, salt);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "verify failed", e);
            return false;
        }
    }

    private static void rehashToCurrentFactor(final Context c, final String code, final byte[] salt) {
        new Thread(() -> {
            try {
                byte[] hash = hash(code, salt, LOCK_ITERATIONS);
                prefs(c).edit()
                    .putInt(KEY_LOCK_ITERATIONS, LOCK_ITERATIONS)
                    .putString(KEY_LOCK_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .apply();
            } catch (Exception e) {
                Log.w(TAG, "rehash of the lock code failed", e);
            }
        }).start();
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

    /** True when the device has a usable strong biometric AND at least one enrolled print. Modern
     *  builds ask {@code BiometricManager}; Android 8–9 (which have no BiometricManager) fall back to
     *  the legacy FingerprintManager, which is still functional there. */
    static boolean fingerprintCapable(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                BiometricManager bm = (BiometricManager) c.getSystemService(Context.BIOMETRIC_SERVICE);
                if (bm == null) return false;
                int verdict = Build.VERSION.SDK_INT >= 30
                    ? bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    : bm.canAuthenticate();
                return verdict == BiometricManager.BIOMETRIC_SUCCESS;
            } catch (Throwable t) {
                return false;
            }
        }
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

    /** Which system prompt the entrance screen should use on this build. */
    static boolean fpUsesBiometricPrompt() {
        return Build.VERSION.SDK_INT >= 28;
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
        if (!fingerprintCapable(c)) return FP_UNAVAILABLE;
        try {
            Cipher dec = fpDecryptCipher(c);
            if (dec == null) return FP_UNAVAILABLE;
            return FP_OK;
        } catch (KeyPermanentlyInvalidatedException e) {
            return FP_INVALIDATED;
        } catch (Exception e) {
            return FP_UNAVAILABLE;
        }
    }

    /** The outcome of one fingerprint prompt, reported by {@link #startFingerprint} on the main
     *  thread. {@code onFpFailed} carries a {@code R.string} error resource, or -1 for a dismissal
     *  (user or system cancel) that needs no feedback. */
    interface FpCallback {
        void onFpSucceeded();
        void onFpFailed(int errorRes);
    }

    /** Opens the platform fingerprint dialog (BiometricPrompt on Android 10+, the system
     *  FingerprintManager dialog on Android 8–9). The stored challenge is only accepted once it
     *  actually decrypts with the authorized keystore key, so a verified print is genuinely
     *  required. Callbacks arrive on the main thread. Returns the cancellation signal, or null if
     *  the prompt could not be started (in which case {@code cb.onFpFailed} was already told). */
    static CancellationSignal startFingerprint(final Context c, final FpCallback cb) {
        final CancellationSignal cancel = new CancellationSignal();
        try {
            final Cipher cipher = fpDecryptCipher(c);
            if (cipher == null) {
                cb.onFpFailed(R.string.lock_error_fingerprint_unavailable);
                return null;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                final android.app.Activity activity = (android.app.Activity) c;
                final BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        boolean ok = false;
                        try {
                            ok = result != null && result.getCryptoObject() != null
                                && fpChallengeValid(c, result.getCryptoObject().getCipher());
                        } catch (Exception e) {
                            Log.w(TAG, "fingerprint challenge failed", e);
                        }
                        if (ok) cb.onFpSucceeded();
                        else cb.onFpFailed(R.string.lock_error_fingerprint_expired);
                    }

                    @Override public void onAuthenticationFailed() {
                        cb.onFpFailed(R.string.lock_error_fingerprint_failed);
                    }

                    @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                            cb.onFpFailed(-1);
                        } else {
                            cb.onFpFailed(R.string.lock_error_fingerprint_failed);
                        }
                    }
                };
                BiometricPrompt prompt;
                if (Build.VERSION.SDK_INT >= 30) {
                    prompt = new BiometricPrompt.Builder(activity)
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setTitle(activity.getString(R.string.lock_fingerprint_action))
                        .setNegativeButton(
                            activity.getString(R.string.lock_fingerprint_negative_button),
                            activity.getMainExecutor(), (d, w) -> cb.onFpFailed(-1))
                        .build();
                } else {
                    // Android 9 (API 29) only: its (Activity, Executor, callback) constructor is not
                    // part of the modern public API surface, so it is reached reflectively on this
                    // one build. Android 8–9 keep the working FingerprintManager path above instead.
                    try {
                        java.lang.reflect.Constructor<BiometricPrompt> ctor =
                            BiometricPrompt.class.getDeclaredConstructor(
                                android.app.Activity.class, java.util.concurrent.Executor.class,
                                BiometricPrompt.AuthenticationCallback.class);
                        ctor.setAccessible(true);
                        prompt = ctor.newInstance(activity, activity.getMainExecutor(), callback);
                    } catch (ReflectiveOperationException e) {
                        Log.w(TAG, "legacy BiometricPrompt constructor unavailable", e);
                        cb.onFpFailed(R.string.lock_error_fingerprint_unavailable);
                        return null;
                    }
                }
                prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),
                    cancel, activity.getMainExecutor(), callback);
            } else {
                final FingerprintManager fm = fingerprintManager(c);
                if (fm == null) {
                    cb.onFpFailed(R.string.lock_error_fingerprint_unavailable);
                    return null;
                }
                fm.authenticate(new FingerprintManager.CryptoObject(cipher), cancel, 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                FingerprintManager.AuthenticationResult result) {
                            boolean ok = false;
                            try {
                                ok = result != null && result.getCryptoObject() != null
                                    && fpChallengeValid(c, result.getCryptoObject().getCipher());
                            } catch (Exception e) {
                                Log.w(TAG, "fingerprint challenge failed", e);
                            }
                            if (ok) cb.onFpSucceeded();
                            else cb.onFpFailed(R.string.lock_error_fingerprint_expired);
                        }

                        @Override public void onAuthenticationFailed() {
                            cb.onFpFailed(R.string.lock_error_fingerprint_failed);
                        }

                        @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (errorCode == FingerprintManager.FINGERPRINT_ERROR_CANCELED
                                    || errorCode == FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED) {
                                cb.onFpFailed(-1);
                            } else {
                                cb.onFpFailed(R.string.lock_error_fingerprint_failed);
                            }
                        }
                    }, null);
            }
            return cancel;
        } catch (KeyPermanentlyInvalidatedException e) {
            cb.onFpFailed(R.string.lock_error_fingerprint_expired);
        } catch (ClassCastException e) {
            Log.w(TAG, "fingerprint requires an activity context", e);
            cb.onFpFailed(R.string.lock_error_fingerprint_unavailable);
        } catch (Exception e) {
            Log.w(TAG, "fingerprint prompt could not start", e);
            cb.onFpFailed(R.string.lock_error_fingerprint_unavailable);
        }
        return null;
    }

    /** Proves the stored challenge actually decrypts with the authorized keystore key — the step
     *  that completes the crypto operation the system dialog just authorized. */
    private static boolean fpChallengeValid(Context c, Cipher dec) {
        try {
            String challenge = prefs(c).getString(KEY_LOCK_FP_CHALLENGE, null);
            byte[] blob = Base64.decode(prefs(c).getString(KEY_LOCK_FP_CHAIN, ""), Base64.NO_WRAP);
            if (challenge == null || dec == null || blob == null || blob.length <= GCM_IV_BYTES)
                return false;
            byte[] plain = dec.doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
            return challenge.equals(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "fingerprint challenge failed", e);
            return false;
        }
    }

    /** The decrypt cipher for the stored challenge, ready to hand to the system dialog. Throws when
     *  the binding is unavailable to unlock (invalidated, key gone, or the device is locked since
     *  boot without a first unlock — the PIN/password is the fallback then). */
    private static Cipher fpDecryptCipher(Context c) throws Exception {
        String chain = prefs(c).getString(KEY_LOCK_FP_CHAIN, null);
        if (chain == null) throw new Exception("no fp binding");
        byte[] blob = Base64.decode(chain, Base64.NO_WRAP);
        if (blob == null || blob.length < GCM_IV_BYTES + GCM_TAG_BITS / 8) throw new Exception("short blob");
        SecretKey key = fpKey();
        if (key == null) throw new Exception("no fp key");
        Cipher dec = Cipher.getInstance(FP_TRANSFORM);
        dec.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES));
        return dec;
    }

    /** Creates the keystore key (auth-required, invalidated on enrollment changes) and seals a fresh
     *  random challenge with it. The plaintext challenge is kept next to the ciphertext only so the
     *  app can recognise the authentic decrypt; it is useless to anyone without the fingerprint. */
    private static boolean createFingerprintBinding(Context c) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                    FP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setInvalidatedByBiometricEnrollment(true)
                .setRandomizedEncryptionRequired(true);
            // Android 11+ binds the key to its authenticators explicitly; earlier builds flag the
            // key as user-authentication-required, which is the same strong-biometric contract.
            if (Build.VERSION.SDK_INT >= 30)
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            else
                spec.setUserAuthenticationRequired(true);
            kg.init(spec.build());
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