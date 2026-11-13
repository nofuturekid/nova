# Privacy Policy — NOVA

**Effective:** 2026-11-10

NOVA is an open-source Android client for Unraid® servers, published under the GPL v3 license. This policy describes what data the app processes, where it goes, and what you can do about it.

## Data the app handles

NOVA accesses the following on your device:

- **Your Unraid server's API key.** You enter this when adding a server. It is stored locally on your device, encrypted at rest using the Android Keystore via Google's Tink library. It never leaves your device except in HTTP requests to your own Unraid server.
- **Your server's network addresses** (local URL, remote URL, name). Stored locally in per-app DataStore. Used only to reach your server.
- **Your theme preferences** (accent colour, density, layout choices). Stored locally in per-app DataStore.
- **Pinned certificate fingerprints** (only if you enable "Trust self-signed certificate" for a server's local connection): the SHA-256 fingerprint of that server's certificate, stored per server in per-app DataStore and excluded from cloud backup and device transfer.

## Data the app sends

NOVA initiates network requests to:

- **Your own Unraid server**, at the addresses you configured, using the Unraid API/Connect plugin's GraphQL endpoint. All app functionality runs through this.
- **GitHub** (`api.github.com`), only when the app starts, when it returns from the background, when you come back to the main screen from another screen (such as Settings or the server list), when you open Settings, or when you press "Check now" (a check for app updates runs then).

  These requests appear in GitHub's standard server logs with no NOVA-specific identifier beyond the default `User-Agent` header. *(`direct` flavor only — see "Per-flavor differences" below.)*

  When you tap "Download" on an available update, the app makes no request itself: it asks your browser to open the release's APK (or its release page) on GitHub. That download is made by your browser, under the browser's own headers and privacy terms.

- **Container icon hosts** (both flavors). The Docker tab and container details show each container's icon. The icon URL is supplied by your own Unraid server (from the container's template), and is usually hosted by a third party chosen by the template author — for example `raw.githubusercontent.com`, `cdn.jsdelivr.net` or `github.com`. The app fetches these URLs directly, so those hosts see your IP address and the standard request headers. NOVA never adds credentials or identifiers to these requests, and decides neither which hosts are contacted nor which icons exist: that comes from the templates on your server.

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

- **`INTERNET`** — required to reach your Unraid server, container icon hosts and (`direct` flavor) GitHub
- **`ACCESS_NETWORK_STATE`** — lets the app check whether a network is available

NOVA does not declare `REQUEST_INSTALL_PACKAGES` in any flavor: it never installs apps itself. Updates of the GitHub APK are downloaded and opened by your browser and Android's installer. (Versions up to 0.1.46 did declare it, in the GitHub APK only.)

## Per-flavor differences

NOVA ships in two flavors that differ only in their distribution channel:

- **`direct`** — installed from the [GitHub Releases page](https://github.com/hellwand/nova/releases). Checks for updates (talks to `api.github.com`) and offers the new APK as a browser download from GitHub Releases; it does not install updates itself.
- **`store`** — installed from F-Droid (and, in the future, the Google Play Store). The update check is absent. App updates are managed by your store of choice. No GitHub traffic for app updates or update checks. Container icons are still fetched from the URLs your server provides (see "Data the app sends").

## Update integrity (`direct` flavor only)

The app does not download or install updates, so it cannot verify the file. The update sheet shows the SHA-256 that GitHub lists for the APK (when GitHub provides one) so you can compare it with the downloaded file. The update check runs over HTTPS and refuses any link that is not an HTTPS release link of the NOVA repository on GitHub. See [ADR-0034](https://github.com/hellwand/nova/blob/main/docs/adr/0034-update-and-data-at-rest-hardening.md) and [ADR-0046](https://github.com/hellwand/nova/blob/main/docs/adr/0046-update-via-browser-download.md).

## Your rights

NOVA stores no personal data on any remote system that we control. Source code is GPL v3 — audit, fork, run your own build. To remove all locally stored NOVA data, uninstall the app; Android removes all per-app data with it.

## Trademark

Unraid® is a registered trademark of Lime Technology, Inc. NOVA is an independent, community-built client; it uses the term solely to describe compatibility with the Unraid® operating system and is not affiliated with, endorsed by, or supported by Lime Technology.

## Changes to this policy

Changes are versioned in this repository under [`docs/privacy/`](https://github.com/hellwand/nova/tree/main/docs/privacy). Material changes are mentioned in the app's release notes (`CHANGELOG.md`).

## Contact

Bug reports and questions are best filed as GitHub issues: <https://github.com/hellwand/nova/issues>. The repository is owned by the GitHub organization `hellwand`; the maintainer is identified on the GitHub profile <https://github.com/nofuturekid>.
