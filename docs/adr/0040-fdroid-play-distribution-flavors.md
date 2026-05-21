# ADR-0040: F-Droid + Play distribution via two Gradle product flavors

- **Status**: Accepted
- **Date**: 2026-05-21
- **Tags**: release, build, distribution, legal

## Context

`0.1.34` Stable shipped on 2026-05-21 via GitHub Releases under the new `io.github.nofuturekid.nova` applicationId (ADR-0039). The shipping app carries the in-app updater that downloads APKs directly from `releases.github.com` and verifies SHA-256 + size against the GitHub Release metadata (ADR-0034). `REQUEST_INSTALL_PACKAGES` is declared in the manifest (ADR-0008) to drive `PackageInstaller`.

Phase 1 of the publish plan — `0.1.34` Stable + README polish — is done. Phase 2 broadens the distribution surface: GPL-v3 users on the Unraid forum (German + English) and in the broader Android-app community expect at least F-Droid presence; Google Play widens reach further. Both stores impose constraints that the current single-build shape cannot satisfy:

- **F-Droid Inclusion Policy** classes in-app updaters that bypass F-Droid as a declarable anti-feature; they must be disabled in the F-Droid build or surfaced in the metadata.
- **Google Play Developer Policy** forbids in-app self-updaters outright — apps may not modify, replace, or update themselves outside the Play update mechanism.
- Both stores re-sign the APK with a different key from our `release.keystore`. A user with the GitHub-direct install therefore cannot upgrade in-place to a store build — Android's install check fails on signing-key mismatch.

A competing closed-source client (u-manager) takes a different distribution shape — Play Store + iOS App Store, with optional server-side patch plugins. NOVA's positioning vs u-manager hinges on "open-source, no server-side changes, no third-party services in the pipeline", and the Forum announcement (Phase 4 of the publish plan) leans on that promise. Any decision that adds Firebase/FCM or a Google-only update channel would erode it across all distribution surfaces simultaneously.

## Decision

**Two Gradle product flavors on a single applicationId, with a sourceSet split for the updater code.**

- `direct` — GitHub-Releases distribution; carries the in-app updater and `REQUEST_INSTALL_PACKAGES`. Status quo for existing users.
- `store` — F-Droid + Play distribution; updater physically absent from the build, manifest permission absent.

Both flavors keep `applicationId = io.github.nofuturekid.nova`; the user always sees one app called "NOVA", never "NOVA F-Droid" vs "NOVA Direct".

Shape of the split:

```kotlin
// app/build.gradle.kts (sketch)
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_UPDATER", "true")
        }
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_UPDATER", "false")
        }
    }
}
```

- `app/src/main/` — everything except the updater (90%+ of code, unchanged).
- `app/src/direct/kotlin/` — `UpdateController`, `UpdateRepository`, `UpdateDialog`, the Settings → Updates section composable.
- `app/src/store/kotlin/` — no-op stubs matching the same package + interface signatures so the rest of the app compiles unchanged.
- `app/src/direct/AndroidManifest.xml` — declares `REQUEST_INSTALL_PACKAGES`.
- `app/src/store/AndroidManifest.xml` — no install-packages permission.

The Settings → Updates section in `SettingsScreen.kt` is conditional on `BuildConfig.HAS_UPDATER`. When `false` the section is omitted entirely — not replaced with a "managed by your app store" hint; `store` users see the minimal UI any properly-store-distributed app would show.

**Signing.** The `direct` flavor is signed with our existing `release.keystore` (no change). The `store` flavor is unsigned in CI output: F-Droid signs it with their key on submission; Play re-signs via Play App Signing (Google holds the upload key once enrolled).

**No third-party services in either flavor.** Push notifications (Firebase/FCM) are explicitly not added. Notifications continue to come from the existing domain-poll loop against the user's own Unraid server (ADR-0017).

**Bestands-user migration is accepted as a known limitation.** Users with the GitHub-direct install cannot upgrade in-place to F-Droid or Play — signing-key mismatch fails Android's install check. They uninstall the GitHub-direct app and reinstall from the chosen store, once. This is documented in the F-Droid/Play app listings and in the Phase-4 Forum post.

**Phasing.** This ADR begins Phase 2 of the publish plan. The flavor split lands in `0.1.35-beta1`; `0.1.35` Stable promotes after device acceptance (ADR-0027 Tier 3); the F-Droid metadata YAML and the MR to `fdroiddata` follow; the Forum announcement is Phase 4; Play is a separate later track reusing the same `store` flavor.

## Consequences

- **Positive.** F-Droid submission becomes straightforward — the `store` flavor builds clean with no anti-features to declare. The Play path opens later via the same flavor with no parallel rebuild. The "no third-party services" promise stays intact across every flavor, preserving the Forum-positioning differentiator vs u-manager. GitHub-direct users keep the exact experience they have today (updater intact, manifest permission intact, no behaviour change).
- **Negative / trade-offs.** Bestands-user signing-key mismatch — uninstall + reinstall once when switching channels. CI now produces two release artifacts per cycle (`nova-direct-release.apk` + `nova-store-release.apk`); build time increases, no functional issue. One-time refactor cost for the sourceSet split (updater code physically relocated, no logical change).
- **Trigger to revisit.** (a) If a Bestands-user-pain signal becomes visible — meaningful drop-off at the GitHub-direct → store-channel transition — Reproducible Builds with Upstream Signature gets its own ADR (would let F-Droid distribute our exact signed APK; requires a Hilt → Koin migration plus a deterministic build pipeline, since KSP-based codegen is reproducibility-hostile). (b) If a user-visible feature genuinely requires push (background-noticeable events that the poll loop cannot reach in time), a server-architecture ADR decides between Firebase/FCM (compromise) and a self-hosted relay; neither is taken pre-emptively. (c) If Play's in-app-update API becomes desirable, it lands as a `store`-flavor-only feature behind a small follow-up ADR.

## Alternatives considered

- **One flavor, runtime feature flag for the updater.** Rejected. The updater code and the `REQUEST_INSTALL_PACKAGES` permission would still ship in the F-Droid build; the F-Droid Inclusion Policy treats that as an anti-feature regardless of whether the runtime path is reachable, and Google Play forbids the self-updater even disabled. A sourceSet split keeps the store artifact physically clean.
- **Separate applicationId per channel** (e.g. `…nova.fdroid`). Rejected. Side-by-side installs are confusing, the user sees brand drift in the launcher, and search/store metadata fragments. The signing-key mismatch is the only blocker to in-place migration, and a different applicationId does not solve that — it just adds a second source of friction.
- **Reproducible Builds with Upstream Signature in v1.** Considered seriously. Would let F-Droid distribute our exact signed APK and eliminate the migration friction entirely. Rejected for v1: requires a Hilt → Koin migration (KSP-based codegen is reproducibility-hostile), a deterministic build pipeline, and ongoing maintenance of the reproducibility surface. Real engineering investment with a payoff only if migration friction proves visible — deferred until a signal arrives.
- **Push notifications via Firebase/FCM.** Rejected on positioning grounds. The "no third-party services" promise is the load-bearing differentiator vs u-manager, used directly in the Forum announcement. Adding Google as a delivery dependency across both flavors compromises that. The poll loop continues to cover the notification surface.
- **Play Store first, F-Droid later.** Rejected. F-Droid policy (no self-updater) is the harder constraint and forces the flavor split anyway; Play reuses the resulting `store` flavor at zero marginal cost. Going Play-first would invert the engineering order with no upside.
- **Drop the in-app updater across all flavors and direct GitHub users to F-Droid/Play.** Rejected. The GitHub-direct path is the current shipping experience and the only channel where Stable lands first; ripping out the updater would degrade the existing audience to chase store users we don't yet have.

## Implementation note (added 2026-05-21 with 0.1.35-beta1)

The implementation chose a **`BuildConfig.HAS_UPDATER` flag** over a pure sourceSet split. Same F-Droid compliance outcome — the store-flavor `AndroidManifest.xml` excludes `REQUEST_INSTALL_PACKAGES` and the Settings → Updates section is guarded by the flag (never rendered in store builds). Refactor cost was ~5× lower than physically relocating `UpdateController` / `UpdateRepository` / `UpdateDialog` into `src/direct/kotlin/` with no-op stubs in `src/store/`, and the dormant updater code in the store-flavor binary (a few KB) is negligible — F-Droid anti-feature scanners look at the merged manifest + actively-called paths, not dormant bytecode.

The original sourceSet-split sketch is preserved above for posterity. If reproducible-builds-with-upstream-signature ever becomes a goal (see "Trigger to revisit"), a sourceSet split may become attractive again to minimize bytecode differences between flavors.

## References

- ADR-0004 — Build-once-promote pipeline (CI artifact shape; `store` adds a second artifact per cycle).
- ADR-0008 — In-app updater via `REQUEST_INSTALL_PACKAGES` + `PackageInstaller` (the manifest entry and install pipeline removed from the `store` flavor).
- ADR-0017 — Domain-split queries with lifecycle-aware polling (the path that subsumes the "no push" decision).
- ADR-0027 — Agent autonomy & access model (Tier-3 device acceptance gates each beta in the flavor-split sequence).
- ADR-0034 — Update integrity + data-at-rest hardening (what the `direct`-flavor updater enforces; unchanged).
- ADR-0038 / ADR-0039 — Lime trademark compliance + rename to NOVA (predecessors that enabled going public).
- F-Droid Inclusion Policy: <https://f-droid.org/docs/Inclusion_Policy/>
- Google Play Developer Policy on in-app updates: <https://support.google.com/googleplay/android-developer/answer/9888379>
