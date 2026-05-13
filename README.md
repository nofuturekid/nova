# UnraidControl

Native Android client for Unraid NAS servers. Kotlin + Jetpack Compose, talks to the Unraid Connect GraphQL API over HTTPS with an `x-api-key` header.

This is a fresh scaffold built from the Claude Design HTML/CSS prototype. The visual language (dark/glassy Material 3, accent palette, dense cards) matches the prototype; the implementation is real native code.

## Status

| Area | State |
| --- | --- |
| Gradle scaffold | ✅ |
| Theme + density system | ✅ |
| 4 main tabs (Overview / Array / Docker / VMs) | ✅ |
| Server list + Add/Edit (with Test Connection) | ✅ |
| Container detail (Info / Ports / Volumes) | ✅ |
| Container logs streaming | ⚠️ placeholder — needs GraphQL subscription wire-up |
| Confirm dialogs for destructive actions | ✅ |
| Settings (accent / dark / density / docker view) | ✅ |
| Pull-to-refresh | ✅ |
| GraphQL schema | ⚠️ hand-written — verify against your Unraid Connect version |
| Downloadable fonts (Inter / JetBrains Mono) | ⚠️ falls back to system fonts; see "Fonts" below |

## Requirements

- **JDK 17** on `JAVA_HOME`
- **Android SDK** with platform 36 (Android 16) installed
- **Gradle 8.10.2** (the wrapper will fetch this automatically once `gradle-wrapper.jar` is present)
- An Unraid 7.x server running the Connect plugin, with an API key

## First build

The Gradle wrapper is committed, so a checkout is enough:

```bash
./gradlew :app:assembleDebug
```

Apollo's codegen runs as part of the build and produces `net.unraidcontrol.app.graphql.*` types from the `.graphqls` files under `app/src/main/graphql/`.

## CI / CD

Two GitHub Actions workflows live in `.github/workflows/`:

- **`build.yml`** — every push to `main` and every PR. Builds a debug APK + runs Android Lint. The APK lives as an `app-debug` workflow artifact for 30 days.
- **`release.yml`** — every push of a tag matching `v*` (e.g. `v0.1.0`). Builds a signed release APK, verifies the APK signature with `apksigner`, and publishes a GitHub Release with the APK + ProGuard mapping attached.

### Release signing

The release workflow expects four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Notes |
| --- | --- |
| `KEYSTORE_B64` | Base64 of the release keystore (`base64 -w 0 app/release.keystore`). |
| `KEYSTORE_PASSWORD` | Keystore password. |
| `KEY_PASSWORD` | Key password — PKCS12 keystores require this to equal `KEYSTORE_PASSWORD`. |
| `KEY_ALIAS` | Alias inside the keystore (e.g. `unraidcontrol`). |

`app/build.gradle.kts` reads these from env vars in CI and falls back to the debug-signed config if `app/release.keystore` isn't present, so local debug builds work without any secrets.

### Cutting a release

```bash
git tag v0.1.0
git push --tags
```

This kicks off `release.yml`, which produces `UnraidControl-v0.1.0.apk` on the GitHub Releases page.

## Configuring the app

On first launch you'll see "No server configured". Tap **Menu → Add server**, fill in:

- **Server name** — display label (e.g. "Tower")
- **Local URL** — base address on your home network, e.g. `http://192.168.1.10`
- **Remote URL** — optional Unraid Connect host, e.g. `https://your-server.unraid.net`
- **API Key** — generate in the Unraid web UI under Settings → Management Access → Connect

Hit **Test** to verify, then **Save**. The connection-mode pill in the top bar toggles between Local and Remote.

## Unraid Connect schema

The GraphQL schema in `app/src/main/graphql/net/unraidcontrol/app/schema.graphqls` is **hand-written**. It's modelled on documented Unraid Connect shapes, but field names may differ between Connect plugin versions. If a query fails with `Cannot query field "X"`:

1. Hit `https://{your-server}/graphql` in a browser with the Connect plugin's GraphQL playground enabled, or run an introspection query.
2. Compare against the operations in `queries.graphql` / `mutations.graphql`.
3. Adjust the schema + operations to match. UI code is decoupled via domain models in `data/model/`, so renames only affect the schema files and `data/api/GraphQlMapper.kt`.

The currently assumed operations:

| Operation | Query | Notes |
| --- | --- | --- |
| Snapshot | `GetServerSnapshot` | One poll fetches info, array, disks, dockerContainers, vms |
| Array | `startArray`, `stopArray` | No variables |
| Container | `startContainer`, `stopContainer`, `restartContainer`, `pauseContainer` | `id: ID!` |
| VM | `startVm`, `stopVm(force)`, `pauseVm`, `resumeVm` | `id: ID!`, optional `force: Boolean` |

Container logs are not wired yet — the detail sheet shows a placeholder. Once you confirm the live schema has a subscription or query for logs, add an operation and replace `LogsTabPlaceholder` in `ui/screens/container/ContainerDetailSheet.kt`.

## Architecture

```
ui/             Composables + ViewModels (MVVM)
  theme/        Custom dark/light palette + density tokens (CompositionLocals)
  components/   Card, Pill, Btn, Sparkline, ConfirmDialog, UnraidField, etc.
  screens/      Per-screen composables + Hilt ViewModels
  nav/          NavHost
data/
  model/        Domain types (Server, Container, Vm, Disk, ...)
  api/          ApolloClientFactory + GraphQL → domain mappers
  local/        SettingsStore (DataStore) + ApiKeyStore (EncryptedSharedPreferences)
  repository/   UnraidRepository (snapshot polling + mutations),
                ServerRepository (servers CRUD), SettingsRepository (theme)
di/             Hilt modules
graphql/        Schema + operations (Apollo codegen input)
```

- All state flows are Hilt-scoped singletons or screen-scoped `StateFlow`s
- Polling cadence: 2s on the active server while any tab is foregrounded
- API keys are stored under Tink-encrypted prefs (`unraid_keys.xml`); excluded from cloud backup / device transfer

## Theme & density

The prototype's Tweaks panel is intentionally not shipped — it was a design-time tool. The user-facing equivalents live in the Settings screen (top-bar menu when no server is selected, or tap Edit on the active server to reach Add/Edit; the standalone Settings is reached via NavHost route `settings`):

- **Accent**: 5 swatches (mint / blue / purple / amber / red)
- **Mode**: dark / light
- **Density**: compact (12dp pad) / balanced (16dp) / spacious (20dp)
- **Docker view**: list / grid / grouped

Each option drives the relevant CompositionLocal and rerenders the tree.

## Fonts

The Compose typography references `FontFamily.Default` (Roboto) and `FontFamily.Monospace`. To get pixel parity with the prototype's Inter + JetBrains Mono:

1. Download the TTF files from Google Fonts.
2. Drop them under `app/src/main/res/font/` as e.g. `inter_regular.ttf`, `inter_semibold.ttf`, etc.
3. Update `ui/theme/Type.kt` to reference them via `FontFamily(Font(R.font.inter_regular, ...), ...)`.

Alternatively, set up Downloadable Fonts via `androidx.compose.ui.text.googlefonts.GoogleFont.Provider` — that requires the canonical Google Play Services cert array (see Compose docs). Skipped here to keep the scaffold self-contained.

## What's intentionally not in this scaffold

- Tests (Compose UI tests, repository tests) — easy to add but out of scope for this pass
- ProGuard rules beyond the minimum
- Crash reporting / analytics
- App Store assets

## Reference

The original design prototype is in the handoff bundle (`/tmp/design_extracted/unraid-control/`). Use it as the visual spec when adjusting the Compose UI — colors, spacing, and component shapes there are the source of truth.

## License

[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

This project is licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International** license. In plain English:

- ✅ Use it privately, on your own server, for any non-commercial purpose
- ✅ Modify it and run modifications privately
- 📤 If you distribute modifications, you must publish the modified source under the same CC BY-NC-SA 4.0 license
- ❌ No commercial sale or paid hosting of this app or its derivatives
- 🏷️ Credit the original author when redistributing

Full legal text: [LICENSE](./LICENSE) · summary: [creativecommons.org](https://creativecommons.org/licenses/by-nc-sa/4.0/)

© 2026 nofuturekid
