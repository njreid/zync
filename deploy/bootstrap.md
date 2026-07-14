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
- **Storage caps:** the op-log is quota-guarded in-app (`ZYNC_QUOTA_OPLOG_MB`, default
  1024; pushes 507 until compaction frees space). Blob spend is capped S3-side — set an
  AWS Budget alarm on the account and (optionally) a lifecycle rule on `zync-blobs`;
  the app deliberately has no blob-bytes quota (S3 can't fill the box's disk).

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

**CI (preferred):** `.github/workflows/deploy.yml` deploys on every published GitHub
release (or manually via *Run workflow*, optionally overriding the target host).
One-time repo setup — secrets `HALOY_API_TOKEN` (from the haloyd install output) +
`ZYNC_WEBAUTHN_REG_TOKEN`; variables `HALOY_SERVER`, `ZYNC_DOMAIN`, `ZYNC_PUBLIC_ADDR`,
`AWS_REGION`, `ZYNC_BLOB_BUCKET`, `ZYNC_WEBAUTHN_RP_ID`, `ZYNC_WEBAUTHN_ORIGIN`. The
workflow substitutes the host/domain placeholders in `haloy.yaml`, runs
`haloy validate-config && haloy deploy`, then gates on `/health`.

**Manually:**
```sh
export AWS_REGION=us-east-1 ZYNC_BLOB_BUCKET=zync-blobs
export ZYNC_PUBLIC_ADDR=https://zync.example.com
# Browser passkey auth — omit and the :web UI ships UNGATED (server warns on boot):
export ZYNC_WEBAUTHN_RP_ID=zync.example.com ZYNC_WEBAUTHN_ORIGIN=https://zync.example.com
export ZYNC_WEBAUTHN_REG_TOKEN=...        # one secret gating passkey enrolment
haloy validate-config
haloy deploy                              # pre_deploy runs ./gradlew :server:installDist
```
Edit `haloy.yaml` `server:` (the haloyd host) and `domains:` (your hostname) first; point a
DNS A record at the Elastic IP. haloy provisions/renews the TLS cert automatically.

## 4. Restore drill
**Scheduled (non-destructive):** install `restore-drill.sh` + its units and enable the
weekly timer — it restores the replica to a temp path, integrity-checks it, and fails
if the replica lags the live DB by more than `ZYNC_DRILL_MAX_LAG` (default 100) ops:

```sh
cp deploy/restore-drill.sh /opt/zync/restore-drill.sh && chmod +x /opt/zync/restore-drill.sh
cp deploy/restore-drill.service deploy/restore-drill.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now restore-drill.timer
systemctl start restore-drill.service && journalctl -u restore-drill -n 3   # run one now
```

**Full drill (destructive; do once before trusting the box):** stop the app,
`rm /opt/zync/data/zync.db*`, `systemctl restart litestream-restore` → the DB restores
from S3; redeploy/restart the app; verify state via `/sync/bootstrap`. (Property
covered by M4 Task 6's durability tests.)

## 5. Rollback
`haloy rollback` (local image history) — instant revert to the previous deploy.
