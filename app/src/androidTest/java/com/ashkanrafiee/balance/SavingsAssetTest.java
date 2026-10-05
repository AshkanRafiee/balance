package com.ashkanrafiee.balance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exact valuation and validation tests for manually entered holdings. */
@RunWith(AndroidJUnit4.class)
public class SavingsAssetTest {
    @Test public void goldWeightUsesScaledQuantityAndManualUnitValue() {
        SavingsAsset asset = SavingsAsset.create("1.250g", SavingsAsset.Kind.GOLD_GRAM,
            SavingsAsset.KARAT_24, "", "", SavingsAsset.parseScaled("۱٫۲۵۰"), 10_000_000L);
        assertEquals(12_500_000L, asset.totalRial());
    }

    @Test public void coinsAndCurrenciesUseTheirManualUnitValue() {
        SavingsAsset coin = SavingsAsset.create("", SavingsAsset.Kind.COIN, 0,
            SavingsAsset.COIN_EMAMI, SavingsAsset.AGE_FROM_1386, 2_000, 50_000_000L);
        SavingsAsset currency = SavingsAsset.create("", SavingsAsset.Kind.CURRENCY, 0, "", "USD",
            SavingsAsset.parseScaled("250.500"), 500_000L);
        assertEquals(100_000_000L, coin.totalRial());
        assertEquals(125_250_000L, currency.totalRial());
    }

    @Test public void tomanValueConvertsToRawRial() {
        assertEquals(1_250_000L, SavingsAsset.parseValueRial("۱۲۵٬۰۰۰", true));
        assertEquals(125_000L, SavingsAsset.parseValueRial("125,000", false));
    }

    @Test public void serializedAssetsRoundTrip() throws Exception {
        SavingsAsset asset = SavingsAsset.create("bar", SavingsAsset.Kind.GOLD_BAR,
            SavingsAsset.KARAT_18, "", "", 1_000, 20_000_000L);
        java.util.List<SavingsAsset> roundTrip = BalanceData.deserializeSavingsAssets(
            BalanceData.serializeSavingsAssets(Collections.singletonList(asset)));
        assertEquals(1, roundTrip.size());
        assertEquals(SavingsAsset.KARAT_18, roundTrip.get(0).karat);
        assertEquals(20_000_000L, roundTrip.get(0).unitValueRial);
    }

    @Test public void coinAgeIsSeparateFromCurrencyCode() throws Exception {
        SavingsAsset coin = SavingsAsset.create("coin", SavingsAsset.Kind.COIN, 0,
            SavingsAsset.COIN_EMAMI, SavingsAsset.AGE_FROM_1386, "", 1_000, 50_000_000L);
        assertEquals(SavingsAsset.AGE_FROM_1386, coin.coinAge);
        assertEquals("", coin.currencyCode);
        assertEquals(SavingsAsset.AGE_FROM_1386, coin.toJson().getString("coinAge"));

        // The old eight-argument call remains source-compatible, but is normalized to the new shape.
        SavingsAsset legacyCall = new SavingsAsset("legacy", "", SavingsAsset.Kind.COIN, 0,
            SavingsAsset.COIN_EMAMI, SavingsAsset.AGE_FROM_1386, 1_000, 1L);
        assertEquals(SavingsAsset.AGE_FROM_1386, legacyCall.coinAge);
        assertEquals("", legacyCall.currencyCode);
    }

    @Test public void valuationRoundsEachRowHalfUp() {
        SavingsAsset asset = SavingsAsset.create("fraction", SavingsAsset.Kind.CURRENCY, 0,
            "", "USD", 1_500, 1L);
        assertEquals(2L, asset.totalRial());
    }

    @Test public void mixedHoldingKindsAggregateIndependentlyPerRow() {
        SavingsAsset gold = SavingsAsset.create("gold", SavingsAsset.Kind.GOLD_GRAM,
            SavingsAsset.KARAT_24, "", "", 1_250, 10_000_000L);
        SavingsAsset bar = SavingsAsset.create("bar", SavingsAsset.Kind.GOLD_BAR,
            SavingsAsset.KARAT_18, "", "", 1_000, 20_000_000L);
        SavingsAsset silver = SavingsAsset.create("silver", SavingsAsset.Kind.SILVER_GRAM,
            0, "", "", 500, 100_000L);
        SavingsAsset coin = SavingsAsset.create("coin", SavingsAsset.Kind.COIN, 0,
            SavingsAsset.COIN_EMAMI, SavingsAsset.AGE_FROM_1386, "", 2_000, 50_000_000L);
        SavingsAsset currency = SavingsAsset.create("currency", SavingsAsset.Kind.CURRENCY, 0,
            "", "USD", 250_500, 500_000L);
        assertEquals(257_800_000L, SavingsAsset.totalRial(Arrays.asList(
            gold, bar, silver, coin, currency)));
    }

    @Test public void quantityParserRejectsExponentSignsAndGrouping() {
        assertEquals(1_250L, SavingsAsset.parseScaled("۱٫۲۵۰"));
        for (String hostile : new String[]{"1e3", "-1", "+1", "1,000", "1 000", "1.0001"}) {
            try {
                SavingsAsset.parseScaled(hostile);
                fail("hostile quantity accepted: " + hostile);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void fractionalCoinCountIsRejected() {
        SavingsAsset.create("coin", SavingsAsset.Kind.COIN, 0, SavingsAsset.COIN_EMAMI,
            SavingsAsset.AGE_FROM_1386, 1_500, 50_000_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fractionalGoldBarCountIsRejected() {
        SavingsAsset.create("bar", SavingsAsset.Kind.GOLD_BAR, SavingsAsset.KARAT_18,
            "", "", 1_500, 20_000_000L);
    }

    @Test public void irrelevantMetadataAndNullKindAreRejected() {
        assertInvalid(() -> new SavingsAsset("coin", "", SavingsAsset.Kind.COIN, 18,
            SavingsAsset.COIN_EMAMI, SavingsAsset.AGE_FROM_1386, "", 1_000, 1L));
        assertInvalid(() -> new SavingsAsset("gold", "", SavingsAsset.Kind.GOLD_GRAM,
            SavingsAsset.KARAT_18, "", "", "USD", 1_000, 1L));
        assertInvalid(() -> new SavingsAsset("currency", "", SavingsAsset.Kind.CURRENCY,
            0, "", "USD", "", 1_000, 1L));
        assertInvalid(() -> new SavingsAsset("null-kind", "", null, 0, "", "", "", 1_000, 1L));
    }

    @Test public void aggregateRejectsOverflowInsteadOfSaturating() {
        SavingsAsset first = SavingsAsset.create("a", SavingsAsset.Kind.CURRENCY, 0,
            "", "USD", 1_000, Long.MAX_VALUE);
        SavingsAsset second = SavingsAsset.create("b", SavingsAsset.Kind.CURRENCY, 0,
            "", "EUR", 1_000, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, first.totalRial());
        try {
            SavingsAsset.totalRial(Arrays.asList(first, second));
            fail("aggregate overflow accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void deserializeRejectsMissingOrEmptyIdWithoutGeneratingOne() throws Exception {
        SavingsAsset asset = SavingsAsset.create("stable", SavingsAsset.Kind.CURRENCY, 0,
            "", "USD", 1_000, 1L);
        JSONObject json = asset.toJson();
        json.remove("id");
        assertInvalid(() -> SavingsAsset.fromJson(json));
        json.put("id", "");
        assertInvalid(() -> SavingsAsset.fromJson(json));
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("invalid savings asset accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidGoldPurityIsRejected() {
        SavingsAsset.create("", SavingsAsset.Kind.GOLD_GRAM, 22, "", "", 1_000, 1_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void valuationOverflowIsRejected() {
        SavingsAsset.create("", SavingsAsset.Kind.SILVER_GRAM, 0, "", "", Long.MAX_VALUE, Long.MAX_VALUE);
    }
}
