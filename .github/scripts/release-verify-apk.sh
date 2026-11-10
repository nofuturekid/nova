#!/usr/bin/env bash
# Release APK verification (ADR-0014 amendment): the APK itself is the source
# of truth, not the text of app/build.gradle.kts. Runs in release.yml after the
# CI artifact was downloaded and BEFORE anything is published. Fails unless the
# APK reports the expected package, versionName == tag, the versionCode the
# release guard derived from source, and is signed by the pinned release key.
#
# Env:
#   APK                   path to the APK to verify (required)
#   TAG                   tag name, e.g. v0.1.41 (required)
#   EXPECTED_VERSION_CODE versionCode the source at the tagged commit declares (required);
#                         release-guard.sh has already checked it is > the previous tag's
#   EXPECTED_PACKAGE      optional, default io.github.nofuturekid.nova (the `direct` flavor
#                         sets no applicationIdSuffix; verified on the v0.1.41 APK)
#   ANDROID_HOME / ANDROID_SDK_ROOT  SDK root; highest build-tools/*/ is used
#   BUILD_TOOLS_DIR       optional, overrides the build-tools lookup (local testing)
set -euo pipefail

fail() { echo "::error::APK verify: $*"; exit 1; }

# SHA-256 of the release signing certificate (public information). Obtained by
# running `apksigner verify --print-certs` on the published v0.1.41 release APK
# (gh release download v0.1.41); the v0.1.40-beta1 APK carries the same digest.
# If the signing key is ever rotated, update this value in the same PR.
EXPECTED_CERT_SHA256="9f86baa17c1208004d9284e99597f77276c77301b06340b10075c793152b6824"

: "${APK:?APK is required}" "${TAG:?TAG is required}" "${EXPECTED_VERSION_CODE:?EXPECTED_VERSION_CODE is required}"
EXPECTED_PACKAGE="${EXPECTED_PACKAGE:-io.github.nofuturekid.nova}"
[ -f "$APK" ] || fail "APK not found: $APK"

BT="${BUILD_TOOLS_DIR:-}"
if [ -z "$BT" ]; then
  ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$ROOT" ] && [ -d "$ROOT/build-tools" ] || fail "no Android SDK build-tools found (ANDROID_HOME/ANDROID_SDK_ROOT unset or without build-tools/)."
  BT=$(find "$ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
fi
AAPT2="$BT/aapt2"; APKSIGNER="$BT/apksigner"
[ -x "$AAPT2" ] && [ -x "$APKSIGNER" ] || fail "aapt2/apksigner missing in '$BT'."

# --- package, versionName, versionCode as the APK declares them -------------
BADGING=$("$AAPT2" dump badging "$APK") || fail "aapt2 dump badging failed for $APK."
PKG_LINE=$(printf '%s\n' "$BADGING" | grep -E '^package:' || true)
[ "$(printf '%s\n' "$PKG_LINE" | grep -c .)" = 1 ] || fail "expected exactly one 'package:' line in badging."
attr() { printf '%s\n' "$PKG_LINE" | sed -nE "s/.*[[:space:]]$1='([^']*)'.*/\1/p"; }
PKG=$(attr name); VNAME=$(attr versionName); VCODE=$(attr versionCode)
[ -n "$PKG" ] && [ -n "$VNAME" ] && [ -n "$VCODE" ] || fail "could not read package/versionName/versionCode from the APK (got '$PKG_LINE')."

[ "$PKG" = "$EXPECTED_PACKAGE" ] || fail "APK package is $PKG, expected $EXPECTED_PACKAGE."
[ "v$VNAME" = "$TAG" ] \
  || fail "APK reports versionName \"$VNAME\" but the tag is $TAG. The artifact CI built is not this release (the v0.1.40-beta1 failure class); fix forward with a new commit and tag."
[ "$VCODE" = "$EXPECTED_VERSION_CODE" ] \
  || fail "APK versionCode is $VCODE but the source at the tagged commit declares $EXPECTED_VERSION_CODE; source and artifact disagree."
echo "ok: APK package $PKG, versionName $VNAME == tag, versionCode $VCODE == source"

# --- signature: valid, single signer, pinned certificate --------------------
CERTS=$("$APKSIGNER" verify --print-certs "$APK") || fail "apksigner verify failed (APK unsigned or signature invalid)."
# The label in front of the digest differs between build-tools versions ("Signer #1
# certificate ..." in 36.x, "V2 Signer: certificate ..." in 37.x, one line per signature
# scheme), so match on the stable part only: every "certificate SHA-256 digest: <hex>"
# line, whatever the prefix, except SourceStamp signers. All of them must be the same
# digest, and there must be at least one.
DIGESTS=$(printf '%s\n' "$CERTS" | tr -d '\r' | grep -v 'Source Stamp' \
  | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-fA-F]+)[[:space:]]*$/\1/p' | tr 'A-F' 'a-f' | sort -u) || true
[ -n "$DIGESTS" ] || fail "no 'certificate SHA-256 digest' line in apksigner output."
[ "$(printf '%s\n' "$DIGESTS" | grep -c .)" = 1 ] || fail "signer certificates differ across signature schemes/signers: ${DIGESTS//$'\n'/, }."
[ "$DIGESTS" = "$EXPECTED_CERT_SHA256" ] \
  || fail "signer certificate SHA-256 is $DIGESTS, expected the pinned release key $EXPECTED_CERT_SHA256."
echo "ok: APK signature valid, signer certificate matches the pinned release key"
