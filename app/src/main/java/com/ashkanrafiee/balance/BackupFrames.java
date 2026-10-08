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
     * Synchronously produces plaintext into a backup. Returning normally is required for the
     * completion footer to be written.
     */
    @FunctionalInterface
    public interface PlaintextProducer {
        void produce(OutputStream plaintext) throws IOException;
    }

    /**
     * Opens a bounded-memory plaintext writer. The returned stream writes encrypted frames as it
     * is written to; it never starts a thread or creates an intermediate pipe.
     *
     * <p>{@link BackupOutputStream#finish()} is the commit operation. Closing an unfinished
     * stream aborts it and deliberately does not write a completion footer. This makes a producer
     * exception unable to turn a partial JSON document into a restorable backup.
     */
    public static BackupOutputStream openOutputStream(OutputStream backup, char[] password)
            throws IOException {
        if (backup == null) throw new IllegalArgumentException("output stream is null");
        if (password == null) throw new IllegalArgumentException("password is null");
        return new BackupOutputStream(backup, password);
    }

    /**
     * Opens an authenticated plaintext stream. A read returning {@code -1} means the completion
     * footer has been authenticated and the physical backup has been checked for trailing bytes.
     * Call {@link AuthenticatedInputStream#finish()} (or close the stream) when the JSON consumer
     * stops before reading plaintext EOF.
     */
    public static AuthenticatedInputStream openInputStream(InputStream backup, char[] password)
            throws IOException {
        if (backup == null) throw new IllegalArgumentException("input stream is null");
        if (password == null) throw new IllegalArgumentException("password is null");
        return new AuthenticatedInputStream(backup, password);
    }

    /**
     * Runs a synchronous producer and commits the backup only when it returns normally.
     * Neither the producer's stream nor {@code backup} is closed.
     */
    public static void write(PlaintextProducer producer, OutputStream backup, char[] password)
            throws IOException {
        if (producer == null) throw new IllegalArgumentException("producer is null");
        BackupOutputStream out = openOutputStream(backup, password);
        try {
            // Keep close ownership with this method. JSON writers commonly close the stream they
            // wrap, and that must not commit or abort before the producer has returned.
            producer.produce(out.producerStream());
            out.finish();
        } catch (IOException | RuntimeException | Error e) {
            out.abort();
            throw e;
        } finally {
            out.close();
        }
    }

    /** Same producer API with the destination first, for call sites that build the destination first. */
    public static void write(OutputStream backup, char[] password, PlaintextProducer producer)
            throws IOException {
        write(producer, backup, password);
    }

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

        BackupOutputStream out = openOutputStream(backup, password);
        byte[] buffer = new byte[MAX_PLAINTEXT_BYTES];
        try {
            for (;;) {
                int count = readChunk(plaintext, buffer);
                if (count < 0) break;
                if (count == 0) continue;
                out.write(buffer, 0, count);
            }
            out.finish();
        } catch (IOException | RuntimeException | Error e) {
            out.abort();
            throw e;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            out.close();
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

        AuthenticatedInputStream in = openInputStream(backup, password);
        byte[] buffer = new byte[MAX_PLAINTEXT_BYTES];
        Throwable failure = null;
        try {
            int count;
            while ((count = in.read(buffer, 0, buffer.length)) != -1) {
                plaintext.write(buffer, 0, count);
            }
            // read() returning EOF already performs this check; keep finish explicit so this
            // method remains correct if the stream implementation changes its read granularity.
            in.finish();
            plaintext.flush();
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            try {
                in.close();
            } catch (IOException closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    /**
     * Plaintext output stream for a backup under construction.
     *
     * <p>Data is retained only until one frame is full (at most 64 KiB), then encrypted and sent
     * to the destination. {@link #finish()} is intentionally separate from {@link #close()}:
     * close aborts an unfinished document, while finish authenticates completion and flushes the
     * destination. The destination stream is never closed.
     */
    public static final class BackupOutputStream extends OutputStream {
        private final OutputStream backup;
        private byte[] header;
        private SecretKey key;
        private Cipher cipher;
        private final byte[] plaintextFrame = new byte[MAX_PLAINTEXT_BYTES];
        private final byte[] singleByte = new byte[1];
        private int buffered;
        private long sequence;
        private boolean finished;
        private boolean closed;
        private IOException failure;

        private BackupOutputStream(OutputStream backup, char[] password) throws IOException {
            this.backup = backup;

            byte[] salt = new byte[SALT_BYTES];
            byte[] madeHeader = null;
            SecretKey madeKey = null;
            Cipher madeCipher = null;
            boolean adopted = false;
            try {
                RANDOM.nextBytes(salt);
                madeHeader = buildHeader(salt);
                madeKey = deriveKey(password, salt);
                madeCipher = cipher();
                backup.write(madeHeader);
                this.header = madeHeader;
                this.key = madeKey;
                this.cipher = madeCipher;
                adopted = true;
            } catch (GeneralSecurityException e) {
                throw new IOException("Unable to initialize backup encryption", e);
            } finally {
                Arrays.fill(salt, (byte) 0);
                if (!adopted && madeHeader != null) {
                    Arrays.fill(madeHeader, (byte) 0);
                }
            }
        }

        /**
         * Completes the backup. This method is idempotent after a successful completion.
         */
        public void finish() throws IOException {
            ensureOpen();
            if (finished) return;
            try {
                flushFrame();
                // The footer is an encrypted frame, not an unauthenticated marker. It binds the
                // complete sequence and header even when no data was written.
                writeFrame(backup, cipher, key, header, FOOTER_FRAME, sequence++, COMPLETION,
                        COMPLETION.length);
                backup.flush();
                finished = true;
                wipeCryptoState();
            } catch (GeneralSecurityException e) {
                fail(new IOException("Unable to encrypt backup", e));
                throw failure;
            } catch (IOException e) {
                fail(e);
                throw e;
            }
        }

        /**
         * Aborts this writer without writing a completion footer. It is safe to call repeatedly.
         */
        public void abort() {
            cleanup();
        }

        @Override public void write(int value) throws IOException {
            singleByte[0] = (byte) value;
            write(singleByte, 0, 1);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureWritable();
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }

            int position = offset;
            int remaining = length;
            while (remaining > 0) {
                int count = Math.min(remaining, MAX_PLAINTEXT_BYTES - buffered);
                System.arraycopy(bytes, position, plaintextFrame, buffered, count);
                buffered += count;
                position += count;
                remaining -= count;
                if (buffered == MAX_PLAINTEXT_BYTES) flushFrame();
            }
        }

        @Override public void flush() throws IOException {
            ensureOpen();
            try {
                flushFrame();
                backup.flush();
            } catch (IOException e) {
                fail(e);
                throw e;
            }
        }

        /**
         * Aborts an unfinished writer. It never closes the destination and never invents a footer
         * after a producer has failed.
         */
        @Override public void close() {
            if (closed) return;
            cleanup();
        }

        private void flushFrame() throws IOException {
            if (buffered == 0) return;
            try {
                writeFrame(backup, cipher, key, header, DATA_FRAME, sequence++, plaintextFrame,
                        buffered);
            } catch (GeneralSecurityException e) {
                fail(new IOException("Unable to encrypt backup", e));
                throw failure;
            } catch (IOException e) {
                fail(e);
                throw e;
            } finally {
                Arrays.fill(plaintextFrame, (byte) 0);
                buffered = 0;
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("Backup output stream is closed");
            if (failure != null) throw failure;
        }

        private void ensureWritable() throws IOException {
            ensureOpen();
            if (finished) throw new IOException("Backup output stream is finished");
        }

        private void fail(IOException exception) {
            failure = exception;
            cleanup();
        }

        private void cleanup() {
            Arrays.fill(plaintextFrame, (byte) 0);
            Arrays.fill(singleByte, (byte) 0);
            buffered = 0;
            wipeCryptoState();
            closed = true;
        }

        private void wipeCryptoState() {
            if (header != null) Arrays.fill(header, (byte) 0);
            header = null;
            key = null;
            cipher = null;
        }

        private OutputStream producerStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    BackupOutputStream.this.write(value);
                }

                @Override public void write(byte[] bytes, int offset, int length)
                        throws IOException {
                    BackupOutputStream.this.write(bytes, offset, length);
                }

                @Override public void flush() throws IOException {
                    BackupOutputStream.this.flush();
                }

                @Override public void close() {
                    // The enclosing producer method decides whether to commit or abort.
                }
            };
        }
    }

    /**
     * Authenticated plaintext input stream for staged restore.
     *
     * <p>Plaintext is intentionally released before the later footer is checked. The caller must
     * therefore write it to disposable staging storage and publish that storage only after
     * {@link #finish()} or an EOF return from {@link #read(byte[], int, int)}. Calling close on a
     * partially consumed stream drains and validates it; a validation failure is reported by
     * close. The encrypted source is never closed.
     */
    public static final class AuthenticatedInputStream extends InputStream {
        private final DataInputStream in;
        private byte[] header;
        private SecretKey key;
        private Cipher cipher;
        private final byte[] singleByte = new byte[1];
        private byte[] framePlaintext;
        private int framePosition;
        private int frameLength;
        private long expectedSequence;
        private boolean finished;
        private boolean closed;
        private IOException failure;

        private AuthenticatedInputStream(InputStream backup, char[] password) throws IOException {
            DataInputStream madeIn = new DataInputStream(backup);
            byte[] madeHeader = readHeader(madeIn);
            try {
                byte[] salt = parseAndCopySalt(madeHeader);
                SecretKey madeKey;
                Cipher madeCipher;
                try {
                    madeKey = deriveKey(password, salt);
                    madeCipher = cipher();
                } catch (GeneralSecurityException e) {
                    throw new InvalidBackupException("Unable to initialize backup encryption", e);
                } finally {
                    Arrays.fill(salt, (byte) 0);
                }
                this.in = madeIn;
                this.header = madeHeader;
                this.key = madeKey;
                this.cipher = madeCipher;
            } catch (IOException e) {
                Arrays.fill(madeHeader, (byte) 0);
                throw e;
            }
        }

        /**
         * Reads and discards any remaining plaintext, then requires authenticated footer and
         * physical EOF. It uses a fixed-size discard buffer and is safe to call repeatedly after
         * successful completion.
         */
        public void finish() throws IOException {
            ensureOpen();
            if (finished) return;

            byte[] discard = new byte[8 * 1024];
            try {
                while (read(discard, 0, discard.length) != -1) {
                    // Deliberately discard staged plaintext that the JSON reader did not consume.
                }
            } finally {
                Arrays.fill(discard, (byte) 0);
            }
        }

        /** Alias for finish, useful at restore call sites that describe this operation as draining. */
        public void drain() throws IOException {
            finish();
        }

        @Override public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : singleByte[0] & 0xff;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (finished) return -1;

            try {
                if (framePosition == frameLength) {
                    clearFrame();
                    if (!readNextFrame()) return -1;
                }
                int count = Math.min(length, frameLength - framePosition);
                System.arraycopy(framePlaintext, framePosition, bytes, offset, count);
                framePosition += count;
                return count;
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }

        /**
         * Drains and validates an unfinished source. The source supplied to this class is not
         * closed, because ownership remains with the caller.
         */
        @Override public void close() throws IOException {
            if (closed) return;
            IOException validationFailure = null;
            try {
                if (failure == null && !finished) finish();
            } catch (IOException e) {
                validationFailure = e;
            } finally {
                Arrays.fill(singleByte, (byte) 0);
                clearFrame();
                wipeCryptoState();
                closed = true;
            }
            if (validationFailure != null) throw validationFailure;
        }

        private boolean readNextFrame() throws IOException {
            byte[] frameHeader = new byte[FRAME_HEADER_BYTES];
            byte[] ciphertext = null;
            byte[] cleartext = null;
            try {
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

                ciphertext = new byte[length + TAG_BYTES];
                readFully(in, ciphertext, 0, ciphertext.length, "Truncated backup frame");
                try {
                    cleartext = decryptFrame(cipher, key, header, frameHeader, ciphertext);
                } catch (GeneralSecurityException e) {
                    throw new InvalidBackupException("Backup authentication failed", e);
                }

                if (type == DATA_FRAME) {
                    framePlaintext = cleartext;
                    cleartext = null;
                    framePosition = 0;
                    frameLength = framePlaintext.length;
                    expectedSequence++;
                    return true;
                }

                if (!MessageDigest.isEqual(cleartext, COMPLETION)) {
                    throw new InvalidBackupException("Invalid backup completion footer");
                }
                // A successful plaintext EOF is stronger than seeing a footer: no bytes may follow
                // it, even if those bytes would otherwise be ignored by a JSON consumer.
                if (in.read() != -1) {
                    throw new InvalidBackupException("Backup has trailing bytes");
                }
                finished = true;
                wipeCryptoState();
                return false;
            } finally {
                Arrays.fill(frameHeader, (byte) 0);
                if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
                if (cleartext != null) Arrays.fill(cleartext, (byte) 0);
            }
        }

        private void clearFrame() {
            if (framePlaintext != null) Arrays.fill(framePlaintext, (byte) 0);
            framePlaintext = null;
            framePosition = 0;
            frameLength = 0;
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("Backup input stream is closed");
            if (failure != null) throw failure;
        }

        private void wipeCryptoState() {
            if (header != null) Arrays.fill(header, (byte) 0);
            header = null;
            key = null;
            cipher = null;
        }
    }

    /** A format/authentication failure that must prevent a staged restore from being published. */
    public static final class InvalidBackupException extends IOException {
        private static final long serialVersionUID = 1L;

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
        try {
            readFully(in, header, 0, header.length, "Truncated backup header");
            return header;
        } catch (IOException e) {
            Arrays.fill(header, (byte) 0);
            throw e;
        }
    }

    private static byte[] parseAndCopySalt(byte[] header) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(header));
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
        } finally {
            Arrays.fill(magic, (byte) 0);
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

    private static void writeFrame(OutputStream out, Cipher cipher, SecretKey key,
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

        byte[] ciphertext = null;
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header);
            cipher.updateAAD(frameHeader);
            ciphertext = cipher.doFinal(cleartext, 0, length);
            out.write(frameHeader);
            out.write(ciphertext);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(madeHeader, (byte) 0);
            Arrays.fill(frameHeader, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
        }
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
