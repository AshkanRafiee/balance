package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.Rules.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes a decoded pack back to the prototype-1 JSON it came from, so a pack the app holds can be
 * exported, shared, edited by hand and imported again without the format having a second dialect.
 *
 * <p>The output is canonical rather than faithful: members appear in the schema's own order,
 * optional members are omitted when they carry their default, and every set or map is sorted. Two
 * packs that mean the same thing therefore produce the same bytes whatever order they were built
 * in, which is what lets a shared pack be diffed, deduplicated and digested. Sorting also means the
 * output cannot leak the iteration order of a hash map, so a document's bytes never depend on how
 * it happened to be decoded.
 *
 * <p>The guarantee is semantic, not textual: decoding what this wrote yields the document it was
 * given, member for member. A hand-authored pack that spells out a default comes back without it,
 * which is the same document and a smaller one; nothing else is dropped. {@link PackWriterTest}
 * holds that claim against every pack that ships and every example, and holds the output inside the
 * bounds the reader enforces, so a pack the app wrote is always a pack the app can read back.
 *
 * <p>This is an encoder over an already-validated {@link PackDocument}, so it needs no validation
 * of its own and never refuses a value the decoder accepted. The one thing it does refuse is output
 * the reader could not take back: more bytes or tokens than the format allows. Failures carry no
 * pack text, ids or values.
 */
public final class PackWriter {
    private PackWriter() {}

    /** The whole document as canonical JSON text, ending in one newline. */
    public static String write(PackDocument pack) {
        Builder out = new Builder();
        out.object(0, null);
        out.text(1, "schema", PackDocument.SCHEMA);
        out.text(1, "id", pack.id());
        out.text(1, "revision", pack.revision());
        out.object(1, "bank");
        out.text(2, "id", pack.bank().id());
        out.text(2, "country", pack.bank().country());
        out.text(2, "name", pack.bank().name());
        out.text(2, "provenance", pack.bank().provenance().name());
        out.end(1);
        out.array(1, "templates");
        for (Template template : pack.templates()) template(out, template);
        out.end(1);
        out.end(0);
        return checked(out.finish());
    }

    private static void template(Builder out, Template template) {
        out.object(2, null);
        out.text(3, "id", template.id());
        // senders() is a set: sorted, so two packs naming one bank identically produce one document.
        out.array(3, "senders");
        sorted(template.senders()).forEach(sender -> out.item(4, string(sender)));
        out.end(3);
        out.array(3, "guards");
        for (Guard guard : template.guards()) {
            out.object(4, null);
            out.member(5, "line", Integer.toString(guard.line()));
            out.text(5, "literal", guard.literal());
            out.member(5, "excluded", Boolean.toString(guard.excluded()));
            out.end(4);
        }
        out.end(3);
        out.array(3, "outputs");
        for (Output output : template.outputs()) output(out, 4, output);
        out.end(3);
        out.end(2);
    }

    private static void output(Builder out, int depth, Output output) {
        out.object(depth, null);
        out.text(depth + 1, "id", output.id());
        field(out, depth + 1, "region", output.region());
        if (output.account() != null) field(out, depth + 1, "account", output.account());
        out.text(depth + 1, "kind", output.kind().name());
        money(out, depth + 1, "money", output.money());
        if (output.direction() != null) choice(out, depth + 1, "direction", output.direction());
        if (output.originalAmount() != null) money(out, depth + 1, "originalAmount", output.originalAmount());
        if (output.date() != null) date(out, depth + 1, output.date());
        if (output.accountOptional()) out.member(depth + 1, "accountOptional", "true");
        if (output.reason() != null) textRule(out, depth + 1, "reason", output.reason());
        if (output.channel() != null) textRule(out, depth + 1, "channel", output.channel());
        out.end(depth);
    }

    private static void field(Builder out, int depth, String name, Field field) {
        out.object(depth, name);
        out.member(depth + 1, "line", Integer.toString(field.line()));
        out.text(depth + 1, "after", field.after());
        out.text(depth + 1, "before", field.before());
        out.member(depth + 1, "maxLength", Integer.toString(field.maxLength()));
        if (field.normalization() != Normalization.NONE) out.text(depth + 1, "normalization", field.normalization().name());
        if (field.numeric() != null) numeric(out, depth + 1, field.numeric());
        if (field.terminated()) out.member(depth + 1, "terminated", "true");
        out.end(depth);
    }

    private static void numeric(Builder out, int depth, NumericShape shape) {
        out.object(depth, "numeric");
        out.array(depth + 1, "segments");
        for (Width segment : shape.segments()) {
            out.object(depth + 2, null);
            out.member(depth + 3, "min", Integer.toString(segment.min()));
            out.member(depth + 3, "max", Integer.toString(segment.max()));
            out.end(depth + 2);
        }
        out.end(depth + 1);
        out.text(depth + 1, "mode", shape.mode().name());
        out.end(depth);
    }

    private static void money(Builder out, int depth, String name, MoneyRule rule) {
        out.object(depth, name);
        field(out, depth + 1, "amount", rule.amount());
        choice(out, depth + 1, "currency", rule.currency());
        out.text(depth + 1, "decimal", String.valueOf(rule.decimal()));
        // A group separator of 0 means "this format does not group", which the schema spells as the
        // empty string rather than as a character nobody can read.
        out.text(depth + 1, "group", rule.group() == 0 ? "" : String.valueOf(rule.group()));
        out.text(depth + 1, "grouping", rule.grouping().name());
        out.text(depth + 1, "digits", rule.digits().name());
        out.member(depth + 1, "unitMultiplier", Integer.toString(rule.unitMultiplier()));
        if (rule.sign() != Sign.LEADING) out.text(depth + 1, "sign", rule.sign().name());
        if (rule.leadingPoint()) out.member(depth + 1, "leadingPoint", "true");
        out.end(depth);
    }

    private static void choice(Builder out, int depth, String name, CurrencyRule rule) {
        out.object(depth, name);
        if (rule.fixed() != null) out.text(depth + 1, "fixed", rule.fixed().name());
        if (rule.token() != null) field(out, depth + 1, "token", rule.token());
        mapping(out, depth + 1, rule.mapping(), Currency::name);
        out.end(depth);
    }

    private static void choice(Builder out, int depth, String name, DirectionRule rule) {
        out.object(depth, name);
        if (rule.fixed() != null) out.text(depth + 1, "fixed", rule.fixed().name());
        if (rule.token() != null) field(out, depth + 1, "token", rule.token());
        mapping(out, depth + 1, rule.mapping(), Direction::name);
        out.end(depth);
    }

    private static <E> void mapping(Builder out, int depth, Map<String, E> source,
            Function<E, String> name) {
        if (source.isEmpty()) return;
        out.object(depth, "mapping");
        sorted(source.keySet()).forEach(key ->
            out.text(depth + 1, key, name.apply(source.get(key))));
        out.end(depth);
    }

    private static void textRule(Builder out, int depth, String name, TextRule rule) {
        out.object(depth, name);
        field(out, depth + 1, "field", rule.field());
        mapping(out, depth + 1, rule.mapping(), value -> value);
        out.member(depth + 1, "minLength", Integer.toString(rule.minLength()));
        out.member(depth + 1, "maxLength", Integer.toString(rule.maxLength()));
        out.member(depth + 1, "digitFree", Boolean.toString(rule.digitFree()));
        out.end(depth);
    }

    private static void date(Builder out, int depth, DateRule rule) {
        out.object(depth, "date");
        // The calendar is carried by the option set; without one the schema allows only Gregorian,
        // which is therefore what the absent set means.
        out.text(depth + 1, "calendar", rule.options() == null
            ? Calendar.GREGORIAN.name() : rule.options().calendar().name());
        field(out, depth + 1, "field", rule.field());
        // orders() is a set, and the enum's own order is the one a reader would write by hand.
        out.array(depth + 1, "orders");
        List<Order> orders = new ArrayList<>(rule.orders());
        orders.sort(Comparator.comparingInt(Enum::ordinal));
        for (Order order : orders) out.item(depth + 2, string(order.name()));
        out.end(depth + 1);
        out.text(depth + 1, "separator", String.valueOf(rule.separator()));
        out.member(depth + 1, "withTime", Boolean.toString(rule.withTime()));
        out.text(depth + 1, "digits", rule.digits().name());
        seconds(out, depth + 1, "maxPastSeconds", rule.maxPast());
        seconds(out, depth + 1, "maxFutureSeconds", rule.maxFuture());
        if (rule.options() != null) options(out, depth + 1, rule.options());
        out.end(depth);
    }

    private static void options(Builder out, int depth, DateOptions options) {
        out.object(depth, "options");
        out.text(depth + 1, "year", options.year().name());
        out.member(depth + 1, "yearBase", Integer.toString(options.yearBase()));
        out.member(depth + 1, "variableWidth", Boolean.toString(options.variableWidth()));
        out.text(depth + 1, "layout", options.layout().name());
        out.text(depth + 1, "timeSeparator", String.valueOf(options.timeSeparator()));
        out.member(depth + 1, "seconds", Boolean.toString(options.seconds()));
        out.text(depth + 1, "zone", options.zone().getId());
        out.end(depth);
    }

    private static void seconds(Builder out, int depth, String name, Duration duration) {
        out.member(depth, name, Long.toString(duration.getSeconds()));
    }

    private static List<String> sorted(java.util.Collection<String> values) {
        List<String> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.naturalOrder());
        return ordered;
    }

    /** A JSON string, escaped so the text a reader takes back is the text this holds. Control
     *  characters are escaped because raw ones are not legal JSON, and everything else is written
     *  as it stands: a Persian anchor stays readable in the file a person is asked to edit. */
    private static String string(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** Holds the output inside the bounds the reader enforces, so anything written can be read. */
    private static String checked(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > JsonLexicalGuard.MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Written pack exceeds the readable document size");
        JsonLexicalGuard.validate(text);
        return text;
    }

    /** Indented JSON with the punctuation kept in one place, so no caller has to remember it: a
     *  value knows its depth and nothing else. Each open container remembers its own closing
     *  character and whether it has held a value yet, which is all that is needed to put a comma
     *  between siblings and none after the last one at every depth. */
    private static final class Builder {
        private final StringBuilder out = new StringBuilder();
        private final ArrayDeque<Character> closers = new ArrayDeque<>();
        /** Whether the container being written into already holds a value. Seeded with the
         *  document root, so the first container has a container to look at. */
        private final ArrayDeque<Boolean> filled = new ArrayDeque<>(List.of(false));

        void object(int depth, String name) { open(depth, name, '{', '}'); }

        void array(int depth, String name) { open(depth, name, '[', ']'); }

        private void open(int depth, String name, char open, char close) {
            separate(depth);
            if (name != null) out.append(string(name)).append(": ");
            out.append(open);
            closers.push(close);
            filled.push(false);
        }

        /** One named value whose text is already a JSON value: a number, a boolean, or a string
         *  quoted by the caller because it needed care (an empty group separator). */
        void member(int depth, String name, String value) {
            separate(depth);
            out.append(string(name)).append(": ").append(value);
        }

        /** One named string value, quoted here, which is how almost every member is written. */
        void text(int depth, String name, String value) {
            member(depth, name, string(value));
        }

        void item(int depth, String value) {
            separate(depth);
            out.append(value);
        }

        void end(int depth) {
            out.append('\n');
            indent(depth);
            out.append(closers.pop());
            filled.pop();
        }

        String finish() {
            return out.append('\n').toString();
        }

        /** Puts the comma and newline in front of a value, unless it is the first in its container
         *  or the very first thing in the document. */
        private void separate(int depth) {
            if (out.length() > 0) {
                if (filled.peek()) out.append(',');
                out.append('\n');
            }
            indent(depth);
            filled.pop();
            filled.push(true);
        }

        private void indent(int depth) {
            for (int i = 0; i < depth; i++) out.append("  ");
        }
    }
}
