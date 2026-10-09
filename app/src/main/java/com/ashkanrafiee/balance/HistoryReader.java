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
 * Bounded-memory history projection for the history screen.
 *
 * <p>{@link #summary(Context, Request)} is the production-shaped path. It consumes the stable
 * {@link TransactionStore#forEach} snapshot once, retains aggregates for calendar periods, and
 * writes visible rows to an encrypted disk timeline. Only requested page rows and their point
 * metadata are retained in the result heap.
 *
 * <p>{@link #reference(Context, Request)} is intentionally named as a compatibility method. It
 * materializes the scoped transaction list, calls {@link Residual#between(List)}, and applies the
 * same filtering order as {@link HistoryActivity}. It is a parity oracle for tests and for the
 * later migration of the screen; it is not the bounded reader.
 */
final class HistoryReader {
    static final int DEFAULT_PAGE_SIZE = 256;
    static final int DEFAULT_ROW_PAGE_SIZE = HistoryTimeline.DEFAULT_PAGE_SIZE;
    /** Source-compatible name from the old bounded prototype; it is no longer a hard limit. */
    static final int DEFAULT_MAX_REQUESTED_DAYS = 64;
    /** Source-compatible name; it selects the initial/page batch size now. */
    static final int DEFAULT_MAX_ROWS_PER_DAY = 128;

    static final String SUMMARY_RESIDUAL_LIMITATION =
        "Summary mode does not calculate residuals; use the explicit reference mode until "
        + "date-ordered residual staging is available";

    private HistoryReader() {}

    enum Mode { SUMMARY, RESIDUAL_SUMMARY, REFERENCE }

    /** The immutable scope and page policy for one read. */
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
         * Creates a read request. The old day value is retained for source compatibility but is
         * ignored as a cap. The old row value selects the initial page size; no history rows are
         * rejected or silently omitted.
         */
        Request(boolean iran, int pageSize, String bank, String account,
                HistoryActivity.Filter filter, String searchQuery,
                Collection<String> selectedTags, Collection<String> requestedDayKeys,
                int maxRequestedDays, int maxRowsPerDay) {
            if (maxRequestedDays < 0) throw new IllegalArgumentException("invalid page-day hint");
            if (maxRowsPerDay < 0) throw new IllegalArgumentException("invalid row page size");
            this.iran = iran;
            this.pageSize = pageSize;
            this.bank = bank;
            this.account = account;
            this.filter = filter == null ? HistoryActivity.Filter.ALL : filter;
            this.searchQuery = searchQuery == null ? "" : searchQuery.trim();
            this.selectedTags = immutableStrings(selectedTags);
            this.requestedDayKeys = immutableSet(requestedDayKeys);
            this.maxRequestedDays = maxRequestedDays;
            this.maxRowsPerDay = maxRowsPerDay == 0 ? DEFAULT_ROW_PAGE_SIZE : maxRowsPerDay;
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
     * One history result. The summary fields are complete for visible rows. Full visible lists are
     * populated only by {@link #reference(Context, Request)}; the normal path exposes all rows by
     * keyset pages through its encrypted timeline.
     */
    static final class Result {
        final boolean reference;
        final boolean residualsComplete;
        final String limitation;
        final boolean iranCalendar;
        /** Count of visible residual rows, including rows outside the currently loaded pages. */
        final long residualCount;
        final boolean hasResiduals;

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
        /** Initial pages for requested days; adjacent pages are loaded through {@link #readPage}. */
        final Map<String, DayRows> requestedDayRows;

        /** Full visible lists in reference mode; null in summary mode by design. */
        final List<Transaction> visibleTransactions;
        final List<Residual> visibleResiduals;
        private HistoryTimeline timeline;

        private Result(boolean reference, boolean residualsComplete, Stats allTime, Stats today,
                Stats month, Stats year,
                List<YearSummary> years, Map<String, DaySummary> daySummaries,
                Map<String, DayRows> requestedDayRows, List<Transaction> visibleTransactions,
                List<Residual> visibleResiduals, long residualCount, HistoryTimeline timeline,
                boolean iranCalendar) {
            this.reference = reference;
            this.residualsComplete = residualsComplete;
            this.limitation = residualsComplete ? null : SUMMARY_RESIDUAL_LIMITATION;
            this.iranCalendar = iranCalendar;
            this.residualCount = residualCount;
            this.hasResiduals = residualCount != 0;
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
            this.timeline = timeline;
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
                "summary mode exposes rows through day pages; use readPage for additional rows");
        }

        /** Releases the encrypted disk snapshot held by this result. Safe to call more than once. */
        void close() throws Exception {
            HistoryTimeline old = timeline;
            timeline = null;
            if (old != null) old.close();
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

    /** Point metadata for one transaction in a loaded page. */
    static final class Metadata {
        final String note;
        final String reason;
        final String channel;
        final List<String> tags;

        Metadata(String note, String reason, String channel, List<String> tags) {
            this.note = note;
            this.reason = reason;
            this.channel = channel;
            this.tags = tags == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tags));
        }
    }

    /** Rows for one requested expanded day, in the same newest-first/tie order as the screen. */
    static final class DayRows {
        final String key;
        final CalDate date;
        final List<Row> rows;
        final List<Transaction> transactions;
        final List<Residual> residuals;
        final Map<String, Metadata> metadata;
        final boolean hasPrevious;
        final boolean hasMore;
        final HistoryTimeline.CursorKey firstCursor;
        final HistoryTimeline.CursorKey lastCursor;

        private DayRows(String key, CalDate date, List<Row> rows,
                List<Transaction> transactions, List<Residual> residuals,
                Map<String, Metadata> metadata, boolean hasPrevious, boolean hasMore,
                HistoryTimeline.CursorKey firstCursor, HistoryTimeline.CursorKey lastCursor) {
            this.key = key;
            this.date = date;
            this.rows = Collections.unmodifiableList(rows);
            this.transactions = Collections.unmodifiableList(transactions);
            this.residuals = Collections.unmodifiableList(residuals);
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.hasPrevious = hasPrevious;
            this.hasMore = hasMore;
            this.firstCursor = firstCursor;
            this.lastCursor = lastCursor;
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
        if (mode == Mode.SUMMARY) return summary(context, request);
        if (mode == Mode.RESIDUAL_SUMMARY) return summaryWithResiduals(context, request);
        return reference(context, request);
    }

    /** One stable pass over the transaction store; rows remain available through the timeline. */
    static Result summary(Context context, Request request) throws Exception {
        return timelineSummary(context, request, false);
    }

    /** Complete summary plus date-ordered residuals emitted into the encrypted timeline. */
    static Result summaryWithResiduals(Context context, Request request) throws Exception {
        return timelineSummary(context, request, true);
    }

    /** Same as {@link #summaryWithResiduals(Context, Request)} with advisory progress. */
    static Result summaryWithResiduals(Context context, Request request, WorkProgress progress)
            throws Exception {
        return timelineSummary(context, request, true, progress);
    }

    /**
     * Builds one immutable cross-store snapshot. All transaction, residual and point-metadata
     * passes happen under the same store monitor: a scan cannot publish a new generation between
     * the summary and the residual walk, and a metadata edit cannot make a page disagree with the
     * search that selected it.
     */
    private static Result timelineSummary(Context context, Request request, boolean residuals)
            throws Exception {
        return timelineSummary(context, request, residuals, null);
    }

    /**
     * Scope sizes at or below this run residual detection in memory instead of through the
     * encrypted staging database. SMS-scale histories (thousands of rows, a few megabytes
     * transient) take the fast path; anything larger keeps the bounded external walk, so peak
     * memory never depends on total retained data. The scoped set can never outgrow the store
     * total the gate measured, and exceeding the bound still fails closed.
     */
    static final int MEMORY_RESIDUAL_LIMIT = 20_000;

    /** Same as {@link #timelineSummary(Context, Request, boolean)} with advisory progress. */
    private static Result timelineSummary(Context context, Request request, boolean residuals,
            WorkProgress progress) throws Exception {
        if (context == null) throw new NullPointerException("context");
        if (request == null) throw new NullPointerException("request");
        synchronized (BalanceData.class) {
            HistoryTimeline.Builder timeline = HistoryTimeline.open(context);
            Accumulator accumulator = new Accumulator(context, request, timeline);
            try (MetadataStore.LookupSession metadata = MetadataStore.LookupSession.open(context)) {
                Matcher matcher = new Matcher(context, request, metadata);
                boolean stageResiduals = residuals && request.selectedTags.isEmpty();
                // SMS-scale scopes skip the encrypted staging database: the scoped rows are
                // already decrypted in hand, and Residual.between over them is exactly what the
                // reference oracle computes. Larger scopes keep the bounded external walk.
                boolean memoryResiduals = stageResiduals
                    && TransactionStore.estimateCount(context) <= MEMORY_RESIDUAL_LIMIT;
                List<Transaction> memoryScope = memoryResiduals ? new ArrayList<>() : null;
                try (HistoryResidualReader.Staging residualStaging =
                        stageResiduals && !memoryResiduals
                            ? HistoryResidualReader.Staging.open(context) : null) {
                    if (progress != null) progress.stage(R.string.history_stage_loading);
                    TransactionStore.forEach(context, request.pageSize, transaction -> {
                        // Scope narrowing precedes all other filters. Residual detection needs the
                        // whole bank/account scope, so staging happens before narrowing predicates.
                        if (!inScope(transaction, request)) return;
                        if (residualStaging != null) residualStaging.add(transaction);
                        if (memoryScope != null) {
                            if (memoryScope.size() >= MEMORY_RESIDUAL_LIMIT)
                                throw new IllegalStateException("residual scope outgrew its bound");
                            memoryScope.add(transaction);
                        }
                        if (!matchesMovementFilter(transaction, request)) return;
                        if (!matchesTags(metadata, transaction, request.selectedTags)) return;
                        if (!matcher.matchesTransaction(transaction)) return;
                        accumulator.addTransaction(transaction);
                    });
                    if (residualStaging != null) {
                        if (progress != null) progress.stage(R.string.history_stage_gaps);
                        residualStaging.emit(residual -> {
                            if (!matchesResidualFilter(residual, request)) return;
                            if (!matcher.matchesResidual(residual)) return;
                            accumulator.addResidual(residual);
                        }, request.pageSize);
                    } else if (memoryScope != null) {
                        if (progress != null) progress.stage(R.string.history_stage_gaps);
                        for (Residual residual : Residual.between(memoryScope)) {
                            if (!matchesResidualFilter(residual, request)) continue;
                            if (!matcher.matchesResidual(residual)) continue;
                            accumulator.addResidual(residual);
                        }
                    }
                }
                if (progress != null) progress.stage(R.string.history_stage_building);
                return accumulator.result(false, residuals, null, null);
            } finally {
                accumulator.close();
            }
        }
    }

    static Result readSummaryWithResiduals(Context context, Request request) throws Exception {
        return summaryWithResiduals(context, request);
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
        List<Residual> filteredResiduals = HistoryActivity.applyResidualFilters(
            residuals, request.filter, request.iran);
        List<Transaction> filteredTransactions = HistoryActivity.applyFilters(
            scope, request.filter, request.iran);

        List<Transaction> visibleTransactions;
        List<Residual> visibleResiduals;
        try (MetadataStore.LookupSession metadata = MetadataStore.LookupSession.open(context)) {
            visibleTransactions = filterTags(metadata, filteredTransactions, request.selectedTags);
            visibleResiduals = new ArrayList<>(filteredResiduals);
            if (!request.selectedTags.isEmpty()) visibleResiduals = new ArrayList<>();

            Matcher matcher = new Matcher(context, request, metadata);
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
        }

        Accumulator accumulator = new Accumulator(context, request);
        for (Transaction transaction : visibleTransactions) accumulator.addTransaction(transaction);
        for (Residual residual : visibleResiduals) accumulator.addResidual(residual);
        return accumulator.result(true, true, visibleTransactions, visibleResiduals);
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

    private static boolean matchesResidualFilter(Residual residual, Request request) {
        HistoryActivity.Filter filter = request.filter;
        if (filter.direction == HistoryActivity.DIR_DEPOSIT && residual.amount <= 0) return false;
        if (filter.direction == HistoryActivity.DIR_WITHDRAWAL && residual.amount >= 0) return false;
        if (filter.from == null && filter.to == null) return true;
        CalDate date = calendarDate(residual.toDate, request.iran,
            Calendar.getInstance(Locale.getDefault()));
        if (filter.from != null && date.compare(filter.from) < 0) return false;
        return filter.to == null || date.compare(filter.to) <= 0;
    }

    private static List<Transaction> filterTags(MetadataStore.LookupSession metadata,
            List<Transaction> input, Collection<String> selected) throws Exception {
        if (selected == null || selected.isEmpty()) return new ArrayList<>(input);
        List<Transaction> out = new ArrayList<>();
        for (Transaction transaction : input) {
            if (matchesTags(metadata, transaction, selected)) out.add(transaction);
        }
        return out;
    }

    /** Session point lookups keep this prototype from materializing notes, reasons, channels, or tags. */
    private static boolean matchesTags(MetadataStore.LookupSession metadata, Transaction transaction,
            Collection<String> selected) throws Exception {
        if (selected == null || selected.isEmpty()) return true;
        List<String> actual = metadata.tags(BalanceData.noteKey(transaction));
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
        private final MetadataStore.LookupSession metadata;
        private final Request request;
        private final List<String> tokens;
        private final boolean persian;
        private final boolean toman;
        private final String deposit;
        private final String withdrawal;
        private final String residualLabel;
        private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);

        Matcher(Context context, Request request, MetadataStore.LookupSession metadata) {
            this.context = context;
            this.request = request;
            this.metadata = metadata;
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
            String note = metadata.text(MetadataStore.NOTES, key);
            String reason = metadata.text(MetadataStore.REASONS, key);
            String channel = metadata.text(MetadataStore.CHANNELS, key);
            List<String> tags = metadata.tags(key);
            CalDate date = calendarDate(transaction.date, request.iran,
                Calendar.getInstance(Locale.getDefault()));
            String haystack = HistoryActivity.transactionSearchText(transaction,
                BankRules.displayName(context, transaction.bank), note, reason,
                BankRules.reasonCaption(context, reason), channel,
                BankRules.channelCaption(context, channel),
                 CurrencyHelper.amount(toman, persian, transaction.amount),
                 direction(transaction.amount), dateText(date), timeText(transaction.date),
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

        private String timeText(long millis) {
            String value = clock.format(millis);
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

    private static final class Accumulator {
        final Context context;
        final Request request;
        HistoryTimeline.Builder timeline;
        final MutableStats allTime = new MutableStats();
        final MutableStats today = new MutableStats();
        final MutableStats month = new MutableStats();
        final MutableStats year = new MutableStats();
        final TreeMap<Integer, MutableYear> years = new TreeMap<>(Collections.reverseOrder());
        final CalDate todayDate;
        long transactionOrdinal;
        long residualOrdinal;
        long residualCount;

        Accumulator(Context context, Request request, HistoryTimeline.Builder timeline) {
            this.context = context;
            this.request = request;
            this.timeline = timeline;
            this.todayDate = CalDate.today(request.iran);
        }

        Accumulator(Context context, Request request) {
            this(context, request, null);
        }

        void addTransaction(Transaction transaction) throws Exception {
            add(transaction.date, transaction.amount, true);
            if (timeline != null) {
                timeline.addTransaction(dayKey(transaction.date), transaction, transactionOrdinal);
                transactionOrdinal = nextOrdinal(transactionOrdinal);
            }
        }

        void addResidual(Residual residual) throws Exception {
            add(residual.toDate, residual.amount, false);
            residualCount++;
            if (timeline != null) {
                timeline.addResidual(dayKey(residual.toDate), residual, residualOrdinal);
                residualOrdinal = nextOrdinal(residualOrdinal);
            }
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

        private long nextOrdinal(long ordinal) {
            return ordinal == Long.MAX_VALUE ? -1 : ordinal + 1;
        }

        Result result(boolean reference, boolean residualsComplete, List<Transaction> transactions,
                List<Residual> residuals) throws Exception {
            HistoryTimeline snapshot = timeline == null ? null : timeline.finish();
            timeline = null;
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
            if (snapshot != null && !request.requestedDayKeys.isEmpty()) {
                try (MetadataStore.LookupSession metadata =
                        MetadataStore.LookupSession.open(context)) {
                    for (String key : request.requestedDayKeys) {
                        rowResults.put(key, fromPage(metadata, snapshot,
                            snapshot.firstPage(key, request.maxRowsPerDay), request.iran));
                    }
                }
            }
            List<Transaction> visibleTx = transactions == null ? null
                : Collections.unmodifiableList(new ArrayList<>(transactions));
            List<Residual> visibleResidual = residuals == null ? null
                : Collections.unmodifiableList(new ArrayList<>(residuals));
            return new Result(reference, residualsComplete, allTime.freeze(), today.freeze(), month.freeze(),
                year.freeze(), yearResults, dayResults, rowResults, visibleTx, visibleResidual,
                residualCount, snapshot, request.iran);
        }

        void close() throws Exception {
            if (timeline != null) {
                timeline.close();
                timeline = null;
            }
        }
    }

    /** Loads one timeline page and the metadata needed to render only that page. */
    /**
     * Reads one day's first page reusing the caller's metadata session. Paging a whole seed set
     * through one session matches the old in-result paging cost; opening a session per day
     * multiplies store opens by the day count.
     */
    static DayRows readPage(MetadataStore.LookupSession metadata, Result result, String dayKey,
            int pageSize) throws Exception {
        if (metadata == null) throw new NullPointerException("metadata");
        if (result == null) throw new NullPointerException("result");
        HistoryTimeline snapshot = result.timeline;
        if (snapshot == null) throw new IllegalStateException("history result is closed");
        return fromPage(metadata, snapshot, snapshot.firstPage(dayKey, pageSize),
            result.iranCalendar);
    }

    static DayRows readPage(Context context, Result result, String dayKey,
            HistoryTimeline.CursorKey cursor, boolean previous, int pageSize) throws Exception {
        if (context == null) throw new NullPointerException("context");
        if (result == null) throw new NullPointerException("result");
        synchronized (BalanceData.class) {
            HistoryTimeline snapshot = result.timeline;
            if (snapshot == null) throw new IllegalStateException("history result is closed");
            HistoryTimeline.Page page;
            if (cursor == null) {
                if (previous) throw new IllegalArgumentException("previous page needs a cursor");
                page = snapshot.firstPage(dayKey, pageSize);
            } else if (previous) {
                page = snapshot.previousPage(dayKey, cursor, pageSize);
            } else {
                page = snapshot.nextPage(dayKey, cursor, pageSize);
            }
            try (MetadataStore.LookupSession metadata = MetadataStore.LookupSession.open(context)) {
                return fromPage(metadata, snapshot, page, result.iranCalendar);
            }
        }
    }

    private static DayRows fromPage(MetadataStore.LookupSession metadata, HistoryTimeline snapshot,
            HistoryTimeline.Page page, boolean iran) throws Exception {
        List<Row> rows = new ArrayList<>(page.entries.size());
        List<Transaction> transactions = new ArrayList<>();
        List<Residual> residuals = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        CalDate date = null;
        for (HistoryTimeline.Entry entry : page.entries) {
            if (date == null) date = calendarDate(entry.date, iran,
                Calendar.getInstance(Locale.getDefault()));
            Row row = new Row(entry.date, entry.transaction, entry.residual, entry.tie);
            rows.add(row);
            if (entry.transaction != null) {
                transactions.add(entry.transaction);
                keys.add(BalanceData.noteKey(entry.transaction));
            } else {
                residuals.add(entry.residual);
            }
        }
        // One indexed query per kind for the whole page instead of four store accesses per row.
        Map<String, String> notes = metadata.texts(MetadataStore.NOTES, keys);
        Map<String, String> reasons = metadata.texts(MetadataStore.REASONS, keys);
        Map<String, String> channels = metadata.texts(MetadataStore.CHANNELS, keys);
        Map<String, List<String>> tags = metadata.tagsFor(keys);
        Map<String, Metadata> pageMetadata = new LinkedHashMap<>();
        for (String key : keys) {
            if (!pageMetadata.containsKey(key)) {
                pageMetadata.put(key, new Metadata(notes.get(key), reasons.get(key),
                    channels.get(key),
                    tags.containsKey(key) ? tags.get(key) : Collections.emptyList()));
            }
        }
        return new DayRows(page.dayKey, date, rows, transactions, residuals, pageMetadata,
            page.hasPrevious, page.hasMore, page.first, page.last);
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
