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

## Two ways to reach us

1. **GitHub issue** — the preferred channel, so everyone can see the fix:
   https://github.com/AshkanRafiee/balance/issues
2. **Email** — if you would rather not create an issue or not share public
   details:
   balance.plausible268@passmail.net

We integrate reported bank formats into the next update either way.

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

## What happens next

We review the report, reproduce it, add or fix the matching rule, and the fix
ships in a later Balance release. Thank you for helping keep Balance correct for
everyone.