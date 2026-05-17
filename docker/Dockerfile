# Reproducible local-CI toolchain for UnraidControl.
#
# Mirrors the GitHub Actions environment (.github/workflows/ci.yml):
#   - Temurin JDK 21 as the BUILD JVM (ADR-0023). App bytecode stays
#     Java 17 — that is controlled by app/build.gradle.kts, NOT here.
#   - Android cmdline-tools + platform-36 + build-tools 36.0.0 +
#     platform-tools, licenses accepted (matches android-actions/setup-android).
#
# The image bakes ONLY the toolchain. Project Maven dependencies and the
# Gradle wrapper distribution are NOT baked — they land in the Gradle user
# home, which is a named volume (see scripts/local-ci.sh). That keeps the
# image reproducible and satisfies "no project deps in the image".
#
# Everything below is version-pinned. The one component sdkmanager does
# not let us pin is `platform-tools` (no per-revision package id) — noted
# in docs/local-build.md.

# Pinned by immutable Adoptium version tag (not a moving `21-jdk`/`latest`).
FROM eclipse-temurin:21.0.5_11-jdk-jammy

# --- pinned versions ---------------------------------------------------
# Android commandline tools build number (the _latest.zip is content-stable
# per build number; sdkmanager itself fetches package lists at install time).
ARG CMDLINE_TOOLS_BUILD=11076708
ARG ANDROID_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0
# Match the host user so files written into the bind-mounted repo (and the
# named Gradle volume) are owned by the host user, not root.
ARG HOST_UID=1000
ARG HOST_GID=1000

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/home/builder/.gradle \
    DEBIAN_FRONTEND=noninteractive

# Minimal OS deps. --no-install-recommends keeps the layer small; we do not
# hard-pin apt versions because Ubuntu point-release mirrors rotate them —
# the immutable Temurin base tag fixes the OS snapshot (documented tradeoff).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Non-root build user whose UID/GID match the host (overridable via
# --build-arg). All build state is owned by this user.
RUN groupadd -g "${HOST_GID}" builder \
 && useradd -m -u "${HOST_UID}" -g "${HOST_GID}" -s /bin/bash builder

# Android SDK: cmdline-tools at the canonical .../cmdline-tools/latest path,
# then accept licenses and install the pinned platform/build-tools.
RUN set -eux; \
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"; \
    cd /tmp; \
    curl -fsSL -o cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"; \
    unzip -q cmdline-tools.zip; \
    mv cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest"; \
    rm cmdline-tools.zip; \
    yes | "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null; \
    "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" \
      "platform-tools" \
      "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" > /dev/null; \
    chown -R builder:builder "${ANDROID_SDK_ROOT}"

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

# Pre-create the Gradle user home so the named volume mounted there
# initialises with builder ownership (not root).
RUN mkdir -p "${GRADLE_USER_HOME}" && chown -R builder:builder /home/builder

USER builder
WORKDIR /work

# The repo is bind-mounted at /work at run time; the entrypoint script
# lives in the repo (scripts/local-ci.sh) and is invoked there.
CMD ["bash", "scripts/local-ci.sh"]
