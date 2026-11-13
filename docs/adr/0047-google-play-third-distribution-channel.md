# ADR-0047: Google Play as a third distribution channel

- **Status**: Accepted (2026-11-13)
- **Date**: 2026-11-13
- **Tags**: release, distribution, signing, legal

## Context

NOVA ships through two channels: the signed APK on GitHub Releases (`direct` flavor) and F-Droid (`store` flavor, built and signed by F-Droid). ADR-0040 split the two flavors and left Google Play as "a separate later track reusing the same `store` flavor". On 2026-11-13 the maintainer decided that NOVA goes to Google Play (issue #328). The plan behind this ADR (Google's policies and pages read on the same day) found:

- **Signing.** For a new app Google enrols Play App Signing and generates the app signing key by default. A developer "can change this default before a release is rolled out in the open testing or production track" and choose "Provide a copy of your app signing key"; the transfer goes through Google's PEPK tool, and custom keys "must be RSA 2048-bit or higher". The upload key is a separate key that the developer keeps and can reset if lost; "your upload key and app signing key should be different". A self-managed app signing key "cannot be reset if you manage it yourself and lose it" (<https://support.google.com/googleplay/android-developer/answer/9842756>).
- **Updates across channels.** Android accepts an update only from the same signing certificate, and a `versionCode` that is higher or equal (<https://developer.android.com/google/play/app-updates>). Both flavors share the `versionCode`, so builds with one signer can update each other in place.
- **Policy.** Play forbids an app that "may not modify, replace, or update itself using any method other than Google Play's update mechanism" (<https://support.google.com/googleplay/android-developer/answer/9888379>). The `store` flavor has no updater (`BuildConfig.HAS_UPDATER` false, `app/build.gradle.kts:31-33`) and no flavor declares `REQUEST_INSTALL_PACKAGES` (ADR-0046).
- **Review.** Google requires "an active demo account, login information, and all other resources needed for Google Play to review your app" (<https://support.google.com/googleplay/android-developer/answer/10788890>). NOVA is useless without an Unraid server and shows only "No server configured" (`SnapshotStates.kt:119`).
- **Format.** New apps must publish as an Android App Bundle (<https://developer.android.com/guide/app-bundle>). Nothing builds one yet.

ADR-0040 contains two statements that this does not match. Its "Signing" paragraph says Play "re-signs via Play App Signing (Google holds the upload key once enrolled)": the developer holds the upload key, Google holds the app signing key. And it accepts that a GitHub-direct user must uninstall and reinstall to switch to a store, because "both stores re-sign the APK with a different key". That is true of F-Droid, and of Play only if Google generates the key.

## Decision

1. **NOVA is listed on Google Play.**
2. **The `store` flavor is the Play build,** published as an Android App Bundle (`:app:bundleStoreRelease`). Same `applicationId` (`io.github.nofuturekid.nova`), same `versionCode` as the other channels. No third flavor.
3. **The Play build carries the existing release signing key.** The maintainer gives Google a copy of the release key (the one whose certificate SHA-256 starts with `9f86baa1`, the pin in `.github/scripts/release-verify-apk.sh`) through Play App Signing, **before any open-testing or production rollout** (after that the choice cannot be made any more). A **separate upload key**, new and kept in the `release` environment, signs the bundles that are uploaded. The upload key is registered with Play; the private key of the release key stays with the maintainer, who keeps an offline backup.
4. **Reviewers get an in-app demo mode** instead of credentials: an entry on the "No server configured" screen that shows the fictional data the store screenshots already use, without any network request. It changes the APK in every flavor, including F-Droid, and is built and released as an ordinary beta cycle (separate issue). Until it ships, the App-access answer cannot be written (`docs/store/app-access.md` holds the text with placeholders).
5. **The listing material lives in this repository.** Images in `fastlane/metadata/android/en-US/images/` (shared with F-Droid), Play-only texts and the declarations in `docs/store/` (see `docs/store/README.md`). The Play title is `NOVA for Unraid®`; the attribution of Lime Technology's trademark policy sits in the description.
6. **Not decided here:** the app-bundle CI job and its signing (it must fit the tag-time signing design of #295 and needs a spike), the Play account and trader declaration (the maintainer's), the demo mode's design, and whether to register F-Droid's signing key for developer verification.

## Corrections to ADR-0040

- "Google holds the upload key once enrolled" is wrong: the developer holds the upload key, Google the app signing key. Under this decision Google holds a copy of the existing release key as app signing key.
- The "uninstall and reinstall once" migration no longer applies between **GitHub and Play**: both carry the same signer. It still applies between **F-Droid and either of the others**, because F-Droid signs with its own key.

An amendment line in ADR-0040 points here; its text is not rewritten.

## Consequences

- **Positive.** GitHub users who meet the Play Protect warning (ADR-0046) can move to Play without losing their servers, and the other way round, in place. One signer for GitHub and Play keeps developer verification to one identity for those two. If the Play account is ever terminated, Play users can move to the GitHub build in place.
- **Negative / trade-offs.** Google holds a copy of the key that also signs GitHub builds; the export is a one-time operation on the maintainer's machine that handles the private key. Losing that key cannot be undone, and it would also block the registration of packages (developer verification, <https://developer.android.com/developer-verification/guides/faq>): an offline backup is required. Whether quantum-ready hybrid signing applies to an uploaded key is not stated by Google. The demo mode is an APK change in every flavor and holds the Play review until it ships. A personal Play account must first run a closed test with at least 12 testers for 14 days before it can apply for production (<https://support.google.com/googleplay/android-developer/answer/14151465>).
- **Trigger to revisit.** (a) Google rejects the demo mode as a substitute for credentials: add a permanently reachable reviewer server or amend this ADR. (b) Google's requirements for app signing or bundle uploads change before the first rollout. (c) The Play account is suspended or the key is compromised: the signing key must then be rotated, which breaks in-place updates in every channel at once.

## Alternatives considered

- **Google-generated app signing key (the default).** Safest for the key (Google holds it, quantum-ready hybrid signing), but Play installs get a different signer from GitHub builds: switching between GitHub and Play means uninstalling and losing app data, and developer verification would need the GitHub key registered separately. Rejected because NOVA's users move between GitHub and the stores.
- **Google-generated key, plus distributing Play's universal APK on GitHub.** Would change the signer of the GitHub build and break every existing GitHub install, because updates need the same certificate. Not viable.
- **A permanently reachable reviewer Unraid server instead of a demo mode.** No code change, but permanent internet exposure and upkeep, and a failing login is a rejection reason. Kept as the fallback; the demo mode also helps new users.
- **Organisation account.** Needs a D-U-N-S number and is meant for a business; a private person without one uses a personal account.

## References

- Issue hellwand/nova#328 (decision and plan).
- ADR-0038 and ADR-0039 (trademark compliance, name `NOVA for Unraid®`), ADR-0040 (flavors; amended), ADR-0046 (no install permission).
- Google: Play App Signing <https://support.google.com/googleplay/android-developer/answer/9842756>; listing assets <https://support.google.com/googleplay/android-developer/answer/9866151>; metadata policy <https://support.google.com/googleplay/android-developer/answer/9898842>; App access <https://support.google.com/googleplay/android-developer/answer/15748846>; closed-test requirement <https://support.google.com/googleplay/android-developer/answer/14151465>.
- Lime Technology, Third-Party App Naming and Trademark Use Policy (effective 2025-10-01): <https://unraid.net/policies>.
- [Store listing material](../store/README.md).
