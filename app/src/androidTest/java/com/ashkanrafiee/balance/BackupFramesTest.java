package com.ashkanrafiee.balance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Streaming-format tests; no app database state is involved. */
@RunWith(AndroidJUnit4.class)
public class BackupFramesTest {
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();
    private static final char[] WRONG_PASSWORD = "not the password".toCharArray();

    @Test public void roundTrip_multiFrameStream_isLossless() throws Exception {
        byte[] original = patternedBytes(BackupFrames.MAX_PLAINTEXT_BYTES * 3 + 17);
        byte[] backup = encode(new ByteArrayInputStream(original), PASSWORD);

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        BackupFrames.read(new ByteArrayInputStream(backup), restored, PASSWORD);

        assertArrayEquals(original, restored.toByteArray());
    }

    @Test public void hugeFieldStream_roundTripsWithoutAnAggregateCodecLimit() throws Exception {
        final int size = BackupFrames.MAX_PLAINTEXT_BYTES * 48 + 123;
        PatternInputStream source = new PatternInputStream(size);
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        BackupFrames.write(source, backup, PASSWORD);

        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update(patternedBytes(size));
        MessageDigest actual = MessageDigest.getInstance("SHA-256");
        CountingDigestOutputStream restored = new CountingDigestOutputStream(actual);
        BackupFrames.read(new ByteArrayInputStream(backup.toByteArray()), restored, PASSWORD);

        assertEquals(size, restored.count);
        assertArrayEquals(expected.digest(), actual.digest());
    }

    @Test public void wrongPassword_isRejectedBeforePlaintextIsAccepted() throws Exception {
        byte[] backup = encode(new ByteArrayInputStream("private data".getBytes(StandardCharsets.UTF_8)),
                PASSWORD);
        ByteArrayOutputStream restored = new ByteArrayOutputStream();

        expectInvalid(() -> BackupFrames.read(
                new ByteArrayInputStream(backup), restored, WRONG_PASSWORD));
        assertEquals(0, restored.size());
    }

    @Test public void truncatedFooter_isRejected() throws Exception {
        byte[] backup = encode(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)),
                PASSWORD);
        byte[] truncated = Arrays.copyOf(backup, backup.length - 1);

        expectInvalid(() -> BackupFrames.read(
                new ByteArrayInputStream(truncated), new ByteArrayOutputStream(), PASSWORD));
    }

    @Test public void bitFlipInCiphertext_isRejected() throws Exception {
        byte[] backup = encode(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)),
                PASSWORD);
        backup[BackupFrames.HEADER_BYTES + BackupFrames.FRAME_HEADER_BYTES + 2] ^= 0x40;

        expectInvalid(() -> BackupFrames.read(
                new ByteArrayInputStream(backup), new ByteArrayOutputStream(), PASSWORD));
    }

    @Test public void reorderedFrames_areRejectedByTheAuthenticatedSequence() throws Exception {
        byte[] original = patternedBytes(BackupFrames.MAX_PLAINTEXT_BYTES * 3 + 1);
        byte[] backup = encode(new ByteArrayInputStream(original), PASSWORD);
        int first = BackupFrames.HEADER_BYTES;
        int second = nextFrame(backup, first);
        int third = nextFrame(backup, second);
        byte[] reordered = backup.clone();
        int firstLength = second - first;
        int secondLength = third - second;
        System.arraycopy(backup, second, reordered, first, secondLength);
        System.arraycopy(backup, first, reordered, first + secondLength, firstLength);

        expectInvalid(() -> BackupFrames.read(
                new ByteArrayInputStream(reordered), new ByteArrayOutputStream(), PASSWORD));
    }

    @Test public void headerBitFlip_isRejectedBecauseEveryFrameAuthenticatesTheHeader() throws Exception {
        byte[] backup = encode(new ByteArrayInputStream(new byte[]{1, 2, 3}), PASSWORD);
        backup[BackupFrames.HEADER_BYTES - 1] ^= 0x01;

        expectInvalid(() -> BackupFrames.read(
                new ByteArrayInputStream(backup), new ByteArrayOutputStream(), PASSWORD));
    }

    @Test public void trailingBytesAfterFooter_areRejected() throws Exception {
        byte[] backup = encode(new ByteArrayInputStream(new byte[]{1, 2, 3}), PASSWORD);
        byte[] trailing = Arrays.copyOf(backup, backup.length + 2);
        trailing[trailing.length - 2] = 0x55;
        trailing[trailing.length - 1] = 0x66;

        expectInvalid(() -> BackupFrames.read(
                new ByteArrayInputStream(trailing), new ByteArrayOutputStream(), PASSWORD));
    }

    @Test public void emptyStream_hasAnAuthenticatedCompletionFooter() throws Exception {
        byte[] backup = encode(new ByteArrayInputStream(new byte[0]), PASSWORD);
        ByteArrayOutputStream restored = new ByteArrayOutputStream();

        BackupFrames.read(new ByteArrayInputStream(backup), restored, PASSWORD);
        assertEquals(0, restored.size());
        assertEquals(BackupFrames.HEADER_BYTES + BackupFrames.FRAME_HEADER_BYTES
                + "COMPLETE".length() + 16, backup.length);
    }

    private static byte[] encode(InputStream source, char[] password) throws IOException {
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        BackupFrames.write(source, backup, password);
        return backup.toByteArray();
    }

    private static int nextFrame(byte[] backup, int offset) {
        int length = ((backup[offset + 1 + Long.BYTES] & 0xff) << 24)
                | ((backup[offset + 1 + Long.BYTES + 1] & 0xff) << 16)
                | ((backup[offset + 1 + Long.BYTES + 2] & 0xff) << 8)
                | (backup[offset + 1 + Long.BYTES + 3] & 0xff);
        return offset + BackupFrames.FRAME_HEADER_BYTES + length + 16;
    }

    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + (i >>> 8));
        }
        return bytes;
    }

    private static void expectInvalid(ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("corrupt or incomplete backup must be rejected");
        } catch (BackupFrames.InvalidBackupException expected) {
            // Expected rejection path.
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class PatternInputStream extends InputStream {
        private final int size;
        private int position;

        PatternInputStream(int size) {
            this.size = size;
        }

        @Override public int read() {
            if (position == size) return -1;
            return pattern(position++);
        }

        @Override public int read(byte[] bytes, int offset, int length) {
            if (position == size) return -1;
            int count = Math.min(length, size - position);
            for (int i = 0; i < count; i++) bytes[offset + i] = (byte) pattern(position++);
            return count;
        }

        private static int pattern(int position) {
            return (position * 31 + (position >>> 8)) & 0xff;
        }
    }

    private static final class CountingDigestOutputStream extends OutputStream {
        private final MessageDigest digest;
        int count;

        CountingDigestOutputStream(MessageDigest digest) {
            this.digest = digest;
        }

        @Override public void write(int value) {
            digest.update((byte) value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
            count += length;
        }
    }
}
