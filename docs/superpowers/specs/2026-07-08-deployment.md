# zync — Deployment (server) spec

> **Status: 🟢 DECIDED (2026-07-08).** Pure AWS: a single **EC2** instance running
> **Docker Compose** (Ktor app + **litestream** + **Caddy**), CI/CD via **GitHub
> Actions → GHCR → SSM**. Feeds M4 (server foundation) in the rebuild roadmap. The
> config sketches below are **materialized as real files in M4**, not committed now
> (a workflow building a not-yet-existent `server` module would be dead config).

## 1. The constraint that shapes everything

SQLite makes the server a **single stateful node**: no scale-out, no load balancer,
no serverless, no zero-downtime blue/green. **Deploy = replace the one app container,
keep the persistent volume, run migrations on startup, litestream keeps S3 in sync.**
A few seconds of restart downtime is acceptable. Design for that.

## 2. Topology (AWS)

- **1× EC2** `t4g.small` (Graviton/ARM, ~$12/mo) — enough for JVM + litestream +
  Caddy. Amazon Linux 2023.
- **Persistent EBS** (gp3) mounted at `/data` — the SQLite file + Caddy cert store.
- **Elastic IP** + DNS (Route53 or any) → stable hostname; Caddy gets a real
  **Let's Encrypt** cert (no self-signed pinning needed — that's why desktop/browser
  become plain HTTPS clients).
- **Security group:** 443 open; **80 open only for ACME** HTTP-01 (or use TLS-ALPN /
  DNS challenge and close 80); **no inbound 22** — use **SSM Session Manager** for
  shell + deploys.
- **Instance IAM role (least privilege):** RW to the one **S3 bucket** (litestream +
  blobs), read the app's **SSM Parameter Store** secrets, SSM-managed for deploys.
- **S3 bucket** (private, SSE): litestream SQLite replication + content-addressed
  encrypted attachment blobs.

## 3. Runtime: Docker Compose (two services)

- **`caddy`** — reverse proxy + automatic TLS; forwards `:443` → `app:8080`. ~3-line
  Caddyfile. Own volume for certs.
- **`app`** — the Ktor server image from **GHCR**, with **litestream as PID 1
  supervising the JVM** (`litestream replicate -exec "java -jar /app/zync.jar"`):
  litestream **restores `/data/zync.db` from S3 on boot**, then runs the app and
  streams the WAL back to S3 continuously. This avoids a separate container racing on
  the DB file. Mounts `/data`. Reads secrets from env (fetched from SSM on boot via
  the instance role — see §5).

```
# Caddyfile
zync.example.com {
    reverse_proxy app:8080
}
```
```yaml
# docker-compose.yml  (materialized in M4)
services:
  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes: [caddy_data:/data, ./Caddyfile:/etc/caddy/Caddyfile:ro]
    depends_on: [app]
  app:
    image: ghcr.io/njreid/zync-server:${TAG:-latest}
    environment: [ "AWS_REGION=...", "S3_BUCKET=...", "ZYNC_ENV=prod" ]
    volumes: [ zync_data:/data ]        # /data/zync.db
    restart: unless-stopped
volumes: { caddy_data: {}, zync_data: {} }   # zync_data backed by the EBS mount
```
litestream config lives in the app image (`/etc/litestream.yml`: `path:
/data/zync.db`, replica → `s3://<bucket>/litestream`).

## 4. CI/CD: GitHub Actions → GHCR → SSM

Separate workflow from the Android APK release (keep them independent). Trigger: push
to `main` touching `core/`, `data/`, or `server/` (path filter), or a `server-v*` tag.

```yaml
# .github/workflows/server-deploy.yml  (materialized in M4)
permissions: { id-token: write, contents: read, packages: write }
jobs:
  build:
    - ./gradlew :core:test :data:test :server:test
    - docker build -t ghcr.io/njreid/zync-server:${{ github.sha }} .
    - docker push  ghcr.io/njreid/zync-server:${{ github.sha }}
  deploy:
    needs: build
    - configure-aws-credentials via OIDC   # assume-role, NO long-lived keys
    - aws ssm send-command --targets Instance=<id> \
        --parameters 'commands=["cd /opt/zync && TAG=${{ github.sha }} docker compose pull && docker compose up -d"]'
```
- **GitHub↔AWS auth = OIDC** (GitHub's OIDC provider → an IAM role); **no static AWS
  keys in GitHub**. The only repo secret is the role ARN.
- **Image registry = GHCR** (free for the repo; simpler than ECR auth).
- **Deploy = SSM `send-command`** runs `docker compose pull && up -d` on the box — no
  SSH, no open 22.

## 5. Secrets

- App secrets (LLM API keys, device pubkey list, etc.) live in **SSM Parameter
  Store** (SecureString). The **app fetches them at startup via the instance IAM
  role** — so no secrets in GitHub, none baked into the image, none in compose.
- GitHub holds only the **OIDC role ARN** (a variable, not a secret).

## 6. Migrations & safety (stateful!)

- App runs **SQLDelight migrations on startup**.
- litestream keeps **generations**, so a bad migration is recoverable by restoring a
  prior point-in-time. Take an explicit **litestream snapshot immediately before
  migrating** (or gate deploys that change schema).
- Single SQLite file ⇒ **stop-old → migrate → start-new** (brief downtime). Don't
  attempt to run old+new against one DB.
- **Rollback:** redeploy a previous GHCR image tag via SSM; DB rollback via litestream
  restore (rare — only bad migrations).

## 7. Local = prod parity

- Tightest loop: `./gradlew :server:run` against a local SQLite (no litestream/S3).
- Parity loop: `docker compose up` locally runs the same app + Caddy; point litestream
  at a local **MinIO** if you want to exercise replication.

## 8. Cost (personal): ~$15/mo
t4g.small (~$12) + gp3 EBS (~$1-2) + S3 (pennies) + EIP (free while attached) +
minimal transfer.

## 9. Deferred / open
- **IaC:** start with a **bootstrap script** (instance user-data + a README) for the
  one box; add **Terraform** later only if reproducibility bites. (CDK explicitly
  rejected — wrong altitude for one instance.)
- **Monitoring/alerting** (CloudWatch agent or a lightweight uptime check) — M9
  hardening.
- **Backup/restore drills** (verify litestream restore end-to-end) — M4 acceptance +
  M9.
- **ARM image:** build the server image `linux/arm64` to match Graviton.

## 10. Decision — haloy (ADOPTED 2026-07-11; supersedes §3–§5)

**Adopted** as the deploy mechanism, replacing Compose + Caddy + GHCR/buildx + SSM.
`haloy deploy` builds the image locally from `server/Dockerfile`, uploads only changed
layers (no registry), and does a zero-downtime swap with automatic TLS + rollback.
Materialized: **`haloy.yaml`** (repo root), a **JVM-only `server/Dockerfile`** (litestream
removed), and the **litestream host sidecar** (`deploy/litestream.service` +
`deploy/litestream-restore.service`, config `server/litestream.yml`). Retired:
`docker-compose.yml`, `Caddyfile`, `docker-entrypoint.sh`, `.github/workflows/server-deploy.yml`.

**Key move — litestream decoupled from the app container.** haloy's swap briefly runs
old+new containers; two `litestream replicate` processes against one S3 replica would
conflict on generations. So litestream runs as a **single host sidecar** (systemd) against
a **host bind-mount `/opt/zync/data`** shared with the app container. `litestream-restore`
(oneshot) restores on a fresh box before the app starts; the app itself runs no litestream
(`StartupSequence` just opens the file). Single writer = the app; the sidecar reads + replicates.

**Deploy:** `haloy validate-config && haloy deploy` (a `pre_deploy` hook runs
`./gradlew :server:installDist`). S3 (blobs + litestream) uses the EC2 instance role — no
AWS keys in the container. **Not runtime-verified here** (no haloy/AWS); YAML validated,
server compiles.

### Prior evaluation (2026-07-10)

Evaluated **[haloy](https://haloy.dev/)** (CLI + `haloyd` daemon + reverse-proxy daemon;
`haloy deploy` builds locally, uploads only changed layers — no registry — and does a
zero-downtime swap with ACME TLS, rollback, and replicas) as a replacement for the
Compose + Caddy + GHCR/buildx + SSM `deploy.sh` pipeline (§3–§5).

**Verdict: viable and would simplify shipping, but deferred until after M5/M6.**
- It would replace **Caddy** (proxy + TLS), the **GHCR + buildx + SSM deploy** path
  (`haloy deploy`, direct layer upload), and add **instant rollback** — cutting the
  fiddliest part of §3–§5.
- **One real design change is required:** decouple **litestream from the app container.**
  haloy's zero-downtime swap briefly runs old+new containers on the same volume; two
  `litestream replicate` processes against the same S3 replica would conflict on
  generations. Fix: run litestream as a **separate persistent process** (own container /
  systemd) that haloy does not replace, replicating the shared SQLite volume; haloy
  swaps only the JVM app. The brief single-writer SQLite overlap during a swap is
  acceptable for the single-user workload (or use a stop-first deploy for zero overlap).
- Cost: adopt `haloyd` on the box, rework the litestream/PID-1 arrangement, retire the
  Compose/Caddy/workflow artifacts.
- **Sequencing:** nothing is deployed yet and the current stack works, so this is a
  post-product deployment-simplification pass — prototype `haloy.yaml` + the litestream
  sidecar then. Not blocking. (haloy explicitly supports SQLite apps, so SQLite itself
  is not the blocker — only the litestream-in-the-app-container arrangement is.)
