#!/usr/bin/env bash
# Creates the release signing keystore once and prints what to put in the GitHub secrets.
# Keep the .jks file somewhere safe and private: losing it means future releases cannot
# update an installed app, and it must never be committed (it is git-ignored).
set -euo pipefail

OUT="${1:-imu-mapper-release.jks}"
ALIAS="${KEY_ALIAS:-imumapper}"

if [ -e "$OUT" ]; then
  echo "refusing to overwrite existing $OUT" >&2
  exit 1
fi

read -r -s -p "Keystore password (also used for the key): " PASS
echo
if [ "${#PASS}" -lt 6 ]; then
  echo "password must be at least 6 characters" >&2
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=IMU Mapper, OU=Release, O=Stastyle, C=IL"

echo
echo "Add these four repository secrets (GitHub > Settings > Secrets and variables > Actions):"
echo
echo "  ANDROID_KEYSTORE_BASE64   = (contents of $OUT.base64, printed below)"
echo "  ANDROID_KEYSTORE_PASSWORD = the password you just typed"
echo "  ANDROID_KEY_ALIAS         = $ALIAS"
echo "  ANDROID_KEY_PASSWORD      = the password you just typed"
echo
base64 -w0 "$OUT" > "$OUT.base64" 2>/dev/null || base64 "$OUT" | tr -d '\n' > "$OUT.base64"
echo "Base64 written to $OUT.base64 (single line). Paste its content as ANDROID_KEYSTORE_BASE64."
