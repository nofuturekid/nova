#!/usr/bin/env bash
# graphql-smoke — live smoke test of NOVA's READ-ONLY GraphQL ops
# against the real Unraid server.
#
# Drift-free: it sends the ACTUAL queries.graphql document and selects each
# operation by name via `operationName`, so it can never diverge from the
# app's real operations. The vendored schema is only a SUBSET of the live
# Unraid 7 schema, so green here is the only proof an op actually works.
#
# READ-ONLY GUARDRAIL: this never reads or sends mutations.graphql. Mutations
# change server state and stay maintainer-only — do NOT add them here.
#
# Requires (per the project's live-validation rule):
#   UNRAID_API_KEY      — set in ~/.bashrc; loaded below without printing it.
#   UNRAID_GRAPHQL_URL  — base URL of the server (no trailing /graphql),
#                         e.g. https://192.168.11.2
set -uo pipefail

# Load the key from the profile without printing it. The explicit grep+eval
# bypasses the usual non-interactive-shell guard at the top of ~/.bashrc.
eval "$(grep -E '^export UNRAID_API_KEY=' ~/.bashrc 2>/dev/null)"
: "${UNRAID_API_KEY:?set UNRAID_API_KEY in ~/.bashrc}"
: "${UNRAID_GRAPHQL_URL:?set UNRAID_GRAPHQL_URL to the server base URL (no /graphql)}"

ROOT="$(git rev-parse --show-toplevel)"
DOC="$ROOT/app/src/main/graphql/io/github/nofuturekid/nova/queries.graphql"
[ -f "$DOC" ] || { echo "queries.graphql not found at $DOC"; exit 1; }

URL="${UNRAID_GRAPHQL_URL%/}/graphql"
# -k: LAN servers typically use a self-signed cert (mirrors the app's
# self-signed local-trust, ADR-0041). Read-only smoke test only.
CURL=(curl -sk -m 20 -H "x-api-key: ${UNRAID_API_KEY}" -H "Accept: application/json" -H "Content-Type: application/json")

query_doc="$(cat "$DOC")"

# Zero-argument operations only: `query Name {` (no `(` = no required vars).
# Parameterized ops (e.g. FetchContainerLogs) need real inputs and are skipped.
mapfile -t OPS < <(grep -oE '^query [A-Za-z0-9]+ *\{' "$DOC" | sed -E 's/^query ([A-Za-z0-9]+).*/\1/')

pass=0; fail=0; skipped_param=$(( $(grep -cE '^query [A-Za-z0-9]+ *\(' "$DOC") ))
printf '%-28s %-6s %s\n' "OPERATION" "HTTP" "RESULT"
for name in "${OPS[@]}"; do
  body=$(jq -n --arg q "$query_doc" --arg op "$name" '{query:$q, operationName:$op}')
  resp=$("${CURL[@]}" -w $'\n%{http_code}' --data "$body" "$URL")
  http="${resp##*$'\n'}"; json="${resp%$'\n'*}"
  if echo "$json" | jq -e '.errors' >/dev/null 2>&1; then
    msg=$(echo "$json" | jq -r '[.errors[].message]|join(" | ")' 2>/dev/null | cut -c1-160)
    printf '%-28s %-6s ERRORS: %s\n' "$name" "$http" "$msg"; ((fail++))
  elif echo "$json" | jq -e '.data != null' >/dev/null 2>&1; then
    printf '%-28s %-6s OK\n' "$name" "$http"; ((pass++))
  else
    printf '%-28s %-6s UNEXPECTED: %s\n' "$name" "$http" "$(echo "$json"|head -c 120)"; ((fail++))
  fi
done
echo "---"
echo "PASS=$pass FAIL=$fail (skipped $skipped_param parameterized op(s) needing inputs)"
[ "$fail" -eq 0 ]
