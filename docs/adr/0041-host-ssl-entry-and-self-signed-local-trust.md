# ADR-0041: Host+SSL connection entry and self-signed local-cert trust (TOFU)

- **Status**: Accepted
- **Date**: 2026-05-29
- **Tags**: ui, security, network, tls

## Context

The server-connection form historically accepted a free-text URL field (e.g. `http://192.168.1.10:8080`). This was clumsy — users had to get the scheme, hostname/IP, port, and trailing slash right in a single unguided text field, and the form offered no affordance for toggling between HTTP and HTTPS.

More importantly: the maintainer switched their local Unraid server to HTTPS with a self-signed certificate. The app's existing TLS posture used stock OkHttp with a `network_security_config` that trusted system CAs only. Since Android 7 (API 24), user-installed CA certificates are explicitly excluded from app trust by default. A self-signed certificate is not signed by any CA in the system store, so OkHttp rejected the TLS handshake outright — the app could not connect to a locally-managed HTTPS server at all.

The two problems share a UI entry point (how you enter the connection details), and the second problem (self-signed TLS) only arises on **local** connections — remote connections to Unraid.Community-hosted relay or a reverse proxy should always have a valid CA-signed cert, so relaxing TLS globally was never on the table.

## Decision

**Replace the free-text URL field with a host+port field and an SSL toggle** on both Local and Remote endpoint forms. The SSL switch controls the scheme (`http` vs `https`); port defaults to `80`/`443` depending on the switch state (user may override). The composed URL stored in `ServerConfig` (`localUrl` / `remoteUrl`) is identical to what was stored before — no data migration.

**Add opt-in, local-endpoint-only TOFU certificate-pinning** for self-signed HTTPS. Scope is strictly gated by three conditions that must all be true: `ConnectionMode.Local`, the per-server `trustSelfSignedLocal` opt-in flag, and `https` scheme. The pure `trustFor(config: ServerConfig, mode: ConnectionMode): TlsTrust` function is the single decision point — if any condition is false it returns `TlsTrust.Default` (stock system-CA validation), otherwise `TlsTrust.LocalPinned`.

Trust flow on `TlsTrust.LocalPinned`:

1. **First connection (no stored pin):** the `LocalPinningTrustManager` accepts the presented certificate chain, computes its SHA-256 fingerprint, surfaces a confirmation dialog showing the fingerprint, and persists the pin only after the user taps **Trust**. Until the user confirms, the connection is held and the server is not considered reachable.
2. **Subsequent connections (pin stored):** the trust manager compares the presented leaf-certificate fingerprint to the stored pin. Match → accepted. Mismatch → rejected and a "certificate changed" prompt surfaces, requiring the user to explicitly re-accept the new fingerprint. This is the change-detection property: a changed cert blocks the connection until the user actively reviews it.
3. **Explicit Reset:** Settings exposes a **"Reset certificate"** button that clears the stored pin. The next connection is treated as first-use again.

A **permissive hostname verifier** is installed on the single-purpose local-endpoint HTTPS `OkHttpClient` that carries the pinning trust manager. This is intentional and safe for the following reasons: (a) the client is single-purpose — it is created per server, per local endpoint, and is never reused for any other host or purpose; (b) the certificate pin (SHA-256 of the leaf cert) is the trust anchor, not the CA chain — a mismatch in the CN or SAN field does not weaken the trust model because the pin ties trust to the exact certificate, not to the name; (c) self-signed LAN certificates issued by `openssl req -x509` almost never include a SAN that matches the IP address or mDNS hostname the user typed, so a strict hostname verifier would make TOFU non-functional in the exact scenario it is designed for. Remote endpoints never receive this client — they always use a client with system-CA validation and a strict hostname verifier.

## Consequences

- **Positive.** Users who self-host Unraid on HTTPS with a self-signed cert can now connect without modifying their server or purchasing a certificate. The host+port+SSL UI is substantially less error-prone than the free-text URL. No storage migration is required — composed URLs are identical. Remote endpoints are completely unaffected: they continue to use system-CA validation and the standard hostname verifier.
- **Negative / trade-offs.** The TOFU model is only as strong as the user's ability to verify the fingerprint out-of-band on first accept; a motivated MITM on the LAN at first-connection could cause the user to pin an attacker's certificate. However, this is the same trust model as SSH host key fingerprint acceptance — well understood for LAN-local trust scenarios. The opt-in flag limits exposure to users who deliberately enable it.
- **Trigger to revisit.** (a) If Unraid ships an official ACME/Let's Encrypt integration that makes valid CA-signed LAN certs easy, TOFU becomes unnecessary and the feature can be removed. (b) If Remote endpoints ever need a non-CA-validated cert path, a separate ADR is required — this ADR explicitly does not permit that.

## Alternatives considered

**OkHttp `CertificatePinner`.** Rejected. `CertificatePinner` narrows trust within a CA-validated chain — it can enforce that a CA-signed certificate has a specific public-key pin, but it cannot accept a certificate that has already failed chain validation. A self-signed cert fails chain validation before `CertificatePinner` is consulted, so pinning alone cannot establish trust for a chain-invalid cert.

**Bundling the cert as a `network_security_config` trust anchor.** Rejected. The `network_security_config` trust-anchor mechanism is static and compile-time: the certificate must be shipped inside the APK or system image. It cannot be per-server, cannot be entered by the user at runtime, and cannot handle the TOFU rotation scenario (cert changes → user re-accepts). Even if we accepted the static limitation, it would mean re-releasing the app every time any user's self-signed cert is regenerated.

**Global TLS trust relaxation (trust all certs app-wide).** Rejected without consideration. Would silently break TLS for remote connections and expose all API-key traffic.

## References

- ADR-0027 — Agent autonomy & access model (Tier-3 device acceptance gates the beta).
- ADR-0005 — Pre-release tag convention (`v0.1.39-beta1`).
- ADR-0013 — Version naming (pre-release suffix in `versionName`).
- `TlsTrust`, `trustFor`, `LocalPinningTrustManager`, `TlsTrustDecisionTest`, `EndpointUrlTest` — implementation in this branch.

---

## Amendment — 2026-05-29 (0.1.39-beta2)

**Apollo Kotlin 5 `execute()` does not throw on TLS/network errors.** In Apollo Kotlin 5, `ApolloCall.execute()` returns all fetch-level failures (TLS handshake, socket errors, etc.) in `ApolloResponse.exception` rather than throwing. `hasErrors()` reflects only GraphQL-body errors. The `beta1` implementation inspected only thrown exceptions, so a self-signed TLS rejection was never caught as a cert-trust scenario — it was misclassified as an empty/null response and the fingerprint dialog never fired. Fixed in `beta2` by introducing a pure `classifyResponse(resp)` function that inspects `resp.exception` directly; both `fetch` and `testConnection` route through it, so all error paths are classified consistently.

**Per-endpoint Test decision.** The connection test was reworked into two independent **Local** and **Remote** test panels in the connection sheet. The Local test now surfaces the self-signed certificate fingerprint dialog inline (right in the sheet) when `trustSelfSignedLocal` is on and no pin is stored yet; accepting the fingerprint there stores the pin immediately, so saving the server config does not prompt a second time (`pendingLocalCertSha256` carries the in-flight fingerprint from the test into the save path). Remote endpoints always use full CA validation — the Local-only TOFU trust grant from the original ADR decision is unchanged. Both the Test path and the Overview's first-connect path converge on the same per-server pin store, so there is no double-prompt and no duplicate pin.
