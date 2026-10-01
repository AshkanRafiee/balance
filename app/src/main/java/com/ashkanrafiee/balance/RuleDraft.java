package com.ashkanrafiee.balance;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A rule a reader builds without writing any of the prototype's language: one example message,
 * the parts they highlighted, and a few plain choices become one strict pack document.
 *
 * <p>This class is deliberately Android-free. Everything a reader can produce is decided here, so
 * the tests can assert exactly what the wizard shows them, and the activity is left with drawing
 * controls and localizing messages.
 *
 * <p>The translation is bounded on purpose. A value is captured by the words around it (a prefix
 * that must appear exactly once, and a suffix that ends it), never by position in a whole message:
 * a rule that matched "the fourth number after the word X" would read the wrong thing the first
 * time a bank reworded its SMS. When a message has no shape this can describe safely, the draft
 * reports a plain problem instead of guessing -- guessing silently is what made the original
 * scanner misread transactions, and the whole point of the builder is to be the opposite.
 */
final class RuleDraft {
    /** Anchors bound the text a reader recognizes. Long enough to identify a phrase, short
     *  enough that the core never spends time over a long literal. */
    static final int MAX_ANCHOR = 48;
    static final int MAX_FIELD = 256;
    static final int MAX_REGION = 1024;
    /** Room for a value that is longer in another message ("1,000" against "1,000,000,000"). */
    private static final int SLACK = 12;
    private static final int MAX_LITERAL = 128;
    /** What the engine allows a bank name to be. Mirrors PackDocument's own bound. */
    private static final int MAX_BANK_NAME = 128;
    /** What the engine allows a sender to be, and how many lines a message may span. */
    private static final int MAX_SENDER = 128;
    private static final int MAX_LINES = 256;
    /** Iranian messages state amounts in rials or tomans, and a reader should not have to know
     *  which one their bank used. */
    private static final String TOMAN = "تومان";
    private static final String RIAL = "ریال";

    enum Role { AMOUNT, BALANCE, DATE, ACCOUNT }

    /** What the message says, in the reader's words. UNSUPPORTED is the explicit way out. */
    enum Shape {
        BALANCE, MOVEMENT, BOTH, UNSUPPORTED
    }

    enum Calendar { GREGORIAN, JALALI }

    /** Why a draft cannot be built yet. The activity turns these into localized text; tests
     *  assert the code, so a wording change never breaks them. */
    enum Code {
        NO_MESSAGE, NO_SENDER, NO_BANK, MISSING_AMOUNT, MISSING_BALANCE, CROSSES_LINE,
        OVERLAP, NO_PREFIX, NOT_NUMERIC, DATE_NO_SEPARATOR, DATE_YEAR, DATE_NO_TIME,
        SENDER_TOO_LONG, BANK_TOO_LONG, TOO_MANY_LINES, EMPTY_AROUND
    }

    static final class Problem {
        final Code code;
        final String detail;

        Problem(Code code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return detail.isEmpty() ? code.name() : code + "(" + detail + ")";
        }
    }

    /** One highlighted stretch of the example and what it means. */
    static final class Anchor {
        final int start;
        final int end;
        final Role role;

        Anchor(int start, int end, Role role) {
            this.start = start;
            this.end = end;
            this.role = role;
        }

        String text(RuleDraft draft) {
            return draft.body.substring(start, end);
        }

        @Override
        public String toString() {
            return role + "[" + start + "," + end + ")";
        }
    }

    /** Where a value sits and what bounds it. Anchoring that reaches across a line break changes
     *  the region from one line to the whole message, so the two travel together. */
    private record Anchored(int line, String after, String before) {}

    String sender = "";
    String bankName = "";
    String country = "IR";
    String body = "";
    Shape shape = Shape.MOVEMENT;
    /** CREDIT or DEBIT as words; the activity offers the two. */
    String direction = "DEBIT";
    String currency = "IRR";
    Calendar calendar = Calendar.JALALI;
    boolean varyingDigits;
    boolean withTime;
    /** Words that must not appear, one per line: the reader names what would mean this is a
     *  different message. A guard that cannot be shown to be present is not written. */
    final List<String> exclusions = new ArrayList<>();
    private final Map<Role, Anchor> anchors = new LinkedHashMap<>();

    /** Records one highlighted stretch for a role, replacing whatever was highlighted for it
     *  before: a reader correcting a selection is normal, not an error. */
    void highlight(int start, int end, Role role) {
        int from = Math.max(0, Math.min(start, end));
        int to = Math.min(body.length(), Math.max(start, end));
        if (to <= from) return;
        anchors.put(role, new Anchor(from, to, role));
    }

    void clear(Role role) {
        anchors.remove(role);
    }

    Anchor anchor(Role role) {
        return anchors.get(role);
    }

    /** What the reader highlighted, in reading order. */
    List<Anchor> ordered() {
        List<Anchor> all = new ArrayList<>(anchors.values());
        all.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start)
                : Integer.compare(a.end, b.end));
        return all;
    }

    /** An empty draft never produces a document: the builder asks for what is missing first. */
    boolean empty() {
        return sender.trim().isEmpty() && bankName.trim().isEmpty() && body.isEmpty();
    }

    /**
     * Whether this draft may be built, tested, or offered to a reader. A message the reader said
     * this builder cannot read is never built into a rule: an empty rule would look installed and
     * read nothing, which is the one outcome worse than saying so.
     */
    boolean ready() {
        return shape != Shape.UNSUPPORTED && problems().isEmpty();
    }

    /**
     * Everything standing between this draft and a valid document, most important first. An empty
     * list means the draft is buildable and can be tested against the real engine.
     */
    List<Problem> problems() {
        List<Problem> found = new ArrayList<>();
        if (body.isEmpty()) {
            found.add(new Problem(Code.NO_MESSAGE, ""));
            return found;
        }
        if (shape == Shape.UNSUPPORTED) return found;
        if (bankName.trim().isEmpty()) found.add(new Problem(Code.NO_BANK, ""));
        if (sender.trim().isEmpty()) found.add(new Problem(Code.NO_SENDER, ""));
        if (shape == Shape.MOVEMENT && anchor(Role.AMOUNT) == null) {
            found.add(new Problem(Code.MISSING_AMOUNT, ""));
        }
        if ((shape == Shape.BALANCE || shape == Shape.BOTH) && anchor(Role.BALANCE) == null) {
            found.add(new Problem(Code.MISSING_BALANCE, ""));
        }
        for (Anchor anchor : ordered()) {
            int line = body.indexOf('\n', anchor.start);
            if (line >= 0 && line < anchor.end) {
                found.add(new Problem(Code.CROSSES_LINE, anchor.text(this)));
                continue;
            }
            if (isMoney(anchor) && !hasDigits(anchor.text(this))) {
                found.add(new Problem(Code.NOT_NUMERIC, anchor.text(this)));
            }
        }
        for (int i = 1; i < ordered().size(); i++) {
            if (ordered().get(i).start < ordered().get(i - 1).end) {
                found.add(new Problem(Code.OVERLAP, ""));
                break;
            }
        }
        for (Anchor anchor : ordered()) {
            if (!anchored(anchor).after().isEmpty()) continue;
            if (anchor.role == Role.DATE && lineStart(anchor.start) == anchor.start) continue;
            // Nothing before the value anywhere in the message: the rule would have to start the
            // line and read to the suffix, which on a line with two numbers reads whichever one
            // comes first.
            found.add(new Problem(Code.NO_PREFIX, anchor.text(this)));
        }
        // The engine refuses these, and a rule the builder calls ready but the engine rejects is
        // the worst outcome here: the reader sees "Install", presses it, and is told nothing more
        // specific than that it failed. So the same bounds are checked before the offer is made.
        if (sender.trim().length() > MAX_SENDER) {
            found.add(new Problem(Code.SENDER_TOO_LONG, sender.trim()));
        }
        if (bankName.trim().length() > MAX_BANK_NAME) {
            found.add(new Problem(Code.BANK_TOO_LONG, bankName.trim()));
        }
        if (lineCount(body) > MAX_LINES - 1) found.add(new Problem(Code.TOO_MANY_LINES, ""));
        // Two anchors on either side of a value that come out identical describe a region with no
        // width, which the engine rejects outright.
        for (Anchor anchor : ordered()) {
            Anchored region = anchored(anchor);
            if (!region.after().isEmpty() && region.after().equals(region.before())) {
                found.add(new Problem(Code.EMPTY_AROUND, anchor.text(this)));
                break;
            }
        }
        Anchor date = anchor(Role.DATE);
        if (date != null) {
            String span = body.substring(date.start, date.end);
            if (separator(span) == null && !span.contains(":")) {
                found.add(new Problem(Code.DATE_NO_SEPARATOR, span));
            }
            if (span.length() > MAX_FIELD) found.add(new Problem(Code.DATE_YEAR, span));
            // The reader said the date carries a time, so the highlighted date has to include it:
            // the rule reads exactly what was highlighted, and widening the reader's selection
            // without being asked would be the builder deciding what they meant.
            if (withTime && !carriesClock(span)) {
                found.add(new Problem(Code.DATE_NO_TIME, span));
            }
        }
        return found;
    }

    /** The document the wizard shows, installs, or offers to share. Throws when the draft is not
     *  buildable, because the only caller that gets here has already cleared {@link #ready()}. */
    Map<String, Object> document() {
        if (!ready()) throw new IllegalStateException("draft is incomplete");
        // A reader's pack is community provenance: nobody can claim it is official, and the store
        // rewrites it to LOCAL when it is installed on this device.
        Map<String, Object> bank = map("id", slug(), "country", country(), "name", bankName.trim(),
                "provenance", "COMMUNITY");
        return map("schema", "prototype-1", "id", "builder." + slug(), "revision", "builder-1",
                "bank", bank, "templates",
                List.of(map("id", "builder", "senders", List.of(sender.trim()), "guards", guards(),
                        "outputs", outputs())));
    }

    /**
     * What the rule matches on: a distinctive phrase from the message itself, so the rule cannot
     * claim an unrelated message from the same sender, plus whatever the reader said must not be
     * there. The core requires at least one guard that must be present.
     */
    private List<Map<String, Object>> guards() {
        List<Map<String, Object>> guards = new ArrayList<>();
        String phrase = phrase();
        if (!phrase.isEmpty()) guards.add(map("line", 0, "literal", phrase, "excluded", false));
        for (String exclusion : exclusions) {
            String word = exclusion.trim();
            if (word.isEmpty() || word.length() > MAX_LITERAL || body.indexOf(word) < 0) continue;
            guards.add(map("line", -1, "literal", word, "excluded", true));
        }
        // Short enough to stay inside the core's shared work budget on a long message: the literal
        // scan charges its length at every position, so a 128-character catch-all would make a rule
        // written from a big message give up (LIMIT_EXCEEDED) on perfectly ordinary ones after it.
        if (guards.isEmpty()) guards.add(map("line", -1, "literal", body.length() > MAX_ANCHOR
                ? body.substring(0, MAX_ANCHOR) : body, "excluded", false));
        return guards;
    }

    /** The words the rule is recognized by: the opening phrase of the example, or, when the
     *  message starts with the value itself, the words that follow it. */
    private String phrase() {
        List<Anchor> marks = ordered();
        if (marks.isEmpty()) return "";
        Anchor first = marks.get(0);
        int limit = first.start > 0 ? first.start : -1;
        if (limit <= 0) limit = marks.get(marks.size() - 1).end;
        if (limit <= 0 || limit > body.length()) return "";
        String opening = body.substring(0, limit).trim();
        if (opening.length() > MAX_LITERAL) opening = opening.substring(0, MAX_LITERAL).trim();
        return opening;
    }

    private List<Map<String, Object>> outputs() {
        List<Map<String, Object>> outputs = new ArrayList<>();
        if (shape == Shape.BALANCE || shape == Shape.BOTH) outputs.add(balance());
        if (shape == Shape.MOVEMENT || shape == Shape.BOTH) outputs.add(movement());
        return outputs;
    }

    private Map<String, Object> balance() {
        Anchor anchor = anchor(Role.BALANCE);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "builder-balance");
        output.put("region", region());
        output.put("kind", "BOOKED_BALANCE");
        output.put("money", money(anchor));
        account(output, anchor(Role.ACCOUNT));
        return output;
    }

    private Map<String, Object> movement() {
        Anchor anchor = anchor(Role.AMOUNT);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "builder-movement");
        output.put("region", region());
        output.put("kind", "POSTED_MOVEMENT");
        output.put("direction", map("fixed", direction));
        output.put("money", money(anchor));
        Anchor date = anchor(Role.DATE);
        if (date != null) output.put("date", date(date));
        account(output, anchor(Role.ACCOUNT));
        return output;
    }

    /** A movement with an account is attached to it; a balance that mentions no account belongs
     *  to no account, which is what accountOptional records. */
    private void account(Map<String, Object> output, Anchor account) {
        if (account == null) {
            output.put("accountOptional", true);
            return;
        }
        Map<String, Object> field = selector(account);
        field.put("normalization", "ACCOUNT");
        output.put("account", field);
    }

    private Map<String, Object> region() {
        return map("line", -1, "after", "", "before", "", "maxLength", MAX_REGION);
    }

    /**
     * The money rule for one highlighted value. The reader's own words anchor it; the digits
     * policy says which numerals the bank wrote in, so the same rule reads a message that spells
     * the amount in a different script.
     */
    private Map<String, Object> money(Anchor anchor) {
        String span = anchor.text(this);
        Numbers numbers = numbers(span);
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", selector(anchor));
        money.put("currency", map("fixed", unit()));
        money.put("decimal", numbers.decimal());
        money.put("group", numbers.group());
        money.put("grouping", numbers.group().isEmpty() ? "NONE" : "WESTERN");
        money.put("digits", digits(span));
        money.put("unitMultiplier", multiplier());
        return money;
    }

/**
     * The selector for a highlighted value: the line it is on, the words before it (which must
     * appear exactly once there), the words that end it, and how long it may be. Amounts and dates
     * stay raw on purpose -- a rule must not reshape the value the bank printed, only find it --
     * so the cap is the reader's slack, not a guess about digit groups.
     */
    private Map<String, Object> selector(Anchor anchor) {
        Anchored at = anchored(anchor);
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("line", at.line());
        field.put("after", at.after());
        field.put("before", at.before());
        field.put("maxLength", maxLength(anchor, at));
        return field;
    }

    /**
     * The date rule. Only dates the reader can read as dates are written: with a separator and an
     * order the reader chose, or without a year and the year the message was received.
     */
    private Map<String, Object> date(Anchor anchor) {
        String span = anchor.text(this);
        Map<String, Object> date = new LinkedHashMap<>();
        date.put("calendar", calendar.name());
        date.put("field", selector(anchor));
        date.put("orders", orders(span));
        date.put("separator", separator(span) == null ? "/" : separator(span));
        date.put("withTime", withTime);
        date.put("digits", digits(span));
        // Thirty days of slack matches the shipped packs: a message read late is still the message
        // the reader showed us, and anything older is not this rule's business.
        date.put("maxPastSeconds", 2592000);
        date.put("maxFutureSeconds", 172800);
        if (calendar == Calendar.JALALI) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("year", hasYear(span) ? "FULL" : "NEIGHBOR");
            options.put("yearBase", 0);
            options.put("variableWidth", false);
            options.put("layout", "SEPARATED");
            options.put("timeSeparator", " ");
            options.put("seconds", false);
            options.put("zone", ZoneId.systemDefault().getId());
            date.put("options", options);
        }
        return date;
    }

    /**
     * How much raw text the rule may enclose. The reader's own value, plus the line break that
     * separates it from the words that anchored it when the value opens a line -- the core measures
     * that text before normalizing, so a cap that ignored the gap would refuse the very message the
     * reader highlighted -- plus the slack for a value that is longer next time. The core requires
     * an amount to stay exactly as the bank printed it, so nothing else widens this: a number the
     * builder cannot bound is reported as an unreadable message rather than reshaped into a guess.
     */
    private int maxLength(Anchor anchor, Anchored at) {
        int gap = at.line() < 0 ? lineBreak(anchor.start) : 0;
        int length = anchor.end - anchor.start + gap;
        return Math.min(MAX_FIELD, length + (varyingDigits ? SLACK : 0));
    }

    /** The characters between the end of the previous line and the value: one LF, or two for CRLF.
     *  Only the newest break matters, because the anchor ends on the line directly above. */
    private int lineBreak(int offset) {
        int at = body.lastIndexOf('\n', Math.max(0, offset - 1));
        if (at < 0) return 0;
        return at > 0 && body.charAt(at - 1) == '\r' ? 2 : 1;
    }

    /**
     * Where a value is, and the reader's own words on either side of it. A value that opens a line
     * -- a date on its own line is the common case -- is anchored by the words on the line before
     * it, and the region widens to the whole message, because the rule is still recognized by
     * something the reader chose rather than by a position that only held for this message.
     */
    /** Whether an exclusion can be written at all: it has to be a stretch of the example, since a
     *  guard the rule cannot be shown to hold would look like protection and be none. */
    boolean exclusionUsable(String word) {
        return word != null && !word.isEmpty() && word.length() <= MAX_LITERAL
                && body.indexOf(word) >= 0;
    }

    /** How many lines a message spans. The engine caps this, so the builder has to know it too. */
    private static int lineCount(String value) {
        int lines = 1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
        return lines;
    }

    private Anchored anchored(Anchor anchor) {
        int lineStart = lineStart(anchor.start);
        String after = bounded(body.substring(Math.max(lineStart, anchor.start - MAX_ANCHOR),
                anchor.start));
        int line = line(anchor.start);
        if (!after.isEmpty()) return new Anchored(line, after, suffix(anchor));
        // Nothing before the value on its line. A date may open its line: the date grammar checks
        // every part's digits and width, so a misread ends in an explicit diagnostic rather than a
        // wrong date. An amount may not, because two numbers on one line are indistinguishable and
        // the rule would take whichever came first.
        if (anchor.role == Role.DATE) return new Anchored(line, "", suffix(anchor));
        String previous = previousLine(lineStart);
        if (previous.isEmpty()) return new Anchored(line, "", suffix(anchor));
        return new Anchored(-1, previous, suffix(anchor));
    }

    /** The end of the previous line, without its line break: an anchor is one line of words, so
     *  it never spans a break the bank might send as CR LF or LF alone. */
    private String previousLine(int lineStart) {
        if (lineStart <= 0) return "";
        int cut = lineStart - 1;
        if (cut > 0 && body.charAt(cut - 1) == '\r') cut--;
        int from = Math.max(0, cut - MAX_ANCHOR);
        String text = body.substring(from, cut);
        // If that window still contains a line break, take what follows the last one.
        int line = text.lastIndexOf('\n');
        return line < 0 ? text : text.substring(line + 1);
    }

    /** The words that end the value on its line; empty is fine when the value ends the line. */
    private String suffix(Anchor anchor) {
        int to = Math.min(lineEnd(anchor.end), anchor.end + MAX_ANCHOR);
        return body.substring(anchor.end, to);
    }

    private int line(int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < body.length(); i++) {
            if (body.charAt(i) == '\n') line++;
        }
        return line;
    }

    private int lineStart(int offset) {
        int at = body.lastIndexOf('\n', Math.max(0, offset - 1));
        return at < 0 ? 0 : at + 1;
    }

    private int lineEnd(int offset) {
        int at = body.indexOf('\n', Math.max(0, offset));
        return at < 0 ? body.length() : at;
    }

    private boolean isMoney(Anchor anchor) {
        return (shape == Shape.MOVEMENT && anchor.role == Role.AMOUNT)
                || ((shape == Shape.BALANCE || shape == Shape.BOTH) && anchor.role == Role.BALANCE);
    }

    /** The currency the message itself names. A reader should not have to know that tomans are a
     *  tenth of rials for the rule to read the right number. */
    private String unit() {
        String following = suffixValue();
        if (following.startsWith(TOMAN) || following.startsWith(RIAL)) return "IRR";
        for (String code : new String[] {"IRR", "USD", "EUR", "GBP", "JPY", "KWD", "JOD"}) {
            if (following.startsWith(code)) return code;
        }
        return currency;
    }

    private int multiplier() {
        String following = suffixValue();
        return following.startsWith(TOMAN) ? 10 : 1;
    }

    private String suffixValue() {
        Anchor anchor = anchor(Role.AMOUNT);
        if (anchor == null) anchor = anchor(Role.BALANCE);
        if (anchor == null) return "";
        return body.substring(anchor.end).trim();
    }

    /** Whether one separator between three digits groups thousands. The reader answers for their
     *  own bank: Iranian amounts are written with commas ("1,250,000"), most of the world with
     *  dots ("1.250.000"), and the two are the same number written differently. */
    boolean commaIsThousands = true;
    boolean dotIsThousands;

    /** A number as the reader's example wrote it: which dot or comma groups thousands and which is
     *  a decimal point. One separator between three digits could be either -- a thousands mark in
     *  most of the world, a decimal point in some -- so the reader answers instead of the builder
     *  guessing, and always sees the number their highlight produced before saving anything. */
    private record Numbers(String decimal, String group) {}

    /** Whether the reader's own highlight contains one separator between three digits, which is the
     *  only case the builder cannot answer on its own. */
    boolean ambiguousSeparators(String span) {
        int last = Math.max(span.lastIndexOf('.'), span.lastIndexOf(','));
        if (last < 0 || span.indexOf('٫') >= 0 || span.indexOf('٬') >= 0) return false;
        char mark = span.charAt(last);
        int tail = 0;
        for (int i = last + 1; i < span.length(); i++) {
            if (isDigit(span.charAt(i))) tail++;
        }
        int head = 0;
        for (int i = 0; i < last; i++) {
            if (isDigit(span.charAt(i))) head++;
        }
        int marks = 0;
        for (int i = 0; i < span.length(); i++) {
            if (span.charAt(i) == mark) marks++;
        }
        return marks == 1 && tail == 3 && head >= 1;
    }

    private Numbers numbers(String span) {
        // Persian marks are unambiguous, which is part of why Iranian packs declare them.
        if (span.indexOf('٫') >= 0 || span.indexOf('٬') >= 0) {
            return new Numbers(span.indexOf('٫') >= 0 ? "٫" : ".",
                    span.indexOf('٬') >= 0 ? "٬" : "");
        }
        int last = Math.max(span.lastIndexOf('.'), span.lastIndexOf(','));
        if (last < 0) return new Numbers(".", "");
        char mark = span.charAt(last);
        int tail = 0;
        for (int i = last + 1; i < span.length(); i++) {
            if (isDigit(span.charAt(i))) tail++;
        }
        int head = 0;
        for (int i = 0; i < last; i++) {
            if (isDigit(span.charAt(i))) head++;
        }
        int marks = 0;
        int earlier = 0;
        for (int i = 0; i < span.length(); i++) {
            if (span.charAt(i) == mark) {
                marks++;
                if (i < last) earlier++;
            }
        }
        boolean ambiguous = marks == 1 && tail == 3 && head >= 1;
        boolean thousands = marks > 1
                || (ambiguous && (mark == ',' ? commaIsThousands : dotIsThousands));
        if (thousands) return new Numbers(mark == ',' ? "." : ",", String.valueOf(mark));
        char other = earlier > 0 && span.indexOf(mark == ',' ? '.' : ',') >= 0
                ? (mark == ',' ? '.' : ',') : 0;
        return new Numbers(String.valueOf(mark), other == 0 ? "" : String.valueOf(other));
    }

    private String digits(String span) {
        for (int i = 0; i < span.length(); i++) {
            char c = span.charAt(i);
            if ((c >= '۰' && c <= '۹') || (c >= '٠' && c <= '٩')) return "ASCII_PERSIAN_ARABIC";
        }
        return "ASCII";
    }

    /** The date separator the reader's highlight used. Strings, not chars, because the document
     *  is built for the strict decoder, which takes text and nothing else. */
    private static String separator(String span) {
        for (char c : new char[] {'/', '-', '.'}) {
            if (span.indexOf(c) >= 0) return String.valueOf(c);
        }
        return null;
    }

    private static boolean hasYear(String span) {
        int run = 0;
        for (int i = 0; i < span.length(); i++) {
            if (isDigit(span.charAt(i))) {
                run++;
            } else if (run > 0) {
                if (run >= 4) return true;
                run = 0;
            }
        }
        return run >= 4;
    }

    /** The order the reader highlighted: a four-digit year says which end it was on; two short
     *  groups mean no year at all, and the core takes the year from the message's own date. */
    /** Whether a highlighted date already includes a clock, which the reader said it does. */
    private boolean carriesClock(String span) {
        int space = span.lastIndexOf(' ');
        if (space <= 0 || space + 6 > span.length()) return false;
        String clock = span.substring(space + 1);
        return clock.length() == 5 && clock.charAt(2) == ':' && hasDigits(clock);
    }

    private List<String> orders(String span) {
        String separator = separator(span) == null ? "/" : separator(span);
        String[] groups = span.split(String.valueOf(separator), -1);
        List<Integer> widths = new ArrayList<>();
        for (String group : groups) {
            int digits = 0;
            for (int i = 0; i < group.length(); i++) {
                if (isDigit(group.charAt(i))) digits++;
            }
            widths.add(digits);
        }
        if (widths.size() >= 3 && widths.get(0) >= 4) return List.of("YMD");
        if (widths.size() >= 3) return List.of("DMY");
        if (widths.size() == 2) return List.of("MD");
        return List.of("DMY");
    }

    /** A bank id the core accepts, derived from the name the reader typed. */
    private String slug() {
        StringBuilder out = new StringBuilder();
        String name = bankName.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < name.length() && out.length() < 32; i++) {
            char c = name.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (plain) out.append(c);
            else if (out.length() > 0 && out.charAt(out.length() - 1) != '.') out.append('.');
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '.') out.setLength(out.length() - 1);
        return out.length() == 0 ? "bank" : out.toString();
    }

    /** The country code the schema accepts: two letters, and never a guess about where a bank is
     *  when the reader did not say. */
    private String country() {
        String code = country.trim().toUpperCase(Locale.ROOT);
        return code.length() == 2 && code.charAt(0) >= 'A' && code.charAt(0) <= 'Z'
                && code.charAt(1) >= 'A' && code.charAt(1) <= 'Z' ? code : "IR";
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= '۰' && c <= '۹') || (c >= '٠' && c <= '٩');
    }

    private static boolean hasDigits(String span) {
        for (int i = 0; i < span.length(); i++) {
            if (isDigit(span.charAt(i))) return true;
        }
        return false;
    }

    /** An anchor is bounded words: never longer than a reader could recognize, and never carrying
     *  a line break the bank might send as CR LF or LF alone. */
    private static String bounded(String text) {
        String out = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
        return out.length() > MAX_ANCHOR ? out.substring(out.length() - MAX_ANCHOR) : out;
    }
}