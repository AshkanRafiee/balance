package com.ashkanrafiee.balance.parser;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated, immutable codec boundary. No defaults derived from locale or device state. */
public final class Rules {
    private Rules() {}

    public static final int MAX_INPUT = 16_384; // UTF-16 code units; never truncate
    public static final int MAX_SENDER = 128;
    public static final int MAX_TEMPLATES = 256;
    public static final int MAX_CANDIDATES = 32;
    public static final int MAX_OUTPUTS = 8;
    public static final int MAX_FIELD = 256;
    public static final int MAX_LITERAL = 128;
    public static final int MAX_LINES = 256;
    public static final int MAX_MAP = 16;
    public static final int MAX_WORK = 1_000_000; // shared literal, scope and opt-in operation budget

    /** The versioned currency registry, `prototype-currencies-1`. A member is an ISO 4217 code
     * whose scale is that standard's minor-unit exponent, so a pack can state an amount exactly
     * and the app can draw it back without guessing. A code is added only alongside a reviewed
     * pack that needs it, and the scale is fixed at that point: stored amounts are plain minor
     * units under a currency code, so changing a scale later would silently revalue every
     * already-stored amount, and dropping a code would orphan them. Nothing here implies a rate;
     * the registry says how to write and read an amount, never what it is worth. */
    public enum Currency {
        IRR(0), USD(2), EUR(2), GBP(2), JPY(0), KWD(3), JOD(3);
        public final int scale;
        Currency(int scale) { this.scale = scale; }
    }
    public enum Kind { BOOKED_BALANCE, AVAILABLE_BALANCE, POSTED_MOVEMENT }
    public enum Direction { CREDIT, DEBIT }
    public enum Order { YMD, DMY, MDY, YDM, MD, DM }
    public enum Digits { ASCII, ASCII_PERSIAN_ARABIC }
    public enum Grouping { NONE, WESTERN, INDIAN }
    /** Where the optional plus/minus sits relative to the digits. Enclosing bidi marks
     * around the amount are layout noise in both modes; TRAILING also tolerates spaces
     * between the digits and the sign. */
    public enum Sign { LEADING, TRAILING }
    public enum Calendar { GREGORIAN, JALALI }
    public enum Year { FULL, TWO_DIGIT, NEIGHBOR }
    public enum DateLayout { SEPARATED, COMPACT }
    /** Field-local only. Every non-NONE mode folds U+06F0..9/U+0660..9 digits.
     * ACCOUNT trims only edge spaces/tabs and U+200E/F, U+202A..E, U+2066..9.
     * TEXT removes those bidi marks plus U+200C/D throughout, then trims/collapses
     * ASCII whitespace (space, U+0009..D). CHANNEL also folds Arabic yeh/kaf. */
    public enum Normalization { NONE, DIGITS, ACCOUNT, TEXT, CHANNEL }
    public enum NumericMode { EXACT, PREFIX, UNIQUE }

    public record Width(int min, int max) {
        public Width { require(min > 0 && min <= max && max <= MAX_FIELD, "numeric width"); }
    }
    /** One to four digit segments, joined by dots. Never truncates a numeric token.
     * Tokens are maximal runs of Unicode decimal digits, dot or comma; only ASCII
     * digit segments validate after the declared normalization. EXACT consumes the
     * whole capture; PREFIX starts at its first character; UNIQUE searches the
     * bounded capture and rejects multiple valid tokens. Surrounding letters are
     * permitted for PREFIX/UNIQUE, but adjoining digits/dots/commas cannot be skipped. */
    public record NumericShape(List<Width> segments, NumericMode mode) {
        public NumericShape {
            require(!segments.isEmpty() && segments.size() <= 4, "numeric segments");
            segments = ImmutableCollections.copyList(segments);
            Objects.requireNonNull(mode);
            require(segments.stream().mapToInt(Width::max).sum() + segments.size() - 1 <= MAX_FIELD,
                    "numeric total width");
        }
    }

    /** Select one line (zero based), or the whole scope (-1), then a unique opening
     * anchor and first following closing anchor. Empty anchors mean scope edges.
     * maxLength caps the raw enclosed text BEFORE normalization/numeric selection.
     * terminated requires the selected line's LF/CRLF inside its enclosing region.
     * Anchors always match raw text. Reported spans enclose the raw capture. */
    public record Field(int line, String after, String before, int maxLength,
                        Normalization normalization, NumericShape numeric, boolean terminated) {
        public Field(int line, String after, String before, int maxLength) {
            this(line, after, before, maxLength, Normalization.NONE, null, false);
        }
        public Field {
            require(line >= -1 && line < MAX_LINES, "field line");
            literal(after, true); literal(before, true);
            require(maxLength > 0 && maxLength <= MAX_INPUT, "field length");
            require(after.isEmpty() || !after.equals(before), "identical anchors");
            Objects.requireNonNull(normalization);
            require(!terminated || line >= 0, "terminated line selector");
            require(normalization == Normalization.NONE && numeric == null || maxLength <= MAX_FIELD,
                    "transformed field length");
        }
        public static Field line(int line) { return new Field(line, "", "", MAX_FIELD); }
    }

    /** Optional metadata: unknown/invalid/absent tokens are omitted with diagnostics.
     * Length is measured before normalization; only finite semantic IDs are emitted. */
    public record TextRule(Field field, Map<String, String> mapping, int minLength,
                           int maxLength, boolean digitFree) {
        public TextRule {
            Objects.requireNonNull(field); small(field);
            mapping = finiteMap(mapping);
            require(!mapping.isEmpty(), "text mapping");
            mapping.values().forEach(Rules::id);
            require(minLength > 0 && minLength <= maxLength && maxLength <= MAX_FIELD, "text length");
        }
    }

    public record Guard(int line, String literal, boolean excluded) {
        public Guard {
            require(line >= -1 && line < MAX_LINES, "guard line");
            Rules.literal(literal, false);
        }
    }

    /** Fixed currency, finite token mapping, or fixed currency checked against a token.
     * Tokens permit NONE or explicit TEXT normalization only, never numeric selection. */
    public record CurrencyRule(Currency fixed, Field token, Map<String, Currency> mapping) {
        public CurrencyRule {
            mapping = finiteMap(mapping);
            require(fixed != null || token != null, "currency required");
            require((token == null) == mapping.isEmpty(), "currency mapping");
            finiteToken(token);
        }
        public static CurrencyRule fixed(Currency currency) {
            return new CurrencyRule(Objects.requireNonNull(currency), null, Collections.emptyMap());
        }
    }

    /** One optional sign in the declared position only; group/decimal syntax is explicit.
     * Unit multiplier is 1, or 10 for explicitly declared toman-to-IRR conversion. The
     * amount field must be raw (NONE, no numeric selection); digits is the sole digit
     * policy. This also applies to original-amount context. LeadingPoint is the only
     * widening of the money contract: it lets a bank that prints amounts below one
     * unit — ".11" — be read as zero point one one instead of refused. It is opt-in
     * per money rule, because an empty integer part is otherwise the loudest possible
     * signal that a capture group is misaligned, and a silent 0.11 read from a
     * misaligned capture is worse than no amount at all. */
    public record MoneyRule(Field amount, CurrencyRule currency, char decimal,
                            char group, Grouping grouping, Digits digits, int unitMultiplier,
                            Sign sign, boolean leadingPoint) {
        public MoneyRule(Field amount, CurrencyRule currency, char decimal, char group,
                         Grouping grouping, Digits digits, int unitMultiplier) {
            this(amount, currency, decimal, group, grouping, digits, unitMultiplier, Sign.LEADING, false);
        }
        public MoneyRule(Field amount, CurrencyRule currency, char decimal, char group,
                         Grouping grouping, Digits digits, int unitMultiplier, Sign sign) {
            this(amount, currency, decimal, group, grouping, digits, unitMultiplier, sign, false);
        }
        public MoneyRule {
            Objects.requireNonNull(amount); small(amount); rawField(amount);
            Objects.requireNonNull(currency); Objects.requireNonNull(grouping);
            Objects.requireNonNull(digits); Objects.requireNonNull(sign);
            require(decimal == '.' || decimal == ',' || decimal == '\u066b', "decimal separator");
            require(group == 0 || group == '.' || group == ',' || group == ' ' || group == '\u066c',
                    "group separator");
            require(group != decimal && (group == 0) == (grouping == Grouping.NONE), "grouping");
            require(unitMultiplier == 1 || unitMultiplier == 10, "unit multiplier");
            if (unitMultiplier == 10) {
                require(currency.fixed() == null || currency.fixed() == Currency.IRR, "toman currency");
                require(currency.mapping().values().stream().allMatch(c -> c == Currency.IRR),
                        "toman currency mapping");
            }
        }
    }

    /** Finite tokens have the same NONE/TEXT-only policy as currency tokens. */
    public record DirectionRule(Direction fixed, Field token, Map<String, Direction> mapping) {
        public DirectionRule {
            mapping = finiteMap(mapping);
            require(fixed != null || token != null, "direction required");
            require((token == null) == mapping.isEmpty(), "direction mapping");
            finiteToken(token);
        }
        public static DirectionRule fixed(Direction direction) {
            return new DirectionRule(Objects.requireNonNull(direction), null, Collections.emptyMap());
        }
    }

    /** Opt-in date grammar. FULL is four digits; TWO_DIGIT adds an explicit base;
     * NEIGHBOR tests arrival year -1, 0, +1 with independent leap validation.
     * Variable width applies only to separated month/day (1..2 digits); clocks
     * remain HH:mm[:ss]. COMPACT is exactly MMDD followed by the declared clock
     * separator (the DateRule date separator is unused). A zone is mandatory. */
    public record DateOptions(Calendar calendar, Year year, int yearBase, boolean variableWidth,
                              DateLayout layout, char timeSeparator, boolean seconds, ZoneId zone) {
        public DateOptions {
            Objects.requireNonNull(calendar); Objects.requireNonNull(year);
            Objects.requireNonNull(layout); Objects.requireNonNull(zone);
            require(timeSeparator == ' ' || timeSeparator == '-' || timeSeparator == 'T', "time separator");
            require(year == Year.TWO_DIGIT ? yearBase >= 1 && yearBase <=
                    (calendar == Calendar.JALALI ? 3078 : 9900) : yearBase == 0, "year base");
            require(layout != DateLayout.COMPACT || year == Year.NEIGHBOR && !variableWidth,
                    "compact year/width");
        }
    }

    /** The original seven-argument constructor retains strict Gregorian YYYY/MM/DD
     * widths and caller-supplied Message.zone. Non-null options opt into the new
     * calendar/year grammar and a pinned zone. Order ambiguity precedes plausibility.
     * The field is raw (NONE, no numeric selection); digits is the sole digit policy. */
    public record DateRule(Field field, Set<Order> orders, char separator, boolean withTime,
                           Digits digits, Duration maxPast, Duration maxFuture, DateOptions options) {
        public DateRule(Field field, Set<Order> orders, char separator, boolean withTime,
                        Digits digits, Duration maxPast, Duration maxFuture) {
            this(field, orders, separator, withTime, digits, maxPast, maxFuture, null);
        }
        public DateRule {
            Objects.requireNonNull(field); small(field); rawField(field);
            orders = ImmutableCollections.copySet(orders);
            require(!orders.isEmpty(), "date order");
            boolean missing = options != null && options.year() == Year.NEIGHBOR;
            require(orders.stream().allMatch(o -> (o == Order.MD || o == Order.DM) == missing), "year/order");
            require(options == null || !options.seconds() || withTime, "seconds require time");
            require(options == null || options.layout() != DateLayout.COMPACT ||
                    orders.equals(Collections.singleton(Order.MD)) && withTime, "compact layout");
            require(separator == '/' || separator == '-' || separator == '.', "date separator");
            Objects.requireNonNull(digits);
            Objects.requireNonNull(maxPast); Objects.requireNonNull(maxFuture);
            require(!maxPast.isNegative() && maxPast.compareTo(Duration.ofDays(366)) <= 0,
                    "date past window");
            require(!maxFuture.isNegative() && maxFuture.compareTo(Duration.ofDays(2)) <= 0,
                    "date future window");
        }
    }

    /** Every declared financial output is required. Fields are relative to its raw
     * region. accountOptional permits an absent or invalid reference to a declared
     * account, emitting null account plus ACCOUNT_UNRESOLVED and the underlying
     * diagnostic; an output that declares no account at all states none and reports
     * nothing, because nothing went unresolved.
     * Optional reason/channel maps omit absent/invalid/unknown/ambiguous tokens.
     * Account ambiguity and all resource exhaustion remain failures. Original purchase
     * amount is context, never an additional ledger movement. */
    public record Output(String id, Field region, Field account, Kind kind, MoneyRule money,
                         DirectionRule direction, MoneyRule originalAmount, DateRule date,
                         boolean accountOptional, TextRule reason, TextRule channel) {
        public Output(String id, Field region, Field account, Kind kind, MoneyRule money,
                      DirectionRule direction, MoneyRule originalAmount, DateRule date) {
            this(id, region, Objects.requireNonNull(account), kind, money, direction, originalAmount, date, false, null, null);
        }
        public Output {
            Rules.id(id); Objects.requireNonNull(region); small(account);
            require(account != null || accountOptional, "account required");
            require(region.normalization() == Normalization.NONE && region.numeric() == null, "raw region");
            Objects.requireNonNull(kind); Objects.requireNonNull(money);
            require((kind == Kind.POSTED_MOVEMENT) == (direction != null), "movement direction");
            require(originalAmount == null || kind == Kind.POSTED_MOVEMENT, "original amount context");
        }
    }

    public record Template(String packId, String revision, String bankId, String id,
                           Set<String> senders, List<Guard> guards, List<Output> outputs) {
        public Template {
            Rules.id(packId); Rules.id(revision); Rules.id(bankId); Rules.id(id);
            senders = ImmutableCollections.copySet(senders);
            guards = ImmutableCollections.copyList(guards); outputs = ImmutableCollections.copyList(outputs);
            require(!senders.isEmpty() && senders.size() <= MAX_MAP, "senders");
            for (String sender : senders) text(sender, MAX_SENDER, false);
            require(!guards.isEmpty() && guards.size() <= MAX_MAP, "guards");
            require(guards.stream().anyMatch(g -> !g.excluded()), "positive guard required");
            require(!outputs.isEmpty() && outputs.size() <= MAX_OUTPUTS, "outputs");
            Set<String> ids = new HashSet<>();
            for (Output output : outputs) require(ids.add(output.id()), "duplicate output ID");
        }
        public String key() { return packId + "/" + id; }
    }

    static void small(Field field) {
        require(field == null || field.maxLength() <= MAX_FIELD, "typed field length");
    }
    private static void rawField(Field field) {
        require(field.normalization() == Normalization.NONE && field.numeric() == null, "raw financial field");
    }
    private static void finiteToken(Field field) {
        small(field);
        require(field == null || field.numeric() == null &&
                (field.normalization() == Normalization.NONE || field.normalization() == Normalization.TEXT),
                "finite token field");
    }
    static <T> Map<String, T> finiteMap(Map<String, T> source) {
        require(source.size() <= MAX_MAP, "mapping size");
        Map<String, T> map = ImmutableCollections.copyMap(source);
        map.keySet().forEach(key -> literal(key, false));
        return map;
    }
    static void id(String value) {
        text(value, 64, false);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-',
                    "ID alphabet");
        }
    }
    static void literal(String value, boolean empty) { text(value, MAX_LITERAL, empty); }
    static void text(String value, int max, boolean empty) {
        Objects.requireNonNull(value);
        require(value.length() <= max && (empty || !value.isEmpty()), "text length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c != 0 && c != '\r', "unsupported control character");
            if (Character.isHighSurrogate(c)) {
                require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)), "Unicode");
            } else require(!Character.isLowSurrogate(c), "Unicode");
        }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
