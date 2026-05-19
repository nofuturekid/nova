# ADR-0037: Resilient action launchers — failed user actions surface, never crash

- **Status**: Accepted
- **Date**: 2026-05-19
- **Tags**: ui, data, resilience

## Context

Triage #19 examined the error handling of the notification actions
(archive / unread / delete / archive-all / delete-all-archived) and found
a latent crash. In `MainViewModel` every user-initiated mutation was a
bare:

```kotlin
fun archiveNotification(id: String) = viewModelScope.launch { unraid.archiveNotification(id) }
```

with **no `try`/`catch`**, the repository action method **did not guard
the mutation call** (`UnraidRepository.runNotificationActionFlow`
intentionally lets a failed mutation propagate so it does not run a
recalc/nudge against nothing), and the ViewModel had **no
`CoroutineExceptionHandler`**. An uncaught throwable in `viewModelScope`
is delivered to the default handler, which on Android crashes the
process. So a single Archive/Delete tap over a flaky LAN or remote link
(transient socket drop, `ApolloNetworkException`, GraphQL error) could
**crash the whole app**, with zero user feedback.

The same unguarded shape existed for the entire action surface, not just
notifications: docker start/stop/restart/pause/update/update-all, VM
start/stop/pause/resume/reboot/reset, array start/stop, and parity
start/pause/resume/cancel were all bare
`viewModelScope.launch { unraid.<action>() }`. `updateContainer` /
`updateAllContainers` had a `finally` (to clear the updating-pill) but
still propagated the throwable. This is one defect class, ~22 call sites.

The app's UI is a Compose `Column` with no Material 3 `Scaffold`/
`SnackbarHost`, and there was no existing transient-message / error
channel to reuse.

## Decision

**Route every user-action mutation through one resilient launcher on
`MainViewModel`, add a `CoroutineExceptionHandler` safety net, and keep
the established action→server-truth convergence intact.** A failed
action never crashes and never fails silently — it surfaces one
transient message and the polling streams reconcile the real state.

Concretely:

- `MainViewModel.launchAction(message, before, finally) { … }` — the
  single seam. It runs the action; **re-throws `CancellationException`**
  (structured concurrency must keep working); catches any other
  `Throwable`, emits exactly one transient message, and ends the
  coroutine normally. `before`/`finally` carry the prior call sites'
  pre/post bookkeeping (the updating-pill set) so nothing is stranded.
  Its control flow is the pure `internal` companion seam
  `runResilientAction(...)` — same convention as
  `UnraidRepository.runNotificationActionFlow` / `pollDomainForTest` —
  so the unit test drives the *real* logic, not a copy.
- A minimal transient-message channel —
  `MainViewModel.userMessages: SharedFlow<String>` (replay 0,
  `extraBufferCapacity = 1`, lossless `tryEmit`) — collected by
  `MainScreen` into a Material 3 `SnackbarHost` overlaid in a `Box`. This
  is the idiomatic Compose/M3 mechanism; no parallel UI system was
  invented.
- `viewModelScope.launch(actionExceptionHandler)` — a
  `CoroutineExceptionHandler` as defense-in-depth (§3): anything that
  ever bypasses the seam still cannot crash the process; it logs a
  generic transient message instead. It is the net, not the primary fix.
- The repository's action→nudge/refetch convergence is unchanged. On a
  failed mutation the repo still short-circuits (nothing to reconcile);
  the domain poll loop's existing transient-error tolerance (ADR-0017)
  reconciles the UI to server truth on the next poll. On success the
  recalc + nudge still fire.

## Consequences

- **Positive** — a routine action tap on a flaky connection can no
  longer crash the app. The user gets a brief, honest signal and the UI
  self-heals on the next poll. One seam (DRY) instead of ~22 ad-hoc
  try/catches; a new action launcher is one `launchAction { … }` line.
- **Negative / trade-offs** — the transient message is intentionally
  generic ("Couldn't reach the server — try again"); it does not
  classify the failure. The optimistic-free, poll-to-reconcile model
  means the UI may briefly still show the pre-action state until the
  next poll — acceptable and already the established design.
- **Trigger to revisit** — if a second ViewModel grows the same action
  surface (extract the seam to a shared base/extension); or if users
  need per-action error detail / retry affordances (the snackbar would
  become an action-bearing component); or if we move to optimistic
  mutations (the failure path would also need to roll back local state).

## Alternatives considered

- **Per-call `try`/`catch` at each launcher.** Correct but ~22 copies of
  the same catch/emit/rethrow-cancellation logic — guaranteed to drift,
  and a new launcher silently omits it. Rejected for DRY/Rule 3.
- **Accept and document the propagation (status quo + a note).** The
  pre-fix behaviour is an app crash on a normal tap over a flaky link.
  Documenting a crash is not a fix. Rejected.
- **Global handler only (no seam).** A `CoroutineExceptionHandler` alone
  stops the crash but gives a single generic signal with no per-action
  bookkeeping (the updating-pill would leak) and hides the intent. Kept
  only as the defense-in-depth net behind the seam.

## References

- Triage #19 (notification-action network-error handling; the crash path).
- ADR-0017 — domain-split queries with lifecycle-aware polling and
  transient-error tolerance (the convergence this preserves).
- ADR-0027 Tier 3 — maintainer on-device acceptance still gates this
  behaviour/UX change.
