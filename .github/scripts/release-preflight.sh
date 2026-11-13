#!/usr/bin/env bash
# Release preflight (issue #295, decision f): the checks on a version bump that
# do not depend on a CI run, so that the pull request and the tag cannot drift
# apart. ci.yml (job `release-check`) runs it on a pull request whose
# versionName changed; release.yml will call it at the tag (a later work
# package). It only reads git; it needs no token and no Gradle.
#
# Usage: release-preflight.sh <commit>
#
# Checks, all against <commit>:
#   1. versionName has the ADR-0005 shape (X.Y.Z or X.Y.Z-(alpha|beta|rc|pre)N,
#      trailing digit required), so v<versionName> is a valid tag name.
#   2. versionCode is greater than at the previous v* tag (ADR-0015).
#   3. No tag v<versionName> exists, except one that already points at <commit>
#      (the state at the tag run). A tag on another commit means the version is
#      used up (tags are never moved, ADR-0014).
#   4. CHANGELOG.md has a non-empty `## [<versionName>]` section (ADR-0031).
#
# Needs the tags and enough history to find the previous v* tag (fetch-depth: 0).
set -euo pipefail

fail() {
	echo "::error::Release preflight: $*"
	exit 1
}

[ "$#" -eq 1 ] || fail "usage: release-preflight.sh <commit>"
COMMIT=$(git rev-parse --verify --quiet "$1^{commit}") || fail "$1 is not a commit in this checkout."

gradle_field() { # $1 = commit, $2 = versionCode|versionName; fails on more than one distinct value
	local vals
	vals=$(git show "$1:app/build.gradle.kts" |
		sed -nE "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*\"?([^\"[:space:]]+)\"?.*/\1/p" | sort -u)
	[ "$(printf '%s\n' "$vals" | grep -c .)" -le 1 ] ||
		fail "app/build.gradle.kts at $1 assigns $2 more than once with different values (${vals//$'\n'/, })."
	printf '%s\n' "$vals"
}

VNAME=$(gradle_field "$COMMIT" versionName)
VCODE=$(gradle_field "$COMMIT" versionCode)
[ -n "$VNAME" ] && [ -n "$VCODE" ] || fail "could not read versionName/versionCode from app/build.gradle.kts at $COMMIT."

# 1. Version shape. Same pattern as the tag check in release-guard.sh and the
# "Detect pre-release" step in release.yml: a digitless `-beta` would be
# published as STABLE.
[[ "$VNAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc|pre)[0-9]+)?$ ]] ||
	fail "versionName \"$VNAME\" is not X.Y.Z or X.Y.Z-(alpha|beta|rc|pre)N (trailing digit required, ADR-0005); a digitless pre-release tag would be published as stable."
[[ "$VCODE" =~ ^[0-9]+$ ]] || fail "versionCode \"$VCODE\" is not a number."
TAG="v$VNAME"
echo "ok: versionName $VNAME has the ADR-0005 shape (tag $TAG)"

# 2. versionCode must have increased since the previous v* tag.
if PREV=$(git describe --tags --abbrev=0 --match 'v*' "$COMMIT^" 2>/dev/null); then
	PREV_VCODE=$(gradle_field "$PREV" versionCode)
	[ -n "$PREV_VCODE" ] || fail "could not read versionCode from app/build.gradle.kts at the previous tag $PREV."
	[ "$VCODE" -gt "$PREV_VCODE" ] ||
		fail "versionCode $VCODE at $COMMIT is not greater than $PREV_VCODE at the previous tag $PREV (ADR-0015)."
	echo "ok: versionCode $VCODE > $PREV_VCODE ($PREV)"
else
	echo "ok: versionCode $VCODE (no previous v* tag)"
fi

# 3. The tag name must be free.
if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null; then
	TAG_COMMIT=$(git rev-parse "refs/tags/$TAG^{commit}")
	[ "$TAG_COMMIT" = "$COMMIT" ] ||
		fail "tag $TAG already exists on $TAG_COMMIT, not on $COMMIT. Tags are never moved or reused (ADR-0014); bump to the next version."
	echo "ok: tag $TAG exists and points at $COMMIT"
else
	echo "ok: tag $TAG does not exist yet"
fi

# 4. A non-empty CHANGELOG section for this version. The heading match is a
# literal prefix test (no regex), so the dots in the version cannot match
# anything else and 0.1.45 does not match 0.1.45-beta1.
CHANGELOG=$(git show "$COMMIT:CHANGELOG.md") || fail "CHANGELOG.md does not exist at $COMMIT."
SECTION=$(printf '%s\n' "$CHANGELOG" | awk -v ver="$VNAME" '
  index($0, "## [" ver "]") == 1 {found = 1; next}
  found && /^## \[/ {exit}
  found {print}
')
[ -n "$(printf '%s' "$SECTION" | tr -d '[:space:]')" ] ||
	fail "CHANGELOG.md has no non-empty \"## [$VNAME]\" section at $COMMIT (ADR-0031). The version-bump PR renames [Unreleased] to the release version."
echo "ok: CHANGELOG.md has a non-empty section for $VNAME"
