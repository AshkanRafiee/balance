package com.ashkanrafiee.balance;

import static com.ashkanrafiee.balance.HistoryActivity.faDigits;
import static com.ashkanrafiee.balance.HistoryActivity.faDigitsString;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Month-grid picker for a History date range or a single cash transaction date. */
final class CalendarRangePicker {
    final CalDate[] picked;
    /** Cash mode selects one date between the epoch and today. */
    final CalDate earliest, latest;
    private final Context context;
    private final boolean iranCalendar;
    private final boolean persian;
    private final Consumer<AlertDialog> showDialog;
    private final int fg, muted, accent, chipBg;
    private final LinearLayout grid;
    private final TextView title, status, prev, next;
    int viewYear;
    int viewMonth;

    CalendarRangePicker(Context context, boolean iranCalendar, CalDate from, CalDate to,
            CalDate latest, Consumer<AlertDialog> showDialog) {
        this.context = context;
        this.iranCalendar = iranCalendar;
        this.showDialog = showDialog;
        persian = LocaleHelper.isPersian(context);
        fg = ExpressiveUi.color(context, R.color.fg);
        muted = ExpressiveUi.color(context, R.color.expressive_on_surface_variant);
        accent = ExpressiveUi.color(context, R.color.expressive_primary);
        chipBg = ExpressiveUi.color(context, R.color.expressive_surface_container_high);
        grid = ExpressiveUi.column(context);
        title = ExpressiveUi.heading(context, "", 14);
        status = text("", 12.5f, muted);
        prev = navButton("\u2039");
        next = navButton("\u203A");
        picked = new CalDate[]{from, to};
        this.latest = latest;
        earliest = latest == null ? null : calOf(1, iranCalendar);
        CalDate start = picked[0] != null ? picked[0] : picked[1];
        if (start == null) start = CalDate.today(iranCalendar);
        viewYear = start.year;
        viewMonth = start.month;
        title.setClickable(true);
        title.setFocusable(true);
        title.setContentDescription(context.getString(R.string.history_jump_title));
        title.setOnClickListener(v -> promptJump());
        render();
    }

    LinearLayout content() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutDirection(context.getResources().getConfiguration().getLayoutDirection());
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout monthRow = new LinearLayout(context);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.setMarginStart(dp(8));
        titleLp.setMarginEnd(dp(8));
        monthRow.addView(prev, new LinearLayout.LayoutParams(-2, -2));
        monthRow.addView(title, titleLp);
        monthRow.addView(next, new LinearLayout.LayoutParams(-2, -2));
        wrap.addView(monthRow, ExpressiveUi.widthMatch(context, 8, 0));
        wrap.addView(grid, ExpressiveUi.widthMatch(context, 6, 0));
        return wrap;
    }

    /** A single dialog to jump straight to any month and year of the active calendar instead of
     *  stepping month by month: a month dropdown plus a typeable year field. */
    void promptJump() {
        final List<String> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) months.add(monthName(m));
        final Spinner monthSpin = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_spinner_dropdown_item, months);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        monthSpin.setAdapter(adapter);
        monthSpin.setSelection(viewMonth - 1);

        final EditText year = new EditText(context);
        year.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        year.setText(Integer.toString(viewYear));
        year.selectAll();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8) + dp(18), dp(4), dp(8), dp(4));
        TextView mLabel = text(context.getString(R.string.history_month_label), 13, muted);
        box.addView(mLabel);
        box.addView(monthSpin, new LinearLayout.LayoutParams(-1, -2));
        String yRange = context.getString(R.string.history_year_label,
            minYear(), maxYear());
        if (persian) yRange = faDigitsString(yRange);
        TextView yLabel = text(yRange, 13, muted);
        LinearLayout.LayoutParams yLp = new LinearLayout.LayoutParams(-1, -2);
        yLp.setMargins(0, dp(10), 0, 0);
        box.addView(yLabel, yLp);
        box.addView(year, new LinearLayout.LayoutParams(-1, -2));

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.history_jump_title))
            .setView(box)
            .setPositiveButton(context.getString(R.string.history_jump_apply), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                viewYear = parseYear(year.getText());
                viewMonth = Math.max(firstMonth(), Math.min(
                    monthSpin.getSelectedItemPosition() + 1, lastMonth()));
                dlg.dismiss();
                render();
            }));
        showDialog.accept(dlg);
    }

    /** Reads the entered year, accepting Latin or Persian digits, and clamps it to the
     *  navigable year band that the chevrons already bound to, per calendar system. */
    int parseYear(CharSequence s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toString().trim().toCharArray()) {
            if (c >= '\u06f0' && c <= '\u06f9') b.append((char) ('0' + c - '\u06f0'));
            else if (c >= '0' && c <= '9') b.append(c);
        }
        int year;
        try {
            year = Integer.parseInt(b.toString());
        } catch (NumberFormatException e) {
            return viewYear;
        }
        return Math.max(minYear(), Math.min(maxYear(), year));
    }

    int minYear() {
        return earliest == null ? CalDate.minYear(iranCalendar) : earliest.year;
    }

    int maxYear() {
        return latest == null ? CalDate.maxYear(iranCalendar) : latest.year;
    }

    int firstMonth() {
        return earliest != null && viewYear == earliest.year ? earliest.month : 1;
    }

    int lastMonth() {
        return latest != null && viewYear == latest.year ? latest.month : 12;
    }

    TextView navButton(String arrow) {
        TextView b = ExpressiveUi.heading(context, arrow, 18);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(2), dp(12), dp(2));
        b.setBackground(ExpressiveUi.shape(context, chipBg, 10));
        b.setContentDescription(context.getString("\u2039".equals(arrow)
            ? R.string.history_prev_month : R.string.history_next_month));
        return b;
    }

    /** Rebuilds the month title, the prev/next affordance and the day grid for the viewed month. */
    void render() {
        title.setText(monthName(viewMonth) + " "
            + (persian ? faDigits(viewYear) : Integer.toString(viewYear)) + " \u25be");
        title.setGravity(Gravity.CENTER);
        bindNav(prev, viewMonth > firstMonth() || viewYear > minYear(), -1);
        bindNav(next, viewMonth < lastMonth() || viewYear < maxYear(), 1);
        grid.removeAllViews();

        String[] weekdays = weekdayLabels();
        LinearLayout weekRow = new LinearLayout(context);
        for (String w : weekdays) {
            TextView h = text(w, 11, muted);
            h.setGravity(Gravity.CENTER);
            weekRow.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        }
        grid.addView(weekRow, new LinearLayout.LayoutParams(-1, -2));

        CalDate today = CalDate.today(iranCalendar);
        int firstDay = CalDate.weekdayIndex(CalDate.of(viewYear, viewMonth, 1), iranCalendar);
        int days = CalDate.daysInMonth(viewYear, viewMonth, iranCalendar);
        for (int offset = 0; offset < firstDay + days; offset += 7) {
            LinearLayout row = new LinearLayout(context);
            for (int col = 0; col < 7; col++) {
                int d = offset + col - firstDay + 1;
                TextView cell = d < 1 || d > days
                    ? text("", 0, fg) : dayCell(CalDate.of(viewYear, viewMonth, d), today);
                row.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        updateStatus();
    }

    /** One tappable day, highlighted as a range bound, today's outline, or the ranged tint. */
    TextView dayCell(CalDate day, CalDate today) {
        boolean fromSel = picked[0] != null && picked[0].sameDay(day);
        boolean toSel = picked[1] != null && picked[1].sameDay(day);
        boolean inRange = picked[0] != null && picked[1] != null
            && picked[0].compare(day) <= 0 && day.compare(picked[1]) <= 0;
        boolean isToday = today.sameDay(day);
        TextView cell = text(persian ? faDigits(day.day) : Integer.toString(day.day), 12.5f,
            fromSel || toSel ? ExpressiveUi.color(context,
                R.color.expressive_on_primary) : fg);
        cell.setGravity(Gravity.CENTER);
        if (latest != null && (day.compare(latest) > 0 || day.compare(earliest) < 0)) {
            cell.setEnabled(false);
            cell.setAlpha(.35f);
            return cell;
        }
        if (fromSel || toSel) {
            cell.setBackground(ExpressiveUi.shape(context, accent, 10));
        } else if (isToday) {
            cell.setBackground(ExpressiveUi.outline(context, Color.TRANSPARENT, 10, accent));
        } else if (inRange) {
            int rangeFill = (accent & 0x00FFFFFF) | 0x26000000;
            cell.setBackground(ExpressiveUi.shape(context, rangeFill, 10));
        }
        cell.setContentDescription(dateText(day)
            + (fromSel && latest == null ? " \u2014 " + context.getString(R.string.history_range_start)
                : toSel ? " \u2014 " + context.getString(R.string.history_range_end)
                : inRange ? " \u2014 " + context.getString(R.string.history_range_selected) : ""));
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setSelected(fromSel || toSel);
        cell.setOnClickListener(v -> {
            if (latest == null) pickDay(picked, day);
            else picked[0] = day;
            render();
        });
        return cell;
    }

    void bindNav(TextView arrow, boolean canStep, int delta) {
        arrow.setEnabled(canStep);
        arrow.setAlpha(canStep ? 1f : .35f);
        arrow.setClickable(canStep);
        arrow.setOnClickListener(canStep ? v -> step(delta) : null);
    }

    void step(int delta) {
        viewMonth += delta;
        if (viewMonth < 1) {
            viewMonth = 12;
            viewYear--;
        } else if (viewMonth > 12) {
            viewMonth = 1;
            viewYear++;
        }
        render();
    }

    /** Tells the user what to pick next, or shows the closing summary once the range is full. */
    void updateStatus() {
        if (latest != null) {
            status.setText(dateText(picked[0]));
        } else if (picked[0] == null) {
            status.setText(R.string.history_filter_pick_from);
        } else if (picked[1] == null) {
            status.setText(context.getString(R.string.history_filter_pick_to, compactDate(picked[0])));
        } else {
            status.setText(context.getString(R.string.history_filter_range_summary,
                compactDate(picked[0]), compactDate(picked[1])));
        }
    }

    /** The tap rule for the range calendar, kept pure so the tests cover it: the first pick sets
     *  From, the next sets To, a tap while both are set starts a fresh From, and a To tapped before
     *  From swaps the bounds so From always precedes To. */
    static void pickDay(CalDate[] picked, CalDate day) {
        if (picked[0] == null || picked[1] != null) {
            picked[0] = day;
            picked[1] = null;
        } else if (day.compare(picked[0]) < 0) {
            picked[1] = picked[0];
            picked[0] = day;
        } else {
            picked[1] = day;
        }
    }

    /** The 7 weekday grid headings in the app language. The Iran region leads with Saturday, the
     *  International region leads with Monday (ISO). */
    private String[] weekdayLabels() {
        if (iranCalendar) {
            return persian
                ? new String[]{"ش", "ی", "د", "س", "چ", "پ", "ج"}
                : new String[]{"Sa", "Su", "Mo", "Tu", "We", "Th", "Fr"};
        }
        return persian
            ? new String[]{"د", "ی", "س", "چ", "پ", "ج", "ش"}
            : new String[]{"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};
    }

    private int dp(float value) {
        return ExpressiveUi.dp(context, value);
    }

    private TextView text(String value, float size, int color) {
        return ExpressiveUi.label(context, value, size, color);
    }

    private String monthName(int month) {
        return CalDate.monthName(month, iranCalendar, persian);
    }

    private String dateText(CalDate date) {
        return dateText(date, iranCalendar, persian);
    }

    static String dateText(CalDate date, boolean iranCalendar, boolean persian) {
        String month = CalDate.monthName(date.month, iranCalendar, persian);
        return persian ? faDigits(date.day) + " " + month + " " + faDigits(date.year)
            : month + " " + date.day + " " + date.year;
    }

    static CalDate calOf(long timestamp, boolean iranCalendar) {
        Calendar clock = Calendar.getInstance(Locale.US);
        clock.setTimeInMillis(timestamp);
        return CalDate.fromGregorian(clock.get(Calendar.YEAR), clock.get(Calendar.MONTH) + 1,
            clock.get(Calendar.DAY_OF_MONTH), iranCalendar);
    }

    private String compactDate(CalDate date) {
        return persian ? faDigitsString(date.key()) : date.key();
    }
}
