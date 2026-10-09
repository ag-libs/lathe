#!/usr/bin/env bash
# Turn the raw recording (record.sh) into the published GIF: burn captions, add title/end cards,
# encode a two-pass-palette GIF, publish it under a content-hashed name, and rewrite the embed in
# docs/guide/editors/zed.md. Same convention as the Emacs/Neovim/OpenJDK demos.
#
#   ./dev/demo-zed/render.sh      # needs docs/videos/zed-tour.mp4
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
videos="$repo/docs/videos"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

[ -f "$videos/zed-tour.mp4" ] || { echo "[demo] run ./dev/demo-zed/record.sh first" >&2; exit 1; }

echo "[demo] burning captions…"
ffmpeg -loglevel error -y -i "$videos/zed-tour.mp4" -vf "subtitles=$here/tour.ass" \
  -c:v libx264 -pix_fmt yuv420p -crf 20 "$videos/zed-tour-captioned.mp4"

echo "[demo] rendering title/end cards…"
ffmpeg -loglevel error -y -f lavfi -i "color=c=0x16181d:s=1600x900:r=25" -t 2.6 \
  -vf "subtitles=$here/title-card.ass" -c:v libx264 -pix_fmt yuv420p -crf 20 "$videos/zed-title.mp4"
ffmpeg -loglevel error -y -f lavfi -i "color=c=0x16181d:s=1600x900:r=25" -t 3.0 \
  -vf "subtitles=$here/end-card.ass" -c:v libx264 -pix_fmt yuv420p -crf 20 "$videos/zed-end.mp4"

echo "[demo] stitching…"
printf "file '%s'\n" "$videos/zed-title.mp4" "$videos/zed-tour-captioned.mp4" "$videos/zed-end.mp4" \
  >"$tmp/list.txt"
ffmpeg -loglevel error -y -f concat -safe 0 -i "$tmp/list.txt" -c:v libx264 -pix_fmt yuv420p -crf 20 -r 25 \
  "$videos/zed-tour-final.mp4"

echo "[demo] encoding GIF…"
ffmpeg -loglevel error -y -i "$videos/zed-tour-final.mp4" \
  -vf "fps=10,scale=1000:-1:flags=lanczos,palettegen=max_colors=256:stats_mode=diff" "$tmp/pal.png"
ffmpeg -loglevel error -y -i "$videos/zed-tour-final.mp4" -i "$tmp/pal.png" \
  -lavfi "fps=10,scale=1000:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle" \
  "$tmp/zed-tour.gif"

hash="$(sha1sum "$tmp/zed-tour.gif" | cut -c1-8)"
for old in "$repo"/docs/zed-tour-*.gif; do
  [ -e "$old" ] && [ "$old" != "$repo/docs/zed-tour-$hash.gif" ] && rm -f "$old"
done
mv "$tmp/zed-tour.gif" "$repo/docs/zed-tour-$hash.gif"
sed -i -E "s#\(\.\./\.\./zed-tour(-[0-9a-f]+)?\.gif\)#(../../zed-tour-$hash.gif)#" "$repo/docs/guide/editors/zed.md"
echo "[demo] published docs/zed-tour-$hash.gif"
