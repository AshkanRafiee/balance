package com.ashkanrafiee.balance.parser.legacy;

import com.ashkanrafiee.balance.parser.catalog.CatalogIds;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Test-only reference projection of the frozen legacy pipeline, mirroring the app's
 * BankRules/BalanceData/MessageDate sequence (resolve, calendar, eventTime, extract,
 * extractTransaction, extractAccount, extractReason, extractChannel). The official pack
 * build gate runs it as the independent legacy oracle and requires the packed parser to
 * reproduce it: a fixture is not portable until both engines agree on its projection.
 * Not shipped to the app.
 */
public final class LegacyReference {
    private LegacyReference() {}

    /**
     * Returns a plain map with keys {@code status, bank, account, reason, channel,
     * balance, txn, time, timeArrival}. {@code zone} is an IANA zone id used to build the
     * captured calendar context, replacing the device default the app would capture.
     */
    public static Map<String, Object> reference(String sender, String body, long arrivalMs, String zone) {
        Map<String, Object> out = new LinkedHashMap<>();
        String bankKey = sender == null ? null : LegacyBankRules.resolve(sender);
        if (bankKey == null) {
            out.put("status", "UNKNOWN_SENDER");
            out.put("bank", null);
            return out;
        }
        out.put("status", "PARSED");
        out.put("bank", CatalogIds.bankId("ir", bankKey));
        out.put("account", LegacyBankRules.extractAccount(bankKey, body));
        out.put("reason", LegacyBankRules.extractReason(bankKey, body));
        out.put("channel", LegacyBankRules.extractChannel(bankKey, body));
        long stated = LegacyMoney.extract(body);
        out.put("balance", stated < 0 ? null : stated);
        out.put("txn", LegacyMoney.extractTransaction(body));
        if (out.get("txn") == null && out.get("balance") == null) out.put("status", "NO_MATCH");
        LegacyCalendarSystem cal = LegacyBankRules.calendar(bankKey);
        LegacyCalendarContext context = new LegacyCalendarContext(TimeZone.getTimeZone(zone), Locale.US);
        long time = LegacyMessageDate.eventTime(body, arrivalMs, cal, context);
        out.put("time", time);
        out.put("timeArrival", time == arrivalMs);
        return out;
    }

    /** The legacy bank key (the frozen tables' own name) for a bank id, or null. */
    public static String bankKey(String bankId) {
        for (String[] row : LegacyBankRules.rulesTestOnly()) {
            if (bankId.equals(CatalogIds.bankId("ir", row[0]))) return row[0];
        }
        return null;
    }
}