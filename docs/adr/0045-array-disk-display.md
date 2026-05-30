# ADR-0045: Array disk display — sleeping disks, per-disk thresholds, errors

- **Status**: Accepted
- **Date**: 2026-05-30
- **Tags**: ui, array, data

## Context

Live-verified 2026-05-30: the Unraid server returns `temp = null` for a
spun-down (sleeping) disk, and `isSpinning = false`. The existing `GetArray`
poll did not select `isSpinning`, and the mapper coalesced `null → 0`, so
sleeping disks displayed "0°" in the Array tab — a misleading health signal.

## Decisions

### 1. Sleeping disks show "Standby" instead of "0°"

The `GetArray` poll now selects `isSpinning` (and `rotational`, `numErrors`,
`warning`, `critical`) for all disk groups (parities, disks, caches).
The domain `Disk` model carries `isSpinning: Boolean` as the authoritative
truth source for whether `tempC` is meaningful. When `isSpinning = false`,
both the grid tile and the list row replace the thermometer + temperature with
a moon icon (`UC.Standby` → `Icons.Outlined.Bedtime`) and the label "Standby"
(grid: icon + label if space, else icon only; list: icon + "Standby").

`tempC` is kept as `Int` (not `Int?`) to avoid a wider refactor — the
existing rendering code and tests only need to consult `isSpinning` before
using the value. KDoc on `Disk.tempC` documents this contract.

### 2. Richer disk fields surfaced over the existing 5 s poll

The following fields are now selected and forwarded to the domain model:

| Field       | Type    | Domain field  | Null treatment                      |
|-------------|---------|---------------|-------------------------------------|
| isSpinning  | Boolean | isSpinning    | null → false                        |
| rotational  | Boolean | rotational    | null → false                        |
| numErrors   | BigInt  | numErrors     | null → 0L                           |
| warning     | Int     | warningC      | null or ≤ 0 → null (unset)          |
| critical    | Int     | criticalC     | null or ≤ 0 → null (unset)          |

Per-disk temperature thresholds (warning/critical) take precedence over the
app's global defaults (42 °C warn / 50 °C danger). The precedence logic lives
in the pure `Disk.tempLevel()` extension (`DiskTempLevel` enum) so it can be
unit-tested without Compose.

Disk errors (`numErrors > 0`) are shown as a `"N err"` danger pill on both
the grid tile and the list row.

### 3. The array deliberately stays on the 5 s poll — NOT the subscription

`arraySubscription` was live-verified 2026-05-30 and works correctly. However,
measured event frequency is ~10 s (one heartbeat per second from the server
results in a practical update cadence of 10 s). The existing `GetArray` poll
runs every 5 s and is **faster**. Furthermore, the poll is foreground-gated
(paused when the app is backgrounded), so there is no battery benefit to
switching to the subscription. The array therefore deliberately remains on the
5 s poll for this beta; the subscription path is available if the cadence
changes upstream.

## Consequences

- Sleeping disks no longer show "0°"; they show a moon icon + "Standby".
- Per-disk Unraid thresholds drive temperature colour coding; the app defaults
  (42 / 50 °C) remain as fallback for servers that have not configured them.
- Disk errors are immediately visible without entering a detail view.
- No subscription migration required for the array.
