# Security Policy

## Supported versions

UnraidControl is a dev-distributed Android app — there is **no Play Store
build and no stable release yet**. Betas/prereleases are published as
GitHub prereleases and reach devices via the in-app updater (ADR-0008).

Only the **latest beta/prerelease** is supported. Older betas are not
patched — update to the newest prerelease before reporting.

Per-server Unraid API keys are stored encrypted on-device (Preferences
DataStore + Tink AEAD, Android-Keystore-wrapped; see ADR-0024).

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use the repository's **GitHub Private Vulnerability Reporting**:
Security tab → "Report a vulnerability". This keeps the report private
until a fix ships. There is no personal email contact by design.

Include affected app version, what you observed, and minimal repro
steps. You'll get a response on the private advisory thread.
