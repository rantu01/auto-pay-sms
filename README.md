# Recive Payment — bKash SMS auto-receiver (separate app)

Runs on the phone that receives bKash payments. Detects incoming bKash
"money received" SMS in the background, parses it, and uploads it to the
existing backend → MongoDB (`received_payments`). Offline-safe: pending
payments are kept in a local SQLite outbox and retried.

Existing projects (`admin-app`, `backend`, `OTP-Android-App`) are untouched
except for the additive changes listed below.

## Structure

```
Recive payment/
  settings.gradle  build.gradle  gradle.properties
  app/build.gradle  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/otpfetch/recvpay/
    BkashParser.java        # robust received-payment parser
    SmsReceiver.java        # SMS_RECEIVED broadcast (no polling)
    BootReceiver.java       # retry after reboot
    SyncService.java        # foreground upload + retry
    PendingStore.java       # SQLite outbox (offline persistence)
    RecvApi.java            # POST /api/received-payments (x-api-key)
    RecvPrefs.java          # base URL + API key + device label
    MainActivity.java       # dashboard + history + settings + test-SMS
    PaymentDetailActivity.java
  app/src/main/res/layout/activity_main.xml
  app/src/test/java/.../BkashParserTest.java  # 7 JUnit cases
```

## Android permissions (why each)

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Core: system delivers `SMS_RECEIVED` to `SmsReceiver` in background |
| `READ_SMS` | Read the message body that the broadcast carries |
| `INTERNET` | Upload parsed payments to backend |
| `ACCESS_NETWORK_STATE` | Detect offline → keep in outbox; retry when back |
| `RECEIVE_BOOT_COMPLETED` | Retry pending uploads after reboot (`BootReceiver`) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | `SyncService` uploads survive background restrictions (Android 8+) |
| `POST_NOTIFICATIONS` | Sync status notification (Android 13+) |

No MongoDB URI/credentials in the app. Only backend URL + `RECV_API_KEY`.

## Setup

1. **Backend URL + auth** — open the app → Settings:
   - Backend URL: `https://YOUR-BACKEND` (emulator: `http://10.0.2.2:4000`, LAN device: `http://192.168.x.x:4000`)
   - API key: value of `RECV_API_KEY` in `backend/.env` (or admin JWT fallback)
   - Device label: e.g. `Payment-Phone-1`
2. **MongoDB** — no phone-side config. Backend uses existing `MONGODB_URI` / `MONGO_DB_NAME`.
3. **Build APK**:
   - Open `D:\KALI\OTP\Recive payment` in Android Studio → Build → Build APK(s), or `./gradlew :app:assembleDebug`
   - `app/build/outputs/apk/debug/app-debug.apk`
4. **Install on payment phone** — copy APK, install, then grant:
   - SMS (Allow — required for detection)
   - Notifications (Allow — sync status)
   - Disable battery optimization for the app (so background uploads aren't killed)
5. **Test with real SMS**:
   - Send bKash money to this phone's wallet → SMS arrives → app queues + syncs automatically
   - Or use in-app **Test SMS** button (pastes the example SMS, parses + queues + uploads)
   - Dashboard shows detected/synced/pending counts; tap a row for full SMS + fields

## Parser

`BkashParser` extracts amount, sender, fee, balance, trxId, date (→ `YYYY-MM-DD`),
time (`HH:MM`), and rejects cash-out / send-money / recharge / promo / OTP /
security messages. Tolerant to case, extra spaces, `Tk/BDT`, comma amounts,
`TrxID/Trx ID/TrxNo`, `DD/MM/YYYY` with `/ - .`, 2/4-digit years, AM/PM.

## Backend changes (all additive, existing APIs untouched)

- `backend/src/models.js` — new `OtpReceivedPayment` model, collection
  `received_payments`, unique index on `trxIdNorm` + indexes on
  `transactionDate`, `sender`, `createdAt`, `{status,id}`.
- `backend/src/store.js` — `validateReceivedPayload`, `createReceivedPayment`
  (duplicate → `{duplicate:true}`), `findReceivedByTrx`, `listReceivedPayments`,
  `verifyReceivedPayment` (record must exist + amount must match), `setReceivedStatus`.
- `backend/src/app.js` — new routes (all under `requireMongo`):
  - `POST /api/received-payments` (auth: `x-api-key == RECV_API_KEY` or admin JWT)
  - `GET /api/received-payments` (admin, search/filter/pagination)
  - `GET /api/received-payments/:trxId` (admin)
  - `POST /api/received-payments/verify` (admin: TrxID must exist, amount checked)
  - `PATCH /api/received-payments/:trxId/status` (admin)
- `backend/.env.example` (+ `.env`) — new `RECV_API_KEY`.
- `backend/src/recvpay-test.js` — 14-case suite (22 assertions incl. live Mongo
  duplicate + verify). Run: `node src/recvpay-test.js`.

## Admin app changes (additive)

- `admin-app/.../AdminMainActivity.java` — new `recvpay` tab: received-payments
  list (search TrxID/sender, pending/all), verify box (TrxID + expected amount),
  copy TrxID, mark used/rejected. Existing tabs untouched.

## MongoDB

- Collection: `received_payments`. Schema: `id` (numeric API id), `amount`,
  `sender`, `fee`, `balance`, `trxId`, `trxIdNorm` (upper, no spaces/dashes),
  `transactionDate` (`YYYY-MM-DD`), `transactionTime` (`HH:MM`),
  `originalMessage`, `receivedAt`, `deviceInfo`, `source`, `status`
  (`pending|verified|used|rejected|duplicate`), `matchedPaymentId`,
  `verifiedBy`, `verifiedAt`, `createdAt`, `updatedAt`.
- Indexes: `trxIdNorm` **unique** (duplicate protection — backend is final
  authority; race-safe via unique index + pre-check), `transactionDate`,
  `sender`, `createdAt`, `{status,id}`.
- Duplicate response: `{success:true, duplicate:true, message:"Transaction already exists"}`.

## Verification flow

```
Customer sends money → bKash SMS → SmsReceiver → BkashParser →
PendingStore (SQLite) → SyncService → POST /api/received-payments →
MongoDB received_payments (unique trxIdNorm) → duplicate? return duplicate:true
Customer submits TrxID → POST /api/received-payments/verify {trxId, amount} →
  record must exist + amount must match → status pending→verified →
  admin marks used when the package payment is approved
```

Test results: `node src/recvpay-test.js` → 22 passed (incl. live Mongo
insert/duplicate/lookup/verify). `node src/smoke.js` → SMOKE OK (existing
flows unbroken). E2E HTTP check: POST 201 → re-POST 200 duplicate:true →
GET/verify OK → unauth 401.
