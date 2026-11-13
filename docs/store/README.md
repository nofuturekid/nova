# Store listings

Everything the project prepares for its app-store listings that is not app code. Two stores read from here and from `fastlane/`:

| Store                      | Reads                                                                                                       | Notes                                                                                                                     |
| -------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| F-Droid                    | `fastlane/metadata/android/en-US/` (texts, `images/`, `changelogs/`), from the tag it builds                 | Unchanged by the Google Play work except that the images now also contain `featureGraphic.png` (F-Droid shows it).         |
| Google Play (not live yet) | the texts in [`play-listing/`](./play-listing/) and the declarations in this folder; images from `fastlane/` | Entered by hand in Play Console. [ADR-0047](../adr/0047-google-play-third-distribution-channel.md) records the decision. |

## What lives where

| File                                                                                                                                      | Content                                                                                      |
| ----------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `fastlane/metadata/android/en-US/images/icon.png`, `icon.svg`                                                                             | 512x512 32-bit PNG with alpha (Play) and its source                                          |
| `fastlane/metadata/android/en-US/images/featureGraphic.png`, `featureGraphic.svg`                                                         | 1024x500 24-bit PNG, no alpha (Play) and its source                                          |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` to `6.png`                                                                | 1080x1920 phone screenshots (F-Droid and Play)                                               |
| [`play-listing/title.txt`](./play-listing/title.txt), [`short_description.txt`](./play-listing/short_description.txt), [`full_description.txt`](./play-listing/full_description.txt) | Texts for Play Console                                                                       |
| [`data-safety.md`](./data-safety.md)                                                                                                       | Data safety form answers, derived from the `store` flavor's code                              |
| [`content-rating.md`](./content-rating.md)                                                                                                 | Content rating questionnaire answers                                                          |
| [`app-content.md`](./app-content.md)                                                                                                       | Target audience, ads and the other App content declarations                                  |
| [`app-access.md`](./app-access.md)                                                                                                         | Instructions for Google's reviewers (blocked on the demo mode)                                |

## Why Play gets its own texts

F-Droid's build recipe for NOVA (`metadata/io.github.nofuturekid.nova.yml` in fdroiddata, read 2026-11-13) has `AutoName: NOVA` and no `Summary` or `Description`, so F-Droid takes the listing from `fastlane/metadata/android/en-US/` of the tag it builds. Texts for Play cannot live there without changing F-Droid's listing. They differ in three ways:

- **Title.** Play: `NOVA for Unraid®` (16 characters; the `[YourApp] for Unraid®` form of ADR-0038 and ADR-0039). F-Droid keeps `NOVA`, which is the app's name there.
- **Attribution.** Lime Technology's policy (section 6, <https://unraid.net/policies>, read 2026-11-13) wants the attribution near the first mention of "Unraid" in each medium, and lets the listing description carry the short form. The Play full description opens with the short form and ends with the long form, both verbatim from the policy. The F-Droid description still carries the older wording ("... endorsed by, or supported by Lime Technology"), as do the app's About screen (`SettingsScreen.kt`) and `docs/privacy/POLICY.md`; aligning them is a separate change (the app one needs an APK build).
- **Claims.** The F-Droid text says "no third-party services involved". That is true of NOVA's own traffic, but container icons come from hosts the user's server names (ADR-0040, amendment 2026-11-10), so the Play text says "no NOVA account, no analytics and no advertising" instead.

Limits (Google, read 2026-11-13): title 30 characters and short description 80 characters are stated on the [metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842) and the [listing assets page](https://support.google.com/googleplay/android-developer/answer/9866151). The full description is limited to 4000 characters ([Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152), "Add product details": app name 30, short description 80, full description 4,000). The metadata policy only asks for a "succinct and straightforward" description with no keyword stuffing, no rankings, no "best" or promotional claims, no emojis or repeated special characters. Check the lengths and the banned words with:

```bash
cd docs/store/play-listing
for f in title short_description full_description; do printf '%s: ' "$f"; python3 -c "print(len(open('$f.txt', encoding='utf-8').read().rstrip('\n')))"; done
grep -n -i -E '\b(free|best|#1|top|number one)\b' *.txt   # must print nothing
```

Current values: title 16, short description 74, full description 1747.

When the Play listing goes live, add one line to `full_description.txt` that names the demo mode (see `app-access.md`), and change the future-tense mention of Google Play in `docs/privacy/POLICY.md` (line "and, in the future, the Google Play Store").

## Rendering the graphics

```bash
./scripts/store-screenshots.sh        # re-render the phone screenshots (Docker, as local-ci does)
./scripts/render-store-graphics.sh    # icon.png and featureGraphic.png from their SVG sources
```

The second script needs `rsvg-convert`, `magick` and `woff2_decompress`, uses the website's Geist font (SIL OFL, `site/fonts/`) through a private fontconfig file, and embeds `phoneScreenshots/1.png` and `3.png` into the feature graphic, so run it after the screenshots.

### Google's numbers (read 2026-11-13)

- Icon: 512x512, "32-bit PNG (with alpha)", at most 1024 KB. The existing icon was RGB without alpha and is now RGBA.
- Feature graphic: 1024x500, "JPEG or 24-bit PNG (no alpha)".
- Phone screenshots: JPEG or 24-bit PNG (no alpha), 2 to 8 per device type, each side 320 to 3840 px, and "the maximum dimension of your screenshot can't be more than twice as long as the minimum dimension". The earlier 1078x2399 screenshots (ratio 2.23) broke that rule.
- Screenshot size, chosen: **1080x1920**, 432x768 dp at 400 dpi (ratio 1.78, the 9:16 portrait shape at the 1080x1920 minimum that Google's listing assets page gives for screenshot-based promotion formats). 1080x2160 would pass the rule at exactly 2:1 with no margin and is not Google's recommended shape; F-Droid has no size limit, so one size serves both. The screenshots are 24-bit RGB without alpha.

### Still to do before the listing goes live

- Re-render the screenshots (and run `render-store-graphics.sh` again) whenever a visible screen changes before the listing goes live.
- `featureGraphic.png` is new in the F-Droid image folder. F-Droid's `fdroid update` ignores the two `.svg` sources next to it (not tested here: no F-Droid tooling on this machine).
