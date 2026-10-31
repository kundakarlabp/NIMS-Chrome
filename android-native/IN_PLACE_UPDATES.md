# NIMS Results — in-place Android updates

## Non-negotiable requirements
- Keep `applicationId=org.kundakarlab.nimsresults` unchanged.
- Increase `versionCode` monotonically (v1.0.39 = 40).
- Sign every installed update with the exact current installed app signing identity, or a platform-accepted signing lineage. A fresh CI debug key is **not** compatible.
- Do not uninstall or clear storage to resolve an installation conflict: the encrypted clinical snapshot and Android Keystore credentials will be lost.

## Determine the current identity
The current installed APK now displays the public certificate SHA-256 in **Copy diagnostics** as `app.signingCertSha256`. For older builds, inspect the installed APK with the Android SDK's `apksigner verify --print-certs`, or retrieve a copy of the installed APK and inspect its signer. This reveals the certificate only—not the private key.

## Configure a reproducible signed APK
Retrieve the **original** keystore and its private key used for the app currently on the device. Store it securely outside Git, and populate these GitHub Actions secrets:
- `NIMS_SIGNING_KEYSTORE_B64` — base64 of the original JKS/PKCS12 keystore
- `NIMS_SIGNING_STORE_PASSWORD`
- `NIMS_SIGNING_KEY_ALIAS`
- `NIMS_SIGNING_KEY_PASSWORD`
- `NIMS_INSTALLED_CERT_SHA256` — the exact installed certificate fingerprint, with or without colons

CI will restore that keystore, sign the APK with the original key and compare the resulting certificate SHA-256 with `NIMS_INSTALLED_CERT_SHA256`. A mismatch fails the build.

Without these settings, CI deliberately produces an artifact named `nims-results-debug-NOT-upgrade-verified`. It is **not** suitable for distribution as an update.

Even a matched certificate requires a real Android installation trial to verify data preservation and successful upgrade. If the original private key cannot be recovered, this code cannot manufacture an install-compatible APK; the certificate fingerprint alone is insufficient.

## Clinical provenance
Use NIMS authenticated structured values first. The PDF source index stays separate until patient CR and a compatible specimen/lab ID, investigation type, and report stage establish an exact relationship. The source-index date and Custom Sheet result-date column never become specimen collection or reporting timestamps by inference.
