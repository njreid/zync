#!/bin/sh
# litestream is PID 1: restore the SQLite DB from S3 if this box is fresh, then run
# the JVM under continuous replication. The app also runs StartupSequence + Room-style
# migrations once the file is present.
set -eu

mkdir -p "$(dirname "$ZYNC_DB_PATH")"

if [ ! -f "$ZYNC_DB_PATH" ]; then
  echo "litestream: restoring $ZYNC_DB_PATH from replica (if any)"
  litestream restore -if-replica-exists -config /etc/litestream.yml "$ZYNC_DB_PATH" || true
fi

exec litestream replicate -config /etc/litestream.yml -exec "/app/bin/server"
