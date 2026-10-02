package com.ashkanrafiee.balance;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ashkanrafiee.balance.parser.LocalPackStore;
import com.ashkanrafiee.balance.parser.PackDocument;
import com.ashkanrafiee.balance.parser.PackWriter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The builder: paste a bank message, highlight the parts that matter, say what the rule is for,
 * test it, and then either keep it on this device or send it for review.
 *
 * <p>This is the one screen where the reader decides what the app reads, so it is built to be slow
 * rather than clever. Nothing is inferred that the reader did not say: the amount, the balance, the
 * date and the account are chosen by selecting the words in the message, because a builder that
 * guesses which digits belong together is a builder that quietly records the wrong number.
 *
 * <p>Testing happens against the rules this device already has, not against the new rule alone, so
 * a sender that is already covered is reported before anything is installed. A message the reader
 * says Balance cannot read is recorded as such and produces nothing: a rule that looks installed and
 * reads nothing is worse than an honest no.
 *
 * <p>The example stays on the device. It is held in the sealed draft store, never in saved instance
 * state, and leaves only when the reader sends it for review, on a screen that shows exactly what
 * will be sent first.
 */
public final class RuleBuilderActivity extends ThemedScreenActivity {

    /** View tags naming which role a highlight button belongs to, so the four identical "Set"
     *  buttons can be told apart by anything driving this screen. */
    static final String TAG_SET = "builder.set.";
    static final String TAG_CLEAR = "builder.clear.";
    static final String TAG_EXCLUDE = "builder.exclude";

    private RuleDraft draft;
    private RuleDraftStore store;
    private EngineRules engine;
    private EditText message;
    /** The part of the column below the highlights: what is still missing, what the last test said,
     *  and the two buttons. It is rebuilt on its own when the reader types, so the screen keeps
     *  answering the question they are in the middle of asking. */
    private LinearLayout tail;
    private final List<RuleDraftTester.Verdict> verdicts = new ArrayList<>();

    @Override
    String title() {
        return getString(R.string.builder_title);
    }

    @Override
    public void onCreate(Bundle state) {
        // The draft must exist before super renders, and it is restored from the sealed store before
        // anything is drawn so the screen opens on the rule the reader was in the middle of.
        draft = new RuleDraft();
        store = new RuleDraftStore(this);
        engine = EngineRules.activate(this);
        Intent incoming = getIntent();
        boolean opened = incoming != null && incoming.hasExtra(EXTRA_BODY);
        // Highlights are offsets into one particular message, so they mean nothing at all in a
        // different one. A stored draft is therefore only restored when this screen was opened for
        // its own message: an incoming message starts a fresh draft rather than inheriting
        // highlights that now point at whatever sits at those offsets.
        if (!opened) {
            Map<String, Object> saved = store.present() ? store.read() : null;
            if (saved != null) restore(saved);
        } else {
            draft.body = text(incoming.getStringExtra(EXTRA_BODY));
            draft.sender = text(incoming.getStringExtra(EXTRA_SENDER));
            draft.bankName = text(incoming.getStringExtra(EXTRA_BANK));
        }
        super.onCreate(state);
    }

    @Override
    protected void onDestroy() {
        // Anything still queued for the worker is dropped first, so it cannot land on top of the
        // decision below.
        if (background != null) background.removeCallbacks(sealing);
        // Leaving the builder after having saved or discarded is the only thing that forgets the
        // draft: the draft exists to survive a pause or a crash, not to keep a message the reader
        // has finished with on the device indefinitely.
        if (isFinishing()) {
            if (savedOrDiscarded) store.clear();
            else store.write(save());
        }
        // The draft is settled by now, so the thread that would have sealed it can go. Leaving it
        // running would keep this screen's message alive in the heap after the screen is gone.
        if (background != null) {
            background.removeCallbacks(sealing);
            background.getLooper().quit();
            background = null;
        }
        super.onDestroy();
    }

    /** Set once the reader has either installed or thrown the draft away. */
    private boolean savedOrDiscarded;

    static final String EXTRA_BODY = "body";
    static final String EXTRA_SENDER = "sender";
    static final String EXTRA_BANK = "bank";

    @Override
    void render() {
        sectionLabel(getString(R.string.builder_section_message), 2, 0, 2, 6);
        body.addView(messageBox(), margin(0, 0, 0, 12));

        sectionLabel(getString(R.string.builder_section_identity), 2, 0, 2, 6);
        body.addView(identityBox(), margin(0, 0, 0, 12));

        sectionLabel(getString(R.string.builder_section_type), 2, 0, 2, 6);
        body.addView(typeBox(), margin(0, 0, 0, 12));

        if (draft.shape == RuleDraft.Shape.UNSUPPORTED) {
            body.addView(note(getString(R.string.builder_unsupported_note)),
                    margin(2, 0, 2, 16));
            return;
        }
        sectionLabel(getString(R.string.builder_section_highlights), 2, 0, 2, 6);
        body.addView(highlightsBox(), margin(0, 0, 0, 12));
        // Only a movement states which way the money went. A balance-only rule writes no direction
        // at all, so asking for one would collect an answer nothing reads, and the reader would be
        // left thinking their choice had been dropped.
        if (draft.shape != RuleDraft.Shape.BALANCE) {
            sectionLabel(getString(R.string.builder_section_direction), 2, 0, 2, 6);
            body.addView(directionBox(), margin(0, 0, 0, 12));
        }
        sectionLabel(getString(R.string.builder_section_details), 2, 0, 2, 6);
        body.addView(detailsBox(), margin(0, 0, 0, 12));
        tail = new LinearLayout(this);
        tail.setOrientation(LinearLayout.VERTICAL);
        body.addView(tail, margin(0, 0, 0, 0));
        renderTail();
    }

    /** Whether the reader has already been told the draft could not be kept, so a rule that will
     *  not persist says so once instead of on every keystroke. */
    private boolean warnedAboutDraft;

    /** Redraws the problems, the verdicts and the buttons. */
    private void renderTail() {
        tail.removeAllViews();
        tail.addView(problems(), margin(0, 0, 0, 12));
        verdictsBox();
        actions();
    }

    /** Where the coalesced draft write happens: a draft is sealed off the main thread, so neither
     *  the keystore nor the disk can stall the reader while they type. Started on demand and torn
     *  down with the screen, since a half-written rule is worth keeping but the thread holding it
     *  up is not. */
    private Handler background;

    private Handler background() {
        if (background == null) {
            HandlerThread worker = new HandlerThread("rule-draft");
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
            background = new Handler(worker.getLooper());
        }
        return background;
    }

    /** Called whenever the draft itself changes. The verdicts are dropped as well: a reading taken
     *  from a rule the reader has since edited says nothing about the rule in front of them, and
     *  installing on the strength of it would put something on the device that was never tested. */
    private void edited() {
        verdicts.clear();
        persist();
        renderTail();
    }

    /** The message the reader is working on. Raw bank text, so it never enters saved state. */
    private LinearLayout messageBox() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(card, 13));
        message = new EditText(this);
        message.setTypeface(Typeface.MONOSPACE);
        message.setTextSize(13);
        message.setTextColor(fg);
        message.setHintTextColor(muted);
        message.setBackgroundColor(0x00000000);
        message.setGravity(Gravity.TOP | Gravity.START);
        message.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        message.setHint(getString(R.string.builder_message_hint));
        message.setText(draft.body);
        message.setSaveEnabled(false);
        message.addTextChangedListener(watching(s -> {
            draft.body = s.toString();
            edited();
        }));
        box.addView(message, new LinearLayout.LayoutParams(-1, dp(170)));
        return box;
    }

    private LinearLayout identityBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(card, 13));
        box.addView(singleLine(getString(R.string.builder_bank_hint), draft.bankName,
                value -> {
                    draft.bankName = value;
                    edited();
                }), fieldParams());
        box.addView(singleLine(getString(R.string.builder_sender_hint), draft.sender, value -> {
            draft.sender = value;
            edited();
        }), fieldParams());
        return box;
    }

    

    /** One tappable choice, drawn the way every other tappable row in this app is. The chosen one is
     *  filled, because "which of these four" is a question the screen has to answer visually. */
    private TextView choice(LinearLayout parent, String label, boolean selected, Runnable onTap) {
        TextView view = text(label, 14, selected ? bg : fg);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setMinHeight(dp(48));
        view.setBackground(rounded(selected ? accent : card, 13));
        parent.addView(view, margin(0, 4, 0, 4));
        view.setOnClickListener(v -> {
            onTap.run();
            verdicts.clear();
            persist();
            redraw();
        });
        return view;
    }

    private LinearLayout typeBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(card, 13));
        choice(box, getString(R.string.builder_shape_balance),
                draft.shape == RuleDraft.Shape.BALANCE,
                () -> draft.shape = RuleDraft.Shape.BALANCE);
        choice(box, getString(R.string.builder_shape_movement),
                draft.shape == RuleDraft.Shape.MOVEMENT,
                () -> draft.shape = RuleDraft.Shape.MOVEMENT);
        choice(box, getString(R.string.builder_shape_both),
                draft.shape == RuleDraft.Shape.BOTH,
                () -> draft.shape = RuleDraft.Shape.BOTH);
        choice(box, getString(R.string.builder_shape_unsupported),
                draft.shape == RuleDraft.Shape.UNSUPPORTED,
                () -> draft.shape = RuleDraft.Shape.UNSUPPORTED);
        return box;
    }

    private LinearLayout highlightsBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(card, 13));
        box.addView(note(getString(R.string.builder_set_hint)));
        role(box, RuleDraft.Role.AMOUNT, getString(R.string.builder_highlight_amount));
        if (draft.shape != RuleDraft.Shape.MOVEMENT) {
            role(box, RuleDraft.Role.BALANCE, getString(R.string.builder_highlight_balance));
        }
        role(box, RuleDraft.Role.DATE, getString(R.string.builder_highlight_date));
        role(box, RuleDraft.Role.ACCOUNT, getString(R.string.builder_highlight_account));
        return box;
    }

    /** One part of the message: what it is, what is currently selected for it, and the two buttons
     *  that change that. The selection is shown rather than implied, because a highlight the reader
     *  cannot see is a highlight they will not check. */
    private void role(LinearLayout box, RuleDraft.Role role, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(text(label, 14, fg));
        RuleDraft.Anchor anchor = draft.anchor(role);
        TextView chosen = text(anchor == null ? getString(R.string.builder_not_set)
                : getString(R.string.builder_selected, anchor.text(draft)),
                12, anchor == null ? muted : accent);
        chosen.setMaxLines(3);
        row.addView(chosen, margin(0, 2, 0, 4));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        TextView set = action(getString(R.string.builder_set));
        // Tagged, because several roles carry a button with the same label and a test must be able
        // to reach the one belonging to the row it means.
        set.setTag(TAG_SET + role.name());
        set.setOnClickListener(v -> {
            int from = message.getSelectionStart();
            int to = message.getSelectionEnd();
            draft.highlight(Math.min(from, to), Math.max(from, to), role);
            verdicts.clear();
            persist();
            redraw();
        });
        buttons.addView(set);
        TextView clear = action(getString(R.string.builder_clear));
        clear.setTag(TAG_CLEAR + role.name());
        clear.setOnClickListener(v -> {
            draft.clear(role);
            verdicts.clear();
            persist();
            redraw();
        });
        buttons.addView(clear);
        row.addView(buttons);
        box.addView(row);
    }

    private LinearLayout directionBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(card, 13));
        choice(box, getString(R.string.builder_direction_debit), "DEBIT".equals(draft.direction),
                () -> draft.direction = "DEBIT");
        choice(box, getString(R.string.builder_direction_credit), "CREDIT".equals(draft.direction),
                () -> draft.direction = "CREDIT");
        return box;
    }

    /** The parts of the rule that are not words in the message: which calendar the dates are
     *  written in, whether a highlighted date carries a time as well, and any whole line the rule
     *  must ignore. Each is a fact about the message the builder cannot read off the digits, so the
     *  reader says it rather than the builder guessing it. */
    private LinearLayout detailsBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(10), dp(10));
        box.setBackground(rounded(card, 13));
        choice(box, getString(R.string.builder_digits_fixed), !draft.varyingDigits,
                () -> draft.varyingDigits = false);
        choice(box, getString(R.string.builder_digits_vary), draft.varyingDigits,
                () -> draft.varyingDigits = true);
        TextView digitsNote = text(getString(R.string.builder_digits_note), 13, muted);
        digitsNote.setLineSpacing(2, 1.05f);
        box.addView(digitsNote, margin(2, 2, 2, 8));
        if (draft.anchor(RuleDraft.Role.DATE) != null) {
            choice(box, getString(R.string.builder_calendar_jalali),
                    draft.calendar == RuleDraft.Calendar.JALALI,
                    () -> draft.calendar = RuleDraft.Calendar.JALALI);
            choice(box, getString(R.string.builder_calendar_gregorian),
                    draft.calendar == RuleDraft.Calendar.GREGORIAN,
                    () -> draft.calendar = RuleDraft.Calendar.GREGORIAN);
            choice(box, getString(R.string.builder_date_with_time),
                    draft.withTime, () -> draft.withTime = true);
            choice(box, getString(R.string.builder_date_without_time),
                    !draft.withTime, () -> draft.withTime = false);
        }
        TextView add = action(getString(R.string.builder_exclusion_add));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(fg);
        field.setHintTextColor(muted);
        field.setHint(getString(R.string.builder_exclusion_hint));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(rounded(bg, 13));
        field.setSaveEnabled(false);
        box.addView(field, margin(0, 4, 0, 0));
        box.addView(add, margin(0, 4, 0, 0));
        add.setTag(TAG_EXCLUDE);
        add.setOnClickListener(v -> {
            String line = field.getText().toString().trim();
            // An exclusion the rule cannot be written with would sit in the list looking like it
            // stops a message the rule will in fact read, so it is refused here rather than quietly
            // dropped later. The two refusals say different things: words the example already
            // contains are the mistake a reader is likely to make, and are worth naming.
            if (draft.inExample(line)) {
                toast(R.string.builder_exclusion_in_example);
                return;
            }
            if (!draft.exclusionUsable(line) || draft.exclusions.contains(line)) {
                toast(R.string.builder_exclusion_unusable);
                return;
            }
            draft.exclusions.add(line);
            edited();
        });
        for (String exclusion : new ArrayList<>(draft.exclusions)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView held = text(exclusion, 13, fg);
            held.setMaxLines(2);
            row.addView(held, margin(0, 0, 0, 0));
            TextView drop = action(getString(R.string.builder_clear));
            drop.setOnClickListener(v -> {
                draft.exclusions.remove(exclusion);
                edited();
            });
            row.addView(drop);
            box.addView(row, margin(0, 2, 0, 2));
        }
        return box;
    }

    /** What still stands between this draft and a rule, in the reader's words. The codes come from
     *  the builder so the text and the validation cannot drift apart. */
    private LinearLayout problems() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        List<RuleDraft.Problem> found = draft.problems();
        if (found.isEmpty()) return column;
        sectionLabel(column, getString(R.string.builder_section_problems), 2, 0, 2, 6);
        for (RuleDraft.Problem problem : found) {
            TextView line = text(problemText(problem), 13, muted);
            line.setLineSpacing(2, 1.05f);
            column.addView(line, margin(2, 2, 2, 2));
        }
        return column;
    }

    /** Every test run in this session, most recent first, so a reader can see the verdict change as
     *  they correct the rule rather than only the last one. */
    private void verdictsBox() {
        sectionLabel(tail, getString(R.string.builder_section_test), 2, 0, 2, 6);
        if (verdicts.isEmpty()) {
            tail.addView(note(getString(R.string.builder_test_hint)), margin(0, 0, 0, 0));
        } else {
            for (RuleDraftTester.Verdict verdict : verdicts) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(10), dp(14), dp(10));
                card.setBackground(rounded(this.card, 13));
                TextView headline = text(verdict.parsed
                        ? getString(R.string.builder_test_working, amountText(verdict))
                        : getString(R.string.builder_test_not_working), 14,
                        verdict.parsed ? accent : muted);
                card.addView(headline);
                if (!verdict.parsed) {
                    TextView why = text(getString(R.string.builder_test_not_working_reason,
                            reasonText(verdict)), 12, muted);
                    why.setLineSpacing(2, 1.05f);
                    card.addView(why, margin(0, 4, 0, 0));
                }
                for (String issue : verdict.issues) {
                    TextView line = text(issue, 12, muted);
                    line.setLineSpacing(2, 1.05f);
                    card.addView(line, margin(0, 2, 0, 0));
                }
                if (verdict.needsConfirming()) {
                    TextView claims = text(getString(R.string.builder_test_claims,
                            String.join(", ", verdict.claims)), 12, muted);
                    claims.setLineSpacing(2, 1.05f);
                    card.addView(claims, margin(0, 4, 0, 0));
                }
                tail.addView(card, margin(0, 0, 0, 8));
            }
        }
        TextView test = button(getString(R.string.builder_test), accent, bg);
        test.setOnClickListener(v -> test());
        tail.addView(test, margin(0, 0, 0, 12));
    }

    /** The reading the engine produced, shown the way every other balance in the app is shown: in
     *  the currency the rule says, divided by that currency's scale. A bare minor-unit count would
     *  show a reader of a two-decimal currency a number a hundred times too large. */
    private String amountText(RuleDraftTester.Verdict verdict) {
        if (verdict.amounts.isEmpty()) return getString(R.string.builder_test_no_amount);
        StringBuilder out = new StringBuilder();
        for (RuleDraftTester.Amount amount : verdict.amounts) {
            if (out.length() > 0) out.append(", ");
            out.append(CurrencyHelper.amount(this, amount.currency(), amount.minorUnits()));
        }
        return out.toString();
    }

    private String reasonText(RuleDraftTester.Verdict verdict) {
        switch (verdict.status) {
            case PARSED: return getString(R.string.builder_test_reason_parsed);
            case UNKNOWN_SENDER: return getString(R.string.builder_test_reason_unknown_sender);
            case NO_MATCH: return getString(R.string.builder_test_reason_no_match);
            case ABSENT: return getString(R.string.builder_test_reason_absent);
            case INVALID: return getString(R.string.builder_test_reason_invalid);
            case AMBIGUOUS: return getString(R.string.builder_test_reason_ambiguous);
            case OVERFLOW: return getString(R.string.builder_test_reason_overflow);
            case LIMIT_EXCEEDED: return getString(R.string.builder_test_reason_limit_exceeded);
            default: return verdict.status.name();
        }
    }

    private String problemText(RuleDraft.Problem problem) {
        switch (problem.code) {
            case NO_MESSAGE: return getString(R.string.builder_problem_no_message);
            case NO_SENDER: return getString(R.string.builder_problem_no_sender);
            case NO_BANK: return getString(R.string.builder_problem_no_bank);
            case MISSING_AMOUNT: return getString(R.string.builder_problem_missing_amount);
            case MISSING_BALANCE: return getString(R.string.builder_problem_missing_balance);
            case CROSSES_LINE: return getString(R.string.builder_problem_crosses_line,
                    problem.detail);
            case OVERLAP: return getString(R.string.builder_problem_overlap);
            case NO_PREFIX: return getString(R.string.builder_problem_no_prefix, problem.detail);
            case NOT_NUMERIC: return getString(R.string.builder_problem_not_numeric,
                    problem.detail);
            case DATE_NO_SEPARATOR: return getString(R.string.builder_problem_date_no_separator,
                    problem.detail);
            case DATE_YEAR: return getString(R.string.builder_problem_date_year, problem.detail);
            case DATE_NO_TIME: return getString(R.string.builder_problem_date_no_time);
            case SENDER_TOO_LONG: return getString(R.string.builder_problem_sender_too_long);
            case BANK_TOO_LONG: return getString(R.string.builder_problem_bank_too_long);
            case TOO_MANY_LINES: return getString(R.string.builder_problem_too_many_lines);
            case EMPTY_AROUND: return getString(R.string.builder_problem_empty_around,
                    problem.detail);
            default: return problem.code.name();
        }
    }

    private void actions() {
        sectionLabel(tail, getString(R.string.builder_section_install), 2, 0, 2, 6);
        // Another rule already reads this sender. Installing anyway is allowed -- a second rule for
        // a bank is a normal thing to want -- but the reader is asked, because from then on both
        // rules compete for every message from that sender and only one can win.
        RuleDraftTester.Verdict last = verdicts.isEmpty() ? null : verdicts.get(0);
        if (last != null && last.needsConfirming()) {
            TextView title = text(getString(R.string.builder_claim_title), 14, fg);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            tail.addView(title, margin(0, 0, 0, 4));
            TextView warning = text(getString(R.string.builder_claim_message,
                    String.join(", ", last.claims)), 13, muted);
            warning.setLineSpacing(2, 1.05f);
            tail.addView(warning, margin(0, 0, 0, 10));
            TextView go = button(getString(R.string.builder_claim_install), card, fg);
            go.setOnClickListener(v -> writePack());
            tail.addView(go, margin(0, 0, 0, 8));
        }
        TextView install = button(getString(R.string.builder_install), accent, bg);
        install.setOnClickListener(v -> install());
        tail.addView(install, margin(0, 0, 0, 8));
        TextView share = button(getString(R.string.builder_share), card, fg);
        share.setOnClickListener(v -> share());
        tail.addView(share, margin(0, 0, 0, 24));
    }

    private void test() {
        if (!draft.ready()) {
            toast(R.string.builder_not_ready);
            return;
        }
        verdicts.add(0, RuleDraftTester.test(draft, engine, draft.sender.trim(), draft.body,
                System.currentTimeMillis()));
        redraw();
    }

    /** Installs the rule through the same store an import uses, so a reader's own rule goes on the
     *  device by exactly the path a contributed pack does and gets the same conflict and revision
     *  checks. The engine is refreshed only after the store has agreed. */
    private void install() {
        if (!draft.ready()) {
            toast(R.string.builder_not_ready);
            return;
        }
        RuleDraftTester.Verdict last = verdicts.isEmpty() ? null : verdicts.get(0);
        if (last == null || !last.parsed) {
            // Installing a rule that does not read the message it was built from would put something
            // on the device that looks installed and reads nothing.
            toast(R.string.builder_test_first);
            return;
        }
        writePack();
    }

    /** Puts the draft on the device by the same path an imported pack takes. */
    private void writePack() {
        // Both ways onto this screen's install button end here, so the draft is checked here too:
        // agreeing to install over a rule that already reads this sender is not the same as having
        // finished building the rule.
        if (!draft.ready()) {
            toast(R.string.builder_not_ready);
            return;
        }
        try {
            LocalPackStore store = EngineRules.localStore(this);
            String json = PackWriter.write(PackDocument.decode(draft.document()));
            LocalPackStore.Result result = store.install(store.stage(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
            if (engine != null) engine.refreshLocal();
            savedOrDiscarded = true;
            toast(result.outcome() == LocalPackStore.Outcome.NO_OP
                    ? R.string.builder_already_installed : R.string.builder_installed);
            finish();
        } catch (LocalPackStore.Failure refused) {
            // Only a clash with an existing pack is a clash. Being told the rule "conflicts" when
            // the real problem is that it is malformed, or that there is no room left for it, sends
            // the reader looking for a conflict that does not exist.
            switch (refused.code) {
                case CONFLICT:
                case DOWNGRADE:
                    toast(R.string.builder_install_conflict);
                    break;
                case FULL:
                    toast(R.string.builder_install_full);
                    break;
                default:
                    toast(R.string.builder_install_failed);
                    break;
            }
        } catch (IOException | RuntimeException failure) {
            toast(R.string.builder_install_failed);
        }
    }

    /** Opens the same preview every other outbound report goes through, so nothing a reader
     *  contributed can leave the app without having been shown first. */
    private void share() {
        if (!draft.ready()) {
            toast(R.string.builder_not_ready);
            return;
        }
        String json;
        try {
            json = PackWriter.write(PackDocument.decode(draft.document()));
        } catch (RuntimeException impossible) {
            toast(R.string.builder_not_ready);
            return;
        }
        String sender = draft.sender.trim();
        Intent preview = new Intent(this, SharePreviewActivity.class);
        preview.putExtra(SharePreviewActivity.EXTRA_SUBJECT,
                getString(R.string.builder_share_subject, sender));
        preview.putExtra(SharePreviewActivity.EXTRA_REPORT,
                getString(R.string.builder_share_report, RuleContext.header(engine), sender,
                        draft.body, json));
        startActivity(preview);
    }

    // ---- draft persistence ----

    private void persist() {
        // Sealing a draft costs a keystore load, an fsync and a file rename. Doing that on every
        // keystroke puts all three on the main thread and leaves the screen stuttering behind the
        // reader's own typing, so edits are coalesced into a single trailing write instead: the
        // draft is still saved a moment after the reader stops typing, and again whenever the
        // screen is left.
        background().removeCallbacks(sealing);
        background().postDelayed(sealing, 600);
    }

    private final Runnable sealing = this::persistNow;

    /** Writes the draft, off the main thread, and says so when it could not be kept. */
    private boolean persistNow() {
        // Once the rule is installed or thrown away, the draft is gone on purpose. A write already
        // queued behind the reader's typing must not put it back: the draft exists to survive a
        // pause, and this is the pause that was supposed to end it.
        if (savedOrDiscarded) return true;
        Map<String, Object> state = save();
        boolean kept = store.write(state);
        if (!kept && !warnedAboutDraft) {
            warnedAboutDraft = true;
            new Handler(Looper.getMainLooper()).post(() -> toast(R.string.builder_draft_not_kept));
        }
        return kept;
    }

    @Override
    protected void onPause() {
        // Leaving the foreground is the one moment the draft must be on disk rather than pending.
        // This runs before the screen can be torn down or replaced, so a reader who walks away or
        // comes straight back finds the rule they were in the middle of. Typing still only costs
        // one trailing write, because the debounce above has already absorbed the keystrokes.
        if (background != null) background.removeCallbacks(sealing);
        persistNow();
        super.onPause();
    }

    private Map<String, Object> save() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sender", draft.sender);
        state.put("bankName", draft.bankName);
        state.put("body", draft.body);
        state.put("shape", draft.shape.name());
        state.put("direction", draft.direction);
        state.put("currency", draft.currency);
        state.put("calendar", draft.calendar.name());
        state.put("varyingDigits", draft.varyingDigits);
        state.put("withTime", draft.withTime);
        state.put("exclusions", new ArrayList<>(draft.exclusions));
        Map<String, Object> selected = new LinkedHashMap<>();
        for (RuleDraft.Anchor anchor : draft.ordered()) {
            selected.put(anchor.role.name(), List.of(anchor.start, anchor.end));
        }
        state.put("selected", selected);
        return state;
    }

    private void restore(Map<String, Object> state) {
        draft.sender = text(state.get("sender"));
        draft.bankName = text(state.get("bankName"));
        draft.body = text(state.get("body"));
        draft.direction = text(state.get("direction"));
        if (draft.direction.isEmpty()) draft.direction = "DEBIT";
        draft.currency = text(state.get("currency"));
        if (draft.currency.isEmpty()) draft.currency = "IRR";
        draft.shape = shape(state.get("shape"), RuleDraft.Shape.MOVEMENT);
        draft.calendar = state.get("calendar") != null
                && "GREGORIAN".equals(text(state.get("calendar"))) ? RuleDraft.Calendar.GREGORIAN
                : RuleDraft.Calendar.JALALI;
        draft.varyingDigits = Boolean.TRUE.equals(state.get("varyingDigits"));
        draft.withTime = Boolean.TRUE.equals(state.get("withTime"));
        Object exclusions = state.get("exclusions");
        if (exclusions instanceof List<?> list) {
            for (Object line : list) {
                String word = text(line).trim();
                if (!word.isEmpty()) draft.exclusions.add(word);
            }
        }
        Object selected = state.get("selected");
        if (selected instanceof Map<?, ?> spans) {
            for (Map.Entry<?, ?> entry : spans.entrySet()) {
                try {
                    RuleDraft.Role role = RuleDraft.Role.valueOf(text(entry.getKey()));
                    List<?> span = (List<?>) entry.getValue();
                    draft.highlight(((Number) span.get(0)).intValue(),
                            ((Number) span.get(1)).intValue(), role);
                } catch (RuntimeException stale) {
                    // A draft written by an older build, or one whose anchors no longer fit the
                    // message it was restored with: the reader sets the highlights again.
                }
            }
        }
    }

    private static RuleDraft.Shape shape(Object value, RuleDraft.Shape fallback) {
        String name = text(value);
        for (RuleDraft.Shape candidate : RuleDraft.Shape.values()) {
            if (candidate.name().equals(name)) return candidate;
        }
        return fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ---- shared drawing ----

    /** A quiet line of explanation, which the caller puts wherever it belongs. */
    private TextView note(String words) {
        TextView view = text(words, 12, muted);
        view.setLineSpacing(2, 1.05f);
        view.setPadding(dp(2), dp(2), dp(2), dp(6));
        return view;
    }

    

    private TextView button(String label, int background, int foreground) {
        TextView view = text(label, 15, foreground);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setMinHeight(dp(48));
        view.setBackground(rounded(background, 13));
        return view;
    }

    private EditText singleLine(String hint, String value, java.util.function.Consumer<String> onChange) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(fg);
        field.setHintTextColor(muted);
        field.setHint(hint);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(rounded(bg, 13));
        field.setText(value);
        field.setSaveEnabled(false);
        field.addTextChangedListener(watching(onChange));
        return field;
    }

/** The identity box holds two fields; adding them here keeps the box's padding in one place. */
    private LinearLayout.LayoutParams fieldParams() {
        return margin(0, 4, 0, 4);
    }

    private static TextWatcher watching(java.util.function.Consumer<String> onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                onChange.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        };
    }
}