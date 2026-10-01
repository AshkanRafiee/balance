package com.ashkanrafiee.balance.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The synthetic packs the encoder and store gates share.
 *
 * <p>They are documents rather than typed objects on purpose: a gate that builds a pack and reads
 * it back has to cross the same decoder a real import crosses, and a fixture written as the schema
 * is written is the only way to say anything about how the two agree. Every fixture here is a legal
 * pack, which is why the corners they reach are the corners of the grammar rather than errors.
 */
final class SyntheticPack {
    private SyntheticPack() {}

    /** A minimal valid pack: one template, one balance, and whatever the caller varies in the
     *  places a caller can vary them without the document becoming invalid. */
    static Map<String, Object> pack(List<String> senders, List<String> orders,
            Map<String, Object> currencyTokens, Map<String, String> reasonTokens) {
        Map<String, Object> currency = obj("fixed", "IRR");
        if (!currencyTokens.isEmpty()) {
            currency.put("token", field("Currency="));
            currency.put("mapping", currencyTokens);
        }
        Map<String, Object> output = obj("id", "balance", "kind", "BOOKED_BALANCE",
            "region", field("Statement"),
            "money", obj("amount", field("Balance="), "currency", currency,
                "decimal", ".", "group", "", "grouping", "NONE", "digits", "ASCII",
                "unitMultiplier", 1),
            "date", dateRule(orders), "accountOptional", true);
        // A reason map spells the token as it stands and gives it a lowercase id, which is what
        // the schema asks for and what the example pack in rules/examples does.
        if (!reasonTokens.isEmpty()) {
            Map<String, Object> reason = normalized("Reason=", "TEXT");
            output.put("reason", obj("field", reason, "mapping", reasonTokens,
                "minLength", 2, "maxLength", 64, "digitFree", true));
        }
        return obj("schema", "prototype-1", "id", "synthetic.pack", "revision", "r1",
            "bank", obj("id", "synthetic.bank", "country", "IR", "name", "Synthetic Bank",
                "provenance", "COMMUNITY"),
            "templates", List.of(obj("id", "statement", "senders", senders,
                "guards", List.of(obj("line", -1, "literal", "Statement", "excluded", false)),
                "outputs", List.of(output))));
    }

    /** A named pack, for a gate that needs one id, revision, provenance and anchor of its own.
     *  A null anchor keeps the minimal pack's own, which is how a caller says "nothing about the
     *  text varies" without repeating it. */
    static Map<String, Object> named(String packId, String bankId, String revision,
            String provenance, String anchor) {
        String text = anchor == null ? "Statement" : anchor;
        Map<String, Object> pack = pack(List.of("+98200036"), List.of("YMD"), Map.of(), Map.of());
        pack.put("id", packId);
        pack.put("revision", revision);
        @SuppressWarnings("unchecked")
        Map<String, Object> bank = (Map<String, Object>) pack.get("bank");
        bank.put("id", bankId);
        bank.put("provenance", provenance);
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) ((List<Object>) pack.get("templates")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) ((List<Object>) template.get("outputs")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> region = (Map<String, Object>) output.get("region");
        region.put("after", text);
        @SuppressWarnings("unchecked")
        Map<String, Object> guard = (Map<String, Object>) ((List<Object>) template.get("guards")).get(0);
        guard.put("literal", text);
        return pack;
    }

    /** The minimal pack again, built either with the members that only repeat what a reader already
     *  assumes or without them: the two must be the same document. */
    static Map<String, Object> defaults(boolean spelledOut) {
        Map<String, Object> account = field("Account=");
        Map<String, Object> money = obj("amount", field("Balance="),
            "currency", obj("fixed", "IRR"), "decimal", ".", "group", "", "grouping", "NONE",
            "digits", "ASCII", "unitMultiplier", 1);
        Map<String, Object> output = obj("id", "balance", "kind", "BOOKED_BALANCE",
            "region", field("Statement"), "account", account, "money", money,
            "date", dateRule(List.of("YMD")));
        if (spelledOut) {
            account.put("normalization", "NONE");
            account.put("terminated", false);
            money.put("sign", "LEADING");
            money.put("leadingPoint", false);
            output.put("accountOptional", false);
        }
        return obj("schema", "prototype-1", "id", "synthetic.defaults", "revision", "r1",
            "bank", obj("id", "synthetic.bank", "country", "IR", "name", "Synthetic Bank",
                "provenance", "COMMUNITY"),
            "templates", List.of(obj("id", "statement", "senders", List.of("+98AAA"),
                "guards", List.of(obj("line", 0, "literal", "Statement", "excluded", false)),
                "outputs", List.of(output))));
    }

    /** A pack that uses every optional member the schema has: a numeric shape, a transformed and
     *  line-terminated account, an original amount, a direction, a reason and a channel map, and an
     *  explicit date with the full option set. */
    static Map<String, Object> full() {
        // A typed field, which is where a numeric shape and a line selector are allowed: the
        // account is read under ACCOUNT normalization, ends at the line's end, and its digits are
        // selected by an exact width. A financial amount stays raw, so a numeric member there
        // would not be a legal pack.
        Map<String, Object> account = field(0, "Account=");
        account.put("normalization", "ACCOUNT");
        account.put("terminated", true);
        account.put("numeric", obj("segments", List.of(obj("min", 1, "max", 12)), "mode", "EXACT"));
        Map<String, Object> money = obj("amount", field("Debited="),
            "currency", obj("fixed", "IRR"), "decimal", ".", "group", ",", "grouping", "WESTERN",
            "digits", "ASCII_PERSIAN_ARABIC", "unitMultiplier", 1, "sign", "TRAILING",
            "leadingPoint", true);
        Map<String, Object> original = obj("amount", field("Original="),
            "currency", obj("fixed", "EUR"), "decimal", ",", "group", " ", "grouping", "WESTERN",
            "digits", "ASCII", "unitMultiplier", 1);
        Map<String, Object> direction = obj("fixed", "DEBIT", "token", field("Direction="),
            "mapping", obj("DR", "DEBIT", "CR", "CREDIT"));
        // NEIGHBOR-year dates are month/day only, which is what the grammar allows, and an
        // explicit option set is the other end of the date grammar.
        Map<String, Object> date = dateRule(List.of("MD"));
        date.put("withTime", true);
        date.put("options", obj("year", "NEIGHBOR", "yearBase", 0, "variableWidth", true,
            "layout", "SEPARATED", "timeSeparator", "T", "seconds", true, "zone", "Asia/Tehran"));
        Map<String, Object> reason = obj("field", normalized("Reason=", "TEXT"),
            "mapping", obj("ATM", "cash-withdrawal", "POS", "card-payment"),
            "minLength", 2, "maxLength", 64, "digitFree", true);
        Map<String, Object> channel = obj("field", normalized("Channel=", "CHANNEL"),
            "mapping", obj("ATM", "cash"), "minLength", 1, "maxLength", 32, "digitFree", true);
        return obj("schema", "prototype-1", "id", "synthetic.full", "revision", "r1",
            "bank", obj("id", "synthetic.bank", "country", "IR", "name", "Synthetic Bank",
                "provenance", "OFFICIAL"),
            "templates", List.of(obj("id", "statement", "senders", List.of("+98AAA"),
                "guards", List.of(obj("line", -1, "literal", "Statement", "excluded", false)),
                "outputs", List.of(obj("id", "movement", "kind", "POSTED_MOVEMENT",
                    "region", field("Statement"), "account", account, "money", money,
                    "direction", direction, "originalAmount", original, "date", date,
                    "accountOptional", true, "reason", reason, "channel", channel)))));
    }

    static Map<String, Object> currencyMapping() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("USD", "USD");
        mapping.put("IRR", "IRR");
        return mapping;
    }

    static Map<String, Object> reversedCurrencyMapping() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("IRR", "IRR");
        mapping.put("USD", "USD");
        return mapping;
    }

    static Map<String, Object> dateRule(List<String> orders) {
        return obj("calendar", "GREGORIAN", "field", field("Date="), "orders", orders,
            "separator", "/", "withTime", false, "digits", "ASCII",
            "maxPastSeconds", 31_622_400L, "maxFutureSeconds", 172_800L);
    }

    static Map<String, Object> field(String after) {
        return obj("line", -1, "after", after, "before", ";", "maxLength", 256);
    }

    static Map<String, Object> field(int line, String after) {
        return obj("line", line, "after", after, "before", ";", "maxLength", 256);
    }

    /** A field read under a normalization, which is what the text rules ask for. */
    static Map<String, Object> normalized(String after, String normalization) {
        Map<String, Object> field = field(after);
        field.put("normalization", normalization);
        return field;
    }

    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}