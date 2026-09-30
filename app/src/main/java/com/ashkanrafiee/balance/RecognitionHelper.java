package com.ashkanrafiee.balance;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Which banks Balance reads messages from.
 *
 *  <p>Every shipped bank is recognized and read until the user says otherwise. A reader whose bank
 *  sends a layout the app has never seen, or who simply does not want a sender read at all, can turn
 *  that bank off here; the control exists per bank because recognizing everything is not a virtue
 *  for someone who banks elsewhere.
 *
 *  <p>The choice is stored by <b>canonical bank name</b>, which is the app's own stable identity for
 *  a bank: it keys the dashboard, the history, the exclusions and every stored row, so it is what
 *  survives a data reset and what a backup restores. A pack's catalog id belongs to the rules
 *  catalog instead, which is republished as regions and revisions are added, and a bank that lost
 *  its pack would lose its id with it while its stored history stayed.
 *
 *  <p>Turning a bank off stops Balance <b>reading</b> its messages. It deletes nothing: balances,
 *  history, notes and exclusions already stored for that bank stay exactly as they are, and turn it
 *  back on and the next scan picks up where the inbox left off. That is why this is a preference
 *  rather than a data operation, and why a reader who turns a bank off is not losing their history.
 *
 *  <p>The decision is enforced in one place — {@link MessageFacts}, the single per-message reduction
 *  every scan and history path goes through — so it holds for a bank read by a bundled pack and for
 *  one read by the legacy tables alike, and no caller can forget to honour it.
 */
final class RecognitionHelper {

    /** Package-private so a test can clear the choice the way a fresh install starts: as a
     *  preference, not as financial state. */
    static final String PREFS = "balance_recognition";
    static final String KEY_DISABLED = "disabled_banks";
    private static final String KEY_REVISION = "revision";

    /** The disabled set every reader consults, kept in memory because a scan asks about a bank for
     *  every message it reads. A snapshot rather than a live preference read: correctness comes from
     *  {@link #setEnabled} and {@link #refresh} both writing here, and reading a preference file
     *  per message would cost more than the answer is worth. */
    private static volatile Set<String> disabled = Collections.emptySet();
    /** How many times the choice has changed on this device, and what the scan remembers it against. */
    private static volatile int revision;

    private RecognitionHelper() { }

    /** Reloads the snapshot from storage. Called wherever a scan begins, so a choice made in another
     *  screen — or restored from a backup in another process — is in force before the first message
     *  is read, and the same way whether the app was opened, refreshed or woken for the widget. */
    static void refresh(Context context) {
        SharedPreferences prefs = stored(context);
        disabled = new HashSet<>(prefs.getStringSet(KEY_DISABLED, Collections.emptySet()));
        revision = prefs.getInt(KEY_REVISION, 0);
    }

    /** Whether Balance reads messages from {@code bank}. A null bank is not a bank's message and is
     *  never the reader's choice to make, so it is always read. */
    static boolean isEnabled(String bank) {
        return bank == null || !disabled.contains(bank);
    }

    /** The banks the user has turned off, for the settings screen to draw. */
    static Set<String> disabledBanks(Context context) {
        return stored(context).getStringSet(KEY_DISABLED, Collections.emptySet());
    }

    /** How many times this device's choice has changed. The scan remembers the number it read the
     *  inbox under and re-reads the inbox when the number has moved on.
     *
     *  <p>A count rather than the choice itself, because the choice alone cannot answer the
     *  question: turning one bank off and back on returns the choice to exactly what it was, while
     *  the inbox has meanwhile gained a pass that skipped that bank's messages. Comparing the choice
     *  would call that "no change" and leave the reader looking at a bank whose balance was never
     *  derived. Counting changes cannot be fooled that way, and it is also the right thing to set
     *  against a watermark, which only ever means something for the pass that produced it.
     *
     *  <p>A fresh install is revision 0 and a device that has never opened this screen never leaves
     *  it: nothing to re-read, and nothing to force. */
    static int revision() {
        return revision;
    }

    /** Turns one bank on or off and returns the choice in force afterwards, so a caller that redraws
     *  from this value cannot disagree with what was stored. */
    static boolean setEnabled(Context context, String bank, boolean on) {
        SharedPreferences prefs = stored(context);
        Set<String> next = new LinkedHashSet<>(prefs
            .getStringSet(KEY_DISABLED, Collections.emptySet()));
        if (on) next.remove(bank);
        else next.add(bank);
        int moved = prefs.getInt(KEY_REVISION, 0) + 1;
        prefs.edit().putStringSet(KEY_DISABLED, next).putInt(KEY_REVISION, moved).apply();
        // The snapshot is updated here rather than left to the next refresh, because the user is
        // looking at a screen that says the choice took effect: a scan started a moment later must
        // honour it even though nothing has restarted the process.
        disabled = next;
        revision = moved;
        return on;
    }

    private static SharedPreferences stored(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
