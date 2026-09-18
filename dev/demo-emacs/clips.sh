#!/bin/sh
# Slice the captioned hero into short, self-contained per-feature clips for
# embedding in a README / announcement thread. Each clip keeps its caption.
#   ./dev/demo-emacs/caption.sh    # first, produces docs/videos/emacs-hero.mp4
#   ./dev/demo-emacs/clips.sh
set -e
cd "$(git rev-parse --show-toplevel)"

IN=docs/videos/emacs-hero.mp4
enc() { ffmpeg -v error -y -ss "$2" -to "$3" -i "$IN" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "docs/videos/$1"; echo "  docs/videos/$1"; }

echo "Slicing $IN:"
enc emacs-clip-completion.mp4 5.3  9.4
enc emacs-clip-deps-jdk.mp4   9.5  16.4
enc emacs-clip-fold.mp4       16.5 19.28
