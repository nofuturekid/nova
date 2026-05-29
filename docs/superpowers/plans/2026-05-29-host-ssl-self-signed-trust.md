# Host + SSL Connection Entry & Self-Signed Local Trust (TOFU) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users enter a server as `host[:port]` + an SSL switch (both endpoints), and opt in — per server, local endpoint only — to trusting a self-signed HTTPS certificate via TOFU pinning (show fingerprint on first use, block on change).

**Architecture:** The host+SSL split is a UI-layer convenience that composes/parses to the existing stored full URLs (`localUrl`/`remoteUrl`) — **no storage migration**. Trust is a per-client custom `X509TrustManager` installed only on the active server's *local https* Apollo client, anchored by a pinned SHA-256 fingerprint. The pin lives in `ActiveServer` (so it's a synchronous snapshot on the TLS thread and part of `distinctUntilChanged`), and changing it restarts the polling streams. A dedicated `certPrompt` StateFlow drives a confirm dialog.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Apollo GraphQL, OkHttp, DataStore (Preferences + kotlinx.serialization), Hilt, JUnit.

**Spec:** `docs/superpowers/specs/2026-05-29-host-ssl-and-self-signed-trust-design.md`

---

## File Structure

**Create:**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrl.kt` — pure compose/parse between `(host, ssl)` and a stored URL string.
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/Tls.kt` — `TlsTrust` sealed type, `TrustDecision`, `decidePinnedTrust`, `sha256Hex`, typed cert exceptions, `LocalPinningTrustManager`, `certIssue()` cause-walk, `CertIssue`.
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrlTest.kt`
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TlsTrustDecisionTest.kt`

**Modify:**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Models.kt` — add `Server.trustSelfSignedLocal`.
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/local/SettingsStore.kt` — per-server cert-pin persistence.
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/ApolloClientFactory.kt` — `build(..., trust)` + trust in cache key + install pinning.
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ServerRepository.kt` — `ActiveServer.localCertPin`, resolve it, pin mutators, clear-on-delete / clear-when-flag-off.
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt` — compute `TlsTrust` at build sites, rethrow+report cert issues, `certPrompt`, `trustLocalCertificate`, `testConnection(allowSelfSigned)`.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt` — host+SSL rows, trust switch, reset-pin, compose/parse via `EndpointUrl`.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt` — expose `certPrompt`, `trustCertificate`, `dismissCertPrompt`.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt` — cert trust dialog.

---

## Phase A — Data foundation

### Task 1: Add `trustSelfSignedLocal` to `Server`

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Models.kt:5-12`

- [ ] **Step 1: Add the field with a default**

In `Models.kt`, change the `Server` data class:

```kotlin
@Serializable
data class Server(
    val id: String,
    val name: String,
    val hostname: String,
    val localUrl: String,
    val remoteUrl: String,
    val trustSelfSignedLocal: Boolean = false,
)
```

The default + `Json { ignoreUnknownKeys = true }` (already configured in `SettingsStore`) means existing persisted servers deserialize cleanly (missing field → `false`).

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Models.kt
git commit -m "feat(model): add Server.trustSelfSignedLocal flag (default off)"
```

---

### Task 2: Per-server cert-pin persistence in `SettingsStore`

The pinned SHA-256 fingerprint is stored separately from `Server` (it's runtime-captured, not user-typed), keyed by server id, as a JSON map in DataStore.

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/local/SettingsStore.kt`

- [ ] **Step 1: Add the preferences key**

In the `Keys` object, add:

```kotlin
val CertPins = stringPreferencesKey("local_cert_pins_json")
```

- [ ] **Step 2: Add read flow + accessors**

Add to the `SettingsStore` class body (near the other `servers` accessors):

```kotlin
/** server id → pinned local-cert SHA-256 (colon-separated hex). */
val certPins: Flow<Map<String, String>> = ds.data.map { prefs ->
    prefs[Keys.CertPins]?.let {
        runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
    } ?: emptyMap()
}

suspend fun pinFor(serverId: String): String? = certPins.first()[serverId]

suspend fun setPin(serverId: String, sha256: String) {
    val next = certPins.first().toMutableMap().apply { put(serverId, sha256) }
    ds.edit { it[Keys.CertPins] = json.encodeToString(next) }
}

suspend fun clearPin(serverId: String) {
    val next = certPins.first().toMutableMap().apply { remove(serverId) }
    ds.edit { it[Keys.CertPins] = json.encodeToString(next) }
}
```

Add the import if missing: `import kotlinx.coroutines.flow.first`.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/local/SettingsStore.kt
git commit -m "feat(store): persist per-server local-cert SHA-256 pins"
```

---

## Phase B — Trust mechanism (core security)

### Task 3: Pure trust-decision logic + fingerprint util + typed exceptions

This isolates the security decision as a pure function so it can be tested exhaustively without a TLS server or real certificates.

**Files:**
- Create: `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/Tls.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TlsTrustDecisionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TlsTrustDecisionTest.kt`:

```kotlin
package io.github.nofuturekid.nova.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class TlsTrustDecisionTest {

    // WHY: a CA-valid cert must always pass regardless of pin state, so a
    // properly-certified local server keeps working without a pin.
    @Test fun caValid_accepts_even_without_pin() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(defaultTrusts = true, pinned = null, presented = "AA"))
    }

    // WHY: first untrusted cert must surface its fingerprint for user confirm,
    // never silently trust (the locked TOFU decision).
    @Test fun untrusted_noPin_isFirstUse_withFingerprint() {
        assertEquals(TrustDecision.FirstUse("AA:BB"), decidePinnedTrust(false, null, "AA:BB"))
    }

    // WHY: a matching pin is the steady-state accept path.
    @Test fun untrusted_matchingPin_accepts() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(false, "AA:BB", "AA:BB"))
    }

    // WHY: matching must be case-insensitive (hex casing must not cause a
    // false "certificate changed" lockout).
    @Test fun untrusted_matchingPin_caseInsensitive_accepts() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(false, "aa:bb", "AA:BB"))
    }

    // WHY: a different cert under an existing pin is the MITM/rotation signal —
    // must block and report both fingerprints. This is the core security property.
    @Test fun untrusted_differentPin_isChanged() {
        assertEquals(TrustDecision.Changed("AA:BB", "CC:DD"), decidePinnedTrust(false, "AA:BB", "CC:DD"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TlsTrustDecisionTest"`
Expected: FAIL — `decidePinnedTrust` / `TrustDecision` unresolved.

- [ ] **Step 3: Create `Tls.kt` with the pure logic + utils + exceptions**

Create `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/Tls.kt`:

```kotlin
package io.github.nofuturekid.nova.data.api

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/** Outcome of evaluating a presented leaf certificate against an optional pin. */
sealed interface TrustDecision {
    data object Accept : TrustDecision
    data class FirstUse(val sha256: String) : TrustDecision
    data class Changed(val pinned: String, val presented: String) : TrustDecision
}

/**
 * Pure TOFU decision. [defaultTrusts] = whether the platform default trust
 * manager already accepts the chain (CA-valid). [pinned] = stored SHA-256 or
 * null. [presented] = the leaf's SHA-256. Matching is case-insensitive.
 */
fun decidePinnedTrust(defaultTrusts: Boolean, pinned: String?, presented: String): TrustDecision =
    when {
        defaultTrusts -> TrustDecision.Accept
        pinned == null -> TrustDecision.FirstUse(presented)
        pinned.equals(presented, ignoreCase = true) -> TrustDecision.Accept
        else -> TrustDecision.Changed(pinned, presented)
    }

/** Colon-separated uppercase hex SHA-256 of the certificate's DER encoding. */
fun sha256Hex(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString(":") { "%02X".format(it) }

/** Thrown on first-use of an untrusted cert (live path) so the UI can prompt. */
class CertificateUntrustedException(val sha256: String) :
    CertificateException("Self-signed certificate not yet trusted: $sha256")

/** Thrown when a pinned cert's fingerprint no longer matches. */
class CertificateChangedException(val pinned: String, val presented: String) :
    CertificateException("Pinned certificate changed")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TlsTrustDecisionTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/api/Tls.kt app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TlsTrustDecisionTest.kt
git commit -m "feat(tls): pure TOFU trust decision + fingerprint util + typed cert exceptions"
```

---

### Task 4: `LocalPinningTrustManager`, `TlsTrust`, and the cert-issue cause walk

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/Tls.kt`

- [ ] **Step 1: Append the trust-manager + supporting types to `Tls.kt`**

Add to `Tls.kt`:

```kotlin
import com.apollographql.apollo.exception.ApolloException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** How a given Apollo client should evaluate server trust. */
sealed interface TlsTrust {
    /** Stock platform validation (system CAs). */
    data object Default : TlsTrust
    /**
     * Self-signed-aware path for ONE server's local https endpoint.
     * [acceptFirstUse] = true only for the pre-save connectivity test
     * (accept-and-report, no persistence); false for the live data path
     * (throw on first use so the UI prompts and then pins).
     */
    data class PinnedSelfSigned(val pinnedSha256: String?, val acceptFirstUse: Boolean = false) : TlsTrust
}

/** The platform's default X509 trust manager (system CA store). */
fun systemDefaultTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}

/**
 * Wraps the platform default. CA-valid chains pass straight through; otherwise
 * the pin decides (see [decidePinnedTrust]). On the live path a first-use or
 * changed cert throws a typed exception that propagates up through Apollo.
 */
class LocalPinningTrustManager(
    private val default: X509TrustManager,
    private val pinnedSha256: String?,
    private val acceptFirstUse: Boolean,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val defaultTrusts = runCatching { default.checkServerTrusted(chain, authType) }.isSuccess
        val presented = sha256Hex(chain[0])
        when (val d = decidePinnedTrust(defaultTrusts, pinnedSha256, presented)) {
            is TrustDecision.Accept -> return
            is TrustDecision.FirstUse -> if (!acceptFirstUse) throw CertificateUntrustedException(d.sha256)
            is TrustDecision.Changed -> throw CertificateChangedException(d.pinned, d.presented)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        default.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers
}

/** Build an [SSLContext] whose only trust manager is [tm]. */
fun sslContextFor(tm: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }

/**
 * The ONLY place "trust is relaxed" is decided. Pure + top-level so it is
 * unit-testable and the local-only guarantee is provable: relaxed trust is
 * returned ONLY for a Local-mode, opt-in, https endpoint. Everything else —
 * remote mode, http, flag off — gets [TlsTrust.Default]. Pure (no I/O); the
 * pin is passed in.
 */
fun trustFor(
    mode: io.github.nofuturekid.nova.data.model.ConnectionMode,
    trustSelfSignedLocal: Boolean,
    url: String,
    pin: String?,
): TlsTrust =
    if (mode == io.github.nofuturekid.nova.data.model.ConnectionMode.Local &&
        trustSelfSignedLocal &&
        url.startsWith("https", ignoreCase = true)
    ) {
        TlsTrust.PinnedSelfSigned(pin)
    } else {
        TlsTrust.Default
    }

/** A cert-trust problem surfaced from a failed connection, with fingerprints. */
sealed interface CertIssue {
    data class Untrusted(val sha256: String) : CertIssue
    data class Changed(val pinned: String, val presented: String) : CertIssue
}

/** Walk a throwable's cause chain for a typed cert exception. */
fun Throwable.certIssue(): CertIssue? {
    var t: Throwable? = this
    while (t != null) {
        when (t) {
            is CertificateUntrustedException -> return CertIssue.Untrusted(t.sha256)
            is CertificateChangedException -> return CertIssue.Changed(t.pinned, t.presented)
        }
        t = t.cause
    }
    return null
}
```

> **ADR-0041 note:** the pinned client also uses a permissive `hostnameVerifier` (installed in Task 5). A self-signed LAN cert's CN/SAN rarely matches the IP/host, so hostname matching cannot be the anchor — the pinned fingerprint is. The verifier is safe because each pinned client is single-purpose (one server's local endpoint) and the `TrustManager` already gates on the exact cert.

- [ ] **Step 2: Write the scope-isolation test for `trustFor`**

Append to `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TlsTrustDecisionTest.kt`:

```kotlin
    // WHY: the entire "only for local" guarantee lives in trustFor. Relaxed
    // trust must appear ONLY for local + opt-in + https; never remote, http,
    // or flag-off (otherwise self-signed trust could leak to the remote or
    // GitHub-update path).
    @Test fun trustFor_local_https_optIn_isPinned() {
        val t = trustFor(ConnectionMode.Local, trustSelfSignedLocal = true, url = "https://192.168.11.2", pin = "AA")
        assertEquals(TlsTrust.PinnedSelfSigned("AA"), t)
    }
    @Test fun trustFor_remote_isAlwaysDefault() {
        assertEquals(TlsTrust.Default, trustFor(ConnectionMode.Remote, true, "https://x.unraid.net", "AA"))
    }
    @Test fun trustFor_local_http_isDefault() {
        assertEquals(TlsTrust.Default, trustFor(ConnectionMode.Local, true, "http://192.168.11.2", null))
    }
    @Test fun trustFor_flagOff_isDefault() {
        assertEquals(TlsTrust.Default, trustFor(ConnectionMode.Local, false, "https://192.168.11.2", "AA"))
    }
```

Add the import at the top of the test file: `import io.github.nofuturekid.nova.data.model.ConnectionMode`.

- [ ] **Step 3: Run the test to verify it fails then (after Step 1's `trustFor` exists) passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TlsTrustDecisionTest"`
Expected: PASS (9 tests total: 5 decision + 4 scope).

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/api/Tls.kt app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TlsTrustDecisionTest.kt
git commit -m "feat(tls): LocalPinningTrustManager, TlsTrust, trustFor (local-only, tested)"
```

---

### Task 5: Install pinning in `ApolloClientFactory`

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/ApolloClientFactory.kt`

- [ ] **Step 1: Add the `trust` parameter to `build`/`buildLongRunning`**

Replace the two public builders (lines 72-79):

```kotlin
/** Build a per-server client. URL must already be the base address (no trailing /graphql). */
fun build(baseUrl: String, apiKey: String, trust: TlsTrust = TlsTrust.Default): ApolloClient =
    cached("short", baseHttpClient, baseUrl, apiKey, trust)

/** Same as [build] but uses the long-running OkHttp client (10-min read/write). */
fun buildLongRunning(baseUrl: String, apiKey: String, trust: TlsTrust = TlsTrust.Default): ApolloClient =
    cached("long", longRunningHttpClient, baseUrl, apiKey, trust)
```

- [ ] **Step 2: Thread `trust` through `cached` (incl. cache key) and `buildOn`**

Replace `cached` (lines 81-93) and `buildOn` (lines 95-109):

```kotlin
private fun cached(
    variant: String,
    base: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    trust: TlsTrust,
): ApolloClient {
    val endpoint = baseUrl.trimEnd('/') + "/graphql"
    // Trust is part of the key: a Default and a PinnedSelfSigned client for the
    // same endpoint must not collide, and changing the pin must yield a fresh
    // client (so the new fingerprint snapshot takes effect). Old entries are
    // bounded and harmless (ADR-0028 lifecycle ownership).
    val trustKey = when (trust) {
        TlsTrust.Default -> "d"
        is TlsTrust.PinnedSelfSigned -> "p:${trust.pinnedSha256 ?: "none"}:${trust.acceptFirstUse}"
    }
    return clients.computeIfAbsent("$variant|$endpoint|$apiKey|$trustKey") {
        buildOn(base, endpoint, apiKey, trust)
    }
}

private fun buildOn(base: OkHttpClient, endpoint: String, apiKey: String, trust: TlsTrust): ApolloClient {
    val keyed = base.newBuilder()
        .apply {
            if (trust is TlsTrust.PinnedSelfSigned) {
                val tm = LocalPinningTrustManager(
                    default = systemDefaultTrustManager(),
                    pinnedSha256 = trust.pinnedSha256,
                    acceptFirstUse = trust.acceptFirstUse,
                )
                sslSocketFactory(sslContextFor(tm).socketFactory, tm)
                // Pin is the anchor; this client only ever talks to one server's
                // local endpoint (see ADR-0041). Self-signed LAN certs rarely
                // match the host, so hostname matching is intentionally bypassed.
                hostnameVerifier { _, _ -> true }
            }
        }
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("x-api-key", apiKey)
                .addHeader("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()
    return ApolloClient.Builder()
        .serverUrl(endpoint)
        .okHttpClient(keyed)
        .build()
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/api/ApolloClientFactory.kt
git commit -m "feat(api): install LocalPinningTrustManager on pinned per-server clients"
```

---

## Phase C — Repository wiring

### Task 6: `ServerRepository` — pin in `ActiveServer`, mutators, cleanup

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ServerRepository.kt`

- [ ] **Step 1: Add `localCertPin` to `ActiveServer`**

Update the `ActiveServer` data class (around lines 16-26):

```kotlin
data class ActiveServer(
    val server: Server,
    val mode: ConnectionMode,
    val apiKey: String,
    val keyState: ApiKeyResult,
    val localCertPin: String? = null,
)
```

- [ ] **Step 2: Resolve the pin in `activeWithKey`**

In the `activeWithKey` combine (around lines 41-50), read the pin alongside the key. Replace the mapping body so it also fetches `store.pinFor(it.id)`:

```kotlin
val activeWithKey: Flow<ActiveServer?> =
    activeServer.map { server ->
        server?.let {
            val result = keys.getResult(it.id)
            ActiveServer(
                server = it,
                mode = connectionModeValue(),
                apiKey = (result as? ApiKeyResult.Ok)?.key.orEmpty(),
                keyState = result,
                localCertPin = store.pinFor(it.id),
            )
        }
    }
```

> Adapt to the existing combine shape — the only change is adding `localCertPin = store.pinFor(it.id)`. `activeWithKey` is `distinctUntilChanged` downstream (in `UnraidRepository.domainStream`), so a pin change re-triggers the polling streams.

- [ ] **Step 3: Add pin mutators + cleanup**

Add to `ServerRepository`:

```kotlin
suspend fun setLocalCertPin(serverId: String, sha256: String) = store.setPin(serverId, sha256)
suspend fun clearLocalCertPin(serverId: String) = store.clearPin(serverId)
suspend fun pinFor(serverId: String): String? = store.pinFor(serverId)
```

In `delete(id)` (around lines 66-73), after removing the server, also clear its pin:

```kotlin
store.clearPin(id)
```

In `upsert(...)` (around lines 54-64), when the incoming server has `trustSelfSignedLocal == false`, drop any stale pin so toggling the switch off forgets the cert:

```kotlin
if (!resolved.trustSelfSignedLocal) store.clearPin(resolved.id)
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ServerRepository.kt
git commit -m "feat(repo): carry local-cert pin in ActiveServer; mutate + clean up pins"
```

---

### Task 7: `UnraidRepository` — compute trust, surface cert issues, test flag

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt`

- [ ] **Step 1: Add the cert-prompt state + trust computation helper**

Add imports:

```kotlin
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.api.certIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Add to the class body (near `activeBaseUrl`):

```kotlin
/** A pending certificate-trust decision for the active server, or null. */
data class CertPrompt(
    val serverId: String,
    val serverName: String,
    val presentedSha256: String,
    val previousSha256: String?,
)

private val _certPrompt = MutableStateFlow<CertPrompt?>(null)
val certPrompt: StateFlow<CertPrompt?> = _certPrompt.asStateFlow()

/** Local-only trust selection delegates to the pure top-level [trustFor]. */
private fun trustFor(active: ActiveServer, url: String): TlsTrust =
    io.github.nofuturekid.nova.data.api.trustFor(
        mode = active.mode,
        trustSelfSignedLocal = active.server.trustSelfSignedLocal,
        url = url,
        pin = active.localCertPin,
    )
```

> Add `import io.github.nofuturekid.nova.data.api.trustFor` (or fully-qualify as above). The local-only guarantee is proven by the Task 4 scope tests on the pure function.

- [ ] **Step 2: Pass trust at every `build` site**

- In `domainStream` (line ~313-314):

```kotlin
val url = activeUrl(active)
val client = apolloFactory.build(url, active.apiKey, trustFor(active, url))
```

- In `refreshAll` (line ~352-353):

```kotlin
val url = activeUrl(active)
val client = apolloFactory.build(url, active.apiKey, trustFor(active, url))
```

- In `buildClient` (line ~368-369):

```kotlin
private fun buildClient(active: ActiveServer): ApolloClient {
    val url = activeUrl(active)
    return apolloFactory.build(url, active.apiKey, trustFor(active, url))
}
```

- [ ] **Step 3: Rethrow cert issues from `fetch` so `domainStream` can report them**

In `fetch` (lines ~334-338), inspect the cause chain before flattening to `Error`:

```kotlin
} catch (e: ApolloException) {
    e.certIssue()?.let { throw e }   // let domainStream map it with server context
    DomainState.Error(e.message ?: "Network error")
} catch (e: Exception) {
    e.certIssue()?.let { throw e }
    DomainState.Error(e.message ?: "Unexpected error")
}
```

- [ ] **Step 4: Catch + report cert issues in `domainStream` (has `active`)**

Wrap the poll call in the `domainStream` flow block (line ~313-315):

```kotlin
val url = activeUrl(active)
val client = apolloFactory.build(url, active.apiKey, trustFor(active, url))
try {
    pollDomain(intervalMs, nudge) { fetch(client, url) }
} catch (e: Throwable) {
    val issue = e.certIssue()
    if (issue != null) {
        _certPrompt.value = when (issue) {
            is CertIssue.Untrusted -> CertPrompt(active.server.id, active.server.name, issue.sha256, null)
            is CertIssue.Changed -> CertPrompt(active.server.id, active.server.name, issue.presented, issue.pinned)
        }
        emit(DomainState.Error("Certificate not trusted — confirm it to connect"))
    } else {
        throw e
    }
}
```

> When the user trusts the cert (Step 6), the pin changes → `activeWithKey` emits a new `ActiveServer` → `flatMapLatest` restarts this stream with the new pin → the client accepts.

- [ ] **Step 5: `testConnection` gains `allowSelfSigned`**

Replace `testConnection` (lines ~502-511):

```kotlin
suspend fun testConnection(
    baseUrl: String,
    apiKey: String,
    allowSelfSigned: Boolean = false,
): String? = try {
    val trust = if (allowSelfSigned && baseUrl.startsWith("https", ignoreCase = true)) {
        // Pre-save reachability probe: accept-and-report (no pin yet); the real
        // pin is captured on first live connect via the confirm dialog.
        TlsTrust.PinnedSelfSigned(pinnedSha256 = null, acceptFirstUse = true)
    } else {
        TlsTrust.Default
    }
    val resp = apolloFactory.build(baseUrl, apiKey, trust).query(GetServerInfoQuery()).execute()
    if (resp.hasErrors()) resp.errors?.joinToString { it.message } ?: "Unknown GraphQL error" else null
} catch (e: ApolloException) { e.message ?: "Network error" }
  catch (e: Exception) { e.message ?: "Unknown error" }
```

> Keep the original query/response logic of the existing `testConnection` body; only the trust selection and `.build(...)` call are new. Verify against the real lines at implementation time.

- [ ] **Step 6: Add `trustLocalCertificate` + dismiss**

```kotlin
/** Persist the presented cert as this server's pin and clear the prompt. The
 *  pin change restarts the polling streams (via activeWithKey). */
suspend fun trustLocalCertificate(serverId: String, sha256: String) {
    servers.setLocalCertPin(serverId, sha256)
    _certPrompt.value = null
}

fun dismissCertPrompt() { _certPrompt.value = null }
```

- [ ] **Step 7: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt
git commit -m "feat(repo): local-only TOFU trust path, cert-prompt surfacing, test flag"
```

---

## Phase D — UI

### Task 8: Host + SSL split, trust switch, reset-pin (with compose/parse unit tests)

**Files:**
- Create: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrl.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrlTest.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt`

- [ ] **Step 1: Write the failing compose/parse test**

Create `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrlTest.kt`:

```kotlin
package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointUrlTest {

    // WHY: an empty host must stay an unset endpoint (parity with old blank URL).
    @Test fun compose_blankHost_isEmpty() {
        assertEquals("", EndpointUrl.compose("", ssl = true))
        assertEquals("", EndpointUrl.compose("   ", ssl = false))
    }

    @Test fun compose_http_and_https() {
        assertEquals("http://192.168.11.2", EndpointUrl.compose("192.168.11.2", ssl = false))
        assertEquals("https://192.168.11.2", EndpointUrl.compose("192.168.11.2", ssl = true))
    }

    // WHY: a custom port typed into the host must survive composition.
    @Test fun compose_keepsPort() {
        assertEquals("https://192.168.11.2:8443", EndpointUrl.compose("192.168.11.2:8443", ssl = true))
    }

    // WHY: editing an existing server must round-trip the stored URL back into
    // host + toggle without rewriting it.
    @Test fun parse_roundTrips_storedUrls() {
        assertEquals(EndpointUrl("192.168.11.2", ssl = false), EndpointUrl.parse("http://192.168.11.2", defaultSsl = true))
        assertEquals(EndpointUrl("unraid-01.kroll-home.de", ssl = true), EndpointUrl.parse("https://unraid-01.kroll-home.de", defaultSsl = false))
        assertEquals(EndpointUrl("192.168.11.2:8443", ssl = true), EndpointUrl.parse("https://192.168.11.2:8443", defaultSsl = false))
    }

    // WHY: a blank stored URL takes the per-endpoint default SSL state.
    @Test fun parse_blank_usesDefault() {
        assertEquals(EndpointUrl("", ssl = true), EndpointUrl.parse("", defaultSsl = true))
        assertEquals(EndpointUrl("", ssl = false), EndpointUrl.parse("", defaultSsl = false))
    }

    // WHY: legacy URLs with a trailing path must not leak the path into the host.
    @Test fun parse_stripsTrailingPath() {
        assertEquals(EndpointUrl("192.168.11.2", ssl = false), EndpointUrl.parse("http://192.168.11.2/", defaultSsl = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.EndpointUrlTest"`
Expected: FAIL — `EndpointUrl` unresolved.

- [ ] **Step 3: Create `EndpointUrl.kt`**

Create `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrl.kt`:

```kotlin
package io.github.nofuturekid.nova.data.model

/** A connection endpoint expressed as host[:port] + an SSL (https) toggle. */
data class EndpointUrl(val host: String, val ssl: Boolean) {
    companion object {
        /** host[:port] + ssl → stored URL. Blank host → "" (endpoint unset). */
        fun compose(host: String, ssl: Boolean): String {
            val h = host.trim()
            if (h.isEmpty()) return ""
            return (if (ssl) "https://" else "http://") + h
        }

        /** Stored URL → host[:port] + ssl. Blank URL takes [defaultSsl]. */
        fun parse(url: String, defaultSsl: Boolean): EndpointUrl {
            val u = url.trim()
            if (u.isEmpty()) return EndpointUrl("", defaultSsl)
            val ssl = u.startsWith("https://", ignoreCase = true)
            val host = u
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("HTTPS://").removePrefix("HTTP://")
                .substringBefore('/')
            return EndpointUrl(host, ssl)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.EndpointUrlTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Rework `AddEditUiState` + VM to host/ssl/trust**

In `AddEditServerSheet.kt`, replace `AddEditUiState` (lines 52-60):

```kotlin
data class AddEditUiState(
    val name: String = "",
    val localHost: String = "",
    val localSsl: Boolean = false,
    val remoteHost: String = "",
    val remoteSsl: Boolean = true,
    val trustSelfSignedLocal: Boolean = false,
    val hasStoredPin: Boolean = false,
    val apiKey: String = "",
    val showKey: Boolean = false,
    val testState: TestState = TestState.Idle,
    val testMessage: String? = null,
)
```

In `load` (lines 73-89), parse the stored URLs:

```kotlin
fun load(server: Server?) {
    existingId = server?.id ?: ""
    viewModelScope.launch {
        val local = EndpointUrl.parse(server?.localUrl.orEmpty(), defaultSsl = false)
        val remote = EndpointUrl.parse(server?.remoteUrl.orEmpty(), defaultSsl = true)
        _state.value = AddEditUiState(
            name = server?.name.orEmpty(),
            localHost = local.host,
            localSsl = local.ssl,
            remoteHost = remote.host,
            remoteSsl = remote.ssl,
            trustSelfSignedLocal = server?.trustSelfSignedLocal ?: false,
            hasStoredPin = server?.id?.let { servers.pinFor(it) } != null,
            apiKey = server?.id?.let { servers.apiKeyFor(it) }.orEmpty(),
        )
    }
}
```

Replace the field setters (lines 91-95) and add the new ones:

```kotlin
fun setName(v: String)        { _state.value = _state.value.copy(name = v, testState = TestState.Idle) }
fun setLocalHost(v: String)   { _state.value = _state.value.copy(localHost = v, testState = TestState.Idle) }
fun setLocalSsl(v: Boolean)   { _state.value = _state.value.copy(localSsl = v, testState = TestState.Idle) }
fun setRemoteHost(v: String)  { _state.value = _state.value.copy(remoteHost = v, testState = TestState.Idle) }
fun setRemoteSsl(v: Boolean)  { _state.value = _state.value.copy(remoteSsl = v, testState = TestState.Idle) }
fun setTrustSelfSigned(v: Boolean) {
    // Turning trust off forgets any captured cert on save (handled in upsert);
    // here just flip the intent.
    _state.value = _state.value.copy(trustSelfSignedLocal = v, testState = TestState.Idle)
}
fun setApiKey(v: String)      { _state.value = _state.value.copy(apiKey = v, testState = TestState.Idle) }
fun toggleKeyVisible()        { _state.value = _state.value.copy(showKey = !_state.value.showKey) }

/** Forget the captured certificate for this server (deliberate rotation).
 *  The next local connect re-runs first-use and re-prompts. */
fun resetCertificate() {
    val id = existingId
    if (id.isBlank()) return
    viewModelScope.launch {
        servers.clearLocalCertPin(id)
        _state.value = _state.value.copy(hasStoredPin = false)
    }
}
```

- [ ] **Step 6: Compose URLs in `test()` and `save()`**

In `test()` (lines 97-113), compose the local URL (prefer local, fall back to remote) and pass `allowSelfSigned`:

```kotlin
fun test() {
    val s = _state.value
    val localUrl = EndpointUrl.compose(s.localHost, s.localSsl)
    val remoteUrl = EndpointUrl.compose(s.remoteHost, s.remoteSsl)
    if (localUrl.isBlank() && remoteUrl.isBlank()) return
    if (s.apiKey.isBlank() || s.apiKey.all { it == '•' }) {
        _state.value = s.copy(testState = TestState.Fail, testMessage = "Enter the API key first")
        return
    }
    _state.value = s.copy(testState = TestState.Testing, testMessage = null)
    viewModelScope.launch {
        val url = localUrl.ifBlank { remoteUrl }
        val allowSelfSigned = s.trustSelfSignedLocal && url == localUrl
        val err = unraid.testConnection(url, s.apiKey, allowSelfSigned)
        _state.value = _state.value.copy(
            testState = if (err == null) TestState.Ok else TestState.Fail,
            testMessage = err,
        )
    }
}
```

In `save()` (lines 115-135):

```kotlin
fun save(onDone: () -> Unit) {
    val s = _state.value
    if (s.name.isBlank()) return
    viewModelScope.launch {
        val localUrl = EndpointUrl.compose(s.localHost, s.localSsl)
        val remoteUrl = EndpointUrl.compose(s.remoteHost, s.remoteSsl)
        val hostname = s.localHost.substringBefore('/').ifBlank { s.remoteHost }
        servers.upsert(
            Server(
                id = existingId,
                name = s.name.trim(),
                hostname = hostname,
                localUrl = localUrl,
                remoteUrl = remoteUrl,
                trustSelfSignedLocal = s.trustSelfSignedLocal,
            ),
            apiKey = s.apiKey,
        )
        onDone()
    }
}
```

- [ ] **Step 7: Update the composable fields (host + SSL switch + trust switch)**

In the `AddEditServerSheet` composable, replace the two URL `UnraidField`s (lines 204-221) with host fields + SSL switch rows, and add the trust switch shown only when `state.localSsl`. Use Material 3 `Switch` and the existing `UnraidField`/theme. Example for the local block (mirror for remote, without the trust switch):

```kotlin
UnraidField(
    label = "Local host",
    value = state.localHost,
    onChange = vm::setLocalHost,
    placeholder = "192.168.11.2",
    leadingIcon = { UC.Wifi(18.dp, t.muted) },
    helper = "Used on home network · add :port if non-default",
    keyboardType = KeyboardType.Uri,
)
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text("Use SSL (HTTPS)", color = t.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    androidx.compose.material3.Switch(checked = state.localSsl, onCheckedChange = vm::setLocalSsl)
}
if (state.localSsl) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Trust self-signed certificate", color = t.text, style = MaterialTheme.typography.bodyMedium)
            Text("Local connection only · you confirm the certificate once", color = t.muted, style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.Switch(checked = state.trustSelfSignedLocal, onCheckedChange = vm::setTrustSelfSigned)
    }
    if (state.trustSelfSignedLocal && state.hasStoredPin) {
        UnraidButton(
            onClick = vm::resetCertificate,
            label = "Reset certificate",
            variant = BtnVariant.Text,
            tone = Tone.Neutral,
            leadingIcon = { UC.Refresh(14.dp, t.muted) },
        )
    }
}
```

> The Reset button appears only when editing a server that has a stored pin and the trust switch is on. It forgets the captured cert so the next local connect re-prompts (deliberate cert rotation). Match the file's button/spacing conventions.

Update the `canSave` guard (lines 264-266) to use the host fields:

```kotlin
val canSave = state.name.isNotBlank() &&
    (state.localHost.isNotBlank() || state.remoteHost.isNotBlank()) &&
    state.apiKey.isNotBlank()
```

> Match surrounding spacing/typography conventions in the file (it uses `UnraidTheme.colors`, `MaterialTheme.typography`, `Spacer(Modifier.height(...))`). Keep the remote block symmetrical (host + SSL switch, no trust switch).

- [ ] **Step 8: Build + run the unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*.EndpointUrlTest" && ./gradlew :app:compileDebugKotlin`
Expected: tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrl.kt app/src/test/kotlin/io/github/nofuturekid/nova/data/model/EndpointUrlTest.kt app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt
git commit -m "feat(ui): host + SSL-switch entry, trust-self-signed switch (local)"
```

---

### Task 9: Cert-trust confirm dialog (MainViewModel + MainScreen)

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt`

- [ ] **Step 1: Expose the prompt + actions in `MainViewModel`**

Add (near the other repo-backed flows; `repo`/`unraid` is the `UnraidRepository` injected into this VM — match the existing field name):

```kotlin
val certPrompt: StateFlow<UnraidRepository.CertPrompt?> =
    unraid.certPrompt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

fun trustCertificate(serverId: String, sha256: String) = viewModelScope.launch {
    unraid.trustLocalCertificate(serverId, sha256)
}
fun dismissCertPrompt() = unraid.dismissCertPrompt()
```

Add imports if missing: `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.stateIn`.

- [ ] **Step 2: Show the dialog in `MainScreen`**

Collect the prompt and render an `AlertDialog` (Material 3) when non-null. Place it alongside the existing dialog/confirm handling in `MainScreen`:

```kotlin
val certPrompt by viewModel.certPrompt.collectAsState()
certPrompt?.let { p ->
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { viewModel.dismissCertPrompt() },
        title = { Text(if (p.previousSha256 == null) "Trust this certificate?" else "Certificate changed") },
        text = {
            Column {
                Text(
                    if (p.previousSha256 == null)
                        "${p.serverName} presented a self-signed certificate. Trust it for the local connection?"
                    else
                        "${p.serverName}'s certificate changed. Only trust this if you changed it yourself.",
                )
                Spacer(Modifier.height(8.dp))
                if (p.previousSha256 != null) {
                    Text("Was: ${p.previousSha256}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                }
                Text("SHA-256: ${p.presentedSha256}", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { viewModel.trustCertificate(p.serverId, p.presentedSha256) }) {
                Text(if (p.previousSha256 == null) "Trust" else "Trust new")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { viewModel.dismissCertPrompt() }) { Text("Cancel") }
        },
    )
}
```

> Match the screen's existing imports/composable style. If `MainScreen` already wraps dialogs in a themed component (e.g. the notifications `ConfirmRequest` pattern), prefer that for visual consistency (Rule 13) over a raw `AlertDialog`.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt
git commit -m "feat(ui): certificate-trust confirm dialog (first-use + changed)"
```

---

## Phase E — Verify, document, release (delegated tail — Rule 14)

### Task 10: Full local CI gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full local check**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected: all unit tests PASS (incl. `TlsTrustDecisionTest`, `EndpointUrlTest`), lint clean, BUILD SUCCESSFUL.

- [ ] **Step 2: If anything fails, fix it before proceeding.** Do not skip or `xfail` tests (Rule 12).

---

### Task 11: ADR-0041 + CHANGELOG + version bump + PR (sub-agent)

**Files:**
- Create: `docs/adr/0041-host-ssl-entry-and-self-signed-local-trust.md`
- Modify: `CHANGELOG.md`, `app/build.gradle.kts` (versionName/versionCode bump to next beta)

- [ ] **Step 1: Write ADR-0041** — context (LAN self-signed problem + clumsy URL entry), decisions (host+SSL split with no storage migration; TOFU pinning; local-endpoint-only scope; permissive hostname verifier on the pinned single-purpose client and why it's safe), rejected alternatives (`CertificatePinner`, `network_security_config` anchor), consequences. Follow `docs/adr/template.md`.

- [ ] **Step 2: CHANGELOG** (curated, ADR-0031, plain language): "Enter just a host and flip an SSL switch instead of a full URL. Connect to a local server that uses a self-signed HTTPS certificate — you confirm the certificate once."

- [ ] **Step 3: Version bump** — own commit (ADR-0015), `-beta1` suffix (ADR-0013), next patch version after 0.1.38.

- [ ] **Step 4: Branch already exists** (`feat/host-ssl-self-signed-trust`). Push and open the PR (squash-merge target `main`, ADR-0014). **Report and STOP — do NOT watch CI or merge** (sub-agents are single-shot; the main thread owns watch+merge — see `feedback_ci_watch_merge`).

---

## Self-Review

**Spec coverage:**
- Host + SSL switch, both endpoints → Task 8 ✓
- Optional `:port` in host → `EndpointUrl` + test ✓
- Storage unchanged / no migration → `EndpointUrl.compose`/`parse`, `Server` only gains a bool → Tasks 1, 8 ✓
- New-server defaults (local http, remote https) → `load`/`AddEditUiState` defaults → Task 8 ✓
- `Server.trustSelfSignedLocal` + separate pin store → Tasks 1, 2 ✓
- TOFU trust manager, local-https-only, remote always full validation → Tasks 4, 5, 7 (`trustFor`) ✓
- First-use shows fingerprint + confirm; changed blocks + re-accept → Tasks 4, 7, 9 ✓
- New connection states surfaced (not generic Offline) → `certPrompt` flow → Tasks 7, 9 ✓
- Reset/clear pin (deliberate rotation) → clear-on-flag-off in `upsert` + Trust-new on change dialog → Tasks 6, 9 (a dedicated in-sheet "Reset certificate" button was descoped to the simpler flag-off-clears + change-dialog re-accept; noted below) 
- Tests: compose/parse round-trip, trust decision matrix, scope isolation → Tasks 3, 8 + scope note below
- ADR-0041 + CHANGELOG → Task 11 ✓

**Deviations from spec — both RESOLVED per maintainer (2026-05-29):**
1. **"Reset certificate" affordance** — KEPT as the spec intended. Explicit Reset button added to Task 8 (`resetCertificate()` + `hasStoredPin` state), shown when editing a server that has a stored pin with the trust switch on. Flag-off-clears (Task 6 `upsert`) and change-dialog re-accept (Task 9) remain as complementary paths.
2. **Scope-isolation test** — DONE. `trustFor` extracted to a pure top-level function in `Tls.kt` (Task 4) with signature `fun trustFor(mode: ConnectionMode, trustSelfSignedLocal: Boolean, url: String, pin: String?): TlsTrust`, unit-tested for the matrix (local+https+flag→Pinned; remote→Default; local+http→Default; flag-off→Default). `UnraidRepository` delegates to it (Task 7).

**Placeholder scan:** none — every code step has concrete code; commands have expected output.

**Type consistency:** `decidePinnedTrust`, `TrustDecision`, `sha256Hex`, `CertificateUntrustedException`/`CertificateChangedException`, `CertIssue`, `TlsTrust.PinnedSelfSigned(pinnedSha256, acceptFirstUse)`, `certIssue()`, `ActiveServer.localCertPin`, `CertPrompt`, `trustFor`, `testConnection(allowSelfSigned)` — names are consistent across Tasks 3→9.

---

## On-device acceptance (maintainer gate — out of plan scope)

Per ADR-0027 Tier 3 + `live-graphql-validation`: the maintainer verifies on-device against the live self-signed `https://192.168.11.2` — first-use prompt shows the real fingerprint, Trust pins it, data loads, reconnect stays trusted, and (optionally) a cert change triggers the re-accept dialog. This is the device gate; promote-to-stable stays maintainer-only.
