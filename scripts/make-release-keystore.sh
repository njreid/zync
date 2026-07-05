#!/usr/bin/env bash
#
# Create a release signing keystore for zync and wire it up for local +
# GitHub-Actions signed release builds.
#
# What it does:
#   1. Generates `zync-release.jks` (RSA-4096, 10000-day validity) at the repo
#      root — unless one already exists (never silently overwrites your key).
#   2. Writes `key.properties` (git-ignored) so `./gradlew assembleRelease`
#      signs locally.
#   3. Prints the four GitHub Actions secrets to set (and, if `gh` is
#      installed and authenticated, offers to set them for you).
#
# The keystore + key.properties are git-ignored. BACK UP the keystore and its
# password somewhere safe: if you lose them you can never ship an update that
# installs over an existing install.
#
# Usage:
#   scripts/make-release-keystore.sh
#   ZYNC_KEY_ALIAS=zync ZYNC_KEYSTORE_PASSWORD=... scripts/make-release-keystore.sh
#
# Env overrides (all optional — a strong random password is generated if unset):
#   ZYNC_KEY_ALIAS          default: zync
#   ZYNC_KEYSTORE_PASSWORD  default: random 24-char
#   ZYNC_KEY_PASSWORD       default: same as keystore password
#   ZYNC_CERT_DN            default: "CN=zync, O=njr"

set -euo pipefail

# Resolve repo root (parent of this script's dir) so paths are stable no
# matter where the script is invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

KEYSTORE="$ROOT_DIR/zync-release.jks"
KEY_PROPS="$ROOT_DIR/key.properties"

ALIAS="${ZYNC_KEY_ALIAS:-zync}"
CERT_DN="${ZYNC_CERT_DN:-CN=zync, O=njr}"

command -v keytool >/dev/null 2>&1 || {
  echo "error: keytool not found on PATH (install a JDK, e.g. Temurin 17)." >&2
  exit 1
}

gen_password() {
  # 24 URL-safe chars from the CSPRNG.
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 18 | tr '+/' '-_' | tr -d '=\n'
  else
    LC_ALL=C tr -dc 'A-Za-z0-9_-' < /dev/urandom | head -c 24
  fi
}

if [[ -f "$KEYSTORE" ]]; then
  echo "==> $KEYSTORE already exists — leaving it untouched."
  echo "    Delete it first if you really want to regenerate (this invalidates"
  echo "    every previously published APK's update path)."
  STORE_PASS="${ZYNC_KEYSTORE_PASSWORD:-}"
  KEY_PASS="${ZYNC_KEY_PASSWORD:-$STORE_PASS}"
  if [[ -z "$STORE_PASS" ]]; then
    echo
    echo "Set ZYNC_KEYSTORE_PASSWORD to re-emit key.properties / secrets for the"
    echo "existing keystore. Skipping those steps for now."
    exit 0
  fi
else
  STORE_PASS="${ZYNC_KEYSTORE_PASSWORD:-$(gen_password)}"
  KEY_PASS="${ZYNC_KEY_PASSWORD:-$STORE_PASS}"
  echo "==> Generating $KEYSTORE (alias '$ALIAS', RSA-4096, 10000-day)…"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "$CERT_DN"
fi

echo "==> Writing $KEY_PROPS (git-ignored)…"
cat > "$KEY_PROPS" <<EOF
storeFile=zync-release.jks
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$KEY_PROPS"

KEYSTORE_B64="$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')"

echo
echo "================================================================"
echo " Local signed builds are ready:  ./gradlew assembleRelease"
echo "================================================================"
echo
echo "GitHub Actions release secrets (Settings → Secrets and variables → Actions):"
echo "  ZYNC_KEYSTORE_PASSWORD = $STORE_PASS"
echo "  ZYNC_KEY_ALIAS         = $ALIAS"
echo "  ZYNC_KEY_PASSWORD      = $KEY_PASS"
echo "  ZYNC_KEYSTORE_BASE64   = (base64 of the keystore, shown/handled below)"
echo

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  # `|| ans=""` so an EOF (non-interactive / piped stdin) doesn't trip set -e;
  # empty answer means "no, just print instructions".
  read -r -p "gh CLI is authenticated — set these four secrets now? [y/N] " ans || ans=""
  if [[ "${ans:-}" =~ ^[Yy]$ ]]; then
    printf '%s' "$STORE_PASS"   | gh secret set ZYNC_KEYSTORE_PASSWORD
    printf '%s' "$ALIAS"        | gh secret set ZYNC_KEY_ALIAS
    printf '%s' "$KEY_PASS"     | gh secret set ZYNC_KEY_PASSWORD
    printf '%s' "$KEYSTORE_B64" | gh secret set ZYNC_KEYSTORE_BASE64
    echo "==> Secrets set on the current repo."
  else
    echo "Skipped. To set ZYNC_KEYSTORE_BASE64 manually:"
    echo "  base64 -w0 zync-release.jks | gh secret set ZYNC_KEYSTORE_BASE64"
  fi
else
  echo "gh CLI not authenticated. Set the base64 secret with:"
  echo "  base64 -w0 zync-release.jks | gh secret set ZYNC_KEYSTORE_BASE64"
  echo "(or paste the base64 into the web UI). The raw base64 is also in"
  echo "  \$KEYSTORE_B64 if you exported it."
fi

echo
echo "Done. Keep zync-release.jks + its password backed up somewhere safe."
