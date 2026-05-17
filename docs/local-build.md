# Local CI — reproducible containerized build

Run the **exact checks CI runs**, locally, in a pinned container, before
you commit or push. Goal: **green locally ⇒ green in CI**, with zero
toolchain pollution on the host (no JDK / Android SDK / Gradle installed
outside the container — all state lives in the image and one named volume).

CI round-trips here are expensive; catching a compile/lint/codegen
failure locally saves a full push → CI → read-logs cycle.

## The one command

```bash
./scripts/local-ci.sh
```

Run it before every push. It:

1. builds the toolchain image (`docker/Dockerfile`, cached — instant when
   unchanged), with your host UID/GID baked in so any files written into
   the repo are owned by you, not root;
2. runs, inside the container, the exact Gradle tasks `.github/workflows/ci.yml`
   runs:
   - `:app:generateApolloSources` (Apollo codegen — listed first so codegen
     errors surface crisply)
   - `:app:lintDebug`
   - `:app:assembleDebug`
   - `:app:assembleRelease`
3. exits non-zero on any failure (and prints `LOCAL CI FAILED`).

Release signing falls back to debug signing when no keystore is present —
that is expected locally; no secrets are needed or added.

> The task list lives in **one** place: the `GRADLE_TASKS` array at the top
> of `scripts/local-ci.sh`. If CI's tasks change, change only that array to
> keep them in lockstep. Unit tests are auto-included if a `app/src/test`
> source set ever appears (none today; CI does not run tests either).

## How the cache works

A Docker **named volume** `unraidcontrol-gradle` is mounted as the
container's Gradle user home (`/home/builder/.gradle`). It holds the
Gradle wrapper distribution, resolved Maven dependencies, and the Gradle
build cache.

- **First run** downloads the Gradle distribution and all dependencies
  into the volume (needs network).
- **Subsequent runs** reuse the volume — no re-download, much faster, and
  network-independent.

Verify the offline/warm path (used in the acceptance test):

```bash
LOCAL_CI_OFFLINE=1 ./scripts/local-ci.sh   # --network none + gradle --offline
```

Reset the cache (forces a cold run again):

```bash
docker volume rm unraidcontrol-gradle
```

The project's `app/build/` and `.gradle/configuration-cache/` are written
into the bind-mounted working tree (owned by your host user), exactly as a
host build would.

## Design: what's in the image vs. the volume

- **Image (pinned, reproducible toolchain):** Temurin JDK 21 (immutable
  Adoptium version tag), Android cmdline-tools (pinned build number),
  `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`,
  licenses accepted. This mirrors the GitHub Actions runner environment
  (`actions/setup-java` + `android-actions/setup-android`).
- **Named volume (mutable, not baked):** the Gradle user home — project
  Maven dependencies, wrapper distribution, build cache. Deliberately
  **not** baked into the image, so the image stays reproducible and free
  of project-specific state.

The build JVM is JDK 21; the app bytecode stays Java 17. That split is
controlled by `app/build.gradle.kts` (ADR-0023) — the container does not
and must not change it.

## Out of scope

This environment is **headless build + lint + Apollo codegen** (plus unit
tests if/when they exist). It deliberately does **not** run the Android
emulator or instrumented / UI tests — those need `/dev/kvm` and are a
separate concern, not handled here.

## Reproducibility notes / chosen versions

Pinned in the repo and mirrored by the image: AGP, Gradle (wrapper),
Kotlin, KSP, Hilt, Compose BOM, Apollo, JDK 21, compileSdk 36, minSdk 26.

Versions **not** pinned in the repo that this image had to choose:

| Component | Pinned value | Why this value |
|---|---|---|
| Base image | `eclipse-temurin:21.0.5_11-jdk-jammy` | Immutable Adoptium version tag (no `latest`); JDK 21 per ADR-0023 |
| Android cmdline-tools | build `11076708` | Stable, widely mirrored; sdkmanager fetches package lists at install time |
| Android build-tools | `36.0.0` | Pairs with compileSdk 36 / AGP 9.2.x |
| Android platform | `android-36` | compileSdk 36 |

`platform-tools` is the one component `sdkmanager` does not expose a
per-revision package id for, so it resolves to the current revision at
image-build time. It changes rarely and is not build-determining; rebuild
the image to refresh it.

Ubuntu apt packages (`curl`, `unzip`, `ca-certificates`) are not
hard-pinned because Ubuntu point-release mirrors rotate them; the
immutable Temurin base tag fixes the OS snapshot, which is the practical
reproducibility boundary.
