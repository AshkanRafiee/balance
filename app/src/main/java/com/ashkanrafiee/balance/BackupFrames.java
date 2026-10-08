package com.ashkanrafiee.balance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A streaming, password-encrypted backup container.
 *
 * <p>The container carries an unstructured byte stream. Data is divided into independently
 * authenticated AES-GCM frames, so callers can serialize arbitrarily large fields without first
 * building the complete backup in memory. The reader writes to the supplied staging stream while
 * it verifies frames; callers must publish that staged data only after {@link #read} returns.
 *
 * <p>The header, frame type, frame length, random IV, and sequence number are authenticated as
 * associated data for every frame. The final frame is an authenticated completion footer. A read
 * therefore fails for a wrong password, changed header or frame, reordered frames, truncation, or
 * any byte after the footer.
 */
public final class BackupFrames {
    /** PBKDF2 work factor used by this format. It is deliberately not configurable by the file. */
    public static final int PBKDF2_ITERATIONS = 600_000;

    /** Maximum plaintext held for one frame. The total stream has no corresponding limit. */
    public static final int MAX_PLAINTEXT_BYTES = 64 * 1024;

    private static final byte[] MAGIC = "BALFRM01".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int DATA_FRAME = 1;
    private static final int FOOTER_FRAME = 2;
    private static final byte[] COMPLETION = "COMPLETE".getBytes(StandardCharsets.US_ASCII);

    /* Package-visible format sizes are useful to same-package format tests without making the
     * wire layout part of the public integration API. */
    static final int HEADER_BYTES = MAGIC.length + 1 + 4 + 2 + 2 + 1 + 4 + SALT_BYTES;
    static final int FRAME_HEADER_BYTES = 1 + Long.BYTES + Integer.BYTES + IV_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupFrames() {}

    /**
     * Encrypts all bytes from {@code plaintext} into {@code backup}.
     *
     * <p>Neither stream is closed. The destination is flushed only after the authenticated footer
     * has been written. If the source or destination fails, the output is intentionally left
     * without a completion footer and cannot be restored.
     *
     * @param plaintext source byte stream, consumed until EOF
     * @param backup destination backup stream
     * @param password password characters; an empty password is allowed
     */
    public static void write(InputStream plaintext, OutputStream backup, char[] password)
            throws IOException {
        requireArguments(plaintext, backup, password);

        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] header = buildHeader(salt);
        SecretKey key;
        try {
            key = deriveKey(password, salt);
        } catch (GeneralSecurityException e) {
            Arrays.fill(header, (byte) 0);
            throw new IOException("Unable to initialize backup encryption", e);
        } finally {
            Arrays.fill(salt, (byte) 0);
        }

        DataOutputStream out = new DataOutputStream(backup);
        byte[] plaintextFrame = new byte[MAX_PLAINTEXT_BYTES];
        long sequence = 0;
        try {
            out.write(header);
            Cipher cipher = cipher();

            for (;;) {
                int count = readChunk(plaintext, plaintextFrame);
                if (count < 0) break;
                if (count == 0) continue;
                writeFrame(out, cipher, key, header, DATA_FRAME, sequence++, plaintextFrame, count);
            }

            // The footer is a frame rather than an unencrypted marker, so it authenticates both
            // the complete stream and the header even when the stream contains no data frames.
            writeFrame(out, cipher, key, header, FOOTER_FRAME, sequence++, COMPLETION,
                    COMPLETION.length);
            out.flush();
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to encrypt backup", e);
        } finally {
            Arrays.fill(plaintextFrame, (byte) 0);
            Arrays.fill(header, (byte) 0);
        }
    }

    /**
     * Authenticates and decrypts {@code backup} into {@code plaintext}.
     *
     * <p>Neither stream is closed. Plaintext may already have been written when a later frame,
     * the completion footer, or the EOF check fails. The destination must consequently be a
     * disposable staging stream; publish it only after this method returns successfully.
     *
     * @throws InvalidBackupException if the password is wrong, the format is invalid, a frame is
     *         reordered or damaged, the footer is missing, or trailing bytes are present
     */
    public static void read(InputStream backup, OutputStream plaintext, char[] password)
            throws IOException {
        requireArguments(backup, plaintext, password);

        DataInputStream in = new DataInputStream(backup);
        byte[] header = readHeader(in);
        byte[] salt = parseAndCopySalt(header);
        SecretKey key;
        try {
            key = deriveKey(password, salt);
        } catch (GeneralSecurityException e) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(header, (byte) 0);
            throw new InvalidBackupException("Unable to initialize backup encryption", e);
        } finally {
            Arrays.fill(salt, (byte) 0);
        }

        long expectedSequence = 0;
        boolean footerSeen = false;
        try {
            Cipher cipher = cipher();
            byte[] frameHeader = new byte[FRAME_HEADER_BYTES];
            for (;;) {
                int first = in.read();
                if (first < 0) {
                    throw new InvalidBackupException("Backup is missing its completion footer");
                }
                frameHeader[0] = (byte) first;
                readFully(in, frameHeader, 1, FRAME_HEADER_BYTES - 1,
                        "Truncated backup frame header");

                int type = frameHeader[0] & 0xff;
                long sequence = readLong(frameHeader, 1);
                int length = readInt(frameHeader, 1 + Long.BYTES);
                if (sequence != expectedSequence) {
                    throw new InvalidBackupException("Backup frame sequence is invalid");
                }
                if (type != DATA_FRAME && type != FOOTER_FRAME) {
                    throw new InvalidBackupException("Unknown backup frame type");
                }
                if (type == DATA_FRAME) {
                    if (length <= 0 || length > MAX_PLAINTEXT_BYTES) {
                        throw new InvalidBackupException("Backup frame is too large");
                    }
                } else if (length != COMPLETION.length) {
                    throw new InvalidBackupException("Invalid backup completion footer");
                }

                byte[] ciphertext = new byte[length + TAG_BYTES];
                readFully(in, ciphertext, 0, ciphertext.length, "Truncated backup frame");
                byte[] cleartext = decryptFrame(cipher, key, header, frameHeader, ciphertext);

                if (type == DATA_FRAME) {
                    plaintext.write(cleartext);
                } else {
                    if (!MessageDigest.isEqual(cleartext, COMPLETION)) {
                        throw new InvalidBackupException("Invalid backup completion footer");
                    }
                    footerSeen = true;
                    Arrays.fill(cleartext, (byte) 0);
                    Arrays.fill(ciphertext, (byte) 0);
                    break;
                }
                Arrays.fill(cleartext, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
                expectedSequence++;
            }

            if (!footerSeen) {
                throw new InvalidBackupException("Backup is missing its completion footer");
            }
            if (in.read() != -1) {
                throw new InvalidBackupException("Backup has trailing bytes");
            }
            plaintext.flush();
        } catch (GeneralSecurityException e) {
            throw new InvalidBackupException("Backup authentication failed", e);
        } finally {
            Arrays.fill(header, (byte) 0);
        }
    }

    /** A format/authentication failure that must prevent a staged restore from being published. */
    public static final class InvalidBackupException extends IOException {
        InvalidBackupException(String message) {
            super(message);
        }

        InvalidBackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void requireArguments(InputStream input, OutputStream output, char[] password) {
        if (input == null) throw new IllegalArgumentException("input stream is null");
        if (output == null) throw new IllegalArgumentException("output stream is null");
        if (password == null) throw new IllegalArgumentException("password is null");
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int count = input.read(buffer, 0, buffer.length);
        if (count < 0) return -1;
        if (count > 0) return count;
        // InputStream permits a zero result for some wrappers. Probe one byte so a broken wrapper
        // cannot make a writer spin while still preserving a frame boundary.
        int one = input.read();
        if (one < 0) return -1;
        buffer[0] = (byte) one;
        return 1;
    }

    private static byte[] buildHeader(byte[] salt) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeByte(FORMAT_VERSION);
        out.writeInt(PBKDF2_ITERATIONS);
        out.writeShort(KEY_BITS);
        out.writeShort(TAG_BITS);
        out.writeByte(SALT_BYTES);
        out.writeInt(MAX_PLAINTEXT_BYTES);
        out.write(salt);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] readHeader(DataInputStream in) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        readFully(in, header, 0, header.length, "Truncated backup header");
        return header;
    }

    private static byte[] parseAndCopySalt(byte[] header) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(header));
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)
                    || in.readUnsignedByte() != FORMAT_VERSION
                    || in.readInt() != PBKDF2_ITERATIONS
                    || in.readUnsignedShort() != KEY_BITS
                    || in.readUnsignedShort() != TAG_BITS
                    || in.readUnsignedByte() != SALT_BYTES
                    || in.readInt() != MAX_PLAINTEXT_BYTES) {
                throw new InvalidBackupException("Unsupported backup header");
            }
            byte[] salt = new byte[SALT_BYTES];
            in.readFully(salt);
            if (in.available() != 0) {
                throw new InvalidBackupException("Invalid backup header length");
            }
            return salt;
        } catch (EOFException e) {
            throw new InvalidBackupException("Truncated backup header", e);
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(encoded, "AES");
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static Cipher cipher() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }

    private static void writeFrame(DataOutputStream out, Cipher cipher, SecretKey key,
            byte[] header, int type, long sequence, byte[] cleartext, int length)
            throws IOException, GeneralSecurityException {
        byte[] frameHeader = new byte[FRAME_HEADER_BYTES];
        ByteArrayOutputStream frameBytes = new ByteArrayOutputStream(FRAME_HEADER_BYTES);
        DataOutputStream frameOut = new DataOutputStream(frameBytes);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        frameOut.writeByte(type);
        frameOut.writeLong(sequence);
        frameOut.writeInt(length);
        frameOut.write(iv);
        frameOut.flush();
        byte[] madeHeader = frameBytes.toByteArray();
        System.arraycopy(madeHeader, 0, frameHeader, 0, FRAME_HEADER_BYTES);

        byte[] ciphertext;
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header);
            cipher.updateAAD(frameHeader);
            ciphertext = cipher.doFinal(cleartext, 0, length);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(madeHeader, (byte) 0);
        }
        out.write(frameHeader);
        out.write(ciphertext);
        Arrays.fill(frameHeader, (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
    }

    private static byte[] decryptFrame(Cipher cipher, SecretKey key, byte[] header,
            byte[] frameHeader, byte[] ciphertext)
            throws GeneralSecurityException, IOException {
        byte[] iv = Arrays.copyOfRange(frameHeader, 1 + Long.BYTES + Integer.BYTES,
                FRAME_HEADER_BYTES);
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header);
            cipher.updateAAD(frameHeader);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw e;
        } finally {
            Arrays.fill(iv, (byte) 0);
        }
    }

    private static void readFully(DataInputStream in, byte[] bytes, int offset, int length,
            String message) throws IOException {
        int done = 0;
        while (done < length) {
            try {
                int count = in.read(bytes, offset + done, length - done);
                if (count < 0) throw new InvalidBackupException(message);
                if (count == 0) {
                    int one = in.read();
                    if (one < 0) throw new InvalidBackupException(message);
                    bytes[offset + done++] = (byte) one;
                } else {
                    done += count;
                }
            } catch (EOFException e) {
                throw new InvalidBackupException(message, e);
            }
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static long readLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 56)
                | ((long) (bytes[offset + 1] & 0xff) << 48)
                | ((long) (bytes[offset + 2] & 0xff) << 40)
                | ((long) (bytes[offset + 3] & 0xff) << 32)
                | ((long) (bytes[offset + 4] & 0xff) << 24)
                | ((long) (bytes[offset + 5] & 0xff) << 16)
                | ((long) (bytes[offset + 6] & 0xff) << 8)
                | ((long) (bytes[offset + 7] & 0xff));
    }
}
