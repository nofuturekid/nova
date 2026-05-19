# ADR-0034: Verify the update APK and harden data-at-rest / transport

- **Status**: Accepted
- **Date**: 2026-05-19
- **Tags**: security, ci/release, data

## Context

The 2026-05-18 external code-review triage (HANDOFF "Code-review triage
(2026-05-18)") flagged a security cluster:

- **#1 (Critical):** the in-app updater downloaded an APK from a GitHub
  release asset URL and handed the bytes straight to `PackageInstaller`
  with **no integrity check** — no size check, no hash check, and no
  guarantee the download URL was HTTPS. A MITM able to influence the
  asset URL or the bytes in flight could get an arbitrary APK installed
  with the app's `REQUEST_INSTALL_PACKAGES` permission.
- **#2 (by-design base / hardening real):** `usesCleartextTraffic="true"`
  is deliberate — users run Unraid on the LAN over plain HTTP and the
  v0.1.2 LAN-HTTP decision permits that. But the *global* flag also
  permits cleartext to the GitHub update hosts, and there was no
  `network_security_config.xml` to scope it.
- **#12 (Medium):** `backup_rules.xml` / `data_extraction_rules.xml`
  excluded a stale path (`sharedpref` `unraid_keys.xml`) that no longer
  exists after ADR-0024 moved key storage to DataStore + Tink. The real
  secret/topology files (server URLs, API-key ciphertext, Tink keyset)
  were therefore being included in cloud backup and device-to-device
  transfer.

Storage layout verified against current code (`ApiKeyStore.kt`,
`SettingsStore.kt`):

| File | Domain/path | Contents |
|---|---|---|
| `unraid_prefs` DataStore | `datastore/unraid_prefs.preferences_pb` | Server names + URLs (plaintext topology) |
| `unraid_keys` DataStore | `datastore/unraid_keys.preferences_pb` | Tink AEAD ciphertext of API keys |
| Tink keyset sharedpref | `sharedpref/unraid_apikey_keyset_prefs.xml` | Tink keyset (Android-Keystore wrapped) |

## Decision

**#1 — verify before install (fail closed).**
`UpdateRepository` threads the GitHub asset `size` and `digest`
(`"sha256:<hex>"`, may be null on older assets) into `UpdateInfo`, and
**rejects a non-`https` `browser_download_url`** up front
(`UpdateState.Error`). `ApkInstaller.download(url, expectedSizeBytes,
expectedDigest, onProgress)` now, *before returning the file to the
install path*:

1. re-asserts the URL is HTTPS (defence in depth);
2. streams the body while computing SHA-256 over exactly the persisted
   bytes;
3. asserts downloaded byte count == `expectedSizeBytes`;
4. if `expectedDigest` is non-null, constant-time
   (`MessageDigest.isEqual`) compares the computed hex to the expected
   hex.

Any failure deletes the temp file and throws
`ApkInstaller.VerificationException`. `UpdateController` already maps a
thrown download error to `InstallState.Failed(message)`, so a
verification failure surfaces as a distinct install-failed message and
`PackageInstaller` is never reached.

**Residual gap (documented):** when GitHub did not digest an asset
(`digest == null`, older releases), only the size check applies. Size
alone is weak integrity, but combined with the HTTPS-only constraint on
the GitHub hosts (#2) the realistic downgrade/MITM path is closed.
Current GitHub populates `digest` for new release assets, so betas cut
from here onward get full SHA-256 verification.

**#2 — `network_security_config.xml`.** Added and referenced via
`android:networkSecurityConfig`. `base-config
cleartextTrafficPermitted="true"` keeps LAN HTTP working (the v0.1.2
decision stands; `usesCleartextTraffic="true"` is unchanged). A
`domain-config cleartextTrafficPermitted="false"` pins
`api.github.com`, `github.com`, `objects.githubusercontent.com` to
HTTPS-only regardless of the global flag.

**#12 — corrected backup / data-extraction rules.** Both
`backup_rules.xml` and `data_extraction_rules.xml`
(`cloud-backup` + `device-transfer`) now exclude the real files: the two
DataStore `.preferences_pb` files and the Tink keyset sharedpref.
Fail-safe: the entire `datastore` directory is also excluded so a future
DataStore file-name change cannot silently re-expose a secret.

## Consequences

- **Positive:** an attacker can no longer get an arbitrary APK installed
  via the updater (HTTPS-only + size + SHA-256, fail-closed). Secrets and
  LAN topology no longer leave the device via cloud backup or device
  transfer. The cleartext scope is now explicit and auditable.
- **Negative / trade-offs:** assets with no GitHub `digest` fall back to
  size-only verification (residual gap above). The `download` seam
  signature changed (all implementors/callers/tests updated). Excluding
  the whole `datastore` domain also excludes non-secret app settings from
  backup — acceptable; settings are cheap to recreate, a leaked key is
  not.
- **Trigger to revisit:** if GitHub stops providing asset `digest`, or if
  the app is ever distributed somewhere the update URL is not GitHub, the
  size-only fallback must be replaced with a mandatory pinned signature.

## Alternatives considered

- **APK signing-certificate / signature verification instead of asset
  hash.** Stronger in theory but the project signs release APKs with a
  debug-fallback key locally (see docs/local-build.md); pinning a signer
  cert across that split is brittle. Asset digest + size + HTTPS is the
  pragmatic, fail-closed check available today.
- **Drop `usesCleartextTraffic` entirely.** Would break the documented
  LAN-HTTP Unraid use case (v0.1.2). Rejected — scoped per-domain instead.
- **Leave backup rules; rely on Tink encryption.** The Tink *keyset*
  sharedpref and the plaintext server URLs would still leak; ciphertext
  plus a transferred keyset is not safe. Rejected.

## References

- HANDOFF "Code-review triage (2026-05-18)" — items #1, #2, #12.
- ADR-0008 (in-app updater / PackageInstaller), ADR-0024 (DataStore +
  Tink storage), ADR-0027 (Tier-3 on-device acceptance).
- Sibling: ADR-0035 (key-decrypt failure semantics).

## Device acceptance

v0.1.31-beta6 maintainer device-accepted 2026-05-18 (PR #146). On-device
1–5 green: LAN cleartext HTTP to local Unraid preserved (#2, no
regression); HTTPS / remote connectivity OK; the in-app update
download/verify pipeline runs end-to-end without a false rejection (#1
happy path). Latent / not on-device-testable, covered by CI + static
review: the full #1 tamper/verify proof is latent until a later release
self-updates *away from* beta6 (no post-beta6 signed asset to verify
against from within beta6 itself), as is the #1 negative / MITM-rejection
path; #12 backup & device-transfer exclusion of the key material + server
URLs is verified by static review of the backup/transfer rules, not
on-device. Tier-3 (ADR-0027) is satisfied for the testable surface; the
latent paths stay CI/review-covered until a post-beta6 self-update
exercises them.
