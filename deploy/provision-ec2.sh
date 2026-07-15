#!/bin/bash
# Provision the zync server on EC2, end to end and idempotently:
#   S3 buckets (blobs + litestream) -> IAM role/profile -> security group (80/443)
#   -> Elastic IP -> reusable /data EBS volume -> t4g.small AL2023/arm64 instance
#   (user-data: mount /data, docker, litestream sidecar + restore drill, haloyd)
#   -> fetch the haloyd API token via SSM -> wire GitHub repo vars/secrets so
#   .github/workflows/deploy.yml deploys on every release.
#
# Everything is tagged app=zync-server / app=zync-data and reused when it already
# exists — rerunning is safe, and terminating the instance keeps the data volume
# and Elastic IP for the next run. DNS: uses sslip.io names derived from the
# Elastic IP (zync-<ip>.sslip.io); swap in a real domain later by changing the
# repo variables and re-running the deploy workflow.
#
# Needs the permissions in deploy/provision-policy.json. Run from anywhere with
# the AWS CLI (+ gh CLI authenticated to the repo, for automatic CI wiring).
set -euo pipefail

REGION=${AWS_REGION:-us-east-1}
INSTANCE_TYPE=${ZYNC_INSTANCE_TYPE:-t4g.small}
DATA_GB=${ZYNC_DATA_GB:-10}
REPO=${ZYNC_REPO:-njreid/zync}
export AWS_DEFAULT_REGION="$REGION"

ACCT=$(aws sts get-caller-identity --query Account --output text)
BLOB_BUCKET="zync-blobs-$ACCT"
LS_BUCKET="zync-litestream-$ACCT"

say() { echo "==> $*"; }

# --- S3 buckets ---------------------------------------------------------------
for b in "$BLOB_BUCKET" "$LS_BUCKET"; do
  if ! aws s3api head-bucket --bucket "$b" 2>/dev/null; then
    say "creating bucket $b"
    if [ "$REGION" = "us-east-1" ]; then aws s3api create-bucket --bucket "$b" >/dev/null
    else aws s3api create-bucket --bucket "$b" --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null; fi
    aws s3api put-public-access-block --bucket "$b" --public-access-block-configuration \
      BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  fi
done

# --- IAM role + instance profile ----------------------------------------------
ROLE=zync-server-role
PROFILE=zync-server-profile
if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  say "creating role $ROLE"
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
fi
aws iam put-role-policy --role-name "$ROLE" --policy-name zync-s3 --policy-document "{
  \"Version\":\"2012-10-17\",
  \"Statement\":[
    {\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\"],
     \"Resource\":[\"arn:aws:s3:::$BLOB_BUCKET/*\",\"arn:aws:s3:::$LS_BUCKET/*\"]},
    {\"Effect\":\"Allow\",\"Action\":[\"s3:ListBucket\"],
     \"Resource\":[\"arn:aws:s3:::$BLOB_BUCKET\",\"arn:aws:s3:::$LS_BUCKET\"]}]}"
aws iam attach-role-policy --role-name "$ROLE" \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore 2>/dev/null || true
if ! aws iam get-instance-profile --instance-profile-name "$PROFILE" >/dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name "$PROFILE" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$PROFILE" --role-name "$ROLE"
  sleep 10 # instance-profile propagation
fi

# --- network: default VPC, security group 80/443 -------------------------------
VPC=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=zync-server "Name=vpc-id,Values=$VPC" \
  --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo None)
if [ "$SG" = "None" ] || [ -z "$SG" ]; then
  say "creating security group"
  SG=$(aws ec2 create-security-group --group-name zync-server --vpc-id "$VPC" \
    --description "zync server: HTTPS + ACME (haloy proxy terminates TLS)" --query GroupId --output text)
  # 80 = ACME HTTP-01 + redirect; 443 = app + haloyd API (SNI-routed by the haloy proxy).
  aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol tcp --port 80 --cidr 0.0.0.0/0 >/dev/null
  aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol tcp --port 443 --cidr 0.0.0.0/0 >/dev/null
fi

# --- Elastic IP (reused across instance replacement) ---------------------------
ALLOC=$(aws ec2 describe-addresses --filters Name=tag:app,Values=zync-server \
  --query 'Addresses[0].AllocationId' --output text 2>/dev/null || echo None)
if [ "$ALLOC" = "None" ] || [ -z "$ALLOC" ]; then
  say "allocating Elastic IP"
  ALLOC=$(aws ec2 allocate-address --query AllocationId --output text)
  aws ec2 create-tags --resources "$ALLOC" --tags Key=app,Value=zync-server
fi
IP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC" --query 'Addresses[0].PublicIp' --output text)
IP_DASHED=${IP//./-}
DOMAIN="zync-$IP_DASHED.sslip.io"
API_DOMAIN="haloy-$IP_DASHED.sslip.io"

# --- reusable /data volume ------------------------------------------------------
SUBNET=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC" Name=default-for-az,Values=true \
  --query 'Subnets[0].SubnetId' --output text)
AZ=$(aws ec2 describe-subnets --subnet-ids "$SUBNET" --query 'Subnets[0].AvailabilityZone' --output text)
VOL=$(aws ec2 describe-volumes --filters Name=tag:app,Values=zync-data Name=status,Values=available,in-use \
  --query 'Volumes[0].VolumeId' --output text 2>/dev/null || echo None)
if [ "$VOL" = "None" ] || [ -z "$VOL" ]; then
  say "creating $DATA_GB GiB data volume in $AZ"
  VOL=$(aws ec2 create-volume --availability-zone "$AZ" --size "$DATA_GB" --volume-type gp3 \
    --tag-specifications 'ResourceType=volume,Tags=[{Key=app,Value=zync-data}]' --query VolumeId --output text)
else
  AZ=$(aws ec2 describe-volumes --volume-ids "$VOL" --query 'Volumes[0].AvailabilityZone' --output text)
  SUBNET=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC" "Name=availability-zone,Values=$AZ" \
    --query 'Subnets[0].SubnetId' --output text)
fi

# --- instance -------------------------------------------------------------------
INSTANCE=$(aws ec2 describe-instances --filters Name=tag:app,Values=zync-server \
  Name=instance-state-name,Values=pending,running --query 'Reservations[0].Instances[0].InstanceId' \
  --output text 2>/dev/null || echo None)
if [ "$INSTANCE" = "None" ] || [ -z "$INSTANCE" ]; then
  AMI=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
    --query Parameter.Value --output text)
  say "launching $INSTANCE_TYPE ($AMI) in $AZ"
  USERDATA=$(mktemp)
  cat > "$USERDATA" <<EOF
#!/bin/bash
set -eux
dnf install -y docker
systemctl enable --now docker
# reusable /data volume: format only if blank, mount by label
while [ ! -e /dev/nvme1n1 ]; do sleep 2; done
blkid /dev/nvme1n1 || mkfs -t xfs -L ZYNCDATA /dev/nvme1n1
mkdir -p /opt/zync/data
grep -q ZYNCDATA /etc/fstab || echo 'LABEL=ZYNCDATA /opt/zync/data xfs defaults,nofail 0 2' >> /etc/fstab
mount -a
# litestream sidecar + restore drill (units + drill script from the repo)
curl -fsSL https://github.com/benbjohnson/litestream/releases/download/v0.3.13/litestream-v0.3.13-linux-arm64.tar.gz | tar -xz -C /usr/local/bin litestream
mkdir -p /etc/zync
cat > /etc/zync/litestream.yml <<LS
dbs:
  - path: /opt/zync/data/zync.db
    replicas:
      - type: s3
        bucket: $LS_BUCKET
        path: litestream/zync.db
        region: $REGION
        retention: 168h
        snapshot-interval: 1h
        validation-interval: 12h
LS
for f in litestream-restore.service litestream.service restore-drill.service restore-drill.timer; do
  curl -fsSL "https://raw.githubusercontent.com/$REPO/main/deploy/\$f" -o "/etc/systemd/system/\$f"
done
curl -fsSL "https://raw.githubusercontent.com/$REPO/main/deploy/restore-drill.sh" -o /opt/zync/restore-drill.sh
chmod +x /opt/zync/restore-drill.sh
systemctl daemon-reload
systemctl enable --now litestream-restore.service litestream.service restore-drill.timer
# haloyd (deploy daemon; its API + app TLS are SNI-routed by the haloy proxy on 443)
curl -fsSL https://sh.haloy.dev/install-haloyd.sh | API_DOMAIN=$API_DOMAIN sh 2>&1 | tee /var/log/haloyd-install.log
grep -oE '[A-Za-z0-9_-]{20,}' /var/log/haloyd-install.log | tail -1 > /opt/zync/haloyd-token
chmod 600 /opt/zync/haloyd-token
EOF
  INSTANCE=$(aws ec2 run-instances --image-id "$AMI" --instance-type "$INSTANCE_TYPE" \
    --subnet-id "$SUBNET" --security-group-ids "$SG" \
    --iam-instance-profile "Name=$PROFILE" \
    --user-data "file://$USERDATA" \
    --tag-specifications 'ResourceType=instance,Tags=[{Key=app,Value=zync-server},{Key=Name,Value=zync-server}]' \
    --query 'Instances[0].InstanceId' --output text)
  rm -f "$USERDATA"
  aws ec2 wait instance-running --instance-ids "$INSTANCE"
  aws ec2 attach-volume --volume-id "$VOL" --instance-id "$INSTANCE" --device /dev/sdf >/dev/null
fi
aws ec2 associate-address --allocation-id "$ALLOC" --instance-id "$INSTANCE" >/dev/null
say "instance $INSTANCE at $IP  app=https://$DOMAIN  haloyd=$API_DOMAIN"

# --- haloyd API token via SSM (user-data takes a few minutes) --------------------
say "waiting for the haloyd token (SSM)..."
TOKEN=""
for i in $(seq 1 30); do
  CMD=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
    --parameters 'commands=["cat /opt/zync/haloyd-token 2>/dev/null"]' \
    --query Command.CommandId --output text 2>/dev/null) || { sleep 20; continue; }
  sleep 5
  TOKEN=$(aws ssm get-command-invocation --command-id "$CMD" --instance-id "$INSTANCE" \
    --query StandardOutputContent --output text 2>/dev/null | tr -d '[:space:]')
  [ -n "$TOKEN" ] && break
  sleep 15
done
[ -n "$TOKEN" ] || { echo "haloyd token not readable yet — check /var/log/haloyd-install.log on the box"; exit 1; }

# --- wire GitHub CI ---------------------------------------------------------------
REG_TOKEN=$(openssl rand -hex 16)
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  say "setting GitHub repo variables + secrets on $REPO"
  gh variable set HALOY_SERVER        --repo "$REPO" --body "$API_DOMAIN"
  gh variable set ZYNC_DOMAIN         --repo "$REPO" --body "$DOMAIN"
  gh variable set ZYNC_PUBLIC_ADDR    --repo "$REPO" --body "https://$DOMAIN"
  gh variable set AWS_REGION          --repo "$REPO" --body "$REGION"
  gh variable set ZYNC_BLOB_BUCKET    --repo "$REPO" --body "$BLOB_BUCKET"
  gh variable set ZYNC_WEBAUTHN_RP_ID --repo "$REPO" --body "$DOMAIN"
  gh variable set ZYNC_WEBAUTHN_ORIGIN --repo "$REPO" --body "https://$DOMAIN"
  gh secret set HALOY_API_TOKEN         --repo "$REPO" --body "$TOKEN"
  gh secret set ZYNC_WEBAUTHN_REG_TOKEN --repo "$REPO" --body "$REG_TOKEN"
  say "CI wired. Deploy now with: gh workflow run deploy.yml --repo $REPO"
else
  echo "gh CLI not authenticated — set these yourself:"
  echo "  vars: HALOY_SERVER=$API_DOMAIN ZYNC_DOMAIN=$DOMAIN ZYNC_PUBLIC_ADDR=https://$DOMAIN"
  echo "        AWS_REGION=$REGION ZYNC_BLOB_BUCKET=$BLOB_BUCKET"
  echo "        ZYNC_WEBAUTHN_RP_ID=$DOMAIN ZYNC_WEBAUTHN_ORIGIN=https://$DOMAIN"
  echo "  secrets: HALOY_API_TOKEN=<printed below> ZYNC_WEBAUTHN_REG_TOKEN=$REG_TOKEN"
  echo "  HALOY_API_TOKEN=$TOKEN"
fi
echo
say "passkey enrolment token (save it): $REG_TOKEN"
say "done. App: https://$DOMAIN  (deploy via the GitHub 'Deploy server' workflow)"
