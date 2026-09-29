package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.Rules.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared structural and semantic mapper for the prototype-1 pack schema.
 * This is not a lexical JSON decoder: adapters must reject duplicate JSON keys,
 * non-strict syntax and excessive bytes, nesting and total nodes before allocation.
 * Adapters supply ordinary maps/lists, strings, booleans and exact Integer, Long
 * or BigDecimal numbers. Every collection traversed here has a schema bound;
 * traversal follows a fixed schema, never recursively walks an arbitrary graph.
 * Rules constructors and Parser remain the authority for shared semantics.
 *
 * <p>Draft extensions: Field accepts optional normalization/numeric/terminated;
 * numeric is {segments:[{min,max}],mode}. Output accepts accountOptional and
 * optional reason/channel TextRule objects {field,mapping,minLength,maxLength,digitFree}.
 * Account may be omitted only with accountOptional=true. Date retains its required
 * calendar and adds optional options {year,yearBase,variableWidth,layout,timeSeparator,
 * seconds,zone}; all seven members are required when options is present. Calendar
 * remains on date, not options. Without options only the original Gregorian
 * grammar/caller-supplied zone is supported. Enum strings match Rules names exactly.
 */
public final class PackDocument {
    public static final String SCHEMA = "prototype-1";

    public record Bank(String id, String country, String name) {
        public Bank {
            Rules.id(id);
            Rules.require(country != null && country.length() == 2
                    && country.charAt(0) >= 'A' && country.charAt(0) <= 'Z'
                    && country.charAt(1) >= 'A' && country.charAt(1) <= 'Z', "bank country");
            Rules.text(name, 128, false);
        }
    }

    private final String id;
    private final String revision;
    private final Bank bank;
    private final List<Template> templates;

    private PackDocument(String id, String revision, Bank bank, List<Template> templates) {
        this.id = id;
        this.revision = revision;
        this.bank = bank;
        this.templates = ImmutableCollections.copyList(templates);
    }

    public String schema() { return SCHEMA; }
    public String id() { return id; }
    public String revision() { return revision; }
    public Bank bank() { return bank; }
    public List<Template> templates() { return templates; }

    /** All invalid documents fail without exposing values, keys, IDs or nested causes. */
    public static PackDocument decode(Map<String, Object> document) {
        try {
            Map<?, ?> root = object(document, "schema id revision bank templates", "");
            Rules.require(SCHEMA.equals(string(root.get("schema"))), "schema");
            String id = string(root.get("id"));
            String revision = string(root.get("revision"));
            Rules.id(id);
            Rules.id(revision);
            Map<?, ?> metadata = object(root.get("bank"), "id country name", "");
            Bank bank = new Bank(string(metadata.get("id")), string(metadata.get("country")),
                    string(metadata.get("name")));
            List<Template> templates = new ArrayList<>();
            for (Object value : list(root.get("templates"), MAX_TEMPLATES)) {
                Map<?, ?> template = object(value, "id senders guards outputs", "");
                Set<String> senders = new HashSet<>();
                for (Object sender : list(template.get("senders"), MAX_MAP)) {
                    String text = string(sender);
                    Rules.text(text, MAX_SENDER, false);
                    Rules.require(senders.add(text), "duplicate sender");
                }
                List<Guard> guards = new ArrayList<>();
                for (Object item : list(template.get("guards"), MAX_MAP)) {
                    Map<?, ?> guard = object(item, "line literal excluded", "");
                    guards.add(new Guard(integer(guard.get("line"), -1, MAX_LINES - 1),
                            string(guard.get("literal")), bool(guard.get("excluded"))));
                }
                List<Output> outputs = new ArrayList<>();
                for (Object item : list(template.get("outputs"), MAX_OUTPUTS)) outputs.add(output(item));
                templates.add(new Template(id, revision, bank.id(), string(template.get("id")),
                        senders, guards, outputs));
            }
            // Includes cross-template identity validation; do not duplicate it in the codec.
            new Parser(templates);
            return new PackDocument(id, revision, bank, templates);
        } catch (IllegalArgumentException | NullPointerException | ArithmeticException | DateTimeException e) {
            // Enum.valueOf and future constructor errors may contain untrusted values.
            throw invalid();
        }
    }

    private static Output output(Object value) {
        Map<?, ?> map = object(value, "id region kind money", "account direction originalAmount date accountOptional reason channel");
        return new Output(string(map.get("id")), field(map.get("region"), MAX_INPUT),
                map.containsKey("account") ? field(map.get("account"), MAX_FIELD) : null, enumeration(map.get("kind"), Kind.class),
                money(map.get("money")), map.containsKey("direction") ? direction(map.get("direction")) : null,
                map.containsKey("originalAmount") ? money(map.get("originalAmount")) : null,
                map.containsKey("date") ? date(map.get("date")) : null,
                map.containsKey("accountOptional") && bool(map.get("accountOptional")),
                map.containsKey("reason") ? textRule(map.get("reason")) : null,
                map.containsKey("channel") ? textRule(map.get("channel")) : null);
    }

    private static Field field(Object value, int maximum) {
        Map<?, ?> map = object(value, "line after before maxLength", "normalization numeric terminated");
        return new Field(integer(map.get("line"), -1, MAX_LINES - 1), string(map.get("after")),
                string(map.get("before")), integer(map.get("maxLength"), 1, maximum),
                map.containsKey("normalization") ? enumeration(map.get("normalization"), Normalization.class) : Normalization.NONE,
                map.containsKey("numeric") ? numeric(map.get("numeric")) : null,
                map.containsKey("terminated") && bool(map.get("terminated")));
    }

    private static NumericShape numeric(Object value) {
        Map<?, ?> map = object(value, "segments mode", "");
        List<Width> segments = new ArrayList<>();
        for (Object item : list(map.get("segments"), 4)) {
            Map<?, ?> width = object(item, "min max", "");
            segments.add(new Width(integer(width.get("min"), 1, MAX_FIELD), integer(width.get("max"), 1, MAX_FIELD)));
        }
        return new NumericShape(segments, enumeration(map.get("mode"), NumericMode.class));
    }

    private static TextRule textRule(Object value) {
        Map<?, ?> map = object(value, "field mapping minLength maxLength digitFree", "");
        if (!(map.get("mapping") instanceof Map<?, ?> source) || source.isEmpty() || source.size() > MAX_MAP) throw invalid();
        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) mapping.put(string(entry.getKey()), string(entry.getValue()));
        return new TextRule(field(map.get("field"), MAX_FIELD), mapping,
                integer(map.get("minLength"), 1, MAX_FIELD), integer(map.get("maxLength"), 1, MAX_FIELD),
                bool(map.get("digitFree")));
    }

    private static MoneyRule money(Object value) {
        Map<?, ?> map = object(value, "amount currency decimal group grouping digits unitMultiplier",
                "sign leadingPoint");
        return new MoneyRule(field(map.get("amount"), MAX_FIELD), currency(map.get("currency")),
                separator(map.get("decimal"), false), separator(map.get("group"), true),
                enumeration(map.get("grouping"), Grouping.class), enumeration(map.get("digits"), Digits.class),
                integer(map.get("unitMultiplier"), 1, 10),
                map.containsKey("sign") ? enumeration(map.get("sign"), Sign.class) : Sign.LEADING,
                map.containsKey("leadingPoint") && bool(map.get("leadingPoint")));
    }

    private static CurrencyRule currency(Object value) {
        Map<?, ?> map = choice(value);
        return new CurrencyRule(map.containsKey("fixed") ? enumeration(map.get("fixed"), Currency.class) : null,
                map.containsKey("token") ? field(map.get("token"), MAX_FIELD) : null,
                map.containsKey("mapping") ? mapping(map.get("mapping"), Currency.class) : Collections.emptyMap());
    }

    private static DirectionRule direction(Object value) {
        Map<?, ?> map = choice(value);
        return new DirectionRule(map.containsKey("fixed") ? enumeration(map.get("fixed"), Direction.class) : null,
                map.containsKey("token") ? field(map.get("token"), MAX_FIELD) : null,
                map.containsKey("mapping") ? mapping(map.get("mapping"), Direction.class) : Collections.emptyMap());
    }

    private static Map<?, ?> choice(Object value) {
        Map<?, ?> map = object(value, "", "fixed token mapping");
        Rules.require(map.containsKey("fixed") || map.containsKey("token"), "choice");
        Rules.require(map.containsKey("token") == map.containsKey("mapping"), "mapping pair");
        return map;
    }

    private static <E extends Enum<E>> Map<String, E> mapping(Object value, Class<E> type) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty() || map.size() > MAX_MAP) throw invalid();
        Map<String, E> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = string(entry.getKey());
            Rules.literal(key, false);
            result.put(key, enumeration(entry.getValue(), type));
        }
        return result;
    }

    private static DateRule date(Object value) {
        Map<?, ?> map = object(value,
                "calendar field orders separator withTime digits maxPastSeconds maxFutureSeconds", "options");
        Calendar calendar = enumeration(map.get("calendar"), Calendar.class);
        DateOptions options = null;
        if (map.containsKey("options")) {
            Map<?, ?> o = object(map.get("options"), "year yearBase variableWidth layout timeSeparator seconds zone", "");
            String zone = string(o.get("zone"));
            Rules.text(zone, MAX_LITERAL, false);
            options = new DateOptions(calendar, enumeration(o.get("year"), Year.class),
                    integer(o.get("yearBase"), 0, 9900), bool(o.get("variableWidth")),
                    enumeration(o.get("layout"), DateLayout.class), separator(o.get("timeSeparator"), false),
                    bool(o.get("seconds")), ZoneId.of(zone));
        } else Rules.require(calendar == Calendar.GREGORIAN, "explicit date options required");
        Set<Order> orders = new HashSet<>();
        for (Object order : list(map.get("orders"), 6)) {
            Rules.require(orders.add(enumeration(order, Order.class)), "duplicate order");
        }
        return new DateRule(field(map.get("field"), MAX_FIELD), orders, separator(map.get("separator"), false),
                bool(map.get("withTime")), enumeration(map.get("digits"), Digits.class),
                Duration.ofSeconds(integer(map.get("maxPastSeconds"), 0, 31_622_400)),
                Duration.ofSeconds(integer(map.get("maxFutureSeconds"), 0, 172_800)), options);
    }

    private static Map<?, ?> object(Object value, String required, String optional) {
        Set<String> mandatory = keys(required);
        Set<String> allowed = new HashSet<>(mandatory);
        allowed.addAll(keys(optional));
        if (!(value instanceof Map<?, ?> map) || map.size() > allowed.size()
                || !map.keySet().containsAll(mandatory)) throw invalid();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !allowed.contains(key) || entry.getValue() == null)
                throw invalid();
        }
        return map;
    }

    private static Set<String> keys(String names) {
        if (names.isEmpty()) return Collections.emptySet();
        Set<String> keys = new HashSet<>();
        for (String name : names.split(" ")) Rules.require(keys.add(name), "duplicate schema key");
        return ImmutableCollections.copySet(keys);
    }

    private static List<?> list(Object value, int maximum) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > maximum) throw invalid();
        return list;
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean flag)) throw invalid();
        return flag;
    }

    private static int integer(Object value, int minimum, int maximum) {
        long number;
        if (value instanceof Integer n) number = n;
        else if (value instanceof Long n) number = n;
        else if (value instanceof BigDecimal n) {
            // Check the small range before exact conversion, including extreme exponents.
            if (n.compareTo(BigDecimal.valueOf(minimum)) < 0
                    || n.compareTo(BigDecimal.valueOf(maximum)) > 0) throw invalid();
            number = n.intValueExact();
        } else throw invalid();
        if (number < minimum || number > maximum) throw invalid();
        return (int) number;
    }

    private static char separator(Object value, boolean empty) {
        String text = string(value);
        if (empty && text.isEmpty()) return 0;
        if (text.length() != 1 || text.charAt(0) == 0) throw invalid();
        return text.charAt(0); // Allowed characters/combinations are checked by Rules.
    }

    private static <E extends Enum<E>> E enumeration(Object value, Class<E> type) {
        String text = string(value);
        // Bound work before enum lookup, without incorporating the value in errors.
        if (text.length() > 32) throw invalid();
        return Enum.valueOf(type, text);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid pack document");
    }
}
