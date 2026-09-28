package com.ashkanrafiee.balance.parser;

import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;

/** Standalone arithmetic oracle. Compile the unchanged app JalaliCalendar.java alongside
 * this suite; reflection keeps the app class out of the parser-core compile dependencies.
 * Selection, inferred-year and timezone semantics are deliberately NOT parity claims. */
public final class JalaliParityTest {
    private static int checks;

    public static void main(String[] args) throws ReflectiveOperationException {
        Class<?> oracle = Class.forName("com.ashkanrafiee.balance.JalaliCalendar");
        Method of = accessible(oracle, "of", int.class, int.class, int.class);
        Method toGregorian = accessible(oracle, "toGregorian");
        Method days = accessible(oracle, "daysInMonth", int.class, int.class);
        // All supported years/months: both month boundaries, including every breakpoint/leap day.
        for (int year = 1; year <= 3177; year++) {
            for (int month = 1; month <= 12; month++) {
                int length = (Integer) days.invoke(null, year, month);
                equal(JalaliCalendar.daysInMonth(year, month), length);
                for (int day : new int[]{1, length}) {
                    int[] expected = (int[]) toGregorian.invoke(of.invoke(null, year, month, day));
                    LocalDate actual = JalaliCalendar.toGregorian(year, month, day);
                    equal(Arrays.equals(new int[]{actual.getYear(), actual.getMonthValue(), actual.getDayOfMonth()}, expected), true);
                    equal(JalaliCalendar.year(actual), year);
                }
            }
        }
        // Independently expected modern reference dates, beyond a copied-arithmetic oracle.
        equal(JalaliCalendar.toGregorian(1403, 1, 1), LocalDate.of(2024, 3, 20));
        equal(JalaliCalendar.toGregorian(1403, 12, 30), LocalDate.of(2025, 3, 20));
        equal(JalaliCalendar.toGregorian(1404, 1, 1), LocalDate.of(2025, 3, 21));
        equal(JalaliCalendar.toGregorian(1405, 6, 30), LocalDate.of(2026, 9, 21));
        invalid(() -> JalaliCalendar.toGregorian(1404, 12, 30));
        invalid(() -> JalaliCalendar.toGregorian(1405, 7, 31));
        invalid(() -> JalaliCalendar.toGregorian(0, 1, 1));
        invalid(() -> JalaliCalendar.toGregorian(3178, 1, 1));
        invalid(() -> JalaliCalendar.toGregorian(1405, 0, 1));
        invalid(() -> JalaliCalendar.toGregorian(1405, 13, 1));
        invalid(() -> JalaliCalendar.year(LocalDate.MIN));
        invalid(() -> JalaliCalendar.year(LocalDate.MAX));
        System.out.println("JalaliParityTest: " + checks + " checks passed");
    }

    private static Method accessible(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid date"); }
        catch (DateTimeException expected) { checks++; }
    }
    private static void equal(Object actual, Object expected) {
        checks++;
        if (!actual.equals(expected)) throw new AssertionError("Calendar check " + checks + ": " + actual + " != " + expected);
    }
}
