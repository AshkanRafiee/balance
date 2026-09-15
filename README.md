# Balance

Balance is a small, offline-first Android app that reads supported bank SMS messages locally and shows the latest balance for each recognized bank plus the combined total.

The current release is designed for users in Iran. It recognizes Iranian banks and the Persian SMS formats used by those banks.

## Features

- Local SMS parsing only — no account, cloud service, analytics, or internet permission
- Latest balance per supported bank, without accumulating repeated messages
- Combined total balance, with the option to exclude individual banks from the total
- Transaction history with deposits and withdrawals, broken down by day, month and year
- Per-bank history: tap any bank card to see just that bank's transactions
- Bank sorting by balance or update time, mirrored in the list and the home-screen widget
- Home-screen widget with the same bank order, totals, and privacy mask as the app
- Password-encrypted backup and restore
- Auto light/dark theme, persistent masked-balance and currency preferences
- Pull-to-refresh and animated refresh indicator
- English and Persian (فارسی) interface, with automatic system-language detection and localized bank names

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

## Privacy

Balance requests only `READ_SMS` to read existing messages. It declares no `INTERNET` permission and performs no network requests. SMS and balances remain on the device.

## Source

https://github.com/ashkanrafiee/balance

