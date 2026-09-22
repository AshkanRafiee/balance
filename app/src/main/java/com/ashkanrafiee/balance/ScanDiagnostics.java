package com.ashkanrafiee.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Classifies an SMS-inbox snapshot into what Balance could fully parse and what it could not, for
 *  the Scan diagnostics screen. A message has three fates, decided exactly the way {@link
 *  BalanceData#scanSms} decides them (sender resolved by {@link BankRules#resolve}, content by
 *  {@link BalanceData#extract}):
 *  <ul>
 *    <li><b>parsed</b> — a recognized bank-sender whose message text yielded a balance; these feed
 *        the per-bank "recognized" counts only.</li>
 *    <li><b>known sender, unparsed content</b> — the sender matches a supported bank but the message
 *        layout did not parse. These are undetected structures from a bank we already know, and they
 *        count as format gaps exactly like an unknown sender.</li>
 *    <li><b>unknown sender</b> — the sender resolves to no bank at all.</li>
 *  </ul>
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
    static final String ISSUE_ACCOUNT = "Account detection";
    static final String ISSUE_BALANCE = "Balance detection";
    static final String ISSUE_NUMBER = "Sender number detection";

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

    /** One sender that needs a format contribution: either a sender Balance does not know at all
     *  ({@code bank == null}) or a known bank's sender whose message layout did not parse. */
    static final class SenderHit {
        final String sender;
        final String bank; // resolved bank name, or null when the sender is unknown
        final int messages;
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
        for (Object[] row : rows) {
            s.messages++;
            String sender = row[0] == null ? "" : (String) row[0];
            String body = row[1] == null ? "" : (String) row[1];
            long date = row.length > 2 && row[2] != null ? (Long) row[2] : 0L;
            String bank = BankRules.resolve(sender);
            if (bank == null) {
                s.unknownSendersMessages++;
                add(unknown, sender, null, body, date);
            } else if (BalanceData.extract(body) < 0) {
                s.unparsedSendersMessages++;
                add(unparsed, sender, bank, body, date);
            } else {
                s.parsedMessages++;
                int[] c = banks.get(bank);
                banks.put(bank, new int[]{c == null ? 1 : c[0] + 1});
            }
        }
        List<Map.Entry<String, int[]>> bl = new ArrayList<>(banks.entrySet());
        bl.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, int[]> e : bl) s.banks.add(new BankHit(e.getKey(), e.getValue()[0]));
        s.unknownSenders.addAll(sortedHits(unknown));
        s.unparsedSenders.addAll(sortedHits(unparsed));
        return s;
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
        for (Mutable m : l) out.add(new SenderHit(m.sender, m.bank, m.messages, m.messagesList()));
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
            out.append("```\n").append(m.body).append("\n```\n\n");
        }
        out.append("---\n").append(deviceLine()).append("\n");
        return out.toString();
    }

    /** The subject line for a per-sender report. */
    static String senderSubject(String sender) {
        return "Balance: unrecognized bank SMS sender " + sender;
    }

    private static final class Mutable {
        final String sender;
        final String bank;
        int messages;
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