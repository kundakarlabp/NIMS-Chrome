# AGENTS.md

## Product boundary

Maintain one desktop NIMS Results Dashboard connector:

Dashboard → authenticated NIMS session → Investigation Trends → Custom Sheet GETMETABOLICDATA JSON → normalized dashboard bundle.

Manual login/CAPTCHA is the only authentication fallback.

## Do not reintroduce

- CR-wise Results List / View Report parsing as the normal results path
- direct-report mapping/discovery
- PDF/OCR/helper parsing for structured values
- Android/APK/mobile relay
- side-panel/manual-analysis UI
- CAPTCHA solving

The HBIMS reportList endpoint may be used only for optional report metadata/source links. It must never block the structured bulk results path.
