#!/usr/bin/env bash
#
# Build, sign and verify a release APK.
#
# This is the offline path: it needs no GitHub, no CI and no network. The same script is
# what the Actions workflow runs, so a local release and a CI release are byte-for-byte
# produced by one code path and cannot drift apart.
#
# Usage:
#   ./release.sh                # version from app/build.gradle.kts
#   ./release.sh 1.2.3          # explicit version name
#   ./release.sh 1.2.3 4        # explicit version name and versionCode
#
# Signing material is read from, in order:
#   1. keystore.properties in the project root (gitignored) — normal local use.
#   2. RELEASE_KEYSTORE_FILE / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS /
#      RELEASE_KEY_PASSWORD environment variables — CI, where the keystore is restored
#      from a secret.
#
# Output: dist/immich-file-picker-<version>.apk plus a SHA-256 checksum. dist/ is
# gitignored, so an APK is published by attaching it to a GitHub Release, never by
# committing it.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VERSION_NAME="${1:-}"
VERSION_CODE="${2:-}"

# ---------------------------------------------------------------------------- toolchain

# The Android SDK location is never committed, so resolve it the way a developer would.
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ ! -d "$ANDROID_HOME" ]; then
    echo "error: Android SDK not found at $ANDROID_HOME" >&2
    echo "       set ANDROID_HOME to your SDK path" >&2
    exit 1
fi

# Highest installed build-tools provides apksigner, which is how the signature is checked.
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$BUILD_TOOLS" ]; then
    echo "error: no Android build-tools found under $ANDROID_HOME/build-tools" >&2
    exit 1
fi
APKSIGNER="${BUILD_TOOLS}apksigner"

# ------------------------------------------------------------------------- signing guard

if [ -n "${RELEASE_KEYSTORE_FILE:-}" ]; then
    SIGNING_SOURCE="environment variables"
elif [ -f keystore.properties ]; then
    SIGNING_SOURCE="keystore.properties"
else
    echo "error: no signing material found." >&2
    echo "       Create keystore.properties in the project root, or export the" >&2
    echo "       RELEASE_KEYSTORE_* / RELEASE_KEY_* variables. See README.md." >&2
    exit 1
fi

echo "Signing material: $SIGNING_SOURCE"

# -------------------------------------------------------------------------------- build

GRADLE_ARGS=(":app:assembleRelease" "--no-daemon")
if [ -n "$VERSION_NAME" ]; then
    export RELEASE_VERSION_NAME="$VERSION_NAME"
    GRADLE_ARGS+=("-PreleaseVersionName=$VERSION_NAME")
fi
if [ -n "$VERSION_CODE" ]; then
    export RELEASE_VERSION_CODE="$VERSION_CODE"
    GRADLE_ARGS+=("-PreleaseVersionCode=$VERSION_CODE")
fi

echo "Building…"
./gradlew "${GRADLE_ARGS[@]}" --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
    echo "error: expected $APK but it was not produced" >&2
    exit 1
fi

# ------------------------------------------------------------------------ verify signing

# A release APK that is not signed is worse than no APK at all: Android refuses it, and a
# later unsigned build cannot be distinguished from a signing misconfiguration.
if ! "$APKSIGNER" verify --print-certs "$APK" > /tmp/immich-signature.txt 2>&1; then
    echo "error: the release APK is not signed." >&2
    cat /tmp/immich-signature.txt >&2
    echo "       Check storePassword/keyPassword in keystore.properties." >&2
    exit 1
fi

DN="$(grep -m1 'certificate DN' /tmp/immich-signature.txt | sed 's/.*certificate DN: //')"
DIGEST="$(grep -m1 'SHA-256 digest' /tmp/immich-signature.txt | sed 's/.*SHA-256 digest: //')"

# The publisher identity is a release invariant, so fail loudly if it ever changes —
# that would mean the wrong keystore was used and updates would be rejected on devices.
EXPECTED_ORG="O=Virtunio Ltd"
if [[ "$DN" != *"$EXPECTED_ORG"* ]]; then
    echo "error: unexpected signer identity: $DN" >&2
    echo "       Expected a certificate containing '$EXPECTED_ORG'." >&2
    exit 1
fi

VERSION="$(grep -m1 'versionName' app/build/outputs/apk/release/output-metadata.json \
    | sed 's/.*"versionName": *"\([^"]*\)".*/\1/')"
# Fallback only; normally output-metadata.json above is authoritative.
[ -n "$VERSION" ] || VERSION="${VERSION_NAME:-1.1.0}"

# -------------------------------------------------------------------------------- stage

mkdir -p dist
OUT="dist/immich-file-picker-$VERSION.apk"
cp "$APK" "$OUT"
( cd dist && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )

echo
echo "Signed APK : $OUT"
echo "Checksum   : $OUT.sha256"
echo "Version    : $VERSION"
echo "Signer     : $DN"
echo "SHA-256    : $DIGEST"
