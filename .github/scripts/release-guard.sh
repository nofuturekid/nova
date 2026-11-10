#!/usr/bin/env bash
# Release guard (ADR-0014 amendment, CI review H2): runs in release.yml after the
# CI artifact was located and BEFORE it is downloaded or published. Fails loudly
# unless the tag, the tagged commit and the CI run that built the artifact agree.
#
# Env (all required unless noted):
#   TAG             tag name, e.g. v0.1.41
#   SHA             commit the tag points at
#   RUN_ID          CI run the artifact will be downloaded from
#   RUN_HEAD_SHA    head_sha of that run
#   PR_NUM          PR number parsed from the commit subject (empty on the direct path)
#   GITHUB_REPOSITORY, GH_TOKEN (for gh)
#   MAIN_REF        optional, default origin/main
set -euo pipefail

MAIN_REF="${MAIN_REF:-origin/main}"
fail() { echo "::error::Release guard: $*"; exit 1; }

gradle_field() { # $1 = commit, $2 = versionCode|versionName; fails on more than one distinct value
  local vals
  vals=$(git show "$1:app/build.gradle.kts" \
    | sed -nE "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*\"?([^\"[:space:]]+)\"?.*/\1/p" | sort -u)
  [ "$(printf '%s\n' "$vals" | grep -c .)" -le 1 ] \
    || fail "app/build.gradle.kts at $1 assigns $2 more than once with different values (${vals//$'\n'/, }); the APK verification step is authoritative, but ambiguous source is refused early."
  printf '%s\n' "$vals"
}

# Tag shape: must match ADR-0005 and the "Detect pre-release" step in release.yml
# exactly. A digitless `-beta` would not be detected as pre-release there and
# would be published as STABLE.
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc|pre)[0-9]+)?$ ]] \
  || fail "tag $TAG is not vX.Y.Z or vX.Y.Z-(alpha|beta|rc|pre)N (trailing digit required, ADR-0005); a digitless pre-release tag would be published as stable."

# (b) The tagged commit must be on main. A PR head tagged before the squash
# merge is not an ancestor of main.
git merge-base --is-ancestor "$SHA" "$MAIN_REF" \
  || fail "tagged commit $SHA is not an ancestor of $MAIN_REF (tag on a PR/feature-branch commit?). Merge the PR first, then tag the squash commit on main."
echo "ok: $SHA is on $MAIN_REF"

# (c) PR path: the PR must be merged, and its merge commit must be the tagged commit.
if [ -n "${PR_NUM:-}" ]; then
  PR_INFO=$(gh pr view "$PR_NUM" --json state,mergeCommit --jq '.state + " " + (.mergeCommit.oid // "")')
  [ "${PR_INFO%% *}" = "MERGED" ] || fail "PR #$PR_NUM is not merged (state: ${PR_INFO%% *})."
  [ "${PR_INFO#* }" = "$SHA" ] \
    || fail "PR #$PR_NUM was merged as ${PR_INFO#* }, not as the tagged commit $SHA (cherry-pick or direct push reusing the subject?)."
  echo "ok: PR #$PR_NUM merged as $SHA"
fi

# (d) The tree CI built must equal the tagged commit's tree (holds under strict
# branch protection; an up-to-date PR head has the tree of its squash commit).
RUN_TREE=$(gh api "repos/$GITHUB_REPOSITORY/git/commits/$RUN_HEAD_SHA" --jq '.tree.sha')
TAG_TREE=$(git rev-parse "$SHA^{tree}")
[ "$RUN_TREE" = "$TAG_TREE" ] \
  || fail "CI run $RUN_ID built tree $RUN_TREE (commit $RUN_HEAD_SHA) but the tagged commit has tree $TAG_TREE; the artifact does not match the tag source."
echo "ok: CI run $RUN_ID built the tagged tree $TAG_TREE"

# (a) versionName at the tagged commit must equal the tag (the pre-release suffix
# is part of versionName, ADR-0013), and versionCode must have increased
# since the previous v* tag (ADR-0015).
VNAME=$(gradle_field "$SHA" versionName)
VCODE=$(gradle_field "$SHA" versionCode)
[ -n "$VNAME" ] && [ -n "$VCODE" ] || fail "could not read versionName/versionCode from app/build.gradle.kts at $SHA."
[ "v$VNAME" = "$TAG" ] \
  || fail "app/build.gradle.kts at $SHA has versionName \"$VNAME\" but the tag is $TAG. The updater would offer an APK that installs as a no-op (ADR-0013/0015); bump the version in a new commit and tag that."
if PREV=$(git describe --tags --abbrev=0 --match 'v*' "$SHA^" 2>/dev/null); then
  PREV_VCODE=$(gradle_field "$PREV" versionCode)
  [ -n "$PREV_VCODE" ] || fail "could not read versionCode from app/build.gradle.kts at the previous tag $PREV."
  [ "$VCODE" -gt "$PREV_VCODE" ] \
    || fail "versionCode $VCODE at $SHA is not greater than $PREV_VCODE at the previous tag $PREV (ADR-0015)."
  echo "ok: versionName $VNAME == tag, versionCode $VCODE > $PREV_VCODE ($PREV)"
else
  echo "ok: versionName $VNAME == tag, versionCode $VCODE (no previous v* tag)"
fi
# Hand the source-derived versionCode to the post-download APK verification
# (release-verify-apk.sh), which requires the APK to report exactly this value.
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "vcode=$VCODE" >> "$GITHUB_OUTPUT"; fi
