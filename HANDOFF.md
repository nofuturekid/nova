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

## Stack

- Kotlin **2.0.21**, AGP **8.9.2**, Gradle **8.11.1**
- compileSdk/targetSdk **36**, minSdk **26**
- Jetpack Compose BOM **2024.12.01**, Material 3
- Apollo Kotlin **4.1.1**
- Hilt **2.52** + KSP **1** (NOT KSP2 — broken with Hilt 2.52)
- DataStore + AndroidX Security Crypto (Tink) for storage
- Coil **3.0.4** for AsyncImage
- kotlinx.coroutines 1.9.0, kotlinx.serialization 1.7.3

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
    │   ├── local/                 SettingsStore (DataStore), ApiKeyStore (Tink EncryptedSharedPrefs)
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
            └── settings/          accent / dark / density / docker view
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
- **KSP1 only**: `ksp.useKSP2=false` in `gradle.properties`. Hilt 2.52 + KSP2 fails with "Did you forget to apply the Gradle plugin?" even when the plugin is applied.
- **`DockerContainer.mounts` is `JSON` (single scalar) NOT `[JSON!]`** — the JSON content itself is an encoded array of mount objects. Easy to mistype based on intuition; v0.1.11 made the snapshot fail server-wide because of this exact wrong type. Parse via `parseMountsArray` in `GraphQlMapper.kt`.
- **JSON scalar → kotlin.Any via Apollo's `AnyAdapter`** — the Unraid GraphQL `JSON` scalar serialises as inline JSON values (objects/arrays/primitives), not pre-stringified text. Custom `kotlin.String` adapters crash. We map it to `kotlin.Any` with `com.apollographql.apollo.api.AnyAdapter` (public, built-in). Don't try to write your own with `reader.readAny()` — that's `@ApolloInternal`.
- **In-app updater** lives under `data/update/`: `UpdateRepository` polls `https://api.github.com/repos/nofuturekid/UnraidControl/releases` (no auth, 60 req/h rate limit fine for on-start checks), `UpdateInstaller` downloads APK to `cacheDir/updates/` and uses `PackageInstaller` for the install handshake, `InstallStatusReceiver` catches the session callback via a `BroadcastReceiver` declared in the manifest. The `REQUEST_INSTALL_PACKAGES` permission is needed; on first install Android prompts the user to whitelist us at `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`. The Settings screen exposes the "Include pre-releases" toggle + manual "Check now". Banner on MainScreen dismisses per-tag via `dismissedUpdateTag` in DataStore.
- Cleartext HTTP requires `android:usesCleartextTraffic="true"` in the manifest. LAN access against `http://192.168.x.x` needs this.

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

## Open TODOs

### Bug-class
- [ ] AGP deprecation: move `android.defaults.buildfeatures.buildconfig=true` (gradle.properties) into the app module's `android.buildFeatures.buildConfig = true`. Removed in AGP 10.
- [ ] Container logs only fetched once on tab open — no live tail, no in-sheet refresh.
- [ ] AsyncImage occasionally 404s silently on edge-case icon URLs.

### Feature gaps vs the design prototype
- [ ] Network sparkline flat zero — Unraid GraphQL doesn't expose throughput. Would need REST scraping or proc.
- [ ] Per-container CPU%/Mem in Info tab — wire `dockerContainerStats(id)`.
- [ ] VM details (vCPU / RAM / GPU placeholders) — schema doesn't expose them on VmDomain.
- [ ] Auto-start toggle in container Info is read-only — no mutation.
- [ ] Container Restart emulated as stop+start (no atomic mutation in schema).
- [ ] Pull-to-refresh on container detail sheet.
- [ ] Parity check mutations (start/pause/resume/cancel) — schema has them, UI doesn't.

### Production hardening
- [ ] No tests (Compose UI tests, repository unit tests).
- [ ] No crash reporting.
- [ ] No offline cache — cold start always hits network.
- [ ] Inter / JetBrains Mono are fallback `FontFamily.Default`/`Monospace`. Bundle real TTFs in `res/font/` for parity with prototype.

### Polish
- [ ] German localization (UI is English only).
- [ ] App icon is a placeholder "UC" mark — could use a real logo.
- [ ] CHANGELOG.md auto-generated from tags.

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
