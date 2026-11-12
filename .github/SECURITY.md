# Security Policy

## Supported versions

NOVA is an Android app distributed as GitHub Releases — stable releases and
pre-releases (betas), installed from the Releases page or via the in-app
updater (ADR-0008) — and, for stable releases, through F-Droid (ADR-0040).

Supported are the **latest stable release** and the **current pre-release**
(when one is newer than the stable). Older versions are not patched — update
before reporting.

Per-server Unraid API keys are stored encrypted on-device (Preferences
DataStore + Tink AEAD, Android-Keystore-wrapped; see ADR-0024).

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use the repository's **GitHub Private Vulnerability Reporting**:
Security tab → "Report a vulnerability". This keeps the report private
until a fix ships. There is no personal email contact by design.

Include affected app version, what you observed, and minimal repro
steps. You'll get a response on the private advisory thread.
