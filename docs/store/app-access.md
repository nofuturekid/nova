# Google Play App access: instructions for reviewers

Play Console, App content, App access. Google's requirement: "Provide an active demo account, login information, and all other resources needed for Google Play to review your app" (<https://support.google.com/googleplay/android-developer/answer/10788890>, requirement 3.3). Credentials and resources "must be accessible at all times, reusable, and valid regardless of user location", and instructions must be in English (<https://support.google.com/googleplay/android-developer/answer/15748846>). Up to five sets of instructions fit into the form; "Any other instructions" takes free text (<https://support.google.com/googleplay/android-developer/answer/9859455>).

## Status: blocked on the demo mode

NOVA is a client for the user's own Unraid server. Without a server it shows only "No server configured" and "Add server" (`SnapshotStates.kt:119,130`), and adding a server needs a host and an API key. A reviewer has neither. ADR-0047 decides to give reviewers a **demo mode** inside the app, backed by the fictional data the store screenshots already use (`StoreScreenshots.kt`, server "Tower"). **That demo mode does not exist yet** (APK change in every flavor, tracked separately). Until it ships and is on the track Google reviews, the text below cannot be entered: a placeholder in the form, or a promise of something the build does not have, is a rejection reason ("If the provided password expires ... the app may be rejected", Google, answer 15748846).

If a review is rejected because Google does not accept a demo mode instead of credentials, the fallback is a permanently reachable read-only Unraid server for reviewers (no code change, permanent exposure and upkeep); it needs its own decision.

## Text to enter (English; fill the placeholders when the demo mode ships)

```text
NOVA is a remote client for a user's own Unraid NAS server. It has no accounts, no sign-in and no in-app purchases. No credentials are needed to review it.

To look at the app with sample data:
1. Open NOVA. The first screen says "No server configured".
2. [PLACEHOLDER, demo mode not built yet: tap "<label of the demo entry>" on that screen.]
3. [PLACEHOLDER: describe what the demo shows, e.g. a sample server "Tower" with an array, Docker containers, virtual machines and notifications, all fictional and fully offline.]
4. Browse the four tabs at the bottom (Overview, Array, Docker, VMs) and tap the bell for notifications. [PLACEHOLDER: state which actions the demo accepts and which are disabled.]

Real use needs the user's own Unraid server (version 7.x) with the Unraid API (Connect plugin) enabled and an API key; the app then connects only to that server. The demo makes no network request.
```

## When the demo mode ships

1. Replace every `[PLACEHOLDER ...]` above with the real labels, taken from the build that goes to Play, and check each step on that build.
2. Add one sentence naming the demo mode to `docs/store/play-listing/full_description.txt`.
3. Re-render the screenshots if the demo entry changes the empty state, and keep the "Data safety" answer true: the demo mode must make no network request.
