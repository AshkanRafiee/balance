package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The half-written rule a reader left behind.
 *
 * <p>These run on a device keystore on purpose: the point of the store is that the draft is sealed
 * with a key the device will not hand to anyone else, and a test against a stub cipher would say
 * nothing about that. They also read the file back off disk rather than through the store, because
 * "it comes back the same" is a weaker claim than "what is on this device is not the message".
 */
public class RuleDraftStoreTest {
    private static final String BODY = "برداشت ۱۲۰,۰۰۰ ریال\nمانده حساب: 4,500,000 ریال";
    private static final String SENDER = "+982000320000";

    private RuleDraftStore store;
    private File file;

    @Before
    public void open() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new RuleDraftStore(context);
        store.clear();
        file = new File(context.getNoBackupFilesDir(), "draft.bin");
    }

    private static Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sender", SENDER);
        state.put("body", BODY);
        state.put("shape", "MOVEMENT");
        state.put("hasDate", true);
        state.put("exclusions", List.of("مانده حساب"));
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("AMOUNT", List.of(6, 13));
        selected.put("BALANCE", List.of(27, 36));
        state.put("selected", selected);
        return state;
    }

    @Test
    public void writesNothingUntilThereIsADraft() {
        assertFalse(store.present());
        assertNull(store.read());
    }

    @Test
    public void keepsWhatItWrote() {
        assertTrue(store.write(state()));
        assertTrue(store.present());
        Map<String, Object> read = store.read();
        assertNotNull(read);
        assertEquals(SENDER, read.get("sender"));
        assertEquals(BODY, read.get("body"));
        assertEquals("MOVEMENT", read.get("shape"));
        assertEquals(true, read.get("hasDate"));
        assertEquals(List.of("مانده حساب"), read.get("exclusions"));
    }

    @Test
    public void keepsTheMessageOffTheDisk() throws Exception {
        assertTrue(store.write(state()));
        byte[] raw = new byte[(int) file.length()];
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            handle.readFully(raw);
        }
        // The body is Persian and the sender a number: neither may appear anywhere in the file.
        assertFalse(new String(raw, StandardCharsets.UTF_8).contains(SENDER));
        assertFalse(new String(raw, StandardCharsets.UTF_8).contains("۱۲۰,۰۰۰"));
    }

    @Test
    public void holdsTheFileInNoBackupStorage() {
        assertTrue(store.write(state()));
        // A draft on a device that leaves with its reader through a cloud backup is a draft on
        // someone else's server, and one the app has no way to revoke.
        assertEquals(
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getNoBackupFilesDir().getAbsolutePath(),
                file.getParent());
    }

    @Test
    public void replacesTheDraftWholesale() {
        assertTrue(store.write(state()));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("sender", "+982000360000");
        assertTrue(store.write(second));
        Map<String, Object> read = store.read();
        assertNotNull(read);
        assertEquals("+982000360000", read.get("sender"));
        assertNull("the previous draft is gone, not merged", read.get("body"));
    }

    @Test
    public void forget() {
        assertTrue(store.write(state()));
        store.clear();
        assertFalse(store.present());
        assertFalse(file.exists());
        assertNull(store.read());
    }

    @Test
    public void aSealedFileTheAppCannotOpenIsDroppedRatherThanKept() throws Exception {
        assertTrue(store.write(state()));
        // Bytes that are not a sealed draft: a truncated write, a file from a different install, or
        // a device whose keystore entry is gone. There is nothing here worth recovering, and a
        // file the app cannot read is a message it could not have chosen to keep.
        try (RandomAccessFile handle = new RandomAccessFile(file, "rw")) {
            handle.setLength(8);
        }
        assertNull(store.read());
        assertFalse(store.present());
        assertFalse(file.exists());
    }

    @Test
    public void nothingIsLeftBehindAfterAWrite() {
        assertTrue(store.write(state()));
        assertFalse("no temp file survives a completed write",
                new File(file.getPath() + ".tmp").exists());
    }

    @Test
    public void encodesPersianTextThePlatformReaderTakesBack() throws Exception {
        String encoded = RuleDraftStore.encode(state());
        Map<String, Object> decoded = PlatformRuleJson.read(new java.io.ByteArrayInputStream(
                encoded.getBytes(StandardCharsets.UTF_8)));
        assertEquals(BODY, decoded.get("body"));
        // Numbers come back as integers rather than the BigDecimals the JSON reader produces, so a
        // screen that reads them back does not have to know what a number decoded to.
        Map<String, Object> plain = RuleDraftStore.decode(decoded);
        assertEquals(true, plain.get("hasDate"));
    }

    @Test
    public void keepsTheHighlightedSpans() {
        assertTrue(store.write(state()));
        Map<String, Object> read = store.read();
        assertNotNull(read);
        // The spans are a map of lists, which is deeper than the rest of the draft: written as
        // nulls or zeroes they come back as nothing, and the reader reopens a rule whose parts they
        // had already chosen.
        Object selected = read.get("selected");
        assertTrue("the anchors must survive as a map", selected instanceof Map);
        Map<?, ?> spans = (Map<?, ?>) selected;
        assertEquals("AMOUNT", List.of(6, 13), toList(spans.get("AMOUNT")));
        assertEquals("BALANCE", List.of(27, 36), toList(spans.get("BALANCE")));
    }

    /** A span as a list of numbers, whatever concrete list the reader produced. */
    private static List<?> toList(Object value) {
        assertTrue("a span must come back as a list", value instanceof List);
        List<Object> out = new ArrayList<>();
        for (Object item : (List<?>) value) out.add(item);
        return out;
    }

    @Test
    public void aMessageWithEveryAwkwardCharacterSurvivesTheRoundTrip() {
        Map<String, Object> awkward = new LinkedHashMap<>();
        awkward.put("body", "quote \" backslash \\ newline \n tab \t control  unicode \u202e");
        awkward.put("sender", SENDER);
        assertTrue(store.write(awkward));
        Map<String, Object> read = store.read();
        assertNotNull(read);
        assertEquals(awkward.get("body"), read.get("body"));
    }
}