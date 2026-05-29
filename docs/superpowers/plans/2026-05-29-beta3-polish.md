# 0.1.39-beta3 Polish Bundle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Three small, low-risk cleanups: (1) re-testing an already-pinned local server must not re-prompt for the certificate; (2) `EndpointUrl` parse/compose robust to mixed-case schemes and to a full URL pasted into the host field; (3) remove the stale 0.1.34 "rename to NOVA" migration banner entirely.

**Architecture:** Pure fixes + a dead-code removal. No new behavior, no schema/ADR changes.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, JUnit.

**Environment:** NO local JDK/Android SDK — `./gradlew` cannot run. Skip every `./gradlew` step; CI is the first compile. Implement + review by reading; commit anyway. (See `project_no_local_toolchain`.)

---

## Task 1: `EndpointUrl` — mixed-case scheme + pasted-URL robustness (TDD)

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrl.kt`
- Modify: `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrlTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `EndpointUrlTest.kt` (inside the class):

```kotlin
    // WHY: a stored URL with a mixed-case scheme must still parse host + ssl
    // correctly (the old four-exact-prefix strip left "Https://host" as the host).
    @Test fun parse_mixedCaseScheme() {
        assertEquals(EndpointUrl("tower.local", ssl = true), EndpointUrl.parse("Https://tower.local", defaultSsl = false))
        assertEquals(EndpointUrl("192.168.11.2", ssl = false), EndpointUrl.parse("HTTP://192.168.11.2", defaultSsl = true))
    }

    // WHY: if the user pastes a full URL into the host field, compose must not
    // produce a double scheme like "https://http://host"; it strips the pasted
    // scheme (and any path) and applies the SSL toggle.
    @Test fun compose_stripsPastedSchemeAndPath() {
        assertEquals("https://192.168.11.2", EndpointUrl.compose("http://192.168.11.2", ssl = true))
        assertEquals("https://192.168.11.2:8443", EndpointUrl.compose("https://192.168.11.2:8443/graphql", ssl = true))
    }

    // WHY: save() derives hostname from the host field; a pasted URL must not
    // yield "http:" as the hostname.
    @Test fun normalizeHost_stripsSchemeAndPath() {
        assertEquals("192.168.11.2", EndpointUrl.normalizeHost("http://192.168.11.2/graphql"))
        assertEquals("tower.local", EndpointUrl.normalizeHost("  tower.local  "))
        assertEquals("", EndpointUrl.normalizeHost(""))
    }
```

- [ ] **Step 2: Run test to verify it fails** (CI). Expected: FAIL — `normalizeHost` unresolved + mixed-case/compose assertions fail.

- [ ] **Step 3: Rewrite `EndpointUrl.kt`**

Replace the companion object body:

```kotlin
data class EndpointUrl(val host: String, val ssl: Boolean) {
    companion object {
        /** Strip a leading http(s):// scheme (case-insensitive) and any path. */
        fun normalizeHost(raw: String): String = raw.trim().stripScheme().substringBefore('/')

        /** host[:port] + ssl → stored URL. Blank host → "" (endpoint unset).
         *  A scheme/path pasted into [host] is stripped so we never double up. */
        fun compose(host: String, ssl: Boolean): String {
            val h = normalizeHost(host)
            if (h.isEmpty()) return ""
            return (if (ssl) "https://" else "http://") + h
        }

        /** Stored URL → host[:port] + ssl. Blank URL takes [defaultSsl]. */
        fun parse(url: String, defaultSsl: Boolean): EndpointUrl {
            val u = url.trim()
            if (u.isEmpty()) return EndpointUrl("", defaultSsl)
            val ssl = u.lowercase().startsWith("https://")
            return EndpointUrl(u.stripScheme().substringBefore('/'), ssl)
        }

        private fun String.stripScheme(): String {
            val lower = lowercase()
            return when {
                lower.startsWith("https://") -> substring("https://".length)
                lower.startsWith("http://") -> substring("http://".length)
                else -> this
            }
        }
    }
}
```

- [ ] **Step 4: Run tests** (CI). Expected: PASS (existing 6 + 3 new = 9).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrl.kt app/src/test/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrlTest.kt
git commit -m "fix(model): EndpointUrl handles mixed-case scheme + pasted full URL"
```

---

## Task 2: Re-test must not re-prompt; hostname from a pasted URL

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt`

- [ ] **Step 1: Seed the pending pin from the stored pin on `load`**

In `AddEditServerViewModel.load(...)`, the constructed `AddEditUiState` sets `hasStoredPin` from `servers.pinFor(id)`. Add `pendingLocalCertSha256` from the same source so re-testing an already-pinned server validates against the existing pin instead of re-prompting. Change the relevant lines in `load`'s `_state.value = AddEditUiState(...)`:

```kotlin
                hasStoredPin = server?.id?.let { servers.pinFor(it) } != null,
                pendingLocalCertSha256 = server?.id?.let { servers.pinFor(it) },
                apiKey = server?.id?.let { servers.apiKeyFor(it) }.orEmpty(),
```

> Now `testLocal()` passes the stored pin → `PinnedSelfSigned(pin, acceptFirstUse=false)` → cert matches → `Ok`, no dialog. Saving rewrites the same pin (idempotent).

- [ ] **Step 2: Derive `hostname` via `normalizeHost` in `save()`**

In `save()`, replace the hostname line so a pasted full URL doesn't yield `"http:"`:

```kotlin
            val hostname = EndpointUrl.normalizeHost(s.localHost).ifBlank { EndpointUrl.normalizeHost(s.remoteHost) }
```

> `EndpointUrl.compose` already sanitizes the stored `localUrl`/`remoteUrl`; this aligns the derived `hostname`. (`EndpointUrl` is already imported in this file.)

- [ ] **Step 3: Build to verify** (skip locally — CI). Read to confirm `pendingLocalCertSha256` is a valid `AddEditUiState` field (it is) and `EndpointUrl.normalizeHost` exists (Task 1).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt
git commit -m "fix(ui): re-test reuses stored cert pin (no re-prompt); clean hostname from pasted URL"
```

---

## Task 3: Remove the stale "rename to NOVA" migration banner

The 0.1.34 rename migration aid is long past (now 0.1.39). #180 removed only the Settings-screen notice; the main-screen banner + all its plumbing remain. Remove it entirely.

**Files:**
- Delete: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/rename/RenameBanner.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/local/SettingsStore.kt`

- [ ] **Step 1: Delete the composable**

```bash
git rm app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/rename/RenameBanner.kt
```

- [ ] **Step 2: MainScreen — remove the import + render block**

Remove the import line `import io.github.nofuturekid.nova.ui.screens.rename.RenameBanner` and the render block:

```kotlin
        val renameDismissed by vm.renameBannerDismissed.collectAsState()
        if (!renameDismissed) {
            RenameBanner(onDismiss = { vm.dismissRenameBanner() })
        }
```

Delete those four lines entirely (leave the surrounding `TopBar(...)` call above and the `if (BuildConfig.HAS_UPDATER)` block below intact).

- [ ] **Step 3: MainViewModel — remove the flow + dismiss fn**

Remove:

```kotlin
    val renameBannerDismissed: StateFlow<Boolean> =
        settings.renameBannerDismissed
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

and

```kotlin
    fun dismissRenameBanner() = viewModelScope.launch {
        settings.setRenameBannerDismissed(true)
    }
```

- [ ] **Step 4: SettingsRepository — remove the passthroughs**

Remove the line `val renameBannerDismissed: Flow<Boolean> = store.renameBannerDismissed` and `suspend fun setRenameBannerDismissed(value: Boolean) = store.setRenameBannerDismissed(value)`.

- [ ] **Step 5: SettingsStore — remove the key, flow, setter**

Remove `val RenameBannerDismissed = booleanPreferencesKey("rename_banner_dismissed")` from `Keys`; the `val renameBannerDismissed: Flow<Boolean> = ...` line; and the `suspend fun setRenameBannerDismissed(...) { ... }` function.

> Leaving the persisted key orphaned in any existing DataStore is harmless (it's simply never read again). No migration needed.

- [ ] **Step 6: Verify no references remain**

Run: `grep -rn "RenameBanner\|renameBanner\|rename_banner\|setRenameBannerDismissed\|dismissRenameBanner" app/src/main/kotlin`
Expected: NO matches.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: remove stale 0.1.34 NOVA-rename migration banner + plumbing"
```

---

## Task 4: Release tail — CHANGELOG + version bump + PR (delegated)

- [ ] **Step 1: CHANGELOG** (curated, plain language, ADR-0031): a `0.1.39-beta3` entry — "Fixed: re-testing a saved local server no longer asks you to re-confirm its certificate. Removed the old 'renaming to NOVA' notice. Minor: tolerate pasting a full URL into the host field." (No ADR change — pure cleanup.)

- [ ] **Step 2: Version bump** (own commit, ADR-0015): `app/build.gradle.kts` → `versionCode = 106`, `versionName = "0.1.39-beta3"`.

- [ ] **Step 3: Push + PR** targeting `main`, title `chore: 0.1.39-beta3 — re-test cert reuse, drop rename banner, URL-paste tolerance`. Body notes: no local toolchain → CI is the first compile; watch `build`/`test`. **Report and STOP — do NOT watch CI or merge.**

---

## Self-Review

**Coverage:** (1) re-test re-prompt → Task 2 Step 1 ✓; (2) EndpointUrl mixed-case + pasted-URL → Task 1 + Task 2 Step 2 ✓; (3) rename banner removal → Task 3 (5 files) ✓.

**Placeholder scan:** none — concrete code + a grep verification with expected empty result.

**Type consistency:** `EndpointUrl.normalizeHost(raw): String` used in `compose` and `save()`; `pendingLocalCertSha256` is an existing `AddEditUiState` field (added in beta2); the rename-banner symbols are all removed together (composable, VM flow/fn, repo passthroughs, store key/flow/setter) — grep gate confirms none dangling.

**Risk:** Task 3 spans 5 files; the grep gate (Step 6) catches any missed reference before commit. Orphaned DataStore key is harmless.
