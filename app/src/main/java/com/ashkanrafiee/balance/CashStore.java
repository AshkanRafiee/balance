package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Manual cash, independent of SMS scans. A count and its movements are encrypted and committed
 * together in the active data generation; the current balance is derived from that snapshot.
 */
final class CashStore {
    static final String KEY = "Cash";
    static final String SIGNATURE_PREFIX = "cash:";
    static final String ENABLED = "show_cash";
    static final String DATA = "cash_ledger";
    static final String REVISION = "cash_revision";
    static final int MAX_SNAPSHOT_CHARS = 4 * 1024 * 1024;
    private static final long IMPORT_CLOCK_SKEW_MS = TimeUnit.MINUTES.toMillis(5);
    private static final Pattern AMOUNT_INPUT =
        Pattern.compile("(?:[0-9]++|[0-9]{1,3}(?:,[0-9]{3})++)(?:\\.[0-9]+)?");

    // Guarded by BalanceData.class. The ciphertext key also detects restored generations and
    // preference changes; a successful write can publish its already validated immutable state.
    private static String cachedCiphertext;
    private static State cachedState;
    private static DataGeneration.PublishHook publishHookForTests;

    private CashStore() {}

    static void setPublishHookForTests(DataGeneration.PublishHook hook) {
        synchronized (BalanceData.class) {
            publishHookForTests = hook;
        }
    }

    enum RestoreResult { IMPORTED, UNCHANGED, CONFLICT }

    static final class InsufficientCashException extends IllegalArgumentException {
        final long available;
        final long required;
        final long date;
        final long shortfall;

        InsufficientCashException(long available, long required, long date, long shortfall) {
            super("cash balance would become negative");
            this.available = available;
            this.required = required;
            this.date = date;
            this.shortfall = shortfall;
        }
    }

    static final class StaleCashTransactionException extends IllegalArgumentException {
        StaleCashTransactionException() { super("cash transaction changed"); }
    }

    static final class Preview {
        final long currentBalance;
        final long resultingBalance;
        final boolean affectsBalance;
        final boolean insufficient;
        final long availableAtShortfall;
        final long requiredAtShortfall;
        final long shortfallDate;
        final long shortfall;
        final long recoveryDate;

        private Preview(State current, long resultingBalance, boolean affectsBalance,
                InsufficientCashException failure) {
            currentBalance = current.balance;
            this.resultingBalance = resultingBalance;
            this.affectsBalance = affectsBalance;
            insufficient = failure != null;
            availableAtShortfall = insufficient ? failure.available : 0;
            requiredAtShortfall = insufficient ? failure.required : 0;
            shortfallDate = insufficient ? failure.date : 0;
            shortfall = insufficient ? failure.shortfall : 0;
            recoveryDate = insufficient && failure.date - 1 > current.countedAt
                ? failure.date - 1 : 0;
        }
    }

    static final class State {
        final long countedAmount;
        final long countedAt;
        final long updatedAt;
        final long balance;
        final List<Transaction> transactions;

        private State(long countedAmount, long countedAt, long updatedAt,
                List<Transaction> transactions) {
            this.countedAmount = countedAmount;
            this.countedAt = countedAt;
            this.updatedAt = updatedAt;
            this.transactions = Collections.unmodifiableList(new ArrayList<>(transactions));
            balance = balanceAfterCount(countedAmount, countedAt, transactions);
        }

        boolean hasData() {
            return countedAt != 0 || !transactions.isEmpty();
        }
    }

    private static final State EMPTY = new State(0, 0, 0, Collections.emptyList());

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        synchronized (BalanceData.class) {
            SharedPreferences prefs = preferences(context);
            boolean previous = prefs.getBoolean(ENABLED, true);
            if (previous == enabled) return;
            if (!prefs.edit().putBoolean(ENABLED, enabled).commit()) {
                prefs.edit().putBoolean(ENABLED, previous).commit();
                throw new IllegalStateException("cash visibility could not be saved");
            }
        }
        BalanceData.notifyHistoryChanged();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(BalanceData.PREFS_PREF, Context.MODE_PRIVATE);
    }

    static State read(Context context) {
        synchronized (BalanceData.class) {
            BalanceData.ensureResetComplete(context);
            return read(DataGeneration.dataPrefs(context));
        }
    }

    private static State read(SharedPreferences prefs) {
        String encrypted = prefs.getString(DATA, null);
        if (encrypted == null) {
            cachedCiphertext = null;
            cachedState = null;
            return EMPTY;
        }
        if (encrypted.equals(cachedCiphertext)) return cachedState;
        try {
            State state = decode(BalanceData.decryptStorePayload(encrypted));
            cache(encrypted, state);
            return state;
        } catch (Exception failure) {
            // JSON reader errors may include a fragment of the decrypted input.
            throw new IllegalStateException("cash ledger could not be read");
        }
    }

    static Bank visibleBalance(Context context) {
        synchronized (BalanceData.class) {
            if (!isEnabled(context)) return null;
            State state = read(context);
            return new Bank(KEY, state.balance, state.updatedAt, "");
        }
    }

    /** A new count replaces the running balance; existing movements remain in history. */
    static void setBalance(Context context, long amount) {
        if (amount < 0) throw new IllegalArgumentException("invalid cash count");
        synchronized (BalanceData.class) {
            SharedPreferences prefs = writable(context);
            State previous = read(prefs);
            long now = System.currentTimeMillis();
            long cutoff = Math.max(now, previous.countedAt);
            // Also absorb every existing movement if the device clock was turned back.
            for (Transaction transaction : previous.transactions)
                cutoff = Math.max(cutoff, transaction.date);
            write(prefs, new State(amount, cutoff, now, previous.transactions));
        }
        BalanceData.notifyHistoryChanged();
    }

    static Transaction add(Context context, long date, long amount) {
        return saveMovement(context, null, date, amount, null, null);
    }

    static Transaction add(Context context, long date, long amount, String note) {
        return add(context, date, amount, new Annotation(note, Collections.emptyList()));
    }

    static Transaction add(Context context, long date, long amount, Annotation annotation) {
        return saveMovement(context, null, date, amount, null, annotation);
    }

    static Transaction update(Context context, Transaction expected, long date, long amount) {
        return update(context, expected, date, amount, null, null);
    }

    static Transaction update(Context context, Transaction expected, long date, long amount,
            Annotation original, Annotation annotation) {
        if (!isCash(expected)) throw new StaleCashTransactionException();
        return saveMovement(context, expected, date, amount, original, annotation);
    }

    static final class Annotation {
        final String note;
        final List<String> tags;

        Annotation(String note, List<String> tags) {
            this.note = note == null ? "" : note.trim();
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
        }

        boolean sameAs(Annotation other) {
            return note.equals(other.note) && tags.equals(other.tags);
        }
    }

    private static final Annotation EMPTY_ANNOTATION = new Annotation(null, Collections.emptyList());

    static Annotation readAnnotation(Context context, Transaction transaction) throws Exception {
        synchronized (BalanceData.class) {
            return new Annotation(MetadataStore.getNote(context, transaction),
                MetadataStore.getTags(context, transaction));
        }
    }

    private static Transaction saveMovement(Context context, Transaction expected, long date,
            long amount, Annotation original, Annotation annotation) {
        Transaction saved;
        synchronized (BalanceData.class) {
            SharedPreferences prefs = writable(context);
            long now = System.currentTimeMillis();
            validateEntryTime(date, amount, now);
            State previous = read(prefs);
            String sig = expected == null ? SIGNATURE_PREFIX + UUID.randomUUID() : expected.sig;
            saved = new Transaction(KEY, date, amount, sig);
            List<Transaction> movements = withMovement(previous, expected, saved);
            boolean movementChanged = expected == null || !sameMovement(expected, saved);
            State next = movementChanged ? new State(previous.countedAmount, previous.countedAt,
                affectsBalance(previous, expected, date) ? now : previous.updatedAt, movements) : null;
            boolean annotationChanged = annotation != null
                && !(original == null ? EMPTY_ANNOTATION : original).sameAs(annotation);
            if (annotationChanged && expected != null && original != null
                    && !original.sameAs(currentAnnotation(context, expected)))
                throw new StaleCashTransactionException();
            if (!movementChanged && !annotationChanged) return expected;
            if (movementChanged && annotationChanged)
                writeWithAnnotation(context, next, saved, annotation);
            else if (movementChanged)
                write(prefs, next);
            else
                writeAnnotation(context, saved, annotation);
        }
        BalanceData.notifyHistoryChanged();
        return saved;
    }

    private static Annotation currentAnnotation(Context context, Transaction transaction) {
        try {
            return readAnnotation(context, transaction);
        } catch (Exception failure) {
            throw new IllegalStateException("cash annotation could not be read");
        }
    }

    private static void writeAnnotation(Context context, Transaction transaction, Annotation annotation) {
        try {
            if (MetadataStore.setAnnotations(context, transaction, annotation.note, annotation.tags))
                return;
        } catch (Exception ignored) {
            // Do not include annotation text in diagnostics.
        }
        throw new IllegalStateException("cash annotation could not be saved");
    }

    /** Only edits spanning both stores pay for a new generation. */
    private static void writeWithAnnotation(Context context, State state, Transaction transaction,
            Annotation annotation) {
        try (DataGeneration.Stage stage = DataGeneration.beginStage(context)) {
            Context staged = stage.context();
            SharedPreferences stagedPrefs = DataGeneration.dataPrefs(staged);
            write(stagedPrefs, state);
            writeAnnotation(staged, transaction, annotation);
            stage.setPublishHookForTests(publishHookForTests);
            try {
                stage.publish();
            } catch (Exception failure) {
                try {
                    if (stage.confirmPublication()) return;
                } catch (Exception confirmation) {
                    failure.addSuppressed(confirmation);
                    throw new UnconfirmedCashSaveException(failure);
                }
                throw failure;
            }
        } catch (UnconfirmedCashSaveException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("cash movement and annotation could not be saved", failure);
        }
    }

    static final class UnconfirmedCashSaveException extends IllegalStateException {
        UnconfirmedCashSaveException(Throwable cause) {
            super("cash publication could not be confirmed", cause);
        }
    }

    /** Uses the same chronological check as Save, without writing anything. */
    static Preview preview(Context context, long date, long amount, Transaction replacing) {
        return preview(context, date, amount, replacing, () -> true);
    }

    static Preview preview(Context context, long date, long amount, Transaction replacing,
            BooleanSupplier stillNeeded) {
        validateEntryTime(date, amount, System.currentTimeMillis());
        State current;
        synchronized (BalanceData.class) {
            if (!stillNeeded.getAsBoolean()) throw new CancellationException();
            current = read(writable(context));
        }
        if (!stillNeeded.getAsBoolean()) throw new CancellationException();
        boolean affects = affectsBalance(current, replacing, date);
        Transaction candidate = new Transaction(KEY, date, amount,
            replacing == null ? null : replacing.sig);
        List<Transaction> movements = withMovement(current, replacing, candidate);
        try {
            long balance = balanceAfterCount(current.countedAmount, current.countedAt, movements);
            return new Preview(current, balance, affects, null);
        } catch (InsufficientCashException failure) {
            return new Preview(current, current.balance, affects, failure);
        }
    }

    private static boolean affectsBalance(State current, Transaction replacing, long date) {
        return date > current.countedAt || (replacing != null && replacing.date > current.countedAt);
    }

    private static List<Transaction> withMovement(State current, Transaction replacing,
            Transaction candidate) {
        List<Transaction> movements = new ArrayList<>(current.transactions);
        if (replacing == null) {
            movements.add(candidate);
        } else {
            for (int i = 0; i < movements.size(); i++) {
                if (sameMovement(replacing, movements.get(i))) {
                    movements.set(i, candidate);
                    return movements;
                }
            }
            throw new StaleCashTransactionException();
        }
        return movements;
    }

    static boolean remove(Context context, Transaction expected) {
        if (!isCash(expected)) return false;
        synchronized (BalanceData.class) {
            SharedPreferences prefs = writable(context);
            State previous = read(prefs);
            List<Transaction> movements = new ArrayList<>(previous.transactions);
            if (!movements.removeIf(movement -> sameMovement(expected, movement))) return false;
            long updatedAt = previous.updatedAt;
            if (expected.date > previous.countedAt) updatedAt = System.currentTimeMillis();
            if (previous.countedAt == 0 && movements.isEmpty()) updatedAt = 0;
            State next = new State(previous.countedAmount, previous.countedAt, updatedAt, movements);
            if (hasAnnotation(context, expected))
                writeWithAnnotation(context, next, expected, EMPTY_ANNOTATION);
            else
                write(prefs, next);
        }
        BalanceData.notifyHistoryChanged();
        return true;
    }

    private static boolean hasAnnotation(Context context, Transaction transaction) {
        try {
            // A legacy backup may contain whitespace-only text that the editor trims away.
            return MetadataStore.getNote(context, transaction) != null
                || !MetadataStore.getTags(context, transaction).isEmpty();
        } catch (Exception failure) {
            throw new IllegalStateException("cash annotation could not be read");
        }
    }

    private static SharedPreferences writable(Context context) {
        BalanceData.ensureResetComplete(context);
        if (!isEnabled(context)) throw new IllegalStateException("cash is disabled");
        return DataGeneration.dataPrefs(context);
    }

    static boolean isCash(Transaction transaction) {
        return transaction != null && KEY.equals(transaction.bank) && transaction.sig != null
            && transaction.sig.startsWith(SIGNATURE_PREFIX);
    }

    static boolean containsVisible(Context context, Transaction expected) {
        if (!isCash(expected) || !isEnabled(context)) return false;
        for (Transaction movement : read(context).transactions)
            if (sameMovement(expected, movement)) return true;
        return false;
    }

    private static boolean sameMovement(Transaction expected, Transaction movement) {
        return expected.date == movement.date && expected.amount == movement.amount
            && expected.sig.equals(movement.sig);
    }

    static void forEachVisible(Context context, TransactionStore.Visitor visitor) throws Exception {
        List<Transaction> snapshot;
        synchronized (BalanceData.class) {
            if (!isEnabled(context)) return;
            snapshot = read(context).transactions;
        }
        for (Transaction movement : snapshot) visitor.accept(movement);
    }

    static String revision(Context context) {
        synchronized (BalanceData.class) {
            return isEnabled(context)
                ? DataGeneration.dataPrefs(context).getString(REVISION, "cash-empty") : "cash-hidden";
        }
    }

    static Long parseAmount(Context context, String input) {
        // A long integer in either currency fits comfortably here, even with grouping.
        if (input == null || input.length() > 128) return null;
        String number = TextNormalizer.normalize(input).trim();
        if (!AMOUNT_INPUT.matcher(number).matches())
            return null;
        try {
            BigDecimal amount = new BigDecimal(number.replace(",", ""));
            if (CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(context)))
                amount = amount.movePointRight(1);
            BigInteger rials = amount.toBigIntegerExact();
            return rials.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? null : rials.longValue();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    /** Prefill a recount without rounding away any existing rials in Toman mode. */
    static String formatAmountForInput(Context context, long amount) {
        int scale = CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(context)) ? 1 : 0;
        return BigDecimal.valueOf(amount, scale).stripTrailingZeros().toPlainString();
    }

    private static long balanceAfterCount(long opening, long countedAt, List<Transaction> movements) {
        List<Transaction> active = new ArrayList<>();
        long balance = opening;
        try {
            for (Transaction movement : movements) {
                if (movement.date > countedAt) active.add(movement);
            }
            active.sort(Comparator.comparingLong(movement -> movement.date));
            Transaction firstUnfunded = null;
            long available = 0;
            long shortfall = 0;
            for (Transaction movement : active) {
                long before = balance;
                balance = Math.addExact(balance, movement.amount);
                if (balance >= 0) continue;
                if (firstUnfunded == null) {
                    firstUnfunded = movement;
                    available = before;
                }
                shortfall = Math.max(shortfall, Math.negateExact(balance));
            }
            if (firstUnfunded != null) {
                throw new InsufficientCashException(available, -firstUnfunded.amount,
                    firstUnfunded.date, shortfall);
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("cash total is too large", overflow);
        }
        return balance;
    }

    private static void validateMovement(long date, long amount) {
        if (date <= 0 || amount == 0 || amount == Long.MIN_VALUE)
            throw new IllegalArgumentException("invalid cash movement");
    }

    private static void validateEntryTime(long date, long amount, long now) {
        validateMovement(date, amount);
        if (date > now) throw new IllegalArgumentException("future cash movement");
    }

    private static void write(SharedPreferences prefs, State state) {
        try {
            String json = encode(state);
            if (json.length() > MAX_SNAPSHOT_CHARS)
                throw new IllegalStateException("cash ledger is too large");
            String encrypted = BalanceData.encryptStorePayload(json);
            String previous = prefs.getString(DATA, null);
            String previousRevision = prefs.getString(REVISION, null);
            if (!prefs.edit().putString(DATA, encrypted)
                    .putString(REVISION, UUID.randomUUID().toString()).commit()) {
                // commit() can update the process's cached values even if the disk write fails.
                prefs.edit().putString(DATA, previous).putString(REVISION, previousRevision).commit();
                throw new IllegalStateException("cash ledger commit failed");
            }
            cache(encrypted, state);
        } catch (Exception failure) {
            throw new IllegalStateException("cash ledger could not be saved", failure);
        }
    }

    private static void cache(String ciphertext, State state) {
        cachedCiphertext = ciphertext;
        cachedState = state;
    }

    static String exportData(Context context) throws Exception {
        State state = read(context);
        return state.hasData() ? encode(state) : null;
    }

    static void validateData(String json) throws Exception {
        decodeImport(json);
    }

    /** A cash count and movements cannot safely be merged across conflicting ledgers. */
    static RestoreResult restoreData(Context context, String json) throws Exception {
        synchronized (BalanceData.class) {
            State incoming = decodeImport(json);
            SharedPreferences prefs = DataGeneration.dataPrefs(context);
            State current = read(prefs);
            if (!incoming.hasData() || encode(incoming).equals(encode(current)))
                return RestoreResult.UNCHANGED;
            if (current.hasData()) return RestoreResult.CONFLICT;
            write(prefs, incoming);
            return RestoreResult.IMPORTED;
        }
    }

    private static State decodeImport(String json) throws Exception {
        State state = decode(json);
        long latest = System.currentTimeMillis() + IMPORT_CLOCK_SKEW_MS;
        // Recount absorbs future-dated movements as well as preserving the previous cutoff.
        if (state.countedAt > latest || state.updatedAt > latest)
            throw new IllegalArgumentException("cash count or update time is too far in the future");
        for (Transaction movement : state.transactions) {
            if (movement.date > latest)
                throw new IllegalArgumentException("cash movement time is too far in the future");
        }
        return state;
    }

    private static String encode(State state) throws Exception {
        JSONObject ledger = new JSONObject();
        ledger.put("countedAmount", state.countedAmount);
        ledger.put("countedAt", state.countedAt);
        ledger.put("updatedAt", state.updatedAt);
        JSONArray movements = new JSONArray();
        for (Transaction movement : state.transactions) {
            JSONObject row = new JSONObject();
            row.put("id", movement.sig.substring(SIGNATURE_PREFIX.length()));
            row.put("date", movement.date);
            row.put("amount", movement.amount);
            movements.put(row);
        }
        ledger.put("movements", movements);
        return ledger.toString();
    }

    private static State decode(String json) throws Exception {
        if (json == null || json.length() > MAX_SNAPSHOT_CHARS)
            throw new IllegalArgumentException("invalid cash snapshot");
        long countedAmount = -1;
        long countedAt = -1;
        long updatedAt = -1;
        List<Transaction> movements = null;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                switch (nextField(reader, fields)) {
                    case "countedAmount": countedAmount = exactLong(reader); break;
                    case "countedAt": countedAt = exactLong(reader); break;
                    case "updatedAt": updatedAt = exactLong(reader); break;
                    case "movements": movements = readMovements(reader); break;
                    default: throw new IllegalArgumentException("unknown cash snapshot field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("extra cash snapshot data");
        }
        if (movements == null || countedAmount < 0 || countedAt < 0 || updatedAt < 0
                || (countedAt == 0 && countedAmount != 0))
            throw new IllegalArgumentException("invalid cash count");
        State state = new State(countedAmount, countedAt, updatedAt, movements);
        if (state.hasData() != (updatedAt > 0))
            throw new IllegalArgumentException("invalid cash update time");
        return state;
    }

    private static List<Transaction> readMovements(JsonReader reader) throws Exception {
        List<Transaction> movements = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        reader.beginArray();
        while (reader.hasNext()) {
            String id = null;
            long date = 0;
            long amount = 0;
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                switch (nextField(reader, fields)) {
                    case "id":
                        if (reader.peek() != JsonToken.STRING)
                            throw new IllegalArgumentException("invalid cash movement identity");
                        id = reader.nextString();
                        break;
                    case "date": date = exactLong(reader); break;
                    case "amount": amount = exactLong(reader); break;
                    default: throw new IllegalArgumentException("unknown cash movement field");
                }
            }
            reader.endObject();
            if (id == null || !UUID.fromString(id).toString().equals(id) || !ids.add(id))
                throw new IllegalArgumentException("invalid cash movement identity");
            validateMovement(date, amount);
            movements.add(new Transaction(KEY, date, amount, SIGNATURE_PREFIX + id));
        }
        reader.endArray();
        return movements;
    }

    private static String nextField(JsonReader reader, Set<String> fields) throws Exception {
        String field = reader.nextName();
        if (!fields.add(field)) throw new IllegalArgumentException("duplicate cash snapshot field");
        return field;
    }

    private static long exactLong(JsonReader reader) throws Exception {
        if (reader.peek() != JsonToken.NUMBER)
            throw new IllegalArgumentException("invalid cash integer");
        return Long.parseLong(reader.nextString());
    }
}
