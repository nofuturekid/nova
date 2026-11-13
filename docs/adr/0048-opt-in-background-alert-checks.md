# ADR-0048: Opt-in background alert checks

- **Status**: Accepted (2026-11-13). Amends ADR-0017. The decision logic and its stored state are built first; the background job, the settings screen and the notifications follow in later changes (#321).
- **Date**: 2026-11-13
- **Tags**: data, security, performance, notifications

## Context

Users want to hear about a problem on their server without opening NOVA (#321). Until now NOVA did no background work at all. ADR-0017 states it as a rule: the app lifecycle gates every poll, "so backgrounded app = zero polls, period". (Release 0.1.42 did not remove background polling; it fixed three screens that kept polling while the app was in the background, which is the same rule.)

The maintainer decided on 2026-11-13: alerts are opt-in and off by default, and background checks exist only while the switch is on. The default level is alerts and warnings.

The facts the design rests on:

- Unraid's own monitor already creates a notification, with an importance of alert, warning or normal, for hot disks, SMART warnings, disks in error, full disks, read errors, a degraded pool, a boot drive that is not writable and parity checks. NOVA already reads the unread alerts and warnings with the query behind the bell (`GetNotifications`).
- Unraid does not notify about an array that is not started, and it stays silent about disk errors while the array is stopped.
- Unraid's notification agents send plain HTTP posts. UnifiedPush requires encrypted messages from the sender (RFC 8291), so it cannot be fed by those agents.
- The API key is stored encrypted with a key in the Android Keystore that does not require an unlocked device or user authentication, so a background check can use it.

## Decision

**Add an optional background check for alerts that runs only while the user has switched it on.** With the switch off, NOVA still does nothing in the background, exactly as before. ADR-0017's rule stays in force for every screen stream; only this opt-in job is an exception to it.

What is decided here, and what this first change implements:

1. **Events.** A new unread Unraid notification of importance alert or warning, and two array conditions that Unraid never notifies about: the array is not started, and the array is degraded. The array conditions are the rules of `attentionItems` (the Overview card), wrapped and limited to those two keys; nothing is forked. Disk temperature, disk errors, parity and pools are not raised from the array data again, because Unraid already sends them as notifications and every problem would be posted twice. Information-level notifications are never alerts. Containers and an unreachable server are not alerts either: a stopped container is often deliberate, and the phone cannot tell "server down" from "phone away from home".
2. **Once per notification.** Each server remembers the ids of the notifications it has seen. An id is never posted twice, even if the notification leaves the unread list and returns. The id is built from the event time, so a later notification with the same text is a new event and is posted. The list is kept in insertion order and capped at 300; an id that is still on the server is never forgotten before one that is gone, so the stored state cannot grow without bound.
3. **First check is silent.** The first successful read of a server after the switch is turned on, or after the server is added, only records what is already there and posts nothing. Turning the switch off forgets all state, so the next start is a first check again. The same rule applies to the array conditions.
4. **Array conditions once per episode.** A condition is raised when it begins and again only after it was gone at an in-between check.
5. **Level.** "Alerts and warnings" posts everything above; "Alerts only" leaves out new warning notifications. The two array conditions are always posted, because Unraid never notifies about them. Notifications the level filters out are still remembered, so widening the level later does not post old ones.
6. **Failures change nothing.** A check that did not load a half (server unreachable, certificate problem, key missing, request rejected) neither posts nor alters the remembered state of that half, so alerts created during an outage are posted once when the server answers again and nothing is posted twice. A failed check never counts as the silent first check.
7. **Several servers.** Every saved server has its own state, keyed by its stable id, and the state is removed with the server entry.
8. **At most five alerts per server and check** get their own notification, most urgent first; the rest are folded into one summary line.
9. **One-shot seam.** What one check needs from one server (the unread alerts and warnings, the array state, and how the attempt went) is behind a small interface, so the worker, and later a widget, share one code path. This change adds the interface and a test fake; the implementation that talks to the server arrives with the worker.

Planned for the following changes, recorded here so the picture is complete: a periodic WorkManager job (every 30 minutes by default, 15 minutes to 3 hours selectable) initialised on demand so that WorkManager is never touched while the switch is off; the two existing read-only queries over one connection; fail-closed certificate trust and no prompts in the background; no foreground service, no exact alarms, no battery-optimisation exemption request; `FOREGROUND_SERVICE` stripped from the merged manifest; `POST_NOTIFICATIONS` asked for only when the user turns the switch on.

## Consequences

**Positive**

- Users learn about a failing disk, an array that is not started or a degraded array without opening NOVA, with no relay, no push service, no Google services and no server plugin.
- The decision logic is pure and fully unit-tested before anything runs in the background, and the Overview card and a later widget share its rules.
- Nothing changes for users who leave the switch off.

**Negative / trade-offs**

- Android decides when a periodic job runs. Under Doze and OEM battery managers an alert can come late, sometimes by an hour or more, and on Android 13 and later an app that is not opened for 8 days is limited to about one run a day.
- WorkManager adds two permissions, `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED`, to every install, even with the switch off. It also merges `ACCESS_NETWORK_STATE`, which NOVA already declares, and `FOREGROUND_SERVICE`, which the worker change removes with `tools:node="remove"` because NOVA never starts a foreground service. The privacy policy has to name the two new ones and say the checks are active only while alerts are on.
- A notification is seen only if Unraid keeps it as unread (browser delivery on, the default). Users who turned that off get no alerts for that importance.
- A scheduled parity check raises one warning when it starts; "Alerts only" removes it but also drops warnings such as SMART or read errors.
- Raising a limit later (more than 300 remembered ids, more than five alerts per check) is a data-format and wording decision, not a free change.

**Constraints on other work**

- A biometric app lock (#323) must gate the UI only. If it bound the Keystore key to user authentication or to an unlocked device, background checks could no longer decrypt the key.
- Targeting Android API 37 needs the `ACCESS_LOCAL_NETWORK` permission for connections inside the home network; until then NOVA's target level (36) is unaffected. Such a target bump must treat a blocked local network as "cannot check", never as "server down".

**Trigger to revisit**

- Unraid ships a Web Push sender or a generic webhook agent (instant alerts without polling become possible).
- A widget needs a refresh of its own beyond this job.
- Users report that missing alerts for notifications that only reach the archive matter; reading the archive would then be added.

## Alternatives considered

**UnifiedPush through the user's own ntfy.** Unraid's agents send plain posts, while UnifiedPush requires RFC 8291 encrypted messages. Unraid has no such sender and the project excludes a relay or server plugin that could add one. Users who already run ntfy can get instant alerts with configuration only (Unraid's ntfy agent to their own server, read by the ntfy app); the documentation will say so.

**Firebase Cloud Messaging.** Needs Google services and a sender the project would have to run. Excluded by the project's principles.

**A foreground service holding the notification subscription open.** Needs a permanent notification, and since Android 12 a foreground service cannot be started from the background; the cost is far higher than a short periodic check.

**Raising disk and pool problems from the array data as well.** Duplicates Unraid's own notifications and doubles every alert. The allow-list of two keys, with a test that fails when it grows, keeps this a deliberate decision.

**No alerts, documentation only.** Leaves the users who asked without an answer; the opt-in design costs nothing for everyone else.

## References

- Issue #321 (alerts outside the app) and the design posted there.
- [ADR-0017](./0017-domain-split-queries-with-lifecycle-polling.md): the foreground-only polling rule this amends.
- [ADR-0035](./0035-apikey-store-decrypt-failure-semantics.md): a background check never resets keys.
- [ADR-0041](./0041-host-ssl-entry-and-self-signed-local-trust.md): background trust fails closed.
