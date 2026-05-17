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

[Unreleased]: https://github.com/nofuturekid/UnraidControl/compare/v0.1.30...HEAD
[0.1.30]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.30
