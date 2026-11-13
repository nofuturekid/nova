# ADR-0046: Offer updates of the GitHub build as a browser download, without an in-app installer

- **Status**: Accepted (2026-11-13)
- **Date**: 2026-11-13
- **Tags**: ui, security, release, distribution

## Context

The `direct` flavor (the APK on the GitHub Releases page) has installed its own updates since the in-app updater landed (ADR-0008): it declares `REQUEST_INSTALL_PACKAGES`, downloads the APK itself and hands it to a `PackageInstaller` session. ADR-0040 moved the permission into the `direct` flavor only; the F-Droid build (`store` flavor) has neither the permission nor the installer entry point.

**What was observed (2026-11-13).** Google Play Protect stopped every current GitHub build of NOVA when it was installed or updated: v0.1.45-beta1, v0.1.45 and v0.1.46-beta1, each a new file. It happened on a real Android 9 phone, and a maintainer's Pixel on Android 17 was stopped as well. Play Protect first showed "App scan recommended"; after the user tapped "Scan app" it answered "Harmful app blocked – This app may be harmful" without naming a reason. The only way past it is "More details > Install anyway". The F-Droid build never showed any warning. Issues #306 and #307 describe what the user sees.

**What is proven.** From the phone's system log and Google's documentation:

- The warning came from Google's servers, not from the phone's own check: the on-device analysis of the APK found nothing; Google's server-side lookup then asked for a scan, and the cloud scan returned the "potentially unwanted / generic malware" verdict. The user then continued and the file was uploaded to Google.
- The first dialog ("App scan recommended") is documented behaviour for an app Play Protect has never seen: every GitHub release is a new file that is not on Google Play, so this prompt is expected for every release, whatever else is true.
- Nothing in the APK that could trigger a verdict changed between 0.1.44 and 0.1.45 (manifest and code strings differ only in version and the live-stream watchdog texts).
- Every GitHub build since the updater landed (2026-05-14) has been able to install packages.
- The verdict wording is not one of the categories Google documents, so Google's documentation does not say why it was given.

**What the A/B on the phone showed (2026-11-13).** The official CI build of this pull request is the same version (0.1.46-beta1) with the same signing key, but without `REQUEST_INSTALL_PACKAGES` and without the installer code (its manifest declares only `INTERNET` and `ACCESS_NETWORK_STATE`). It went through the same Google cloud scan 35 minutes later, on the same phone, and was rated "This app seems safe"; the system installer then also says the app needs no special access. The new flow was tried on the phone as well: the banner and the Settings button, the sheet with notes, size and SHA-256, Download (opens the release APK in the browser) and Release page (opens the release page) all work.

This shows the verdict changed when the permission and the installer code were removed. It does not prove they are the only factor: one phone, one day, one Play Store version, and Google's classifier can change. The "App scan recommended" step itself remains for every new file.

**Supporting, not proof for NOVA.** Another open-source app with a built-in updater (OpenAmpere) received the identical verdict and found in an A/B test on a Google Play emulator image that the permission alone was enough (with the permission renamed and nothing else changed, the build was rated "looks safe"); its release without the updater installs without a warning. Google's Play policy also names apps that update themselves outside Google Play as what the permission is restricted for. Why the warning appeared only now is open; other projects report the same verdict since late October 2026, which may point to a change on Google's side.

## Decision

Remove the ability to install packages from the `direct` flavor, and keep only the update _notice_.

- Keep the update check: latest release, the "Include pre-releases" switch, the "Update available" banner and sheet with version, size and release notes.
- Replace "Install" with **Download**, which opens the release's APK URL in the browser (`ACTION_VIEW`), and a second, smaller **Release page** link. A release without an APK asset is still announced; its single action opens the release page.
- The sheet says that NOVA does not install updates itself, that the browser downloads the file and the user opens it to install, and that Android may ask to allow the browser or file manager to install apps and Play Protect may ask to scan the new version first. It shows the SHA-256 that GitHub lists for the asset (when the API provides one) so the user can compare it.
- Remove `REQUEST_INSTALL_PACKAGES` and guard its absence: the main manifest carries a `tools:node="remove"` entry so no flavor and no library can bring it back, and a unit test fails if a manifest declares it or the entry is dropped. Also remove (the now empty `app/src/direct/AndroidManifest.xml`), the `PackageInstaller` session, the status receiver, the permission request flow, the in-app APK download with its cache handling, and their tests and texts. No feature flag: the capability is gone from the code, not hidden.
- The `store` flavor is unchanged in behaviour (`BuildConfig.HAS_UPDATER` stays `false`, no update UI, no GitHub requests). Because the installer classes lived in `main`, they also disappear from the `store` binary, where they were dormant.

This supersedes the install part of ADR-0008 (permission, installer, receiver, permission flow) and the `direct`-flavor permission in ADR-0040, and replaces the verification in ADR-0034 #1 (the app no longer handles the file; it can only show the checksum). What remains of ADR-0034 #1 is the HTTPS rule, tightened: the browser is only sent to HTTPS links under `https://github.com/hellwand/nova/releases/`, anything else is an error.

## Consequences

- **Positive.** The build without the permission was rated safe where the build with it was blocked, so new GitHub releases should no longer be rated harmful, and the in-app message that users cannot act on (#307) and the permission round trip (#306) disappear with the code that produced them. The `direct` flavor gets smaller and the two flavors differ less. The app can no longer be made to install anything.
- **Negative / trade-offs.**
  - No one-tap update any more: download in the browser, open the file, confirm, and possibly allow the browser or file manager to install apps once.
  - The app no longer verifies the downloaded file; it can only show the checksum. The file is still protected by HTTPS, by the link check above and by Android refusing an update signed with a different key.
  - Users of versions up to 0.1.46 still have the old updater. Their update to the first release with this change is installed by the old code and may show the Play Protect warning one last time.
  - If Google rates a later build as harmful again, the one-tap update was given up for nothing. The "App scan recommended" prompt for a never-seen file stays either way, and users may mistake it for a failed fix; the sheet, the README and the website say it is expected.
- **Trigger to revisit.** Revisit if a release built from this code is flagged "harmful" again (then the cause is elsewhere and the updater with a better failure text from #307 is worth keeping), or if Google documents a way to register sideloaded apps or NOVA is distributed through a store that updates it (then the in-app installer or a direct link can be reconsidered).

## Alternatives considered

- **Keep the in-app updater and show a better message (#307).** Cheapest, and fine if the lead is wrong. Rejected, because users still hit the "harmful" verdict first and the message only explains how to click through it; it stays the fallback if a release of this code is flagged again.
- **Appeal to Google.** The form asks for a public VirusTotal upload, is answered with no reply, and peers report that the verdict is computed again for every new file, so a new release is flagged again. Not rejected forever, but not a fix.
- **Recommend F-Droid only.** The F-Droid build has neither the permission nor the installer and never showed a warning, but it has stable releases only, a different signing key (switching means uninstalling and losing the saved servers), and F-Droid builds of other apps have been flagged for other reasons. Remains available as a hint next to the browser download (README and website).
- **Hand the file to the system installer with `ACTION_INSTALL_PACKAGE` instead of a session.** Android requires the same permission for that intent, so it would keep the suspected trigger with a worse flow.
- **Download with `DownloadManager` and open the file from the download notification.** Needs no permission, but is more code, differs between phone makers, and NOVA cannot check what the user opens. A possible refinement of this decision later.
- **Change TLS handling, cleartext, shrinking, package name or signing key.** No evidence that any of them matters (the F-Droid build contains the same code and is not flagged), and a package rename would make NOVA a new app for every user.

## References

- Issues #306 and #307 (what the user sees on a blocked in-app update).
- ADR-0008 (in-app updater), ADR-0034 (update integrity), ADR-0040 (distribution flavors).
- Google, Play Protect client protections: <https://developers.google.com/android/play-protect/client-protections>
- Google, warning guidance for developers: <https://developers.google.com/android/play-protect/warning-dev-guidance>
- Google, Play policy on `REQUEST_INSTALL_PACKAGES`: <https://support.google.com/googleplay/android-developer/answer/12085295>
- OpenAmpere, same verdict and A/B test: <https://github.com/Gr33ndev/OpenAmpere/issues/608>
