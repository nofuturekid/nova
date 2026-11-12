<div align="center">

# NOVA — NAS Operations Viewer Anywhere

**NOVA for Unraid® — your NAS in your pocket. A fast, native Android app for keeping an eye on — and a hand on — your Unraid® server.**

[![Live Interactive Preview — no install](https://img.shields.io/badge/%E2%96%B6%20Live%20Interactive%20Preview-no%20install-22d3a4?style=for-the-badge&logo=googlechrome&logoColor=white)](https://nofuturekid.github.io/nova/)

_See the actual UI in your browser — sample data, no server needed (snapshot of the 0.1.31 cycle)._

[![Stable](https://img.shields.io/github/v/release/nofuturekid/nova?sort=semver&label=stable&color=blue&style=flat-square&logo=github)](https://github.com/nofuturekid/nova/releases/latest)
[![Beta](https://img.shields.io/github/v/release/nofuturekid/nova?include_prereleases&label=beta&color=orange&style=flat-square&logo=github)](https://github.com/nofuturekid/nova/releases)
[![Downloads](https://img.shields.io/github/downloads/nofuturekid/nova/total?label=downloads&style=flat-square&logo=github)](https://github.com/nofuturekid/nova/releases)

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/nofuturekid/nova/releases)
[![Min Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/nofuturekid/nova/releases)
[![Made with Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)

[![CI](https://img.shields.io/github/actions/workflow/status/nofuturekid/nova/ci.yml?branch=main&label=CI&style=flat-square&logo=githubactions&logoColor=white)](https://github.com/nofuturekid/nova/actions/workflows/ci.yml)
[![CodeQL](https://img.shields.io/github/actions/workflow/status/nofuturekid/nova/codeql.yml?branch=main&label=CodeQL&style=flat-square&logo=github&logoColor=white)](https://github.com/nofuturekid/nova/security/code-scanning)
[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg?style=flat-square)](./LICENSE)

[![Dependabot](https://img.shields.io/badge/Dependabot-enabled-025E8C?style=flat-square&logo=dependabot&logoColor=white)](.github/dependabot.yml)
[![Forks](https://img.shields.io/github/forks/nofuturekid/nova?style=flat-square&logo=github)](https://github.com/nofuturekid/nova/network/members)
[![Open issues](https://img.shields.io/github/issues/nofuturekid/nova?style=flat-square&logo=github)](https://github.com/nofuturekid/nova/issues)

</div>

---

NOVA puts the parts of your Unraid® server you actually check on your phone — array health, container and VM state, live system metrics — into a clean, dark, one-thumb interface. Start a container, fix a stuck VM, kick off an update, glance at parity progress: without opening a laptop or fighting the desktop web UI on a phone screen.

It talks to your server through the official Unraid API (the Connect plugin's GraphQL endpoint) over your own network — NOVA itself routes nothing through a third party.

## Screenshots

**Live interactive preview (no install):** <https://nofuturekid.github.io/nova/> — a clickable prototype of the UI (snapshot of the 0.1.31 cycle; sample data, not a live server).

<div align="center">

| Overview | Docker | Container detail |
|:---:|:---:|:---:|
| <img src="docs/screenshots/overview.png" width="240" alt="Overview — array, CPU, memory, containers, system"> | <img src="docs/screenshots/docker.png" width="240" alt="Docker — every container with live status"> | <img src="docs/screenshots/container-detail.png" width="240" alt="Container detail — start/stop/restart, Open Web UI"> |

</div>

## What you can do

- **Overview** — array capacity, CPU & memory live, container/VM counts, parity-check progress at a glance
- **Array** — disk-by-disk status, temperatures, capacity, start/stop the array
- **Docker** — every container with status; start / stop / restart / pause, jump straight to a container's Web UI, see which have image updates and update one — or all — in a tap
- **VMs** — VM state with start / stop / pause / resume
- **Multiple servers** — switch between them; each remembers its own local and remote address
- **Live network speed** — upload/download on the Overview card (needs Unraid API 4.35.0 or newer); the NIC inventory sits under Settings → Network interfaces (vendor, model and link speed only with a third-party API plugin)
- **Stay current** — the GitHub APK checks GitHub for its own updates and installs them in place (the F-Droid build is updated by F-Droid)

## What's not in yet

NOVA surfaces what Unraid's GraphQL API exposes today. A few things commonly asked for aren't there yet — most because the upstream API hasn't added them:

- **Plugin updates** — NOVA shows installed plugins and the install-job history, but Unraid's API doesn't expose "update available" status the way it does for Docker containers. Tracking: [unraid/api#2012](https://github.com/unraid/api/issues/2012).
- **User shares / SMB / NFS**, **UPS**, **disk SMART**, **parity-check scheduling** — not yet surfaced.

If something on this list (or off it) matters to you, an issue or a 👍 on an existing one is the best signal.

## Look & feel

A deliberately dark, glassy Material 3 design — dense cards, a single accent color, restrained motion. Tuned for quick checks, not for living in.

A few things bend to taste in **Settings**:

- **Accent** — mint, blue, purple, amber or red
- **Mode** — dark, light or follow the system
- **Density** — compact, balanced or spacious padding
- **Docker layout** — list, grid or grouped-by-state

## Getting started

1. **Install** NOVA — either from [F-Droid](https://f-droid.org/packages/io.github.nofuturekid.nova/) (stable releases only) or as the latest APK from the [Releases page](https://github.com/nofuturekid/nova/releases). (For the APK, Android will ask you to allow installing from your browser/files app — that's expected for apps outside the Play Store.)
   - Betas are GitHub pre-releases, so they come as an APK from the Releases page. The in-app updater keeps it current when **Include pre-releases** is switched on in Settings; Obtainium works too.
   - The F-Droid build and the GitHub APK are signed with different keys, so switching between them needs an uninstall first (your saved servers and API keys go with the app). See [ADR-0040](./docs/adr/0040-fdroid-play-distribution-flavors.md).
2. On your Unraid server, generate an **API key**: web UI → _Settings → Management Access → API / Connect_.
3. Open the app → **Add server** and fill in:
   - **Server name** — a label, e.g. `Tower`
   - **Local host** — its address on your home network, e.g. `192.168.1.10`. Flip **Use SSL (HTTPS)** if the server uses HTTPS; for a self-signed certificate also enable **Trust self-signed certificate** and confirm it once
   - **Remote host (Unraid Connect)** — optional, e.g. `your-server.unraid.net`, for when you're away (with its own **Use SSL (HTTPS)** switch)
   - **API Key** — the one you just generated
4. Tap **Test** (next to the local and/or remote connection), then **Save**. The pill in the top bar flips between **Local** and **Remote**.

Requires an Unraid 7.x server with the API/Connect plugin enabled. All app features need Unraid API **4.30.0 or newer**; the live network speed needs **4.35.0 or newer** (API versions, not Unraid OS versions). On an older API some screens fail to load.

## Trademark attribution

Unraid® is a registered trademark of Lime Technology, Inc. NOVA is an independent, community-built client; it uses the term solely to describe compatibility with the Unraid® operating system and is not affiliated with, endorsed by, or supported by Lime Technology. The project was previously named `UnraidControl`; the rename to NOVA in v0.1.33 was made to comply with the Unraid® Trademark Policy — see [ADR-0038](./docs/adr/0038-lime-trademark-compliance-rename-pending.md) and [ADR-0039](./docs/adr/0039-rename-to-nova.md).

## Privacy & data handling

- **Full policy:** <https://github.com/nofuturekid/nova/blob/main/docs/privacy/POLICY.md>
- **Your API key stays on your device.** It's encrypted at rest using Android Keystore via Google's Tink library, and explicitly excluded from cloud backup / device-transfer.
- **No analytics, no telemetry, no tracking services.** The app talks to your Unraid server's API; in the GitHub (`direct`) build it also asks GitHub's API for app updates (at app start, when you open Settings, or on "Check now"); and it loads container icons from the URLs your own server supplies (often GitHub or a CDN).
- **Update downloads** go directly from GitHub Releases to your device over HTTPS. The downloaded APK is verified by byte-size match and constant-time SHA-256 against the published release asset before any install attempt; mismatch fails closed. See [ADR-0034](./docs/adr/0034-update-and-data-at-rest-hardening.md) for the rationale.
- **Source is the spec.** Audit, fork, run yourself — GPL v3.

## Contributing & internals

The architecture overview is in [`HANDOFF.md`](./HANDOFF.md) (Layout), design decisions are recorded as Architecture Decision Records under [`docs/adr/`](./docs/adr/), and the contribution workflow and release pipeline are in [`CONTRIBUTING.md`](./CONTRIBUTING.md). Bug reports and PRs welcome.

Before pushing, you can run the exact CI checks locally in a pinned container with `./scripts/local-ci.sh` — see [`docs/local-build.md`](./docs/local-build.md).

## License

[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg)](./LICENSE)

GNU General Public License v3.0. Use it (including commercially), modify it, run it — but if you distribute the app or a fork, the corresponding source has to be available under the same license. Full text in [`LICENSE`](./LICENSE); rationale in [ADR-0021](./docs/adr/0021-relicense-to-gpl-3.md).

© 2026 nofuturekid
