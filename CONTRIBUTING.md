# Contributing: bank formats, account detection and bug reports

Balance parses Iranian bank SMS messages on-device. Helping us keep the
recognized bank formats up to date — or report a problem — is the most valuable
contribution you can make.

## What Balance can and cannot detect

- Balance recognizes the **balance messages of about 40 Iranian banks** (sender
  aliases and message layouts).
- Only a handful of those banks state a **machine-readable account number** in
  their SMS. For those banks Balance splits the balance — and history — per
  account. For every other bank, Balance shows one combined balance per bank,
  even if you hold several accounts, because the message carries no account
  number to tell them apart.
- History entries (deposits/withdrawals) also come from the same message
  layouts. A message layout we have not seen before can therefore mean a wrong
  or missing transaction in history, even when the balance looks fine.

If your balance, account split or history is wrong, it is almost always because
a message format differs from the ones we have on file. Tell us about it and we
will add or fix the rule.

## See what Balance skipped, right in the app

Before writing a report manually, try **Scan diagnostics** — it is the
**Report** item in the footer of the main screen.
It reads the messages on your device and shows, per recognized bank, how many
SMS it parsed, and below that every **message it could not parse**:

- **Known senders, unrecognized messages** — a supported bank's sender, but a
  message layout we could not read (a format gap, just like an unknown sender).
- **Senders not recognized yet** — sender numbers Balance does not know at all.

Each sender is a compact, tappable entry (a chevron marks it): tapping it opens
its message chooser, where the checkmarks live — tick exactly which of its
messages to share, nothing is preselected, and then:

- **Send (n)** — a prefilled email to us opens; nothing is sent until you
  choose your mail app and confirm there.
- **Copy selected (n)** — copies exactly the ticked messages.

That screen is the fastest contribution funnel there is: every unparsed message
is a bank SMS layout we haven't got on file (the bank is named when we know
it), and the chooser already holds exactly the sender number and sample
messages a new rule needs. Nothing from the scan leaves your device until you
copy it or send it yourself.

## Two ways to reach us

1. **GitHub issue** — the preferred channel, so everyone can see the fix:
   https://github.com/AshkanRafiee/balance/issues
2. **Email** — if you would rather not create an issue or not share public
   details:
   balance.plausible268@passmail.net

We integrate reported bank formats into the next update either way.

## Contributing a rule for a bank we do not read yet

If you can tell us what a bank's messages mean, you can skip the report route
entirely and write the rule yourself.

**The easiest route needs no file at all: build the pack in the app.** Open
**Display → Banks Balance reads → Your own packs** and tap
**Write a rule for your bank**. Nothing leaves the phone while you do this: the
message you paste, the rule you are writing and the test run all happen on the
device, and the rule is stored there. The steps mirror the screen's own:

1. **Paste one message** from the bank.
2. **Who sent it** — the sender exactly as it appears in your messaging app,
   plus the name you want the bank to have in Balance.
3. **What the message says** — balance only, a deposit or a withdrawal, or both.
   Choosing "Balance cannot read this message" is the way to say so in Balance's
   own terms: no rule is built and nothing is installed.
4. **Highlight the parts** — select the amount, the balance, the date (and the
   account number, if the message carries one) in the pasted text and tap Set.
5. **Which way the money moves**, whether the date is Persian or Gregorian and
   whether it carries a time, and any whole line the rule must ignore.
6. **Test this rule** — Balance reads that same message with the new rule and
   tells you what it got, so a wrong reading is yours to fix first.
7. **Install on this device**, or **Send it for review** — which first shows
   you exactly what would be sent, and only then hands it to the app you pick.

Everything you have written or imported is listed in that same **Your own packs**
screen, and each pack can be exported as a file you attach to a GitHub issue or
pull request. Balance has no internet permission: the file goes wherever you
send it, and nowhere else.

A pack that reads correctly is worth more than a description of a message, and
what makes it reviewable is included in the pack itself: the sender, the layout,
and the examples it must read and the ones it must leave alone. To be useful in a
review, please also say:

- **The sender** exactly as it shows on your phone.
- **What the message is**, in plain words: the balance line, the amount, the
  currency, and the date — including how the date is written.
- **One message it must read and one it must not** (a payment confirmation that
  should not become a movement is the usual one).
- **That the examples may be shared**, with the real numbers replaced as
  described above, if you would rather not publish them.
- **Someone who uses that bank**, if you know one, confirming the rule reads
  their own messages. We cannot test this part ourselves, and we say so in the
  catalog rather than implying otherwise.

We mark each rule with how it was built and who has seen it work, and a rule with
only reader reports behind it is labelled that way until someone who uses the
bank has checked it. Being submitted is not the same as being supported.

## Before you report: try resetting the data

Some balance and history problems — wrong, missing or duplicated entries, most
often right after an update changed the parsing rules — come from stale data and
can be fixed with a reset. It is the quickest thing to try first:

Open **Data → Reset & rescan**. Balance discards its stored balances and
transaction history and rebuilds them from the messages currently in your SMS
inbox, exactly like a fresh install. If the problem disappears, you are done — no
report needed. If it still shows up after the rebuild, please report it and we
will get to the parsing rule straight away.

What a reset does, so there are no surprises:

- **Stored balances and history are erased and rebuilt** from the messages that
  are in your inbox *right now*. Messages you have deleted from SMS cannot be
  rebuilt — the transactions they contained will not come back.
- **Backup first.** Make an encrypted backup beforehand (**Data → Create
  backup**; restoring merges with your current data), so you can always go back.
  A reset does not touch your backup files.
- **Excluded banks are reset** — every bank is included in the combined total
  again, so re-exclude any you had switched off.
- **Nothing else is lost** — privacy exposure (mask/unmask), Toman display,
  bank order, lock PIN/password, and every other setting stay as they are.

If a problem survives a reset, tell us that you already tried it; it rules out
stale data and points us straight at the format rule.

## Reporting a wrong balance, account split or history entry

Always include:

- **Bank name** — the name Balance shows for it (e.g. `Mellat`, `Saderat`,
  `Pasargad`).
- **Sender number** — the exact number the SMS comes from, as shown in your
  messaging app (e.g. `Meli`, `B.Pasargad`, `5000973189`, `+98...`). On most
  devices you can long-press the message header or look at the message details
  to see the full sender.
- **The message text, exactly as the bank sent it** — copy the whole message
  out of your SMS app: every line, every space, and the digit style (Persian or
  Western numerals) matter. Formatting tells us *how* to parse; the content
  tells us *what* is wrong.
- **What is wrong** — be specific: e.g. “balance shows a number I do not
  recognize”, “two of my accounts are merged into one”, “the account shown does
  not match any of mine”, “a transfer and its fee appear as two wrong entries”,
  “no history for this bank”.
- **Android version and phone model** — e.g. “Android 14, Samsung Galaxy A54
  5G”. Needed to reproduce the problem reliably.

### Privacy: you can keep your numbers (or scramble them)

Sharing the real SMS is fine if you are comfortable with it. Otherwise you can
**replace the numbers while keeping the message structure identical**:

- Keep every line, spacing, punctuation and Persian/western digit style.
- Replace each number with a made-up one of the **same length**:
  - an 18-digit account → another 18-digit number,
  - a 5-digit code → another 5-digit number,
  - an amount/balance → another number with the same digit count and the same
    thousands separator (e.g. `10,000,000` becomes `2,900,000`, not `29`),
  - a date → anything in the same format (e.g. `07/20_14:05`).

Length and position are what the parser keys on, so keeping them lets us fix the
rule without you sharing real financial data. If you email us the report, nothing
about it becomes public either way.

## Reporting any other problem (crash, wrong total, widget, lock, backup…)

Always include:

- **Balance version** — from the About screen.
- **Android version** — e.g. “Android 14”.
- **Phone make and model** — e.g. “Samsung Galaxy A54 5G”.
- **Steps to reproduce** — what you tapped, in order.
- **What you expected vs. what happened.**
- Useful extras: screenshots, a screen recording, and log output if you can
  capture it.
- If the issue involves balances or history, try the data reset described in
  “Before you report: try resetting the data” above, and tell us whether the
  problem survived it.

## Checks you can run yourself

Every check the pull-request workflow runs also runs on your machine, and none
of them downloads rules or needs a secret. The parser side needs a JDK (the
workflow uses Temurin 21) and Python 3 on your PATH; the app checks also need an
Android SDK with platform 36 and build-tools 36.0.0.

- **`./gradlew :parser-core:check`** — the whole rule side in one command:
  the executable parser suites, every pack and every example in the repository
  decoded and run through them, and the Iranian catalog compared against the
  behaviour it replaced. This is the gate any change to a rule, a catalog or an
  example has to pass, and it already runs the Python tool tests below.
- **`python3 -B tools/test_generate_ir_catalog.py`** — the generator that authors
  the shipped Iranian bank list from the frozen tables behind it, so the list
  cannot drift by transcription.
- **`python3 -B tools/test_read_official_pack.py`** — the strict reader the
  official-pack gate hands documents to: it rejects duplicate keys, malformed
  and oversized documents, and any pack that disagrees with the shipped
  catalog.
- **`python3 -B tools/test_read_rule_examples.py`** — the same strict reading for
  the repository's rule examples.

The app side is built and linted the way the workflow builds it:

- **`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`**

**Instrumented Android tests need a connected device or emulator, so they are
not part of CI** — they run on an emulator before a release. Everything above
runs without a phone.

## What happens next

We review the report, reproduce it, add or fix the matching rule, and the fix
ships in a later Balance release. Thank you for helping keep Balance correct for
everyone.