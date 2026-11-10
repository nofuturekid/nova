#!/usr/bin/env bash
#
# store-screenshots.sh — re-render the F-Droid store screenshots
# (fastlane/metadata/android/en-US/images/phoneScreenshots/*.png) from the
# app's real Compose screens with a fictional demo server. No phone, no
# emulator, no network to any server. Runs in the same container as
# scripts/local-ci.sh. See docs/local-build.md ("Store screenshots").
#
#   ./scripts/store-screenshots.sh
#
set -euo pipefail

IMAGE="unraidcontrol-localci:1"
VOLUME="unraidcontrol-gradle"
REPO="$(git rev-parse --show-toplevel)"

command -v docker >/dev/null || { echo "ERROR: docker not found on PATH" >&2; exit 1; }

docker build \
  --build-arg "HOST_UID=$(id -u)" --build-arg "HOST_GID=$(id -g)" \
  -t "${IMAGE}" -f "${REPO}/docker/Dockerfile" "${REPO}"

docker run --rm \
  -v "${REPO}:/work" -v "${VOLUME}:/home/builder/.gradle" -w /work \
  "${IMAGE}" \
  ./gradlew :app:testDirectDebugUnitTest -PnovaScreenshots --no-daemon --console=plain
