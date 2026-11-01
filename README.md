# SpendSense

SpendSense is an Android personal-finance app that reads transaction SMS messages, extracts credits and debits, categorizes transactions, and presents spending analytics.

## Current version

**1.0.0 — First stable release**

- Kotlin + Jetpack Compose
- Local-first personal finance tracking
- Automatic background SMS transaction detection
- Safety-first transaction parsing
- Room-backed persistent transaction history
- Custom categories and merchant category rules
- Transaction editing, deletion, account editing and manual transactions
- Calendar-based home dashboard
- Activity search, filters and sorting
- Spending and category analytics
- Monthly and category budgets
- Recurring-payment insights
- Transfer-aware spending calculations
- Branded bank/account badges
- Adaptive launcher icon

## Release status

SpendSense 1.0 is intended for real-world personal use. The current focus after release is observation and feedback: use the app normally, report incorrect SMS classifications, missing transactions, UI issues, calculation errors, or performance problems, and they can be addressed in subsequent releases.

## Roadmap

- [x] SMS read/receive permissions and scanner
- [x] Automatic background processing of incoming SMS
- [x] Safety-first transaction parser
- [x] Room database and persistent transactions
- [x] Custom categories
- [x] Change category for one transaction
- [x] Apply category rules to future merchant transactions
- [x] Rule-based automatic categorization and merchant learning
- [x] Transaction list, search, filters and sorting
- [x] Interactive charts
- [x] Monthly analytics
- [x] Monthly and category budgets
- [x] Recurring-payment insights
- [x] Transfer classification
- [x] Production launcher icon
- [ ] Export to CSV
- [ ] App lock / privacy controls
- [ ] Optional cloud sync

## Privacy

The initial design is local-first. Transaction data should remain on the device unless a future version explicitly adds an opt-in sync feature.

## Build

From the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

`app\build\outputs\apk\debug\app-debug.apk`
