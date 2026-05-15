# ADR-0024: API-key storage on DataStore + Tink (drop deprecated EncryptedSharedPreferences)

- **Status**: Accepted
- **Date**: 2026-05-15
- **Tags**: data, security

## Context

`ApiKeyStore` persists per-server Unraid API keys — sensitive secrets.
It used `androidx.security:security-crypto`
(`EncryptedSharedPreferences` + `MasterKey`). That library was
deprecated by Google at `1.1.0-alpha07` (April 2025) and has no Jetpack
successor API. Known problems: main-thread StrictMode violations,
"keyset corruption" crashes on some OEM Keystore implementations, and
brittle cross-manufacturer behaviour. ADR-0023's PR-3 bumped it to the
stable-but-deprecated `1.1.0`, surfacing deprecation warnings in
`ApiKeyStore`.

Google's guidance for secure key/value storage is now: **Jetpack
DataStore for persistence + Google Tink for encryption + Android
Keystore for key protection**.

The app is distributed to the maintainer's own devices only (not on any
store), which materially relaxes the migration constraint.

## Decision

Reimplement `ApiKeyStore` on **Preferences DataStore + Tink AEAD**:

- Tink `Aead` (AES256-GCM). The keyset is persisted by Tink's
  `AndroidKeysetManager`, wrapped by an Android-Keystore master key
  (`android-keystore://unraid_apikey_master`).
- Ciphertext (Base64) is stored in a dedicated Preferences DataStore
  (`unraid_keys`), keyed by server id. The server id is passed as Tink
  associated data, binding each ciphertext to its server.
- `androidx.security:security-crypto` is **removed entirely** — no
  deprecated dependency remains.

**No migration from EncryptedSharedPreferences.** A migration layer
would have to keep the deprecated ESP dependency and its brittle
Keystore path alive solely to read old values. Since distribution is
dev-only, the cheaper and cleaner choice is: on first launch after the
update the old key is simply absent and is re-entered once. This mirrors
the project's standing "not publicly distributed yet" reasoning
(ADR-context for the parked rename).

The store API changes from synchronous to **`suspend`** (DataStore is
coroutine/Flow-based): `put`/`get`/`remove`/`ServerRepository.apiKeyFor`
become `suspend`; `AddEditServerViewModel.load` wraps its call in
`viewModelScope`. The `activeWithKey` flow already runs `keys.get` in a
`Flow.map` transform (a suspend lambda) so it is unaffected.

Ships as ADR-0023's follow-up beta cycle: `versionCode 41`,
`versionName 0.1.28-beta5`.

## Consequences

**Positive**
- No deprecated/abandoned crypto dependency; on Google's recommended
  stack (DataStore + Tink + Keystore).
- I/O moves off the main thread (DataStore is async) — removes the
  StrictMode-on-main-thread class of issue ESP had.
- Per-entry associated data (server id) binds ciphertext to its server.

**Negative / trade-offs**
- One-time UX cost: existing dev-device installs lose the stored key on
  the beta5 update and must re-enter it once (accepted; dev-only).
- New `com.google.crypto.tink:tink-android` dependency (sizeable, but
  the standard choice).
- Crypto + Keystore correctness is **runtime**-only behaviour that CI
  cannot prove — CI verifies compile/lint, not that encrypt/decrypt and
  the Keystore-wrapped keyset work on a real device.

**Trigger to revisit**
- Jetpack ships a first-party successor to EncryptedSharedPreferences →
  reassess vs. Tink.
- Tink deprecates the `AndroidKeysetManager` recipe used here.
- App moves to public distribution → revisit whether a real migration
  path is then required (it is not now).

## Alternatives considered

- **DataStore + Tink with an ESP→new migration layer.** Initially
  chosen, then rejected: keeps the deprecated ESP dependency and its
  fragile Keystore code alive only to read legacy values; unjustified
  for a dev-only build where re-entering the key once is trivial.
- **Keep deprecated `security-crypto 1.1.0`.** Zero work, but inherits
  the abandoned library, the OEM keyset-corruption bug class, and no
  future fixes. Only defers the problem.
- **Community JetSec fork.** Minimal change, same API, warning gone —
  but swaps a Google dependency for community-maintained code without
  moving to the recommended stack.
- **DataStore + hand-rolled Keystore AES-GCM (no Tink).** Fewer
  dependencies, but rolling crypto for sensitive keys is the higher-risk
  option; Tink exists precisely to avoid that.

## Test plan

CI proves it compiles/lints under the AGP-9 toolchain. **Runtime
verification is manual, on a dev device** (CI cannot cover crypto/
Keystore):

1. Fresh install beta5 → add a server with an API key → force-stop &
   reopen → key still present and the server connects (encrypt + persist
   + decrypt round-trip + Keystore-wrapped keyset survive process death).
2. Edit the server → the stored key pre-populates (`apiKeyFor` suspend
   path).
3. Delete the server → key removed from the store.
4. Update from an older build (had ESP key) → key absent as expected;
   re-enter once → works thereafter (the no-migration decision).

## References

- ADR-0023 (toolchain bump that surfaced the deprecation; PR-3 took
  security-crypto to the deprecated stable 1.1.0).
- Android Security library release notes (EncryptedSharedPreferences
  deprecation, `1.1.0-alpha07`).
- Tink Java (`com.google.crypto.tink:tink-android`) AEAD +
  `AndroidKeysetManager` Android recipe.
- Jetpack DataStore (Preferences) guidance as the ESP successor stack.
