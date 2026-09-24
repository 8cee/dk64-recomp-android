#!/usr/bin/env bash
# Create one persistent Android signing key and store it as GitHub Actions secrets.
# Run ONCE per fork. Keep the generated backup directory safe; losing this key
# means future APKs cannot update installations signed with it.
set -euo pipefail

command -v gh >/dev/null 2>&1 || { echo "error: gh CLI is required"; exit 1; }
command -v keytool >/dev/null 2>&1 || { echo "error: keytool/JDK is required"; exit 1; }
command -v base64 >/dev/null 2>&1 || { echo "error: base64 is required"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "error: python3 is required"; exit 1; }

if [ -z "${PUBLIC_REPO:-}" ]; then
    PUBLIC_REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
fi
[ -n "${PUBLIC_REPO:-}" ] || { echo "error: run inside your GitHub clone or set PUBLIC_REPO=owner/repo"; exit 1; }

BACKUP_DIR="${SIGNING_BACKUP_DIR:-$HOME/.dk64-recomp-android-signing}"
KS="$BACKUP_DIR/dk64-recompiled-android.jks"
ENV_FILE="$BACKUP_DIR/signing-backup.env"
ALIAS="${ANDROID_RELEASE_KEY_ALIAS:-dk64recomp}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if [ -e "$KS" ] || [ -e "$ENV_FILE" ]; then
    echo "error: signing backup already exists in $BACKUP_DIR"
    echo "Refusing to replace it. Reuse that key or move the directory explicitly."
    exit 1
fi

STORE_PASS="$(python3 - <<'PY2'
import secrets
print(secrets.token_hex(24))
PY2
)"
KEY_PASS="$(python3 - <<'PY2'
import secrets
print(secrets.token_hex(24))
PY2
)"

keytool -genkeypair \
  -keystore "$KS" -storetype JKS \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=DK64 Recompiled Android,OU=CI,O=DK64 Recompiled Android"

KS_B64="$(base64 < "$KS" | tr -d '\r\n')"
gh secret set ANDROID_RELEASE_KEYSTORE_B64 --repo "$PUBLIC_REPO" --body "$KS_B64"
gh secret set ANDROID_RELEASE_KEYSTORE_PASSWORD --repo "$PUBLIC_REPO" --body "$STORE_PASS"
gh secret set ANDROID_RELEASE_KEY_ALIAS --repo "$PUBLIC_REPO" --body "$ALIAS"
gh secret set ANDROID_RELEASE_KEY_PASSWORD --repo "$PUBLIC_REPO" --body "$KEY_PASS"

cat > "$ENV_FILE" <<EOF
# KEEP PRIVATE. Needed to recreate the GitHub signing secrets.
PUBLIC_REPO='$PUBLIC_REPO'
ANDROID_RELEASE_KEYSTORE_FILE='$KS'
ANDROID_RELEASE_KEYSTORE_PASSWORD='$STORE_PASS'
ANDROID_RELEASE_KEY_ALIAS='$ALIAS'
ANDROID_RELEASE_KEY_PASSWORD='$KEY_PASS'
EOF
chmod 600 "$KS" "$ENV_FILE"

CERT_SHA256="$(keytool -list -v -keystore "$KS" -storepass "$STORE_PASS" -alias "$ALIAS" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1)"
echo "Persistent signing configured for $PUBLIC_REPO"
echo "Backup directory: $BACKUP_DIR"
echo "Certificate SHA-256: ${CERT_SHA256:-unknown}"
echo "Back up this directory securely. Do not commit it."
