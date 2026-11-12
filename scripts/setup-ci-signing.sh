#!/usr/bin/env bash
set -euo pipefail

# Helper script to configure Android release signing secrets for CI/CD
# (GitHub Actions) and verify signed AAB output for Play Store submission.
#
# Never commit generated keystores (*.jks / *.keystore) or passwords to git.

usage() {
  cat <<'EOF'
Usage:
  scripts/setup-ci-signing.sh [--repo OWNER/REPO] [--keystore PATH] [--alias ALIAS] [--create] [--build-aab]

Options:
  --repo OWNER/REPO   Target GitHub repository (defaults to current `gh` repo)
  --keystore PATH     Path to an existing .jks/.keystore file (or output path with --create)
  --alias ALIAS       Key alias inside the keystore (default: upload)
  --create            Generate a new RSA-2048 Play Store upload keystore via keytool
  --build-aab         Run a local signed `:app:bundleRelease` build and verify with jarsigner
  -h, --help          Show this help message
EOF
}

REPO=""
KEYSTORE_PATH=""
KEY_ALIAS="upload"
CREATE_KEYSTORE=false
BUILD_AAB=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      REPO="$2"
      shift 2
      ;;
    --keystore)
      KEYSTORE_PATH="$2"
      shift 2
      ;;
    --alias)
      KEY_ALIAS="$2"
      shift 2
      ;;
    --create)
      CREATE_KEYSTORE=true
      shift
      ;;
    --build-aab)
      BUILD_AAB=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$KEYSTORE_PATH" ]]; then
  read -r -p "Path to release keystore (.jks/.keystore) [\$HOME/.android/roku-upload.jks]: " KEYSTORE_PATH
  KEYSTORE_PATH="${KEYSTORE_PATH:-$HOME/.android/roku-upload.jks}"
fi

if [[ "$CREATE_KEYSTORE" == true ]]; then
  if [[ -f "$KEYSTORE_PATH" ]]; then
    echo "Keystore already exists at: $KEYSTORE_PATH" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$KEYSTORE_PATH")"
  read -r -s -p "New keystore password: " STORE_PASS
  echo
  read -r -s -p "New key password (press Enter to match keystore password): " KEY_PASS
  echo
  KEY_PASS="${KEY_PASS:-$STORE_PASS}"

  keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS"
  chmod 600 "$KEYSTORE_PATH"
else
  if [[ ! -f "$KEYSTORE_PATH" ]]; then
    echo "Keystore not found at $KEYSTORE_PATH (pass --create to generate one)." >&2
    exit 1
  fi
  read -r -s -p "Keystore password: " STORE_PASS
  echo
  read -r -s -p "Key password (press Enter to match keystore password): " KEY_PASS
  echo
  KEY_PASS="${KEY_PASS:-$STORE_PASS}"
fi

# Verify keystore credentials before uploading secrets.
keytool -list -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass "$STORE_PASS" >/dev/null
echo "Verified keystore alias '$KEY_ALIAS' in $KEYSTORE_PATH."

if command -v gh >/dev/null 2>&1; then
  if base64 --help 2>&1 | grep -q -- "-w"; then
    KEYSTORE_B64="$(base64 -w 0 "$KEYSTORE_PATH")"
  else
    KEYSTORE_B64="$(base64 < "$KEYSTORE_PATH" | tr -d '\n')"
  fi

  GH_ARGS=()
  if [[ -n "$REPO" ]]; then
    GH_ARGS+=(--repo "$REPO")
  fi

  echo "Uploading encrypted secrets to GitHub Actions..."
  printf '%s' "$KEYSTORE_B64" | gh secret set RELEASE_KEYSTORE_BASE64 "${GH_ARGS[@]}"
  printf '%s' "$STORE_PASS"   | gh secret set RELEASE_KEYSTORE_PASSWORD "${GH_ARGS[@]}"
  printf '%s' "$KEY_ALIAS"    | gh secret set RELEASE_KEY_ALIAS "${GH_ARGS[@]}"
  printf '%s' "$KEY_PASS"     | gh secret set RELEASE_KEY_PASSWORD "${GH_ARGS[@]}"
  echo "GitHub Actions secrets configured:"
  echo "  - RELEASE_KEYSTORE_BASE64"
  echo "  - RELEASE_KEYSTORE_PASSWORD"
  echo "  - RELEASE_KEY_ALIAS"
  echo "  - RELEASE_KEY_PASSWORD"
else
  echo "GitHub CLI ('gh') not found; skipping automatic secret upload."
  echo "Export these environment variables in your CI/CD secrets store:"
  echo "  KITE_KEYSTORE_PATH=<decoded path to keystore>"
  echo "  KITE_KEYSTORE_PASSWORD=<keystore password>"
  echo "  KITE_KEY_ALIAS=$KEY_ALIAS"
  echo "  KITE_KEY_PASSWORD=<key password>"
fi

if [[ "$BUILD_AAB" == true ]]; then
  echo "Building signed release AAB locally..."
  export KITE_KEYSTORE_PATH="$KEYSTORE_PATH"
  export KITE_KEYSTORE_PASSWORD="$STORE_PASS"
  export KITE_KEY_ALIAS="$KEY_ALIAS"
  export KITE_KEY_PASSWORD="$KEY_PASS"

  if [[ -f ./gradlew ]]; then
    bash ./gradlew :app:bundleRelease
  else
    gradle :app:bundleRelease
  fi

  AAB_PATH="app/build/outputs/bundle/release/app-release.aab"
  jarsigner -verify -verbose -certs "$AAB_PATH" | tail -n 10
  echo "Signed Play Store bundle ready at: $AAB_PATH"
fi

unset STORE_PASS KEY_PASS KEYSTORE_B64
