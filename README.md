# SpendSense

SpendSense is an Android personal-finance app that reads transaction SMS messages, extracts credits and debits, categorizes transactions, and presents spending analytics.

## Current version

**0.5.0 — Automatic SMS tracking**

- Kotlin + Jetpack Compose
- Basic dashboard
- Android project structure
- Local-first architecture planned

## Roadmap

- [x] SMS read/receive permissions and scanner
- [x] Automatic background processing of incoming SMS
- [x] Safety-first transaction parser
- [x] Room database and persistent transactions
  - [x] Custom categories
  - [x] Change category for one transaction
  - [x] Apply category rules to future merchant transactions
- [x] Rule-based automatic categorization and merchant learning
- [ ] Transaction list and search
- [ ] Interactive charts
- [ ] Monthly analytics
- [ ] Export to CSV
- [ ] App lock / privacy controls
- [ ] Production APK

## Privacy

The initial design is local-first. Transaction data should remain on the device unless a future version explicitly adds an opt-in sync feature.
