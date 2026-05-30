# Changelog

All notable user-facing changes are documented here in plain language.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project versions per [ADR-0005](docs/adr/0005-prerelease-tag-convention.md)
(`-beta1` etc.). `release.yml` slices the section matching the pushed tag
into the GitHub Release body (the in-app updater shows it verbatim);
GitHub's auto "What's Changed" + compare link are appended below it
(ADR-0010 / ADR-0031).

Contributor rule: every PR that changes user-facing behaviour adds a
bullet under `## [Unreleased]` in the right group (`Added` / `Changed` /
`Fixed`). The version-bump PR (ADR-0015) renames `[Unreleased]` to the
release version + date and opens a fresh `[Unreleased]`.

## [Unreleased]

## [0.1.40-beta9] - 2026-05-30

### Fixed
- Disk and CPU temperature warnings now use your Unraid global temperature thresholds (and per-disk overrides) instead of fixed defaults. A 52 °C disk was previously shown as overheating when the user's configured critical threshold is 60 °C; it now correctly shows as normal.

## [0.1.40-beta8] - 2026-05-30

### Fixed
- Sleeping (spun-down) array disks now show a moon icon and "Standby" instead of "0°". The server returns null temperature for a spun-down disk; the previous release coalesced that to 0 and displayed it as a real temperature reading.

### Added
- Array disk cards now show per-disk temperature thresholds from Unraid (warning/critical) when configured, falling back to the previous hardcoded defaults (42 °C / 50 °C) when not set.
- Disk errors (I/O error count) are now shown as a danger pill directly on each disk card in both grid and list layouts.

## [0.1.40-beta7] - 2026-05-30

### Changed
- The container detail view now labels Network and Disk I/O as running totals (they are cumulative since the container started, not live rates).

## [0.1.40-beta6] - 2026-05-30

### Fixed
- The container detail view now shows live CPU, memory, network and disk I/O (previously it showed 0).

## [0.1.40-beta5] - 2026-05-30

### Changed
- The live network card now keeps updating via a periodic refresh if the real-time connection drops, instead of showing unavailable.

## [0.1.40-beta4] - 2026-05-30

### Changed
- The temperature card now shows CPU and system temperature as two separate lines.

## [0.1.40-beta3] - 2026-05-30

### Fixed
- The temperature card mistook the CPU fan speed and voltage sensors for temperatures (showed ~91° / a 1753° "CPU Fan"); it now reads only real temperature sensors.

## [0.1.40-beta2] - 2026-05-30

### Added
- The Network card on the dashboard now shows live upload/download speed in real time.
- A new CPU-temperature card on the dashboard shows your server's current temperature live, with the hottest sensor highlighted; it turns amber or red if a sensor crosses a warning or critical threshold.
- The Docker tab now shows live CPU and memory usage for each running container, updating in real time.

### Changed
- The dashboard's CPU and Memory cards now update live, in real time, instead of refreshing every couple of seconds. If the live connection drops, they automatically fall back to the usual 2-second refresh so the cards never go blank.

## [0.1.39] - 2026-05-29

Stable promotion of the 0.1.39 cycle (beta1…beta3), maintainer
device-accepted. This release reworks how you add and connect to a server
— a clean host-entry form replaces the free-text URL field — and adds
opt-in trust for local servers running HTTPS with a self-signed certificate
(ADR-0041).

### Added
- **Add a server by entering just its address and flipping an SSL switch** — the old free-text URL field (where you had to type `http://` or `https://` yourself) is replaced with a host/IP field, an optional port field, and a simple SSL toggle. The app composes the URL for you. Existing saved servers are unaffected — no re-entry needed. You can also paste a full address (with `http://` or `https://`) and the app strips the scheme for you.
- **Connect to a local server that uses a self-signed HTTPS certificate** — a new "Trust self-signed certificate (local only)" switch appears in the Local connection settings when SSL is on. Enable it, tap **Test Local**, and the app shows you the certificate's SHA-256 fingerprint; tap **Trust** once and the app remembers it. Re-testing a server reuses the stored pin automatically — no re-confirmation needed. If the certificate ever changes (e.g. you regenerated it), the app blocks and asks you to review and re-accept the new fingerprint. A **"Reset certificate"** button in settings forgets the stored pin. This trust is local-endpoint-only — remote connections always require a valid CA-signed certificate.

## [0.1.38] - 2026-05-21

Stable promotion of the 0.1.38 cycle, maintainer device-accepted. UI
polish that makes the tap-actionable cards/rows visually obvious.

### Changed
- **Visible affordance hints on tap-actionable cards/rows** — adds chevron indicators across the app so it's obvious which cards expand or drill in. In-place-expand rows (Settings → Server plugins → Recent operations, Settings → Network interfaces → per-NIC details) gain a chevron-down that rotates to chevron-up when expanded. Drill-in cards (Docker containers in both list and grid layouts; VMs in both list and grid layouts) gain a trailing chevron-right matching the existing Settings convention. For grid tiles, the chevron sits in the bottom-right corner where it doesn't compete with content. No functional change — purely a "tap-here works" visual cue.

## [0.1.37] - 2026-05-21

Stable promotion of the 0.1.37 cycle, maintainer device-accepted. A
build-config-only release that unblocks the F-Droid submission path
by stripping AGP's "Dependency metadata" extra signing block from the
published APK. No functional or user-visible change.

### Changed
- **Build: strip AGP's "Dependency metadata" extra signing block from the APK** — added the `dependenciesInfo { includeInApk = false; includeInBundle = false }` Gradle block (standard F-Droid compatibility shim). No functional impact on either flavor; no user-visible difference. Required because F-Droid's `check apk` rejects extra signing blocks beyond the standard JAR + APK signing schemes. Unblocks the in-flight F-Droid submission ([gitlab.com/fdroid/fdroiddata!38801](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/38801)).

## [0.1.36] - 2026-05-21

Stable promotion of the 0.1.36 cycle, maintainer device-accepted on
the direct flavor. A targeted privacy/correctness fix closing the
last GitHub-request paths in the `store` flavor — pre-work for the
F-Droid metadata submission.

### Fixed
- **Store flavor truly makes zero GitHub requests at runtime** — the 0.1.35 distribution-flavors split (ADR-0040) guarded only the Settings → Updates UI. `MainViewModel.init` (every app launch via Overview) and `SettingsViewModel.init` (every Settings-open) still called `checkForUpdate()` / `checkNow()` unconditionally, and the Overview's update banner + `UpdateDialog` were rendered unconditionally. All three layers (both ViewModel init blocks + all network-touching methods + Overview banner + dialog) are now guarded by `BuildConfig.HAS_UPDATER`. The store flavor now genuinely contacts only the user's own Unraid server — matching the Privacy Policy and ADR-0040 promises. Defense-in-depth guards on `installUpdate`/`resetInstall`/`launchPermissionIntent` in both ViewModels. No behaviour change in the `direct` flavor.

## [0.1.35] - 2026-05-21

Stable promotion of the 0.1.35 cycle, maintainer device-accepted. This
release opens the F-Droid + Play distribution path (per ADR-0040) and
ships a formal Privacy Policy referenced from both app and stores.

### Added
- **Distribution flavors** — the app now builds in two product flavors: **`direct`** (status quo — GitHub Releases with the in-app updater intact) and **`store`** (F-Droid + future Play distribution, with the in-app updater UI hidden and `REQUEST_INSTALL_PACKAGES` excluded from the manifest). Same app from the user's perspective — same `applicationId`, same UI minus the Updates section in store builds. Per ADR-0040.
- **Privacy Policy** — formal policy at [`docs/privacy/POLICY.md`](https://github.com/nofuturekid/nova/blob/main/docs/privacy/POLICY.md), linked from Settings → About (new "Privacy Policy ›" row between "Releases" and "Open-source licenses"); README "Privacy & data handling" section gains a link to the full policy. No behavioural change — formalises the existing data-handling model (API-key on-device-encrypted, no telemetry, no third-party services in either flavor).

## [0.1.34] - 2026-05-21

Stable promotion of the whole 0.1.34 cycle (beta1…beta5), maintainer
device-accepted. This release adds two new read-only Settings screens
for inspecting your Unraid server — **Server plugins** and
**Network interfaces** — plus a Settings → About cleanup and the
repo-rename housekeeping that closes out the 0.1.33 cycle's NOVA
brand transition.

### Added
- **Settings → Server plugins** — read-only inventory of installed `.plg` files (top section), Unraid API modules with name/version/module flags (middle section), and the install-operation history with status pills and expandable per-operation output log (bottom section). Powered by the upstream `installedUnraidPlugins`, `plugins`, and `pluginInstallOperations` GraphQL queries.
- **Settings → Network interfaces** — read-only NIC inventory with IPv4 + IPv6 addressing (address, netmask, gateway, DHCP-state), hardware info (MAC, vendor, model, link speed, virtual-flag), and a marker for the primary management interface. Live RX/TX byte counters are not included — Unraid's GraphQL API doesn't expose those yet (tracked upstream).

### Changed
- **Settings → About** — regrouped the two GitHub links ("Source code" and the new "Releases") as paired SettingRow entries directly above "Open-source licenses"; the trademark disclaimer moved to the bottom of the card as footer text.
- **Repo rename housekeeping** — `nofuturekid/UnraidControl → nofuturekid/nova` (the final step of ADR-0039). README badge URLs, Live Interactive Preview URL, ADR-0033 reference URLs, in-app About URLs, and the `UpdateRepository.REPO` constant all flip to the new repo path.

## [0.1.33] - 2026-05-20

Renamed from `UnraidControl` to **NOVA** for compliance with the Lime Technology Unraid® Trademark Policy (§3 prohibits compound names combining "Unraid" with another word). See [ADR-0038](docs/adr/0038-lime-trademark-compliance-rename-pending.md) and [ADR-0039](docs/adr/0039-rename-to-nova.md).

### Heads-up — existing installs do not auto-update

The Android `applicationId` changed from `net.unraidcontrol.app` to `io.github.nofuturekid.nova`. From Android's view, NOVA is a new app — your old UnraidControl install stays on `0.1.33-beta2` and no longer receives updates. **Install NOVA fresh from the [Releases page](https://github.com/nofuturekid/nova/releases)**, re-enter your server's API key, and uninstall the old app. Server entries and theme preferences live in per-app DataStore and don't migrate — this is a one-time clean break.

### Added
- **Settings → About** — app name + version, GPL v3 + source-code link, full Unraid® trademark attribution paragraph (per ADR-0038 / §6), and an open-source license list (powered by AboutLibraries).

### Changed
- **Renamed to NOVA — NAS Operations Viewer Anywhere.** Brand mark, `applicationId`, Kotlin package, AGP namespace, Apollo generated package, `app_name`, Application class (`NovaApp`), splash/Material theme (`Theme.Nova`), launcher icon (sparkle 4-pointed-star placeholder), local APK download filename, release-asset name (`NOVA-vX.Y.Z.apk`), and README / HANDOFF prose all flip to NOVA. Repo URL stays at `nofuturekid/UnraidControl` for this Stable — the repo rename follows in a separate post-Stable step (Step 4 of the ADR-0039 plan).
- **Deprecation banner** shipped one cycle ago in `0.1.33-beta2` so existing users were warned about the non-auto-updating applicationId change before it landed.

## [0.1.32] - 2026-05-19

Stable promotion of the whole 0.1.32 cycle (beta1…beta3), maintainer
device-accepted. This release delivers a Material 3 modernization and
accessibility batch, resilient action launchers, and an install
double-tap guard.

### Changed
- Notifications Unread/Archived tabs now use the non-deprecated Material 3
  tab row; the selected-tab indicator follows the accent colour.

### Fixed
- The Settings screen now scrolls on short screens (and in landscape), so
  no content is clipped on small devices.
- On-accent text and icons now use the semantic Material 3 colour token,
  so they stay visible on light accent colours (e.g. Amber) instead of
  rendering near-invisible; dark accents are unchanged.
- Notification & server actions (archive/delete, container/VM/array/parity
  start-stop, …) no longer crash or silently fail on a flaky connection —
  failures surface a transient message and the UI reconciles with the
  server (triage #19, ADR-0037).
- Double-tapping Install no longer starts two downloads / install
  sessions — a second install request while one is in progress is
  ignored (triage #22).

### Accessibility
- The Settings toggles now use the Material 3 Switch, so screen readers
  announce them with the correct switch role and on/off state.

## [0.1.31] - 2026-05-18

Stable promotion of the whole 0.1.31 cycle (beta1…beta9), device-verified.
This release delivers actionable notifications, a security-hardening pass
for app updates and data-at-rest, and a batch of code-review fixes.

### Added
- The notifications sheet now has Unread and Archived tabs showing all
  notifications (including info-level ones). You can mark notifications
  as read or archived, restore archived ones, delete them individually,
  archive all unread at once, or delete all archived at once.

### Changed
- The notifications indicator in the top bar now shows a bell icon
  instead of an information (ⓘ) symbol, and its badge counts every
  unread notification (info-level ones are no longer omitted, so the
  number matches the list).

### Security
- App updates are now verified before install: the downloaded APK must
  match both the expected byte size and a constant-time SHA-256 of the
  GitHub release asset, the download URL must be HTTPS, and a mismatch
  fails closed (nothing is installed). (ADR-0034)
- Network security hardening: cleartext HTTP still works for your local
  Unraid server on the LAN, but the GitHub update hosts are now
  HTTPS-only. (ADR-0034)
- Backup and device-transfer now exclude the API-key material and your
  server URLs, so they are no longer copied into a cloud backup or a
  new-device transfer. (ADR-0034)
- The API-key store now tells "no key saved" apart from "a key is saved
  but can't be decrypted": the second case shows a clear re-enter prompt
  instead of a misleading "Missing API key". (ADR-0035, amends ADR-0024)

### Fixed
- Switching servers no longer briefly shows the previous server's data
  under the new server's name.
- The notifications list now loads correctly against real Unraid servers
  (fixed required pagination parameters).
- The delete-notification confirmation dialog no longer stays on screen
  after deleting (both active and archived notifications).
- The container log tab's live-tail loop no longer swallows coroutine
  cancellation, so closing the sheet or stopping the container stops the
  loop cleanly without a stale log overwrite.
- HTTP request logging is now disabled in release builds (it was only
  ever meant for debug).
- The Add/Edit-server form no longer retains a previous session's state:
  opening it always shows exactly the selected server (blank for Add).
- If the install-permission screen can't be opened on a device (a ROM
  without that settings activity), the updater now shows an error
  instead of silently resetting with no feedback.
- The Docker poll gate now updates without a one-frame lag when the
  container sheet opens or closes, avoiding a needless poll
  cancel/restart.
- In-app update no longer hangs if the install-result broadcast arrives
  before the app finishes (re)starting.

## [0.1.30] - 2026-05-17

Stable promotion of the whole 0.1.30 cycle (beta1…beta7), device-verified.
This release completes the ADR-0030 UI-modernisation roadmap: the app is
now built on standard Material 3 components throughout, with an
accessibility and visual-consistency pass.

### Fixed
- App update started from the Settings screen no longer interferes with
  the update state shown on the main screen.
- Overview sparklines no longer show a spurious dip roughly once a minute.
- Dark Mode: the Overview / Array / Docker / VMs icons in the bottom
  navigation were barely visible after the Material-3 consistency change
  — they are now correctly tinted (accent when selected, muted otherwise).

### Changed
- Cards, icon-only buttons, progress bars, buttons, dialogs and text
  fields across the app are now rebuilt on the standard Material 3
  components instead of custom ones. This is an internal modernisation
  with no intended visual change.
- Accessibility & Material-3 consistency pass: every icon-only button now
  has a screen-reader label; touch targets are at least 48 dp; buttons,
  cards and the bottom navigation give visual press feedback (ripple);
  the bottom navigation now announces the selected tab to TalkBack.
- Filled buttons keep readable text on every accent colour in both
  light and dark themes.
- Cancel, Later and Close buttons are now shown in a neutral colour
  instead of the accent colour. This is an intended change so that these
  dismissive actions no longer compete visually with the primary action.
- Visual consistency: tinted element backgrounds — status pills, info
  panels, disabled buttons, progress tracks, disk-type chips — now use
  one consistent opacity per element type across every screen, aligned
  to Material 3, instead of drifting slightly from screen to screen.
  Some surfaces are marginally lighter or stronger as a result; the
  change is deliberate and uniform.
- Install progress is now shown on both the Overview and Settings
  screens (previously only one of them updated while installing).
- Internal: server connections are reused instead of being rebuilt on
  every poll/refresh (lower overhead, no behaviour change).
- Internal Android Gradle Plugin cleanup with no user-facing effect.
