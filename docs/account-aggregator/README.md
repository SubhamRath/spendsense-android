# Account Aggregator integration — sandbox foundation

Status: discovery and sandbox planning only. No live bank accounts are connected.

## Goal

Add consent-based, read-only bank transaction ingestion to SpendSense so bank data can supplement missing SMS alerts. SMS remains a secondary source. Both sources must feed the same transaction-normalisation and deduplication pipeline.

## Regulatory and access gate

The Account Aggregator ecosystem is not an unrestricted consumer API.

- An FIU must be an entity registered with and regulated by an Indian financial-sector regulator (RBI, SEBI, IRDAI or PFRDA), as described by Sahamati.
- Setu's production onboarding checklist requires the FIU's regulatory licence and certification.
- A personal Android app by itself should not assume it can become a production FIU.
- Sandbox/UAT testing must use only provider-supplied dummy accounts and dummy data. Do not attempt to link real bank accounts or use replicas of real accounts in UAT.
- Before any production work, obtain written confirmation from Setu about an eligible FIU partner / regulated-entity arrangement for this personal project.

This means the first milestone is a sandbox protocol proof-of-concept, not live bank connectivity.

## Provider documentation

- Setu AA quickstart: https://docs.setu.co/data/account-aggregator/v1/get-started
- Setu consent flow: https://docs.setu.co/data/account-aggregator/api-integration/consent-flow
- Setu production onboarding: https://docs.setu.co/data/account-aggregator/licenses-and-go-live/go-live
- Sahamati ecosystem onboarding and sandbox guidance: https://sahamati.org.in/how-to-join-the-account-aggregator-network-to-share-and-access-financial-data/

## Intended architecture

```
SpendSense Android
  |  authenticated app session; no Setu secret in APK
  v
SpendSense AA backend (to be selected / deployed)
  |  Setu API authentication, request signing, webhook validation
  v
Setu AA gateway + licensed AA consent experience
  |
  v
Dummy FIP data in sandbox / consented production FIP data only after eligibility
  |
  v
Normalised transaction feed
  |
  v
Existing Room transaction repository
```

The Android application must never contain Setu API tokens, private signing keys, client secrets, webhook secrets, or bank credentials. These belong only in a server-side secret store.

## Sandbox flow to prove

1. Create a sandbox FIU integration with Setu and obtain sandbox-specific configuration.
2. Configure a backend notification endpoint and the app's redirect/deep-link handling.
3. Create a consent request with a narrowly scoped purpose, FI type, date range and fetch frequency.
4. Open the returned Setu consent URL in a browser/custom tab (or provider-approved embedded flow).
5. Handle consent status notifications on the backend; verify webhook authenticity as specified by Setu.
6. Create a data session for an approved consent and fetch the resulting dummy financial information.
7. Decrypt and parse data only in the trusted backend boundary, following Setu/ReBIT specifications.
8. Return only the minimum normalised transaction fields needed by the Android app.
9. Deduplicate AA and SMS records without treating SMS message IDs as bank transaction IDs.
10. Add consent status, last successful fetch, data range and revoke/disconnect states to the app.

Do not hard-code request/response shapes from examples. Generate the client and validation from the current Setu API specification supplied for the sandbox account.

## Transaction reconciliation rules

- Prefer bank-provided transaction identifiers / UTR / reference when present.
- Otherwise use a stable fingerprint of account identity (non-display internal identifier), timestamp, amount, direction and normalised description.
- Keep source provenance (AA, SMS, manual) and source identifiers.
- A matched SMS and AA record must become one transaction with both source references retained.
- Do not overwrite a user-edited category or merchant when a later sync arrives.
- Reversals/refunds and pending/posted states must be modelled explicitly; do not silently convert pending entries into posted transactions.

## Security baseline

- No collection or storage of bank login passwords, PINs or OTPs.
- Read-only data access; no payment initiation.
- TLS for every network hop.
- Server-side secrets only; rotate and restrict credentials.
- Validate webhook signatures and replay protection.
- Encrypt sensitive data at rest; Android Keystore-backed key management for device-held encryption keys.
- Minimise retention; support consent revocation and deletion.
- Redact account numbers, PAN, mobile numbers and transaction payloads from logs and crash reports.
- Never send financial data to analytics, advertising or general-purpose logging services.
- Keep sandbox and production credentials, databases and endpoints fully separated.

## Decisions still required

- Setu must confirm whether it can support a personal-use project and identify the required eligible FIU partner. Production cannot proceed without this.
- Select a backend host and runtime. Do not put provider credentials in the Android app.
- Confirm whether the Setu sandbox access granted to this project includes the current AA APIs and dummy FIP data.
- Confirm refresh frequency and whether recurring fetches are permitted by the approved consent artefact.

## Acceptance criteria for the sandbox milestone

- Consent request can be created using sandbox credentials from the backend.
- User can complete a dummy consent journey.
- Consent status notification is verified.
- Dummy data fetch completes and is parsed.
- No secrets or real personal financial data appear in the APK, Git history, logs or analytics.
- Existing SMS ingestion continues to work unchanged.
- AA/SMS duplicate examples reconcile to one transaction.
