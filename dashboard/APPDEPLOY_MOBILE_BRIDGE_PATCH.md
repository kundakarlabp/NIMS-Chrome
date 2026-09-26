# AppDeploy dashboard → Android NIMS relay integration

This folder preserves the deploy-ready client module for the existing AppDeploy NIMS Results Dashboard.

## Intended dashboard behavior

1. Pair once with the Android NIMS Results app using an 8-character one-time pairing code.
2. Store only the restricted requester token in browser localStorage.
3. CR entry calls `requestCrFromMobileAgent()`.
4. The CR itself is encrypted to the Android device public key before leaving the dashboard.
5. The Android agent retrieves/parses NIMS data using its local authenticated session.
6. Returned clinical JSON is encrypted to a per-request ephemeral browser key.
7. If the NIMS session expired, the job moves to `auth_required` and the dashboard displays:
   **Authentication required on phone — enter the fresh CAPTCHA and tap Authenticate.**
8. The same job resumes after authentication. No JSON copy/paste is involved.

## App.tsx changes

- Replace the no-bridge `Use ChatGPT retrieval` fallback with a mobile-agent path.
- Add one-time **Pair Android agent** UI when `loadMobilePairing()` is null.
- On CR submit:
  - prefer local desktop browser bridge if present;
  - otherwise use the paired Android relay;
  - normalize the returned payload using the existing `normalizeBundle()`.
- Treat `auth_required` as a non-error waiting state.
- Keep JSON import/paste only under Advanced recovery, not the routine workflow.
- Do not store NIMS credentials, cookies, passwords, CAPTCHA values, or session tokens in the dashboard.

## Backend

Supabase Edge Function `nims-chat-bridge` v4 already supports:
- device registration
- one-time pairing
- restricted requester tokens
- encrypted job creation/status
- `auth_required`
- resume after authentication
- encrypted result return
- short TTL and cleanup

The AppDeploy frontend publication is currently blocked only by AppDeploy's daily deployment-credit floor.
