# Handoff / project notes

Snapshot for resuming this project elsewhere. Keep up to date when major decisions or version bumps happen.

## What this is

Native Android client for Unraid NAS servers (Kotlin + Jetpack Compose, Apollo GraphQL against Unraid 7's Connect API). Recreated from a Claude Design HTML/CSS prototype.

## Repo

- URL: https://github.com/hellwand/nova
- License: GPL-3.0-only (relicensed from CC BY-NC-SA 4.0 — see ADR-0021)
- Default branch: `main` (protected)
- Owner: the GitHub organization `hellwand` (the maintainer's account `nofuturekid` is its owner). `main` protection applies to admins too (`enforce_admins` on since 2026-11-10, ADR-0003); only squash merges are allowed; tags `v*` are protected against update and deletion by the ruleset "Protect release tags" (ADR-0010).
- Moved: the repository moved from `nofuturekid/nova` to `hellwand/nova` on 2026-11-12. Nothing may ever be created, forked or renamed into `nofuturekid/nova`, and `hellwand/nova` must not be forked into the personal account under the name `nova`: installed app versions up to 0.1.42 find updates only through GitHub's redirect from the old name, and a repository of that name would break it. Since 0.1.43 the app addresses `hellwand/nova` directly.

## Releases shipped so far

| Tag                  | Class       | Headline                                                                                                                                                                                                                                                                                                      |
| -------------------- | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| v0.1.0               | release     | Initial scaffold                                                                                                                                                                                                                                                                                              |
| v0.1.1               | release     | System-bar insets + empty-state Add-Server callback                                                                                                                                                                                                                                                           |
| v0.1.2               | release     | `usesCleartextTraffic` for LAN HTTP                                                                                                                                                                                                                                                                           |
| v0.1.3               | release     | `{ __typename }` ping for Test Connection                                                                                                                                                                                                                                                                     |
| v0.1.4               | release     | Full Unraid 7 schema rewrite                                                                                                                                                                                                                                                                                  |
| v0.1.5               | release     | Add/Edit Server tap target + real API key load                                                                                                                                                                                                                                                                |
| v0.1.6               | release     | Live CPU/RAM graphs + Docker container icons (Coil)                                                                                                                                                                                                                                                           |
| v0.1.7               | release     | Promote-flow + branch protection + CONTRIBUTING                                                                                                                                                                                                                                                               |
| v0.1.8-beta1, beta2  | pre-release | Pull-to-refresh spinner fix (beta channel test)                                                                                                                                                                                                                                                               |
| v0.1.8               | release     | Pull-to-refresh fix (stable)                                                                                                                                                                                                                                                                                  |
| v0.1.9               | release     | Transient poll error tolerance (3 polls grace)                                                                                                                                                                                                                                                                |
| v0.1.10              | release     | Array tab unit fix (ArrayDisk fields are KB)                                                                                                                                                                                                                                                                  |
| v0.1.11              | release     | Container detail: real logs fetch + volume mappings                                                                                                                                                                                                                                                           |
| v0.1.12–0.1.21       | release     | Incremental fixes/features (see git tags / GitHub releases)                                                                                                                                                                                                                                                   |
| v0.1.22–0.1.25       | release     | Docker-update phases A–C (ADR-0016/0017), WebUI link, GPL-v3 relicense (ADR-0021), CI acceleration (ADR-0018/0019/0020)                                                                                                                                                                                       |
| v0.1.26              | release     | Density setting wired, per-view layout (List/Grid/Grouped)                                                                                                                                                                                                                                                    |
| v0.1.27-beta1        | pre-release | (interim)                                                                                                                                                                                                                                                                                                     |
| v0.1.28-beta1…rc2    | pre-release | **AGP-9 toolchain** (ADR-0023), **API-key → Tink+DataStore** (ADR-0024), UI Light/Dark pass, VM detail sheet + slim cards, ContainerDetailSheet layout, numeric SemVer compare, container-update-button fix                                                                                                   |
| v0.1.28              | release     | Stable promotion of the whole 0.1.28 cycle, device-verified                                                                                                                                                                                                                                                   |
| v0.1.29-beta1…beta7  | pre-release | M3 type-scale migration; Docker-logs Dark/Light; Theme-mode **Dark/Light/System**; Dependabot adopted (ADR-0025); **notifications bell+badge+sheet**; UI polish (changelog typo, overview cards, banner); system-bar contrast vs app theme                                                                    |
| v0.1.29-beta8…beta13 | pre-release | Container-sheet **log live-tail + pull-to-refresh**; Phase E GraphQL-subscription pilot E0+E1 (ADR-0026) — WSS transport works but reverted (no subscribe-snapshot, server-side FS-watcher); back to polling; **ADR-0027** agent autonomy/access model                                                        |
| v0.1.29              | release     | Stable promotion of the 0.1.29 cycle (polling-only; container-sheet live-tail/pull-to-refresh; Phase E evaluated → provisionally parked per ADR-0026/0027)                                                                                                                                                    |
| v0.1.30-beta1…beta7  | pre-release | M3 modernization roadmap (ADR-0030): standard Material-3 components throughout; accessibility + visual-consistency pass; AGP cleanup; Apollo-client reuse                                                                                                                                                     |
| v0.1.30              | release     | Stable promotion of the whole 0.1.30 cycle, device-verified                                                                                                                                                                                                                                                   |
| v0.1.31-beta1…beta9  | pre-release | Notifications actions + Unread/Archived tabs + bell badge (ADR-0032); security hardening — verified APK install, network-security config, backup exclusions, key-decrypt re-enter (ADR-0034/0035); code-review triage fixes; CI-governance (ADR-0036)                                                         |
| v0.1.31              | release     | Stable promotion of the whole 0.1.31 cycle (beta1…beta9 maintainer device-accepted): notifications actions, security hardening (ADR-0034/0035), code-review triage fixes, CI governance (ADR-0036), maintainer device-accepted 2026-05-18                                                                     |
| v0.1.32-beta1…beta3  | pre-release | ADR-0030 F1–F4 M3 follow-ups; #19 resilient action launchers (ADR-0037); #22 installUpdate double-tap guard; CI transient-KSP note (ADR-0036)                                                                                                                                                                 |
| **v0.1.32**          | **release** | Stable promotion of the 0.1.32 cycle — F1–F4 M3 modernization, resilient action launchers (ADR-0037), install double-tap guard; beta1–beta3 maintainer device-accepted 2026-05-19                                                                                                                             |
| v0.1.33-beta1        | pre-release | Settings → About: §6 trademark attribution in-app + app name/version + GPL v3 + source-code link + OSS license list (AboutLibraries 14.2.0)                                                                                                                                                                   |
| v0.1.33-beta2        | pre-release | Deprecation banner + About "What's changing" — last release under old applicationId                                                                                                                                                                                                                           |
| v0.1.33-beta3        | pre-release | Full rename — applicationId io.github.nofuturekid.nova, app_name NOVA, new icon, README/CHANGELOG rebrand, release asset NOVA-vX.Y.Z.apk                                                                                                                                                                      |
| **v0.1.33**          | **release** | First Lime-trademark-compliant Stable — full rename to NOVA per ADR-0038/0039; existing UnraidControl installs do not auto-update                                                                                                                                                                             |
| v0.1.34-beta1        | pre-release | Repo rename to `nofuturekid/nova` — URL refresh sweep                                                                                                                                                                                                                                                         |
| v0.1.34-beta2        | pre-release | Settings → Server plugins (read-only inventory + install-ops history)                                                                                                                                                                                                                                         |
| v0.1.34-beta3        | pre-release | Settings → Server plugins: add real `.plg` inventory section + relabel API-modules section                                                                                                                                                                                                                    |
| v0.1.34-beta4        | pre-release | Settings → Network interfaces (read-only NIC inventory: IPv4/IPv6 + hardware)                                                                                                                                                                                                                                 |
| v0.1.34-beta5        | pre-release | About-section regrouped (paired GitHub links, footer trademark)                                                                                                                                                                                                                                               |
| **v0.1.34**          | **release** | Stable promotion of the 0.1.34 cycle — Settings → Server plugins (.plg inventory + API modules + install-ops history), Settings → Network interfaces (NIC inventory IPv4/IPv6 + hardware), Settings → About regrouped, repo-rename housekeeping; beta1–beta5 maintainer device-accepted 2026-05-21            |
| v0.1.35-beta1        | pre-release | Distribution flavors (direct / store) per ADR-0040 — first step of the F-Droid publish path                                                                                                                                                                                                                   |
| **v0.1.35**          | **release** | Stable promotion of the 0.1.35 cycle — distribution flavors (direct / store, ADR-0040) + formal Privacy Policy; opens the F-Droid submission path                                                                                                                                                             |
| v0.1.36-beta1        | pre-release | Store flavor: guard MainViewModel + SettingsViewModel updater paths + Overview banner UI — true zero-GitHub at runtime. Privacy-policy + ADR-0040 promise compliance ahead of F-Droid MR                                                                                                                      |
| **v0.1.36**          | **release** | Stable promotion of the 0.1.36 cycle — store-flavor true-zero-GitHub privacy fix (MainViewModel + SettingsViewModel + Overview banner + UpdateDialog all guarded); pre-work for the F-Droid metadata MR                                                                                                       |
| v0.1.37-beta1        | pre-release | Build: dependenciesInfo block to strip AGP's extra signing block (F-Droid `check apk` compliance)                                                                                                                                                                                                             |
| **v0.1.37**          | **release** | Stable promotion of the 0.1.37 cycle — build-config-only `dependenciesInfo {}` block to strip AGP's "Dependency metadata" extra signing block (unblocks F-Droid `check apk`)                                                                                                                                  |
| v0.1.38-beta1        | pre-release | UI: tap-affordance chevrons on Docker/VM cards (list+grid) + expand-chevron on Plugins-ops + Network-interface rows                                                                                                                                                                                           |
| **v0.1.38**          | **release** | Stable promotion of the 0.1.38 cycle — UI tap-affordance chevrons on Docker/VM cards (list+grid) + expand-chevron on Plugins-ops + Network-interface rows                                                                                                                                                     |
| v0.1.39-beta1…beta3  | pre-release | Host + SSL server entry (replaces the free-text URL), opt-in self-signed local-cert trust with a pinned SHA-256 (ADR-0041), URL-paste tolerance                                                                                                                                                               |
| **v0.1.39**          | **release** | Stable promotion of the 0.1.39 cycle — host + SSL entry and self-signed local-cert trust (ADR-0041)                                                                                                                                                                                                           |
| v0.1.40-beta1…beta10 | pre-release | GraphQL-subscription transport back, rolled out per domain: network throughput (ADR-0042), CPU/memory/temperature with poll fallback and per-container docker stats (ADR-0043), container-detail live stats (ADR-0044), temperature card, Array/Cache split, standby/thresholds/errors/ZFS members (ADR-0045) |
| **v0.1.40**          | **release** | Stable promotion of the 0.1.40 cycle (beta1…beta10), device-accepted                                                                                                                                                                                                                                          |
| **v0.1.41**          | **release** | Error messages name the real cause; connection pill keeps Local/Remote when offline (#218)                                                                                                                                                                                                                    |
| v0.1.42-beta1        | burnt tag   | Tagged but never published: release APK verification failed on the runner (apksigner 37 output format); superseded by beta2                                                                                                                                                                                   |
| v0.1.42-beta2        | pre-release | Beta: failed actions show why, graceful VM stop + confirmations, network speed on stock Unraid API 4.35+, no background polling, store screenshots                                                                                                                                                            |
| **v0.1.42**          | **release** | Stable promotion of the 0.1.42 cycle (beta2): failed actions show why, graceful VM stop + confirmations, network speed on stock Unraid API 4.35+, no background polling, hardened self-signed trust, store icon and screenshots, privacy policy corrections **(Latest)**                                      |

## Stack

_Toolchain raised in the 0.1.28 cycle — see ADR-0023 (AGP-9) / ADR-0024
(storage) / ADR-0020 (KSP2)._

- Kotlin **2.3.21**, AGP **9.2.1**, Gradle **9.5.1**, JDK **21**
- AGP-9 built-in-Kotlin DSL (no `org.jetbrains.kotlin.android` plugin)
- compileSdk/targetSdk **36**, minSdk **26** (app bytecode = Java 17)
- Jetpack Compose BOM **2026.05.00**, Material 3 + custom M3 type scale
- Apollo Kotlin **5.0.0**
- Hilt **2.59.2** + **KSP2** (2.3.8)
- **DataStore (Preferences) + Google Tink AEAD** for the API-key store
  (Android-Keystore-wrapped keyset; `security-crypto` removed, ADR-0024)
- Coil **3.4.0** for AsyncImage
- okhttp **5.3.2**, kotlinx.coroutines **1.11.0**, serialization **1.11.0**
- Dependabot (github-actions + gradle, monthly, no auto-merge — ADR-0025)
- Distribution flavors `direct` (GitHub APK; declares `REQUEST_INSTALL_PACKAGES`, in-app updater enabled) and `store` (the F-Droid build; updater disabled at runtime via `BuildConfig.HAS_UPDATER = false`) — ADR-0040
- Tests: JUnit 4; Robolectric 4.17 + Roborazzi 1.78 only for the store screenshots (`-PnovaScreenshots`); Gradle wrapper pinned by `distributionSha256Sum`

## Layout

```
app/src/main/
├── graphql/io/github/nofuturekid/nova/
│   ├── schema.graphqls        hand-written subset of the unraid/api generated-schema.graphql
│   ├── queries.graphql        Ping, GetServerInfo/Metrics/NetworkThroughput/Display/Array/DockerContainers/Vms,
│   │                          notifications (overview + list), plugins, plugin ops, installed .plg, NetworkInterfaces, FetchContainerLogs
│   ├── mutations.graphql      Array, Docker (start/stop/pause/unpause/update/updateAll), Vm (start/stop/pause/resume/
│   │                          forceStop/reboot/reset), parityCheck, notification actions
│   └── subscriptions.graphql  SystemMetrics{Network,Cpu,Memory,Temperature}, DockerContainerStats (ADR-0042/0043)
└── kotlin/io/github/nofuturekid/nova/
    ├── MainActivity.kt
    ├── NovaApp.kt                 @HiltAndroidApp
    ├── data/
    │   ├── model/                 Models, Settings, EndpointUrl, NetworkThroughput, Temperature, UpdateInfo
    │   ├── api/                   ApolloClientFactory (cached per server/variant, x-api-key header, HTTP + WS), GraphQlMapper, Tls (pinned local trust)
    │   ├── local/                 SettingsStore (DataStore), ApiKeyStore (Tink AEAD + Preferences DataStore, ADR-0024/0035)
    │   ├── repository/            UnraidRepository (domain streams: polling + subscriptionOrPoll, mutations via runAction, logs),
    │   │                          ServerRepository (CRUD + active server), SettingsRepository,
    │   │                          DomainState, Nudge (refresh nudges), ResponseClassifier
    │   └── update/                UpdateRepository, UpdateInstaller, UpdateController, ApkInstaller, InstallStatusReceiver
    │                              (compiled into both flavors; disabled at runtime when BuildConfig.HAS_UPDATER is false)
    ├── di/                        AppModule (empty, constructor injection), UpdateModule
    └── ui/
        ├── UnraidApp.kt
        ├── theme/                 Color, Density, Dimens, Shapes, Alpha, Type, Theme, ThemeMode (CompositionLocals)
        ├── components/            Card, Pill, Buttons, Sparkline, Progress, ContainerIcon (Coil), UnraidField, ConfirmDialog,
        │                          SectionLabel, ExpandIndicator, Tone, ComponentColors, UnraidIcons
        ├── nav/AppNavGraph.kt     single NavHost: main / server_list / settings / about_libraries / plugins / network + modal sheets
        ├── util/                  ByteFormat, TextFormat
        └── screens/
            ├── SnapshotStates.kt
            ├── main/              MainScreen + MainViewModel (Hilt)
            ├── overview/          stat cards (CPU, memory, network, temperature), system info
            ├── array/             disk cards (Array / Cache sections) + parity strip
            ├── docker/            list/grid/grouped + real icons
            ├── vms/               VmsTab, VmDetailSheet
            ├── container/         detail sheet (Info / Logs / Ports / Volumes)
            ├── server/            list + Add/Edit sheet
            ├── notifications/     bell sheet (Unread / Archived, actions)
            ├── plugins/           Settings → Server plugins
            ├── network/           Settings → Network interfaces
            ├── update/            UpdateBanner, UpdateDialog (gated by BuildConfig.HAS_UPDATER)
            └── settings/          accent / theme-mode (Dark/Light/System) / density / views, AboutLibrariesScreen
```

## CI / CD workflow

The "why" for everything in this section lives in ADRs under [`docs/adr/`](docs/adr/). What follows is the operational summary.

- **Branch protection** on `main`: PRs required, required status check `build`, 0 required approvals (solo-dev-friendly). See [ADR-0003](docs/adr/0003-pr-gate-required-status-check.md).
- **Build-once-promote** split between `ci.yml` (builds debug+release APKs, uploads artifacts) and `release.yml` (`v*` tag → finds the SHA's successful CI run, runs the release guard (commit on `main`, PR merged as this commit, CI tree == tag tree, `versionName` == tag, `versionCode` up), downloads that run's `app-release` artifact, verifies the APK itself (package id, `versionName` == tag, `versionCode` == source, pinned signer certificate; `.github/scripts/release-verify-apk.sh`), then publishes the GitHub Release; **never rebuilds**). See [ADR-0004](docs/adr/0004-build-once-promote-pipeline.md).
- **Concurrency**: `ci.yml` cancels superseded runs unconditionally; it no longer runs on push to `main` (ADR-0014), so there is no main run to protect (ADR-0011's carve-out is moot). See [ADR-0011](docs/adr/0011-cancel-in-progress-only-on-pr-branches.md), [ADR-0014](docs/adr/0014-single-ci-run-per-pr.md).
- **Docs-only CI bypass**: `ci.yml` builds only when a build-affecting path changed (inverted allowlist: `app/`, `gradle/`, wrapper, root Gradle files, `ci.yml`); anything else finishes in ~10s and still reports `SUCCESS` on `build`. No version bump, no tag for these. See [ADR-0029](docs/adr/0029-invert-ci-build-skip-filter.md) (supersedes ADR-0009).
- **Tag conventions**: stable `v0.X.Y`, pre-release `v0.X.Y-beta1` / `-rc1` with trailing digit required. See [ADR-0005](docs/adr/0005-prerelease-tag-convention.md).
- **Other workflows**: `actionlint.yml`, `codeql.yml` (ADR-0022/0036), `dependency-submission.yml` (ADR-0025), `pages.yml` (ADR-0033). `ci.yml` also runs the unit-test job (ADR-0036) and builds both flavors in `build-release` (ADR-0040).
- **Release-class policy**: pragmatic risk-categorised beta-first. UI/docs → direct stable. Schema / manifest / SettingsStore / dependency bumps / install-flow → beta-first. See [ADR-0006](docs/adr/0006-pragmatic-beta-first-release-policy.md).

### Release procedure (operational)

1. Feature branch → PR → CI green → squash-merge, then delete the branch. It is not deleted automatically (`delete_branch_on_merge` is off); use `gh pr merge <n> --squash --delete-branch`.
2. Version-bump PR (its own commit per [ADR-0015](docs/adr/0015-promotion-requires-new-commit.md)).
   **Title it `release: vX.Y.Z-betaN — <summary>`** (beta/rc) or
   **`release: X.Y.Z — <summary> (Stable)`** (stable) — not "Version bump" /
   "Promote". See CONTRIBUTING "Naming the release commit / PR".
3. CI green on the bump → squash-merge.
4. Tag the bump commit: `git tag -a vX.Y.Z[-betaN] -m "..." && git push origin vX.Y.Z[-betaN]`.
5. `release.yml` promotes the existing artifact.
6. If beta: test on device → new bump PR (`release: X.Y.Z — … (Stable)`,
   versionCode bumped again) once happy. No re-tagging the same commit
   (ADR-0015 superseded the old "same commit, new tag" flow).

A burnt tag (v0.1.42-beta1: tag pushed, release verification failed) is never moved; fix forward with a new commit and the next version.

If a tag is pushed before CI on its commit is green, `release.yml` fails with "no successful CI run found for commit". Wait, then tag.

### Release cleanup

`gh release delete <tag> --yes` — never `--cleanup-tag`. Recovery recipe for accidentally-deleted tags lives in [ADR-0010](docs/adr/0010-release-cleanup-keeps-tags.md).

## Schema mapping decisions

Apollo scalar mappings (`app/build.gradle.kts`):

```kotlin
mapScalar("PrefixedID", "kotlin.String")
mapScalar("DateTime",   "kotlin.String")
mapScalar("BigInt",     "kotlin.Long")
mapScalar("JSON",       "kotlin.Any",  "com.apollographql.apollo.api.AnyAdapter")
mapScalar("URL",        "kotlin.String")
mapScalar("Port",       "kotlin.Int")
```

JSON-scalar decision rationale (incl. why-not-String): [ADR-0007](docs/adr/0007-apollo-json-scalar-anyadapter.md). In-app updater architecture: [ADR-0008](docs/adr/0008-in-app-updater-packageinstaller.md).

### Critical gotchas (don't relearn the hard way)

- **ArrayDisk sizes are KILOBYTES**, not bytes. Use `kbToTb()`. (Top-level `Disk` type uses bytes but we don't query that.)
- **Mutations are nested**: `mutation { docker { start(id) } }` not top-level `startContainer(id)`.
- **Docker containers** live under `docker.containers`, NOT top-level `dockerContainers`.
- **VMs** live under `vms.domains`, NOT a top-level list.
- Apollo with `generateOptionalOperationVariables = false`: optional GraphQL vars become `T?` directly. **Never wrap in `Optional.present()`** — it won't compile.
- **KSP2 + Hilt 2.59.2** (ADR-0020/0023): the old `ksp.useKSP2=false` / Hilt-2.52 pin is gone. Hilt requires Kotlin ≥ 2.1.10 — it is coupled to the toolchain (ADR-0023 rollback note: Hilt can't be reverted in isolation).
- **`DockerContainer.mounts` is `JSON` (single scalar) NOT `[JSON!]`** — the JSON content itself is an encoded array of mount objects. Easy to mistype based on intuition; v0.1.11 made the snapshot fail server-wide because of this exact wrong type. Parse via `parseMountsArray` in `GraphQlMapper.kt`.
- **JSON scalar → kotlin.Any via Apollo's `AnyAdapter`** — the Unraid GraphQL `JSON` scalar serialises as inline JSON values (objects/arrays/primitives), not pre-stringified text. Custom `kotlin.String` adapters crash. We map it to `kotlin.Any` with `com.apollographql.apollo.api.AnyAdapter` (public, built-in). Don't try to write your own with `reader.readAny()` — that's `@ApolloInternal`.
- **In-app updater** lives under `data/update/`: `UpdateRepository` polls `https://api.github.com/repos/hellwand/nova/releases` (installed versions up to 0.1.42 still carry the old `nofuturekid/nova` name and reach it through GitHub's redirect; no auth, 60 req/h rate limit fine for on-start checks), `UpdateInstaller` downloads APK to `cacheDir/updates/` and uses `PackageInstaller` for the install handshake, `InstallStatusReceiver` catches the session callback via a `BroadcastReceiver` declared in the manifest. The `REQUEST_INSTALL_PACKAGES` permission is declared in the `direct` flavor manifest only (`app/src/direct/AndroidManifest.xml`); the updater code itself is compiled into both flavors and disabled at runtime when `BuildConfig.HAS_UPDATER` is false (`store`). On first install Android prompts the user to whitelist us at `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`. The Settings screen exposes the "Include pre-releases" toggle + manual "Check now". Banner on MainScreen dismisses per-tag via `dismissedUpdateTag` in DataStore.
- Cleartext HTTP requires `android:usesCleartextTraffic="true"` in the manifest. LAN access against `http://192.168.x.x` needs this. The base-config in `network_security_config.xml` permits cleartext; the GitHub hosts (`api.github.com`, `github.com`, `objects.githubusercontent.com`) are pinned to HTTPS.
- **CI `test` job (ADR-0036) can transiently fail at Gradle _configuration_** with a plugin-not-resolvable error (e.g. `com.google.devtools.ksp` not found) on a plugin-portal/registry blip — it fetches plugins fresh under PR `cache-read-only`. **Re-run the job; it is not a code defect** (see ADR-0036 "Known consequence — transient plugin-fetch in the `test` job" note; PR #163 2026-05-19).

### What the Unraid API does NOT expose

Minimum server API: every operation the app sends, except the two network-speed operations (`GetNetworkThroughput`, `SystemMetricsNetwork`), validates against Unraid API **4.30.0** and newer; those two, and with them the live network speed, need **4.35.0** (these are API versions, not Unraid OS versions). Older servers reject the unknown fields. Only the network speed has capability detection; there is no general minimum-version check.

- Network throughput is implemented (PR #222): `metrics.network` / the `systemMetricsNetwork` subscription (`name rxSec txSec`) exist from Unraid API 4.35.0; older servers show no speed (ADR-0042 amendment). Live via subscription with a 2 s poll fallback.
- Per-container CPU/Mem/Net/Disk I/O exist only as the `dockerContainerStats` subscription (no query path). Implemented and shown on the Docker tab and in the container sheet (ADR-0043/0044); no poll fallback.
- Container logs: stderr is not returned by the stock API; the log view shows a hint instead of a bare "No log lines" (#225).
- NIC vendor/model/link speed/virtual flag only with a third-party API plugin; rows without data are left out (#223).
- VmDomain has only id/name/state — no vcpu/memGb/gpu fields. UI placeholder zeros (still not exposed).
- Container icons may come back as **root-relative paths** (`/state/plugins/dynamix.docker.manager/images/foo.png`). We prepend the configured server base URL in `ContainerIcon.resolveIconUrl`.

## Secrets

### GitHub Actions secrets (already set on the repo)

- `KEYSTORE_B64` — base64 of the release keystore
- `KEYSTORE_PASSWORD`, `KEY_PASSWORD` — PKCS12 only supports one password, so they're identical
- `KEY_ALIAS` = `unraidcontrol`

### Local backup

`SECRETS_SUMMARY.md` outside the repo (in the original sandbox session). Lose this AND lose the GitHub secrets and you cannot ship updates that Android will accept as upgrades of existing installs — they'll require uninstall/reinstall.

## Local development

```bash
git clone https://github.com/hellwand/nova
cd nova
./scripts/local-ci.sh                 # recommended: pinned container, same tasks as CI (docs/local-build.md)
./gradlew :app:assembleDirectDebug    # host debug build, no keystore needed (flavors: direct | store)
```

For local release builds (when you have the keystore):

```bash
export KEYSTORE_PASSWORD='...'
export KEY_PASSWORD='...'
export KEY_ALIAS='unraidcontrol'
# place release.keystore at app/release.keystore
./gradlew :app:assembleRelease
```

Without the keystore the release config falls back to debug signing for local builds.

## Code-review triage (2026-05-18)

Historical record of the 2026-05-18 external deep review, verified against HEAD f86c25f. Most fix-now items shipped in 0.1.31/0.1.32; #15 (hard-coded onPrimary colour) still has 3 sites in `app/src/main/kotlin`. Tracks are no longer binding. Not re-checked at 0.1.42: #1-12 and #17-22. See ADR-0030 for the UI follow-ups.

| #   | Item                                                                                               | Verdict                                                             | Track                               |
| --- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | ----------------------------------- |
| 1   | No APK SHA-256/size verify before silent install                                                   | REAL (Crit)                                                         | Security ADR + beta (Step 2)        |
| 2   | Cleartext global, no network_security_config                                                       | BY-DESIGN base (v0.1.2 LAN-HTTP) / hardening REAL                   | Security ADR + beta (Step 2)        |
| 3   | stateIn retains prev server's DomainState on switch (stale data under new name)                    | REAL (Crit)                                                         | fix-now beta (own, Step 3)          |
| 4   | log-tail loop catch swallows CancellationException                                                 | REAL (Crit)                                                         | fix-now beta (Step 3 trivial batch) |
| 5   | HttpLoggingInterceptor BASIC, no DEBUG guard (release log leak)                                    | REAL (High)                                                         | fix-now beta (Step 3 trivial batch) |
| 6   | deleteNotification n.type ?: Unread "wrong on Archived"                                            | STALE — premise false at HEAD (type is per-item from server mapper) | wontfix (rationale)                 |
| 7   | AddEditServer LaunchedEffect(server?.id) null-key collision                                        | REAL (High)                                                         | fix-now beta (Step 3 trivial batch) |
| 8   | ApiKeyStore.get() swallows Tink decrypt failure → permanent false "Missing API key"                | NEEDS-JUDGMENT                                                      | ADR-0024 amendment + beta (Step 2)  |
| 9   | launchPermissionIntent resets before verifying launch(); ActivityNotFound eaten                    | REAL (High)                                                         | fix-now beta (Step 3 trivial batch) |
| 10  | InstallStatusReceiver SharedFlow replay=0 startup race                                             | REAL (High)                                                         | fix-now beta (own, Step 3)          |
| 11  | apiKey verbatim in Apollo client map key                                                           | ROUTE→ADR-0028 (already accepted verbatim)                          | no action (ADR-0028)                |
| 12  | backup/data-extraction rules stale post-ADR-0024; unraid_prefs (server URLs) + keyset not excluded | REAL (Med)                                                          | Security ADR + beta (Step 2)        |
| 13  | SettingsScreen no verticalScroll, clips short screens                                              | REAL → ROUTE                                                        | ADR-0030 follow-up                  |
| 14  | refreshAll() fires gated streams                                                                   | BY-DESIGN (ADR-0017)                                                | wontfix (rationale)                 |
| 15  | hardcoded Color(0xFF06120E) vs onPrimary at 6 sites                                                | REAL → ROUTE                                                        | ADR-0030 follow-up (P1 nachzug)     |
| 16  | SettingsScreen bespoke Toggle vs M3 Switch (no Role.Switch)                                        | REAL → ROUTE                                                        | ADR-0030 follow-up                  |
| 17  | LaunchedEffect(openContainer) non-suspending → one-frame stale dockerGate                          | REAL (Med)                                                          | fix-now beta (Step 3 trivial batch) |
| 18  | server-switch DomainState reset untested                                                           | REAL gap                                                            | test backlog                        |
| 19  | runNotificationAction network-error propagation untested                                           | REAL gap                                                            | test backlog                        |
| 20  | ServerRepository.delete of only server (setActiveServer(null) NPE) untested                        | REAL gap                                                            | test backlog                        |
| 21  | domainStream TRANSIENT_ERROR_TOLERANCE=3 untested                                                  | REAL gap                                                            | test backlog                        |
| 22  | UpdateController.installUpdate re-entrancy untested                                                | REAL gap                                                            | test backlog                        |

- **#6 wontfix:** `UnraidNotification.type` is populated per-item from the server (GraphQlMapper, `NotificationFields.type`); an Archived item has `type == Archive`, so the elvis fallback only fires if the server omits type entirely — it does not derive from the wrong tab. Premise false at HEAD. (Trivial: `GraphQlMapper.kt` ~:202 has a dead `?.`/`?:` on a non-null `NotificationType` (CI "unnecessary safe call"; #6-adjacent, negligible) — remove opportunistically.)
- **#11 no action:** ADR-0028 explicitly adopts the `(variant,endpoint,apiKey)` composite key and its stale-entry trade-off; in-memory only.
- **#14 wontfix:** ADR-0017 `refreshAll()` deliberately warms all domains to shorten the post-pull wait; client is cached (ADR-0028). Intentional, documented.

## Open TODOs

_Done since the old list: AGP-buildconfig (ADR-0023), parity-check
mutations (wired), KSP1→KSP2 (ADR-0020), Tink-storage (ADR-0024),
notifications indicator (0.1.29-beta6, on-device verify pending),
README screenshots, Edge-to-Edge incl. system-bar contrast
(0.1.29-beta7), container-sheet log live-tail + pull-to-refresh
(0.1.29-beta8), code-review test backlog #18–22 written + merged +
executed as a blocking CI gate (ADR-0036), resilient action launchers
(#19, ADR-0037, 0.1.32-beta2), installUpdate double-tap guard (#22,
0.1.32-beta3)._

### Open

- [ ] Mid-size feature tabs (schema-backed, not started): Shares · SMART disks · UPS card · System logs.
- [ ] AsyncImage occasionally 404s silently on edge-case icon URLs.
- [ ] No crash reporting / no offline cache.
- [ ] German localization; real app icon; bundle Inter/JetBrains-Mono TTFs.

### Parked / blocked / out of scope

- **GraphQL subscriptions: live since 0.1.40.** ADR-0026 is superseded by
  ADR-0042 (network throughput) and ADR-0043 (CPU, memory, temperature and
  per-container docker stats); ADR-0044 shows the container stats in the
  detail sheet. CPU, memory, temperature and network each fall back to
  polling via `subscriptionOrPoll`; docker stats have no fallback (there is
  no query path). Still polled: array, notifications, docker list/state, VMs,
  server info (array and notifications are blocked server-side, see the
  ADR-0026 amendment). **Known limitation:** there is no in-session
  WebSocket reconnect — the app configures no reopen/retry for the socket
  (Apollo's `reopenWhen` is opt-in), so after a socket error the stream is not
  resubscribed. The cards above drop to the poll after a short grace window and
  the live docker stats disappear, until the stream is collected again: the
  screen gate flips (tab change, app to the background and back) or the active
  server, connection mode or key changes. (The doc comment on
  `subscriptionStream` that says Apollo reconnects on its own is inaccurate.)
- **Baseline Profiles: parked** — modest startup gain vs. real infra
  cost (generation needs an emulator; only an on-demand GMD workflow
  fits our lean CI). Own ADR if revisited.
- **App-rename: done.** NOVA — NAS Operations Viewer Anywhere (canonical form
  `NOVA for Unraid®`, applicationId `io.github.nofuturekid.nova`), decided in
  ADR-0038/0039 and shipped as 0.1.33 (existing UnraidControl installs do not
  auto-update). Repo renamed `UnraidControl` → `nova` in 0.1.34-beta1; Pages
  URL https://hellwand.github.io/nova/. iOS / web targets remain
  non-committal (their own ADR if ever pursued; GPL-v3-vs-App-Store-ToS is a
  real concern).
- VM vCPU/RAM/GPU stay placeholder zeros — `VmDomain` doesn't expose them.
- Container auto-start toggle read-only; Restart = stop+start (no atomic
  mutation in schema).

## Useful gh commands

```bash
gh pr list --repo hellwand/nova
gh run list --repo hellwand/nova --limit 10
gh release list --repo hellwand/nova
gh run download <id> --repo hellwand/nova --name app-release
gh secret list --repo hellwand/nova
```

## Resuming work — quick start

1. `git pull` on main
2. Branch: `git checkout -b feat/something` or `fix/whatever`
3. Code → commit → `./scripts/local-ci.sh` (the PR template requires it) → `git push -u origin <branch>`
4. `gh pr create` (or open via browser)
5. Wait for CI green
6. `gh pr merge <n> --squash --delete-branch`
7. Shipping a release? Do not tag the feature merge. Open a separate version-bump PR (ADR-0015, see "Release procedure" above); once it is green and squash-merged, tag that squash commit on `main`: `git tag -a vX.Y.Z[-betaN] -m "..." && git push origin vX.Y.Z[-betaN]`
8. Claude Code: saved workflows `nova-research`, `nova-pr`, `nova-merge-train` (`.claude/workflows/`); rules in `.claude/CLAUDE.md` ("Working rules"). See CONTRIBUTING.md, "Claude Code setup".

### Schema changes

Edit `schema.graphqls` + `queries.graphql` / `mutations.graphql` / `subscriptions.graphql` + `GraphQlMapper.kt`. UI never imports Apollo types — domain models in `data/model/Models.kt` are the contract.

### New screen

Create folder under `ui/screens/`, add ViewModel with `@HiltViewModel`, register a route in `AppNavGraph.kt`, plumb action handlers from MainScreen if it's a tab.
