# Runtime image for the zync server (linux/arm64, Graviton).
# The JVM distribution is built on CI first (arch-independent bytecode):
#   ./gradlew :server:installDist
# then built here with buildx --platform linux/arm64. litestream is PID 1 and
# supervises the JVM (deployment spec §3).
FROM eclipse-temurin:21-jre

ARG LITESTREAM_VERSION=0.3.13
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    curl -fsSL "https://github.com/benbjohnson/litestream/releases/download/v${LITESTREAM_VERSION}/litestream-v${LITESTREAM_VERSION}-linux-arm64.tar.gz" \
      | tar -xz -C /usr/local/bin litestream; \
    apt-get purge -y curl; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server/build/install/server/ /app/
COPY server/litestream.yml /etc/litestream.yml
COPY server/docker-entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENV ZYNC_DB_PATH=/data/zync.db \
    ZYNC_PORT=8080
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
