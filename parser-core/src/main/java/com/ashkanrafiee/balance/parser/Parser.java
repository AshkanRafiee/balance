package com.ashkanrafiee.balance.parser;

import static com.ashkanrafiee.balance.parser.Rules.*;
import static com.ashkanrafiee.balance.parser.ImmutableCollections.listOf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Thread-safe immutable snapshot, with bounded per-message work and no I/O. */
public final class Parser {
    public static final String ENGINE = "prototype-1";
    public static final String CURRENCY_REGISTRY = "prototype-currencies-1";
    public enum Status { PARSED, UNKNOWN_SENDER, NO_MATCH, ABSENT, INVALID, OVERFLOW,
        AMBIGUOUS, LIMIT_EXCEEDED }
    public enum Code { REQUIRED_ABSENT, CAPTURE_AMBIGUOUS, FIELD_LIMIT, INPUT_LIMIT,
        CANDIDATE_LIMIT, WORK_LIMIT, INVALID_INPUT, INVALID_ACCOUNT, INVALID_MONEY, MONEY_OVERFLOW,
        UNKNOWN_CURRENCY, CURRENCY_CONFLICT, UNKNOWN_DIRECTION, DIRECTION_CONFLICT,
        ZERO_MOVEMENT, OVERLAPPING_MOVEMENTS, TEMPLATE_CONFLICT, DATE_INVALID,
        DATE_AMBIGUOUS, DATE_OUT_OF_WINDOW, DATE_ZONE_INVALID, DATE_ZONE_AMBIGUOUS,
        INVALID_FIELD, OPTIONAL_ABSENT, UNKNOWN_TEXT, INVALID_TEXT, ACCOUNT_UNRESOLVED,
        OPTIONAL_CONFLICT, OPTIONAL_ASSOCIATION_AMBIGUOUS }
    public enum Precision { DAY, MINUTE, SECOND, ARRIVAL }
    /** A captured reference is not proof of a resolved ledger identity. */
    public enum AccountState { REFERENCED, UNRESOLVED }
    public record SemanticText(String id, String text) {}

    /** Source ID is supplied by the caller; this prototype does not invent SMS occurrence identity. */
    public record Message(String sourceId, String sender, String body, Instant arrival, ZoneId zone) {
        public Message {
            Rules.text(sourceId, 128, false);
            Objects.requireNonNull(sender); Objects.requireNonNull(body);
            Objects.requireNonNull(arrival); Objects.requireNonNull(zone);
        }
    }
    public record Money(Currency currency, long minorUnits, int scale) {
        public Money {
            Objects.requireNonNull(currency);
            Rules.require(scale == currency.scale, "noncanonical scale");
        }
    }
    /** Offsets refer to the original body, measured in UTF-16 code units, end exclusive. */
    public record Span(int start, int end) {
        public Span { Rules.require(start >= 0 && end >= start, "span"); }
        boolean overlaps(Span other) { return start < other.end && other.start < end; }
    }
    public record EventTime(Instant instant, Precision precision, ZoneId zone, boolean fallback) {}
    public record Provenance(String sourceId, String packId, String revision, String templateId,
                             String outputId, String engine, Span amountSpan) {}
    public record Fact(String bankId, String account, Kind kind, Money money, Money originalAmount,
                       EventTime time, Instant arrival, Provenance provenance,
                       SemanticText reason, SemanticText channel) {
        public Fact(String bankId, String account, Kind kind, Money money, Money originalAmount,
                    EventTime time, Instant arrival, Provenance provenance) {
            this(bankId, account, kind, money, originalAmount, time, arrival, provenance, null, null);
        }
        public AccountState accountState() { return account == null ? AccountState.UNRESOLVED : AccountState.REFERENCED; }
    }
    public record Diagnostic(String templateKey, String outputId, String field, Code code) {}
    public record Result(Status status, List<Fact> facts, List<Diagnostic> diagnostics,
                         List<Provenance> matchedProvenance) {
        public Result {
            facts = ImmutableCollections.copyList(facts); diagnostics = ImmutableCollections.copyList(diagnostics);
            matchedProvenance = ImmutableCollections.copyList(matchedProvenance);
        }
    }
    public record SenderOverlap(String sender, List<String> templateKeys) {
        public SenderOverlap { templateKeys = ImmutableCollections.copyList(templateKeys); }
    }

    private final Map<String, List<Template>> index;
    private final List<SenderOverlap> overlaps;

    public Parser(List<Template> templates) {
        Rules.require(templates.size() <= MAX_TEMPLATES, "template count");
        Map<String, List<Template>> mutable = new HashMap<>();
        Set<String> keys = new HashSet<>();
        Map<String, String> revisions = new HashMap<>();
        for (Template t : ImmutableCollections.copyList(templates)) {
            Rules.require(keys.add(t.key()), "duplicate template ID");
            String previous = revisions.putIfAbsent(t.packId(), t.revision());
            Rules.require(previous == null || previous.equals(t.revision()), "mixed pack revisions");
            for (String sender : t.senders()) mutable.computeIfAbsent(sender, s -> new ArrayList<>()).add(t);
        }
        Map<String, List<Template>> frozen = new HashMap<>();
        List<SenderOverlap> collisions = new ArrayList<>();
        mutable.forEach((sender, values) -> {
            List<Template> sorted = ImmutableCollections.sortedList(values, Comparator.comparing(Template::key));
            frozen.put(sender, sorted);
            if (sorted.size() > 1) collisions.add(new SenderOverlap(sender,
                    sorted.stream().map(Template::key).collect(Collectors.toList())));
        });
        index = ImmutableCollections.copyMap(frozen);
        overlaps = ImmutableCollections.sortedList(collisions, Comparator.comparing(SenderOverlap::sender));
    }

    /** Informational static overlaps; runtime guards determine actual conflicts. */
    public List<SenderOverlap> senderOverlaps() { return overlaps; }

    public Result parse(Message message) {
        try { return evaluate(message, new Work()); }
        catch (Problem e) {
            // Resource exhaustion is global: no candidate can publish partial results.
            return failed(e.status, e.code);
        }
    }

    private Result evaluate(Message message, Work work) {
        if (message.body().length() > MAX_INPUT || message.sender().length() > MAX_SENDER)
            return failed(Status.LIMIT_EXCEEDED, Code.INPUT_LIMIT);
        try {
            // Accept CRLF while preserving original offsets; bare CR is unsupported.
            Rules.text(message.sender(), MAX_SENDER, false);
            validateBody(message.body());
        } catch (IllegalArgumentException e) { return failed(Status.INVALID, Code.INVALID_INPUT); }
        if (lineCount(message.body()) > MAX_LINES) return failed(Status.LIMIT_EXCEEDED, Code.INPUT_LIMIT);
        List<Template> candidates = index.get(message.sender());
        if (candidates == null) return new Result(Status.UNKNOWN_SENDER, listOf(), listOf(), listOf());
        if (candidates.size() > MAX_CANDIDATES) return failed(Status.LIMIT_EXCEEDED, Code.CANDIDATE_LIMIT);
        List<List<Fact>> successes = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<Status> failures = new HashSet<>();
        for (Template template : candidates) {
            if (!matches(template, message.body(), work)) continue;
            List<Fact> facts = new ArrayList<>();
            List<Span> movements = new ArrayList<>();
            boolean valid = true;
            for (Output output : ImmutableCollections.sortedList(template.outputs(), Comparator.comparing(Output::id))) {
                try {
                    Capture region = capture(message.body(), new Span(0, message.body().length()),
                            output.region(), "region", work);
                    String account = account(message.body(), region.span(), output, template.key(), diagnostics, work);
                    ParsedMoney money = money(message.body(), region.span(), output.money(), "amount", work);
                    long units = money.money().minorUnits();
                    if (output.kind() == Kind.POSTED_MOVEMENT) {
                        Direction direction = direction(message.body(), region.span(), output.direction(), work);
                        if (units == 0) throw problem(Status.INVALID, Code.ZERO_MOVEMENT, "amount");
                        if (money.sign() != 0 && (money.sign() < 0) != (direction == Direction.DEBIT))
                            throw problem(Status.INVALID, Code.DIRECTION_CONFLICT, "amount");
                        if (money.sign() == 0 && direction == Direction.DEBIT) units = -units;
                        for (Span prior : movements) if (prior.overlaps(money.span()))
                            throw problem(Status.INVALID, Code.OVERLAPPING_MOVEMENTS, "amount");
                        movements.add(money.span());
                    }
                    Money original = output.originalAmount() == null ? null
                            : money(message.body(), region.span(), output.originalAmount(), "originalAmount", work).money();
                    if (original != null && original.minorUnits() < 0)
                        throw problem(Status.INVALID, Code.INVALID_MONEY, "originalAmount");
                    EventTime time = eventTime(message, region.span(), output.date(), template.key(),
                            output.id(), diagnostics, work);
                    Provenance provenance = new Provenance(message.sourceId(), template.packId(),
                            template.revision(), template.id(), output.id(), ENGINE, money.span());
                    SemanticText reason = optionalText(message.body(), region.span(), output.reason(), "reason",
                            template.key(), output.id(), diagnostics, work);
                    SemanticText channel = optionalText(message.body(), region.span(), output.channel(), "channel",
                            template.key(), output.id(), diagnostics, work);
                    facts.add(new Fact(template.bankId(), account, output.kind(),
                            new Money(money.money().currency(), units, money.money().scale()), original,
                            time, message.arrival(), provenance, reason, channel));
                } catch (Problem e) {
                    if (e.code == Code.WORK_LIMIT) throw e;
                    valid = false; failures.add(e.status);
                    diagnostics.add(new Diagnostic(template.key(), output.id(), e.field, e.code));
                }
            }
            if (valid) successes.add(ImmutableCollections.copyList(facts));
        }
        diagnostics.sort(Comparator.comparing(Diagnostic::templateKey).thenComparing(Diagnostic::outputId)
                .thenComparing(Diagnostic::field).thenComparing(d -> d.code().name()));
        if (successes.isEmpty()) {
            Status status = failures.isEmpty() ? Status.NO_MATCH : failureStatus(failures);
            return new Result(status, listOf(), diagnostics, listOf());
        }
        Map<Semantic, Integer> first = semantics(successes.get(0));
        boolean conflict = !failures.isEmpty() || successes.stream().anyMatch(s -> !semantics(s).equals(first));
        if (conflict) {
            diagnostics.add(new Diagnostic("", "", "template", Code.TEMPLATE_CONFLICT));
            return new Result(Status.AMBIGUOUS, listOf(), diagnostics, listOf());
        }
        List<Fact> reconciled = reconcileMetadata(successes, diagnostics, work);
        diagnostics.sort(Comparator.comparing(Diagnostic::templateKey).thenComparing(Diagnostic::outputId)
                .thenComparing(Diagnostic::field).thenComparing(d -> d.code().name()));
        return new Result(Status.PARSED, reconciled, diagnostics,
                successes.stream().flatMap(List::stream).map(Fact::provenance).collect(Collectors.toList()));
    }

    private static Status failureStatus(Set<Status> failures) {
        for (Status s : listOf(Status.LIMIT_EXCEEDED, Status.AMBIGUOUS, Status.OVERFLOW, Status.INVALID))
            if (failures.contains(s)) return s;
        return Status.ABSENT;
    }
    private static Result failed(Status status, Code code) {
        return new Result(status, listOf(), listOf(new Diagnostic("", "", "input", code)), listOf());
    }
    private record Semantic(String bank, String account, Kind kind, Money money, Money original,
                            EventTime time) {}
    private static Semantic semantic(Fact f) {
        return new Semantic(f.bankId(), f.account(), f.kind(), f.money(), f.originalAmount(), f.time());
    }
    private static Map<Semantic, Integer> semantics(List<Fact> facts) {
        Map<Semantic, Integer> result = new HashMap<>();
        for (Fact f : facts) result.merge(semantic(f), 1, Integer::sum);
        return result;
    }

    /** Runs only after complete financial multiset equivalence. Output IDs/order are
     * not evidence of cross-template occurrence correspondence. For duplicate financial
     * groups, keep a metadata field only when every member across all candidates agrees
     * (including absence); otherwise omit it for the entire group. This deliberately
     * conservative policy does not infer correspondence from capture spans either.
     * Within a single template, the declared per-output associations remain intact. */
    private static List<Fact> reconcileMetadata(List<List<Fact>> successes, List<Diagnostic> diagnostics, Work work) {
        if (successes.size() == 1) return successes.get(0);
        Map<Semantic, List<Fact>> groups = new HashMap<>();
        for (List<Fact> candidate : successes) for (Fact fact : candidate) {
            work.spend(MAX_FIELD); // Bounded account-key hashing/equality and group insertion.
            groups.computeIfAbsent(semantic(fact), ignored -> new ArrayList<>()).add(fact);
        }
        Map<Semantic, Metadata> reconciled = new HashMap<>();
        groups.forEach((key, group) -> reconciled.put(key, metadata(group, successes.size(), work)));
        List<Fact> result = new ArrayList<>();
        for (Fact representative : successes.get(0)) {
            Metadata metadata = reconciled.get(semantic(representative));
            Provenance p = representative.provenance();
            if (metadata.reasonConflict()) diagnostics.add(new Diagnostic(p.packId() + "/" + p.templateId(),
                    p.outputId(), "reason", metadata.code()));
            if (metadata.channelConflict()) diagnostics.add(new Diagnostic(p.packId() + "/" + p.templateId(),
                    p.outputId(), "channel", metadata.code()));
            result.add(new Fact(representative.bankId(), representative.account(), representative.kind(),
                    representative.money(), representative.originalAmount(), representative.time(),
                    representative.arrival(), p, metadata.reason(), metadata.channel()));
        }
        return result;
    }

    private record Metadata(SemanticText reason, SemanticText channel, boolean reasonConflict,
                            boolean channelConflict, Code code) {}
    private static Metadata metadata(List<Fact> group, int candidateCount, Work work) {
        Fact first = group.get(0);
        boolean reasonConflict = false, channelConflict = false;
        for (Fact fact : group) {
            work.spend(2 * (MAX_FIELD + 64)); // Maximum metadata text plus semantic-ID comparisons.
            reasonConflict |= !Objects.equals(first.reason(), fact.reason());
            channelConflict |= !Objects.equals(first.channel(), fact.channel());
        }
        return new Metadata(reasonConflict ? null : first.reason(), channelConflict ? null : first.channel(),
                reasonConflict, channelConflict,
                group.size() > candidateCount ? Code.OPTIONAL_ASSOCIATION_AMBIGUOUS : Code.OPTIONAL_CONFLICT);
    }

    private static boolean matches(Template template, String body, Work work) {
        for (Guard guard : template.guards()) {
            Span scope = line(body, new Span(0, body.length()), guard.line(), work);
            boolean found = scope != null && indexOf(body, guard.literal(), scope.start(), scope.end(), work) >= 0;
            if (found == guard.excluded()) return false;
        }
        return true;
    }

    private record Capture(String text, Span span, int rawLength) {}
    private static Capture capture(String body, Span region, Field field, String name, Work work) {
        Span scope = line(body, region, field.line(), work);
        if (scope == null) throw problem(Status.ABSENT, Code.REQUIRED_ABSENT, name);
        if (field.terminated()) {
            int end = scope.end();
            if (end < region.end() && body.charAt(end) == '\r') end++;
            if (end >= region.end() || body.charAt(end) != '\n')
                throw problem(Status.ABSENT, Code.REQUIRED_ABSENT, name);
        }
        int start = scope.start(), end = scope.end();
        if (!field.after().isEmpty()) {
            int anchor = unique(body, field.after(), start, end, name, work);
            start = anchor + field.after().length();
        }
        if (!field.before().isEmpty()) {
            end = indexOf(body, field.before(), start, end, work);
            if (end < 0) throw problem(Status.ABSENT, Code.REQUIRED_ABSENT, name);
        }
        if (start == end) throw problem(Status.ABSENT, Code.REQUIRED_ABSENT, name);
        if (end - start > field.maxLength()) throw problem(Status.LIMIT_EXCEEDED, Code.FIELD_LIMIT, name);
        if (field.normalization() != Normalization.NONE || field.numeric() != null)
            work.spend(8 * (end - start)); // Bounded normalization, token splitting and validation passes.
        String text = normalizeField(body.substring(start, end), field.normalization());
        if (text.isEmpty()) throw problem(Status.ABSENT, Code.REQUIRED_ABSENT, name);
        if (field.numeric() != null) text = numeric(text, field.numeric(), name, work);
        // The span encloses the original capture, including any normalized edge marks.
        return new Capture(text, new Span(start, end), end - start);
    }

    private static String normalizeField(String value, Normalization policy) {
        if (policy == Normalization.NONE) return value;
        value = normalizeDigits(value, Digits.ASCII_PERSIAN_ARABIC);
        if (policy == Normalization.DIGITS) return value;
        if (policy == Normalization.ACCOUNT) {
            int start = 0, end = value.length();
            while (start < end && accountEdge(value.charAt(start))) start++;
            while (end > start && accountEdge(value.charAt(end - 1))) end--;
            return value.substring(start, end);
        }
        StringBuilder result = new StringBuilder(value.length());
        boolean space = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (bidi(c) || c == '\u200c' || c == '\u200d') continue;
            if (c == ' ' || c >= '\t' && c <= '\r') { space = result.length() > 0; continue; }
            if (space) { result.append(' '); space = false; }
            if (policy == Normalization.CHANNEL) {
                if (c == '\u064a') c = '\u06cc';
                if (c == '\u0643') c = '\u06a9';
            }
            result.append(c);
        }
        return result.toString();
    }
    private static boolean bidi(char c) {
        return c == '\u200e' || c == '\u200f' || c >= '\u202a' && c <= '\u202e'
                || c >= '\u2066' && c <= '\u2069';
    }
    private static boolean accountEdge(char c) { return c == ' ' || c == '\t' || bidi(c); }
    private static String trimBidi(String text) {
        int start = 0, end = text.length();
        while (start < end && bidi(text.charAt(start))) start++;
        while (end > start && bidi(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }
    private static boolean numericChar(int c) { return Character.isDigit(c) || c == '.' || c == ','; }

    private static String numeric(String text, NumericShape shape, String name, Work work) {
        String found = null;
        for (int start = 0; start < text.length();) {
            work.spend(1);
            if (!numericChar(text.codePointAt(start))) {
                if (shape.mode() != NumericMode.UNIQUE) break;
                start += Character.charCount(text.codePointAt(start)); continue;
            }
            int end = start;
            while (end < text.length() && numericChar(text.codePointAt(end))) {
                work.spend(2); end += Character.charCount(text.codePointAt(end));
            }
            String token = text.substring(start, end);
            List<String> segments = split(token, '.');
            boolean valid = segments.size() == shape.segments().size();
            if (valid) for (int i = 0; i < segments.size(); i++) {
                Width width = shape.segments().get(i);
                String part = segments.get(i);
                valid &= asciiDigits(part) && part.length() >= width.min() && part.length() <= width.max();
            }
            if (valid && (shape.mode() != NumericMode.EXACT || end == text.length())) {
                if (found != null) throw problem(Status.AMBIGUOUS, Code.CAPTURE_AMBIGUOUS, name);
                found = token;
            }
            if (shape.mode() != NumericMode.UNIQUE) break;
            start = end;
        }
        if (found == null) throw problem(Status.INVALID, Code.INVALID_FIELD, name);
        return found;
    }

    private static String account(String body, Span region, Output output, String template,
                                  List<Diagnostic> diagnostics, Work work) {
        // An output that asks for no account is not a message whose account went missing: there was
        // never one to resolve, so there is nothing to report and the fact simply states none.
        // Reporting an unresolved account here would put a diagnostic on every message of every
        // pack that legitimately reads no account -- a community card statement, say -- and a
        // diagnostic that fires on a complete read is one no consumer can be asked to trust.
        if (output.account() == null) return null;
        try {
            String text = capture(body, region, output.account(), "account", work).text();
            validateAccount(text);
            return text;
        } catch (Problem e) {
            if (!output.accountOptional() || e.status == Status.LIMIT_EXCEEDED || e.status == Status.AMBIGUOUS) throw e;
            diagnostics.add(new Diagnostic(template, output.id(), "account", e.code));
            diagnostics.add(new Diagnostic(template, output.id(), "account", Code.ACCOUNT_UNRESOLVED));
            return null;
        }
    }

    private static SemanticText optionalText(String body, Span region, TextRule rule, String name,
                                             String template, String output, List<Diagnostic> diagnostics, Work work) {
        if (rule == null) return null;
        try {
            Capture captured = capture(body, region, rule.field(), name, work);
            work.spend(2 * captured.text().length()); // Code-point guard and finite-map lookup.
            if (captured.rawLength() < rule.minLength() || captured.rawLength() > rule.maxLength())
                throw problem(Status.INVALID, Code.INVALID_TEXT, name);
            if (rule.digitFree()) for (int i = 0; i < captured.text().length();) {
                int c = captured.text().codePointAt(i);
                if (Character.isDigit(c))
                    throw problem(Status.INVALID, Code.INVALID_TEXT, name);
                i += Character.charCount(c);
            }
            String id = rule.mapping().get(captured.text());
            if (id == null) throw problem(Status.INVALID, Code.UNKNOWN_TEXT, name);
            return new SemanticText(id, captured.text());
        } catch (Problem e) {
            if (e.status == Status.LIMIT_EXCEEDED) throw e;
            diagnostics.add(new Diagnostic(template, output, name,
                    e.code == Code.REQUIRED_ABSENT ? Code.OPTIONAL_ABSENT : e.code));
            return null;
        }
    }
    private static int unique(String body, String anchor, int start, int end, String field, Work work) {
        int first = indexOf(body, anchor, start, end, work);
        if (first < 0) throw problem(Status.ABSENT, Code.REQUIRED_ABSENT, field);
        if (indexOf(body, anchor, first + 1, end, work) >= 0)
            throw problem(Status.AMBIGUOUS, Code.CAPTURE_AMBIGUOUS, field);
        return first;
    }
    /** Explicit bounded literal search, never searches outside the declared region. */
    private static int indexOf(String body, String literal, int start, int end, Work work) {
        for (int i = start; i <= end - literal.length(); i++) {
            work.spend(literal.length());
            if (body.regionMatches(i, literal, 0, literal.length())) return i;
        }
        return -1;
    }
    private static Span line(String body, Span region, int selected, Work work) {
        if (selected == -1) return region;
        int start = region.start();
        for (int number = 0; start <= region.end(); number++) {
            int end = start;
            while (end < region.end() && body.charAt(end) != '\n') end++;
            work.spend(end - start + 1);
            int contentEnd = end > start && body.charAt(end - 1) == '\r' ? end - 1 : end;
            if (number == selected) return new Span(start, contentEnd);
            if (end == region.end()) break;
            start = end + 1;
        }
        return null;
    }
    private static int lineCount(String body) {
        int count = 1;
        for (int i = 0; i < body.length(); i++) if (body.charAt(i) == '\n') count++;
        return count;
    }
    private static void validateBody(String body) {
        Rules.text(body.replace("\r\n", "\n"), MAX_INPUT, true);
    }
    private static void validateAccount(String account) {
        for (int i = 0; i < account.length(); i++) {
            char c = account.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c == '.' || c == '-'))
                throw problem(Status.INVALID, Code.INVALID_ACCOUNT, "account");
        }
    }

    private record ParsedMoney(Money money, int sign, Span span) {}
    private static ParsedMoney money(String body, Span region, MoneyRule rule, String field, Work work) {
        Currency currency = rule.currency().fixed();
        if (rule.currency().token() != null) {
            String token = capture(body, region, rule.currency().token(), field + ".currency", work).text();
            Currency mapped = rule.currency().mapping().get(token);
            if (mapped == null) throw problem(Status.INVALID, Code.UNKNOWN_CURRENCY, field + ".currency");
            if (currency != null && currency != mapped)
                throw problem(Status.INVALID, Code.CURRENCY_CONFLICT, field + ".currency");
            currency = mapped;
        }
        Capture captured = capture(body, region, rule.amount(), field, work);
        String text = normalizeDigits(captured.text(), rule.digits());
        int sign = 0;
        if (rule.sign() == Rules.Sign.TRAILING) {
            // Enclosing bidi marks are layout noise, never digits; TRAILING amounts
            // may also separate the digits and the sign with spaces.
            text = trimBidi(text.trim());
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') end--;
            if (end > 0 && (text.charAt(end - 1) == '-' || text.charAt(end - 1) == '+')) {
                sign = text.charAt(end - 1) == '-' ? -1 : 1;
                end--;
            }
            while (end > 0 && text.charAt(end - 1) == ' ') end--;
            text = text.substring(0, end).trim();
        } else if (!text.isEmpty() && (text.charAt(0) == '-' || text.charAt(0) == '+')) {
            sign = text.charAt(0) == '-' ? -1 : 1;
            text = text.substring(1);
        }
        int decimal = text.indexOf(rule.decimal());
        String integer = decimal < 0 ? text : text.substring(0, decimal);
        String fraction = decimal < 0 ? "" : text.substring(decimal + 1);
        if (decimal >= 0 && (fraction.isEmpty() || !asciiDigits(fraction)))
            throw problem(Status.INVALID, Code.INVALID_MONEY, field);
        if (integer.isEmpty()) {
            // ".11" is an amount below one unit, but only where the rule asks for it: an empty
            // integer part is otherwise a misaligned capture, and a capture that lost its integer
            // digits must keep failing loudly rather than quietly reading as a fraction.
            if (decimal < 0 || !rule.leadingPoint())
                throw problem(Status.INVALID, Code.INVALID_MONEY, field);
            integer = "0";
        }
        List<String> groups = split(integer, rule.group());
        if (groups.size() > 1) {
            int middle = rule.grouping() == Grouping.INDIAN ? 2 : 3;
            if (rule.grouping() == Grouping.NONE || groups.get(0).isEmpty()
                    || groups.get(0).length() > middle || groups.get(groups.size() - 1).length() != 3)
                throw problem(Status.INVALID, Code.INVALID_MONEY, field);
            for (int i = 1; i < groups.size() - 1; i++) if (groups.get(i).length() != middle)
                throw problem(Status.INVALID, Code.INVALID_MONEY, field);
        }
        for (String group : groups) if (!asciiDigits(group))
            throw problem(Status.INVALID, Code.INVALID_MONEY, field);
        // Extra source precision is rejected even when all excess digits are zero.
        int allowed = currency.scale + (rule.unitMultiplier() == 10 ? 1 : 0);
        if (fraction.length() > allowed) throw problem(Status.INVALID, Code.INVALID_MONEY, field);
        BigInteger unscaled = new BigInteger(String.join("", groups) + fraction);
        if (sign < 0) unscaled = unscaled.negate();
        try {
            long minor = new BigDecimal(unscaled, fraction.length())
                    .multiply(BigDecimal.valueOf(rule.unitMultiplier()))
                    .movePointRight(currency.scale).longValueExact();
            return new ParsedMoney(new Money(currency, minor, currency.scale), sign, captured.span());
        } catch (ArithmeticException e) { throw problem(Status.OVERFLOW, Code.MONEY_OVERFLOW, field); }
    }
    private static Direction direction(String body, Span region, DirectionRule rule, Work work) {
        Direction direction = rule.fixed();
        if (rule.token() != null) {
            String token = capture(body, region, rule.token(), "direction", work).text();
            Direction mapped = rule.mapping().get(token);
            if (mapped == null) throw problem(Status.INVALID, Code.UNKNOWN_DIRECTION, "direction");
            if (direction != null && direction != mapped)
                throw problem(Status.INVALID, Code.DIRECTION_CONFLICT, "direction");
            direction = mapped;
        }
        return direction;
    }
    private static List<String> split(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) if (delimiter != 0 && text.charAt(i) == delimiter) {
            parts.add(text.substring(start, i)); start = i + 1;
        }
        parts.add(text.substring(start));
        return parts;
    }
    private static boolean asciiDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }
    private static String normalizeDigits(String value, Digits policy) {
        if (policy == Digits.ASCII) return value;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u06f0' && c <= '\u06f9') c = (char) ('0' + c - '\u06f0');
            else if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + c - '\u0660');
            result.append(c);
        }
        return result.toString();
    }

    private static EventTime eventTime(Message message, Span region, DateRule rule, String template,
                                       String output, List<Diagnostic> diagnostics, Work work) {
        if (rule != null) {
            try { return date(message, region, rule, work); }
            catch (Problem e) {
                if (e.status == Status.LIMIT_EXCEEDED) throw e;
                diagnostics.add(new Diagnostic(template, output, e.field, e.code));
            }
        }
        ZoneId zone = rule != null && rule.options() != null ? rule.options().zone() : message.zone();
        return new EventTime(message.arrival(), Precision.ARRIVAL, zone, true);
    }
    private static EventTime date(Message message, Span region, DateRule rule, Work work) {
        String text = normalizeDigits(capture(message.body(), region, rule.field(), "date", work).text(), rule.digits());
        if (rule.options() != null) return extendedDate(message, text, rule, work);
        String date = text;
        int hour = 0, minute = 0;
        if (rule.withTime()) {
            if (text.length() != 16 || text.charAt(10) != ' ' || text.charAt(13) != ':')
                throw problem(Status.INVALID, Code.DATE_INVALID, "date");
            date = text.substring(0, 10);
            hour = number(text.substring(11, 13)); minute = number(text.substring(14, 16));
        }
        List<String> parts = split(date, rule.separator());
        if (parts.size() != 3) throw problem(Status.INVALID, Code.DATE_INVALID, "date");
        Set<LocalDateTime> interpretations = new HashSet<>();
        for (Order order : rule.orders()) {
            int yi = order == Order.YMD || order == Order.YDM ? 0 : 2;
            int mi = switch (order) { case YMD, DMY -> 1; case MDY -> 0; case YDM -> 2;
                default -> throw new IllegalStateException("Validated date order"); };
            int di = 3 - yi - mi;
            if (parts.get(yi).length() != 4 || parts.get(mi).length() != 2 || parts.get(di).length() != 2) continue;
            int year = number(parts.get(yi)), month = number(parts.get(mi)), day = number(parts.get(di));
            if (year == 0) continue;
            try { interpretations.add(LocalDate.of(year, month, day).atTime(hour, minute)); }
            catch (DateTimeException ignored) { /* Invalid layout is not a viable interpretation. */ }
        }
        if (interpretations.isEmpty()) throw problem(Status.INVALID, Code.DATE_INVALID, "date");
        // Resolve ambiguity before plausibility; the arrival window cannot choose a date order.
        if (interpretations.size() > 1) throw problem(Status.AMBIGUOUS, Code.DATE_AMBIGUOUS, "date");
        LocalDateTime local = interpretations.iterator().next();
        List<ZoneOffset> offsets = message.zone().getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw problem(Status.INVALID, Code.DATE_ZONE_INVALID, "date");
        if (offsets.size() != 1) throw problem(Status.AMBIGUOUS, Code.DATE_ZONE_AMBIGUOUS, "date");
        Instant instant = local.toInstant(offsets.get(0));
        java.time.Duration distance = java.time.Duration.between(message.arrival(), instant);
        if (distance.compareTo(rule.maxPast().negated()) < 0 || distance.compareTo(rule.maxFuture()) > 0)
            throw problem(Status.INVALID, Code.DATE_OUT_OF_WINDOW, "date");
        return new EventTime(instant, rule.withTime() ? Precision.MINUTE : Precision.DAY, message.zone(), false);
    }

    /** At most six orders and three neighboring years. Arrival may infer a year, never an order. */
    private static EventTime extendedDate(Message message, String text, DateRule rule, Work work) {
        work.spend(2 * text.length());
        DateOptions options = rule.options();
        String date = text;
        int hour = 0, minute = 0, second = 0;
        if (rule.withTime()) {
            int clockLength = options.seconds() ? 8 : 5;
            int boundary = text.length() - clockLength - 1;
            if (boundary < 1 || text.charAt(boundary) != options.timeSeparator())
                throw problem(Status.INVALID, Code.DATE_INVALID, "date");
            String clock = text.substring(boundary + 1);
            if (clock.charAt(2) != ':' || options.seconds() && clock.charAt(5) != ':')
                throw problem(Status.INVALID, Code.DATE_INVALID, "date");
            hour = number(clock.substring(0, 2)); minute = number(clock.substring(3, 5));
            if (options.seconds()) second = number(clock.substring(6, 8));
            if (hour > 23 || minute > 59 || second > 59)
                throw problem(Status.INVALID, Code.DATE_INVALID, "date");
            date = text.substring(0, boundary);
        }
        List<String> parts;
        if (options.layout() == DateLayout.COMPACT) {
            if (date.length() != 4) throw problem(Status.INVALID, Code.DATE_INVALID, "date");
            parts = listOf(date.substring(0, 2), date.substring(2));
        } else parts = split(date, rule.separator());
        boolean infer = options.year() == Year.NEIGHBOR;
        if (parts.size() != (infer ? 2 : 3)) throw problem(Status.INVALID, Code.DATE_INVALID, "date");
        // Check the small grammar before numeric conversion (no unchecked parseInt overflow).
        for (String part : parts) if (part.length() > 4 || !asciiDigits(part))
            throw problem(Status.INVALID, Code.DATE_INVALID, "date");
        int arrivalYear = 0;
        if (infer) try {
            LocalDate localArrival = message.arrival().atZone(options.zone()).toLocalDate();
            arrivalYear = options.calendar() == Calendar.JALALI ? JalaliCalendar.year(localArrival) : localArrival.getYear();
            if (arrivalYear < 1 || arrivalYear > 9999) throw new DateTimeException("Year range");
        } catch (DateTimeException e) { throw problem(Status.INVALID, Code.DATE_INVALID, "date"); }
        Set<Set<LocalDateTime>> layouts = new HashSet<>();
        for (Order order : rule.orders()) {
            int yi = order == Order.YMD || order == Order.YDM ? 0 : 2;
            int mi = switch (order) { case YMD, DMY, DM -> 1; case MDY, MD -> 0; case YDM -> 2; };
            int di = infer ? 1 - mi : 3 - yi - mi;
            int minWidth = options.variableWidth() ? 1 : 2;
            if (!width(parts.get(mi), minWidth, 2) || !width(parts.get(di), minWidth, 2)) continue;
            if (!infer && parts.get(yi).length() != (options.year() == Year.FULL ? 4 : 2)) continue;
            int year = infer ? arrivalYear : number(parts.get(yi)) + options.yearBase();
            int month = number(parts.get(mi)), day = number(parts.get(di));
            Set<LocalDateTime> candidates = new HashSet<>();
            for (int offset = infer ? -1 : 0; offset <= (infer ? 1 : 0); offset++) {
                work.spend(128); // Upper bound for fixed breakpoint/calendar arithmetic per candidate.
                try {
                    int candidateYear = year + offset;
                    if (candidateYear < 1 || candidateYear > 9999) continue;
                    LocalDate local = options.calendar() == Calendar.JALALI
                            ? JalaliCalendar.toGregorian(candidateYear, month, day)
                            : LocalDate.of(candidateYear, month, day);
                    candidates.add(local.atTime(hour, minute, second));
                } catch (DateTimeException ignored) { /* Validate leap days independently in every year. */ }
            }
            if (!candidates.isEmpty()) layouts.add(candidates);
        }
        if (layouts.isEmpty()) throw problem(Status.INVALID, Code.DATE_INVALID, "date");
        if (layouts.size() > 1) throw problem(Status.AMBIGUOUS, Code.DATE_AMBIGUOUS, "date");
        Set<Instant> instants = new HashSet<>();
        Code zoneFailure = null;
        for (LocalDateTime local : layouts.iterator().next()) {
            List<ZoneOffset> offsets = options.zone().getRules().getValidOffsets(local);
            if (offsets.size() != 1) {
                boolean relevant = !infer;
                if (offsets.isEmpty()) {
                    java.time.zone.ZoneOffsetTransition transition = options.zone().getRules().getTransition(local);
                    relevant |= inWindow(local.toInstant(transition.getOffsetBefore()), message, rule)
                            || inWindow(local.toInstant(transition.getOffsetAfter()), message, rule);
                } else for (ZoneOffset offset : offsets)
                    relevant |= inWindow(local.toInstant(offset), message, rule);
                if (relevant) {
                    Code failure = offsets.isEmpty() ? Code.DATE_ZONE_INVALID : Code.DATE_ZONE_AMBIGUOUS;
                    if (zoneFailure == null || failure == Code.DATE_ZONE_AMBIGUOUS) zoneFailure = failure;
                }
                continue;
            }
            Instant instant = local.toInstant(offsets.get(0));
            if (inWindow(instant, message, rule)) instants.add(instant);
        }
        if (zoneFailure != null) throw problem(zoneFailure == Code.DATE_ZONE_AMBIGUOUS ? Status.AMBIGUOUS
                : Status.INVALID, zoneFailure, "date");
        if (instants.isEmpty()) throw problem(Status.INVALID, Code.DATE_OUT_OF_WINDOW, "date");
        if (instants.size() > 1) throw problem(Status.AMBIGUOUS, Code.DATE_AMBIGUOUS, "date");
        Precision precision = options.seconds() ? Precision.SECOND : rule.withTime() ? Precision.MINUTE : Precision.DAY;
        return new EventTime(instants.iterator().next(), precision, options.zone(), false);
    }
    private static boolean width(String text, int min, int max) { return text.length() >= min && text.length() <= max; }
    private static boolean inWindow(Instant instant, Message message, DateRule rule) {
        java.time.Duration distance = java.time.Duration.between(message.arrival(), instant);
        return distance.compareTo(rule.maxPast().negated()) >= 0 && distance.compareTo(rule.maxFuture()) <= 0;
    }
    private static int number(String text) {
        if (!asciiDigits(text)) throw problem(Status.INVALID, Code.DATE_INVALID, "date");
        return Integer.parseInt(text);
    }
    private static Problem problem(Status status, Code code, String field) { return new Problem(status, code, field); }
    private static final class Work {
        private int remaining = MAX_WORK;
        void spend(int amount) {
            remaining -= amount;
            if (remaining < 0) throw problem(Status.LIMIT_EXCEEDED, Code.WORK_LIMIT, "input");
        }
    }
    private static final class Problem extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status;
        final Code code;
        final String field;
        Problem(Status status, Code code, String field) {
            super(code.name(), null, false, false);
            this.status = status; this.code = code; this.field = field;
        }
    }
}
