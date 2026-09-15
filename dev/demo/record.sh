#!/usr/bin/env bash
# Re-record the Lathe demo from the committed sources (dev/demo/beats/*.tape + *.ass).
# Run AFTER ./dev/demo/prepare.sh, which rebuilds+installs Lathe, rebuilds the multi-module invoker
# fixture, and copies your Neovim config into dev/demo/.nvim. This script only renders + captions +
# stitches; it does not rebuild Lathe.
#
#   ./dev/demo/prepare.sh
#   ./dev/demo/record.sh          ->  docs/demo.gif (the published, committed artifact)
#
# GIF-first: the per-beat docs/videos/<beat>.mp4 and the stitched docs/demo.mp4 are regenerable
# intermediates (gitignored); only docs/demo.gif is committed and embedded in the README.
#
# Each tape re-runs `mvn clean test -Dlathe.capture.only=true` (build cache off) in its hidden setup,
# so .lathe is captured fresh with the currently-installed Lathe on every render.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cd "$repo"
mkdir -p docs/videos
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

clips=()
for tape in dev/demo/beats/*.tape; do
  base="$(basename "$tape" .tape)"
  out="docs/videos/$base.mp4"
  ass="dev/demo/beats/$base.ass"
  echo "[demo] rendering $base"
  vhs "$tape"                                        # writes the raw clip to $out (the tape's Output)
  if [ -f "$ass" ]; then
    echo "[demo] captioning $base"
    ffmpeg -y -i "$out" -vf "subtitles=$ass" -c:a copy "$work/$base.mp4" >/dev/null 2>&1
    mv "$work/$base.mp4" "$out"                      # caption in place -> one titled file per beat
  fi
  clips+=("$out")
done

echo "[demo] stitching ${#clips[@]} clips -> docs/demo.mp4"
: > "$work/list.txt"
for c in "${clips[@]}"; do printf "file '%s'\n" "$repo/$c" >> "$work/list.txt"; done
# -c copy needs identical stream params across clips; VHS renders them all the same, so this is safe.
ffmpeg -y -f concat -safe 0 -i "$work/list.txt" -c copy docs/demo.mp4 >/dev/null 2>&1

# Derive the published GIF from the stitched mp4. 1200px/dither=none keeps terminal text crisp and the
# flat dark background clean; a two-pass palette (stats_mode=diff) gives good colour on little content.
echo "[demo] deriving docs/demo.gif (the published artifact)"
ffmpeg -y -i docs/demo.mp4 \
  -vf "fps=10,scale=1200:-1:flags=lanczos,palettegen=max_colors=256:stats_mode=diff" \
  "$work/palette.png" >/dev/null 2>&1
ffmpeg -y -i docs/demo.mp4 -i "$work/palette.png" \
  -lavfi "fps=10,scale=1200:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle" \
  docs/demo.gif >/dev/null 2>&1

echo "[demo] done: docs/demo.gif (published); docs/demo.mp4 + docs/videos/*.mp4 are gitignored intermediates"
