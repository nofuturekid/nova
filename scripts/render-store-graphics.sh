#!/usr/bin/env bash
#
# render-store-graphics.sh — render the store graphics from their SVG sources:
#   fastlane/metadata/android/en-US/images/icon.svg           -> icon.png            (512x512, 32-bit PNG with alpha)
#   fastlane/metadata/android/en-US/images/featureGraphic.svg -> featureGraphic.png  (1024x500, 24-bit PNG, no alpha)
# Google Play wants exactly these (docs/store/README.md). Needs rsvg-convert,
# magick (ImageMagick) and woff2_decompress; no network. The feature graphic
# uses the project website's font (Geist, SIL OFL, site/fonts/): it is unpacked
# into a temporary directory and handed to rsvg-convert through a private
# fontconfig file, so nothing is installed and the result does not depend on
# the fonts of the machine. It also embeds phoneScreenshots/1.png and 3.png, so
# re-run it after ./scripts/store-screenshots.sh.
#
#   ./scripts/render-store-graphics.sh
#
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
IMG="${REPO}/fastlane/metadata/android/en-US/images"

for tool in rsvg-convert magick woff2_decompress; do
  command -v "${tool}" >/dev/null || { echo "ERROR: ${tool} not found on PATH" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

# Private font setup: Geist (variable font) from the website's woff2.
mkdir "${WORK}/fonts"
cp "${REPO}/site/fonts/geist-latin.woff2" "${WORK}/fonts/"
woff2_decompress "${WORK}/fonts/geist-latin.woff2" >/dev/null
rm "${WORK}/fonts/geist-latin.woff2"
cat > "${WORK}/fonts.conf" <<CONF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>${WORK}/fonts</dir>
  <cachedir>${WORK}/cache</cachedir>
</fontconfig>
CONF

# Icon: opaque pixels, but with an alpha channel (PNG32), as Google Play asks.
rsvg-convert -w 512 -h 512 "${IMG}/icon.svg" -o "${WORK}/icon.png"
magick "${WORK}/icon.png" -alpha on PNG32:"${IMG}/icon.png"

# Feature graphic: no alpha channel (PNG24).
FONTCONFIG_FILE="${WORK}/fonts.conf" rsvg-convert -w 1024 -h 500 "${IMG}/featureGraphic.svg" -o "${WORK}/feature.png"
magick "${WORK}/feature.png" -background '#0A0E0D' -alpha remove -alpha off PNG24:"${IMG}/featureGraphic.png"

file "${IMG}/icon.png" "${IMG}/featureGraphic.png"
