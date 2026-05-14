# ADR-0012: Install-from-Settings duplicates install pipeline (vs. singleton refactor)

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: ui, data

## Context

The in-app updater (ADR-0008) shipped with a single entry point: the banner on `MainScreen`. The Settings screen showed `Latest available: vX.Y.Z` but offered no install action — users had to navigate back to Main to actually update.

The install pipeline (download + `PackageInstaller` session + permission flow + `InstallStatusReceiver` event collection) lived entirely in `MainViewModel`. Two reasonable ways to expose it from a second screen:

1. **Refactor to a `@Singleton UpdateController`** that owns the install state and methods; both `MainViewModel` and `SettingsViewModel` read/forward it.
2. **Duplicate** the ~30 LOC of install logic in `SettingsViewModel`.

Option 1 is cleaner architecturally — there's exactly one install in flight at any time, and a singleton matches that reality. Option 2 keeps the change scoped to the screen that needs it.

## Decision

**Duplicate** the install pipeline in `SettingsViewModel`. Both view-models inject `UpdateInstaller`, hold their own `_installState` flow, collect from `InstallStatusReceiver.events`, and call the same `installer.download` + `installer.install` sequence.

The duplication is exact — same try/catch shape, same permission-exception handling, same flow type. The diff is one ViewModel construction parameter and ~30 lines.

We chose the local fix over the singleton refactor because:
- The behaviour change ships under the beta-first policy (ADR-0006) since it touches the install-end-to-end flow. We want the **smallest possible diff** under that constraint.
- Both screens having independent flows is, in practice, fine: an install is a brief flow (download → system confirm → app pauses), so two ViewModels showing slightly different states is a theoretical concern, not an observed one.
- The `InstallStatusReceiver.events` `SharedFlow` is process-wide; both collectors receive every event, so terminal states (success / failure) reach both screens.

## Consequences

**Positive**
- Settings now has an `Install update` button + `UpdateDialog` overlay. Feature complete.
- The Settings screen also auto-checks on open (replaces a stale `Up to date` default), which improves the UX independently of the install button.
- The duplication is contained and obvious. If we revisit, the singleton extraction is a mechanical refactor.

**Trade-offs**
- ~30 LOC repeated across two ViewModels. Bug fixes to one must be applied to both — currently no automated reminder.
- Two simultaneous `InstallStatusReceiver.events` collectors. Harmless because the events are state-update broadcasts, not commands.
- If a future third entry point is added (e.g. a notification action), the refactor argument gets stronger and the duplication argument gets weaker.

**Trigger to revisit**
- A **third** caller of the install pipeline. At that point the duplication is no longer "occasional" and the singleton refactor pays off.
- Observed bug where the two ViewModels' install states actually diverge in a user-visible way (e.g. one shows "Failed" while the other thinks idle).
- An install ever needs cross-screen orchestration that requires a single source of truth.

## Alternatives considered

- **Singleton `UpdateController`** — cleaner, ~50 LOC new file + edits to two ViewModels and the Hilt module. Reasonable; rejected because of the beta-first scoping pressure. Will be the right call when a third caller appears.
- **Navigate back to Main and trigger the banner programmatically** — fragile cross-screen state passing through nav arguments or a shared SavedStateHandle. More complex than the duplication for worse UX.
- **Lift install state into `SavedStateHandle` of the activity** — same problems as above, against Android's data-flow grain.

## References

- `app/src/main/kotlin/.../ui/screens/main/MainViewModel.kt` — the original install pipeline.
- `app/src/main/kotlin/.../ui/screens/settings/SettingsScreen.kt` (`SettingsViewModel`) — the duplicate that this ADR captures.
- ADR-0008 — the in-app updater this ADR extends.
- ADR-0006 — the beta-first release policy that scoped the change.
