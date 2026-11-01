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

## Only publish signed upgrade candidates through the release workflow

1. Recover the exact existing installation's signing keystore/private key. The certificate
   fingerprint from `Copy diagnostics` is **public proof only** and cannot sign an update.
2. In GitHub repository Settings → Secrets and variables → Actions, configure the five
   secrets listed above. Keep the keystore and its recovery backup out of Git and out
   of ChatGPT attachments. Protect access to release workflows and the GitHub repository.
3. On the repository's **Actions** page, manually run
   **Android signed in-place update (original key only)** on `main`. If any secret
   is absent or the signer differs from the installed certificate, this job
   **fails without uploading a release APK**.
4. Download the `ORIGINAL-SIGNER-VERIFIED` artifact. Verify the artifact's
   `APK_BUILD_IDENTITY.txt` fields, especially package, versionCode and
   signerSha256. This is a certificate-match check, **not an on-device upgrade test**.
5. Install the signed APK over the current app *without uninstalling or clearing storage*.
   Confirm login/session, patient shelf, encrypted snapshots, results, and PDFs remain
   available. Roll back by correcting and releasing a *higher* versionCode using
   the **same original signer**; Android does not normally accept version downgrades.

**If the old private key is lost:** Android provides no safe versionCode or
manifest change that can permit a normal in-place update signed by a new unrelated
key. Do not attempt a fresh-key APK over the patient-data-bearing installation.
A new persistent signing key could support future upgrades only after an
independently approved, patient-data-safe migration to a new baseline. An export
of reports is not necessarily an export of encrypted application secrets.

**Normal `CI` debug APKs are disposable test builds.** The artifacts named
`NOT-upgrade-verified` are not production distribution files, even when
unit tests, lint and APK archive verification pass.

**Versioning:** v1.0.49 uses versionCode 10049 to avoid collisions with
older historical version codes. All subsequent releases must increment
versionCode and preserve `applicationId=org.kundakarlab.nimsresults`.
Signing identity—not the versionName or file name—is the upgrade requirement.

## Clinical provenance
Use NIMS authenticated structured values first. The PDF source index stays separate until patient CR and a compatible specimen/lab ID, investigation type, and report stage establish an exact relationship. The source-index date and Custom Sheet result-date column never become specimen collection or reporting timestamps by inference.
