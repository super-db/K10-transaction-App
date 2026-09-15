# K10 SMS Bridge

K10 SMS Bridge is a deliberately small Android app that listens for Slice credit SMS messages for one configured account, parses eligible transactions on-device, and sends only those transactions to a configured K10 backend. It is not a general SMS reader or finance dashboard.

## Privacy and safety model

Every message must pass four local checks before it can be stored or uploaded:

1. The exact sender is in the active list and is also locally recognized as a Slice-branded financial sender.
2. The body contains an immutable credit signal and no immutable OTP, debit, withdrawal, spending, loan, or promotion signal.
3. The configured four account digits are present.
4. A validated banking amount template and date template match.

Remote rules cannot turn these checks off. Remote extraction patterns are bounded literal templates, not arbitrary regular expressions. Invalid, oversized, unversioned, or older configurations are rejected. The last-known-good rules are kept in private app storage for offline operation.

Manual **Search SMS** queries the Android inbox locally. Its default is eligible-only. If that toggle is disabled, the app still shows only Slice sender + configured account + credit candidates; unrelated SMS never appear. Search results are not sent anywhere. A user must explicitly import a result, and the normal eligibility and duplicate checks run again.

## Project structure

- `sms/` — immutable safety gate, parser, broadcast receiver, and local inbox recovery search
- `rules/` — versioned rule schema, validator, and last-known-good store
- `data/` — Room transaction queue and duplicate-key constraint
- `sync/` — HTTPS API client and WorkManager rules/upload worker
- `security/` — Android Keystore-backed token encryption
- `ui/` — Compose main, transaction log, search, and settings screens
- `docs/api-contract.md` — sample backend contract

## Build and run

1. Open this folder in Android Studio.
2. Use Android Studio's embedded JDK 17 and install Android SDK 35.
3. Sync Gradle, then run the `app` configuration on an Android 8.0 (API 26) or newer device.
4. Grant Receive SMS and Read SMS when prompted. Read SMS is used only for explicit manual recovery searches.
5. In Settings, enter an HTTPS backend base URL and a device token, enable the service, save, then test the connection.

Some Play Store distributions restrict SMS permissions. This private-purpose bridge must satisfy the applicable default-handler/exception and declaration requirements if distributed through Google Play. Direct enterprise/private deployment still requires the user to grant Android permissions.

## Tests

Run the local unit tests from Android Studio or with:

```text
./gradlew testDebugUnitTest
```

Tests cover the required exact sample, OTP/debit/wrong-account rejection, unapproved sender rejection, deterministic duplicate keys, year-boundary date parsing, and immutable remote-rule safety.

## Operations

- WorkManager runs every six hours when connected, fetches matching rules, and retries pending/failed uploads.
- Incoming eligible SMS also enqueue an immediate connected sync.
- Room persists the queue across restarts and uniquely indexes the deterministic SHA-256 duplicate key.
- Cleartext HTTP is disabled in the manifest and the client independently rejects non-HTTPS URLs.
- The raw message is retained only after it has passed all eligibility checks. API tokens and raw SMS are never logged.

See [the API contract](docs/api-contract.md) and [sample rules](sample-rules-v1.3.json) when implementing the K10 backend.
