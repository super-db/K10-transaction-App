# Releasing K10 Pay

The `Publish signed K10 Pay APK` GitHub Actions workflow is the only supported production release path.

## Run a release

1. Merge tested app changes to `main`.
2. Open **Actions → Publish signed K10 Pay APK → Run workflow**.
3. Enter a version code greater than the previous release and a semantic version name.
4. Add a short release note and choose whether the update is mandatory.
5. Wait for the workflow to test, sign, verify and upload the APK.

The workflow publishes:

- `https://downloads.k10classes.com/k10-pay/K10-Pay-v<VERSION>.apk`
- `https://downloads.k10classes.com/k10-pay/latest.json`

It also retains the signed APK as a GitHub Actions artifact for 30 days.

## Signing safety

The release keystore and passwords must remain only in GitHub Actions secrets. Never commit the keystore, its Base64 value, or its password. Keep an offline backup of the permanent keystore; losing it prevents future APKs from updating the installed app.

The signing alias is `k10pay`. The R2 bucket is `k10-pay-releases`.
