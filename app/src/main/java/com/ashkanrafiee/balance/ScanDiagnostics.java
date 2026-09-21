package com.ashkanrafiee.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Classifies an SMS-inbox snapshot into recognized and unrecognized senders for the Scan
 *  diagnostics screen: which SMS Balance parsed, and which senders it skipped (the newer a bank's
 *  message layout is, the more of its senders land here — exactly what a bank-format contribution
 *  needs to name and paste). Purely functional: the activity feeds in the raw rows it reads from
 *  the inbox ({sender, body, date}) and renders the result. Nothing here is persisted — a summary
 *  is assembled on demand and leaves the device only when the user copies or sends the report
 *  through one of the share/mail flows. */
final class ScanDiagnostics {
    private ScanDiagnostics() { }

    /** Newest messages kept per skipped sender (bounds memory on huge inboxes). */
    static final int MAX_SAMPLES_PER_SENDER = 25;

    /** One recognized bank and how many of its SMS messages the scan saw. */
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

    /** One skipped sender: message count plus its newest messages verbatim — the samples a
     *  contributor pastes into a report, so the layout can be reproduced even if the numbers are
     *  scrambled. */
    static final class SenderHit {
        final String sender;
        final int messages;
        final List<Message> stored;
        SenderHit(String sender, int messages, List<Message> stored) {
            this.sender = sender;
            this.messages = messages;
            this.stored = stored;
        }
    }

    static final class Summary {
        int messages, recognizedMessages, skippedMessages;
        final List<BankHit> banks = new ArrayList<>();
        final List<SenderHit> senders = new ArrayList<>();
    }

    /** Splits inbox rows into recognized banks and unrecognized senders, counting messages and
     *  keeping, for every skipped sender, its newest messages (newest first, capped). Both lists
     *  come back sorted by message count, highest first. */
    static Summary analyze(List<Object[]> rows) {
        Summary s = new Summary();
        Map<String, int[]> banks = new LinkedHashMap<>();
        Map<String, Mutable> skipped = new LinkedHashMap<>();
        for (Object[] row : rows) {
            s.messages++;
            String sender = row[0] == null ? "" : (String) row[0];
            String body = row[1] == null ? "" : (String) row[1];
            long date = row.length > 2 && row[2] != null ? (Long) row[2] : 0L;
            String bank = BankRules.resolve(sender);
            if (bank == null) {
                s.skippedMessages++;
                Mutable m = skipped.get(sender);
                if (m == null) skipped.put(sender, m = new Mutable(sender));
                m.messages++;
                m.add(body, date);
            } else {
                s.recognizedMessages++;
                int[] c = banks.get(bank);
                banks.put(bank, new int[]{c == null ? 1 : c[0] + 1});
            }
        }
        List<Map.Entry<String, int[]>> bl = new ArrayList<>(banks.entrySet());
        bl.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, int[]> e : bl) s.banks.add(new BankHit(e.getKey(), e.getValue()[0]));
        List<Mutable> sl = new ArrayList<>(skipped.values());
        sl.sort((a, b) -> Integer.compare(b.messages, a.messages));
        for (Mutable m : sl) s.senders.add(new SenderHit(m.sender, m.messages, m.messagesList()));
        return s;
    }

    /** A ready-to-paste report body for the unrecognized senders, or null when every sender is
     *  recognized (nothing to report). Uses each skipped sender's newest message as its sample.
     *  Mirrors what CONTRIBUTING asks for: each entry names the sender and a sample message exactly
     *  as the bank sent it. */
    static String reportText(Summary s) {
        if (s == null || s.senders.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        out.append("## SMS senders Balance does not recognize yet\n\n")
            .append("Please add the message format behind these sender numbers. Each entry lists the\n")
            .append("sender and a sample message exactly as the bank sent it; numbers can be scrambled\n")
            .append("as long as the digit count and layout stay the same.\n\n");
        for (SenderHit h : s.senders) {
            out.append("### Sender `").append(h.sender).append("` — ").append(h.messages).append(
                h.messages == 1 ? " message" : " messages").append("\n\n```\n");
            if (!h.stored.isEmpty()) out.append(h.stored.get(0).body);
            out.append("\n```\n\n");
        }
        out.append("---\nDevice model and Android version help reproduce the format.");
        return out.toString();
    }

    /** The report body for one sender with a chosen subset of its messages (the originals, so the
     *  user sees beforehand exactly what leaves the device). {@code total} is the sender's message
     *  count; an empty selection composes a header with no messages. */
    static String senderReport(String sender, int total, List<Message> selected) {
        StringBuilder out = new StringBuilder();
        out.append("## SMS sender Balance does not recognize yet\n\n")
            .append("Sender `").append(sender).append("` — ").append(total).append(
                total == 1 ? " message in total" : " messages in total").append(".\n")
            .append("Sample message(s) exactly as the bank sent them:\n\n");
        for (Message m : selected) {
            out.append("```\n").append(m.body).append("\n```\n\n");
        }
        out.append("---\nDevice model and Android version help reproduce the format.");
        return out.toString();
    }

    /** The subject line for a per-sender report. */
    static String senderSubject(String sender) {
        return "Balance: unrecognized SMS sender " + sender;
    }

    private static final class Mutable {
        final String sender;
        int messages;
        final List<Message> newest = new ArrayList<>();
        Mutable(String sender) { this.sender = sender; }

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