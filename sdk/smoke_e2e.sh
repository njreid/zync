#!/usr/bin/env bash
# End-to-end SDK smoke test: boot a REAL zyncd and drive it with the real Go SDK, closing
# the SDK -> server -> op-log loop. (The Kotlin SDK's equivalent e2e runs in-JVM as part of
# `:server:test` -> SdkE2ETest, so it needs no external process.)
#
# Usage:  sdk/smoke_e2e.sh          # picks port 18080, a temp DB, and a random bot token
#         ZYNC_E2E_PORT=9000 sdk/smoke_e2e.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${ZYNC_E2E_PORT:-18080}"
TOKEN="smoke-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
WORK="$(mktemp -d)"
LOG="$WORK/server.log"
SRV=""
cleanup() { [ -n "$SRV" ] && kill "$SRV" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> building zyncd (installDist)…"
./gradlew -q :server:installDist

echo "==> starting zyncd on 127.0.0.1:$PORT (db=$WORK/zync.db, token=$TOKEN)…"
ZYNC_PORT="$PORT" ZYNC_DB_PATH="$WORK/zync.db" \
  ZYNC_SERVER_KEY_FILE="$WORK/id.key" ZYNC_NEWZ_KEY_FILE="$WORK/newz.key" \
  ZYNC_BOT_TOKEN="$TOKEN" ZYNC_BOT_ID="smoke" \
  ZYNC_ALLOW_UNAUTHENTICATED_WEB=true \
  server/build/install/server/bin/server >"$LOG" 2>&1 &
SRV=$!

echo "==> running Go e2e (real client → real server)…"
# The Go module root is sdk/go (its own go.mod), so run the test from there.
if ZYNC_E2E_ADDR="http://127.0.0.1:$PORT" ZYNC_BOT_TOKEN="$TOKEN" \
     bash -c 'cd sdk/go && go test -tags e2e -count=1 -v ./...'; then
  echo "==> smoke OK"
else
  echo "==> smoke FAILED; last of server log ($LOG):"
  tail -n 40 "$LOG"
  exit 1
fi
