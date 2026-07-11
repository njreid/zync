# zync server — EC2 bootstrap (haloy)

Single EC2 (Graviton/arm64). **haloy** deploys the app (zero-downtime, auto-TLS, no
registry); **litestream** runs as a host sidecar replicating the SQLite DB to S3. Per the
deployment spec (`docs/superpowers/specs/2026-07-08-deployment.md` §10).

> **Not runtime-verified in this repo's sandbox** (no haloy/AWS). These are the
> materialized artifacts + runbook; `haloy deploy` / the restore drill run when infra exists.

## 1. AWS resources
- **EC2** `t4g.small` (arm64), Amazon Linux 2023, tag `app=zync-server`, Elastic IP.
- **EBS** gp3 mounted at `/opt/zync/data` (the SQLite DB + server identity key live here).
- **S3** buckets: `zync-blobs` (attachments) + `zync-litestream` (WAL replica).
- **IAM instance role**: `s3:GetObject/PutObject/ListBucket` on the two buckets only.

## 2. One-time host setup (user-data or manual)
```sh
#!/bin/bash
set -eux
dnf install -y docker
systemctl enable --now docker
# data volume
mkfs -t xfs /dev/nvme1n1 || true
mkdir -p /opt/zync/data && mount /dev/nvme1n1 /opt/zync/data
echo '/dev/nvme1n1 /opt/zync/data xfs defaults,nofail 0 2' >> /etc/fstab
# litestream binary + config + sidecar units
curl -fsSL https://github.com/benbjohnson/litestream/releases/download/v0.3.13/litestream-v0.3.13-linux-arm64.tar.gz \
  | tar -xz -C /usr/local/bin litestream
mkdir -p /etc/zync && cp server/litestream.yml /etc/zync/litestream.yml   # + set ZYNC_S3_BUCKET/AWS_REGION
cp deploy/litestream-restore.service deploy/litestream.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now litestream-restore.service litestream.service
# install + configure haloyd (the haloy server daemon) per haloy.dev docs
```

## 3. Deploy (from a dev machine or CI)
```sh
export AWS_REGION=us-east-1 ZYNC_BLOB_BUCKET=zync-blobs
export ZYNC_ADMIN_PASSWORD=...            # or a haloy secret provider
haloy validate-config
haloy deploy                              # pre_deploy runs ./gradlew :server:installDist
```
Edit `haloy.yaml` `server:` (the haloyd host) and `domains:` (your hostname) first; point a
DNS A record at the Elastic IP. haloy provisions/renews the TLS cert automatically.

## 4. Restore drill (do before trusting it)
Stop the app, `rm /opt/zync/data/zync.db*`, `systemctl restart litestream-restore` → the DB
restores from S3; redeploy/restart the app; verify state via `/sync/bootstrap`. (Property
covered by M4 Task 6's durability tests.)

## 5. Rollback
`haloy rollback` (local image history) — instant revert to the previous deploy.
