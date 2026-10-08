package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests the disk-backed, source-driven history CSV path without using the activity's lists. */
@RunWith(AndroidJUnit4.class)
public class HistoryCsvExportTest {
    private static final long DATE = 1_700_000_000_000L;
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BalanceData.reset(context, true);
        LocaleHelper.setLanguage(context, "en");
        CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
        assertTemporaryFilesRemoved();
    }

    @After public void tearDown() {
        BalanceData.reset(context, true);
        assertTemporaryFilesRemoved();
    }

    @Test public void moreThanTwoThousandFilteredRows_streamInChronologicalTieOrder()
            throws Exception {
        final int[] staged = {0};
        StringWriter output = new StringWriter();
        HistoryCsvExport.writeLookup(context, 17,
            visitor -> {
                for (int i = 0; i < 2_501; i++) {
                    if ((i & 1) != 0) continue;
                    staged[0]++;
                    visitor.accept(new Transaction("bank_melli", "account-" + i,
                        DATE + i, i, "signature-" + i, "content-" + i));
                }
            },
            visitor -> {
                // This source is already filtered; its source order is deliberately retained for
                // same-date residual ties, just as CsvExport.csv retains a stable residual list.
                visitor.accept(new Residual("bank_melli", "residual-a", DATE - 100, DATE,
                    -7L, 1));
                visitor.accept(new Residual("bank_melli", "residual-b", DATE - 100, DATE,
                    9L, 1));
            }, output, new CsvExport.TextLookup() {
                @Override public String note(String key) { return null; }
                @Override public String reason(String key) { return null; }
                @Override public String channel(String key) { return null; }
                @Override public List<String> tags(String key) { return null; }
            });

        String[] rows = output.toString().split("\n", -1);
        assertEquals(1_253 + 1, rows.length);
        assertEquals(1_251, staged[0]);
        assertEquals("-7", parse(rows[1]).get(5));
        assertEquals(CsvExport.KIND_UNACCOUNTED, parse(rows[1]).get(11));
        assertEquals("9", parse(rows[2]).get(5));
        assertEquals(CsvExport.KIND_UNACCOUNTED, parse(rows[2]).get(11));
        assertEquals("0", parse(rows[3]).get(5));
        assertEquals(CsvExport.KIND_MOVEMENT, parse(rows[3]).get(11));
        assertEquals("2500", parse(rows[rows.length - 1]).get(5));
        assertEquals(CsvExport.KIND_MOVEMENT, parse(rows[rows.length - 1]).get(11));
        assertTemporaryFilesRemoved();
    }

    @Test public void unsortedSources_areSortedAndResidualsLeadEqualMovements() throws Exception {
        List<Transaction> transactions = Arrays.asList(
            new Transaction("bank_melli", "late", DATE + 300, 30, "late"),
            new Transaction("bank_melli", "first", DATE + 200, 20, "first"),
            new Transaction("bank_melli", "second", DATE + 200, 21, "second"),
            new Transaction("bank_melli", "early", DATE + 100, 10, "early"));
        List<Residual> residuals = Collections.singletonList(
            new Residual("bank_melli", "gap", DATE, DATE + 200, -5, 1));
        StringWriter output = new StringWriter();
        HistoryCsvExport.write(context, 1,
            visitor -> { for (Transaction transaction : transactions) visitor.accept(transaction); },
            visitor -> { for (Residual residual : residuals) visitor.accept(residual); },
            output, CsvExport.Text.none());

        assertEquals(CsvExport.csv(context, transactions, residuals, CsvExport.Text.none()),
            output.toString());
        String[] rows = output.toString().split("\n", -1);
        assertEquals("10", parse(rows[1]).get(5));
        assertEquals(CsvExport.KIND_UNACCOUNTED, parse(rows[2]).get(11));
        assertEquals("20", parse(rows[3]).get(5));
        assertEquals("21", parse(rows[4]).get(5));
        assertEquals("30", parse(rows[5]).get(5));
        assertEquals("early", parse(rows[1]).get(1));
        assertEquals("first", parse(rows[3]).get(1));
        assertEquals("second", parse(rows[4]).get(1));
        assertTemporaryFilesRemoved();
    }

    @Test public void pointMetadataLookup_preservesUtf8EscapingAndTags() throws Exception {
        Transaction transaction = new Transaction("bank_melli", "account", DATE, 1_250_000,
            "signature", "content-fa");
        AtomicInteger lookups = new AtomicInteger();
        StringWriter output = new StringWriter();
        HistoryCsvExport.writeLookup(context, 1,
            visitor -> visitor.accept(transaction), null, output, new CsvExport.TextLookup() {
                @Override public String note(String key) {
                    lookups.incrementAndGet();
                    return "مبلغ, \"یادداشت\"\nروز دوم";
                }

                @Override public String reason(String key) {
                    lookups.incrementAndGet();
                    return null;
                }

                @Override public String channel(String key) {
                    lookups.incrementAndGet();
                    return null;
                }

                @Override public List<String> tags(String key) {
                    lookups.incrementAndGet();
                    return Arrays.asList("خانه", "say \"later\"");
                }
            });

        byte[] utf8 = output.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals((byte) 0xEF, utf8[0]);
        assertEquals((byte) 0xBB, utf8[1]);
        assertEquals((byte) 0xBF, utf8[2]);
        assertTrue(output.toString().contains("مبلغ, \"\"یادداشت\"\"\nروز دوم"));
        assertTrue(output.toString().contains("خانه"));
        assertTrue(output.toString().contains("say"));
        assertEquals(4, lookups.get());
        assertTemporaryFilesRemoved();
    }

    @Test public void sourceFailure_isPropagatedWithoutFlushAndCleansStaging() throws Exception {
        final IOException expected = new IOException("source stopped");
        final boolean[] flushed = {false};
        Writer output = new StringWriter() {
            @Override public void flush() {
                flushed[0] = true;
                super.flush();
            }
        };
        try {
            HistoryCsvExport.write(context, 1,
                visitor -> {
                    visitor.accept(new Transaction("bank_melli", "one", DATE, 1, "one"));
                    throw expected;
                }, null, output, CsvExport.Text.none());
            fail("source failure must be propagated");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
        assertFalse(flushed[0]);
        assertTemporaryFilesRemoved();
    }

    @Test public void writerFailure_isPropagatedAndStagingIsDeleted() throws Exception {
        final IOException expected = new IOException("destination stopped");
        Writer output = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                throw expected;
            }

            @Override public void flush() throws IOException {
                throw new AssertionError("failed output must not be flushed");
            }

            @Override public void close() {}
        };
        try {
            HistoryCsvExport.write(context, 1,
                visitor -> visitor.accept(new Transaction("bank_melli", "one", DATE, 1, "one")),
                null, output, CsvExport.Text.none());
            fail("writer failure must be propagated");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
        assertTemporaryFilesRemoved();
    }

    private void assertTemporaryFilesRemoved() {
        assertEquals(0, temporaryFiles().size());
    }

    private List<java.io.File> temporaryFiles() {
        java.io.File directory = context.getNoBackupFilesDir();
        java.io.File[] files = directory == null ? null : directory.listFiles((parent, name) ->
            name.startsWith(HistoryCsvExport.STAGING_FILE_PREFIX)
                || name.startsWith(HistoryCsvExport.WRAPPED_KEY_FILE_PREFIX));
        return files == null ? Collections.emptyList() : Arrays.asList(files);
    }

    private static List<String> parse(String row) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
