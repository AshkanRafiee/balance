package com.ashkanrafiee.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Classifies an SMS-inbox snapshot into what Balance read from it and what it could not, for the
 *  Scan diagnostics screen. A message has three fates, decided the way {@link BalanceData#scanSms}
 *  decides them: the same {@link MessageFacts#of} reduction, over the same clamped arrival, decides
 *  both the sender and the content.
 *  <ul>
 *    <li><b>read</b> — a recognized bank sender whose message yielded a balance or a movement; these
 *        feed the per-bank "recognized" counts only.</li>
 *    <li><b>known sender, nothing read</b> — the sender belongs to a supported bank but the message
 *        said nothing the app models. These are undetected structures from a bank we already know,
 *        and they count as format gaps exactly like an unknown sender.</li>
 *    <li><b>unknown sender</b> — the sender resolves to no bank at all.</li>
 *    <li><b>bank turned off</b> — the sender belongs to a bank the reader chose not to read. Those
 *        messages are counted and shown as one line, never listed as a gap: a format the reader
 *        declined is not one Balance failed to read.</li>
 *  </ul>
 *  Reading a message is not a claim that all of it was understood: a pack may read the amount of a
 *  card statement and leave the merchant and the time unmodelled, and the app cannot tell that
 *  apart from a message it read whole. So nothing here decides what is worth reporting — a sender
 *  this screen leaves out is still reachable through the sender picker, and a report carries the
 *  message text for a human to judge.
 *  <p>
 *  The last two groups are the contribution funnel: the user picks which senders to include and
 *  copies or emails a report of the exact texts. Nothing is persisted — a summary is assembled on
 *  demand and leaves the device only through the user's own copy/send actions. */
final class ScanDiagnostics {
    private ScanDiagnostics() { }

    /** Newest messages kept per skipped sender (bounds memory on huge inboxes). */
    static final int MAX_SAMPLES_PER_SENDER = 25;

    /** The report-issue categories a user can flag about a sender, embedded in the prefilled email
     *  template header. Kept language-neutral English so the maintainer-side report reads the same
     *  regardless of the reporter's interface language. */
    static final String ISSUE_BALANCE = "Balance detection";
    static final String ISSUE_MOVEMENT = "Movement detection";
    static final String ISSUE_ACCOUNT = "Account detection";
    static final String ISSUE_NUMBER = "Sender number detection";
    static final String ISSUE_DATE = "Date detection";
    static final String ISSUE_DESCRIPTION = "Payee description detection";
    static final String ISSUE_CHANNEL = "Channel detection";

    /** One thing the user believes the app failed to detect. */
    static final class Issue {
        /** Language-neutral English, copied into the report verbatim. */
        final String tag;
        /** The chooser's label, in the reader's own language. */
        final int label;

        Issue(String tag, int label) { this.tag = tag; this.label = label; }
    }

    /** Every detection the app performs and can therefore get wrong, so a user can say which one.
     *  One list, ordered as the chooser shows it: the amounts a reader watches first, then the
     *  numbers that identify the account, then the two transaction captions, then the sender itself.
     *  The chooser renders this and maps the ticked rows back through it, so a category can never be
     *  offered without a tag to report it under. */
    static final Issue[] ISSUES = {
        new Issue(ISSUE_BALANCE, R.string.sender_share_issue_balance),
        new Issue(ISSUE_MOVEMENT, R.string.sender_share_issue_movement),
        new Issue(ISSUE_ACCOUNT, R.string.sender_share_issue_account),
        new Issue(ISSUE_DATE, R.string.sender_share_issue_date),
        new Issue(ISSUE_DESCRIPTION, R.string.sender_share_issue_description),
        new Issue(ISSUE_CHANNEL, R.string.sender_share_issue_channel),
        new Issue(ISSUE_NUMBER, R.string.sender_share_issue_number),
    };

    /** One recognized bank and how many of its SMS messages the scan parsed. */
    static final class BankHit {
        final String bank;
        final int messages;
        BankHit(String bank, int messages) { this.bank = bank; this.messages = messages; }
    }

    /** One skipped sender's stored SMS, newest first, so the user can choose which to share. */
    static final class Message {
        final String body;
        final long date;
        Message(String body, long date) { this.body = body; this.date = date; }
    }

    /** One sender with a format contribution to make: a sender Balance does not know at all
     *  ({@code bank == null}), a known bank's sender whose message layout did not parse, or -- from
     *  {@link #inboxSenders} -- any sender the user chose to report from. */
    static final class SenderHit {
        final String sender;
        /** Resolved bank name, or null when the sender is unknown. */
        String bank;
        final int messages;
        /** How many of this sender's messages the app reads. Zero for every sender that reached the
         *  funnel, which is exactly why it cannot tell the user whether a read message was read
         *  whole; the picker carries the count so the choice to report stays the user's. */
        int read;
        final List<Message> stored;
        SenderHit(String sender, String bank, int messages, List<Message> stored) {
            this.sender = sender;
            this.bank = bank;
            this.messages = messages;
            this.stored = stored;
        }
    }

    static final class Summary {
        int messages, parsedMessages;
        int unparsedMessages() { return unknownSendersMessages + unparsedSendersMessages; }
        int unknownSendersMessages, unparsedSendersMessages;
        /** Messages from a bank the user turned off. Counted separately and never listed as a gap:
         *  a format the reader chose not to read is not a format Balance failed to read, and calling
         *  it a gap would ask them to report on a decision they just made. */
        int turnedOffMessages;
        final List<BankHit> banks = new ArrayList<>();
        final List<SenderHit> unknownSenders = new ArrayList<>();
        final List<SenderHit> unparsedSenders = new ArrayList<>();
    }

    /** Splits inbox rows ({sender, body, date}) into parsed messages, known-bank senders with
     *  unparsed content, and wholly unknown senders — counting messages and keeping, for every
     *  problem sender, its newest messages (newest first, capped). All lists come back sorted by
     *  message count, highest first. */
    static Summary analyze(List<Object[]> rows) {
        Summary s = new Summary();
        Map<String, int[]> banks = new LinkedHashMap<>();
        Map<String, Mutable> unknown = new LinkedHashMap<>();
        Map<String, Mutable> unparsed = new LinkedHashMap<>();
        // The same clamp the scan applies: an inbox timestamp ahead of the clock is not evidence of
        // an arrival in the future, and a date the packs resolve against must not see one.
        long now = System.currentTimeMillis();
        for (Object[] row : rows) {
            s.messages++;
            String sender = row[0] == null ? "" : (String) row[0];
            String body = row[1] == null ? "" : (String) row[1];
            long date = row.length > 2 && row[2] != null ? (Long) row[2] : 0L;
            // One reduction decides the sender and the content, so this screen can never claim a
            // message the scan would have read -- or the reverse. Going through the legacy tables
            // alone would have called every bank a community pack covers an unknown sender, because
            // the legacy sender table has only ever known Iranian banks.
            MessageFacts facts = MessageFacts.of(sender, body, Math.min(date, now));
            String bank = facts == null ? null : facts.bank;
            boolean read = bank != null && (facts.balance >= 0 || facts.movement != null);
            if (read) {
                s.parsedMessages++;
                int[] c = banks.get(bank);
                banks.put(bank, new int[]{c == null ? 1 : c[0] + 1});
                continue;
            }
            // Nothing was read here, which is not the same as a sender the app has never heard of:
            // a pack can cover the sender and simply not match this wording.
            if (bank == null) bank = senderBank(sender);
            if (bank == null) {
                s.unknownSendersMessages++;
                add(unknown, sender, null, body, date);
            } else if (!RecognitionHelper.isEnabled(bank)) {
                // The reader asked not to read this bank, so this message is not evidence of
                // anything the app got wrong. Naming the bank and leaving it out of both lists keeps
                // the screen honest in both directions: not "unknown sender" either.
                s.turnedOffMessages++;
            } else {
                s.unparsedSendersMessages++;
                add(unparsed, sender, bank, body, date);
            }
        }
        List<Map.Entry<String, int[]>> bl = new ArrayList<>(banks.entrySet());
        bl.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, int[]> e : bl) s.banks.add(new BankHit(e.getKey(), e.getValue()[0]));
        s.unknownSenders.addAll(sortedHits(unknown));
        s.unparsedSenders.addAll(sortedHits(unparsed));
        return s;
    }

    /** Every sender the inbox holds, with the bank Balance attributes it to (null when none), how
     *  many of its messages the app reads, and its newest messages -- the sender picker's data,
     *  ordered by message count. Unlike {@link #analyze} this keeps the senders whose messages are
     *  read fine, because reading a message is not a claim that all of it was understood: a user who
     *  wants to report the merchant name the app ignores must still be able to reach the sender.
     *  Nothing is filtered out here, so the caller owns what it shows. */
    static List<SenderHit> inboxSenders(List<Object[]> rows) {
        Map<String, Mutable> all = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Object[] row : rows) {
            String sender = row[0] == null ? "" : (String) row[0];
            String body = row[1] == null ? "" : (String) row[1];
            long date = row.length > 2 && row[2] != null ? (Long) row[2] : 0L;
            MessageFacts facts = MessageFacts.of(sender, body, Math.min(date, now));
            String bank = facts == null ? null : facts.bank;
            boolean read = bank != null && (facts.balance >= 0 || facts.movement != null);
            Mutable m = all.get(sender);
            if (m == null) all.put(sender, m = new Mutable(sender, read ? bank : senderBank(sender)));
            // A sender is one bank or none; an earlier message that named no bank must not hide the
            // one a later message did.
            else if (m.bank == null) m.bank = bank != null ? bank : senderBank(sender);
            m.messages++;
            if (read) m.read++;
            m.add(body, date);
        }
        return sortedHits(all);
    }

    /** The bank a sender belongs to whether or not a message from it says anything the app models:
     *  a loaded pack that covers the sender names it, and the legacy table answers for the banks
     *  that shipped before packs. Deciding that a sender is unknown from the one message that did not
     *  parse would call a supported bank's advertisement an unrecognized sender -- the same false
     *  claim, one message further on. */
    private static String senderBank(String sender) {
        EngineRules engine = EngineRules.get();
        if (engine != null && engine.active()) {
            EngineRules.Bank packed = engine.covers(sender);
            if (packed != null) return packed.name;
        }
        return BankRules.resolve(sender);
    }

    /** Every sender the user may include in a report, known-bank senders first (they are often the
     *  surprising ones), each bucket sorted by message count. */
    static List<SenderHit> problemSenders(Summary s) {
        List<SenderHit> all = new ArrayList<>(s.unparsedSenders);
        all.addAll(s.unknownSenders);
        return all;
    }

    private static void add(Map<String, Mutable> into, String sender, String bank, String body, long date) {
        Mutable m = into.get(sender);
        if (m == null) into.put(sender, m = new Mutable(sender, bank));
        m.messages++;
        m.add(body, date);
    }

    private static List<SenderHit> sortedHits(Map<String, Mutable> map) {
        List<Mutable> l = new ArrayList<>(map.values());
        l.sort((a, b) -> Integer.compare(b.messages, a.messages));
        List<SenderHit> out = new ArrayList<>();
        for (Mutable m : l) {
            SenderHit hit = new SenderHit(m.sender, m.bank, m.messages, m.messagesList());
            hit.read = m.read;
            out.add(hit);
        }
        return out;
    }

    /** A ready-to-paste report body for exactly the chosen senders (the originals, newest sample
     *  each, so the user sees beforehand what leaves the device). Each entry names the bank when
     *  one is known. Null-safe: an empty selection composes a header with no entries. */
    static String reportText(List<SenderHit> selected) {
        StringBuilder out = new StringBuilder();
        out.append("## Bank SMS formats Balance could not parse\n\n")
            .append("Please add these formats. Each entry names the sender and a sample message exactly\n")
            .append("as the bank sent it; numbers can be scrambled as long as the digit count and\n")
            .append("layout stay the same.\n\n");
        for (SenderHit h : selected) {
            out.append("### ");
            if (h.bank != null) out.append("Bank: ").append(h.bank).append(" — ");
            out.append("Sender `").append(h.sender).append("` — ").append(h.messages).append(
                h.messages == 1 ? " message" : " messages").append("\n\n```\n");
            if (!h.stored.isEmpty()) out.append(h.stored.get(0).body);
            out.append("\n```\n\n");
        }
        out.append("---\n").append(deviceLine()).append("\n");
        return out.toString();
    }

    /** Device, Android-version, build and ROM facts embedded in every report footer, so the
     *  maintainer can reproduce the message layout without asking. Everything is read from the
     *  platform at report time; a field the system does not expose is simply omitted. */
    private static String deviceLine() {
        StringBuilder out = new StringBuilder();
        String make = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.trim();
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
        String device = (make + " " + model).trim();
        if (device.isEmpty()) device = "Unknown device";
        out.append("Device: ").append(device).append(" \u00b7 Android ")
            .append(android.os.Build.VERSION.RELEASE).append(" (API ")
            .append(android.os.Build.VERSION.SDK_INT).append(")");
        String display = android.os.Build.DISPLAY == null ? "" : android.os.Build.DISPLAY.trim();
        if (!display.isEmpty()) out.append(" \u00b7 build ").append(display);
        String rom = romLine();
        if (rom != null) out.append('\n').append("ROM: ").append(rom);
        return out.toString();
    }

    private static final String[][] ROM_PROPS = {
        {"ro.mi.os.version.name", "HyperOS"},
        {"ro.miui.ui.version.name", "MIUI"},
        {"ro.build.version.emui", "EMUI/HarmonyOS"},
        {"ro.build.version.oneui", "One UI"},
        {"ro.vendor.build.version.sem_oneui", "One UI"},
        {"ro.oxygen.version", "OxygenOS"},
        {"ro.build.version.oplusrom", "ColorOS"},
        {"ro.build.version.coloros", "ColorOS"},
        {"ro.build.version.realmeui", "Realme UI"},
        {"ro.xos.version", "XOS"},
        {"ro.hios.version", "HiOS"},
        {"ro.vivo.os.build.display.id", "FunTouch OS"},
        {"ro.lineage.version", "LineageOS"},
        {"ro.crDroid.version", "crDroid"},
        {"ro.evolution.version", "Evolution X"},
        {"ro.havoc.version", "HavocOS"},
        {"ro.dotos.version", "dotOS"},
        {"ro.modversion", "custom"},
    };

    /** The OEM/custom-ROM name and version when the firmware exposes it through one of the
     *  well-known {@code ro.*} properties; otherwise a best-effort stock guess from the build
     *  fingerprint, or null when nothing can be told (the build id line above already pins the
     *  exact firmware then). */
    private static String romLine() {
        for (String[] p : ROM_PROPS) {
            String v = sysProp(p[0]);
            if (v != null && !v.trim().isEmpty()) {
                String val = v.trim();
                if (val.length() > 48) val = val.substring(0, 48);
                return p[1] + " " + val;
            }
        }
        String fp = android.os.Build.FINGERPRINT == null ? "" : android.os.Build.FINGERPRINT;
        if (fp.startsWith("google/")) return "Google stock firmware";
        return null;
    }

    /** Reads one Android {@code ro.*} system property by reflection; the class is hidden from the
     *  SDK but the read stays usable for apps, and any future restriction degrades to null. */
    private static String sysProp(String key) {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The report body for one sender with a chosen subset of its messages (the originals, so the
     *  user sees beforehand exactly what leaves the device). {@code total} is the sender's message
     *  count; an empty selection composes a header with no messages. */
    static String senderReport(String sender, int total, List<Message> selected) {
        return senderReport(sender, total, selected, java.util.Collections.emptyList());
    }

    /** Like {@link #senderReport(String, int, List)} with the user-picked issue categories listed
     *  right below the title, so the maintainer knows which stage of detection failed. The list uses
     *  the {@code ISSUE_*} constants; unselected categories stay out of the report. */
    static String senderReport(String sender, int total, List<Message> selected,
            List<String> issues) {
        StringBuilder out = new StringBuilder();
        out.append("## Bank SMS format Balance could not parse\n\n");
        if (!issues.isEmpty()) {
            out.append("Issue type(s): ").append(String.join(", ", issues)).append("\n\n");
        }
        out.append("Sender `").append(sender).append("` — ").append(total).append(
            total == 1 ? " message in total" : " messages in total").append(".\n")
            .append("Sample message(s) exactly as the bank sent them:\n\n");
        for (Message m : selected) {
            out.append("```\n").append(m.body).append("\n```\n\n")
                .append(readingLine(sender, m)).append("\n\n");
        }
        out.append("---\n").append(deviceLine()).append("\n");
        return out.toString();
    }

    /** What Balance currently reads out of one shared message, stated as a reading and not as a
     *  verdict: the user chose this message because some part of it was wrong or missing, and the
     *  app is in no position to know which part. Naming the amounts, the account and the date it did
     *  take is what lets the maintainer see the gap without re-deriving it — a card statement whose
     *  amount and day are read while the merchant and the time of day are not shows up here as
     *  exactly that. Amounts are written plainly rather than in the reader's locale, because the
     *  report is read by the maintainer, not by the person who sent it. */
    private static String readingLine(String sender, Message m) {
        MessageFacts facts = MessageFacts.of(sender, m.body, Math.min(m.date, System.currentTimeMillis()));
        if (facts == null || facts.bank == null) return "Balance reads: nothing — sender not recognized.";
        java.util.List<String> parts = new ArrayList<>();
        if (facts.balance >= 0) parts.add("balance " + plain(facts.balanceCurrency, facts.balance));
        if (facts.movement != null)
            parts.add("movement " + plain(facts.movementCurrency, facts.movement));
        if (parts.isEmpty()) parts.add("nothing");
        parts.add("dated " + java.time.Instant.ofEpochMilli(facts.time)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate());
        parts.add(facts.account == null ? "no account number found" : "account " + facts.account);
        return "Balance reads: " + String.join(" \u00b7 ", parts) + ".";
    }

    /** A minor-unit amount at its currency's own scale, written the way the currency defines it and
     *  with no locale applied. */
    private static String plain(String currency, long minorUnits) {
        int scale = CurrencyHelper.scaleOf(currency);
        String amount = new java.math.BigDecimal(java.math.BigInteger.valueOf(minorUnits), scale)
            .toPlainString();
        return amount + " " + currency;
    }

    /** The subject line for a per-sender report. A sender Balance recognizes is named by its bank
     *  instead: reporting one it already reads is a format gap, not an unrecognized sender, and the
     *  subject is the first line the maintainer reads. */
    static String senderSubject(String sender, String bank) {
        if (bank == null || bank.isEmpty())
            return "Balance: unrecognized bank SMS sender " + sender;
        return "Balance: " + bank + " SMS format, sender " + sender;
    }

    static String senderSubject(String sender) {
        return senderSubject(sender, null);
    }

    private static final class Mutable {
        final String sender;
        String bank;
        int messages;
        int read;
        final List<Message> newest = new ArrayList<>();
        Mutable(String sender, String bank) { this.sender = sender; this.bank = bank; }

        // analyze() feeds rows newest-first (the inbox query is DATE DESC), so appending keeps the
        // newest messages in order and the cap just bounds memory on huge inboxes.
        void add(String body, long date) {
            if (newest.size() < MAX_SAMPLES_PER_SENDER) newest.add(new Message(body, date));
        }

        List<Message> messagesList() {
            return newest;
        }
    }
}