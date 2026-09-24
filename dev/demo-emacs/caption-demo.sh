#!/bin/sh
# Caption the synthetic multi-module demo (dev/demo-emacs/demo.tape output) and
# slice the two clips that shine on that fixture (clean com.example paths):
# cross-module navigation and annotation-processor generated sources.
#   vhs dev/demo-emacs/demo.tape       # -> docs/videos/emacs-demo.mp4
#   ./dev/demo-emacs/caption-demo.sh   # -> emacs-demo-captioned.mp4 + two clips
set -e
cd "$(git rev-parse --show-toplevel)"

IN=docs/videos/emacs-demo.mp4
CAP=docs/videos/emacs-demo-captioned.mp4
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

printf '%s' "Cross-module go-to-definition, across your Maven reactor" > "$TMP/c1"
printf '%s' "Into annotation-processor generated sources"             > "$TMP/c2"
printf '%s' "Completion from the reactor: record accessors"           > "$TMP/c3"

style="fontfile=$FONT:fontcolor=white:fontsize=38:box=1:boxcolor=black@0.66:boxborderw=26:x=(w-text_w)/2:y=h-text_h-96"

ffmpeg -v error -y -i "$IN" -vf "
  drawtext=$style:textfile=$TMP/c1:enable='between(t,3.6,6.6)',
  drawtext=$style:textfile=$TMP/c2:enable='between(t,8.6,11.6)',
  drawtext=$style:textfile=$TMP/c3:enable='between(t,12.7,15.2)'
" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$CAP"
echo "Wrote $CAP"

enc() { ffmpeg -v error -y -ss "$2" -to "$3" -i "$CAP" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "docs/videos/$1"; echo "  docs/videos/$1"; }
enc emacs-clip-crossmodule.mp4 3.3 7.2
enc emacs-clip-generated.mp4   8.3 11.9
