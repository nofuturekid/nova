# ADR-0047: Detect the server's API version and what the API key may do

- **Status**: Accepted (2026-11-13)
- **Date**: 2026-11-13
- **Tags**: data, api, architecture

## Context

NOVA talks to the Unraid GraphQL API with one API key per server. Two facts about that API were not visible to the app so far:

- **The API version.** NOVA needs API 4.30.0 or newer (4.35.0 for live network speed, 4.36.0 for `docker.restart`). An older server answers a query with an HTTP 400 validation error, which the user saw as a raw error text (#247).
- **What the key may do.** A key with the `VIEWER` role reads everything NOVA shows but every action is refused with `FORBIDDEN` (#315). The app offered the buttons anyway.

What was verified against a real server (Unraid 7.4.0, API `4.37.11+8e587fe2`, read-only `VIEWER` key, 2026-11-13) and against the upstream source:

- `info { versions { core { api } } }` returns the API version with a build suffix (`4.37.11+8e587fe2`); the field exists since API 4.15.0. `me { roles permissions { resource actions } }` returns `{"roles":["VIEWER"],"permissions":[]}`: `me` describes the API key, not a signed-in user, and `permissions` lists only explicit permissions.
- The role policy of the API (`ADMIN` everything, `VIEWER` read on everything except `API_KEY`, `CONNECT`, `GUEST`) is identical from 4.30.0 to 4.37.11. The server compares actions exactly: `UPDATE_ANY` does not imply `READ_ANY`, and `*_OWN` never satisfies an `*_ANY` check.
- `me` is non-null: a `FORBIDDEN` on it nulls the whole response, so it must not share a request with the version.
- A wrong key gets `UNAUTHENTICATED` with HTTP 200 on every query except `__typename`, which is what the existing `Ping` sends. An unknown field gets HTTP 400 with `GRAPHQL_VALIDATION_FAILED`.
- Introspection is blocked on a stock 7.4.0 server (sandbox off), so the schema cannot be asked what it supports.

## Decision

Detect two things per server, with two small queries, keep the result in memory, and make it available as state. This change adds the foundation only; no screen reads it yet.

- **Model** (`data/model/ServerCapabilities.kt`, pure Kotlin): `ServerCapabilities` holds the API version (parsed; a build suffix is ignored when comparing), the key's roles and explicit grants, and a `Detection` state. `access(KeyAction)` answers `Allowed`, `Denied` or `Unknown` from the static role table plus the explicit grants; `supports(ApiFeature)` answers `Yes`, `No` or `Unknown` against the floors 4.30.0, 4.35.0 and 4.36.0. `KeyAction` is the single list of controls NOVA offers and the permission the server checks for each; a unit test requires that every mutation in `mutations.graphql` belongs to exactly one, so a new control cannot slip past hiding or the documentation.
- **Detection** (`GetApiVersion`, `GetKeyAccess` in `queries.graphql`): two operations on purpose, so a refused `me` does not lose the version. `CapabilityProbe.kt` sends them and classifies the answers by `extensions.code`: `UNAUTHENTICATED` is a rejected key, `GRAPHQL_VALIDATION_FAILED` on the version means an API older than 4.15.0, `FORBIDDEN` leaves the access unknown, transport and other errors are a failed detection. The mapping lives in its own file, not in `GraphQlMapper.kt`.
- **Wiring** (`CapabilityRepository`, a singleton built like `UpdateTracker`; `MainViewModel.capabilities`): detection runs when the target (server id, resolved address, API key) changes, on pull-to-refresh, and when the app returns to the foreground. There is no timer, no retry loop and no polling, so nothing runs in the background.
- **Fail open.** Unknown is never denied. A detection that fails, a `me` that is refused and a role NOVA does not know all leave the access `Unknown`; a later UI shows controls unless the access is `Denied`, which only a successful detection can say. A failed re-detection keeps the last good result for the same target; a new target starts unknown, so one server's result never gates another.
- **Memory only.** Not stored in DataStore or in `Server`: a server upgrade or a changed key would leave a stored result stale.

Domain availability (is Docker or the VM service turned on, does the server have an array) is deliberately not part of this model. It would cost more queries, goes stale when the user switches a service on, and each tab's own query answers it on every poll anyway.

## Consequences

- **Positive** — one place that knows the API version and the key's access; later changes (hiding controls a key cannot use, the atomic container restart, the Add server test, per-tab error reasons) read it instead of each probing the server. No visible change in this step.
- **Negative / trade-offs** — two more small requests per server change, pull-to-refresh and return to the foreground. The static role table copies the server's policy and can drift; an unknown role fails open, so drift can only show a button the server then refuses, never hide one the key may use (exception: a role that loses rights it has today). The CONNECT role is mirrored from the upstream policy but NOVA's detection of it is unverified on a live server.
- **Deviation from the first design, on purpose.** The detection pipeline lives in the application scope for as long as the repository exists, instead of being shared only while a screen collects it. The return to the foreground arrives together with the screen's own re-subscription, so a refresh that depended on an active collector could be lost. The foreground refresh fires only when the app was really away (not at the first start, which detects by itself), so it does not double the first detection.
- **Trigger to revisit** — if the Unraid API exposes a supported way to ask for the key's effective permissions without `PERMISSION:READ_ANY`, replace the static role table; if the role policy changes upstream, update the table and the test that pins it; if a feature must be gated that has no version floor, revisit the "no introspection" rule once introspection is allowed by default.

## Alternatives considered

- **Introspection of the schema.** Would tell exactly what the server has, but a stock Unraid 7.4.0 blocks it unless the developer sandbox is on, and 4.37.8 and newer also block `__type`. Version floors work everywhere.
- **Probing with a mutation to see whether it is refused.** Rejected: a probe that can change a server is not acceptable, and the server's `FORBIDDEN` for a real action is shown to the user anyway.
- **One query for version and access.** Rejected: `me` is non-null, so a key without `ME:READ_ANY` would lose the version as well.
- **`getPermissionsForRoles` from the API.** Needs `PERMISSION:READ_ANY`, which a read-only key does not have. The client computes the effective rights from the role table instead.
- **Detecting on a timer.** Rejected: nothing in this design runs in the background; the three triggers cover every real change (a key's permissions change only when the user edits them in the web UI, then returns to NOVA).
- **Storing the result.** Rejected, see Decision.

## References

- Issues #315 (read-only keys), #247 (older API versions), #317 (container restart), #316 (documentation of key permissions).
- Related: ADR-0035 (API key storage), `UpdateTracker` (same in-memory singleton pattern).
