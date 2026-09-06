# Balance

Balance is a small, offline-first Android app that reads supported bank SMS messages locally and shows the latest balance for each recognized bank plus the combined total.

The current release is designed for users in Iran. It recognizes Iranian banks and the Persian SMS formats used by those banks.

## Features

- Local SMS parsing only
- Latest balance per supported bank, without accumulating repeated messages
- Combined total balance
- Persistent masked-balance and currency preferences
- Pull-to-refresh and animated refresh indicator
- English and Persian (فارسی) interface, with automatic system-language detection and localized bank names
- No account, cloud service, analytics, or internet permission

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

