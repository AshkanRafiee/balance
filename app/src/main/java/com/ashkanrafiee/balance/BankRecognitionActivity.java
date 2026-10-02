package com.ashkanrafiee.balance;

import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.ashkanrafiee.balance.parser.PackDocument;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Which banks Balance reads, grouped by where their formats came from, with a switch each.
 *
 *  <p>Every shipped bank is on until the reader says otherwise, and turning one off stops Balance
 *  reading its messages without touching a single stored balance, note or exclusion — this screen
 *  writes one preference and reads no financial data at all. The grouping is where the reader can
 *  check where a format came from: a bank whose messages the app was shipped understanding sits
 *  apart from one a user report got added, which is the difference between "this was always here"
 *  and "this was written from a message someone pasted in".
 *
 *  <p>There is no search field here and no verdict per bank: with tens of banks, what a reader wants
 *  is the one switch next to the name they recognize, and turning it off is a decision they make
 *  from the bank's own name, not from a count.
 */
public final class BankRecognitionActivity extends ThemedScreenActivity {

    @Override String title() {
        return getString(R.string.recognition_title);
    }

    /** The groups, in the order the index registers them, so a region cannot move between opens and
     *  a reader learns one layout instead of a new one each time. */
    @Override
    void render() {
        TextView note = text(getString(R.string.recognition_note), 12, muted);
        note.setLineSpacing(2, 1.05f);
        body.addView(note, margin(2, 0, 2, 10));

        EngineRules engine;
        try {
            engine = EngineRules.load(this);
        } catch (IOException unavailable) {
            engine = null;
        }
        // Every bank the app reads, not only the shipped ones: a pack the reader brought in is read
        // like any other, and a switch they cannot reach would be a bank they cannot turn off.
        List<EngineRules.Bank> banks = engine == null ? Collections.emptyList()
                : engine.allBanksInOrder();
        if (banks.isEmpty()) {
            // The packs are assets of the app itself, so an empty list means the app is running
            // without them. Say so rather than drawing an empty screen that reads as "no banks".
            body.addView(text(getString(R.string.recognition_unavailable), 14, muted),
                margin(2, dp(18), 2, 0));
            return;
        }
        String region = null;
        for (EngineRules.Bank bank : banks) {
            String group = bank.region == null ? LOCAL_REGION : bank.region;
            if (!group.equals(region)) {
                region = group;
                sectionLabel(regionLabel(group, banks), 2, dp(16), 2, 0);
            }
            bankRow(bank);
        }
        packsEntry();
    }

    /** The way to the reader's own packs. It lives at the end of this screen rather than in its own
     *  place in the settings, because a reader who has packs of their own came here to see them, and
     *  a reader who has none scrolls past one row.
     *
     *  <p>It is hidden until the reader opens the gate in the About screen. Importing your own rules
     *  changes how every message from that bank is read, so it is offered to people who asked for it
     *  rather than shown to everyone — see {@link Experimental}.</p> */
    private void packsEntry() {
        if (!Experimental.ownPacksEnabled(this)) return;
        LinearLayout row = cardRow();
        row.addView(text(getString(R.string.local_packs_title), 15, accent),
            new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> startActivity(new android.content.Intent(this, LocalPacksActivity.class)));
        body.addView(row, margin(0, dp(22), 0, dp(20)));
    }

    /** Where the reader's own packs are grouped. Not a country and not a region: these rules came
     *  from this device, which is the only honest thing to say about where they came from. */
    private static final String LOCAL_REGION = "\u0000local";

    /** A group's heading: its country, and what kind of evidence its formats rest on. The two are
     *  separate because a reader's question is "where did this come from", and the honest answer can
     *  be "from a country, and from people who pasted their messages in" at the same time. The whole
     *  heading is one localized format string, so a language that puts the qualifier first is free to
     *  do so. */
    private String regionLabel(String region, List<EngineRules.Bank> banks) {
        boolean community = false;
        String country = null;
        for (EngineRules.Bank bank : banks) {
            if (!region.equals(bank.region)) continue;
            community |= bank.provenance == PackDocument.Bank.Provenance.COMMUNITY;
            if (country == null && !bank.country.isEmpty()) country = bank.country;
        }
        if (LOCAL_REGION.equals(region)) return getString(R.string.recognition_group_local);
        String where = country == null ? "" : countryLabel(country);
        return getString(community
            ? R.string.recognition_group_community : R.string.recognition_group_official, where);
    }

    /** A country in the reader's own language where the platform knows the name, and its code where it
     *  does not, because an unfamiliar code is still more honest than a guessed country name. */
    private String countryLabel(String code) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String name;
        try {
            name = new Locale("", code).getDisplayCountry(locale);
        } catch (RuntimeException unknown) {
            name = null;
        }
        return name == null || name.isEmpty() ? code : name;
    }

    /** One bank: its name and a switch. Tapping anywhere on the row flips it, the way the message and
     *  issue rows on the report screens do, so a long bank name does not have to be aimed at. */
    private void bankRow(EngineRules.Bank bank) {
        boolean on = RecognitionHelper.isEnabled(bank.name);
        LinearLayout line = cardRow();
        line.setPadding(dp(14), dp(4), dp(10), dp(4));
        body.addView(line, margin(0, 6, 0, 0));

        TextView name = text(BankRules.displayName(this, bank.name), 14, on ? fg : muted);
        name.setMaxLines(2);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        Switch toggle = new Switch(this);
        toggle.setChecked(on);
        // The row is the tap target and the switch draws the state; letting the switch take the tap
        // too would fire the row's listener a second time on some devices.
        toggle.setClickable(false);
        line.addView(toggle);

        String what = getString(on
            ? R.string.recognition_on : R.string.recognition_off, BankRules.displayName(this, bank.name));
        line.setContentDescription(what);
        line.setOnClickListener(v -> {
            // The choice is read from the store at the moment of the tap, never from the value this
            // row happened to be drawn with: the closure outlives the redraw, so a captured flag
            // would compute the same answer twice and leave a bank the reader turned off stuck off
            // until they left the screen and came back.
            boolean now = RecognitionHelper.setEnabled(this, bank.name,
                !RecognitionHelper.isEnabled(bank.name));
            // Only the one row changes, so nothing else on screen can disagree with the stored choice.
            renderRow(line, name, toggle, bank, now);
        });
    }

    private void renderRow(LinearLayout line, TextView name, Switch toggle,
            EngineRules.Bank bank, boolean on) {
        toggle.setChecked(on);
        name.setTextColor(on ? fg : muted);
        line.setContentDescription(getString(on
            ? R.string.recognition_on : R.string.recognition_off, BankRules.displayName(this, bank.name)));
    }
}