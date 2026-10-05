# Balance

Balance is an offline-first Android app for checking bank balances and history without sending financial data away. It reads supported bank SMS messages locally, shows the latest balance for each bank — split by account when the bank states one — and calculates a combined total.

The app is designed for users in Iran, with support for Iranian banks and Persian bank-message formats. Bank SMS is read locally. All saved financial data — including balances, history, payment plans, savings holdings and notes — stays encrypted on the device; backups are password-encrypted. Balance has no account, cloud service, analytics or internet permission.

## Features

- Four bottom tabs: **Balance**, **Payments**, **Savings** and **Settings**. Settings contains **About**, **Display**, **Data** and **Report**.
- Bank balances per account, combined totals, optional account exclusions, automatic SMS refresh and a home-screen widget.
- Transaction history with deposits, withdrawals, bank-stated movement reasons and channels, private notes, filters, search and CSV export. When the bank's balance statements reveal a change that the received movements do not explain, it is shown as an amber **Unaccounted** amount rather than an invented transaction.
- A manual **Payments** planner for one-off payments, subscriptions, debts and fixed loan installments. Plans can repeat weekly, monthly or yearly, end on a date or after a count, and keep a paid/unpaid state for each occurrence. The Payments tab shows included balances minus this month's unpaid plans, with overdue plans listed separately.
- Stopping a plan after today keeps its existing occurrences and paid states. The planner does not make payments, change bank balances or history, or match plans against SMS transactions.
- A manual **Savings** page for gold grams and gold bars (18- or 24-karat), silver by gram, coins (Emami, Bahar Azadi, Half, Rob, Gram or Other) grouped as before 1386 or 1386 and newer, and foreign currencies (USD, EUR, GBP or a custom currency). Enter each quantity and current unit value yourself. Its estimated total is independent of balances, the Payments planner, history and the widget, and prices are never fetched from the network.
- Password-encrypted backups include balances, history, notes, payment plans and their payment states, and savings holdings. Resetting and rescanning bank SMS rebuilds SMS-derived data while preserving manual payment plans, their payment states and savings holdings.
- Persian or English interface, Persian (Jalali) or Gregorian calendar, Toman/Rial/custom currency, light or dark theme, configurable stale-balance warnings and an optional PIN, password or fingerprint lock.
- A first-run introduction explains local processing and SMS access. It can be opened again from **Settings > About**. **Settings > Report** lets you review selected bank messages and share or copy only what you approve when a bank or parsed field is wrong.

## Download

Balance can be installed from any of these sources:

- **GitHub** — install the signed APK from the [GitHub Releases](https://github.com/ashkanrafiee/balance/releases) page. Pair it with [Obtainium](https://obtainium.imranr.dev/) to receive and install updates automatically.
- **F-Droid** — get the store edition from the [F-Droid listing](https://f-droid.org/en/packages/com.ashkanrafiee.balance/).
- **Myket and Cafe Bazaar** — alternative store editions:
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

Balance requests `READ_SMS` to read existing messages and declares the biometric permissions needed for its optional lock. It declares no `INTERNET` permission and performs no network requests. Revoking SMS access deletes nothing: already parsed balances, history and notes remain available, while new bank messages can no longer be read until access is restored.

## Reporting a problem or requesting a bank

If a bank's SMS is not recognized, an account split is wrong, or a balance or history entry is incorrect, open **Settings > Report**. Select only the messages you want to share, mark the field that seems wrong, and then approve sending or copying them. You can replace real numbers with made-up values of the same length and format for privacy. You can also open an [issue](https://github.com/AshkanRafiee/balance/issues) or email us; see [CONTRIBUTING.md](CONTRIBUTING.md) for the full checklist.

## Donate

Balance is free and open source. If you find it useful, you can support its development with a donation in GRAM (prev. TON). Scan the QR code or tap "open in wallet" on the [donation page](https://balance.ashkanrafiee.com/#donate), or send straight from your wallet to:

```
UQB4goexr3cp0QIdd2_fAJPW9REwZvrRQm-mltr1dMQtV9ig
```

Thank you for your support.

## Source

https://github.com/ashkanrafiee/balance
