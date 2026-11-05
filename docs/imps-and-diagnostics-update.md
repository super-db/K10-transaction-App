# IMPS metadata and opening diagnostics

Adds extraction for `from NAME (IMPS Ref: ...)`, covering the two reported Slice messages and wrapped names. UPI parsing/account safeguards stay in place. Improved metadata uses the legacy payer input for duplicate-key calculation, so rescanning an old unknown IMPS SMS retains its existing key.

On foreground ViewModel creation, existing local unknown payer/method fields are repaired with a compare-and-set metadata UPDATE. It never changes local IDs, duplicate keys, amounts, upload status or attempts and never queues an upload. Server repair is in the companion Finance PR, not in POST ingestion.

History GETs for the same range are coalesced. Transaction events received during a GET schedule a follow-up read so a just-uploaded payment is not missed. Only history jobs are invalidated on logout. SMS receiver, wake-window upload, coordinator, retry constraints, FCM service, notification dispatcher, periodic/startup work and signing config are untouched.

Diagnostics shows a persisted last-opening summary, stage durations, backend Server-Timing if supplied, and grouped opening/SMS/upload/notification events. Copy and Share include the summary, checks and all sections without raw SMS or tokens. Backend recent-vs-old notification failure records and affected devices are in the companion report.

Interactive reconciliation reports checked/total through WorkManager progress. Waiting in the foreground stops after two minutes with an explicit background-continuation message; the worker is not cancelled and upload/retry rules are unchanged. The worker's only modification is best-effort progress reporting for interactive runs.

Verification: Android CI runs existing and added parser/diagnostic unit tests and builds the debug APK. Supplied IMPS examples verify extracted payer/method and the exact legacy duplicate-key hash. Physical-device SMS/FCM timing still needs a post-install smoke test. Release publishing must use the existing permanent keystore and a higher workflow version code; no signing key is generated here.
