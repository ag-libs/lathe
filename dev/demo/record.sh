#!/usr/bin/env bash
# Re-record the Lathe demo (the published highlight cut) from committed sources
# (dev/demo/beats/*.tape + *.ass). Run AFTER ./dev/demo/prepare.sh, which rebuilds+installs Lathe
# (incl. the debug-wired server the debug beat needs), rebuilds the multi-module invoker fixture, and
# copies the sample config examples/nvim into dev/demo/.nvim. This script only renders + captions + stitches; it
# does not rebuild Lathe.
#
#   ./dev/demo/prepare.sh
#   ./dev/demo/record.sh          ->  docs/demo-<hash>.gif (the published, committed artifact)
#
# The cut (≈64 s): a title card, then the five universal beats, then an end card.
#
#   title-card → 0 capture → 1 everyday editing → 4 run → 5 debug → 6 test → end-card
#
# Beats 2 (annotation processing) and 3 (JPMS module graph) are retained under dev/demo/beats/ as an
# optional deep-dive but are deliberately NOT in this cut — they are the longest, most niche beats and
# they trip VHS's non-initial-buffer 2x zoom. Add them back to BEATS below to rebuild the full tour.
#
# GIF-first: the per-clip docs/videos/*.mp4 and the stitched docs/demo.mp4 are regenerable
# intermediates (gitignored); only the content-hashed docs/demo-<hash>.gif is committed and embedded
# in the README. The hash in the filename busts browser/CDN caches — see the publish step below.
#
# Each beat tape re-runs `mvn clean test -Dlathe.capture.only=true` (build cache off) in its hidden
# setup, so .lathe is captured fresh with the currently-installed Lathe on every render.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cd "$repo"
mkdir -p docs/videos
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

# The bookend cards are flat text over a dark background (matching the terminal), rendered from an ASS
# with no tape. The beats are VHS tapes, captioned in place. Cards first/last; beats in the middle.
card_bg="0x16181d"
title_dur="2.8"
end_dur="3.0"
beats=(beat0-capture beat1-edit beat4-run beat5-debug beat6-test)
order=(title-card "${beats[@]}" end-card)
min_clip_s=6            # a good render is always longer than this; shorter => VHS dropped, retry

render_card() {
  local name="$1" dur="$2"
  echo "[demo] rendering card $name (${dur}s)"
  ffmpeg -y -f lavfi -i "color=c=$card_bg:s=1800x1080:r=25:d=$dur" \
    -vf "subtitles=dev/demo/beats/$name.ass" \
    -c:v libx264 -pix_fmt yuv420p -crf 20 "docs/videos/$name.mp4" >/dev/null 2>&1
}

render_beat() {
  local base="$1"
  local tape="dev/demo/beats/$base.tape"
  local ass="dev/demo/beats/$base.ass"
  local out="docs/videos/$base.mp4"
  local attempt
  for attempt in 1 2 3 4; do
    echo "[demo] rendering $base (attempt $attempt)"
    rm -f "$out"
    vhs "$tape" >/dev/null 2>&1 || true
    if [ -f "$out" ]; then
      local dur
      dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out" 2>/dev/null || echo 0)"
      if awk "BEGIN{exit !(${dur:-0} > $min_clip_s)}"; then
        echo "[demo] captioning $base (${dur}s)"
        ffmpeg -y -i "$out" -vf "subtitles=$ass" -c:a copy "$work/$base.mp4" >/dev/null 2>&1
        mv "$work/$base.mp4" "$out"
        return 0
      fi
      echo "[demo] $base too short (${dur:-0}s <= ${min_clip_s}s) — VHS likely dropped, retrying"
    fi
  done
  echo "[demo] FAILED to render $base after 4 attempts" >&2
  return 1
}

render_card title-card "$title_dur"
render_card end-card "$end_dur"
for base in "${beats[@]}"; do
  render_beat "$base"
done

echo "[demo] stitching ${#order[@]} clips -> docs/demo.mp4"
: > "$work/list.txt"
for c in "${order[@]}"; do printf "file '%s'\n" "$repo/docs/videos/$c.mp4" >> "$work/list.txt"; done
# Re-encode (not -c copy): the cards come from a different encoder than VHS, so their stream params
# differ and a stream-copy concat would fail. A uniform libx264 pass makes the seams gapless.
ffmpeg -y -f concat -safe 0 -i "$work/list.txt" \
  -c:v libx264 -pix_fmt yuv420p -crf 20 -r 25 docs/demo.mp4 >/dev/null 2>&1

# Derive the published GIF from the stitched mp4. 1200px/dither=none keeps terminal text crisp and the
# flat dark background clean; a two-pass palette (stats_mode=diff) gives good colour on little content.
echo "[demo] deriving the published GIF"
ffmpeg -y -i docs/demo.mp4 \
  -vf "fps=10,scale=1200:-1:flags=lanczos,palettegen=max_colors=256:stats_mode=diff" \
  "$work/palette.png" >/dev/null 2>&1
ffmpeg -y -i docs/demo.mp4 -i "$work/palette.png" \
  -lavfi "fps=10,scale=1200:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle" \
  "$work/demo.gif" >/dev/null 2>&1

# Publish under a content-hashed name (docs/demo-<hash>.gif) so browsers never serve a stale cached
# copy: identical bytes keep the same URL, any change gets a new one. Delete the previous published GIF
# and rewrite the README image link to match — the committed name is always exactly the current bytes.
hash="$(sha1sum "$work/demo.gif" | cut -c1-8)"
gif="docs/demo-$hash.gif"
for old in docs/demo-*.gif; do
  [ -e "$old" ] && [ "$old" != "$gif" ] && rm -f "$old"
done
mv "$work/demo.gif" "$gif"
# Repoint the README image at the new filename (matches docs/demo.gif or any docs/demo-<hash>.gif).
sed -i -E "s#\(docs/demo(-[0-9a-f]+)?\.gif\)#($gif)#" README.md
# The standalone lathe.nvim mirror embeds the same GIF by absolute raw URL (it does not ship the
# binary), so keep its hash in lockstep too — otherwise the mirror link rots on the next re-record.
sed -i -E "s#(raw\.githubusercontent\.com/ag-libs/lathe/main/)docs/demo(-[0-9a-f]+)?\.gif#\1$gif#" \
  dev/nvim-mirror/README.md

echo "[demo] done: $gif (published, content-hashed); README + nvim-mirror links updated"
echo "[demo] docs/demo.mp4 + docs/videos/*.mp4 are gitignored intermediates"
