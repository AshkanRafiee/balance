package com.ashkanrafiee.balance;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded-memory history projection for the next history screen.
 *
 * <p>{@link #summary(Context, Request)} is the production-shaped path. It consumes the stable
 * {@link TransactionStore#forEach} snapshot once, retains aggregates for calendar periods, and
 * retains movement rows only for the requested expanded days. It deliberately does not calculate
 * residuals: {@link Residual#between(List)} needs all rows in date order, while the transaction
 * store's stable order is ordinal order. The limitation is visible in {@link Result}, rather than
 * turning a residual-bearing history into an apparently complete one.
 *
 * <p>{@link #reference(Context, Request)} is intentionally named as a compatibility method. It
 * materializes the scoped transaction list, calls {@link Residual#between(List)}, and applies the
 * same filtering order as {@link HistoryActivity}. It is a parity oracle for tests and for the
 * later migration of the screen; it is not the bounded reader.
 */
final class HistoryReader {
    static final int DEFAULT_PAGE_SIZE = 256;
    static final int DEFAULT_MAX_REQUESTED_DAYS = 64;
    static final int DEFAULT_MAX_ROWS_PER_DAY = 128;

    static final String SUMMARY_RESIDUAL_LIMITATION =
        "Summary mode does not calculate residuals; use the explicit reference mode until "
        + "date-ordered residual staging is available";

    private HistoryReader() {}

    enum Mode { SUMMARY, REFERENCE }

    /** The immutable scope and bounded row-retention policy for one read. */
    static final class Request {
        final boolean iran;
        final int pageSize;
        final String bank;
        final String account;
        final HistoryActivity.Filter filter;
        final String searchQuery;
        final List<String> selectedTags;
        final Set<String> requestedDayKeys;
        final int maxRequestedDays;
        final int maxRowsPerDay;

        /**
         * Creates a read request. {@code maxRowsPerDay} is a UI batch bound, not a history cap: a
         * row beyond it fails the read explicitly instead of being silently omitted.
         */
        Request(boolean iran, int pageSize, String bank, String account,
                HistoryActivity.Filter filter, String searchQuery,
                Collection<String> selectedTags, Collection<String> requestedDayKeys,
                int maxRequestedDays, int maxRowsPerDay) {
            if (maxRequestedDays < 0) throw new IllegalArgumentException("invalid requested-day limit");
            if (maxRowsPerDay < 0) throw new IllegalArgumentException("invalid row limit");
            if (requestedDayKeys != null && requestedDayKeys.size() > maxRequestedDays) {
                throw new IllegalArgumentException("requested day set exceeds its batch bound");
            }
            this.iran = iran;
            this.pageSize = pageSize;
            this.bank = bank;
            this.account = account;
            this.filter = filter == null ? HistoryActivity.Filter.ALL : filter;
            this.searchQuery = searchQuery == null ? "" : searchQuery.trim();
            this.selectedTags = immutableStrings(selectedTags);
            this.requestedDayKeys = immutableSet(requestedDayKeys);
            this.maxRequestedDays = maxRequestedDays;
            this.maxRowsPerDay = maxRowsPerDay;
        }

        /** A no-filter request with no expanded rows. */
        static Request all(boolean iran) {
            return all(iran, DEFAULT_PAGE_SIZE);
        }

        /** A no-filter request with an explicit store page size. */
        static Request all(boolean iran, int pageSize) {
            return new Request(iran, pageSize, null, null, HistoryActivity.Filter.ALL, "",
                null, null, 0, 0);
        }

        /** Copies the request while changing only the expanded-day collection and row bound. */
        Request withRequestedDays(Collection<String> dayKeys, int maxRows) {
            int maxDays = dayKeys == null ? 0 : dayKeys.size();
            return new Request(iran, pageSize, bank, account, filter, searchQuery, selectedTags,
                dayKeys, maxDays, maxRows);
        }
    }

    /**
     * One history result. The summary fields are complete for visible movements. Full visible
     * lists are populated only by {@link #reference(Context, Request)}; calling the accessor on a
     * summary result fails explicitly so a caller cannot mistake an omitted list for an empty one.
     */
    static final class Result {
        final boolean reference;
        final boolean residualsComplete;
        final String limitation;

        final Stats allTime;
        final Stats today;
        final Stats month;
        final Stats year;

        // Direct aliases make the result convenient for the eventual hero/header adapter and keep
        // the names close to HistoryActivity.Lists without retaining that UI model.
        final long total;
        final long todayTotal;
        final long monthTotal;
        final long yearTotal;
        final long totalDeposits;
        final long totalWithdrawals;
        final long todayDeposits;
        final long todayWithdrawals;
        final long monthDeposits;
        final long monthWithdrawals;
        final long yearDeposits;
        final long yearWithdrawals;
        final long movementCount;

        final List<YearSummary> years;
        /** Every visible movement day, newest first, keyed by CalDate.key(). */
        final Map<String, DaySummary> daySummaries;
        /** Only requested expanded days; each list is bounded by Request.maxRowsPerDay. */
        final Map<String, DayRows> requestedDayRows;

        /** Full visible lists in reference mode; null in summary mode by design. */
        final List<Transaction> visibleTransactions;
        final List<Residual> visibleResiduals;

        private Result(boolean reference, Stats allTime, Stats today, Stats month, Stats year,
                List<YearSummary> years, Map<String, DaySummary> daySummaries,
                Map<String, DayRows> requestedDayRows, List<Transaction> visibleTransactions,
                List<Residual> visibleResiduals) {
            this.reference = reference;
            this.residualsComplete = reference;
            this.limitation = reference ? null : SUMMARY_RESIDUAL_LIMITATION;
            this.allTime = allTime;
            this.today = today;
            this.month = month;
            this.year = year;
            this.total = allTime.sum;
            this.todayTotal = today.sum;
            this.monthTotal = month.sum;
            this.yearTotal = year.sum;
            this.totalDeposits = allTime.deposits;
            this.totalWithdrawals = allTime.withdrawals;
            this.todayDeposits = today.deposits;
            this.todayWithdrawals = today.withdrawals;
            this.monthDeposits = month.deposits;
            this.monthWithdrawals = month.withdrawals;
            this.yearDeposits = year.deposits;
            this.yearWithdrawals = year.withdrawals;
            this.movementCount = allTime.movementCount;
            this.years = Collections.unmodifiableList(years);
            this.daySummaries = Collections.unmodifiableMap(daySummaries);
            this.requestedDayRows = Collections.unmodifiableMap(requestedDayRows);
            this.visibleTransactions = visibleTransactions;
            this.visibleResiduals = visibleResiduals;
        }

        /** Returns the full visible movement list only for the explicitly unbounded reference path. */
        List<Transaction> visibleTransactions() {
            if (visibleTransactions == null) throw unsupportedRows();
            return visibleTransactions;
        }

        /** Returns visible residuals only for the explicitly unbounded reference path. */
        List<Residual> visibleResiduals() {
            if (visibleResiduals == null) throw new UnsupportedOperationException(
                "summary mode has no residual result; " + SUMMARY_RESIDUAL_LIMITATION);
            return visibleResiduals;
        }

        private UnsupportedOperationException unsupportedRows() {
            return new UnsupportedOperationException(
                "summary mode retains only requested day rows; use reference mode for full rows");
        }
    }

    /** Signed totals and the count of actual movement messages in a period. */
    static final class Stats {
        final long sum;
        final long deposits;
        final long withdrawals;
        final long movementCount;

        private Stats(long sum, long deposits, long withdrawals, long movementCount) {
            this.sum = sum;
            this.deposits = deposits;
            this.withdrawals = withdrawals;
            this.movementCount = movementCount;
        }
    }

    static final class YearSummary {
        final int year;
        final Stats stats;
        final List<MonthSummary> months;

        private YearSummary(int year, Stats stats, List<MonthSummary> months) {
            this.year = year;
            this.stats = stats;
            this.months = Collections.unmodifiableList(months);
        }
    }

    static final class MonthSummary {
        final int year;
        final int month;
        final Stats stats;
        final List<DaySummary> days;

        private MonthSummary(int year, int month, Stats stats, List<DaySummary> days) {
            this.year = year;
            this.month = month;
            this.stats = stats;
            this.days = Collections.unmodifiableList(days);
        }
    }

    static final class DaySummary {
        final CalDate date;
        final Stats stats;

        private DaySummary(CalDate date, Stats stats) {
            this.date = date;
            this.stats = stats;
        }
    }

    /** Rows for one requested expanded day, in the same newest-first/tie order as the screen. */
    static final class DayRows {
        final String key;
        final CalDate date;
        final List<Row> rows;
        final List<Transaction> transactions;
        final List<Residual> residuals;

        private DayRows(String key, CalDate date, List<Row> rows,
                List<Transaction> transactions, List<Residual> residuals) {
            this.key = key;
            this.date = date;
            this.rows = Collections.unmodifiableList(rows);
            this.transactions = Collections.unmodifiableList(transactions);
            this.residuals = Collections.unmodifiableList(residuals);
        }
    }

    /** One retained row; exactly one of {@link #transaction} and {@link #residual} is non-null. */
    static final class Row {
        final long date;
        final Transaction transaction;
        final Residual residual;
        private final long sequence;

        private Row(long date, Transaction transaction, Residual residual, long sequence) {
            this.date = date;
            this.transaction = transaction;
            this.residual = residual;
            this.sequence = sequence;
        }
    }

    static Result read(Context context, Request request, Mode mode) throws Exception {
        if (context == null) throw new NullPointerException("context");
        if (request == null) throw new NullPointerException("request");
        if (mode == null) throw new NullPointerException("mode");
        return mode == Mode.SUMMARY ? summary(context, request) : reference(context, request);
    }

    /** One stable, bounded-memory pass over the transaction store. */
    static Result summary(Context context, Request request) throws Exception {
        final Accumulator accumulator = new Accumulator(request);
        final Matcher matcher = new Matcher(context, request);
        TransactionStore.forEach(context, request.pageSize, transaction -> {
            // Scope narrowing precedes all other filters. Residual detection in the reference path
            // uses exactly this bank/account scope before any of these later predicates run.
            if (!inScope(transaction, request)) return;
            if (!matchesMovementFilter(transaction, request)) return;
            if (!matchesTags(context, transaction, request.selectedTags)) return;
            if (!matcher.matchesTransaction(transaction)) return;
            accumulator.addTransaction(transaction);
        });
        return accumulator.result(false, null, null);
    }

    /** Explicit alias for callers that want to name the selected mode in the call site. */
    static Result readSummary(Context context, Request request) throws Exception {
        return summary(context, request);
    }

    /**
     * Full-list compatibility oracle. This is intentionally separate from {@link #summary}: it is
     * allowed to retain all rows so residual arithmetic can be compared with HistoryActivity.
     */
    static Result reference(Context context, Request request) throws Exception {
        List<Transaction> stored = new ArrayList<>();
        TransactionStore.forEach(context, request.pageSize, stored::add);

        List<Transaction> scope = new ArrayList<>();
        for (Transaction transaction : stored) {
            if (inScope(transaction, request)) scope.add(transaction);
        }

        // This is the only production-code call site kept for residual parity. Residual detection
        // happens before direction/date/tag/search narrowing, just as HistoryActivity currently does.
        List<Residual> residuals = Residual.between(scope);
        List<Residual> visibleResiduals = HistoryActivity.applyResidualFilters(
            residuals, request.filter, request.iran);
        List<Transaction> visibleTransactions = HistoryActivity.applyFilters(
            scope, request.filter, request.iran);

        visibleTransactions = filterTags(context, visibleTransactions, request.selectedTags);
        if (!request.selectedTags.isEmpty()) visibleResiduals = new ArrayList<>();

        Matcher matcher = new Matcher(context, request);
        if (!matcher.empty()) {
            List<Transaction> searchedTransactions = new ArrayList<>();
            for (Transaction transaction : visibleTransactions) {
                if (matcher.matchesTransaction(transaction)) searchedTransactions.add(transaction);
            }
            visibleTransactions = searchedTransactions;

            List<Residual> searchedResiduals = new ArrayList<>();
            for (Residual residual : visibleResiduals) {
                if (matcher.matchesResidual(residual)) searchedResiduals.add(residual);
            }
            visibleResiduals = searchedResiduals;
        }

        Accumulator accumulator = new Accumulator(request);
        for (Transaction transaction : visibleTransactions) accumulator.addTransaction(transaction);
        for (Residual residual : visibleResiduals) accumulator.addResidual(residual);
        return accumulator.result(true, visibleTransactions, visibleResiduals);
    }

    /** Explicitly named compatibility entry point; it is intentionally not the bounded path. */
    static Result readReference(Context context, Request request) throws Exception {
        return reference(context, request);
    }

    private static boolean inScope(Transaction transaction, Request request) {
        if (transaction == null) return false;
        if (request.bank != null && !request.bank.equals(transaction.bank)) return false;
        return request.account == null || request.account.equals(transaction.account);
    }

    private static boolean matchesMovementFilter(Transaction transaction, Request request) {
        HistoryActivity.Filter filter = request.filter;
        if (filter.direction == HistoryActivity.DIR_DEPOSIT && transaction.amount <= 0) return false;
        if (filter.direction == HistoryActivity.DIR_WITHDRAWAL && transaction.amount >= 0) return false;
        if (filter.from == null && filter.to == null) return true;
        CalDate date = calendarDate(transaction.date, request.iran, Calendar.getInstance(Locale.getDefault()));
        if (filter.from != null && date.compare(filter.from) < 0) return false;
        return filter.to == null || date.compare(filter.to) <= 0;
    }

    private static List<Transaction> filterTags(Context context, List<Transaction> input,
            Collection<String> selected) throws Exception {
        if (selected == null || selected.isEmpty()) return new ArrayList<>(input);
        List<Transaction> out = new ArrayList<>();
        for (Transaction transaction : input) {
            if (matchesTags(context, transaction, selected)) out.add(transaction);
        }
        return out;
    }

    /** Point lookups keep this prototype from materializing notes, reasons, channels, or tags. */
    private static boolean matchesTags(Context context, Transaction transaction,
            Collection<String> selected) throws Exception {
        if (selected == null || selected.isEmpty()) return true;
        List<String> actual = MetadataStore.getTags(context, BalanceData.noteKey(transaction));
        for (String wanted : selected) {
            boolean found = false;
            for (String tag : actual) {
                if (tag != null && wanted != null && tag.trim().equalsIgnoreCase(wanted.trim())) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static final class Matcher {
        private final Context context;
        private final Request request;
        private final List<String> tokens;
        private final boolean persian;
        private final boolean toman;
        private final String deposit;
        private final String withdrawal;
        private final String residualLabel;
        private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);

        Matcher(Context context, Request request) {
            this.context = context;
            this.request = request;
            this.tokens = HistoryActivity.searchTokens(request.searchQuery);
            this.persian = LocaleHelper.isPersian(context);
            this.toman = CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(context));
            this.deposit = context.getString(R.string.history_deposit);
            this.withdrawal = context.getString(R.string.history_withdrawal);
            this.residualLabel = context.getString(R.string.residual_label);
        }

        boolean empty() { return tokens.isEmpty(); }

        boolean matchesTransaction(Transaction transaction) throws Exception {
            if (tokens.isEmpty()) return true;
            String key = BalanceData.noteKey(transaction);
            String note = MetadataStore.getText(context, MetadataStore.NOTES, key);
            String reason = MetadataStore.getText(context, MetadataStore.REASONS, key);
            String channel = MetadataStore.getText(context, MetadataStore.CHANNELS, key);
            List<String> tags = MetadataStore.getTags(context, key);
            CalDate date = calendarDate(transaction.date, request.iran,
                Calendar.getInstance(Locale.getDefault()));
            String haystack = HistoryActivity.transactionSearchText(transaction,
                BankRules.displayName(context, transaction.bank), note, reason,
                BankRules.reasonCaption(context, reason), channel,
                BankRules.channelCaption(context, channel),
                CurrencyHelper.amount(toman, persian, transaction.amount),
                direction(transaction.amount), dateText(date), clock.format(transaction.date),
                CalDate.monthName(date.month, request.iran, persian), compactDate(date), tags);
            return HistoryActivity.matchesTokens(haystack, tokens);
        }

        boolean matchesResidual(Residual residual) {
            if (tokens.isEmpty()) return true;
            CalDate date = calendarDate(residual.toDate, request.iran,
                Calendar.getInstance(Locale.getDefault()));
            String haystack = HistoryActivity.residualSearchText(residual,
                BankRules.displayName(context, residual.bank),
                CurrencyHelper.amount(toman, persian, residual.amount), direction(residual.amount),
                residualLabel, dateText(date), clock.format(residual.toDate),
                CalDate.monthName(date.month, request.iran, persian), compactDate(date));
            return HistoryActivity.matchesTokens(haystack, tokens);
        }

        private String direction(long amount) {
            return amount > 0 ? deposit : amount < 0 ? withdrawal : null;
        }

        private String dateText(CalDate date) {
            if (persian) {
                return HistoryActivity.faDigits(date.day) + " "
                    + CalDate.monthName(date.month, request.iran, true) + " "
                    + HistoryActivity.faDigits(date.year);
            }
            return CalDate.monthName(date.month, request.iran, false) + " "
                + date.day + " " + date.year;
        }

        private String compactDate(CalDate date) {
            String value = date.year + "/" + date.month + "/" + date.day;
            return persian ? HistoryActivity.faDigitsString(value) : value;
        }
    }

    private static final class MutableStats {
        long sum;
        long deposits;
        long withdrawals;
        long movementCount;

        void add(long amount, boolean movement) {
            sum += amount;
            if (amount > 0) deposits += amount;
            else withdrawals += amount;
            if (movement) movementCount++;
        }

        Stats freeze() {
            return new Stats(sum, deposits, withdrawals, movementCount);
        }
    }

    private static final class MutableDay {
        final CalDate date;
        final MutableStats stats = new MutableStats();

        MutableDay(CalDate date) { this.date = date; }
    }

    private static final class MutableMonth {
        final int year;
        final int month;
        final MutableStats stats = new MutableStats();
        final TreeMap<Integer, MutableDay> days = new TreeMap<>(Collections.reverseOrder());

        MutableMonth(int year, int month) {
            this.year = year;
            this.month = month;
        }
    }

    private static final class MutableYear {
        final int year;
        final MutableStats stats = new MutableStats();
        final TreeMap<Integer, MutableMonth> months = new TreeMap<>(Collections.reverseOrder());

        MutableYear(int year) { this.year = year; }
    }

    private static final class RowBucket {
        final String key;
        final List<Row> rows = new ArrayList<>();

        RowBucket(String key) { this.key = key; }

        void add(Row row, int limit) {
            if (rows.size() >= limit) {
                throw new UnsupportedOperationException(
                    "requested day row limit exceeded for " + key
                        + "; increase maxRowsPerDay or request a smaller expanded batch");
            }
            rows.add(row);
        }
    }

    private static final class Accumulator {
        final Request request;
        final MutableStats allTime = new MutableStats();
        final MutableStats today = new MutableStats();
        final MutableStats month = new MutableStats();
        final MutableStats year = new MutableStats();
        final TreeMap<Integer, MutableYear> years = new TreeMap<>(Collections.reverseOrder());
        final Map<String, RowBucket> requested = new LinkedHashMap<>();
        final CalDate todayDate;
        long sequence;

        Accumulator(Request request) {
            this.request = request;
            this.todayDate = CalDate.today(request.iran);
            for (String key : request.requestedDayKeys) requested.put(key, new RowBucket(key));
        }

        void addTransaction(Transaction transaction) {
            add(transaction.date, transaction.amount, true);
            RowBucket bucket = requested.get(dayKey(transaction.date));
            if (bucket != null) bucket.add(new Row(transaction.date, transaction, null, sequence++),
                request.maxRowsPerDay);
        }

        void addResidual(Residual residual) {
            add(residual.toDate, residual.amount, false);
            RowBucket bucket = requested.get(dayKey(residual.toDate));
            if (bucket != null) bucket.add(new Row(residual.toDate, null, residual, sequence++),
                request.maxRowsPerDay);
        }

        private void add(long dateMillis, long amount, boolean movement) {
            CalDate date = calendarDate(dateMillis, request.iran,
                Calendar.getInstance(Locale.getDefault()));
            allTime.add(amount, movement);
            if (date.sameDay(todayDate)) today.add(amount, movement);
            if (date.year == todayDate.year && date.month == todayDate.month)
                month.add(amount, movement);
            if (date.year == todayDate.year) year.add(amount, movement);

            MutableYear yearGroup = years.get(date.year);
            if (yearGroup == null) {
                yearGroup = new MutableYear(date.year);
                years.put(date.year, yearGroup);
            }
            yearGroup.stats.add(amount, movement);

            MutableMonth monthGroup = yearGroup.months.get(date.month);
            if (monthGroup == null) {
                monthGroup = new MutableMonth(date.year, date.month);
                yearGroup.months.put(date.month, monthGroup);
            }
            monthGroup.stats.add(amount, movement);

            MutableDay dayGroup = monthGroup.days.get(date.day);
            if (dayGroup == null) {
                dayGroup = new MutableDay(date);
                monthGroup.days.put(date.day, dayGroup);
            }
            dayGroup.stats.add(amount, movement);
        }

        String dayKey(long dateMillis) {
            return calendarDate(dateMillis, request.iran,
                Calendar.getInstance(Locale.getDefault())).key();
        }

        Result result(boolean reference, List<Transaction> transactions,
                List<Residual> residuals) {
            List<YearSummary> yearResults = new ArrayList<>();
            LinkedHashMap<String, DaySummary> dayResults = new LinkedHashMap<>();
            for (MutableYear yearGroup : years.values()) {
                List<MonthSummary> monthResults = new ArrayList<>();
                for (MutableMonth monthGroup : yearGroup.months.values()) {
                    List<DaySummary> dayResultsForMonth = new ArrayList<>();
                    for (MutableDay dayGroup : monthGroup.days.values()) {
                        DaySummary day = new DaySummary(dayGroup.date, dayGroup.stats.freeze());
                        dayResultsForMonth.add(day);
                        dayResults.put(day.date.key(), day);
                    }
                    monthResults.add(new MonthSummary(monthGroup.year, monthGroup.month,
                        monthGroup.stats.freeze(), dayResultsForMonth));
                }
                yearResults.add(new YearSummary(yearGroup.year, yearGroup.stats.freeze(), monthResults));
            }

            LinkedHashMap<String, DayRows> rowResults = new LinkedHashMap<>();
            for (String key : request.requestedDayKeys) {
                RowBucket bucket = requested.get(key);
                if (bucket == null) bucket = new RowBucket(key);
                bucket.rows.sort(new Comparator<Row>() {
                    @Override public int compare(Row a, Row b) {
                        int byDate = Long.compare(b.date, a.date);
                        if (byDate != 0) return byDate;
                        if ((a.residual == null) != (b.residual == null))
                            return a.residual == null ? 1 : -1;
                        return Long.compare(a.sequence, b.sequence);
                    }
                });
                CalDate date = bucket.rows.isEmpty() ? null
                    : calendarDate(bucket.rows.get(0).date, request.iran,
                        Calendar.getInstance(Locale.getDefault()));
                List<Transaction> dayTransactions = new ArrayList<>();
                List<Residual> dayResiduals = new ArrayList<>();
                for (Row row : bucket.rows) {
                    if (row.transaction != null) dayTransactions.add(row.transaction);
                    else dayResiduals.add(row.residual);
                }
                rowResults.put(key, new DayRows(key, date, new ArrayList<>(bucket.rows),
                    dayTransactions, dayResiduals));
            }
            List<Transaction> visibleTx = transactions == null ? null
                : Collections.unmodifiableList(new ArrayList<>(transactions));
            List<Residual> visibleResidual = residuals == null ? null
                : Collections.unmodifiableList(new ArrayList<>(residuals));
            return new Result(reference, allTime.freeze(), today.freeze(), month.freeze(),
                year.freeze(), yearResults, dayResults, rowResults, visibleTx, visibleResidual);
        }
    }

    private static CalDate calendarDate(long millis, boolean iran, Calendar calendar) {
        calendar.setTimeInMillis(millis);
        return CalDate.fromGregorian(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), iran);
    }

    private static List<String> immutableStrings(Collection<String> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(input));
    }

    private static Set<String> immutableSet(Collection<String> input) {
        if (input == null || input.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(new LinkedHashSet<>(input));
    }
}
