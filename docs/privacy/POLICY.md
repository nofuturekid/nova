# Privacy Policy — NOVA

**Effective:** 2026-05-21

NOVA is an open-source Android client for Unraid® servers, published under the GPL v3 license. This policy describes what data the app processes, where it goes, and what you can do about it.

## Data the app handles

NOVA accesses the following on your device:

- **Your Unraid server's API key.** You enter this when adding a server. It is stored locally on your device, encrypted at rest using the Android Keystore via Google's Tink library. It never leaves your device except in HTTP requests to your own Unraid server.
- **Your server's network addresses** (local URL, remote URL, name). Stored locally in per-app DataStore. Used only to reach your server.
- **Your theme preferences** (accent colour, density, layout choices). Stored locally in per-app DataStore.

## Data the app sends

NOVA initiates network requests to:

- **Your own Unraid server**, at the addresses you configured, using the Unraid API/Connect plugin's GraphQL endpoint. All app functionality runs through this.
- **GitHub** (`api.github.com`, `objects.githubusercontent.com`), only when:
  - You open Settings (a background check for app updates is initiated), or you press "Check now"
  - You install an in-app update (the APK is downloaded from a GitHub Release asset)

  These requests appear in GitHub's standard server logs with no NOVA-specific identifier beyond the default `User-Agent` header. *(`direct` flavor only — see "Per-flavor differences" below.)*

The app does **not** send data to any other service. No analytics, no telemetry, no crash reporting, no advertising SDKs, no third-party authentication providers.

## What's not collected

- No usage analytics
- No crash reports (Android's system crash UI is unmodified; nothing is sent home)
- No advertising IDs
- No location data
- No contacts, phone state, or calendar access
- No background tracking of any kind

## Backup behaviour

Your API key material and server URLs are explicitly **excluded** from Android cloud backup and device-to-device transfer (per ADR-0034). They live only on the original device. After a device transfer or restore, you re-enter the API key.

## Permissions explained

NOVA declares these Android permissions (visible in the app's permission list under Android Settings):

- **`INTERNET`** — required to reach your Unraid server and GitHub
- **`POST_NOTIFICATIONS`** (Android 13+) — for surfacing your Unraid server's notifications via the system tray. NOVA does not subscribe to push notifications from any server; it polls your Unraid server over your own network.
- **`REQUEST_INSTALL_PACKAGES`** *(`direct` flavor only — GitHub-distributed APK)* — required for the in-app updater to install a newer APK. The F-Droid and Play Store flavors of NOVA do not declare this permission.

## Per-flavor differences

NOVA ships in two flavors that differ only in their distribution channel:

- **`direct`** — installed from the [GitHub Releases page](https://github.com/nofuturekid/nova/releases). Includes the in-app updater (talks to `api.github.com` and downloads APKs from GitHub Releases over HTTPS).
- **`store`** — installed from F-Droid (and, in the future, the Google Play Store). The in-app updater is absent. App updates are managed by your store of choice. No network traffic to GitHub from the running app.

## Update integrity (`direct` flavor only)

In-app updates verify the downloaded APK against a SHA-256 hash and byte-size match published in the GitHub release asset metadata, over HTTPS. Mismatch fails closed — nothing is installed. See [ADR-0034](https://github.com/nofuturekid/nova/blob/main/docs/adr/0034-update-integrity-and-data-at-rest.md) for the rationale.

## Your rights

NOVA stores no personal data on any remote system that we control. Source code is GPL v3 — audit, fork, run your own build. To remove all locally stored NOVA data, uninstall the app; Android removes all per-app data with it.

## Trademark

Unraid® is a registered trademark of Lime Technology, Inc. NOVA is an independent, community-built client; it uses the term solely to describe compatibility with the Unraid® operating system and is not affiliated with, endorsed by, or supported by Lime Technology.

## Changes to this policy

Changes are versioned in this repository under [`docs/privacy/`](https://github.com/nofuturekid/nova/tree/main/docs/privacy). Material changes are mentioned in the app's release notes (`CHANGELOG.md`).

## Contact

Bug reports and questions are best filed as GitHub issues: <https://github.com/nofuturekid/nova/issues>. The maintainer is identified on the GitHub profile.
