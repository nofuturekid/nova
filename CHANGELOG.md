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

## [0.1.44] - 2026-11-13

Nothing new in the app. The build tools and libraries behind it were brought up to date, and the 0.1.44 beta confirmed that nothing looks or behaves differently.

### Changed
- **Build tools and libraries updated** — Kotlin 2.4, Android Gradle Plugin 9.5, Jetpack Compose, okhttp 5.5, Apollo 5.2 and Tink 1.24 (among others) are newer. Nothing should look or behave differently.

## [0.1.43] - 2026-11-12

The parity card appears on a real server, a server that cannot be reached says so in plain words, and a robustness batch (double-tap Install, missing install-permission screen, VM list without zeros). It is also the first version that looks for updates at the project's new home, github.com/hellwand/nova.

### Changed
- **The project moved to github.com/hellwand/nova** — the app now looks
  for updates there, and the links in Settings (source code, releases,
  privacy policy) point to the new address (#270).

### Fixed
- **The parity card now appears while a parity operation runs or is
  paused** — on the Array and Overview screens it used to stay hidden on
  a real server. Because the server reports a parity check, a parity
  sync and a disk rebuild the same way, the card is now titled "Parity
  operation", the button that starts a parity check is hidden while one
  is active, and cancelling asks first and says it stops the running
  operation. The server does not report an error count or the remaining
  time, so the card no longer shows them (#244).
- **Tapping Install twice no longer starts the update twice** — a double
  tap on "Install" in the update dialog now starts exactly one download
  and one install (#241).
- **Settings no longer crashes when the install-permission screen is
  missing** — on a phone without the "install unknown apps" screen, the
  update dialog in Settings now shows "Can't open the install-permission
  screen on this device" instead of closing the app (#242).
- **VMs no longer show "0 vCPU · 0 GB"** — the VM list and the VM details
  hide the CPU, memory and GPU values while the server does not report
  them, instead of showing zeros (#243).
- **A server that cannot be reached now says so in plain words** — the
  error screen shows "Check that the server is online and the address is
  correct." instead of a long technical error text. The technical text is
  still available under "Show details" (#266).

## [0.1.42] - 2026-11-12

Actions that fail now tell you why, safer VM and container controls, live
network speed on current Unraid API versions, a hardened self-signed
certificate and key-storage path, and an icon and screenshots for the
store listing.

### Added
- **Icon and screenshots in the store listing** — the F-Droid listing now
  shows the NOVA icon and six screenshots (Overview, Array, Docker, VMs,
  container details and notifications) (#212, thanks @licaon-kter).

### Changed
- **Container details tabs are accessible** — the Info/Logs/Ports/Volumes
  tabs now use the standard Material tab bar, so screen readers announce
  them as tabs with the selected one marked and the touch targets are at
  least 48 dp high.
- **Memory and network values no longer cut off in the Docker tab and
  container details** — the container list shows memory used, the detail
  view shows used with "of <limit>" below, and long values wrap instead of
  being truncated on narrow phones. The notifications header now reads
  "1 alert · 1 warning" instead of "1 alerts · 1 warnings" (#226).
- **Logs tab explains an empty log** — a running container with no log
  lines now says it may be logging to stderr, which the Unraid API does not
  return yet, instead of a bare "No log lines." (#225).
- **Confirmation before Restart, Update and VM Reboot** — restarting or
  updating a single container and rebooting a VM now ask first, like Stop
  already did (#229).
- **VM "Stop" asks the guest to shut down first** — it used to cut the
  power (#228). Stop now asks for confirmation, sends a shutdown request and
  Unraid powers the VM off if the guest has not shut down within about 10
  seconds. "Force stop" powers off immediately, as before, behind its own
  confirmation.
- **Privacy policy updated** — the policy (effective 2026-11-10) now also
  says that the certificate fingerprint you pin for a self-signed server is
  stored on your device per server and left out of backups; that the app
  checks for updates when it starts as well as in Settings (direct
  download version only); that container icons are loaded straight from
  the addresses your own Unraid server provides, usually third-party hosts
  that can see your IP address; and its list of Android permissions now
  names the internet and network-state permissions (the notification
  permission is no longer listed because the app does not declare it).

### Fixed
- **Network interfaces screen no longer shows blank hardware fields** —
  vendor, model, link speed and the virtual flag are only available with a
  third-party API plugin; rows without data are now left out instead of
  shown empty. On a stock API the Hardware block still shows the MAC
  address (#223).
- **No more background polling** — Settings → Server plugins, Network
  interfaces and the container Logs tab kept refreshing from the server
  while the app was in the background; they now pause until you return.
- **Failed actions now tell you why** — stopping a container or VM, array and
  parity actions and notification actions failed silently when the server was
  unreachable or refused them (for example an API key without permission):
  nothing happened and no message appeared. They now show the reason. A failed
  container stop no longer starts the container again on "Restart", and a
  failed notification action no longer triggers a refresh as if it had worked.
- **Container logs keep the last good lines on a hiccup** — one failed refresh
  of the live log tail replaced the logs with "no logs".
- **Pull-to-refresh actually refreshes** — pulling down now reloads the data
  that is on screen, and the spinner stops as soon as the first refreshed data
  has arrived (or the server did not answer) instead of after a series of
  timeouts.
- **Notification list updates after an action during a refresh** — archiving
  or deleting a notification while a refresh was already running could leave
  the list and the bell badge stale for up to a minute.
- **Live stats for every container** (#224) — on current Unraid API versions
  one container per refresh never got live CPU/memory/IO numbers because the
  server sent its id with terminal control characters attached.
- **Network card shows speed on Unraid 4.35 and newer** — the Overview's
  network card showed no speed on Unraid API 4.35 and newer; it now does.
  Live network speed needs Unraid API 4.35 or newer. Servers below 4.35
  that relied on a third-party API patch for this no longer show network
  speed until the API is updated (#217, thanks @lcadhd).
- **Local self-signed trust hardened** — the server name is now checked for every certificate except the one you pinned, so this no longer depends on other app settings.
- **"Reset certificate" now sticks** — saving or testing a server after resetting its certificate no longer brings the old certificate back; the next connection asks for confirmation again.
- **Saving a server no longer crashes when key storage is broken** — if Android's secure key storage was reset, saving a server used to crash the app every time. It now shows an error with a "Reset key storage" button (after confirmation it deletes all saved API keys, which you then enter again). A save that fails in key storage also no longer leaves a duplicate server, and changing only the API key now takes effect immediately.

## [0.1.41] - 2026-10-06

Small fix release: connection errors now name their actual cause, and the
connection pill keeps showing Local/Remote when the server is unreachable
(#217).

### Changed
- **Connection pill keeps the mode when offline** — when the server
  can't be reached, the top-bar pill now reads "Local · Offline" or
  "Remote · Offline" instead of just "Offline", so you can see which
  address the app is using. Tap it to switch.

### Fixed
- **Error messages name the actual cause** — "Can't reach the server"
  errors now show the underlying reason next to the generic message
  (e.g. "Error while reading JSON response (EOFException: …)"),
  which makes connection problems much easier to report and pin down.

## [0.1.40] - 2026-05-30

Stable promotion of the whole 0.1.40 cycle (beta1…beta10), maintainer
device-accepted. This release brings live, real-time server metrics to
the dashboard and Docker tab, a new temperature card, per-container
resource stats, and a batch of Array-tab accuracy improvements.

### Added
- **Live, real-time server metrics** — the dashboard's CPU and Memory
  cards, the Network card's upload/download speed, and per-container
  CPU/memory on the Docker tab now update live in real time via the
  server, instead of refreshing every couple of seconds. If the live
  connection drops, every card automatically falls back to the usual
  periodic refresh, so nothing goes blank.
- **Temperature card on the dashboard** — shows your server's current
  CPU and system temperature live on two separate lines, with the hottest
  sensor highlighted; it turns amber or red when a sensor crosses your
  warning or critical threshold.
- **Per-container resource stats** — the container detail view now shows
  live CPU and memory usage plus cumulative Network and Disk I/O totals
  (labelled as running totals, since they are counted since the container
  started).

### Changed
- **Array tab separates Array from Cache/pool disks** — array disks and
  cache/pool disks now appear in distinct "Array" and "Cache" sections
  in both List and Grid layouts.

### Fixed
- **Sleeping disks show "Standby"** — spun-down array disks now show a
  moon icon and "Standby" instead of a misleading "0°" temperature.
- **Disk errors are surfaced** — each disk card now shows its I/O error
  count as a danger pill in both grid and list layouts.
- **Temperature warnings use your Unraid thresholds** — disk and CPU
  temperature warnings now respect your Unraid global thresholds (and
  per-disk overrides) instead of fixed defaults, so a disk within your
  configured limits is no longer flagged as overheating.
- **ZFS pool members shown correctly** — a ZFS mirror's second disk now
  appears as a pool member (with a "Pool" label and its raw size) instead
  of an empty disk with a misleading 0% usage bar.

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
