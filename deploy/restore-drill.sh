#!/bin/sh
# zync litestream restore drill: prove the S3 replica is actually restorable,
# WITHOUT touching the live database. Restores to a temp dir, integrity-checks,
# and fails if the replica lags the live DB by more than ZYNC_DRILL_MAX_LAG ops.
# Run weekly via restore-drill.timer (or ad hoc before trusting a new box).
# Exit 0 = replica restorable and fresh; non-zero = page yourself.
set -eu

CONFIG=${ZYNC_LITESTREAM_CONFIG:-/etc/zync/litestream.yml}
DB=${ZYNC_DB_PATH:-/opt/zync/data/zync.db}
MAX_LAG=${ZYNC_DRILL_MAX_LAG:-100}

WORK=$(mktemp -d /tmp/zync-restore-drill.XXXXXX)
trap 'rm -rf "$WORK"' EXIT

litestream restore -config "$CONFIG" -o "$WORK/restored.db" "$DB"

[ "$(sqlite3 "$WORK/restored.db" 'PRAGMA integrity_check;')" = "ok" ] || {
  echo "FAIL: restored DB failed integrity_check" >&2
  exit 1
}

OPS=$(sqlite3 "$WORK/restored.db" "SELECT COUNT(*) FROM op_log;")
HEAD=$(sqlite3 "$WORK/restored.db" "SELECT COALESCE(MAX(seq), 0) FROM op_log;")
VERSION=$(sqlite3 "$WORK/restored.db" "PRAGMA user_version;")

# Freshness: compare against the live DB when present (read-only; WAL-safe).
if [ -f "$DB" ]; then
  LIVE_HEAD=$(sqlite3 "file:$DB?mode=ro" "SELECT COALESCE(MAX(seq), 0) FROM op_log;")
  LAG=$((LIVE_HEAD - HEAD))
  if [ "$LAG" -gt "$MAX_LAG" ]; then
    echo "FAIL: replica head $HEAD lags live head $LIVE_HEAD by $LAG ops (max $MAX_LAG)" >&2
    exit 1
  fi
  echo "restore drill OK: ops=$OPS head=$HEAD lag=$LAG schema_version=$VERSION"
else
  echo "restore drill OK (no live DB to compare): ops=$OPS head=$HEAD schema_version=$VERSION"
fi
