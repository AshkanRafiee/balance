package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies that production v2 creation uses authenticated frames and streams every section. */
@RunWith(AndroidJUnit4.class)
public class BackupV2Test {
    private Context context;
    private File file;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        file = new File(context.getCacheDir(), "backup-v2-test.bin");
        file.delete();
    }

    @After public void tearDown() {
        if (file != null) file.delete();
    }

    @Test public void framedCreationStreamsAllRetainedSections() throws Exception {
        BalanceData.write(context, new java.util.LinkedHashMap<String, Bank>() {{
            put("Synthetic", new Bank("Synthetic", 123L, 456L, "sender", "account"));
        }});
        BalanceData.writeTransactions(context, Arrays.asList(
            new Transaction("Synthetic", "account", 1L, -2L, 3L, "sig", "content")));
        Transaction transaction = BalanceData.readTransactions(context).get(0);
        BalanceData.setNote(context, transaction, "note");
        BalanceData.mergeReasons(context,
            java.util.Collections.singletonMap(BalanceData.noteKey(transaction), "reason"));
        BalanceData.mergeChannels(context,
            java.util.Collections.singletonMap(BalanceData.noteKey(transaction), "channel"));
        BalanceData.setTags(context, transaction, Arrays.asList("tag"));
        Commitment commitment = Commitment.create("rent", -100L, Commitment.MONTHLY,
            Commitment.startOfDay(System.currentTimeMillis()), null, false, 0);
        BalanceData.writeCommitments(context, Arrays.asList(commitment));

        BackupManager.createFramed(context, Uri.fromFile(file), "password");
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file)) {
            bytes = new byte[8];
            assertEquals(8, in.read(bytes));
        }
        assertEquals("BALFRM01", new String(bytes, StandardCharsets.US_ASCII));

        int balances = 0, transactions = 0, notes = 0, reasons = 0, channels = 0, tags = 0,
            commitments = 0;
        try (FileInputStream raw = new FileInputStream(file);
                BackupFrames.AuthenticatedInputStream decrypted = BackupFrames.openInputStream(raw,
                    "password".toCharArray());
                JsonReader json = new JsonReader(new InputStreamReader(decrypted,
                    StandardCharsets.UTF_8))) {
            json.beginObject();
            assertEquals("schema", json.nextName());
            assertEquals(2, json.nextInt());
            while (json.hasNext()) {
                String name = json.nextName();
                switch (name) {
                    case "balances": balances = countObjects(json); break;
                    case "transactions": transactions = countObjects(json); break;
                    case "commitments": commitments = countObjects(json); break;
                    case "metadata":
                        json.beginObject();
                        while (json.hasNext()) {
                            String kind = json.nextName();
                            int count = countObjects(json);
                            if ("notes".equals(kind)) notes = count;
                            else if ("reasons".equals(kind)) reasons = count;
                            else if ("channels".equals(kind)) channels = count;
                            else if ("tags".equals(kind)) tags = count;
                        }
                        json.endObject();
                        break;
                    default: json.skipValue();
                }
            }
            json.endObject();
        }
        assertEquals(1, balances);
        assertEquals(1, transactions);
        assertEquals(1, notes);
        assertEquals(1, reasons);
        assertEquals(1, channels);
        assertEquals(1, tags);
        assertEquals(1, commitments);
        assertTrue(file.length() > 0);
    }

    private static int countObjects(JsonReader json) throws Exception {
        int count = 0;
        json.beginArray();
        while (json.hasNext()) {
            json.beginObject();
            while (json.hasNext()) {
                json.nextName();
                json.skipValue();
            }
            json.endObject();
            count++;
        }
        json.endArray();
        return count;
    }
}
