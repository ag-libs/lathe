#!/bin/sh
# Burn lower-third captions onto the raw Emacs hero (dev/demo-emacs/hero.tape):
#   vhs dev/demo-emacs/hero.tape      # -> docs/videos/emacs-hero-raw.mp4
#   ./dev/demo-emacs/caption.sh       # -> docs/videos/emacs-hero.mp4
#
# Captions are keyed to the beat timings verified from the raw render. Text lives
# in temp files (textfile=) so ffmpeg needs no in-arg escaping.
set -e
cd "$(git rev-parse --show-toplevel)"

IN=docs/videos/emacs-hero-raw.mp4
OUT=docs/videos/emacs-hero.mp4
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# beat -> (start end caption)
printf '%s' "Stock eglot: no jdtls, no project import. Lathe reads your Maven build." > "$TMP/c1"
printf '%s' "Completion, straight from your build's classpath"                        > "$TMP/c2"
printf '%s' "Go to definition, into dependency sources (real files)"                  > "$TMP/c3"
printf '%s' "...and into the JDK"                                                      > "$TMP/c4"
printf '%s' "Imports folded from the server's own ranges"                             > "$TMP/c5"

# common drawtext style; y is a lower third, clear of the modeline
style="fontfile=$FONT:fontcolor=white:fontsize=38:box=1:boxcolor=black@0.66:boxborderw=26:x=(w-text_w)/2:y=h-text_h-96:line_spacing=8"

ffmpeg -v error -y -i "$IN" -vf "
  drawtext=$style:textfile=$TMP/c1:enable='between(t,0.4,5.2)',
  drawtext=$style:textfile=$TMP/c2:enable='between(t,5.6,9.2)',
  drawtext=$style:textfile=$TMP/c3:enable='between(t,9.7,12.6)',
  drawtext=$style:textfile=$TMP/c4:enable='between(t,13.0,16.2)',
  drawtext=$style:textfile=$TMP/c5:enable='between(t,16.7,19.2)'
" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$OUT"

echo "Wrote $OUT"
