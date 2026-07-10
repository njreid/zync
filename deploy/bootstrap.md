# zync server — EC2 bootstrap

Single EC2 (Graviton/arm64) + Docker Compose (server + litestream + Caddy), image
from GHCR, secrets in SSM Parameter Store, S3 for blobs + litestream. Per the
deployment spec (`docs/superpowers/specs/2026-07-08-deployment.md`) and threat model.

> **Not runtime-verified in this repo's CI sandbox** (no Docker/AWS). These are the
> materialized artifacts + the runbook; the live `docker compose up` / arm64 image
> build / restore drill run when infra exists.

## 1. AWS resources
- **EC2**: `t4g.small` (arm64), Amazon Linux 2023, tag `app=zync-server`.
- **EBS**: a dedicated gp3 volume mounted at `/data` (the SQLite DB lives here).
- **S3**: two buckets — `zync-blobs` (attachments) and `zync-litestream` (WAL replica).
- **IAM instance role**: least-privilege — `s3:GetObject/PutObject/ListBucket` scoped
  to the two buckets; `ssm:GetParameter*` on `/zync/*`.
- **OIDC deploy role** (`AWS_DEPLOY_ROLE_ARN`): assumed by GitHub Actions to run the
  SSM `send-command`; no static keys in CI.

## 2. SSM parameters (`/zync/*`, SecureString)
- `/zync/admin_password` → `ZYNC_ADMIN_PASSWORD`
- `/zync/devices` → contents of `ZYNC_DEVICES_FILE` (`deviceId=base64pubkey` lines)
- Region/bucket names as plain params or instance tags.

## 3. Instance user-data (once)
```sh
#!/bin/bash
set -eux
dnf install -y docker
systemctl enable --now docker
# mount the data volume
mkfs -t xfs /dev/nvme1n1 || true
mkdir -p /data && mount /dev/nvme1n1 /data
echo '/dev/nvme1n1 /data xfs defaults,nofail 0 2' >> /etc/fstab
# fetch env from SSM → /opt/zync/.env, then compose up (see /opt/zync/deploy.sh)
```

## 4. `/opt/zync/deploy.sh <image>` (invoked by the CI SSM step)
```sh
#!/bin/sh
set -eu
aws ssm get-parameters-by-path --path /zync --with-decryption \
  --query 'Parameters[].[Name,Value]' --output text \
  | sed 's#/zync/##' | awk '{print toupper($1)"="$2}' > /opt/zync/.env
docker pull "$1"
IMAGE="$1" docker compose -f /opt/zync/docker-compose.prod.yml up -d
docker image prune -f
```

## 5. TLS
Caddy provisions/renews Let's Encrypt certs for `ZYNC_DOMAIN`. Point an A record at
the instance's Elastic IP; open 80/443 in the security group. All routes require auth
except `/health` and the ACME challenge.

## 6. Restore drill (do this before trusting it)
Stop the server, delete `/data/zync.db*`, restart → litestream restores from S3;
verify state via `/sync/bootstrap`. See M4 Task 6's durability tests for the property
being relied on.
