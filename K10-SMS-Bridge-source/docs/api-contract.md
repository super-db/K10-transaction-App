# K10 SMS Bridge API contract

All endpoints must use HTTPS. Requests send `Authorization: Bearer <device-token>` and accept JSON. The device token is stored with an Android Keystore AES key and is never logged.

## Fetch matching rules

`GET /api/sms-matching-rules`

Successful response (`200`):

```json
{
  "version": "v1.3",
  "bank_name": "Slice",
  "allowed_sender_ids": ["SLICE", "XX-SLICE"],
  "account_last4": "7972",
  "required_keywords": ["received"],
  "optional_keywords": ["via UPI"],
  "excluded_keywords": ["debited", "OTP"],
  "amount_patterns": ["Rs {amount} received", "₹{amount} received"],
  "payer_extraction_patterns": ["from {payer} via", "from {payer}."],
  "date_extraction_patterns": ["on {date} from"],
  "payment_method_patterns": ["via {payment_method}"],
  "enabled": true
}
```

Patterns are literal templates, not regular expressions. Only the documented placeholder in each pattern is variable. A response is applied atomically only after size, schema, version, sender, account, credit, exclusion, and template validation. Older or invalid responses are ignored and the last-known-good configuration remains active.

## Submit eligible transaction

`POST /api/slice-transactions`

```json
{
  "unique_local_id": "f364cfae-bbe6-4ce1-990c-abdc2e81d346",
  "duplicate_key": "sha256-hex-value",
  "payer_name": "RUBUL Sonowal",
  "amount": 2000.0,
  "currency": "INR",
  "transaction_date": "2026-09-02",
  "sms_received_timestamp": 1788705900000,
  "account_last4": "7972",
  "payment_method": "UPI",
  "sender_id": "SLICE",
  "raw_eligible_sms": "Rs 2000 received on in A/c xx7972 on 2 Sep from RUBUL Sonowal via UPI.",
  "source": "slice_sms",
  "created_at": 1788705900000
}
```

Required responses:

- `200` or `201`: `{ "status": "success", "transaction_id": "server-id" }`
- `409`: `{ "status": "duplicate", "transaction_id": "existing-server-id" }`
- `400`/`422`: `{ "status": "rejected", "message": "..." }`
- `401`/`403`: authentication failure
- `5xx`: temporary failure; the app retries with WorkManager

The backend should enforce a unique constraint on `duplicate_key` as a second layer of duplicate protection.
It must also persist `unique_local_id` and return the durable server transaction ID. The same values should be included as `clientTransactionId` and `duplicateKey` by `GET /api/mobile/transactions` so the Developer app can merge local and cloud rows without duplicates.

The response must be sent only after the D1 write is committed. Firebase fan-out may be queued after that commit, but it must use a durable notification-outbox record so a transient Firebase failure can be retried.
The Firebase fan-out must exclude the registered `bridgeDevice` that uploaded the transaction; that phone already produced the local voice and Android alert. Staff devices receive the server push once, keyed by `transaction_id`.

## Health check

`GET /api/health` returns any `2xx` response when the device credential and service are available.

## Full system diagnostics

`POST /api/k10-pay/diagnostics` uses the same SMS Bridge bearer token. It performs non-destructive checks, queues silent Firebase probes, and never returns credentials, raw SMS, balances, passwords, or Firebase tokens.

```json
{
  "components": [
    { "key": "upload", "status": "working", "detail": "Transaction ingestion route is active" },
    { "key": "d1", "status": "working", "detail": "Read/write probe completed" },
    { "key": "firebase", "status": "working", "detail": "Firebase credentials accepted" },
    { "key": "device_registration", "status": "working", "detail": "3 active staff devices" },
    { "key": "notification_outbox", "status": "pending", "detail": "1 delivery awaiting retry", "suggestion": "Retry the notification outbox" }
  ]
}
```

The D1 write probe should use a dedicated diagnostics table or an atomic write/delete batch so it cannot create a Slice transaction. Firebase should validate credentials and optionally send a silent nonce to registered devices; acknowledgements belong in the diagnostics/outbox table, not the transaction list.

Staff apps acknowledge each Firebase data message with `POST /api/mobile/deliveries` containing `deviceId`, `deliveryId`, `type`, and `receivedAt`. The diagnostics endpoint uses these acknowledgements to distinguish “Firebase accepted the push” from “the staff phone actually received it.”
