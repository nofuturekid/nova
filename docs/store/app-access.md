# Google Play App access: instructions for reviewers

Play Console, App content, App access. Google's requirement: "Provide an active demo account, login information, and all other resources needed for Google Play to review your app" (<https://support.google.com/googleplay/android-developer/answer/10788890>, requirement 3.3). Credentials and resources "must be accessible at all times, reusable, and valid regardless of user location", and instructions must be in English (<https://support.google.com/googleplay/android-developer/answer/15748846>). Up to five sets of instructions fit into the form; "Any other instructions" takes free text (<https://support.google.com/googleplay/android-developer/answer/9859455>).

## Status

NOVA is a client for the user's own Unraid server. Without a server it shows "No server configured", "Add server" and, under it, "Try the demo". Adding a server needs a host and an API key, which a reviewer does not have; the demo mode (ADR-0050) needs neither. It shows a fictional server "Tower" with fictional data, answers every action from memory and makes no network request, so there is no account, password or server to keep alive for Google.

The text below names the labels of the build that carries ADR-0050. It may be entered once that build is on the track Google reviews; check each step on that build first (the checklist at the end).

If a review is rejected because Google does not accept a demo mode instead of credentials, the fallback is a permanently reachable read-only Unraid server for reviewers (no code change, permanent exposure and upkeep); it needs its own decision.

## Text to enter (English)

```text
NOVA is a remote client for a user's own Unraid NAS server. It has no accounts, no sign-in and no in-app purchases. No credentials are needed to review it.

To use every screen with sample data:
1. Open NOVA. The first screen says "No server configured". Tap "Try the demo" (below "Add server").
2. NOVA now shows a fictional server called "Tower". A "Demo" marker is at the top right, and the Overview says that the data is fictional. The demo is fully offline: it makes no network request and needs no account or API key.
3. Browse the four tabs at the bottom (Overview, Array, Docker, VMs). The Overview shows live-looking CPU, memory, network and temperature figures. Tap the bell for notifications (unread and archived). Tap the gear for Settings; "Server plugins" and "Network interfaces" there show sample data too.
4. Everything can be tried and works against sample data kept in memory: start, stop, restart and pause containers and VMs; open a container (details and logs) and update it; start and stop the array; start, pause, resume and cancel a parity check; archive, restore and delete notifications. Changes are lost when the app is closed.
5. To leave the demo: tap the server name at the top left, then the bin icon next to "Tower" and "Remove demo". Tap "Add another server" in the same list to add a real server next to it.

Real use needs the user's own Unraid server (version 7.x) with the Unraid API (Connect plugin) enabled and an API key; the app then connects only to that server. The demo makes no network request.
```

## Before entering the text

1. Check each step above on the build that goes to Play (labels: "Try the demo", "Demo", "Remove demo").
2. Add one sentence naming the demo mode to `docs/store/play-listing/full_description.txt` (not done in the pull request that built the mode).
3. Keep the "Data safety" answer true: the demo mode must make no network request (`DemoUnraid` has no network client; `UnraidGatewayTest` checks that every demo screen and action is answered by `DemoUnraid` and that no network client is requested).
