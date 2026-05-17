# ADR-0032: In-app notification actions (archive / unread / delete)

- **Status**: Proposed
- **Date**: 2026-05-17
- **Tags**: ui, data

## Context

The notifications sheet was read-only: it showed the deduplicated unread
warnings + alerts that drive the bell badge (`warningsAndAlerts`) and
nothing else. Users could not clear, archive, or even see INFO-level or
already-archived notifications from the app — the only way to act on them
was the Unraid web UI.

The live Unraid 7 API already exposes the full surface for this:
`Notifications.list(filter: NotificationFilter)` with a
`NotificationType` of exactly `{ UNREAD, ARCHIVE }` (note: `ARCHIVE`, not
`ARCHIVED`), plus top-level mutations `archiveNotification`,
`unreadNotification`, `deleteNotification(id, type)`, `archiveAll`,
`deleteArchivedNotifications`, and `recalculateOverview`. The vendored
`schema.graphqls` is a deliberately trimmed subset and previously declared
none of this, and was effectively read-only (only array/docker/vm/parity
mutations).

The bell badge must keep working unchanged: its count is unread
warning+alert, and Apollo codegen validates every operation against the
vendored schema at build time, so the schema subset has to be extended
exactly and consistently.

## Decision

The notifications sheet becomes two segments — **Unread** and
**Archived** — backed by `list(filter: { type: UNREAD | ARCHIVE })`. Per
row: Unread → Archive (= mark read) + Delete; Archived → Unarchive
(restore to unread) + Delete; plus a bulk **Archive all** on the Unread
tab. The bell badge keeps deriving from the same `Notifications` model's
unread warning+alert count.

Concretely:

- The vendored `schema.graphqls` subset is **intentionally extended and is
  no longer read-only-only**: it gains `list(filter:)`, the
  `NotificationFilter` input, the `NotificationType` enum
  (`UNREAD`/`ARCHIVE`), the extra `Notification` fields (`link`, `type`,
  `formattedTimestamp`), and the notification mutations added to the
  existing `type Mutation` block (`archiveNotification`,
  `unreadNotification`, `deleteNotification`, `archiveAll`,
  `recalculateOverview`). Field nullability/arg names were confirmed by
  read-only introspection of the live server on 2026-05-17.
- A new `GetNotificationList` query fetches `overview` + both lists in one
  round-trip; the notifications poll stream now uses it, so the sheet and
  the bell badge always derive from the same fetch.
- **Action→refetch, not optimistic** (chosen for provable badge
  correctness over local-mutation guesswork): each repository action runs
  the mutation, then a server `recalculateOverview`, then fires a nudge
  that interrupts the (60 s) notifications poll delay so the stream
  re-fetches immediately. The UI holds no local action state.
- UNREAD↔ARCHIVE model: archive moves an item Unread→Archived; unread
  moves it back; delete needs the item's current `type`.
- Device-gated visual/behaviour change (ADR-0027 Tier 3) — live on-device
  acceptance is the maintainer's. Parked untagged: no version bump / tag /
  release; it rides the next beta cut (consistent with the bell-icon fix).

## Consequences

- **Positive** — users can triage notifications (incl. INFO and archived)
  fully in-app; the bell badge stays correct after every action because it
  and the list share one server-authoritative fetch; no optimistic-state
  drift to reason about.
- **Negative / trade-offs** — the vendored schema is no longer a pure
  read-only mirror, so future schema-diffing must account for the
  intentionally declared mutation block; action→refetch costs one extra
  round-trip + a `recalculateOverview` per action vs. a local optimistic
  update; an action's effect is visible only after the refetch completes
  (acceptable: actions are infrequent and the nudge makes it ~a frame).
- **Trigger to revisit** — if notification actions become high-frequency
  (perceptible refetch lag), or if the live server adds an atomic
  list+overview subscription, reconsider optimistic updates / push.

## Alternatives considered

- **Optimistic local mutation of the `Notifications` model.** Faster
  perceived UI, but the badge would have to be recomputed locally and kept
  in sync with the server's own dedup/archive rules — exactly the kind of
  client-side reimplementation of server logic that drifts. Rejected for
  correctness.
- **Keep the bell's `GetNotifications` query and add a second independent
  list query.** Two sources of truth for the same data; the badge and the
  sheet could disagree mid-refresh. Folding overview into
  `GetNotificationList` keeps them consistent.
- **Nest mutations under a `NotificationMutations` type** (mirroring
  docker/vm). The live server exposes them top-level on `Mutation`
  (verified by introspection); modelling otherwise would fail codegen.

## References

- PR: feat/notifications-actions (this change)
- Related: ADR-0017 (domain-split polling), ADR-0027 (agent autonomy /
  device-gated Tier 3), ADR-0031 (curated changelog)
- Live schema introspected read-only 2026-05-17 (no mutations executed
  against production).
