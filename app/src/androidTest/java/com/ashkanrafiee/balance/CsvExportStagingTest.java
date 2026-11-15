package com.ashkanrafiee.balance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class CsvExportStagingTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CsvExportStaging.sweepOrphans(context);
    }

    @After public void tearDown() throws Exception {
        CsvExportStaging.sweepOrphans(context);
    }

    @Test public void roundTrip_preservesUtf8AcrossFrames_withoutPlaintextOnDisk()
            throws Exception {
        StringBuilder csv = new StringBuilder("bank,account,note,amount\n");
        for (int i = 0; i < 3_000; i++)
            csv.append("Melli,account-canary,یادداشت-private-canary-").append(i).append(",123456\n");
        byte[] expected = csv.toString().getBytes(StandardCharsets.UTF_8);
        File staged;
        try (CsvExportStaging stage = CsvExportStaging.open(context)) {
            staged = file(stage);
            try (OutputStreamWriter writer = new OutputStreamWriter(stage.outputStream(),
                    StandardCharsets.UTF_8)) {
                writer.write(csv.toString());
            }
            stage.finish();
            assertTrue(staged.getName().endsWith(".enc"));
            String raw = new String(Files.readAllBytes(staged.toPath()),
                StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("account-canary"));
            assertFalse(raw.contains("private-canary"));
            stage.verify();
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            stage.copyTo(decoded);
            assertArrayEquals(expected, decoded.toByteArray());
        }
        assertFalse("completed staging must be deleted", staged.exists());
    }

    @Test public void emptyCsv_canBeCommittedAndRead() throws Exception {
        try (CsvExportStaging stage = CsvExportStaging.open(context)) {
            stage.finish();
            stage.verify();
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            stage.copyTo(decoded);
            assertTrue(decoded.size() == 0);
        }
    }

    @Test public void unfinishedCsv_cannotBePublished_andAbortDeletesStaging() throws Exception {
        File staged;
        try (CsvExportStaging stage = CsvExportStaging.open(context)) {
            staged = file(stage);
            stage.outputStream().write("incomplete-private-note".getBytes(StandardCharsets.UTF_8));
            stage.outputStream().close();
            reject(stage);
        }
        assertFalse("aborted staging must be deleted", staged.exists());
    }

    @Test public void corruptedFrame_isRejected() throws Exception {
        try (CsvExportStaging stage = complete(new byte[70_000])) {
            try (RandomAccessFile raw = new RandomAccessFile(file(stage), "rw")) {
                raw.seek(20);
                int value = raw.read();
                raw.seek(20);
                raw.write(value ^ 1);
            }
            reject(stage);
        }
    }

    @Test public void missingFooterAndTrailingBytes_areRejected() throws Exception {
        try (CsvExportStaging stage = complete(new byte[70_000])) {
            try (RandomAccessFile raw = new RandomAccessFile(file(stage), "rw")) {
                raw.setLength(raw.length() - 1);
            }
            reject(stage);
        }
        try (CsvExportStaging stage = complete(new byte[70_000])) {
            try (RandomAccessFile raw = new RandomAccessFile(file(stage), "rw")) {
                raw.seek(raw.length());
                raw.write(0);
            }
            reject(stage);
        }
    }

    @Test public void oversizedFrameLength_isRejectedBeforeAllocation() throws Exception {
        try (CsvExportStaging stage = complete(new byte[70_000])) {
            try (RandomAccessFile raw = new RandomAccessFile(file(stage), "rw")) {
                raw.writeInt(Integer.MAX_VALUE);
            }
            reject(stage);
        }
    }

    @Test public void persistentKeyLegacyCiphertext_isNeverAcceptedAsEphemeralStaging()
            throws Exception {
        try (CsvExportStaging stage = complete(new byte[0])) {
            byte[] legacy = BalanceData.encryptStorePayload("END")
                .getBytes(StandardCharsets.UTF_8);
            try (DataOutputStream raw = new DataOutputStream(new FileOutputStream(file(stage)))) {
                raw.writeInt(legacy.length);
                raw.write(legacy);
            }
            reject(stage);
        }
    }

    @Test public void interruptedCopy_exposesNoBytes_andCloseDeletesStaging() throws Exception {
        File staged;
        try (CsvExportStaging stage = complete(new byte[70_000])) {
            staged = file(stage);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            Thread.currentThread().interrupt();
            try {
                stage.copyTo(decoded);
                fail("interrupted staging copy was accepted");
            } catch (InterruptedIOException expected) {
                assertTrue(decoded.size() == 0);
            } finally {
                Thread.interrupted();
            }
        }
        assertFalse(staged.exists());
    }

    @Test public void reorderedFrames_areRejected() throws Exception {
        byte[] contents = new byte[70_000];
        Arrays.fill(contents, (byte) 'x');
        try (CsvExportStaging stage = complete(contents)) {
            File file = file(stage);
            byte[] encoded = Files.readAllBytes(file.toPath());
            int first;
            int second;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
                first = Integer.BYTES + input.readInt();
                input.skipBytes(first - Integer.BYTES);
                second = Integer.BYTES + input.readInt();
            }
            ByteArrayOutputStream reordered = new ByteArrayOutputStream();
            reordered.write(encoded, first, second);
            reordered.write(encoded, 0, first);
            reordered.write(encoded, first + second, encoded.length - first - second);
            Files.write(file.toPath(), reordered.toByteArray());
            reject(stage);
        }
    }

    @Test public void anotherExportCannotDecryptAbandonedCiphertext() throws Exception {
        try (CsvExportStaging first = complete("private-note".getBytes(StandardCharsets.UTF_8));
                CsvExportStaging second = complete(new byte[0])) {
            Files.write(file(second).toPath(), Files.readAllBytes(file(first).toPath()));
            reject(second);
            first.verify();
        }
    }

    @Test public void sweep_removesLegacyAndEncryptedOrphans_butPreservesLiveAndUnrelatedFiles()
            throws Exception {
        File unrelated = File.createTempFile("kept-export-", ".csv", context.getCacheDir());
        try (CsvExportStaging stage = complete("still-live".getBytes(StandardCharsets.UTF_8))) {
            File legacy = File.createTempFile("history-export-", ".csv", context.getCacheDir());
            File orphan = File.createTempFile("history-export-", ".enc", context.getCacheDir());
            Files.write(legacy.toPath(), "legacy-private-note".getBytes(StandardCharsets.UTF_8));
            CsvExportStaging.sweepOrphans(context);
            assertFalse(legacy.exists());
            assertFalse(orphan.exists());
            assertTrue(unrelated.exists());
            assertTrue(file(stage).exists());
            stage.verify();
        } finally {
            unrelated.delete();
        }
    }

    @Test public void reset_removesLegacyCsv_butDoesNotDeleteLiveEncryptedExport()
            throws Exception {
        try (CsvExportStaging stage = complete("still-live".getBytes(StandardCharsets.UTF_8))) {
            File legacy = File.createTempFile("history-export-", ".csv", context.getCacheDir());
            Files.write(legacy.toPath(), "legacy-private-note".getBytes(StandardCharsets.UTF_8));
            BalanceData.reset(context, true);
            assertFalse(legacy.exists());
            assertTrue(file(stage).exists());
            stage.verify();
        }
    }

    @Test public void warmup_sweepsLegacyCsvEvenWhenReusingCachedHistory() throws Exception {
        HistoryWarmup.invalidate();
        File legacy = null;
        try {
            HistoryWarmup.warmNow(context);
            legacy = File.createTempFile("history-export-", ".csv", context.getCacheDir());
            Files.write(legacy.toPath(), "legacy-private-note".getBytes(StandardCharsets.UTF_8));
            HistoryWarmup.warmNow(context);
            assertFalse(legacy.exists());
        } finally {
            HistoryWarmup.invalidate();
            if (legacy != null) legacy.delete();
        }
    }

    private CsvExportStaging complete(byte[] contents) throws Exception {
        CsvExportStaging stage = CsvExportStaging.open(context);
        try {
            stage.outputStream().write(contents);
            stage.outputStream().close();
            stage.finish();
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

    private static File file(CsvExportStaging stage) throws Exception {
        java.lang.reflect.Field field = CsvExportStaging.class.getDeclaredField("file");
        field.setAccessible(true);
        return (File) field.get(stage);
    }

    private static void reject(CsvExportStaging stage) throws Exception {
        try {
            stage.verify();
            fail("incomplete or unauthenticated CSV staging was accepted");
        } catch (IOException expected) {
            // Failure must be surfaced rather than turning a partial file into a successful export.
        }
    }
}
