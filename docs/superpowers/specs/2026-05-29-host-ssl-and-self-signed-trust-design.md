# Design: Host + SSL-switch connection entry & self-signed local trust (TOFU)

- **Date:** 2026-05-29
- **Status:** Approved (brainstorming) — ready for implementation plan
- **ADR:** 0041 (to be written during implementation)
- **Baseline:** `main` @ `1dfd8af`, v0.1.38

## Problem

Two related friction points in connecting to an Unraid server:

1. **URL entry is clumsy.** Each server endpoint (Local, Remote) is a free-text
   full URL (`http://192.168.1.10`, `https://your-server.unraid.net`). Users
   would rather type just a host (`192.168.11.2`, `unraid-01.kroll-home.de`) and
   flip a switch for SSL.
2. **Self-signed HTTPS on the LAN doesn't work.** The maintainer switched their
   local Unraid to HTTPS with a self-signed certificate. The app uses a stock
   `OkHttpClient` (no custom trust manager) and a `network_security_config` whose
   base config trusts **system CAs only** — so a self-signed cert fails the TLS
   handshake (`SSLHandshakeException`, trust anchor not found). Even installing
   the cert as a user CA wouldn't help (user CAs untrusted by default since
   Android 7 / API 24).

These are cohesive: the SSL switch is the natural anchor for an opt-in to trust a
self-signed certificate, which is only relevant when local SSL is on.

## Scope

In scope:

- Replace the two free-text URL fields with **host field (+ optional `:port`) +
  SSL switch**, per endpoint (Local and Remote).
- Per-server, **local-only**, **opt-in** trust of a self-signed certificate using
  **TOFU pinning** (trust-on-first-use, SSH-style: pin the cert's fingerprint,
  detect and block on change).

Out of scope:

- Self-signed / relaxed trust for the **remote** endpoint — remote always does
  full CA validation. (The host+SSL *input* change applies to both endpoints; the
  *trust opt-in* is local-only.)
- Any change to `network_security_config.xml` (a per-client custom trust manager
  overrides default trust for that one client without touching the global config).
- GraphQL subscriptions / any unrelated connection work.

## Decisions (locked during brainstorming)

| Decision | Choice | Rationale |
|---|---|---|
| Trust model | **TOFU pinning** (not blanket trust-any-cert) | Pins one specific cert; detects change (rotation or MITM). The security value is change-detection. |
| "Only for local" scope | **Bound to the local endpoint** (`localUrl` / mode=Local). No address-type restriction. | Works for RFC1918 IPs *and* hostnames (`tower.local`, custom DNS → LAN). An IP-range gate would break hostname-based local setups. |
| Port input | **Optional `:port` in the host field** | One field; matches how people write addresses. SSL switch only chooses the scheme. |
| Storage format | **Unchanged** — store composed `scheme://host[:port]` in existing `localUrl`/`remoteUrl`; parse back to host+toggle on edit | Zero migration; `ApolloClientFactory`, repos, health layer untouched. Host+SSL is a UI-layer convenience only. |
| First-use UX | **Show SHA-256 fingerprint, require confirm tap** (not silent pin) | SSH-style first-use decision; lets the user verify out-of-band. |
| Trust mechanism | **Custom `X509TrustManager` + `SSLSocketFactory` on the local client only** | The only viable runtime, per-server, TOFU mechanism (see Rejected below). |

### Rejected mechanisms

- **OkHttp `CertificatePinner`** — only *narrows* which CA-validated cert is
  acceptable; it cannot make a self-signed cert that fails chain-building pass.
  Useless on its own here.
- **Bundle cert as a `network_security_config` trust-anchor** — compile-time and
  static; can't be per-server, per-user, or TOFU. Wrong tool for a runtime opt-in.

## Architecture

### Components & responsibilities

1. **Endpoint input model (UI/VM layer only)** — `AddEditServerSheet` +
   `AddEditServerViewModel`. Holds, per endpoint: `host: String`, `ssl: Boolean`.
   - **Compose** (`save`): `url = (if ssl "https" else "http") + "://" + host`
     where `host` may carry `:port`. Empty host → empty stored URL (endpoint
     unset, as today).
   - **Parse** (`load`): split a stored URL into `ssl` (scheme == https) and
     `host` (authority incl. any `:port`, no scheme, no path). A blank stored URL
     → `host=""`, default `ssl` (local=false, remote=true).
   - `hostname` field on `Server` is now the entered local host directly (drop the
     scheme-strip in `save`).

2. **`Server` model** — add `val trustSelfSignedLocal: Boolean = false`. No other
   field changes; `localUrl`/`remoteUrl` keep their composed-URL meaning.

3. **Pin store** — store the pinned cert **SHA-256 fingerprint per server id** in
   `SettingsStore` (same DataStore that holds servers). API roughly:
   `pinFor(id): String?`, `setPin(id, sha256)`, `clearPin(id)`. Cleared on server
   delete and when the local trust switch is turned off.

4. **`LocalPinningTrustManager`** — an `X509TrustManager` wrapping the platform
   default. Evaluation for a presented chain (leaf = `chain[0]`):
   - delegate to platform default → if it **passes**, accept (covers a properly
     CA-certified local server too);
   - else compute leaf SHA-256:
     - **no pin stored** → throw a typed `CertificateUntrusted` exception carrying
       the fingerprint (first-use; UI prompts, pins on confirm);
     - **pin stored, matches** → accept;
     - **pin stored, differs** → throw a typed `CertificateChanged` exception
       carrying old + new fingerprints (block; UI prompts re-accept).

5. **`ApolloClientFactory`** — when building the client for a server whose
   `trustSelfSignedLocal` is on **and** the endpoint being built is its **local**
   `https` URL, install the `LocalPinningTrustManager` (+ matching
   `SSLSocketFactory`) on that client. All other clients (remote, GitHub, http
   local) keep stock validation. The factory already keys clients per
   `(variant, endpoint, apiKey)`; the trust config is part of that build path.
   - The factory needs the per-server trust flag + a pin accessor. Wire via the
     repository/caller that already knows the active `Server` (caller passes the
     trust intent + pin callbacks into `build`, keeping the factory free of a
     direct store dependency where practical).

6. **Health / connection state** — add `CertificateUntrusted` and
   `CertificateChanged` to the connection-status surface used by the poll/health
   layer (where `SSLHandshakeException` / the typed exceptions surface), so the UI
   shows a trust prompt rather than a generic "Offline."

### Data flow (first-use, local self-signed)

```
User: Local host=192.168.11.2, SSL on, "Trust self-signed" on → Save
  store localUrl = "https://192.168.11.2", trustSelfSignedLocal = true (no pin yet)
Next local connect:
  TLS handshake → platform default fails (self-signed)
    → LocalPinningTrustManager: no pin → throws CertificateUntrusted(sha256)
      → health state = CertificateUntrusted
        → UI dialog: "Trust this certificate? SHA-256 9F:2A:…:C1"  [Trust] [Cancel]
          Trust → setPin(id, sha256); reconnect → matches → Connected
Later, cert changes:
  default fails → pin differs → throws CertificateChanged(old, new)
    → health state = CertificateChanged
      → UI dialog: old→new fingerprints, warning  [Trust new] [Cancel]
```

## UI

- **`AddEditServerSheet`**: replace the two URL `UnraidField`s with, per endpoint,
  a host `UnraidField` (placeholder `192.168.11.2`, keyboard `Uri`) + an SSL
  switch row. Local block also shows the **"Trust self-signed certificate"**
  switch *only when Local SSL is on*; when that switch is on and a pin exists, show
  a **Reset certificate** affordance.
- **Trust dialog** (first-use + changed): groomed SHA-256 hex (colon-grouped),
  Trust/Cancel; changed case shows old→new and a warning tone.
- **Test-connection panel**: unchanged behaviour — it composes the URL the same way
  and benefits from the same trust path.
- Helpers/labels: keep the existing "Used on home network" / "used when away from
  home" guidance; add a one-line caption on the trust switch that it applies only
  to the local connection.

## Error handling

- Typed exceptions (`CertificateUntrusted`, `CertificateChanged`) carry
  fingerprints and map to the new health states; never swallowed into a generic
  failure.
- Blank host → endpoint treated as unset (parity with today's blank URL).
- Turning the trust switch **off** clears any stored pin for that server.
- The relaxed trust path is guarded so it is structurally impossible to apply to
  the remote endpoint or the GitHub update client (enforced by a test).

## Testing (Rule 9 — encode intent)

- **Compose/parse round-trip:** `host` ↔ URL for `{http,https} × {host, host:port}`;
  legacy stored full URLs parse correctly; blank stays blank. *Why:* a parse bug
  silently rewrites a saved server's address.
- **Trust manager:** CA-valid passes (no pin needed); untrusted+no-pin throws
  `CertificateUntrusted` with correct fingerprint; matching pin accepts; differing
  pin throws `CertificateChanged`. *Why:* TOFU change-detection is the security
  property — a test that can't fail when that logic regresses is worthless.
- **Scope isolation:** relaxed trust must NOT be installed on the remote client or
  the GitHub client even when `trustSelfSignedLocal` is on. *Why:* the whole point
  of "local only."
- **On-device acceptance (maintainer gate, ADR-0027 Tier 3):** real TLS handshake
  against the live self-signed `https://192.168.11.2`, end-to-end first-use pin +
  reconnect. Per [[live-graphql-validation]], the live check is required before
  ship; it is the maintainer's device gate, not an agent action.

## Docs & release

- **ADR-0041** — records both the input simplification and the TOFU-local-trust
  decision, the local-only scope, and the rejected mechanisms (CertificatePinner,
  network_security_config anchor).
- **CHANGELOG** (curated, ADR-0031, plain language): "Enter just a host and flip an
  SSL switch instead of a full URL; connect to a local server that uses a
  self-signed HTTPS certificate (you confirm the certificate once)."
- Ships through the normal beta pipeline; promote-to-stable stays the maintainer
  gate ([[ci-watch-merge-standing]]).

## Out-of-scope / future

- Remote self-signed trust (deliberately excluded).
- Importing a custom CA cert file.
- mDNS host discovery.
