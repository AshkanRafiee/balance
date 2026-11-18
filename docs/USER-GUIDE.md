# Balance user guide

## Get started with bank SMS

Balance runs on Android 8.0 and later. Allow SMS access to read supported
Persian messages from Iranian banks and selected financial services. Balance
recognizes balance formats from about 40 institutions, but support depends on
both the sender and the message layout. A recognized bank may send some messages
that Balance cannot read. Other countries and message formats are not supported.

Balance does not log in to your bank or check it live. It can only scan messages
that Android makes available. Missing or deleted SMS can leave gaps. Android 17
can also hide restricted messages from Balance even when SMS access is granted.
Turning off SMS access does not delete saved records; it stops Balance from
updating them from your inbox.

## Read balances and history

- **Balances** show what your bank last reported, not what it would report now.
  Set a **Stale balance warning** in **Display** to flag older figures.
  An old balance can still count toward your combined total.
- **Accounts** appear separately only when Balance recognizes their account
  numbers. If the messages do not identify the accounts, the institution's
  figure is its latest reported balance, not a sum of all your accounts there.
  Use a row's menu to **Exclude from total** or **Include in total** without
  deleting anything. Check your exclusions when an update recognizes new accounts.
- **History** groups deposits and withdrawals by day, month and year. Search
  or filter by bank, account, date, direction or tag. Recent matches open
  automatically; expand older periods to see more. Tap a movement to inspect
  it or edit your note and tags. Reasons, payment channels and matched merchants
  are shown when Balance has enough information. A one-time password prompt
  alone does not confirm a transaction.

Choose **Explain this figure** in a balance row's menu or tap a history row to
see the source when Balance can identify it. The explanation distinguishes the
time printed in the message from when the phone received it. If the bank event
time is unknown, Balance may use SMS arrival instead. Older or restored figures
can lack a source, and Balance does not pick one when several messages match.

### What “Unaccounted” means

An **Unaccounted** row is the net change between two reported balances minus
the net movements Balance recorded between them, including the movement on the
closing statement. For example, if the bank reports a drop of 500,000 rials and
the recorded movements net to a drop of 400,000 rials, the unaccounted amount
is −100,000 rials.

That difference contributes to history totals but is not an identified payment.
It does not tell you when, why or how many movements took place. Changing your
search or filters does not recalculate the difference from the visible rows.
A delayed message can reduce or close the gap if it fits the statement period
and accounts for the difference.

## Track payments and receipts

In **Commitments**, add money you plan to pay or receive. Set a one-off, daily,
weekly, monthly or yearly schedule. Repeating commitments can stop at an end
date or a set count, or continue indefinitely. A recurring schedule keeps the
calendar and time zone it was set up with even if you change your display
calendar or travel.

Use **Mark paid** or **Mark received** on individual dues. Balance does not
make payments or automatically mark a due from bank SMS. Payable and receivable
totals are separate from your bank balances and from each other. The dashboard
card shows what remains for this month plus overdue amounts, not the lifetime
total of a recurring commitment.

Turn on **Remind me** when editing a commitment to choose how early to be
notified. Android must allow notifications. If exact alarms are off, the
reminder can still arrive less precisely; Android can delay delivery even
with permission. Tapping a reminder opens and highlights that specific due
if it is still available. The notification can show the name, amount and date
in the unlocked notification shade; check Android's notification settings if
you prefer not to show those details.

## Protect and move your data

Balance has no internet permission and does not upload your data. Saved
financial records, including retained bank-message records, are encrypted
on your device even with the optional app lock off. Balance can retain a
copy of messages from recognized senders, including messages it could not
parse. This is separate from the SMS in your messaging app.

- **App lock** requires a PIN or password to unlock, with optional fingerprint
  unlock on supported devices. While enabled, it blocks screenshots in Balance
  and the home-screen widget shows a lock instead of balances, even when the
  app itself is unlocked.
- **Balance hiding** is for quick privacy on screen, not access control. Tap
  the eye to hide or show balances; long-press it to toggle automatic hiding
  when you return to the app. Both are off by default. When app lock is off,
  the widget has its own hide/show control. Hiding amounts does not protect
  an exported file.
- **History → Export** writes the current filtered history to an **unencrypted
  CSV**. It can include account identifiers, amounts, notes, tags and bank
  details, as well as matching Unaccounted rows. It does not include copies of
  the source messages and is not a restorable backup.
- **Data → Create backup** writes a password-encrypted file containing saved
  balances, transaction history, notes, tags, bank-provided details, commitments
  and their records, and retained bank SMS. The message records can include
  original text and sender details, including one-time codes if the original
  text contains them. Reporting redaction does not apply to the encrypted
  backup. The file does not include your entire inbox, account exclusions, app
  lock or display settings. Keep the password safe: Balance does not store it
  and cannot recover it for you.
- **Data → Restore backup** needs the backup password and the same or a newer
  Balance version than the one that created the file. Choose which sections
  to merge. Older backup balances do not replace newer saved ones. Bringing
  back deleted commitments is a separate, unchecked option.

Export and backup files go to the location you choose. Copying or sharing
sends selected information to the Android clipboard or the app you choose.
Balance cannot control what those destinations do with it.

## Refresh safely or report a problem

1. Pull to refresh. On the dashboard this checks balances; in History it
   checks balances and transactions.
2. If an available message still looks wrong, try **Data → Re-read bank
   messages**. This rechecks the inbox with the current rules and keeps
   commitments, exclusions, notes, tags, saved history and message records.
   It does not recover missing SMS.
3. Use **Report** from the dashboard footer to see what Balance parsed or
   skipped. Nothing is sent automatically. Review selected samples before
   copying or emailing them, even though the report removes one-time password
   values. See [how to report a parsing problem](../CONTRIBUTING.md#see-what-balance-skipped-right-in-the-app).

### Only reset if you want to rebuild from the inbox

**Data → Full reset** requires two confirmations. It deletes saved balances,
history, message records, account exclusions and all commitments, including
their paid or skipped records. It deletes notes and tags only if you select
that option. Display and lock preferences stay as they are.

Create an encrypted backup first if you might need the data again. Reset does
not delete backup files or the inbox. A scan can rebuild balances and history
from supported SMS that are still available. It cannot recreate commitments
or recover history from missing SMS.

## Display preferences

Use **Display** to choose Persian or English, Persian or Gregorian dates,
theme and amount units. One toman is ten rials. A custom unit name only labels
the stored rial amount; it does not apply a currency conversion.
