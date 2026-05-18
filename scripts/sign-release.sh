#!/usr/bin/env bash
# Sign the unsigned release APK with the Android debug keystore.
# Output: app/build/outputs/apk/release/karoo_decoupling-<ver>-release-signed.apk
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="$REPO_ROOT/app/build/outputs/apk/release"
KEYSTORE="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

if [[ ! -f "$KEYSTORE" ]]; then
  echo "error: debug keystore not found at $KEYSTORE" >&2
  echo "hint: run any debug build once (e.g. ./gradlew :app:assembleDebug) to generate it," >&2
  echo "      or set ANDROID_DEBUG_KEYSTORE to an existing keystore path." >&2
  exit 1
fi

shopt -s nullglob
# Accept both the AGP "-unsigned" filename and the bare "-release.apk" form
# (excluding anything we've already signed).
candidates=()
for f in "$RELEASE_DIR"/*-release-unsigned.apk "$RELEASE_DIR"/*-release.apk; do
  [[ "$f" == *-signed.apk ]] && continue
  candidates+=("$f")
done
shopt -u nullglob

if [[ ${#candidates[@]} -eq 0 ]]; then
  echo "error: no release APK found in $RELEASE_DIR" >&2
  echo "hint: run ./gradlew :app:assembleRelease first." >&2
  exit 1
fi
if [[ ${#candidates[@]} -gt 1 ]]; then
  echo "error: multiple release APKs found; expected exactly one:" >&2
  printf '  %s\n' "${candidates[@]}" >&2
  exit 1
fi

UNSIGNED="${candidates[0]}"
if [[ "$UNSIGNED" == *-unsigned.apk ]]; then
  SIGNED="${UNSIGNED%-unsigned.apk}-signed.apk"
else
  SIGNED="${UNSIGNED%.apk}-signed.apk"
fi

# Locate apksigner: prefer ANDROID_HOME/ANDROID_SDK_ROOT, else fall back to
# ~/Library/Android/sdk, and pick the highest build-tools version available.
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS_DIR="$SDK_ROOT/build-tools"
if [[ ! -d "$BUILD_TOOLS_DIR" ]]; then
  echo "error: Android build-tools not found at $BUILD_TOOLS_DIR" >&2
  exit 1
fi
APKSIGNER="$(ls -1 "$BUILD_TOOLS_DIR" | sort -V | tail -n1)"
APKSIGNER="$BUILD_TOOLS_DIR/$APKSIGNER/apksigner"
if [[ ! -x "$APKSIGNER" ]]; then
  echo "error: apksigner not executable at $APKSIGNER" >&2
  exit 1
fi

echo "Signing: $UNSIGNED"
echo "Keystore: $KEYSTORE"
echo "Tool:    $APKSIGNER"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$SIGNED" \
  "$UNSIGNED"

"$APKSIGNER" verify --print-certs "$SIGNED" >/dev/null

echo
echo "OK: $SIGNED"
echo "Install with:"
echo "  adb install -r \"$SIGNED\""
