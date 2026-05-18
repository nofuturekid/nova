<div align="center">

# UnraidControl

**A fast, native Android app for keeping an eye on — and a hand on — your Unraid server.**

[![Stable](https://img.shields.io/github/v/release/nofuturekid/UnraidControl?sort=semver&label=stable&color=blue&style=flat-square&logo=github)](https://github.com/nofuturekid/UnraidControl/releases/latest)
[![Beta](https://img.shields.io/github/v/release/nofuturekid/UnraidControl?include_prereleases&sort=semver&label=beta&color=orange&style=flat-square&logo=github)](https://github.com/nofuturekid/UnraidControl/releases)
[![Downloads](https://img.shields.io/github/downloads/nofuturekid/UnraidControl/total?label=downloads&style=flat-square&logo=github)](https://github.com/nofuturekid/UnraidControl/releases)

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/nofuturekid/UnraidControl/releases)
[![Min Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/nofuturekid/UnraidControl/releases)
[![Made with Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)

[![CI](https://img.shields.io/github/actions/workflow/status/nofuturekid/UnraidControl/ci.yml?branch=main&label=CI&style=flat-square&logo=githubactions&logoColor=white)](https://github.com/nofuturekid/UnraidControl/actions/workflows/ci.yml)
[![CodeQL](https://github.com/nofuturekid/UnraidControl/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/nofuturekid/UnraidControl/security/code-scanning)
[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg?style=flat-square)](./LICENSE)

</div>

---

UnraidControl puts the parts of your Unraid server you actually check on your phone — array health, container and VM state, live system metrics — into a clean, dark, one-thumb interface. Start a container, fix a stuck VM, kick off an update, glance at parity progress: without opening a laptop or fighting the desktop web UI on a phone screen.

It talks to your server through the official Unraid API (the Connect plugin's GraphQL endpoint) over your own network — nothing is routed through any third party.

## Screenshots

**Live interactive preview (no install):** <https://nofuturekid.github.io/UnraidControl/> — a clickable prototype of the UI (snapshot of the 0.1.31 cycle; sample data, not a live server).

<div align="center">

| Overview | Docker | Container detail |
|:---:|:---:|:---:|
| <img src="docs/screenshots/overview.png" width="240" alt="Overview — array, CPU, memory, containers, system"> | <img src="docs/screenshots/docker.png" width="240" alt="Docker — every container with live status"> | <img src="docs/screenshots/container-detail.png" width="240" alt="Container detail — start/stop/restart, Open Web UI"> |

| Container logs | Settings & changelog |
|:---:|:---:|
| <img src="docs/screenshots/container-logs.png" width="240" alt="Live container logs"> | <img src="docs/screenshots/settings-changelog.png" width="240" alt="Settings, with the in-app changelog open"> |

</div>

## What you can do

- **Overview** — array capacity, CPU & memory live, container/VM counts, parity-check progress at a glance
- **Array** — disk-by-disk status, temperatures, capacity, start/stop the array
- **Docker** — every container with status; start / stop / restart / pause, jump straight to a container's Web UI, see which have image updates and update one — or all — in a tap
- **VMs** — VM state with start / stop / pause / resume
- **Multiple servers** — switch between them; each remembers its own local and remote address
- **Stay current** — the app checks GitHub for its own updates and installs them in place

## Look & feel

A deliberately dark, glassy Material 3 design — dense cards, a single accent color, restrained motion. Tuned for quick checks, not for living in.

A few things bend to taste in **Settings**:

- **Accent** — mint, blue, purple, amber or red
- **Mode** — dark or light
- **Density** — compact, balanced or spacious padding
- **Docker layout** — list, grid or grouped-by-state

## Getting started

1. **Install** the latest APK from the [Releases page](https://github.com/nofuturekid/UnraidControl/releases). (Android will ask you to allow installing from your browser/files app — that's expected for apps outside the Play Store.)
2. On your Unraid server, generate an **API key**: web UI → _Settings → Management Access → API / Connect_.
3. Open the app → **Add server** and fill in:
   - **Name** — a label, e.g. `Tower`
   - **Local URL** — its address on your home network, e.g. `http://192.168.1.10`
   - **Remote URL** — optional, e.g. a Connect/remote address for when you're away
   - **API key** — the one you just generated
4. Tap **Test**, then **Save**. The pill in the top bar flips between **Local** and **Remote**.

Requires an Unraid 7.x server with the API/Connect plugin enabled.

## Not affiliated with Lime Technology

UnraidControl is an independent, community-built client. "Unraid" is a trademark of Lime Technology, Inc. This project is not affiliated with, endorsed by, or supported by Lime Technology.

## Contributing & internals

Architecture, the release pipeline, the GraphQL mapping, and design decisions live in [`CONTRIBUTING.md`](./CONTRIBUTING.md) and the Architecture Decision Records under [`docs/adr/`](./docs/adr/). Bug reports and PRs welcome.

Before pushing, you can run the exact CI checks locally in a pinned container with `./scripts/local-ci.sh` — see [`docs/local-build.md`](./docs/local-build.md).

## License

[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg)](./LICENSE)

GNU General Public License v3.0. Use it (including commercially), modify it, run it — but if you distribute the app or a fork, the corresponding source has to be available under the same license. Full text in [`LICENSE`](./LICENSE); rationale in [ADR-0021](./docs/adr/0021-relicense-to-gpl-3.md).

© 2026 nofuturekid
