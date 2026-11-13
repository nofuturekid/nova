#!/usr/bin/env bash
# Release signing at the tag (ADR-0004 amendment 2026-11-13, issue #295 WP3).
# Runs in the `sign` job of release.yml, the only job that has the release key
# (environment `release`, limited to v* tags). CI built the release APK once,
# without any secret, signed by the debug fallback of app/build.gradle.kts;
# this script replaces that signature with the release signature and proves
# that nothing else changed.
#
#   release-sign.sh sign <in.apk> <out.apk>
#       signs a COPY with apksigner (v2 only, the scheme the published APKs
#       use), then checks: apksigner verifies it and reports no other scheme,
#       no v1 signature file is left in the zip, zipalign -c passes (16 KB page
#       size where the build tools offer -P), and the entry list with CRC-32
#       equals the input's, signature files excluded (the `compare` below).
#       Env (all required): KEYSTORE (path), KEYSTORE_PASSWORD, KEY_ALIAS,
#       KEY_PASSWORD. The passwords reach apksigner through `env:` options,
#       never through the command line, and are never printed.
#   release-sign.sh compare <a.apk> <b.apk>
#       the entry comparison alone: names, sizes, compression methods and
#       CRC-32 values must be identical, apart from the v1 signature files
#       (META-INF/MANIFEST.MF, *.SF, *.RSA, *.DSA, *.EC). Fails on any
#       difference.
#
# The pinned certificate and the single-signer rule are NOT checked here:
# release-verify-apk.sh does that, unchanged, right after this script.
#
# Optional env: BUILD_TOOLS_DIR (overrides the lookup of the highest
# $ANDROID_HOME/build-tools/*/, local testing).
set -euo pipefail

# stderr, not stdout: fail() also runs inside $(...) (build_tools, entry_list),
# where a stdout message would be captured and never shown. The runner reads
# workflow commands from stderr as well.
fail() {
	echo "::error::APK sign: $*" >&2
	exit 1
}

# Where apksigner and zipalign live: BUILD_TOOLS_DIR, else the highest version
# below ANDROID_HOME / ANDROID_SDK_ROOT (the same lookup as release-verify-apk.sh).
build_tools() {
	local bt root
	bt="${BUILD_TOOLS_DIR:-}"
	if [ -z "$bt" ]; then
		root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
		[ -n "$root" ] && [ -d "$root/build-tools" ] || fail "no Android SDK build-tools found (ANDROID_HOME/ANDROID_SDK_ROOT unset or without build-tools/)."
		bt=$(find "$root/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
	fi
	[ -x "$bt/apksigner" ] && [ -x "$bt/zipalign" ] || fail "apksigner/zipalign missing in '$bt'."
	printf '%s\n' "$bt"
}

# One line per zip entry: "<name> crc=<crc32> size=<length> method=<method>",
# sorted, without the v1 signature files. Fails on an unreadable or empty zip.
entry_list() { # $1 = apk
	local listing
	listing=$(unzip -v "$1") || fail "cannot list the entries of $1."
	printf '%s\n' "$listing" | awk '
    $1 ~ /^[0-9]+$/ && length($7) == 8 && $7 ~ /^[0-9a-f]+$/ {
      name = $0
      for (i = 1; i <= 7; i++) sub(/^ *[^ ]+/, "", name)
      sub(/^ +/, "", name)
      if (name ~ /^META-INF\/(MANIFEST\.MF|[^\/]+\.(SF|RSA|DSA|EC))$/) next
      print name " crc=" $7 " size=" $1 " method=" $2
    }' | sort
}

compare_entries() { # $1 = downloaded apk, $2 = signed apk
	local a b na nb
	a=$(entry_list "$1")
	b=$(entry_list "$2")
	na=$(printf '%s\n' "$a" | grep -c .) || true
	nb=$(printf '%s\n' "$b" | grep -c .) || true
	[ "$na" -gt 0 ] || fail "no entries found in $1."
	if [ "$a" != "$b" ]; then
		echo "Entries that differ (< $1, > $2):" >&2
		diff <(printf '%s\n' "$a") <(printf '%s\n' "$b") | head -50 >&2 || true
		fail "the signed APK does not contain exactly the entries of the APK CI built ($na vs $nb entries)."
	fi
	echo "ok: $nb entries, names, sizes, methods and CRC-32 identical to the CI build (v1 signature files excluded)"
}

sign() { # $1 = in.apk, $2 = out.apk
	local in="$1" out="$2" bt apksigner zipalign out_v usage align_args align_out
	: "${KEYSTORE:?KEYSTORE is required}" "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required}" \
		"${KEY_ALIAS:?KEY_ALIAS is required}" "${KEY_PASSWORD:?KEY_PASSWORD is required}"
	[ -f "$in" ] || fail "input APK not found: $in"
	[ -s "$KEYSTORE" ] || fail "keystore file missing or empty."
	[ "$in" != "$out" ] && [ ! -e "$out" ] || fail "the output must be a new file, not the input and not an existing file: $out"
	bt=$(build_tools)
	apksigner="$bt/apksigner"
	zipalign="$bt/zipalign"

	# What the CI build was signed with (informational: the debug fallback).
	"$apksigner" verify --print-certs "$in" 2>&1 | grep -E 'certificate SHA-256 digest' | sed 's/^/  input  /' || true

	# v2 only, like every published APK: v1, v3 and v4 explicitly off. apksigner
	# replaces the whole APK Signing Block.
	"$apksigner" sign \
		--ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
		--ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
		--v1-signing-enabled false --v2-signing-enabled true \
		--v3-signing-enabled false --v4-signing-enabled false \
		--out "$out" "$in" || fail "apksigner sign failed (wrong keystore, alias or password?)."
	[ ! -e "$out.idsig" ] || fail "an .idsig file was written although v4 signing is off."

	# The result verifies, and only the v2 scheme is present.
	out_v=$("$apksigner" verify -v --print-certs "$out" 2>&1) || {
		echo "$out_v" | grep -v -i password >&2 || true
		fail "apksigner verify rejected the signed APK."
	}
	printf '%s\n' "$out_v" | grep -E 'certificate SHA-256 digest' | sed 's/^/  output /' || true
	if printf '%s\n' "$out_v" | grep -E 'Verified using v(1|3(\.[0-9]+)?|4) scheme.*: true' >&2; then
		fail "the signed APK carries a signature scheme besides v2."
	fi
	if printf '%s\n' "$out_v" | grep -E 'Verified using v2 scheme.*: false' >&2; then
		fail "the signed APK has no v2 signature."
	fi
	if unzip -Z1 "$out" | grep -E '^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$' >&2; then
		fail "the signed APK still holds v1 signature files (listed above); an old signature survived."
	fi
	echo "ok: apksigner verifies the signed APK, v2 only, no v1 signature files left"

	# Alignment: 4 bytes, and 16 KB for uncompressed native libraries where the
	# build tools know -P (build-tools 35 and newer).
	usage=$("$zipalign" 2>&1 || true)
	if printf '%s\n' "$usage" | grep -q -- '-P <pagesize_kb>'; then
		align_args=(-c -P 16 -v 4)
	else
		echo "::warning::zipalign in $bt has no -P option; checking 4-byte alignment only."
		align_args=(-c -v 4)
	fi
	if ! align_out=$("$zipalign" "${align_args[@]}" "$out" 2>&1); then
		printf '%s\n' "$align_out" | tail -20 >&2
		fail "zipalign ${align_args[*]} failed on the signed APK."
	fi
	echo "ok: zipalign ${align_args[*]} passes"

	compare_entries "$in" "$out"
}

case "${1:-}" in
sign)
	[ "$#" = 3 ] || fail "usage: release-sign.sh sign <in.apk> <out.apk>"
	sign "$2" "$3"
	;;
compare)
	[ "$#" = 3 ] || fail "usage: release-sign.sh compare <a.apk> <b.apk>"
	compare_entries "$2" "$3"
	;;
*) fail "usage: release-sign.sh sign <in.apk> <out.apk> | compare <a.apk> <b.apk>" ;;
esac
