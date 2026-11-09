package com.ashkanrafiee.balance;

import android.content.Context;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Explicit, exact notification amounts. No integer division, floating-point rounding or
 * magnitude conversion (Long.MIN_VALUE must remain representable). */
final class NotificationAmounts {
    private NotificationAmounts() {}

    static String format(Context context, long rials) {
        return number(CurrencyHelper.CURRENCY_TOMAN.equals(CurrencyHelper.currency(context)),
            LocaleHelper.isPersian(context), rials) + " " + CurrencyHelper.label(context);
    }

    static String number(boolean toman, boolean persian, long rials) {
        BigDecimal value = BigDecimal.valueOf(rials);
        if (toman) value = value.movePointLeft(1);
        NumberFormat format = NumberFormat.getNumberInstance(persian ? new Locale("fa") : Locale.US);
        format.setMaximumFractionDigits(toman ? 1 : 0);
        format.setMinimumFractionDigits(0);
        return format.format(value);
    }
}
