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

## [0.1.32-beta3] - 2026-05-19

### Fixed
- Double-tapping Install no longer starts two downloads / install
  sessions — a second install request while one is in progress is
  ignored (triage #22).

## [0.1.32-beta2] - 2026-05-19

### Fixed
- Notification & server actions (archive/delete, container/VM/array/parity
  start-stop, …) no longer crash or silently fail on a flaky connection —
  failures surface a transient message and the UI reconciles with the
  server (triage #19, ADR-0037).

## [0.1.32-beta1] - 2026-05-18

ADR-0030 post-P7 follow-ups (F1–F4): a small Material 3 modernization and
accessibility batch shipped as one combined beta.

### Changed
- Notifications Unread/Archived tabs now use the non-deprecated Material 3
  tab row; the selected-tab indicator follows the accent colour.

### Fixed
- The Settings screen now scrolls on short screens (and in landscape), so
  no content is clipped on small devices.
- On-accent text and icons now use the semantic Material 3 colour token,
  so they stay visible on light accent colours (e.g. Amber) instead of
  rendering near-invisible; dark accents are unchanged.

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

## [0.1.31-beta9] - 2026-05-18

### Fixed
- In-app update no longer hangs if the install result broadcast arrives
  before the app finishes (re)starting (#10).

## [0.1.31-beta8] - 2026-05-18

### Fixed
- Switching servers no longer briefly shows the previous server's data
  under the new server's name (#3).

## [0.1.31-beta7] - 2026-05-18

### Fixed
- The container log tab's live-tail loop no longer swallows coroutine
  cancellation, so closing the sheet or stopping the container stops the
  loop cleanly without a stale log overwrite. (#4)
- HTTP request logging is now disabled in release builds (it was only
  ever meant for debug). (#5)
- The Add/Edit-server form no longer retains a previous session's state:
  opening it always shows exactly the selected server (blank for Add).
  (#7)
- If the install-permission screen can't be opened on a device (a ROM
  without that settings activity), the updater now shows an error
  instead of silently resetting with no feedback. (#9)
- The Docker poll gate now updates without a one-frame lag when the
  container sheet opens or closes, avoiding a needless poll
  cancel/restart. (#17)

## [0.1.31-beta6] - 2026-05-18

### Security
- App updates are now verified before install: the downloaded APK must
  match both the expected byte size and a constant-time SHA-256 of the
  GitHub release asset, the download URL must be HTTPS, and a mismatch
  fails closed (nothing is installed). (ADR-0034, triage #1)
- Network security hardening: cleartext HTTP still works for your local
  Unraid server on the LAN, but the GitHub update hosts are now
  HTTPS-only. (ADR-0034, #2)
- Backup and device-transfer now exclude the API-key material and your
  server URLs, so they are no longer copied into a cloud backup or a
  new-device transfer. (ADR-0034, #12)

### Fixed
- The API-key store now tells "no key saved" apart from "a key is saved
  but can't be decrypted": the second case shows a clear re-enter prompt
  instead of a misleading "Missing API key". (ADR-0035, amends ADR-0024,
  #8)

## [0.1.31-beta5] - 2026-05-17

### Added
- You can now delete all archived notifications at once from the Archived
  tab.

## [0.1.31-beta4] - 2026-05-17

### Fixed
- The notifications bell badge now counts all unread notifications (it
  previously omitted info-level ones, so the number didn't match the
  list).

## [0.1.31-beta3] - 2026-05-17

### Fixed
- The delete-notification confirmation dialog stayed on screen after
  deleting (both active and archived notifications).

## [0.1.31-beta2] - 2026-05-17

### Fixed
- The notifications list failed to load against real Unraid servers;
  fixed required pagination parameters.

## [0.1.31-beta1] - 2026-05-17

### Added
- The notifications sheet now has Unread and Archived tabs showing all
  notifications (including info-level ones), and you can mark notifications
  as read/archived, restore archived ones, delete them individually, or
  archive all unread at once.

### Fixed
- The notifications indicator in the top bar now shows a bell icon
  instead of an information (ⓘ) symbol.

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

[Unreleased]: https://github.com/nofuturekid/UnraidControl/compare/v0.1.32...HEAD
[0.1.32]: https://github.com/nofuturekid/UnraidControl/compare/v0.1.32-beta3...v0.1.32
[0.1.32-beta3]: https://github.com/nofuturekid/UnraidControl/compare/v0.1.32-beta2...v0.1.32-beta3
[0.1.32-beta2]: https://github.com/nofuturekid/UnraidControl/compare/v0.1.32-beta1...v0.1.32-beta2
[0.1.32-beta1]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.32-beta1
[0.1.31]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31
[0.1.31-beta9]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta9
[0.1.31-beta8]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta8
[0.1.31-beta7]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta7
[0.1.31-beta6]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta6
[0.1.31-beta5]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta5
[0.1.31-beta4]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta4
[0.1.31-beta3]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta3
[0.1.31-beta2]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta2
[0.1.31-beta1]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.31-beta1
[0.1.30]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.30
