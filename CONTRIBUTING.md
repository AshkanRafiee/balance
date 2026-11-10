# Contributing to Balance

Balance reads supported Iranian financial SMS on-device. Helping us keep the
supported message formats up to date — or report a problem — is the most valuable
contribution you can make.

## What Balance can and cannot detect

- Balance recognizes the **balance messages of about 40 Iranian banks** (sender
  aliases and message layouts).
- Only a handful of those banks state a **machine-readable account number** in
  their SMS. For those banks Balance splits the balance — and history — per
  account. Without an account number, Balance shows the latest reported balance
  for that institution, not the sum of several accounts. It cannot
  distinguish multiple accounts when messages do not identify them.
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

Start with pull-to-refresh: on the bank list it checks balances; on history it
checks balances and transactions. This is not a reset. If a message still looks
wrong, use the Data menu's safe re-read to check bank messages again without
deleting commitments, notes, tags, excluded-account choices, retained history or
saved message sources. You can also use **Report** to share a redacted example;
you do not need to erase your data to report a problem.

Scans can only read messages Android makes available to Balance. A missing or
deleted message, denied SMS permission, or Android restrictions can leave gaps;
a scan is not a live check against your bank. Tell us if any of these apply.

### A full reset is destructive

Use the Data menu's full reset only when you intend to remove saved financial
data and rebuild what can be read from the current inbox. It requires a second
confirmation; it is not the first troubleshooting step.

- **Back up first** with **Data → Create backup**. Keep the password safe;
  restoring requires the same or a newer app version. Reset does not delete
  backup files.
- Saved balances, transaction history and **commitments are deleted**. Inbox
  messages can rebuild supported balances and history, but not your commitments
  or transactions whose SMS have been deleted.
- Notes and tags are deleted only if you choose the additional deletion option.
- Excluded accounts are included in the combined total again. Display and lock
  preferences remain unchanged.

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

Use a trusted JDK distribution and Google's official Android SDK tools. The
release workflow uses Temurin JDK 21, Android platform 36 and build-tools 36.0.0;
the app's Java source compatibility is 17. Install the SDK packages with the
official `sdkmanager` and point `ANDROID_HOME` at your SDK installation:

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
bash ./gradlew assembleDebug
bash ./gradlew assembleRelease
```

Use the checked-in Gradle wrapper rather than downloading Gradle from an
unverified mirror. Its official distribution URL and SHA-256 checksum are in
`gradle/wrapper/gradle-wrapper.properties`. The project currently uses Gradle
8.14.5 and Android Gradle Plugin 8.13.0. Check the build files and
`.github/workflows/release.yml` if these versions change.

### Release signing

A release build is unsigned when no keystore is configured, which supports
source-based distribution builds. For a locally signed release, create
`signing.properties` next to the root `build.gradle`, using your own values:

```properties
storeFile=/path/to/balance-release.jks
storeType=JKS
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Keep the keystore and passwords outside version control. `signing.properties`
and `*.jks` are gitignored; never attach them to reports or build logs. The
GitHub release workflow restores signing files from repository secrets.

### Version numbers and release metadata

The repository uses `major.minor.patch` for `versionName` and derives
`versionCode` with **`major * 10000 + minor * 1000 + patch`**. For example,
`1.22.0` gives `10000 + 22000 + 0 = 32000`. Recompute the value from the intended
version before changing `app/build.gradle`; do not substitute another project's
formula. The release tag, build version and store changelog filename
(`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`) must agree.

Keep README and English/Persian store descriptions accurate for the shipped
behavior. Each `full_description.txt` must remain under 4000 characters. Write
product-level changelogs at release time. Never move a published release tag;
later changes belong to a new release.
