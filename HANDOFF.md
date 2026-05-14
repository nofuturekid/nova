# Handoff / project notes

Snapshot for resuming this project elsewhere. Keep up to date when major decisions or version bumps happen.

## What this is

Native Android client for Unraid NAS servers (Kotlin + Jetpack Compose, Apollo GraphQL against Unraid 7's Connect API). Recreated from a Claude Design HTML/CSS prototype.

## Repo

- URL: https://github.com/nofuturekid/UnraidControl
- License: CC BY-NC-SA 4.0
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

### Branch protection

- `main` requires PRs to merge
- Required status check: `build` (the job name in `ci.yml`)
- 0 required approvals (solo-dev-friendly, fork-PR-tolerant)
- Admin (nofuturekid) can direct-push for emergencies

### Workflows

**`.github/workflows/ci.yml`** — push to main + PR + workflow_dispatch
- Builds BOTH `app-debug` and `app-release` APKs in one Gradle invocation
- Conditionally decodes `KEYSTORE_B64` secret; fork PRs without secrets get debug-only and still go green
- Verifies release APK signature via `apksigner`
- Uploads artifacts: `app-debug` (30d), `app-release` (90d), `mapping` (90d), `lint-report` (14d)
- `concurrency: cancel-in-progress: true` — newer push cancels older run

**`.github/workflows/release.yml`** — push tag `v*`
- Resolves tag → commit SHA
- Calls Actions API to find the latest **successful** `ci.yml` run for that SHA
- Downloads `app-release` + `mapping` artifacts from that run — **does NOT rebuild**
- Renames APK to `UnraidControl-vX.Y.Z.apk`
- Detects pre-release: tag matches `-(alpha|beta|rc|pre)[0-9]+$` → flagged on GitHub
- Publishes via `softprops/action-gh-release@v2`
- Total runtime: ~16 seconds (vs 3-4 min if it rebuilt)

### Tag conventions

- **Stable**: `v0.X.Y` → `isLatest = true`
- **Beta**: `v0.X.Y-beta1`, `v0.X.Y-beta2`, … → `isPrerelease = true`, not Latest
- **RC**: `v0.X.Y-rc1`, `v0.X.Y-rc2`, …
- The trailing digit is **required**. Plain `-beta` rejected as typo guard.

### Release procedure

1. Feature branch → PR → CI green → squash-merge → branch auto-deleted
2. ci.yml runs on main (auto)
3. Wait for green
4. Tag: `git tag -a v0.X.Y -m "..." && git push origin v0.X.Y`
5. release.yml promotes the existing artifact (~16s)

If a tag is pushed before CI on its commit is green, `release.yml` fails with "no successful CI run found for commit". Push to main first, wait, then tag.

## Schema mapping decisions

### Apollo scalar mappings (`app/build.gradle.kts`)

```kotlin
mapScalar("PrefixedID", "kotlin.String")
mapScalar("DateTime",   "kotlin.String")
mapScalar("BigInt",     "kotlin.Long")
mapScalar("JSON",       "kotlin.String")  // parsed downstream
mapScalar("URL",        "kotlin.String")
mapScalar("Port",       "kotlin.Int")
```

### Critical gotchas (don't relearn the hard way)

- **ArrayDisk sizes are KILOBYTES**, not bytes. Use `kbToTb()`. (Top-level `Disk` type uses bytes but we don't query that.)
- **Mutations are nested**: `mutation { docker { start(id) } }` not top-level `startContainer(id)`.
- **Docker containers** live under `docker.containers`, NOT top-level `dockerContainers`.
- **VMs** live under `vms.domains`, NOT a top-level list.
- Apollo with `generateOptionalOperationVariables = false`: optional GraphQL vars become `T?` directly. **Never wrap in `Optional.present()`** — it won't compile.
- **KSP1 only**: `ksp.useKSP2=false` in `gradle.properties`. Hilt 2.52 + KSP2 fails with "Did you forget to apply the Gradle plugin?" even when the plugin is applied.
- **`DockerContainer.mounts` is `JSON` (single scalar) NOT `[JSON!]`** — the JSON content itself is an encoded array of mount objects. Easy to mistype based on intuition; v0.1.11 made the snapshot fail server-wide because of this exact wrong type. Parse via `parseMountsArray` in `GraphQlMapper.kt`.
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
