package com.ashkanrafiee.balance;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FigureEvidenceTest {
    private Context context;
    private final Transaction movement = new Transaction("Refah", "1234", 100L, -20L, 80L, "sig", "content");

    @Before public void before() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SourceStore.clear(context);
        TransactionStore.clear(context);
        MetadataStore.reset(context, true);
    }

    @After public void after() throws Exception {
        SourceStore.clear(context);
        TransactionStore.clear(context);
        MetadataStore.reset(context, true);
    }

    @Test public void linksOnlyExactObservedTransaction_notSimilarAmountsOrAccounts() throws Exception {
        SourceStore.Source source = SourceStore.capture(context, "Refah", "settled", 110L, 1);
        SourceStore.observeTransaction(context, source, 1, movement);
        SourceStore.observeTransaction(context, source, 2, movement);
        FigureEvidence.SourceMatch linked = FigureEvidence.transaction(context, movement);
        assertFalse(linked.ambiguous);
        assertEquals(source.id, linked.unique().id);
        assertNull(FigureEvidence.transaction(context, new Transaction("Refah", "other", 100L,
            -20L, 80L, "sig", "content")).unique());
        assertNull(FigureEvidence.transaction(context, new Transaction("Refah", "1234", 100L,
            -20L, 80L, "different", "content")).unique());
    }

    @Test public void competingPhysicalSourcesRemainAmbiguous_notFirstOrLatestWins() throws Exception {
        for (long arrival : new long[]{110L, 120L}) {
            SourceStore.Source source = SourceStore.capture(context, "Refah", "same body", arrival, 1);
            SourceStore.observeTransaction(context, source, 1, movement);
            SourceStore.observeBalance(context, source, 1, "Refah", "1234", 100L, 80L);
        }
        FigureEvidence.SourceMatch tx = FigureEvidence.transaction(context, movement);
        assertTrue(tx.ambiguous);
        assertNull(tx.unique());
        FigureEvidence.SourceMatch balance = FigureEvidence.balance(context,
            new Bank("Refah", 80L, 100L, "Refah", "1234"));
        assertTrue(balance.ambiguous);
        assertNull(balance.unique());
    }

    @Test public void missingAndLegacySourcesAreUnavailable_notReconstructedFromSmsInbox() throws Exception {
        assertNull(FigureEvidence.transaction(context, movement).unique());
        Transaction legacy = new Transaction("Refah", "1234", 100L, -20L, 80L, null, null);
        SourceStore.Source source = SourceStore.capture(context, "Refah", "settled", 110L, 1);
        SourceStore.observeTransaction(context, source, 1, legacy);
        assertNull(FigureEvidence.transaction(context, legacy).unique());
        assertNull(FigureEvidence.balance(context,
            new Bank("Refah", 80L, 100L, "Refah", "other-account")).unique());
    }

    @Test public void authorizationCodesNeverReachDetails_evenAlphanumericOrSeparated() throws Exception {
        for (String body : Arrays.asList("OTP: 123456", "رمز پویا\n۱۲۳۴۵۶", "code AB12CD",
                "AZ7-Q4\nOne-time password", "کد تایید: ١٢٣٤٥٦", "کد: AB12CD")) {
            SourceStore.Source source = SourceStore.capture(context, "Refah", body, 110L, 1);
            assertEquals("[redacted]", FigureEvidence.safeBody(source));
        }
        SourceStore.Source ordinary = SourceStore.capture(context, "Refah", "Balance: 1,234 rials", 110L, 1);
        assertEquals("Balance: 1,234 rials", FigureEvidence.safeBody(ordinary));
    }

    @Test public void residualCalculationUsesCompleteSlotAndIncludesClosingMovement() {
        Residual residual = new Residual("Refah", "1234", 10, 30, -35, 2);
        FigureEvidence.BracketReader reader = new FigureEvidence.BracketReader(residual);
        // A narrowing date/direction/search/tag selection must never supply the inputs here.
        // The helper reads the full store; this accumulator independently proves the exact math.
        reader.add(tx("1234", 10, 500, 100L)); // Opening movement is outside the half-open span.
        reader.add(tx("1234", 20, -10, null));
        reader.add(tx("other", 20, 99_999, null));
        reader.add(tx("1234", 30, 5, 60L));
        FigureEvidence.Bracket bracket = reader.finish();
        assertNotNull(bracket);
        assertEquals(100, bracket.before);
        assertEquals(60, bracket.after);
        assertEquals(-5, bracket.received);
    }

    @Test public void changedMissingAmbiguousOrOverflowingBracketsNeverInventCalculations() {
        Residual residual = new Residual("Refah", "1234", 10, 30, -35, 2);
        FigureEvidence.BracketReader missing = new FigureEvidence.BracketReader(residual);
        missing.add(tx("1234", 20, -10, null));
        missing.add(tx("1234", 30, 5, 60L));
        assertNull(missing.finish());
        FigureEvidence.BracketReader ambiguous = new FigureEvidence.BracketReader(residual);
        ambiguous.add(tx("1234", 10, 0, 100L));
        ambiguous.add(tx("1234", 20, -10, null));
        ambiguous.add(tx("1234", 30, 5, 60L));
        ambiguous.add(tx("1234", 30, 0, 60L));
        assertNull(ambiguous.finish());
        FigureEvidence.BracketReader changed = new FigureEvidence.BracketReader(residual);
        changed.add(tx("1234", 10, 0, 100L));
        changed.add(tx("1234", 20, -10, 90L)); // Intermediate balance closes a different bracket.
        changed.add(tx("1234", 30, 5, 60L));
        assertNull(changed.finish());
        FigureEvidence.BracketReader overflow = new FigureEvidence.BracketReader(residual);
        overflow.add(tx("1234", 10, 0, Long.MAX_VALUE));
        overflow.add(tx("1234", 20, Long.MIN_VALUE, null));
        overflow.add(tx("1234", 30, -1, Long.MIN_VALUE));
        assertNull(overflow.finish());
    }

    @Test public void residualReadsUnfilteredStoreRegardlessOfPersonalAnnotations() throws Exception {
        Transaction opening = tx("1234", 10, 500, 100L);
        Transaction withdrawal = tx("1234", 20, -10, null);
        Transaction closing = tx("1234", 30, 5, 60L);
        TransactionStore.replace(context, Arrays.asList(opening, withdrawal, closing,
            tx("other", 20, 999_999, null)));
        MetadataStore.setAnnotations(context, closing, "only this row matches search", Arrays.asList("chosen"));
        Residual residual = new Residual("Refah", "1234", 10, 30, -35, 2);
        FigureEvidence.Bracket bracket = FigureEvidence.residual(context, residual);
        assertNotNull(bracket);
        assertEquals(-5, bracket.received);
        assertEquals(100, bracket.before);
        assertEquals(60, bracket.after);
        assertEquals("only this row matches search", MetadataStore.getNote(context, closing));
        assertEquals(Arrays.asList("chosen"), MetadataStore.getTags(context, closing));
    }

    @Test public void exactRialFormattingHandlesBothLongExtremesInBothLanguages() {
        String original = LocaleHelper.currentTag(context);
        String currency = CurrencyHelper.currency(context);
        try {
            CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_RIAL);
            for (String language : Arrays.asList("en", "fa")) {
                LocaleHelper.setLanguage(context, language);
                Context localized = LocaleHelper.wrap(context);
                assertTrue(FigureExplanation.money(localized, Long.MIN_VALUE)
                    .contains(language.equals("fa") ? "ریال" : "rials"));
                String max = Digits.ascii(FigureExplanation.money(localized, Long.MAX_VALUE))
                    .replaceAll("[^0-9]", "");
                String min = Digits.ascii(FigureExplanation.money(localized, Long.MIN_VALUE))
                    .replaceAll("[^0-9]", "");
                assertEquals("9223372036854775807", max);
                assertEquals("9223372036854775808", min);
            }
        } finally {
            LocaleHelper.setLanguage(context, original);
            CurrencyHelper.setCurrency(context, currency);
        }
    }

    @Test public void selectedUnitIsExactAndPrecedesExplicitRialEvidenceInBothLanguages() {
        String original = LocaleHelper.currentTag(context);
        String currency = CurrencyHelper.currency(context);
        try {
            for (String language : Arrays.asList("en", "fa")) {
                LocaleHelper.setLanguage(context, language);
                Context localized = LocaleHelper.wrap(context);
                CurrencyHelper.setCurrency(context, CurrencyHelper.CURRENCY_TOMAN);
                for (long amount : new long[]{25, -25, 0, Long.MIN_VALUE, Long.MAX_VALUE}) {
                    String text = FigureExplanation.money(localized, amount);
                    String selected = localized.getString(R.string.figure_display_amount,
                        NotificationAmounts.format(localized, amount));
                    String raw = localized.getString(R.string.figure_raw_rials,
                        java.text.NumberFormat.getIntegerInstance(language.equals("fa")
                            ? new java.util.Locale("fa") : java.util.Locale.US).format(amount));
                    assertTrue(text.startsWith(selected + "\n"));
                    assertTrue(text.endsWith(raw));
                }
                // A sum larger than Long.MAX_VALUE must not lose the final fractional toman digit.
                java.math.BigInteger aggregate = new java.math.BigInteger("18446744073709551615");
                String large = Digits.ascii(FigureExplanation.money(localized, aggregate));
                String firstNumber = large.split("\n")[0].replaceAll("[^0-9]", "");
                assertEquals("18446744073709551615", firstNumber);
                assertEquals("18446744073709551615", large.split("\n")[1].replaceAll("[^0-9]", ""));
                if (language.equals("en")) assertTrue(large.split("\n")[0].contains(".5"));

                CurrencyHelper.setCurrency(context, CurrencyHelper.CUSTOM_PREFIX + "USD");
                String custom = FigureExplanation.money(localized, -25);
                assertTrue(custom.startsWith(localized.getString(R.string.figure_display_amount,
                    NotificationAmounts.format(localized, -25))));
                assertTrue(custom.endsWith(localized.getString(R.string.figure_custom_unit_caution)));
            }
        } finally {
            LocaleHelper.setLanguage(context, original);
            CurrencyHelper.setCurrency(context, currency);
        }
    }

    private Transaction tx(String account, long date, long amount, Long balance) {
        return new Transaction("Refah", account, date, amount, balance, null, null);
    }
}
