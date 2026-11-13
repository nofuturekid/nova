# ADR-0050: A demo mode with fictional data

- **Status**: Proposed (2026-11-13)
- **Date**: 2026-11-13
- **Tags**: data, ui, distribution, security

## Context

NOVA is a client for the user's own Unraid server. Without one it shows "No server configured", and adding a server needs a host and an API key. Two groups cannot get past that screen: Google Play's reviewers, who must be able to use every screen ("an active demo account, login information, and all other resources needed for Google Play to review your app"), and anyone who wants to look at the app before creating an API key. ADR-0049 (decision 4, issue #328) decided on an in-app demo mode with fictional data instead of a permanently reachable reviewer server. This ADR is that design.

What the code offers (read before deciding):

- Every screen's data comes from a cold `Flow<DomainState<T>>` of `UnraidRepository` (`infoStream`, `arrayStream`, `dockerStream`, ...), and every action is a `suspend` method of the same class. Each stream is built inside `domainStream` / `subscriptionStream` from `ServerRepository.activeWithKey`, an `ApolloClientFactory` client and an Apollo query, whose result goes through the mapper in `GraphQlMapper.kt`. The seam between "where data comes from" and "what the screen shows" is therefore the domain model (`ArrayInfo`, `Container`, `Notifications`, ...), not the GraphQL types.
- The view models (`MainViewModel`, `PluginsViewModel`, `NetworkViewModel`) and `CapabilityRepository` are the only users of `UnraidRepository`'s streams and actions. `AddEditServerViewModel` uses its `testConnection`, which only a real server needs.
- A server is the stored entry `Server`; its API key is in `ApiKeyStore`, never in the entry. `ServerRepository.activeWithKey` resolves the active entry and its key; a blank key makes every real stream emit "Missing API key".
- The capability model (ADR-0047) is detected per active server by `CapabilityRepository` through `UnraidRepository.capabilityTarget` and `probeCapabilities`.
- The store screenshots are rendered from fictional data that lived in the test source set (`StoreScreenshots`): server "Tower" on `example.net`, the array, ten containers, five VMs, notifications.

## Decision

**NOVA gets a demo mode: a special server entry whose data is fictional, lives in memory and is answered without any network code.**

1. **The entry.** `Server` gets `isDemo: Boolean = false`. The demo is one entry with a fixed id (`demo`), the name "Tower", the host name `tower.example.net`, no address, no key and no certificate. `isDemo` is the one way to tell a demo entry from a real one: the default is left out of the stored list, so a real server's stored entry is byte-for-byte what it was. Everything that opens a connection for a stored server must skip an entry with this flag; the background alert check (ADR-0048, built in parallel) is the one such place today.
2. **The seam.** A new class `UnraidGateway` sits between the view models and `UnraidRepository`. It asks one question, "is the active server the demo?" (from `ServerRepository.activeServer`, which does not read the key), and hands each call either to `UnraidRepository` (a real server, unchanged) or to `DemoUnraid` (the demo). Streams are routed with `flatMapLatest` on that answer, actions with one check per call. `UnraidRepository.kt` is not touched. The view models change only the type they inject; their calls keep their names.
3. **`DemoUnraid`.** It offers the same streams and actions on top of an immutable `DemoState` (containers, VMs, notifications, array state, parity run). Each action is a pure function `DemoState -> DemoState`, so what a tap does is unit-tested without a screen or a clock. The state is held in a `MutableStateFlow`: a screen sees an action at once. It has no Apollo client, no OkHttp, no URL and no key as a dependency: the class cannot open a connection.
4. **Live-looking figures.** `DemoMetrics` computes CPU, memory, network, temperature and per-container stats as pure functions of a tick number (two seconds per tick, counted from the start of the demo). The streams are cold flows that tick only while a screen collects them; nothing runs after the screen is gone. A parity check's progress is computed from the clock, so no timer advances it.
5. **Behaviour.** Container and VM actions change the state as a user expects (stop, start, restart, pause, resume, reboot). Stopping the array stops the containers and VMs and lets the disks sleep, as Unraid does; starting it brings the containers set to autostart back. Parity check start, pause, resume and cancel work; a check takes eight minutes and then ends as completed. Notifications can be archived, marked unread, deleted, archived all and cleared; the counts are derived, so the bell badge follows. A container update "pulls" for 1.5 seconds, then the container is current and the update banner goes. The container logs are generated lines. The web UI links of the containers are left out, so the demo offers no link to an address that goes nowhere.
6. **Life cycle.** Nothing is persisted but the entry itself. `DemoUnraid` replaces its state with a fresh one as soon as the server list no longer contains a demo entry (removing the demo server, like any server), and the state dies with the process.
7. **Capabilities.** For the demo, `UnraidGateway.capabilityTarget` returns a fixed target without a key and `probeCapabilities` answers from memory: detection `Done`, API 4.37.11, the role `ADMIN`. Every `KeyAction` is `Allowed` and every `ApiFeature` is `Yes`, so permission-based hiding of controls (#315) never hides a demo control.
8. **Entry and exit.** On the "No server configured" screen a quiet "Try the demo" text button sits under "Add server", and the server list has a "Try the demo" row until the entry exists. Both read one `CompositionLocal` provided at the top of the navigation graph, so the four tabs need no new parameter. The demo entry in the list can be removed like any server (its edit button opens a small sheet with the explanation and "Remove demo"). Adding a real server while the demo is the active server makes the new server active (one rule in `SettingsStore.saveServer`: the first real server added while the demo is active); the demo stays in the list until removed.
9. **Honest labelling.** The top bar shows a "Demo" pill where the Local/Remote pill sits (the demo has no connection to switch), the server list marks the entry "Demo" with "Fictional data, no connection", and the top of the Overview carries one sentence: "Demo mode: this server and everything on it is fictional. Nothing is sent over the network."
10. **One source for the fixtures.** The fictional data moves from the test source set to `data/demo/DemoFixtures.kt` in main code. `StoreScreenshots` reads it from there (it shows the entry without the demo marker, so the store images are unchanged). The data is plain Kotlin: no resource, no JSON, no new dependency and no new permission. Both flavors have the demo mode.
11. **The update check stays as it is.** It reaches GitHub, not the Unraid server, and is off in the `store` flavor.

## Consequences

- **Positive.** Reviewers and curious users can use every screen without a server; the same code and data produce the store screenshots, so they cannot drift. Real servers behave exactly as before: nothing in `UnraidRepository` changed, and the gateway hands a real server's calls through. The demo's promises are testable: a factory that fails on any client request proves that the demo never creates a client.
- **Negative / trade-offs.** `UnraidGateway` repeats the list of `UnraidRepository`'s public streams and actions (about 35 one-line delegations); a new stream or action must be added there too, or it is not routed (a view model that injects the gateway cannot call what the gateway lacks, so the compiler catches the omission). The demo's behaviour is a model of Unraid's, not Unraid: details such as how long a parity check takes are chosen, not measured. Compiled code and data of the demo are added to every APK (the PR states the measured size).
- **Trigger to revisit.** (a) Google rejects the demo mode as a substitute for credentials (ADR-0049's fallback: a reviewer server). (b) `UnraidRepository` is split into per-domain sources: the demo then becomes one implementation of that interface and the gateway shrinks. (c) A second kind of special entry is wanted: `isDemo` would become a `kind`.

## Alternatives considered

- **A demo `ApolloClient` with its own `NetworkTransport` that answers GraphQL from memory.** It would leave `UnraidRepository` and the mapper untouched and test the mapping too. Rejected: the fixtures would have to be GraphQL response types (the mapper's inverse) for about 25 operations and subscriptions; the demo would carry a second schema model, grow the APK more, and still need the real code's key and URL handling to be bypassed.
- **`if (demo)` inside `UnraidRepository`.** Smallest in the first hour, but 35 scattered checks in a 1,300-line class that three other changes are editing, and every new stream would have to remember the check. Rejected for the scattering and for the merge risk; the gateway keeps the decision in one file.
- **An interface that `UnraidRepository` and `DemoUnraid` both implement, with the repository renamed.** The cleanest end state. Rejected for now because it touches every member of `UnraidRepository` (an `override` on each) and collides with parallel work; the gateway can be turned into this later (trigger b) without changing the callers.
- **A permanently reachable reviewer Unraid server** (ADR-0049's alternative): no code, permanent exposure and upkeep. Kept as the fallback.
- **A debug-only or flavor-only demo.** Reviewers get the `store` flavor, so a demo that exists only elsewhere is useless to them; F-Droid users and new users profit from it too.

## References

- Issue hellwand/nova#328 (the Google Play plan); ADR-0049 (decision 4), ADR-0047 (capabilities), ADR-0048 (background alerts), ADR-0017 (lifecycle-gated streams), ADR-0035 (API key states).
- `docs/store/app-access.md` (reviewer instructions that use this mode).
