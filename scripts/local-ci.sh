#!/usr/bin/env bash
#
# local-ci.sh — run the EXACT CI checks locally, in a pinned container,
# with zero toolchain pollution on the host. "Green here ⇒ green in CI".
#
#   ./scripts/local-ci.sh            # build image (cached) + run all checks
#   LOCAL_CI_OFFLINE=1 ./scripts/local-ci.sh   # also cut the network (warm/offline proof)
#
# One file, two modes: on the host it builds the image and re-invokes
# itself inside the container; inside the container it runs Gradle.
#
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────
# THE canonical CI task list. This is the single source of truth and must
# stay in lockstep with .github/workflows/ci.yml:
#   build-debug   → :app:assembleDebug :app:lintDebug
#   build-release → :app:assembleRelease   (debug-signed locally when no
#                   keystore is present — expected, see ADR/CONTRIBUTING)
# generateApolloSources is a transitive dependency of assemble; it is
# listed explicitly first so Apollo codegen failures surface crisply.
# If CI's task list changes, change ONLY this array.
# ─────────────────────────────────────────────────────────────────────
GRADLE_TASKS=(
  :app:generateApolloSources
  :app:lintDebug
  :app:assembleDebug
  :app:assembleRelease
)

IMAGE="unraidcontrol-localci:1"
VOLUME="unraidcontrol-gradle"   # named volume = Gradle user home (deps cache,
                                # wrapper dist, build cache). Survives between
                                # runs so only the first run downloads.

# ===== in-container mode: run the actual Gradle build ==================
if [[ "${IN_LOCAL_CI_CONTAINER:-}" == "1" ]]; then
  GRADLE_FLAGS=(--stacktrace --no-daemon --console=plain)
  if [[ "${LOCAL_CI_OFFLINE:-}" == "1" ]]; then
    GRADLE_FLAGS+=(--offline)
    echo ">> OFFLINE mode: --offline + no container network"
  fi
  # CI does not run unit tests (none exist). Auto-include them if/when
  # the project grows a test source set, so this stays in lockstep.
  if [[ -d app/src/test ]]; then
    GRADLE_TASKS+=(:app:testDebugUnitTest)
    echo ">> test sources detected → adding :app:testDebugUnitTest"
  fi
  echo ">> ./gradlew ${GRADLE_TASKS[*]} ${GRADLE_FLAGS[*]}"
  exec ./gradlew "${GRADLE_TASKS[@]}" "${GRADLE_FLAGS[@]}"
fi

# ===== host mode: build the image, then run this script in it =========
command -v docker >/dev/null || { echo "ERROR: docker not found on PATH" >&2; exit 1; }

REPO="$(git rev-parse --show-toplevel)"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

echo "==> Building image ${IMAGE} (cached if unchanged)…"
docker build \
  --build-arg "HOST_UID=${HOST_UID}" \
  --build-arg "HOST_GID=${HOST_GID}" \
  -t "${IMAGE}" \
  -f "${REPO}/docker/Dockerfile" \
  "${REPO}"

DOCKER_RUN=(
  docker run --rm
  -e IN_LOCAL_CI_CONTAINER=1
  -e "LOCAL_CI_OFFLINE=${LOCAL_CI_OFFLINE:-}"
  -v "${REPO}:/work"
  -v "${VOLUME}:/home/builder/.gradle"
  -w /work
)
if [[ "${LOCAL_CI_OFFLINE:-}" == "1" ]]; then
  DOCKER_RUN+=(--network none)
fi
DOCKER_RUN+=("${IMAGE}" bash scripts/local-ci.sh)

echo "==> Running CI checks in container…"
START=$(date +%s)
if "${DOCKER_RUN[@]}"; then
  echo "==> LOCAL CI PASSED in $(( $(date +%s) - START ))s — safe to push."
else
  rc=$?
  echo "==> LOCAL CI FAILED (rc=${rc}) in $(( $(date +%s) - START ))s — fix before pushing." >&2
  exit "${rc}"
fi
