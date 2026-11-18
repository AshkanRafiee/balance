# Contributing to Balance

Balance reads supported Iranian financial SMS on-device. Helping us keep the
supported message formats up to date — or report a problem — is the most valuable
contribution you can make.

For everyday use, see the [Balance user guide](docs/USER-GUIDE.md).

## What Balance can and cannot detect

See [supported bank SMS](docs/USER-GUIDE.md#get-started-with-bank-sms) and
[how accounts appear](docs/USER-GUIDE.md#read-balances-and-history) in the
user guide. An unsupported layout can cause a wrong or missing transaction in
history even when the balance looks fine.

If the relevant SMS is still available but Balance shows the wrong balance,
account or history entry, send a sample so we can check the rule.

## See what Balance skipped, right in the app

Before writing a report manually, try **Scan diagnostics** — it is the
**Report** item in the footer of the main screen.
It reads the messages on your device and shows, per recognized bank, how many
SMS it parsed — each recognized sender below the counts is tappable, so a
message that parsed but gets an amount, date, account, reason, channel or merchant wrong
can be reported too — and below that every **message it could not parse**:

- **Known senders, unrecognized messages** — a supported bank's sender, but a
  message layout we could not read (a format gap, just like an unknown sender).
- **Senders not recognized yet** — sender numbers Balance does not know at all.

Each sender is a compact, tappable entry (a chevron marks it): tapping it opens
its message chooser, where the checkmarks live — tick exactly which of its
messages to share, nothing is preselected, mark what is wrong (account,
balance, sender-number, amount, date, reason, channel or merchant detection), and then:

- **Send (n)** — a prefilled email to us opens; nothing is sent until you
  choose your mail app and confirm there.
- **Copy selected (n)** — copies exactly the ticked messages.

One-time password values are removed from shared samples while the surrounding bank message layout is
kept for diagnosis.

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

## Before you report: refresh without deleting data

Try [refreshing or re-reading bank messages safely](docs/USER-GUIDE.md#refresh-safely-or-report-a-problem).
Tell us whether any relevant SMS are missing or Android is restricting access.
You do not need to erase your data to report a problem.

For Wepod, a saved balance can follow its newly recognized account when a
uniquely matching saved message proves the link. If the newest SMS was deleted
and that evidence is missing or ambiguous, splitting accounts may instead use
older inbox balances; re-reading cannot guarantee recovering the newer balance.
Do not reset your data to try to resolve this.

### A full reset is destructive

See [what a full reset deletes](docs/USER-GUIDE.md#only-reset-if-you-want-to-rebuild-from-the-inbox).
If you have already reset, mention it in the report; do not reset again just to
file an issue.

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
- If the issue involves balances or history, tell us whether refreshing helped
  and whether the relevant SMS are still present. A destructive reset is not
  required.

## What happens next

We review the report, reproduce it, add or fix the matching rule, and the fix
ships in a later Balance release. Thank you for helping keep Balance correct for
everyone.

## Build from source

Install JDK 21 and Google's [Android SDK command-line tools](https://developer.android.com/tools/sdkmanager).
Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK. From the
repository root, install the required SDK packages and use the included Gradle
wrapper to build a debug APK:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0"
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
