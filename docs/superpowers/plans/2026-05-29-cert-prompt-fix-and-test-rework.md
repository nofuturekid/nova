# Cert-Prompt Fix (Apollo 5) + Per-Endpoint Test Rework — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the self-signed certificate trust dialog actually appear — on the overview's first live connect (root-cause fix) AND at a per-endpoint "Test" tap — by honoring Apollo Kotlin 5's `ApolloResponse.exception` (which `execute()` no longer throws), and reworking the single shared Test into a Local + Remote test where the Local test surfaces the fingerprint and pins on save.

**Architecture:** A pure `classifyResponse(...)` function turns an Apollo response (`exception` / `hasErrors` / `data==null`) into a `RespClass` (Cert/Failed/Empty/Ok); `fetch` rethrows the cert case so `domainStream` surfaces the overview prompt, and `testConnection` returns a structured `TestOutcome` the sheet uses for an in-sheet cert dialog. The captured fingerprint is held in the Add/Edit VM and written to the pin store on save. Both paths write the same per-server pin, so they never double-prompt.

**Tech Stack:** Kotlin, Apollo Kotlin 5 (`com.apollographql.apollo`), Jetpack Compose (Material 3), JUnit.

**Spec:** `docs/superpowers/specs/2026-05-29-host-ssl-and-self-signed-trust-design.md` (see the "Amendment — 2026-05-29 PM" section).

**Environment:** NO local JDK/Android SDK — `./gradlew` cannot run. Skip every `./gradlew` step; CI is the first compile. Implement + review by reading; commit anyway. (See `project_no_local_toolchain`.)

---

## File Structure

**Create:**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifier.kt` — pure `RespClass` + `classifyResponse(...)` + public `TestOutcome` (one responsibility: classify an Apollo response).
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifierTest.kt`

**Modify:**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt` — `fetch` uses the classifier; `testConnection` returns `TestOutcome`, inspects `resp.exception`, drops `acceptFirstUse=true`.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt` — per-endpoint test state, `pendingLocalCertSha256`, in-sheet cert dialog, pin-on-save, two test panels.

---

## Task 1: Pure response classifier + `TestOutcome` (TDD)

**Files:**
- Create: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifier.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifierTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifierTest.kt`:

```kotlin
package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.CertificateChangedException
import io.github.nofuturekid.nova.data.api.CertificateUntrustedException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ResponseClassifierTest {

    // WHY: Apollo Kotlin 5 returns TLS failures in response.exception (it no
    // longer throws). A self-signed cert must be recognised as a Cert case —
    // even when our typed exception is nested under the platform exception —
    // so the prompt can be surfaced. This is the exact bug that shipped in beta1.
    @Test fun exception_withUntrustedCertInCauseChain_isCert() {
        val ex = ApolloNetworkException(
            message = "TLS failed",
            platformCause = IOException("handshake", CertificateUntrustedException("AA:BB")),
        )
        assertEquals(
            RespClass.Cert(CertIssue.Untrusted("AA:BB")),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    @Test fun exception_withChangedCert_isCert() {
        val ex = ApolloNetworkException("TLS", platformCause = CertificateChangedException("OLD", "NEW"))
        assertEquals(
            RespClass.Cert(CertIssue.Changed("OLD", "NEW")),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    // WHY: a non-cert network error must surface as a real failure message,
    // not be masked as "Empty GraphQL response" (the beta1 misclassification).
    @Test fun exception_nonCert_isFailedWithMessage() {
        val ex = ApolloNetworkException("Unable to resolve host")
        assertEquals(
            RespClass.Failed("Unable to resolve host"),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    // WHY: GraphQL-body errors (e.g. bad query) are a failure, distinct from transport.
    @Test fun graphqlErrors_isFailed() {
        assertEquals(
            RespClass.Failed("bad field"),
            classifyResponse(exception = null, hasErrors = true, errorMessage = "bad field", dataIsNull = false),
        )
    }

    // WHY: no exception, no errors, but null data → genuinely empty response.
    @Test fun noExceptionNullData_isEmpty() {
        assertEquals(RespClass.Empty, classifyResponse(null, hasErrors = false, errorMessage = null, dataIsNull = true))
    }

    @Test fun data_isOk() {
        assertEquals(RespClass.Ok, classifyResponse(null, hasErrors = false, errorMessage = null, dataIsNull = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails** (skip locally — no toolchain; CI runs it). Expected in CI: FAIL — `classifyResponse`/`RespClass`/`TestOutcome` unresolved.

- [ ] **Step 3: Create `ResponseClassifier.kt`**

Create `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifier.kt`:

```kotlin
package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.exception.ApolloException
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.certIssue

/**
 * Classification of an Apollo response. Apollo Kotlin 5's `execute()` does NOT
 * throw on network/TLS errors — it returns them in `ApolloResponse.exception`
 * (with `data == null`), and `hasErrors()` reflects only GraphQL-body errors.
 * This pure function centralises that handling so both [UnraidRepository.fetch]
 * and [UnraidRepository.testConnection] surface a self-signed cert correctly.
 */
sealed interface RespClass {
    data class Cert(val issue: CertIssue) : RespClass
    data class Failed(val message: String) : RespClass
    data object Empty : RespClass
    data object Ok : RespClass
}

/**
 * @param exception   ApolloResponse.exception (null when no transport error)
 * @param hasErrors   ApolloResponse.hasErrors() (GraphQL-body errors)
 * @param errorMessage joined GraphQL error messages, if any
 * @param dataIsNull  ApolloResponse.data == null
 */
fun classifyResponse(
    exception: ApolloException?,
    hasErrors: Boolean,
    errorMessage: String?,
    dataIsNull: Boolean,
): RespClass = when {
    exception != null -> {
        val issue = exception.certIssue()
        if (issue != null) RespClass.Cert(issue) else RespClass.Failed(exception.message ?: "Network error")
    }
    hasErrors -> RespClass.Failed(errorMessage ?: "Unknown GraphQL error")
    dataIsNull -> RespClass.Empty
    else -> RespClass.Ok
}

/** Structured result of a connection test (consumed by the Add/Edit sheet). */
sealed interface TestOutcome {
    data object Ok : TestOutcome
    data class Failed(val message: String) : TestOutcome
    data class CertUntrusted(val sha256: String) : TestOutcome
    data class CertChanged(val pinned: String, val presented: String) : TestOutcome
}
```

- [ ] **Step 4: Run test to verify it passes** (CI). Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifier.kt app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/ResponseClassifierTest.kt
git commit -m "feat(repo): pure Apollo-5 response classifier + TestOutcome (root-cause of missing cert prompt)"
```

---

## Task 2: Wire the classifier into `fetch` and `testConnection`

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt`

- [ ] **Step 1: Rewrite `fetch` to honor `resp.exception` via the classifier**

Replace the body of `fetch` (currently lines ~361-380) — keep the signature and the `try`/`catch`:

```kotlin
private suspend fun <D : Query.Data, T> fetch(
    client: ApolloClient,
    query: Query<D>,
    map: (D) -> T,
): DomainState<T> = try {
    val resp = client.query(query).execute()
    when (val c = classifyResponse(
        exception = resp.exception,
        hasErrors = resp.hasErrors(),
        errorMessage = resp.errors?.joinToString { it.message },
        dataIsNull = resp.data == null,
    )) {
        is RespClass.Cert -> throw resp.exception!!   // surface to domainStream (cause chain carries the typed cert exception)
        is RespClass.Failed -> DomainState.Error(c.message)
        RespClass.Empty -> DomainState.Error("Empty GraphQL response")
        RespClass.Ok -> DomainState.Content(map(resp.data!!))
    }
} catch (e: ApolloException) {
    if (e.certIssue() != null) throw e   // defensive: if execute() ever throws
    DomainState.Error(e.message ?: "Network error")
} catch (e: Exception) {
    if (e.certIssue() != null) throw e
    DomainState.Error(e.message ?: "Unexpected error")
}
```

> `RespClass.Cert` only occurs when `resp.exception != null`, so `resp.exception!!` is safe. `domainStream`'s existing `catch (e: Throwable)` (lines ~344-355) maps the thrown exception to `_certPrompt` via `e.certIssue()` — unchanged.

- [ ] **Step 2: Change `testConnection` to return `TestOutcome` and inspect `resp.exception`**

Replace `testConnection` (currently lines ~548-568):

```kotlin
/**
 * Probe a server endpoint. Returns a structured [TestOutcome]. For the local
 * endpoint with self-signed trust enabled, pass [allowSelfSigned] = true and
 * the already-confirmed [pinnedSha256] (null on the first probe): the first
 * probe surfaces [TestOutcome.CertUntrusted] so the sheet can show the
 * fingerprint dialog; after the user confirms, a re-probe with the pin matches.
 */
suspend fun testConnection(
    baseUrl: String,
    apiKey: String,
    allowSelfSigned: Boolean = false,
    pinnedSha256: String? = null,
): TestOutcome = try {
    val trust = if (allowSelfSigned && baseUrl.startsWith("https", ignoreCase = true)) {
        TlsTrust.PinnedSelfSigned(pinnedSha256, acceptFirstUse = false)
    } else {
        TlsTrust.Default
    }
    val resp = apolloFactory.build(baseUrl, apiKey, trust).query(PingQuery()).execute()
    when (val c = classifyResponse(
        exception = resp.exception,
        hasErrors = resp.hasErrors(),
        errorMessage = resp.errors?.firstOrNull()?.message,
        dataIsNull = resp.data == null,
    )) {
        is RespClass.Cert -> when (val i = c.issue) {
            is CertIssue.Untrusted -> TestOutcome.CertUntrusted(i.sha256)
            is CertIssue.Changed -> TestOutcome.CertChanged(i.pinned, i.presented)
        }
        is RespClass.Failed -> TestOutcome.Failed(c.message)
        RespClass.Empty -> TestOutcome.Failed("No response from server")
        RespClass.Ok -> TestOutcome.Ok
    }
} catch (e: ApolloException) {
    when (val i = e.certIssue()) {
        is CertIssue.Untrusted -> TestOutcome.CertUntrusted(i.sha256)
        is CertIssue.Changed -> TestOutcome.CertChanged(i.pinned, i.presented)
        null -> TestOutcome.Failed(e.message ?: "Network error")
    }
} catch (e: Exception) {
    TestOutcome.Failed(e.message ?: "Unknown error")
}
```

Add the import if missing: `import io.github.nofuturekid.nova.data.repository.TestOutcome` is same-package (no import needed); ensure `CertIssue` is imported (it already is — used by `domainStream`).

- [ ] **Step 3: Confirm no other caller of `testConnection`**

Run: `grep -rn "testConnection" app/src/main/kotlin`
Expected: only the definition here and the call in `AddEditServerSheet.kt` (updated in Task 3). If any other caller exists, update it to the new return type.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt
git commit -m "fix(repo): honor Apollo-5 resp.exception in fetch + testConnection (surfaces cert prompt)"
```

---

## Task 3: Add/Edit VM — per-endpoint test, pending pin, in-sheet cert dialog, pin-on-save

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt`

- [ ] **Step 1: Rework `AddEditUiState`**

Replace `AddEditUiState` (lines ~54-66) — split test state per endpoint and add cert-dialog + pending-pin fields:

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
    val localTest: TestState = TestState.Idle,
    val localTestMsg: String? = null,
    val remoteTest: TestState = TestState.Idle,
    val remoteTestMsg: String? = null,
    // Fingerprint captured at Test, pinned on save (null until confirmed).
    val pendingLocalCertSha256: String? = null,
    // When non-null, the sheet shows the cert-trust confirm dialog.
    val certDialogSha256: String? = null,
    val certDialogPrevious: String? = null,
)
```

- [ ] **Step 2: Update `load` for the new field names**

In `load` (lines ~79-95), the constructed `AddEditUiState` no longer sets `testState`/`testMessage` (they're gone). Leave the rest as-is — the new test/cert fields take their defaults. No other change needed in `load`.

- [ ] **Step 3: Update setters — reset the right test state + clear stale pending/dialog**

Replace the setters block (lines ~97-108). Local-affecting setters reset the local test and clear the captured cert (it's stale once the address/SSL/trust changes); remote-affecting setters reset the remote test:

```kotlin
fun setName(v: String)      { _state.value = _state.value.copy(name = v) }
fun setLocalHost(v: String) { _state.value = _state.value.copy(localHost = v, localTest = TestState.Idle, localTestMsg = null, pendingLocalCertSha256 = null) }
fun setLocalSsl(v: Boolean) { _state.value = _state.value.copy(localSsl = v, localTest = TestState.Idle, localTestMsg = null, pendingLocalCertSha256 = null) }
fun setRemoteHost(v: String){ _state.value = _state.value.copy(remoteHost = v, remoteTest = TestState.Idle, remoteTestMsg = null) }
fun setRemoteSsl(v: Boolean){ _state.value = _state.value.copy(remoteSsl = v, remoteTest = TestState.Idle, remoteTestMsg = null) }
fun setTrustSelfSigned(v: Boolean) {
    _state.value = _state.value.copy(trustSelfSignedLocal = v, localTest = TestState.Idle, localTestMsg = null, pendingLocalCertSha256 = null)
}
fun setApiKey(v: String)    { _state.value = _state.value.copy(apiKey = v, localTest = TestState.Idle, remoteTest = TestState.Idle, localTestMsg = null, remoteTestMsg = null) }
fun toggleKeyVisible()      { _state.value = _state.value.copy(showKey = !_state.value.showKey) }
```

- [ ] **Step 4: Replace `test()` with `testLocal()`/`testRemote()` + cert-dialog handlers**

Remove the old `test()` (lines ~121-140). Add:

```kotlin
private fun keyMissing(s: AddEditUiState): Boolean =
    s.apiKey.isBlank() || s.apiKey.all { it == '•' }

fun testLocal() {
    val s = _state.value
    val url = EndpointUrl.compose(s.localHost, s.localSsl)
    if (url.isBlank()) return
    if (keyMissing(s)) {
        _state.value = s.copy(localTest = TestState.Fail, localTestMsg = "Enter the API key first")
        return
    }
    _state.value = s.copy(localTest = TestState.Testing, localTestMsg = null)
    viewModelScope.launch {
        val allowSelfSigned = s.localSsl && s.trustSelfSignedLocal
        when (val out = unraid.testConnection(url, s.apiKey, allowSelfSigned, s.pendingLocalCertSha256)) {
            TestOutcome.Ok -> _state.value = _state.value.copy(localTest = TestState.Ok, localTestMsg = null)
            is TestOutcome.Failed -> _state.value = _state.value.copy(localTest = TestState.Fail, localTestMsg = out.message)
            is TestOutcome.CertUntrusted -> _state.value = _state.value.copy(
                localTest = TestState.Idle, certDialogSha256 = out.sha256, certDialogPrevious = null,
            )
            is TestOutcome.CertChanged -> _state.value = _state.value.copy(
                localTest = TestState.Idle, certDialogSha256 = out.presented, certDialogPrevious = out.pinned,
            )
        }
    }
}

fun testRemote() {
    val s = _state.value
    val url = EndpointUrl.compose(s.remoteHost, s.remoteSsl)
    if (url.isBlank()) return
    if (keyMissing(s)) {
        _state.value = s.copy(remoteTest = TestState.Fail, remoteTestMsg = "Enter the API key first")
        return
    }
    _state.value = s.copy(remoteTest = TestState.Testing, remoteTestMsg = null)
    viewModelScope.launch {
        // Remote always uses full CA validation (allowSelfSigned = false). A
        // self-signed remote cert therefore comes back as Failed, never a prompt.
        when (val out = unraid.testConnection(url, s.apiKey, allowSelfSigned = false)) {
            TestOutcome.Ok -> _state.value = _state.value.copy(remoteTest = TestState.Ok, remoteTestMsg = null)
            is TestOutcome.Failed -> _state.value = _state.value.copy(remoteTest = TestState.Fail, remoteTestMsg = out.message)
            is TestOutcome.CertUntrusted -> _state.value = _state.value.copy(remoteTest = TestState.Fail, remoteTestMsg = "Certificate not trusted (remote requires a valid certificate)")
            is TestOutcome.CertChanged -> _state.value = _state.value.copy(remoteTest = TestState.Fail, remoteTestMsg = "Certificate changed")
        }
    }
}

/** User tapped "Trust" in the in-sheet cert dialog: remember the fingerprint
 *  (pinned on save) and re-run the local test, which now matches the pin. */
fun confirmLocalCert() {
    val s = _state.value
    val fp = s.certDialogSha256 ?: return
    _state.value = s.copy(pendingLocalCertSha256 = fp, certDialogSha256 = null, certDialogPrevious = null)
    testLocal()
}

fun dismissCertDialog() {
    _state.value = _state.value.copy(certDialogSha256 = null, certDialogPrevious = null)
}
```

- [ ] **Step 5: Persist the captured pin in `save()`**

Replace `save()` (lines ~142-162):

```kotlin
fun save(onDone: () -> Unit) {
    val s = _state.value
    if (s.name.isBlank()) return
    viewModelScope.launch {
        val localUrl = EndpointUrl.compose(s.localHost, s.localSsl)
        val remoteUrl = EndpointUrl.compose(s.remoteHost, s.remoteSsl)
        val hostname = s.localHost.substringBefore('/').ifBlank { s.remoteHost }
        val saved = servers.upsert(
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
        // If the user confirmed a self-signed cert at Test time, pin it now
        // (upsert minted the id for a new server). upsert() already clears the
        // pin when trust is off, so only persist when trust is on.
        val pending = s.pendingLocalCertSha256
        if (s.trustSelfSignedLocal && pending != null) {
            servers.setLocalCertPin(saved.id, pending)
        }
        onDone()
    }
}
```

> `servers.upsert(...)` returns the resolved `Server` (with a generated id for new servers). Confirm the return type while editing; if `upsert` returns `Unit` in the current code, capture the id differently (e.g. generate before save) and report — but per the repo it returns the `Server`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt
git commit -m "feat(ui): per-endpoint test VM, in-sheet cert confirm, pin captured cert on save"
```

---

## Task 4: Add/Edit sheet — two test panels + in-sheet cert dialog

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt`

- [ ] **Step 1: Give `TestConnectionPanel` a title parameter**

The private `TestConnectionPanel` composable (near the bottom of the file) currently hardcodes "Test connection". Add a `title: String` parameter and use it for the idle/headline label; keep the rest of its visuals. Change its signature to:

```kotlin
@Composable
private fun TestConnectionPanel(title: String, state: TestState, onTest: () -> Unit, message: String?) {
```

and replace the headline `Text(...)` that shows "Test connection"/"Connecting…"/etc. so the `TestState.Idle` case shows `title` (e.g. "Test local connection") instead of the literal "Test connection". Leave the Testing/Ok/Fail labels and the rest of the panel unchanged.

- [ ] **Step 2: Replace the single panel with per-endpoint panels**

Remove the single `TestConnectionPanel(state = state.testState, onTest = vm::test, message = state.testMessage)` call (lines ~323-327). In its place, render a local panel (only when a local host is entered) and a remote panel (only when a remote host is entered):

```kotlin
if (state.localHost.isNotBlank()) {
    TestConnectionPanel(
        title = "Test local connection",
        state = state.localTest,
        onTest = vm::testLocal,
        message = state.localTestMsg,
    )
    Spacer(Modifier.height(8.dp))
}
if (state.remoteHost.isNotBlank()) {
    TestConnectionPanel(
        title = "Test remote connection",
        state = state.remoteTest,
        onTest = vm::testRemote,
        message = state.remoteTestMsg,
    )
}
```

- [ ] **Step 3: Add the in-sheet cert-trust dialog**

After the action `Row` (the Delete/Cancel/Save row) but still inside the sheet's `Column` (or just before the closing of the `ModalBottomSheet` content), add — styled to match the app's dialogs (see `MainScreen.kt`'s cert dialog: `t.surface2`, `RoundedCornerShape(UnraidDims.radDialog)`, `UnraidButton`):

```kotlin
state.certDialogSha256?.let { fp ->
    androidx.compose.material3.AlertDialog(
        onDismissRequest = vm::dismissCertDialog,
        shape = RoundedCornerShape(UnraidDims.radDialog),
        containerColor = t.surface2,
        titleContentColor = t.text,
        textContentColor = t.muted,
        title = {
            Text(
                text = if (state.certDialogPrevious == null) "Trust this certificate?" else "Certificate changed",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = if (state.certDialogPrevious == null)
                        "This server presented a self-signed certificate. Trust it for the local connection?"
                    else
                        "The certificate changed. Only trust this if you changed it yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                state.certDialogPrevious?.let {
                    Text("Was: $it", style = MaterialTheme.typography.labelSmall, color = t.muted)
                    Spacer(Modifier.height(4.dp))
                }
                Text("SHA-256: $fp", style = MaterialTheme.typography.labelSmall, color = t.muted)
            }
        },
        confirmButton = {
            UnraidButton(
                onClick = vm::confirmLocalCert,
                label = if (state.certDialogPrevious == null) "Trust" else "Trust new",
                variant = BtnVariant.Text,
                tone = Tone.Accent,
            )
        },
        dismissButton = {
            UnraidButton(onClick = vm::dismissCertDialog, label = "Cancel", variant = BtnVariant.Text, tone = Tone.Neutral)
        },
    )
}
```

Add imports if missing: `import androidx.compose.material3.AlertDialog`, and ensure `UnraidDims` is imported (`io.github.nofuturekid.nova.ui.theme.UnraidDims`). Check the existing imports in the file and `MainScreen.kt` for the exact paths.

- [ ] **Step 4: Verify by reading** — `canSave` (uses `localHost`/`remoteHost`, unchanged) still compiles; no remaining references to the removed `state.testState`/`state.testMessage`/`vm.test()`.

Run: `grep -n "testState\|testMessage\|vm::test\b\|\.test()" app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt`
Expected: no matches (all replaced by the per-endpoint names).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/server/AddEditServerSheet.kt
git commit -m "feat(ui): two per-endpoint test panels + in-sheet self-signed cert dialog"
```

---

## Task 5: Verify, document, release (delegated tail — Rule 14)

- [ ] **Step 1: ADR-0041 amendment.** Append a dated amendment to `docs/adr/0041-host-ssl-entry-and-self-signed-local-trust.md`: Apollo Kotlin 5 `execute()` returns fetch/TLS errors in `ApolloResponse.exception` (does not throw); beta1 only checked thrown exceptions, so the cert prompt never fired — fixed by the pure `classifyResponse`. Plus the per-endpoint Test decision (Local test surfaces the fingerprint + pins on save; Remote always full CA validation).

- [ ] **Step 2: CHANGELOG** (curated, plain language, ADR-0031): under a new `0.1.39-beta2` entry — "Fixed: the self-signed certificate prompt now actually appears (on first connect in the overview, or right when you tap Test). The connection screen now tests the local and remote address separately."

- [ ] **Step 3: Version bump** (own commit, ADR-0015): `app/build.gradle.kts` → `versionCode = 105`, `versionName = "0.1.39-beta2"`.

- [ ] **Step 4: Push + PR** targeting `main`, title `fix: cert prompt (Apollo-5) + per-endpoint Test (0.1.39-beta2)`. Note in the body: no local toolchain → CI is the first compile; watch the `build`/`test` checks. **Report and STOP — do NOT watch CI or merge** (the main thread owns watch+merge; see `feedback_ci_watch_merge`).

---

## Self-Review

**Spec coverage (amendment A–F):**
- A core fix (`resp.exception`, pure `classify`) → Tasks 1, 2 ✓
- B `testConnection` → `TestOutcome`, no `acceptFirstUse=true` → Task 2 ✓
- C per-endpoint Test → Tasks 3 (`testLocal`/`testRemote`), 4 (two panels) ✓
- D Test-time dialog + pin-on-save → Task 3 (`confirmLocalCert`, `save`), 4 (dialog) ✓
- E convergence (same pin, no double prompt) → Task 3 save persists pin; overview path (already shipped) writes via `trustLocalCertificate`; both key by serverId ✓
- F tests → Task 1 (`classifyResponse` incl. nested-cause cert) ✓

**Placeholder scan:** none — every code step has concrete code; the one `grep` verification step has an expected result.

**Type consistency:** `RespClass{Cert(CertIssue)/Failed(String)/Empty/Ok}`, `classifyResponse(exception, hasErrors, errorMessage, dataIsNull)`, `TestOutcome{Ok/Failed/CertUntrusted(sha256)/CertChanged(pinned,presented)}`, `testConnection(baseUrl, apiKey, allowSelfSigned, pinnedSha256): TestOutcome`, VM `pendingLocalCertSha256`/`certDialogSha256`/`certDialogPrevious`, `testLocal`/`testRemote`/`confirmLocalCert`/`dismissCertDialog`, `TestConnectionPanel(title, state, onTest, message)` — consistent across Tasks 1→4.

**Risk flagged:** the cert exception must be reachable inside `resp.exception`'s cause chain. Confirmed from Apollo source: `ApolloNetworkException(message, platformCause)` sets `cause = platformCause as? Throwable`, and Conscrypt wraps a TrustManager `CertificateException` as the cause of `SSLHandshakeException`. Task 1's test exercises the nested-cause case. Final proof is on-device (maintainer gate).
