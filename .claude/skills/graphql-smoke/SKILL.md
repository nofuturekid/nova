---
name: graphql-smoke
description: Smoke-test the app's READ-ONLY GraphQL queries against the real Unraid server before shipping a GraphQL change. Use when queries.graphql or the Apollo scalar/schema config changed.
disable-model-invocation: true
---

# graphql-smoke — live-validate read-only GraphQL ops

The vendored `schema.graphqls` is only a **subset** of the live Unraid 7
schema, so an operation can compile against it yet fail on the real server
(this exact trap bit `GetNotificationList` — see the comment in
`queries.graphql`). This skill runs every zero-argument read-only operation
in `queries.graphql` against the real server and reports which pass.

**Run it before shipping any change to** `queries.graphql`, the Apollo
`mapScalar` config, or `schema.graphqls`.

## Hard guardrail — read-only only

This skill **never** touches `mutations.graphql`. Mutations change server
state (start/stop arrays, containers, VMs, delete notifications) and are
**maintainer-only** — do not add them to the smoke test under any
circumstances.

## Prerequisites

- `UNRAID_API_KEY` exported in `~/.bashrc` (the value never enters the repo,
  transcript, or logs — the script loads it via a targeted `grep`+`eval` that
  also bypasses the non-interactive-shell guard at the top of `~/.bashrc`).
- `UNRAID_GRAPHQL_URL` set to the server's **base** URL, no trailing
  `/graphql` (e.g. `https://192.168.11.2`).
- `curl` + `jq` (both already present in this environment).

## Run

```bash
UNRAID_GRAPHQL_URL='https://192.168.11.2' bash .claude/skills/graphql-smoke/smoke.sh
```

Exit code is `0` only when every op returns `data` with no `errors`. A LAN
server's self-signed cert is accepted with `curl -k`, mirroring the app's
self-signed local-trust (ADR-0041).

## How it stays correct (drift-free)

The script does **not** copy the query text. It sends the actual
`queries.graphql` document and selects each operation by `operationName`, so
it always tests exactly what the app ships. It auto-discovers zero-argument
operations (`query Name {`) and **skips parameterized ops** (e.g.
`FetchContainerLogs`, which needs a real container `id`) — those are reported
as skipped, not silently dropped.

## Reading results

- `OK` — server returned `data`, no GraphQL errors. The op works live.
- `ERRORS: …` — server returned a GraphQL error (e.g.
  `GRAPHQL_VALIDATION_FAILED` when the live schema demands a field/arg the
  vendored subset marked optional). Fix the op before shipping.
- A non-200 HTTP status usually means auth (`x-api-key`) or connectivity.

It reads only `queries.graphql`: mutations are never sent, and the WebSocket
operations in `subscriptions.graphql` are not covered.
