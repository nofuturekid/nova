# ADR-0051: Never follow a redirect with the API key

- **Status**: Accepted (2026-11-13)
- **Date**: 2026-11-13
- **Tags**: security, data, ui

## Context

Every request NOVA sends to an Unraid server carries the API key: the `x-api-key` header of the HTTP clients (`ApolloClientFactory.build` and `buildLongRunning`, added by an application interceptor) and the `connection_init` payload of the WebSocket client (`buildWs`), which is sent after the handshake. The background client (`buildBackground`, ADR-0048) already follows no redirect. The three foreground clients kept OkHttp's defaults (`followRedirects` and `followSslRedirects` both true). OkHttp strips only `Authorization` when it follows a redirect to another host, so a 3xx answer re-sent the key to whatever `Location` named: another host, or plain http after the user entered https. For the WebSocket the handshake itself was followed, so the second host completed it and then received the key. `KeyedClientRedirectTest` shows this on the unchanged clients: the key reaches a second server and the WebSocket peer gets a connection (four of its five tests fail without the change).

What real Unraid servers send (read from `unraid/webgui`, `etc/rc.d/rc.nginx` at commit `99e39027fb` of 2026-11-13; the same lines are in the fixture of `unraid/api`):

- SSL setting **No**: no redirect.
- SSL **Yes**: a server block on the http port answers every request with `return 302 https://$host:$PORTSSL$request_uri;` (`redirect host`). Same host name, https, port always spelled out.
- SSL **Strict** (`USE_SSL=auto`, with a myunraid.net certificate): `return 302 https://$(fqdn $ADDR)$PORTSSL_URL$request_uri;` (`redirect fqdn`), the host name being `lan-ip.hash.myunraid.net`. A different host name from the one the user typed, even for `http://192.168.1.100` or `http://tower.local`. A Tailscale name gets the same kind of `302` to its own https name.
- All of these are `302`, at server level, so they answer `POST /graphql` too. The `location /graphql` block itself has no redirect; the API answers on a unix socket behind nginx.
- OkHttp turns a followed `301`/`302`/`303` POST into a GET without a body (`RetryAndFollowUpInterceptor`; read from its documented behaviour, not run here), so following Unraid's own redirect could never have produced a working query: the user got a failure on the redirected GET, with no explanation.

So the realistic case is a user who typed an http address or a bare IP; the redirect is the server telling them the address to use.

## Decision

**No client that carries the API key follows a redirect. A redirect becomes a failure reason of its own that names the target.**

1. **Rule.** The key is never sent to a host other than the one the user entered, and never over http when the user entered https. The simplest way to guarantee both is that no redirect is followed at all: `followRedirects(false)` and `followSslRedirects(false)` on the base and the long-running client; the WebSocket client is derived from the base client and inherits them, which ends the handshake at the 3xx. The background client keeps its own explicit settings.
2. **Not "follow same-host http-to-https upgrades".** That was the alternative. It would not help: the first request already sent the key over http, to the host the user entered, before the redirect; Unraid's redirect is a 302 that OkHttp would turn into a body-less GET; and following needs a custom interceptor that re-sends the POST and compares hosts, which is more code to get wrong in the code path that guards the key. Telling the user the address to enter costs one sentence and fixes the next request too.
3. **Reason.** `ErrorReason.Redirected` (not a connection problem, so the top-bar pill does not say Offline: the server answered). `ApolloException.redirectMessage()` in `ResponseClassifier.kt` recognises the statuses OkHttp followed (300, 301, 302, 303, 307, 308) and builds `The server redirects to <scheme://host[:port]>. Edit the server and enter that address.` from the `Location` header. Only the origin is shown: the path is of no use to the user, a query can carry tokens, and `user:password@` is dropped. A missing, relative or non-http `Location` gets the same advice without a target. Tabs show it as "The address needs to change" with that sentence; `DomainState.Error.detail` keeps the raw text.
4. **One text everywhere.** `classifyResponse` returns the sentence for a redirect and `checkActionResponse` gives it (lower-cased) to the snackbar of a failed action. `classifyErrorReason` and `toQueryError` give tabs the reason. The Add server sheet's **Test** (`testConnection`) does not go through `classifyResponse`: it runs `testOutcomeOf(probeCapabilities(...))`, and `testOutcomeOf` prefers the redirect message of the probe's failure cause, so Test shows the same sentence. The WebSocket streams keep their generic error text; the polling tabs and **Test** carry the reason.
5. **Unchanged.** A server that does not redirect, http-only servers (SSL No) and the background check behave as before; certificate handling is not touched.

## Consequences

- **Positive.** The key cannot be moved by a redirect, whoever sends it (a misconfigured proxy, a changed DNS answer, a captive portal). A user who typed the wrong scheme is told the address instead of seeing an unexplained failure.
- **Negative / trade-offs.** A setup that relied on NOVA following a redirect (a reverse proxy that redirects `/graphql` to another path or host) now fails with the new message and has to be entered at its final address. Unraid's own redirects never worked for POST queries, so no working setup of that kind is known. An http-to-https redirect cannot be followed even when it is harmless; the user enters the https address once.
- **Not covered.** The https-to-http direction cannot be staged in a unit test without a TLS peer; a test pins the two OkHttp switches instead. Certificate handling and the first request over plain http (the user's own choice) are unchanged.
- **Trigger to revisit.** If a real, common Unraid or proxy setup answers the GraphQL POST with a redirect that must be followed (for example a `308` from a documented proxy), follow only a same-host, same-or-upgrading-scheme `307`/`308` in an interceptor that drops the key for any other target.

## Alternatives considered

- **Follow only same-host http-to-https upgrades.** Rejected in decision 2.
- **Keep following and strip `x-api-key` on a host or scheme change** (an OkHttp network interceptor). It keeps OkHttp's redirect logic and drops the key, but the follow-up request then fails as unauthenticated with a misleading "key rejected", and the first hop still leaked.
- **Do nothing for the WebSocket.** Rejected: the handshake redirect is the worse leak because the key follows in the payload.

## References

- Issue hellwand/nova#354; ADR-0048 (the background client that started this), ADR-0041 (host and SSL entry), ADR-0042 (WebSocket transport), ADR-0047.
- Upstream: `unraid/webgui` `etc/rc.d/rc.nginx` (`redirect()` at the top, the `USE_SSL` branches in `build_servers`, `location /graphql` in `build_locations`).
