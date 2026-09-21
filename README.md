# Balance

Balance is a small, offline-first Android app that reads supported bank SMS messages locally and shows the latest balance for each recognized bank — broken out per account number when the bank states one — plus the combined total.

The current release is designed for users in Iran. It recognizes Iranian banks and the Persian SMS formats used by those banks. A Region setting in the footer switches the app's calendar between the Persian (Jalali) calendar (Iran) and the Gregorian calendar (International), with localized month and weekday names for both.

## Features

- Local SMS parsing only — no account, cloud service, analytics, or internet permission
- Latest balance per supported bank — split per account number when the bank SMS state one — without accumulating repeated messages
- Combined total balance, with the option to exclude individual accounts from the total
- Transaction history with deposits and withdrawals, broken down by day, month and year
- History filters by movement type (all, deposits or withdrawals) and by date — today, this month, this year or a custom date range in the active calendar (Persian for Iran, Gregorian for International) — applied to every figure on the screen
- Full history from the total card, per-bank history from any bank card, and single-account history by tapping an account row in the list
- Long-press the total card to copy the combined total, or long-press a bank or account card to copy that balance; long-press the eye to switch auto-mask on or off (balances start hidden on every open), and long-press the lock icon for the lock settings
- Bank sorting by balance or update time, mirrored in the list and the home-screen widget
- Home-screen widget with the same bank order, totals, and privacy mask as the app
- Password-encrypted backup and restore
- Auto light/dark theme, persistent masked-balance and Toman display mode preference
- Automatic refresh as bank SMS arrive, plus pull-to-refresh
- English and Persian (فارسی) interface, with automatic system-language detection and localized bank names; the footer's single "Region & Language" menu holds both selectors — the Region (Iran / International) chooses the calendar system, the Language overrides the interface language
- Optional in-app lock with a PIN, password or fingerprint, covering the app, the home-screen widget and screenshots/recents, with progressive cooldown delays against wrong-entry guessing
- About screen with direct links to the website and issue tracker
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

`versionName` follows `1.<minor>.<patch>`. `versionCode` is derived from it as `(10 + minor) * 1000 + patch` (so 1.14.0 → 24000); a fixed release line must keep the same `versionCode` as the tag and the store changelog file name `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Privacy

Balance requests `READ_SMS` to read existing messages, and declares `RECEIVE_BOOT_COMPLETED` plus the fingerprint/BIOMETRIC permissions required for its optional in-app lock. It declares no `INTERNET` permission and performs no network requests. SMS and balances remain on the device.

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

