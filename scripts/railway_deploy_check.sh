#!/usr/bin/env sh
set -eu

if ! command -v railway >/dev/null 2>&1; then
  echo "Railway CLI not found. Install it or use the Railway web UI."
  exit 0
fi

railway status || true

cat <<'EOF'
Required Railway variables:
  NIMS_HELPER_REMOTE_MODE=true
  NIMS_HELPER_API_KEY=<strong-key>
  NIMS_HELPER_CACHE_ENABLED=false
  NIMS_HELPER_DISABLE_RAW_LOGS=true
  NIMS_HELPER_MAX_BODY_MB=25

Deploy:
  railway up
EOF
