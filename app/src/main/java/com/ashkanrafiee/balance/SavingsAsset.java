package com.ashkanrafiee.balance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** A manually entered holding with a current, user-supplied rial valuation. */
final class SavingsAsset {
    enum Kind { GOLD_GRAM, GOLD_BAR, SILVER_GRAM, COIN, CURRENCY }

    static final int QUANTITY_SCALE = 1000;
    static final int MAX_LABEL_LENGTH = 100;
    static final int MAX_ITEMS = 500;
    static final int KARAT_18 = 18;
    static final int KARAT_24 = 24;
    static final String AGE_BEFORE_1386 = "before_1386";
    static final String AGE_FROM_1386 = "from_1386";
    static final String COIN_EMAMI = "emami";
    static final String COIN_BAHAR = "bahar_azadi";
    static final String COIN_HALF = "half";
    static final String COIN_ROB = "rob";
    static final String COIN_GRAM = "gram";
    static final String COIN_OTHER = "other";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    String id;
    String label;
    Kind kind;
    int karat;
    String variant;
    String coinAge;
    String currencyCode;
    long quantityScaled;
    long unitValueRial;

    /**
     * Legacy source-compatible constructor.  Its last string was historically used for both a
     * coin's age and a currency's code.  Keep accepting that call shape for the UI and existing
     * callers, but put the value in the field that belongs to the selected kind.
     */
    SavingsAsset(String id, String label, Kind kind, int karat, String variant, String currencyCode,
            long quantityScaled, long unitValueRial) {
        this(id, label, kind, karat, variant,
            kind == Kind.COIN ? currencyCode : "",
            kind == Kind.COIN ? "" : currencyCode,
            quantityScaled, unitValueRial);
    }

    /** Creates an asset with separate coin-age and foreign-currency metadata. */
    SavingsAsset(String id, String label, Kind kind, int karat, String variant, String coinAge,
            String currencyCode, long quantityScaled, long unitValueRial) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.label = label == null ? "" : label.trim();
        this.kind = kind;
        this.karat = karat;
        this.variant = variant == null ? "" : variant;
        this.coinAge = coinAge == null ? "" : coinAge;
        this.currencyCode = currencyCode == null ? "" : currencyCode;
        this.quantityScaled = quantityScaled;
        this.unitValueRial = unitValueRial;
        validate();
    }

    static SavingsAsset create(String label, Kind kind, int karat, String variant, String currencyCode,
            long quantityScaled, long unitValueRial) {
        return new SavingsAsset(UUID.randomUUID().toString(), label, kind, karat, variant,
            currencyCode, quantityScaled, unitValueRial);
    }

    static SavingsAsset create(String label, Kind kind, int karat, String variant, String coinAge,
            String currencyCode, long quantityScaled, long unitValueRial) {
        return new SavingsAsset(UUID.randomUUID().toString(), label, kind, karat, variant, coinAge,
            currencyCode, quantityScaled, unitValueRial);
    }

    void validate() {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Bad savings id");
        if (label.length() > MAX_LABEL_LENGTH) throw new IllegalArgumentException("Label too long");
        if (quantityScaled <= 0 || unitValueRial <= 0) throw new IllegalArgumentException("Bad valuation");
        if (kind == null) throw new IllegalArgumentException("Missing savings kind");
        switch (kind) {
            case GOLD_GRAM:
            case GOLD_BAR:
                if (karat != KARAT_18 && karat != KARAT_24) throw new IllegalArgumentException("Bad gold karat");
                if (!variant.isEmpty() || !coinAge.isEmpty() || !currencyCode.isEmpty())
                    throw new IllegalArgumentException("Bad gold metadata");
                if (kind == Kind.GOLD_BAR && quantityScaled % QUANTITY_SCALE != 0)
                    throw new IllegalArgumentException("Gold bars must be whole counts");
                break;
            case SILVER_GRAM:
                if (karat != 0 || !variant.isEmpty() || !coinAge.isEmpty() || !currencyCode.isEmpty())
                    throw new IllegalArgumentException("Bad silver metadata");
                break;
            case COIN:
                if (karat != 0 || !coinTypeValid(variant) || !(AGE_BEFORE_1386.equals(coinAge)
                        || AGE_FROM_1386.equals(coinAge)) || !currencyCode.isEmpty())
                    throw new IllegalArgumentException("Bad coin metadata");
                if (quantityScaled % QUANTITY_SCALE != 0)
                    throw new IllegalArgumentException("Coins must be whole counts");
                break;
            case CURRENCY:
                if (!currencyValid(currencyCode) || karat != 0 || !variant.isEmpty() || !coinAge.isEmpty())
                    throw new IllegalArgumentException("Bad currency metadata");
                break;
        }
        rowTotalRial();
    }

    long totalRial() {
        return rowTotalRial();
    }

    /** Adds manually valued rows exactly, after rounding each row to the nearest rial. */
    static long totalRial(List<SavingsAsset> assets) {
        if (assets == null) throw new IllegalArgumentException("Savings assets required");
        if (assets.size() > MAX_ITEMS) throw new IllegalArgumentException("Too many assets");
        BigDecimal total = BigDecimal.ZERO;
        for (SavingsAsset asset : assets) {
            if (asset == null) throw new IllegalArgumentException("Null savings asset");
            asset.validate();
            total = total.add(BigDecimal.valueOf(asset.rowTotalRial()));
        }
        try {
            return total.toBigIntegerExact().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Savings total is too large", e);
        }
    }

    /** Explicitly named alias for callers displaying the estimated holdings total. */
    static long totalEstimatedRial(List<SavingsAsset> assets) {
        return totalRial(assets);
    }

    private long rowTotalRial() {
        try {
            return BigDecimal.valueOf(quantityScaled)
                .multiply(BigDecimal.valueOf(unitValueRial))
                .divide(BigDecimal.valueOf(QUANTITY_SCALE), 0, RoundingMode.HALF_UP)
                .toBigIntegerExact().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Savings valuation is too large", e);
        }
    }

    static boolean currencyValid(String code) {
        return code != null && (CODE.matcher(code).matches() || (code.startsWith("custom:")
            && code.length() > 7 && code.length() <= MAX_LABEL_LENGTH
            && code.indexOf('\n') < 0 && code.indexOf('\r') < 0));
    }

    static boolean coinTypeValid(String type) {
        return COIN_EMAMI.equals(type) || COIN_BAHAR.equals(type) || COIN_HALF.equals(type)
            || COIN_ROB.equals(type) || COIN_GRAM.equals(type) || COIN_OTHER.equals(type);
    }

    static long parseScaled(String input) {
        if (input == null) throw new IllegalArgumentException("Quantity required");
        String normalized = Digits.ascii(input).trim().replace("٫", ".");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Quantity required");
        if (!normalized.matches("[0-9]+(?:\\.[0-9]{1,3})?"))
            throw new IllegalArgumentException("Enter a positive quantity with at most three decimals");
        try {
            BigDecimal value = new BigDecimal(normalized).setScale(3, RoundingMode.UNNECESSARY);
            if (value.signum() <= 0) throw new IllegalArgumentException("Quantity must be positive");
            return value.movePointRight(3).toBigIntegerExact().longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Enter a positive quantity with at most three decimals", e);
        }
    }

    static long parseValueRial(String input, boolean toman) {
        if (input == null) throw new IllegalArgumentException("Value required");
        String normalized = Digits.ascii(input).trim().replace('٬', ',');
        if (normalized.isEmpty() || !(normalized.matches("[0-9]+")
                || normalized.matches("[0-9]{1,3}(?:,[0-9]{3})+")))
            throw new IllegalArgumentException("Value must be a whole number");
        try {
            long value = Long.parseLong(normalized.replace(",", ""));
            if (value <= 0) throw new IllegalArgumentException("Value must be positive");
            return toman ? Math.multiplyExact(value, 10L) : value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Value is too large", e);
        }
    }

    JSONObject toJson() throws Exception {
        validate();
        return new JSONObject().put("id", id).put("label", label).put("kind", kind.name())
            .put("karat", karat).put("variant", variant).put("coinAge", coinAge)
            .put("currencyCode", currencyCode)
            .put("quantityScaled", quantityScaled).put("unitValueRial", unitValueRial);
    }

    static SavingsAsset fromJson(JSONObject o) {
        if (o == null) throw new IllegalArgumentException("Malformed savings asset");
        try {
            String id = requiredNonEmpty(o, "id");
            String label = required(o, "label");
            Kind kind = Kind.valueOf(required(o, "kind"));
            String variant = required(o, "variant");
            String currencyCode = required(o, "currencyCode");
            String coinAge;
            if (o.has("coinAge")) {
                coinAge = required(o, "coinAge");
            } else if (kind == Kind.COIN && (AGE_BEFORE_1386.equals(currencyCode)
                    || AGE_FROM_1386.equals(currencyCode))) {
                // The first savings schema stored a coin's age in currencyCode.  Read it once into
                // the explicit field; the next write emits the unambiguous schema.
                coinAge = currencyCode;
                currencyCode = "";
            } else {
                coinAge = "";
            }
            SavingsAsset asset = new SavingsAsset(id, label, kind, requiredInt(o, "karat"), variant,
                coinAge, currencyCode, requiredLong(o, "quantityScaled"),
                requiredLong(o, "unitValueRial"));
            asset.validate();
            return asset;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed savings asset", e);
        }
    }

    private static String required(JSONObject o, String key) throws Exception {
        if (!o.has(key) || o.isNull(key)) throw new IllegalArgumentException("Missing " + key);
        Object value = o.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Bad " + key);
        return (String) value;
    }

    private static String requiredNonEmpty(JSONObject o, String key) throws Exception {
        String value = required(o, key);
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int requiredInt(JSONObject o, String key) throws Exception {
        long value = requiredLong(o, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Bad " + key);
        return (int) value;
    }

    private static long requiredLong(JSONObject o, String key) throws Exception {
        if (!o.has(key) || o.isNull(key)) throw new IllegalArgumentException("Missing " + key);
        Object value = o.get(key);
        if (!(value instanceof Number) || !value.toString().matches("-?[0-9]+"))
            throw new IllegalArgumentException("Bad " + key);
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad " + key, e);
        }
    }
}
