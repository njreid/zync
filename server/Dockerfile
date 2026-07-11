# Runtime image for the zync server (linux/arm64, Graviton) — JVM only.
# litestream is DECOUPLED into a host sidecar (deploy/litestream.service), so haloy's
# zero-downtime swap never runs two litestream processes against the same replica.
# Build the distribution first (a pre_deploy hook in haloy.yaml runs it):
#   ./gradlew :server:installDist
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY server/build/install/server/ /app/

ENV ZYNC_DB_PATH=/data/zync.db \
    ZYNC_PORT=8080
EXPOSE 8080
# The DB lives on a host bind-mount (/opt/zync/data:/data) shared with the litestream
# sidecar, which restores it on boot before the app starts. The app does not run litestream.
ENTRYPOINT ["/app/bin/server"]
