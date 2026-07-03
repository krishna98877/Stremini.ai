#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_PATH="${HOME}/.android/debug.keystore"
ALIAS_NAME="androiddebugkey"

if ! command -v keytool >/dev/null 2>&1; then
  echo "Error: keytool is not installed or not on PATH." >&2
  echo "Install a JDK and retry." >&2
  exit 1
fi

if [[ ! -f "${KEYSTORE_PATH}" ]]; then
  echo "Debug keystore not found. Creating ${KEYSTORE_PATH} ..."
  mkdir -p "${HOME}/.android"
  keytool -genkeypair \
    -v \
    -storetype PKCS12 \
    -keystore "${KEYSTORE_PATH}" \
    -alias "${ALIAS_NAME}" \
    -storepass android \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

KEYTOOL_OUTPUT="$(keytool -list -v \
  -alias "${ALIAS_NAME}" \
  -keystore "${KEYSTORE_PATH}" \
  -storepass android \
  -keypass android)"

SHA1_FINGERPRINT="$(printf '%s\n' "${KEYTOOL_OUTPUT}" | awk -F': ' '/SHA1:/{print $2; exit}')"
SHA256_FINGERPRINT="$(printf '%s\n' "${KEYTOOL_OUTPUT}" | awk -F': ' '/SHA256:/{print $2; exit}')"

echo "Application ID: com.Android.stremini_ai"
echo "Keystore: ${KEYSTORE_PATH}"
echo "Alias: ${ALIAS_NAME}"
echo "SHA1: ${SHA1_FINGERPRINT}"
echo "SHA256: ${SHA256_FINGERPRINT}"