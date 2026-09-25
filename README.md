# Balance

Balance is a small, offline-first Android app that reads supported bank SMS messages locally and shows the latest balance for each recognized bank — broken out per account number when the bank states one — plus the combined total.

The current release is designed for users in Iran. It recognizes Iranian banks and the Persian SMS formats used by those banks. A Region setting in the footer switches the app's calendar between the Persian (Jalali) calendar (Iran — the default) and the Gregorian calendar (International), with localized month and weekday names for both; the same menu picks the currency shown next to amounts — Toman by default — plus Rial as stored or any custom currency you type. Only Toman divides the rial figure by ten (the familiar toman amount); every other currency shows the raw number under its own label.

## Features

- Local SMS parsing only — no account, cloud service, analytics, or internet permission
- Latest balance per supported bank — split per account number when the bank SMS state one — without accumulating repeated messages
- Combined total balance, with the option to exclude individual accounts from the total
- Transaction history with deposits and withdrawals, broken down by day, month and year — the Display menu's "History: expand all" option is on by default, so every year, month and day start open; turn it off to show only the current year, month and its days
- History filters by movement type (all, deposits or withdrawals) and by date — today, this month, this year or a custom date range in the active calendar (Persian for Iran, Gregorian for International) — applied to every figure on the screen
- CSV export of the transaction history — exactly what the current view shows (bank, account, movement type and date range filters; the full history when no filter is active) — saved through the system file picker as a UTF-8 CSV with ISO-8601 UTC timestamps, the date in the active calendar, raw rial amounts and the amounts as displayed
- Per-transaction notes: tap any history row to attach a private note to that movement; it follows the transaction in every filter and view and is included in CSV exports and encrypted backups
- Full history from the total card, per-bank history from any bank card, and single-account history by tapping an account row in the list — an account's history shows the account number in its header, and tapping that chip copies the number
- Long-press the total card to copy the combined total, or long-press a bank or account card to copy that balance; long-press the eye to switch auto-mask on or off (balances start hidden on every open), and long-press the lock icon for the lock settings
- Bank sorting by balance or update time, mirrored in the list and the home-screen widget
- Home-screen widget with the same bank order, totals, and privacy mask as the app, in the app's own color theme
- Password-encrypted backup and restore (balances, transaction history and notes); the same Data menu holds a full reset — deleting every balance and transaction (and optionally every note) needs a second confirmation so it can never be triggered by a stray tap
- Auto light/dark theme (following the device or forced either way), persistent masked-balance and currency display preference
- Automatic refresh as bank SMS arrive, plus pull-to-refresh from the top of the bank list or the history screen (full, per-bank or per-account) — a pull there scans the SMS inbox, the balances and the history together
- Data-freshness warning: a bank whose last balance SMS is older than a configurable number of days is highlighted with an amber ring, an amber amount and an "N days" badge on its card, and an amber amount in the home-screen widget, so you see how fresh today's totals are before trusting them
- English and Persian (فارسی) interface, with automatic system-language detection and localized bank names; the footer's single "Display" menu holds its selectors — the Calendar (Persian Jalali / Gregorian) chooses the calendar system, the Currency (Toman / Rial / custom) chooses the value and unit shown next to amounts (Toman divides by ten, others show the raw figure), the Language overrides the interface language, the Theme (system / dark / light) forces the color scheme, the Widget theme (same as app / system / dark / light) points the home-screen widget somewhere else when you want it to, the Stale balance warning (off / 3 / 7 / 14 / 30 days) sets how long a balance can go without an SMS before it is flagged, and the History: expand-all (on / off) opens every year, month and day of the history breakdown instead of only the current one
- Optional in-app lock with a PIN, password or fingerprint, covering the app, the home-screen widget and screenshots/recents, with progressive cooldown delays against wrong-entry guessing
- About screen with direct links to the website, issue tracker, and a link to show the first-run introduction again
- First-run introduction on a fresh install: three short pages explain what Balance reads, how it stays offline and private, and ask for SMS access in context during the introduction rather than with a bare dialog over an empty dashboard — skippable at any point, never shown again afterwards, and re-openable anytime from the About screen
- Report (the Scan diagnostics screen): see which bank SMS parse per bank and which do not — known banks with an unreadable message layout, and senders not recognized yet. Tap any flagged sender to open its messages and pick exactly which ones to send or copy — and mark what is wrong (account, balance or sender-number detection) so the prefilled email tells the maintainer which stage failed. The email is only sent after you approve — feeding straight into the contribution flow »Contributing« below

## Download

Balance can be installed from any of these sources:

- **GitHub** — the fastest way to get the latest updates is installing the signed APK directly from the [GitHub Releases](https://github.com/ashkanrafiee/balance/releases) page. Pair it with [Obtainium](https://obtainium.imranr.dev/) to receive and install updates automatically.
- **F-Droid** — the preferred store edition for users who like app stores; get it from the [F-Droid listing](https://f-droid.org/en/packages/com.ashkanrafiee.balance/).
- **Myket and Cafe Bazaar** — alternative store editions, handy for users less familiar with the options above:
  - [Cafe Bazaar](https://cafebazaar.ir/app/com.ashkanrafiee.balance)
  - [Myket](https://myket.ir/app/com.ashkanrafiee.balance)

## Build

```sh
bash ./gradlew assembleDebug
bash ./gradlew assembleRelease
```

The release build is unsigned when no keystore is configured, which is suitable for source-based distribution builds. For a locally signed release, put a `signing.properties` file next to `build.gradle`:

```properties
storeFile=/path/to/balance-release.jks
storeType=JKS
storePassword=...
keyAlias=...
keyPassword=...
```

Keep the keystore and passwords outside version control (`signing.properties` and `*.jks` are gitignored). The included GitHub Actions workflow restores its own signing `signing.properties` from repository secrets when publishing a release.

### Version numbers

`versionName` follows `1.<minor>.<patch>`. `versionCode` is derived from it as `(10 + minor) * 1000 + patch` (so 1.15.0 → 25000); a fixed release line must keep the same `versionCode` as the tag and the store changelog file name `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Privacy

Balance requests `READ_SMS` to read existing messages, and declares `RECEIVE_BOOT_COMPLETED` plus the fingerprint/BIOMETRIC permissions required for its optional in-app lock. It declares no `INTERNET` permission and performs no network requests. SMS and balances remain on the device.

Revoking `READ_SMS` deletes nothing. Every balance, transaction and note already parsed stays in the encrypted store, and the dashboard, history screen and home-screen widget keep showing it. Because the app can no longer read new bank SMS, the dashboard adds an amber strip under the total card saying the balances may be out of date; tapping it re-requests SMS access (falling back to the app's settings page when the denial is permanent) and refreshes on the spot.

## Reporting a problem or requesting a bank

Is your bank's SMS not recognized, the per-account split wrong, or a balance or
history entry off? We need the bank's exact message format — verbatim, line
breaks and spacing included — plus the sender number, your Android version and
your phone model. You may swap the real numbers for made-up ones of the same
length and format for privacy. The fastest way to hand us every unparsed
message is the **Report** item in the footer:
known banks with an unreadable message layout are highlighted first, then
unknown senders — tap any flagged sender to open its messages, tick the ones to
share, mark what seems wrong (account, balance or sender-number detection), and
send or copy exactly those. Nothing is sent until you confirm in
your mail app.
Otherwise, open an [issue](https://github.com/AshkanRafiee/balance/issues)
or email us; see [CONTRIBUTING.md](CONTRIBUTING.md) for the full checklist.

## Donate

Balance is free and open source, and always will be. If you find it useful, you can support its development with a donation in GRAM (prev. TON). Scan the QR code or tap "open in wallet" on the [donation page](https://balance.ashkanrafiee.com/#donate), or send straight from your wallet to:

```
UQB4goexr3cp0QIdd2_fAJPW9REwZvrRQm-mltr1dMQtV9ig
```

Thank you for your support.

## Source

https://github.com/ashkanrafiee/balance

