# Railway Deployment

Railway hosts only the NIMS Fast Summary helper. It does not log in to NIMS and must never receive NIMS usernames, passwords, captcha, OTP, cookies, or session tokens. Report fetching stays in the logged-in Chrome session or Android WebView session.

## Deploy

1. Create a Railway project.
2. Deploy from this GitHub repository.
3. Use the root `railway.json`; it builds with `helper/Dockerfile`.
4. Set these variables:

```text
NIMS_HELPER_REMOTE_MODE=true
NIMS_HELPER_API_KEY=<strong-random-key>
NIMS_HELPER_CACHE_ENABLED=false
NIMS_HELPER_DISABLE_RAW_LOGS=true
NIMS_HELPER_MAX_BODY_MB=25
NIMS_HELPER_ALLOWED_ORIGINS=
```

Optional:

```text
NIMS_HELPER_RATE_LIMIT_PER_MINUTE=60
NIMS_HELPER_VERSION=0.1.0
```

5. Generate a Railway domain.
6. Test health:

```bash
curl https://<service>.up.railway.app/health
```

Expected response includes:

```json
{
  "ok": true,
  "service": "nims-fast-summary-helper",
  "remote_mode": true,
  "cache_enabled": false,
  "api_key_configured": true,
  "max_body_mb": 25
}
```

7. Configure the Android app or Chrome extension with the Railway helper URL and API key.

## Railway CLI

The Railway web UI is usually simpler. If using the CLI:

```bash
railway login
railway link
railway variables set NIMS_HELPER_REMOTE_MODE=true
railway variables set NIMS_HELPER_API_KEY=<strong-key>
railway variables set NIMS_HELPER_CACHE_ENABLED=false
railway variables set NIMS_HELPER_DISABLE_RAW_LOGS=true
railway up
```

Do not put real API keys in scripts or committed files.

## Troubleshooting

- `401 unauthorized`: wrong or missing `X-NIMS-HELPER-KEY`.
- `413 request body too large`: report payload exceeded `NIMS_HELPER_MAX_BODY_MB`.
- CORS errors in the extension: set an explicit Chrome extension origin in `NIMS_HELPER_ALLOWED_ORIGINS`.
- Session expired: log in again inside Android WebView or Chrome.
- Cold start: retry after Railway wakes the service.

## Security

Railway receives report content for parsing. Remote cache is disabled by default, raw report previews are disabled in remote mode, and protected endpoints require the helper API key. Verify source reports before clinical decisions.
