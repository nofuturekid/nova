# ADR-0008: In-app updater via `REQUEST_INSTALL_PACKAGES` + `PackageInstaller`

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: ui, security, release

## Context

The app is distributed via GitHub Releases, not the Play Store. Without Play's auto-update, users would have to: (1) notice a new release, (2) open the GitHub page, (3) download the APK, (4) tap to install, (5) navigate Android's "install from unknown sources" flow for each install. That friction kills uptake — and we ship often enough that the friction compounds.

We're also not eligible for Play in-app-updates (`com.google.android.play:play`) because we're not on Play.

## Decision

Build a **full in-app installer** using Android's standard `PackageInstaller` API:

- **Permission**: `REQUEST_INSTALL_PACKAGES` declared in `AndroidManifest.xml`. On first install the user grants it via a system "Install unknown apps" page; the app deep-links there via `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
- **Repository**: `UpdateRepository` polls the GitHub Releases REST API (`/releases?per_page=20`) using a separate OkHttp client (no Unraid `x-api-key`). Parses with kotlinx-serialization. Filters by `prerelease`/`includePrereleases` and version-compares against `BuildConfig.VERSION_NAME`.
- **Installer**: `UpdateInstaller` downloads the APK to `cacheDir/updates/`, opens a `PackageInstaller.Session`, copies bytes, commits with a `PendingIntent`. Android shows its standard install-confirm dialog; the user confirms; on success the new version launches.
- **Receiver**: `InstallStatusReceiver` is a `BroadcastReceiver` for the session callback. Emits a `SharedFlow<InstallEvent>` consumed by the ViewModel.
- **UI**: Banner on `MainScreen` when an update is available; bottom-sheet `UpdateDialog` with release notes, version, size, install button, and progress. (ADR-0012 covers the Settings entry-point.)

Auto-check fires once on app launch. Manual "Check now" available in Settings. No background `WorkManager` schedule.

## Consequences

**Positive**
- Users get betas/stables with one tap per release.
- The whole flow uses public, stable Android APIs — no root, no special install tooling.
- We keep distribution control (no Play review, no 2.5 GB Play console).

**Trade-offs**
- `REQUEST_INSTALL_PACKAGES` flags the app on Play scanners. Irrelevant for us, but if we ever submit to Play it would draw scrutiny.
- The user must grant the per-app install permission once. We mitigate with an in-app "Open settings" button that deep-links to the right page.
- Signature pinning: Android refuses to update an installed APK with a differently-signed one. We must keep the release keystore safe across the project's life. Documented in HANDOFF (and the keystore lives in `/home/claude/SECRETS_SUMMARY.md`, not the repo).
- Auto-install (no user-confirm) is not possible without device-owner privileges; we accept the system confirm dialog as the right security boundary.

**Trigger to revisit**
- If we ever publish to Play, the `REQUEST_INSTALL_PACKAGES` permission will be questioned — at that point switch to Play in-app-updates conditionally.
- If APK size grows past ~10 MB, consider differential updates (APK patches) to save user bandwidth.

## Alternatives considered

- **External browser to GitHub Releases.** Highest friction. Status quo before this ADR.
- **F-Droid distribution.** F-Droid handles updates natively but requires OSI-approved license (we're CC-NC-SA, ADR-0002).
- **Self-hosted update server.** Adds infrastructure for zero benefit over GitHub's free REST API.
- **Auto-install without confirm.** Requires device-owner / system-app privileges. Inappropriate for a user-installed app and not actually possible on stock Android.

## References

- `app/src/main/AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES`.
- `app/src/main/kotlin/.../data/update/UpdateRepository.kt`
- `app/src/main/kotlin/.../data/update/UpdateInstaller.kt`
- `app/src/main/kotlin/.../data/update/InstallStatusReceiver.kt`
- `app/src/main/kotlin/.../ui/screens/update/UpdateBanner.kt`, `UpdateDialog.kt`
- ADR-0005 — pre-release tag convention used by the updater's filter.
- ADR-0012 — Settings entry-point (added later).
