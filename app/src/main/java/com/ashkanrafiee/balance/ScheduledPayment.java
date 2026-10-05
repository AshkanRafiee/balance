package com.ashkanrafiee.balance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** A manually entered outgoing obligation. It is deliberately separate from SMS transactions. */
final class ScheduledPayment {
    enum Type { ONE_TIME, SUBSCRIPTION, DEBT, LOAN }
    enum Frequency { ONCE, WEEKLY, MONTHLY, YEARLY }
    enum EndMode { NEVER, DATE, COUNT }

    static final int STATE_UNPAID = 0;
    static final int STATE_PAID = 1;
    static final int STATE_SKIPPED = 2;
    static final int MAX_TITLE_LENGTH = 100;
    /** Maximum number of explicitly stored state entries per plan, not a schedule length. */
    static final int MAX_OCCURRENCES = 5000;
    static final int MAX_ID_LENGTH = 128;
    static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    String id;
    String title;
    Type type;
    long amountRial;
    CalendarSystem calendar;
    int firstYear;
    int firstMonth;
    int firstDay;
    Frequency frequency;
    EndMode endMode;
    int endYear;
    int endMonth;
    int endDay;
    int occurrenceCount;
    boolean archived;
    /** Null until the user stops future occurrences; the cutoff itself remains part of the plan. */
    ScheduledDate stoppedAfter;
    /** Explicit states are retained so an occurrence manually returned to unpaid survives backup merge. */
    final Map<Integer, Integer> states = new LinkedHashMap<>();

    ScheduledPayment(String id, String title, Type type, long amountRial, CalendarSystem calendar,
            int firstYear, int firstMonth, int firstDay, Frequency frequency, EndMode endMode,
            int endYear, int endMonth, int endDay, int occurrenceCount) {
        this(id, title, type, amountRial, calendar, firstYear, firstMonth, firstDay, frequency,
            endMode, endYear, endMonth, endDay, occurrenceCount, null);
    }

    ScheduledPayment(String id, String title, Type type, long amountRial, CalendarSystem calendar,
            int firstYear, int firstMonth, int firstDay, Frequency frequency, EndMode endMode,
            int endYear, int endMonth, int endDay, int occurrenceCount, ScheduledDate stoppedAfter) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.title = cleanTitle(title);
        this.type = type == null ? Type.ONE_TIME : type;
        this.amountRial = amountRial;
        this.calendar = calendar == null ? CalendarSystem.GREGORIAN : calendar;
        this.firstYear = firstYear;
        this.firstMonth = firstMonth;
        this.firstDay = firstDay;
        this.frequency = frequency == null ? Frequency.ONCE : frequency;
        this.endMode = endMode == null ? EndMode.NEVER : endMode;
        this.endYear = endYear;
        this.endMonth = endMonth;
        this.endDay = endDay;
        this.occurrenceCount = occurrenceCount;
        this.stoppedAfter = stoppedAfter;
        normalizeUnusedEndFields();
        validate();
    }

    static ScheduledPayment create(String title, Type type, long amountRial, CalendarSystem calendar,
            ScheduledDate first, Frequency frequency, EndMode endMode, ScheduledDate end,
            int occurrenceCount) {
        if (first == null) throw new IllegalArgumentException("First date required");
        return new ScheduledPayment(UUID.randomUUID().toString(), title, type, amountRial, calendar,
            first.year, first.month, first.day, frequency, endMode,
            end == null ? 0 : end.year, end == null ? 0 : end.month, end == null ? 0 : end.day,
            occurrenceCount);
    }

    ScheduledDate firstDate() {
        return new ScheduledDate(calendar, firstYear, firstMonth, firstDay);
    }

    ScheduledDate endDate() {
        return endMode == EndMode.DATE ? new ScheduledDate(calendar, endYear, endMonth, endDay) : null;
    }

    boolean hasStopDate() { return stoppedAfter != null; }

    /** Stops occurrences after {@code date}; occurrences on the cutoff date remain visible. */
    void setStoppedAfter(ScheduledDate date) {
        if (date != null && date.calendar != calendar)
            throw new IllegalArgumentException("Stop date uses another calendar");
        stoppedAfter = date;
        validate();
    }

    boolean hasFutureOccurrences(ScheduledDate today) {
        if (today == null) throw new IllegalArgumentException("Today required");
        if (archived) return false;
        long cutoff = today.ordinal();
        if (stoppedAfter != null) cutoff = Math.min(cutoff, stoppedAfter.ordinal());
        return ScheduledPayments.lastSequenceOnOrBefore(this, Long.MAX_VALUE) >= 0
            && ScheduledPayments.firstSequenceOnOrAfter(this, cutoff + 1L)
                <= ScheduledPayments.lastSequenceOnOrBefore(this, Long.MAX_VALUE);
    }

    int state(int sequence) {
        Integer state = states.get(sequence);
        return state == null ? STATE_UNPAID : state;
    }

    void setState(int sequence, int state) {
        if (sequence < 0 || sequence > ScheduledPayments.maxSequence(this))
            throw new IllegalArgumentException("Bad occurrence");
        if (state != STATE_UNPAID && state != STATE_PAID && state != STATE_SKIPPED)
            throw new IllegalArgumentException("Bad occurrence state");
        if (!states.containsKey(sequence) && states.size() >= MAX_OCCURRENCES)
            throw new IllegalArgumentException("Too many occurrence states");
        // STATE_UNPAID is intentionally stored. It is an explicit local answer and must win over a
        // paid/skipped entry arriving from a compatible backup.
        states.put(sequence, state);
    }

    void mergeStates(Map<Integer, Integer> incoming) {
        if (incoming == null) throw new IllegalArgumentException("Missing states");
        for (Map.Entry<Integer, Integer> e : incoming.entrySet()) {
            int sequence = e.getKey() == null ? -1 : e.getKey();
            int value = e.getValue() == null ? -1 : e.getValue();
            if (sequence < 0 || sequence > ScheduledPayments.stateSequenceLimit(this)
                    || (value != STATE_UNPAID && value != STATE_PAID && value != STATE_SKIPPED)) {
                throw new IllegalArgumentException("Bad occurrence state");
            }
            if (!states.containsKey(sequence)) {
                if (states.size() >= MAX_OCCURRENCES)
                    throw new IllegalArgumentException("Too many occurrence states");
                states.put(sequence, value);
            }
        }
    }

    /** Full schedule identity used by backup merge, including the stop cutoff. */
    boolean hasSameSchedule(ScheduledPayment other) {
        return other != null && hasSameOccurrenceIdentity(other)
            && endMode == other.endMode && endYear == other.endYear
            && endMonth == other.endMonth && endDay == other.endDay
            && occurrenceCount == other.occurrenceCount
            && java.util.Objects.equals(stoppedAfter, other.stoppedAfter);
    }

    /** Calendar, anchor and frequency identity. End metadata may change without moving old states. */
    boolean hasSameOccurrenceIdentity(ScheduledPayment other) {
        return other != null && calendar == other.calendar && firstYear == other.firstYear
            && firstMonth == other.firstMonth && firstDay == other.firstDay
            && frequency == other.frequency;
    }

    void validate() {
        // Keep ignored metadata canonical even when a package-level editor changed fields directly.
        normalizeUnusedEndFields();
        if (id == null || !ID_PATTERN.matcher(id).matches() || id.length() > MAX_ID_LENGTH)
            throw new IllegalArgumentException("Bad plan id");
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) throw new IllegalArgumentException("Bad title");
        if (amountRial <= 0) throw new IllegalArgumentException("Bad amount");
        ScheduledDate first = new ScheduledDate(calendar, firstYear, firstMonth, firstDay);
        if (frequency == Frequency.ONCE && endMode != EndMode.NEVER)
            throw new IllegalArgumentException("One-time payment cannot repeat");
        if (type == Type.SUBSCRIPTION && frequency == Frequency.ONCE)
            throw new IllegalArgumentException("Subscription must repeat");
        if (type == Type.ONE_TIME && frequency != Frequency.ONCE)
            throw new IllegalArgumentException("One-time payment must not repeat");
        if (endMode == EndMode.DATE) {
            ScheduledDate end = new ScheduledDate(calendar, endYear, endMonth, endDay);
            if (end.compareTo(first) < 0) throw new IllegalArgumentException("End before first date");
        }
        if (endMode == EndMode.COUNT
                && (frequency == Frequency.ONCE || occurrenceCount < 1 || occurrenceCount > MAX_OCCURRENCES))
            throw new IllegalArgumentException("Bad occurrence count");
        if (endMode == EndMode.COUNT
                && occurrenceCount > ScheduledPayments.stateSequenceLimit(this) + 1)
            throw new IllegalArgumentException("Count exceeds planning horizon");
        if (stoppedAfter != null && stoppedAfter.calendar != calendar) {
            throw new IllegalArgumentException("Bad stop date");
        }
        if (states.size() > MAX_OCCURRENCES) throw new IllegalArgumentException("Too many states");
        for (Map.Entry<Integer, Integer> e : states.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getKey() < 0
                    || e.getKey() > ScheduledPayments.stateSequenceLimit(this)
                    || (e.getValue() != STATE_UNPAID && e.getValue() != STATE_PAID
                    && e.getValue() != STATE_SKIPPED)) {
                throw new IllegalArgumentException("Bad occurrence state");
            }
        }
    }

    private void normalizeUnusedEndFields() {
        if (frequency == Frequency.ONCE) endMode = EndMode.NEVER;
        if (endMode != EndMode.DATE) {
            endYear = 0; endMonth = 0; endDay = 0;
        }
        if (endMode != EndMode.COUNT) occurrenceCount = 0;
    }

    private static String cleanTitle(String value) {
        if (value == null) return "";
        return value.trim();
    }

    JSONObject toJson() throws Exception {
        validate();
        JSONObject o = new JSONObject()
            .put("id", id).put("title", title).put("type", type.name())
            .put("amountRial", amountRial).put("calendar", calendar.tag())
            .put("firstYear", firstYear).put("firstMonth", firstMonth).put("firstDay", firstDay)
            .put("frequency", frequency.name()).put("endMode", endMode.name())
            .put("archived", archived);
        if (endMode == EndMode.DATE) {
            o.put("endYear", endYear).put("endMonth", endMonth).put("endDay", endDay);
        } else if (endMode == EndMode.COUNT) {
            o.put("occurrenceCount", occurrenceCount);
        }
        if (stoppedAfter != null) {
            o.put("stoppedAfter", new JSONObject().put("year", stoppedAfter.year)
                .put("month", stoppedAfter.month).put("day", stoppedAfter.day));
        }
        JSONArray statesJson = new JSONArray();
        for (Map.Entry<Integer, Integer> e : states.entrySet()) {
            statesJson.put(new JSONObject().put("sequence", e.getKey()).put("state", e.getValue()));
        }
        if (!states.isEmpty()) o.put("states", statesJson);
        return o;
    }

    /** Strict parser used by both local storage and backups. It never creates an ID on input. */
    static ScheduledPayment fromJson(JSONObject o) {
        if (o == null) throw new IllegalArgumentException("Malformed scheduled payment");
        try {
            String id = requiredString(o, "id");
            if (!ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("Bad plan id");
            String title = requiredString(o, "title");
            Type type = Type.valueOf(requiredString(o, "type"));
            CalendarSystem calendar = CalendarSystem.ofTag(requiredString(o, "calendar"));
            if (calendar == null) throw new IllegalArgumentException("Bad calendar");
            Frequency frequency = Frequency.valueOf(requiredString(o, "frequency"));
            EndMode endMode = EndMode.valueOf(requiredString(o, "endMode"));
            long amount = o.getLong("amountRial");
            int endYear = 0, endMonth = 0, endDay = 0, count = 0;
            if (endMode == EndMode.DATE) {
                endYear = o.getInt("endYear"); endMonth = o.getInt("endMonth"); endDay = o.getInt("endDay");
            } else if (endMode == EndMode.COUNT) {
                count = o.getInt("occurrenceCount");
            }
            ScheduledDate stoppedAfter = null;
            if (o.has("stoppedAfter")) {
                JSONObject stop = o.getJSONObject("stoppedAfter");
                stoppedAfter = new ScheduledDate(calendar, stop.getInt("year"), stop.getInt("month"),
                    stop.getInt("day"));
            }
            ScheduledPayment p = new ScheduledPayment(id, title, type, amount, calendar,
                o.getInt("firstYear"), o.getInt("firstMonth"), o.getInt("firstDay"), frequency,
                endMode, endYear, endMonth, endDay, count, stoppedAfter);
            if (o.has("archived")) p.archived = o.getBoolean("archived");
            if (o.has("states")) {
                JSONArray states = o.getJSONArray("states");
                if (states.length() > MAX_OCCURRENCES) throw new IllegalArgumentException("Too many states");
                for (int i = 0; i < states.length(); i++) {
                    JSONObject state = states.getJSONObject(i);
                    int sequence = state.getInt("sequence");
                    int value = state.getInt("state");
                    if (sequence < 0 || sequence > ScheduledPayments.stateSequenceLimit(p)
                            || (value != STATE_UNPAID && value != STATE_PAID && value != STATE_SKIPPED))
                        throw new IllegalArgumentException("Bad occurrence state");
                    if (p.states.containsKey(sequence)) throw new IllegalArgumentException("Duplicate state");
                    if (p.states.size() >= MAX_OCCURRENCES)
                        throw new IllegalArgumentException("Too many states");
                    p.states.put(sequence, value);
                }
            }
            p.validate();
            return p;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed scheduled payment", e);
        }
    }

    private static String requiredString(JSONObject o, String key) throws Exception {
        if (!o.has(key) || o.isNull(key)) throw new IllegalArgumentException("Missing " + key);
        String value = o.getString(key);
        if (value.isEmpty()) throw new IllegalArgumentException("Empty " + key);
        return value;
    }
}
