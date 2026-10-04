#!/usr/bin/env bash
# Render + caption the Lathe OpenJDK hero clip, bookend it with a title + end card, and encode the
# published, content-hashed docs/openjdk-demo-<hash>.gif. Run AFTER ./dev/demo-openjdk/prepare.sh
# (which copies + warms the isolated Neovim config and checks the JDK).
#
#   LATHE_OPENJDK_DIR=~/git/jdk ./dev/demo-openjdk/record.sh   ->   docs/openjdk-demo-<hash>.gif
#
# The cut: title-card -> the captioned demo beat -> end-card.
#
# GIF-first: the intermediate docs/videos/*.mp4 are regenerable (gitignored) artifacts; only the
# content-hashed docs/openjdk-demo-<hash>.gif is committed and embedded in docs/guide/openjdk.md. The
# hash in the filename busts browser/CDN caches — see the publish step below. Matches dev/demo/record.sh.
#
# This script only renders + captions + stitches + encodes; it does not build Lathe or sync the JDK.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cd "$repo"
mkdir -p docs/videos
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

tape="dev/demo-openjdk/openjdk.tape"
ass="dev/demo-openjdk/openjdk.ass"
beat_mp4="docs/videos/openjdk-demo-beat.mp4"
min_clip_s=12           # a good render is always longer than this; shorter => VHS dropped, retry

# The bookend cards are flat text over a dark background (matching the terminal), rendered from an ASS
# with no tape. Match the Maven hero demo (dev/demo/record.sh).
card_bg="0x16181d"
title_dur="2.8"
end_dur="3.0"

render_card() {
  local name="$1" dur="$2"
  echo "[demo] rendering card $name (${dur}s)"
  ffmpeg -y -f lavfi -i "color=c=$card_bg:s=1800x1080:r=25:d=$dur" \
    -vf "subtitles=dev/demo-openjdk/$name.ass" \
    -c:v libx264 -pix_fmt yuv420p -crf 20 "docs/videos/$name.mp4" >/dev/null 2>&1
}

render_beat() {
  local attempt dur
  for attempt in 1 2 3 4; do
    echo "[demo] rendering openjdk-demo beat (attempt $attempt)"
    rm -f "$beat_mp4"
    # The tape's Output is docs/videos/openjdk-demo.mp4; move it to the beat slot so the stitched
    # output can own the published name.
    vhs "$tape" >/dev/null 2>&1 || true
    [ -f docs/videos/openjdk-demo.mp4 ] && mv docs/videos/openjdk-demo.mp4 "$beat_mp4"
    if [ -f "$beat_mp4" ]; then
      dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$beat_mp4" 2>/dev/null || echo 0)"
      if awk "BEGIN{exit !(${dur:-0} > $min_clip_s)}"; then
        echo "[demo] rendered (${dur}s); burning captions"
        ffmpeg -y -i "$beat_mp4" -vf "subtitles=$ass" -c:a copy "$work/beat.mp4" >/dev/null 2>&1
        mv "$work/beat.mp4" "$beat_mp4"
        return 0
      fi
      echo "[demo] too short (${dur:-0}s <= ${min_clip_s}s) — VHS likely dropped, retrying"
    fi
  done
  echo "[demo] FAILED to render the beat after 4 attempts" >&2
  return 1
}

render_card title-card "$title_dur"
render_card end-card "$end_dur"
render_beat

echo "[demo] stitching title -> demo -> end"
: > "$work/list.txt"
for c in "docs/videos/title-card.mp4" "$beat_mp4" "docs/videos/end-card.mp4"; do
  printf "file '%s'\n" "$repo/$c" >> "$work/list.txt"
done
# Re-encode (not -c copy): the cards come from a different encoder than VHS, so their stream params
# differ and a stream-copy concat would fail. A uniform libx264 pass makes the seams gapless.
ffmpeg -y -f concat -safe 0 -i "$work/list.txt" \
  -c:v libx264 -pix_fmt yuv420p -crf 20 -r 25 docs/videos/openjdk-demo.mp4 >/dev/null 2>&1

# Derive the GIF. 1200px/dither=none keeps terminal text crisp and the flat dark background clean; a
# two-pass palette (stats_mode=diff) gives good colour on little content. Matches dev/demo/record.sh.
echo "[demo] deriving the GIF"
ffmpeg -y -i docs/videos/openjdk-demo.mp4 \
  -vf "fps=10,scale=1200:-1:flags=lanczos,palettegen=max_colors=256:stats_mode=diff" \
  "$work/palette.png" >/dev/null 2>&1
ffmpeg -y -i docs/videos/openjdk-demo.mp4 -i "$work/palette.png" \
  -lavfi "fps=10,scale=1200:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle" \
  "$work/openjdk-demo.gif" >/dev/null 2>&1

# Publish under a content-hashed name (docs/openjdk-demo-<hash>.gif) so browsers never serve a stale
# cached copy: identical bytes keep the same URL, any change gets a new one. Delete the previous
# published GIF and rewrite the embed in docs/guide/openjdk.md so the committed name is always exactly
# the current bytes. Mirrors dev/demo/record.sh.
hash="$(sha1sum "$work/openjdk-demo.gif" | cut -c1-8)"
gif="docs/openjdk-demo-$hash.gif"
for old in docs/openjdk-demo-*.gif; do
  [ -e "$old" ] && [ "$old" != "$gif" ] && rm -f "$old"
done
mv "$work/openjdk-demo.gif" "$gif"
# The guide lives in docs/guide/, so its link is relative: ../openjdk-demo-<hash>.gif.
sed -i -E "s#\(\.\./openjdk-demo(-[0-9a-f]+)?\.gif\)#(../openjdk-demo-$hash.gif)#" docs/guide/openjdk.md

echo "[demo] done: $gif (published, content-hashed); docs/guide/openjdk.md link updated"
echo "[demo] docs/videos/*.mp4 are gitignored intermediates"
