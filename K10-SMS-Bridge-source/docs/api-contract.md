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

Suggested responses:

- `200` or `201`: `{ "status": "success" }`
- `409`: `{ "status": "duplicate" }`
- `400`/`422`: `{ "status": "rejected", "message": "..." }`
- `401`/`403`: authentication failure
- `5xx`: temporary failure; the app retries with WorkManager

The backend should enforce a unique constraint on `duplicate_key` as a second layer of duplicate protection.

## Health check

`GET /api/health` returns any `2xx` response when the device credential and service are available.
