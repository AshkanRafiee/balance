package com.ashkanrafiee.balance;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Disposable CSV bytes encrypted with a fresh, memory-only key. Small authenticated frames keep
 * both writing and reading bounded; no plaintext file or recoverable key survives a process death.
 */
final class CsvExportStaging implements AutoCloseable {
    private static final String PREFIX = "history-export-";
    private static final int CHUNK_BYTES = 16 * 1024;
    private static final int MAX_FRAME_BYTES = 32 * 1024;
    private static final Object FILE_LOCK = new Object();
    private static final Set<String> LIVE_FILES = new HashSet<>();

    private final byte[] buffer = new byte[CHUNK_BYTES];
    private EncryptedRowCodec codec;
    private DataOutputStream encrypted;
    private File file;
    private int buffered;
    private long sequence;
    private boolean writerClosed;
    private boolean finished;
    private boolean closed;

    private final OutputStream plaintext = new OutputStream() {
        @Override public void write(int value) throws IOException {
            checkWritable();
            buffer[buffered++] = (byte) value;
            if (buffered == buffer.length) flushFrame();
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length)
                throw new IndexOutOfBoundsException();
            checkWritable();
            while (length > 0) {
                int count = Math.min(length, buffer.length - buffered);
                System.arraycopy(bytes, offset, buffer, buffered, count);
                buffered += count;
                offset += count;
                length -= count;
                if (buffered == buffer.length) flushFrame();
            }
        }

        @Override public void flush() throws IOException {
            checkWritable();
            flushFrame();
            encrypted.flush();
        }

        @Override public void close() throws IOException {
            if (writerClosed) return;
            flush();
            writerClosed = true;
        }
    };

    static CsvExportStaging open(Context context) throws Exception {
        CsvExportStaging stage = new CsvExportStaging();
        try {
            // Reuse the row codec, but never persist its wrapped key. A fresh process cannot
            // decrypt an abandoned file, even though the app's long-lived Keystore still exists.
            stage.codec = EncryptedRowCodec.open(EncryptedRowCodec.createWrappedKey(),
                "history-export-staging");
            sweepOrphans(context);
            synchronized (FILE_LOCK) {
                stage.file = File.createTempFile(PREFIX, ".enc", context.getCacheDir());
                LIVE_FILES.add(stage.file.getPath());
            }
            stage.encrypted = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(stage.file)));
            return stage;
        } catch (Exception | Error failure) {
            try {
                stage.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    OutputStream outputStream() throws IOException {
        checkWritable();
        return plaintext;
    }

    /** Commit only after serialization and source-revision checks have succeeded. */
    void finish() throws IOException {
        if (closed || finished) throw new IOException("CSV staging is not writable");
        flushFrame();
        writeFrame("END");
        encrypted.close();
        writerClosed = true;
        finished = true;
    }

    /** Validate every frame and completion marker before opening the user-selected destination. */
    void verify() throws IOException {
        copyTo(new OutputStream() {
            @Override public void write(int value) {}
            @Override public void write(byte[] bytes, int offset, int length) {}
        });
    }

    void copyTo(OutputStream destination) throws IOException {
        if (closed || !finished) throw new IOException("CSV staging is incomplete");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file)))) {
            long next = 0;
            for (;;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                int length = input.readInt();
                if (length <= 0 || length > MAX_FRAME_BYTES)
                    throw new IOException("invalid CSV staging frame");
                byte[] encoded = new byte[length];
                input.readFully(encoded);
                String frame = new String(encoded, StandardCharsets.UTF_8);
                // Never accept the codec's legacy, master-key-only fallback in an ephemeral file.
                if (!frame.startsWith(EncryptedRowCodec.PREFIX))
                    throw new IOException("invalid CSV staging encoding");
                String value = codec.decrypt(frame, identity(next++));
                if ("END".equals(value)) {
                    if (input.read() != -1) throw new IOException("trailing CSV staging bytes");
                    return;
                }
                if (!value.startsWith("DATA:")) throw new IOException("invalid CSV staging data");
                byte[] bytes = Base64.decode(value.substring(5), Base64.NO_WRAP);
                try {
                    if (bytes.length == 0 || bytes.length > CHUNK_BYTES)
                        throw new IOException("invalid CSV staging chunk");
                    destination.write(bytes);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (EOFException failure) {
            throw new IOException("incomplete CSV staging", failure);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("CSV staging authentication failed", failure);
        }
    }

    private void checkWritable() throws IOException {
        if (closed || finished || writerClosed) throw new IOException("CSV staging is not writable");
    }

    private void flushFrame() throws IOException {
        if (buffered == 0) return;
        writeFrame("DATA:" + Base64.encodeToString(buffer, 0, buffered, Base64.NO_WRAP));
        Arrays.fill(buffer, 0, buffered, (byte) 0);
        buffered = 0;
    }

    private void writeFrame(String value) throws IOException {
        try {
            byte[] encoded = codec.encrypt(value, identity(sequence++))
                .getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_FRAME_BYTES) throw new IOException("CSV frame too large");
            encrypted.writeInt(encoded.length);
            encrypted.write(encoded);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("CSV staging encryption failed", failure);
        }
    }

    private static String identity(long ordinal) throws IOException {
        if (ordinal < 0) throw new IOException("CSV staging sequence exhausted");
        return "frame:" + ordinal;
    }

    /** Remove only our own abandoned files, including plaintext staging from older versions. */
    static void sweepOrphans(Context context) throws IOException {
        synchronized (FILE_LOCK) {
            File directory = context.getCacheDir();
            File[] files = directory == null ? null : directory.listFiles();
            if (files == null) throw new IOException("CSV staging directory unavailable");
            for (File candidate : files) {
                String name = candidate.getName();
                if (!name.startsWith(PREFIX)
                        || !(name.endsWith(".csv") || name.endsWith(".enc"))
                        || !candidate.isFile() || LIVE_FILES.contains(candidate.getPath())) continue;
                if (!candidate.delete() && candidate.exists())
                    throw new IOException("abandoned CSV staging cleanup failed");
            }
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            if (encrypted != null) encrypted.close();
        } catch (IOException error) {
            failure = error;
        } finally {
            if (codec != null) codec.close();
            Arrays.fill(buffer, (byte) 0);
            synchronized (FILE_LOCK) {
                if (file != null) {
                    LIVE_FILES.remove(file.getPath());
                    if (!file.delete() && file.exists()) {
                        IOException cleanup = new IOException("CSV staging cleanup failed");
                        if (failure == null) failure = cleanup;
                        else failure.addSuppressed(cleanup);
                    }
                }
            }
        }
        if (failure != null) throw failure;
    }
}
