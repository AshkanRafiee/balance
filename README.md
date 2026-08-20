# Balance

Balance is a small, offline-first Android app that reads supported bank SMS messages locally and shows the latest balance for each recognized bank plus the combined total.

The current release is designed for users in Iran. It recognizes Iranian banks and the Persian SMS formats used by those banks.

## Features

- Local SMS parsing only
- Latest balance per supported bank, without accumulating repeated messages
- Combined total balance
- Rial/Toman display switch
- Persistent masked-balance and currency preferences
- Pull-to-refresh and animated refresh indicator
- No account, cloud service, analytics, or internet permission

## Build

```sh
bash ./gradlew assembleDebug
bash ./gradlew assembleRelease
```

The release build is unsigned when signing variables are absent, which is suitable for source-based distribution builds. For a local signed release, provide the keystore through environment variables:

```sh
BALANCE_STORE_FILE=/path/to/balance-release.jks \
BALANCE_STORE_TYPE=JKS \
BALANCE_STORE_PASSWORD='...' \
BALANCE_KEY_ALIAS='...' \
BALANCE_KEY_PASSWORD='...' \
bash ./gradlew assembleRelease
```

Keep the keystore and passwords outside version control.

## Privacy

Balance requests only `READ_SMS` to read existing messages. It declares no `INTERNET` permission and performs no network requests. SMS and balances remain on the device.

## Source

https://github.com/ashkanrafiee/balance

