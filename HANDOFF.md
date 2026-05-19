# Handoff / project notes

Snapshot for resuming this project elsewhere. Keep up to date when major decisions or version bumps happen.

## What this is

Native Android client for Unraid NAS servers (Kotlin + Jetpack Compose, Apollo GraphQL against Unraid 7's Connect API). Recreated from a Claude Design HTML/CSS prototype.

## Repo

- URL: https://github.com/nofuturekid/UnraidControl
- License: GPL-3.0-only (relicensed from CC BY-NC-SA 4.0 — see ADR-0021)
- Default branch: `main` (protected)
- Owner: nofuturekid (admin bypass on)

## Releases shipped so far

| Tag | Class | Headline |
| --- | --- | --- |
| v0.1.0 | release | Initial scaffold |
| v0.1.1 | release | System-bar insets + empty-state Add-Server callback |
| v0.1.2 | release | `usesCleartextTraffic` for LAN HTTP |
| v0.1.3 | release | `{ __typename }` ping for Test Connection |
| v0.1.4 | release | Full Unraid 7 schema rewrite |
| v0.1.5 | release | Add/Edit Server tap target + real API key load |
| v0.1.6 | release | Live CPU/RAM graphs + Docker container icons (Coil) |
| v0.1.7 | release | Promote-flow + branch protection + CONTRIBUTING |
| v0.1.8-beta1, beta2 | pre-release | Pull-to-refresh spinner fix (beta channel test) |
| v0.1.8 | release | Pull-to-refresh fix (stable) |
| v0.1.9 | release | Transient poll error tolerance (3 polls grace) |
| v0.1.10 | release | Array tab unit fix (ArrayDisk fields are KB) |
| v0.1.11 | release | Container detail: real logs fetch + volume mappings |
| v0.1.12–0.1.21 | release | Incremental fixes/features (see git tags / GitHub releases) |
| v0.1.22–0.1.25 | release | Docker-update phases A–C (ADR-0016/0017), WebUI link, GPL-v3 relicense (ADR-0021), CI acceleration (ADR-0018/0019/0020) |
| v0.1.26 | release | Density setting wired, per-view layout (List/Grid/Grouped) |
| v0.1.27-beta1 | pre-release | (interim) |
| v0.1.28-beta1…rc2 | pre-release | **AGP-9 toolchain** (ADR-0023), **API-key → Tink+DataStore** (ADR-0024), UI Light/Dark pass, VM detail sheet + slim cards, ContainerDetailSheet layout, numeric SemVer compare, container-update-button fix |
| v0.1.28 | release | Stable promotion of the whole 0.1.28 cycle, device-verified |
| v0.1.29-beta1…beta7 | pre-release | M3 type-scale migration; Docker-logs Dark/Light; Theme-mode **Dark/Light/System**; Dependabot adopted (ADR-0025); **notifications bell+badge+sheet**; UI polish (changelog typo, overview cards, banner); system-bar contrast vs app theme |
| v0.1.29-beta8…beta13 | pre-release | Container-sheet **log live-tail + pull-to-refresh**; Phase E GraphQL-subscription pilot E0+E1 (ADR-0026) — WSS transport works but reverted (no subscribe-snapshot, server-side FS-watcher); back to polling; **ADR-0027** agent autonomy/access model |
| v0.1.29 | release | Stable promotion of the 0.1.29 cycle (polling-only; container-sheet live-tail/pull-to-refresh; Phase E evaluated → provisionally parked per ADR-0026/0027) |
| v0.1.30-beta1…beta7 | pre-release | M3 modernization roadmap (ADR-0030): standard Material-3 components throughout; accessibility + visual-consistency pass; AGP cleanup; Apollo-client reuse |
| v0.1.30 | release | Stable promotion of the whole 0.1.30 cycle, device-verified |
| v0.1.31-beta1…beta9 | pre-release | Notifications actions + Unread/Archived tabs + bell badge (ADR-0032); security hardening — verified APK install, network-security config, backup exclusions, key-decrypt re-enter (ADR-0034/0035); code-review triage fixes; CI-governance (ADR-0036) |
| **v0.1.31** | **release (Latest)** | Stable promotion of the whole 0.1.31 cycle (beta1…beta9 maintainer device-accepted): notifications actions, security hardening (ADR-0034/0035), code-review triage fixes, CI governance (ADR-0036), maintainer device-accepted 2026-05-18 |

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

## Layout

```
app/src/main/
├── graphql/net/unraidcontrol/app/
│   ├── schema.graphqls        hand-written from the unraid/api repo's generated-schema.graphql
│   ├── queries.graphql        Ping, GetServerSnapshot, FetchContainerLogs
│   └── mutations.graphql      Array.setState, Docker.start/stop/pause/unpause, Vm.start/stop/pause/resume/forceStop
└── kotlin/net/unraidcontrol/app/
    ├── MainActivity.kt
    ├── UnraidControlApp.kt        @HiltAndroidApp
    ├── data/
    │   ├── model/Models.kt        Server, Container, Vm, Disk, ServerSnapshot, LogLine
    │   ├── api/                   ApolloClientFactory (per-server, x-api-key header), GraphQlMapper
    │   ├── local/                 SettingsStore (DataStore), ApiKeyStore (Tink AEAD + Preferences DataStore, ADR-0024)
    │   └── repository/            UnraidRepository (polled snapshot stream + mutations + logs),
    │                              ServerRepository (CRUD + active server),
    │                              SettingsRepository (theme/density/docker view)
    ├── di/AppModule.kt            empty — Hilt constructor injection
    └── ui/
        ├── theme/                 Color, Density, Type, Theme (CompositionLocals)
        ├── components/            Card, Pill, Btn, IconBtn, Sparkline, Progress, StackBar,
        │                          ContainerIcon (Coil), UnraidField, ConfirmDialog, etc.
        ├── nav/AppNavGraph.kt     single NavHost: main / server_list / settings + modal sheets
        └── screens/
            ├── main/              MainScreen + MainViewModel (Hilt)
            ├── overview/          sparklines + system info
            ├── array/             disk cards + parity strip
            ├── docker/            list/grid/grouped + real icons
            ├── vms/
            ├── container/         detail sheet (Info / Logs / Ports / Volumes)
            ├── server/            list + Add/Edit sheet
            ├── notifications/     warnings+alerts bell sheet (0.1.29-beta6)
            └── settings/          accent / theme-mode (Dark/Light/System) / density / views
```

## CI / CD workflow

The "why" for everything in this section lives in ADRs under [`docs/adr/`](docs/adr/). What follows is the operational summary.

- **Branch protection** on `main`: PRs required, required status check `build`, 0 required approvals (solo-dev-friendly). See [ADR-0003](docs/adr/0003-pr-gate-required-status-check.md).
- **Build-once-promote** split between `ci.yml` (builds debug+release APKs, uploads artifacts) and `release.yml` (`v*` tag → finds the SHA's CI run, downloads its `app-release` artifact, publishes GitHub Release; ~16s, **never rebuilds**). See [ADR-0004](docs/adr/0004-build-once-promote-pipeline.md).
- **Concurrency**: `cancel-in-progress` only on PR branches, never on main — main runs must complete so `release.yml` can find them. See [ADR-0011](docs/adr/0011-cancel-in-progress-only-on-pr-branches.md).
- **Docs-only CI bypass**: `ci.yml` skips heavy steps when the diff is `*.md` / `LICENSE` / `docs/` / AI-assistant configs — finishes in ~10s, still reports `SUCCESS` on `build`. No version bump, no tag for these. See [ADR-0009](docs/adr/0009-docs-only-ci-bypass.md).
- **Tag conventions**: stable `v0.X.Y`, pre-release `v0.X.Y-beta1` / `-rc1` with trailing digit required. See [ADR-0005](docs/adr/0005-prerelease-tag-convention.md).
- **Release-class policy**: pragmatic risk-categorised beta-first. UI/docs → direct stable. Schema / manifest / SettingsStore / dependency bumps / install-flow → beta-first. See [ADR-0006](docs/adr/0006-pragmatic-beta-first-release-policy.md).

### Release procedure (operational)

1. Feature branch → PR → CI green → squash-merge → branch auto-deleted.
2. Version-bump PR (its own commit per [ADR-0015](docs/adr/0015-promotion-requires-new-commit.md)).
   **Title it `Release vX.Y.Z[-betaN] — <summary>`** — not "Version bump" /
   "Promote". See CONTRIBUTING "Naming the release commit / PR".
3. CI green on the bump → squash-merge.
4. Tag the bump commit: `git tag -a vX.Y.Z[-betaN] -m "..." && git push origin vX.Y.Z[-betaN]`.
5. `release.yml` promotes the existing artifact.
6. If beta: test on device → new bump PR (`Release vX.Y.Z (stable) — …`,
   versionCode bumped again) once happy. No re-tagging the same commit
   (ADR-0015 superseded the old "same commit, new tag" flow).

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
- **In-app updater** lives under `data/update/`: `UpdateRepository` polls `https://api.github.com/repos/nofuturekid/UnraidControl/releases` (no auth, 60 req/h rate limit fine for on-start checks), `UpdateInstaller` downloads APK to `cacheDir/updates/` and uses `PackageInstaller` for the install handshake, `InstallStatusReceiver` catches the session callback via a `BroadcastReceiver` declared in the manifest. The `REQUEST_INSTALL_PACKAGES` permission is needed; on first install Android prompts the user to whitelist us at `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`. The Settings screen exposes the "Include pre-releases" toggle + manual "Check now". Banner on MainScreen dismisses per-tag via `dismissedUpdateTag` in DataStore.
- Cleartext HTTP requires `android:usesCleartextTraffic="true"` in the manifest. LAN access against `http://192.168.x.x` needs this.
- **CI `test` job (ADR-0036) can transiently fail at Gradle *configuration*** with a plugin-not-resolvable error (e.g. `com.google.devtools.ksp` not found) on a plugin-portal/registry blip — it fetches plugins fresh under PR `cache-read-only`. **Re-run the job; it is not a code defect** (see ADR-0036 "Known consequence — transient plugin-fetch in the `test` job" note; PR #163 2026-05-19).

### What the Unraid API does NOT expose

- Network throughput (rx/tx) — not in the schema. UI displays 0.
- Per-container CPU/Mem on `DockerContainer` — needs separate `dockerContainerStats(id)` query (schema has it, we haven't wired it).
- VmDomain has only id/name/state — no vcpu/memGb/gpu fields. UI placeholder zeros.
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
git clone https://github.com/nofuturekid/UnraidControl
cd UnraidControl
./gradlew :app:assembleDebug          # debug build, no keystore needed
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

External deep review verified against HEAD f86c25f. Tracks below are binding; see ADR-0030 for the UI follow-ups and the test backlog under Open TODOs.

| # | Item | Verdict | Track |
|---|------|---------|-------|
| 1 | No APK SHA-256/size verify before silent install | REAL (Crit) | Security ADR + beta (Step 2) |
| 2 | Cleartext global, no network_security_config | BY-DESIGN base (v0.1.2 LAN-HTTP) / hardening REAL | Security ADR + beta (Step 2) |
| 3 | stateIn retains prev server's DomainState on switch (stale data under new name) | REAL (Crit) | fix-now beta (own, Step 3) |
| 4 | log-tail loop catch swallows CancellationException | REAL (Crit) | fix-now beta (Step 3 trivial batch) |
| 5 | HttpLoggingInterceptor BASIC, no DEBUG guard (release log leak) | REAL (High) | fix-now beta (Step 3 trivial batch) |
| 6 | deleteNotification n.type ?: Unread "wrong on Archived" | STALE — premise false at HEAD (type is per-item from server mapper) | wontfix (rationale) |
| 7 | AddEditServer LaunchedEffect(server?.id) null-key collision | REAL (High) | fix-now beta (Step 3 trivial batch) |
| 8 | ApiKeyStore.get() swallows Tink decrypt failure → permanent false "Missing API key" | NEEDS-JUDGMENT | ADR-0024 amendment + beta (Step 2) |
| 9 | launchPermissionIntent resets before verifying launch(); ActivityNotFound eaten | REAL (High) | fix-now beta (Step 3 trivial batch) |
| 10 | InstallStatusReceiver SharedFlow replay=0 startup race | REAL (High) | fix-now beta (own, Step 3) |
| 11 | apiKey verbatim in Apollo client map key | ROUTE→ADR-0028 (already accepted verbatim) | no action (ADR-0028) |
| 12 | backup/data-extraction rules stale post-ADR-0024; unraid_prefs (server URLs) + keyset not excluded | REAL (Med) | Security ADR + beta (Step 2) |
| 13 | SettingsScreen no verticalScroll, clips short screens | REAL → ROUTE | ADR-0030 follow-up |
| 14 | refreshAll() fires gated streams | BY-DESIGN (ADR-0017) | wontfix (rationale) |
| 15 | hardcoded Color(0xFF06120E) vs onPrimary at 6 sites | REAL → ROUTE | ADR-0030 follow-up (P1 nachzug) |
| 16 | SettingsScreen bespoke Toggle vs M3 Switch (no Role.Switch) | REAL → ROUTE | ADR-0030 follow-up |
| 17 | LaunchedEffect(openContainer) non-suspending → one-frame stale dockerGate | REAL (Med) | fix-now beta (Step 3 trivial batch) |
| 18 | server-switch DomainState reset untested | REAL gap | test backlog |
| 19 | runNotificationAction network-error propagation untested | REAL gap | test backlog |
| 20 | ServerRepository.delete of only server (setActiveServer(null) NPE) untested | REAL gap | test backlog |
| 21 | domainStream TRANSIENT_ERROR_TOLERANCE=3 untested | REAL gap | test backlog |
| 22 | UpdateController.installUpdate re-entrancy untested | REAL gap | test backlog |

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

### Decision needed
- [ ] Overview Network card shows `0.0 Mbps` (Unraid API exposes no host throughput) — remove / keep / file unraid-api feature request.

### Parked / blocked / out of scope
- **Phase E — GraphQL subscriptions: piloted, reverted — PROVISIONALLY.**
  ADR-0026 (Deprecated, but reversal is provisional). WSS transport +
  auth work; the blockers are no subscribe-snapshot + notifications
  gated by a server-side FS-watcher, but the deciding factor was that
  iterating cost a multi-beta user-relay loop. **Not a closed door:**
  revisit once an ADR-0027 test environment exists — re-test system
  metrics first (interval publisher, not the FS-watcher; highest
  value). Reverted in 0.1.29-beta13; polling (ADR-0017) stays the only
  data path for now. Per-container CPU/Mem (old #2) stays blocked while
  subscriptions are paused (subscription-only field).
- **Baseline Profiles: parked** — modest startup gain vs. real infra
  cost (generation needs an emulator; only an on-demand GMD workflow
  fits our lean CI). Own ADR if revisited.
- **App-rename: waiting on Lime Technology** (trademark mail sent
  2026-05-15). If needed: full package/applicationId change + own ADR.
- VM vCPU/RAM/GPU stay placeholder zeros — `VmDomain` doesn't expose them.
- Container auto-start toggle read-only; Restart = stop+start (no atomic
  mutation in schema).

## Useful gh commands

```bash
gh pr list --repo nofuturekid/UnraidControl
gh run list --repo nofuturekid/UnraidControl --limit 10
gh release list --repo nofuturekid/UnraidControl
gh run download <id> --repo nofuturekid/UnraidControl --name app-release
gh secret list --repo nofuturekid/UnraidControl
```

## Resuming work — quick start

1. `git pull` on main
2. Branch: `git checkout -b feat/something` or `fix/whatever`
3. Code → commit → `git push -u origin <branch>`
4. `gh pr create` (or open via browser)
5. Wait for CI green
6. `gh pr merge <n> --squash --delete-branch`
7. Pull main, tag if shipping: `git tag -a v0.X.Y -m "..." && git push origin v0.X.Y`

### Schema changes
Edit `schema.graphqls` + `queries.graphql` / `mutations.graphql` + `GraphQlMapper.kt`. UI never imports Apollo types — domain models in `data/model/Models.kt` are the contract.

### New screen
Create folder under `ui/screens/`, add ViewModel with `@HiltViewModel`, register a route in `AppNavGraph.kt`, plumb action handlers from MainScreen if it's a tab.
