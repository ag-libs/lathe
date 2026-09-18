#!/bin/sh
# Prepend an intro card and append an outro card to the captioned hero, producing
# a standalone announcement piece.
#   ./dev/demo-emacs/caption.sh        # -> docs/videos/emacs-hero.mp4
#   ./dev/demo-emacs/title-cards.sh    # -> docs/videos/emacs-hero-titled.mp4
set -e
cd "$(git rev-parse --show-toplevel)"

HERO=docs/videos/emacs-hero.mp4
OUT=docs/videos/emacs-hero-titled.mp4
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
W=1800; H=1080; FPS=25; BG=0x11111b
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

card() { # out dur "big" "small"
  ffmpeg -v error -y -f lavfi -i "color=c=$BG:s=${W}x${H}:d=$2:r=$FPS" -vf "
    drawtext=fontfile=$FONT:text='$3':fontcolor=white:fontsize=110:x=(w-text_w)/2:y=(h/2)-120,
    drawtext=fontfile=$FONT:text='$4':fontcolor=0xcdd6f4:fontsize=44:x=(w-text_w)/2:y=(h/2)+20
  " -c:v libx264 -pix_fmt yuv420p "$1"
}

card "$TMP/intro.mp4" 3.0 "Lathe" "A zero-config Java LSP for Emacs — from your real Maven build"
card "$TMP/outro.mp4" 3.0 "No jdtls. No import." "github.com/ag-libs/lathe"

ffmpeg -v error -y -i "$TMP/intro.mp4" -i "$HERO" -i "$TMP/outro.mp4" \
  -filter_complex "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]" -map "[v]" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$OUT"
echo "Wrote $OUT"
