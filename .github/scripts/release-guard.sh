#!/usr/bin/env bash
# Release guard (ADR-0014 amendments, CI review H2, issue #295 WP2): runs in the
# `guard` job of release.yml after the CI artifact was located and BEFORE it is
# downloaded or published. Fails loudly unless the tag, the tagged commit and the
# CI run that built the artifact agree. The same script runs in the dry run
# (workflow_dispatch), where no tag exists yet: TAG is then v<version input>, and
# release-preflight.sh accepts a tag name that is still free.
#
# Checks, in order: tag shape; the commit is on main; a green pull_request run of
# this repository exists on the pull request head (the tagged commit itself on
# lookup path 1; on path 2, the head of the pull request named in the subject,
# which must also have been merged as exactly this commit); the CI tree equals
# the tagged tree; release-preflight.sh (version shape, versionCode up, tag name
# free, non-empty CHANGELOG section); versionName == tag.
#
# Env (all required unless noted):
#   TAG             tag name, e.g. v0.1.41
#   SHA             commit the tag points at (the commit to release in a dry run)
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

# (b) The tagged commit must be on main. A PR head tagged before the PR landed
# is not an ancestor of main.
git merge-base --is-ancestor "$SHA" "$MAIN_REF" \
  || fail "tagged commit $SHA is not an ancestor of $MAIN_REF (tag on a PR/feature-branch commit?). Land the PR on main by the fast-forward push first, then tag its head commit."
echo "ok: $SHA is on $MAIN_REF"

# (c) The pull request behind the commit.
pr_merged_as_sha() { # $1 = PR number: merged, and its merge commit is the tagged commit
  local info
  info=$(gh pr view "$1" --json state,mergeCommit --jq '.state + " " + (.mergeCommit.oid // "")')
  [ "${info%% *}" = "MERGED" ] || fail "PR #$1 is not merged (state: ${info%% *})."
  [ "${info#* }" = "$SHA" ] \
    || fail "PR #$1 was merged as ${info#* }, not as the tagged commit $SHA (cherry-pick or direct push reusing the subject?)."
  echo "ok: PR #$1 merged as $SHA"
}
green_pr_run() { # $1 = commit: a successful ci.yml pull_request run of THIS repository (not a fork) on it
  local n
  n=$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/ci.yml/runs?head_sha=$1&event=pull_request&status=success&per_page=50" \
    | jq -r --arg repo "$GITHUB_REPOSITORY" '[.workflow_runs[] | select(.head_repository.full_name == $repo)] | length')
  [ "$n" -gt 0 ] \
    || fail "no successful ci.yml pull_request run from $GITHUB_REPOSITORY on $1. Only a commit that is its own pull request head and was green there can be released; a dispatch run made after tagging does not count. Open a pull request for the change, let it go green, land it by the fast-forward push, and tag that head."
  echo "ok: $1 has a successful ci.yml pull_request run from this repository ($n)"
}
if [ -n "${PR_NUM:-}" ]; then
  # Path 2 (the lookup in release.yml found no run on the tagged commit and took
  # the PR number from the subject, e.g. a squash commit or a rebase-merged
  # commit): the PR must have been merged as exactly this commit, and its head
  # (RUN_HEAD_SHA) must have been green in a pull_request run of its own. The
  # lookup alone also accepts a dispatch run on that head.
  pr_merged_as_sha "$PR_NUM"
  green_pr_run "$RUN_HEAD_SHA"
else
  # Path 1 (normal since 2026-11-13): the tagged commit is its own pull request
  # head, so a successful ci.yml run of event pull_request from THIS repository
  # (not a fork) must exist on exactly this SHA. The artifact may still come from
  # a workflow_dispatch run made later for recovery, which is why the lookup in
  # release.yml accepts both events and this check does not (issue #295, f). No
  # fallback: a commit without such a run is refused, whatever its subject says.
  green_pr_run "$SHA"
fi

# (d) The tree CI built must equal the tagged commit's tree (holds under strict
# branch protection; an up-to-date PR head has the tree of its merged commit).
RUN_TREE=$(gh api "repos/$GITHUB_REPOSITORY/git/commits/$RUN_HEAD_SHA" --jq '.tree.sha')
TAG_TREE=$(git rev-parse "$SHA^{tree}")
[ "$RUN_TREE" = "$TAG_TREE" ] \
  || fail "CI run $RUN_ID built tree $RUN_TREE (commit $RUN_HEAD_SHA) but the tagged commit has tree $TAG_TREE; the artifact does not match the tag source."
echo "ok: CI run $RUN_ID built the tagged tree $TAG_TREE"

# (e) Version and changelog checks shared with the pull request job `release-check`
# in ci.yml: versionName shape (ADR-0005), versionCode above the previous v* tag
# (ADR-0015), the tag name free or already on this commit, and a non-empty
# CHANGELOG.md section (ADR-0031). Fatal here, before anything is downloaded.
"$(dirname "${BASH_SOURCE[0]}")/release-preflight.sh" "$SHA"

# (a) versionName at the tagged commit must equal the tag (the pre-release suffix
# is part of versionName, ADR-0013). The preflight derives the tag from
# versionName; this ties it to the tag that was actually pushed.
VNAME=$(gradle_field "$SHA" versionName)
VCODE=$(gradle_field "$SHA" versionCode)
[ -n "$VNAME" ] && [ -n "$VCODE" ] || fail "could not read versionName/versionCode from app/build.gradle.kts at $SHA."
[ "v$VNAME" = "$TAG" ] \
  || fail "app/build.gradle.kts at $SHA has versionName \"$VNAME\" but the tag is $TAG. The updater would offer an APK that installs as a no-op (ADR-0013/0015); bump the version in a new commit and tag that."
echo "ok: versionName $VNAME == tag"
# Hand the source-derived versionCode to the post-download APK verification
# (release-verify-apk.sh), which requires the APK to report exactly this value.
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "vcode=$VCODE" >> "$GITHUB_OUTPUT"; fi
