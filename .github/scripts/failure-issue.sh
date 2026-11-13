#!/usr/bin/env bash
# Failure issue for scheduled runs (issue #295, R11): keeps ONE open issue per
# fixed title. If an open issue with label `ci` and exactly that title exists,
# this run is added as a comment; otherwise the issue is created.
#
# Usage: failure-issue.sh <title>
# Env (set by GitHub Actions; all required):
#   GH_TOKEN             token with issues: write, actions: read
#   GITHUB_REPOSITORY    owner/name
#   GITHUB_RUN_ID        run to report
#   GITHUB_SHA           commit of the run
#   GITHUB_SERVER_URL    e.g. https://github.com
# Nothing secret is read or printed; the body holds the run link, the SHA and
# the names and conclusions of the failed or cancelled jobs of this run.
set -euo pipefail

[ "$#" -eq 1 ] && [ -n "$1" ] || { echo "usage: $0 <title>" >&2; exit 2; }
TITLE="$1"
: "${GH_TOKEN:?}" "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_SHA:?}" "${GITHUB_SERVER_URL:?}"

RUN_URL="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"

# Failed, cancelled or timed-out jobs of this run (a timeout reports as
# cancelled). The job that runs this script is still in progress and has no
# conclusion yet, so it never lists itself.
JOBS=$(gh api --paginate "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs?per_page=100" \
  --jq '.jobs[] | select(.conclusion == "failure" or .conclusion == "cancelled" or .conclusion == "timed_out") | "- \(.name): \(.conclusion) (\(.html_url))"')
[ -n "$JOBS" ] || JOBS="- (the API listed no failed or cancelled job; see the run)"

BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT
cat > "$BODY" <<EOT
Run: $RUN_URL
Commit: $GITHUB_SHA

Failed or cancelled jobs:

$JOBS
EOT

# Exact title match on open issues with label ci (jq --arg, no substring or
# pattern matching).
EXISTING=$(gh issue list --repo "$GITHUB_REPOSITORY" --label ci --state open --limit 200 \
  --json number,title | jq -r --arg t "$TITLE" '[.[] | select(.title == $t)] | first | .number // empty')

if [ -n "$EXISTING" ]; then
  gh issue comment "$EXISTING" --repo "$GITHUB_REPOSITORY" --body-file "$BODY"
  echo "Commented on issue #$EXISTING."
else
  gh issue create --repo "$GITHUB_REPOSITORY" --label ci --title "$TITLE" --body-file "$BODY"
fi
