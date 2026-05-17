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

## [0.1.30-beta7] - 2026-05-17

### Changed
- Progress bars, buttons, dialogs and text fields across the app are now
  rebuilt on the standard Material 3 components instead of custom ones.
  Progress bars, dialogs and text fields are an internal modernisation
  with no intended visual change.
- Cancel, Later and Close buttons are now shown in a neutral colour
  instead of the accent colour. This is an intended change so that these
  dismissive actions no longer compete visually with the primary action.
- Install progress is now shown on both the Overview and Settings
  screens (previously only one of them updated while installing).
- Internal Android Gradle Plugin cleanup with no user-facing effect.

## [0.1.30-beta6] - 2026-05-17

### Changed
- Icon-only buttons across the app are now built on the standard
  Material 3 icon-button component instead of a custom one. This is an
  internal modernisation with no intended visual change.

## [0.1.30-beta5] - 2026-05-17

### Changed
- Cards across the app are now built on the standard Material 3 card
  component instead of a custom one. This is an internal modernisation
  with no intended visual change.

## [0.1.30-beta4] - 2026-05-17

### Changed
- Visual consistency: tinted element backgrounds — status pills, info
  panels, disabled buttons, progress tracks, disk-type chips — now use
  one consistent opacity per element type across every screen, aligned
  to Material 3, instead of drifting slightly from screen to screen.
  Some surfaces are marginally lighter or stronger as a result; the
  change is deliberate and uniform.

## [0.1.30-beta3] - 2026-05-17

### Fixed
- Dark Mode: the Overview / Array / Docker / VMs icons in the bottom
  navigation were barely visible after the Material-3 consistency change
  — they are now correctly tinted (accent when selected, muted otherwise).

## [0.1.30-beta2] - 2026-05-17

### Changed
- Accessibility & Material-3 consistency pass: every icon-only button now
  has a screen-reader label; touch targets are at least 48 dp; buttons,
  cards and the bottom navigation give visual press feedback (ripple);
  the bottom navigation now announces the selected tab to TalkBack.
- Filled buttons keep readable text on every accent colour in both
  light and dark themes.

## [0.1.30-beta1] - 2026-05-16

### Fixed
- App update started from the Settings screen no longer interferes with
  the update state shown on the main screen.
- Overview sparklines no longer show a spurious dip roughly once a minute.

### Changed
- Internal: server connections are reused instead of being rebuilt on
  every poll/refresh (lower overhead, no behaviour change).

[Unreleased]: https://github.com/nofuturekid/UnraidControl/compare/v0.1.30-beta3...HEAD
[0.1.30-beta3]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.30-beta3
[0.1.30-beta2]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.30-beta2
[0.1.30-beta1]: https://github.com/nofuturekid/UnraidControl/releases/tag/v0.1.30-beta1
