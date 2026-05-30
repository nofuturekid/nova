# ADR-0045: Array disk display — sleeping disks, per-disk thresholds, errors

- **Status**: Accepted (updated beta10 — 2026-05-30)
- **Date**: 2026-05-30
- **Tags**: ui, array, data

## Context

Live-verified 2026-05-30: the Unraid server returns `temp = null` for a
spun-down (sleeping) disk, and `isSpinning = false`. The existing `GetArray`
poll did not select `isSpinning`, and the mapper coalesced `null → 0`, so
sleeping disks displayed "0°" in the Array tab — a misleading health signal.

**Beta9 update (2026-05-30):** a further bug was discovered: disk temperature
coloring used hardcoded thresholds (42 °C warn / 50 °C crit) even though Unraid
stores global thresholds in its dynamix display settings (`display.hot` / `display.max`
for disks; `display.warning` / `display.critical` for CPU/system). A 52 °C disk
with the user's global crit=60 was wrongly shown as Danger. The Overview CPU
temperature card was similarly unaware of `display.warning` / `display.critical`.
The beta8 "authoritative/no-mixing" rule (if either per-disk threshold is set,
ignore the other) is **removed** in beta9 — see Decision 4 below.

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
app's global defaults. The precedence logic lives in the pure `Disk.tempLevel()`
extension (`DiskTempLevel` enum) so it can be unit-tested without Compose.

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

### 4. Global display thresholds (beta9 — SUPERSEDES beta8 "authoritative/no-mixing" rule)

Unraid stores global disk and CPU temperature thresholds in the dynamix display
settings, exposed at `query { display { hot max warning critical } }`.
Live-verified 2026-05-30: hot=55 (disk warn), max=60 (disk crit),
warning=85 (CPU warn), critical=95 (CPU crit).

The per-disk `ArrayDisk.warning` / `ArrayDisk.critical` fields are null on most
servers (including spinning cache disks). Relying on hardcoded fallbacks (42/50)
while ignoring the user's global config caused false alarms.

**New threshold precedence (replaces the beta8 "if EITHER set, use only those"
rule):** per threshold, coalesce independently:

```
effectiveWarn = disk.warningC  ?: globalDiskWarnC ?: 42
effectiveCrit = disk.criticalC ?: globalDiskCritC ?: 50
```

where `globalDiskWarnC = display.hot`, `globalDiskCritC = display.max`.

This means per-disk values win when set; global display values fill in for
unset per-disk thresholds; the hardcoded 42/50 are a last resort only when
neither per-disk nor global is available. The beta8 "authoritative/no-mixing"
rule (if either per-disk threshold is set, ignore the fallback for the other)
is **removed** — it caused incorrect results when only one per-disk threshold
was configured.

A new `GetDisplay` query (`query GetDisplay { display { hot max warning critical } }`)
polls these settings every 60 s via `UnraidRepository.displayStream()`. The
`DisplayThresholds` domain model carries the four nullable ints. The ViewModel
exposes a `displayThresholds: StateFlow<DisplayThresholds?>` (null until first
poll), gated on overview-or-array visibility.

The Overview CPU temperature card accent color also switches to use
`display.warning` / `display.critical` when available, falling back to the
sensor-derived `Temperature.cpuWarning` / `Temperature.cpuCritical` flags when
the display poll has not yet returned.

### 5. ZFS pool/mirror secondary members shown as pool members, not empty disks (beta10)

In a ZFS mirror pool the second device (`cache2`) has `fsType = null` and
`fsSize = null` — the pool's filesystem statistics live only on the first member.
The mapper previously derived `usedTb` from `fsUsed` (null → 0) and rendered the
disk as "0% · 3.9 TB", giving a false impression of an empty disk.

**Fix:** `mapDisk` now accepts `fsSizeKb` in addition to the existing `usedKb`.
A non-parity disk with `fsSizeKb == null` is flagged `isPoolMember = true` in
the domain `Disk` model. Parity disks always have `isPoolMember = false` (they
legitimately have no filesystem). The UI suppresses the usage bar/percentage for
pool members and shows a compact "Pool" label with the raw disk size instead.

### 6. Array tab separates "Array" and "Cache" sections (beta10)

In `LayoutMode.List` and `LayoutMode.Grid` the previous single `"Disks"` section
header is replaced with two structural sections:

- **"Array"** — parity disks first, then data disks.
- **"Cache"** — cache/pool disks.

A section is omitted when empty. `LayoutMode.Grouped` (Parity / Data / Cache
sub-headers) is unchanged.

## Consequences

- Sleeping disks no longer show "0°"; they show a moon icon + "Standby".
- Disk temperature coloring uses the user's configured global thresholds
  (display.hot/max) with per-disk overrides and a 42/50 last-resort fallback.
- CPU temperature card accent uses display.warning/critical when available.
- Disk errors are immediately visible without entering a detail view.
- No subscription migration required for the array.
- `unit` field intentionally NOT consumed — no unit conversion in this beta.
- ZFS mirror secondary members no longer show a misleading 0% bar; they show
  a "Pool" pill with the raw disk size.
- The Array tab List and Grid views now clearly separate array disks from cache/pool disks.
