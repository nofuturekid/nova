# ADR-0035: Distinguish absent vs. undecryptable stored API keys

- **Status**: Accepted
- **Date**: 2026-05-19
- **Tags**: security, data

This ADR **amends ADR-0024** (API-key storage on DataStore + Tink). It
does not change the storage mechanism — only the read-failure semantics.

## Context

The 2026-05-18 triage item **#8 (needs-judgment)**: `ApiKeyStore.get()`
was

```kotlin
runCatching {
    val ct = Base64.decode(enc, ...)
    String(aead.decrypt(ct, ...))
}.getOrNull()
```

So two very different situations collapsed to the same `null`:

1. **Absent** — no ciphertext was ever stored for this server (normal
   first run, or post-update before the key is entered).
2. **Undecryptable** — ciphertext *is* present but Tink `decrypt` threw.
   The realistic cause is loss of the Android-Keystore master key that
   wraps the Tink keyset: factory reset, "clear app data" on some OEMs,
   or Keystore corruption. ADR-0024 explicitly chose **no migration** and
   accepted re-entry, so this edge is expected to occur.

`UnraidRepository` turned the resulting blank key into
`DomainState.Error("Missing API key for <server>")`. In the
undecryptable case that message is actively misleading: the user *did*
save a key, so "missing" sends them looking for the wrong problem
instead of just re-entering it.

## Decision

`ApiKeyStore` exposes `getResult(serverId): ApiKeyResult`, a sealed type
with `Present(key)` / `Absent` / `Undecryptable`. `get()` is retained as
`(getResult(...) as? Present)?.key` for callers that only need the key or
nothing (the Add/Edit-server prefill).

`ServerRepository.ActiveServer` carries the `ApiKeyResult` as
`keyState`. `UnraidRepository`'s blank-key branch keeps the existing
"Missing API key for <server>" message for `Absent`, but emits a
**distinct** message for `Undecryptable`:

> "Saved API key can no longer be decrypted — re-enter it for <server>"

No new UX is built: the existing Add/Edit-server flow already lets the
user re-enter the key, which calls `ApiKeyStore.put` and overwrites the
bad ciphertext. The decrypt exception is still swallowed deliberately
(it could carry ciphertext-derived data) and is **never logged**; only
the opaque `Undecryptable` marker propagates. No key material is logged.

## Consequences

- **Positive:** the undecryptable-key edge produces an accurate,
  actionable message instead of a false "missing key", with no silent
  null swallow. Scope stayed tight — one sealed result, one message, the
  existing re-enter path.
- **Negative / trade-offs:** `ActiveServer` gained a field and the read
  API surface grew by one method; callers were updated. We cannot
  distinguish "Keystore lost" from "ciphertext corrupted" — both are
  `Undecryptable`; the user remedy (re-enter) is identical so finer
  granularity has no value.
- **Trigger to revisit:** if a migration/recovery path for the keyset is
  ever introduced, or if a caller needs to react to `Undecryptable`
  differently from showing the message (e.g. auto-prompt a dialog),
  revisit the result-to-UI mapping.

## Alternatives considered

- **Typed exception from `get()` instead of a sealed result.** Works,
  but a sealed `ApiKeyResult` keeps the absent case exception-free (it is
  not exceptional) and forces callers to handle both failure modes at the
  type level. Chosen for explicitness.
- **Keep returning `null`, branch on "ciphertext present" separately at
  the call site.** Leaks the storage detail (presence of ciphertext)
  into the repository and is easy to get wrong. Rejected.
- **Auto-delete undecryptable ciphertext and fall back to Absent.**
  Silently destroys data and still shows "missing key". Rejected — the
  user must be told what happened.

## References

- HANDOFF "Code-review triage (2026-05-18)" — item #8.
- Amends **ADR-0024** (API-key storage on DataStore + Tink).
- Sibling: ADR-0034 (update + data-at-rest hardening).

## Device acceptance

v0.1.31-beta6 maintainer device-accepted 2026-05-18 (PR #146). On-device
1–5 green: the #8 happy path is exercised — both an existing API key and
a newly-entered one authenticate against the real Unraid server (the
`Present` → authenticate flow). Latent / not on-device-testable, covered
by CI + static review: the `Undecryptable` path (#8) cannot be triggered
on-device without corrupting the Tink keyset, so the re-enter-prompt
branch and its distinction from `Absent` (the old misleading "Missing
API key") are covered by unit tests + static review, not the device run.
Tier-3 (ADR-0027) is satisfied for the testable surface; the
undecryptable branch stays test/review-covered.

---

## Amendment — 2026-11-10 (the re-enter remedy needs an explicit key-storage reset)

The remedy text above ("re-entry calls `ApiKeyStore.put` and overwrites the bad ciphertext") did not hold for the cause this ADR names. When the Keystore master key is lost, the Tink *keyset* is what is unreadable, not just one ciphertext: `AndroidKeysetManager.build()` throws on every call, so `put` threw too and the unhandled exception in the Add/Edit save crashed the app on every retry.

**No automatic reset.** An unusable keyset or Keystore cannot be told apart reliably from a transient Keystore outage (for example, `KeyStore.containsAlias` returns false on any Keystore error, and a Keystore daemon restart fails several attempts in a row). Any heuristic risks wiping every stored key on a hiccup, so `put` never deletes key material on its own. A failing save shows an error line in the Add/Edit sheet instead of crashing, and leaves everything untouched. A failure in building the AEAD or encrypting is reported as `ApiKeyStore.KeyStorageException`.

**Remedy: explicit reset from the sheet.** For a `KeyStorageException` the sheet offers a "Reset key storage" button. After a destructive confirmation ("deletes the stored API keys of all servers") it deletes the keyset prefs entry, the Keystore master key and all stored ciphertexts, then retries the save once; a failure after the reset shows the error line again. Other servers then report "Missing API key" and are re-entered the same way.

**Hardening kept with this change.** Reset, put and every read (including the ciphertext read in `getResult`) run under one mutex. When no keyset exists yet, the master key is created explicitly; if Tink still ends up with a keyset that is not Keystore-wrapped (its silent plaintext fallback, which existed before this amendment), the new keyset entry is removed and the save fails. An existing keyset, including an old plaintext one, keeps loading. `ServerRepository.upsert` stores the key before the server list, so a failed save leaves no duplicate server, and `activeWithKey` now also combines the key store's change flow, so a key-only edit reaches the running streams even when the server list is unchanged.

Tested on the JVM through internal seams (failed save leaves other keys readable and does not reset, explicit reset then save, racing puts, reset serialised with put, unwrapped fresh keyset rejected, existing keyset kept, key-only change re-emits). Not testable off-device: real Tink, Keystore and DataStore behaviour, and the dialog flow itself.
