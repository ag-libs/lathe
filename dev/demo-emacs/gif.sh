#!/bin/sh
# Convert the Emacs demo mp4s to optimized GIFs (palettegen/paletteuse) for
# embedding in the README / announcement threads.
#   ./dev/demo-emacs/gif.sh
set -e
cd "$(git rev-parse --show-toplevel)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

to_gif() { # in out width fps
  in="docs/videos/$1"; out="docs/videos/$2"; w="$3"; fps="$4"
  [ -f "$in" ] || { echo "  skip (missing): $in"; return; }
  ffmpeg -v error -y -i "$in" -vf "fps=$fps,scale=$w:-1:flags=lanczos,palettegen=stats_mode=diff" "$TMP/p.png"
  ffmpeg -v error -y -i "$in" -i "$TMP/p.png" -lavfi "fps=$fps,scale=$w:-1:flags=lanczos,paletteuse=dither=bayer:bayer_scale=3" "$out"
  echo "  $out  ($(du -h "$out" | cut -f1))"
}

echo "Generating GIFs:"
to_gif emacs-hero.mp4              emacs-hero.gif              960 12
to_gif emacs-clip-completion.mp4  emacs-clip-completion.gif  960 14
to_gif emacs-clip-deps-jdk.mp4    emacs-clip-deps-jdk.gif    960 14
to_gif emacs-clip-fold.mp4        emacs-clip-fold.gif        960 14
to_gif emacs-clip-crossmodule.mp4 emacs-clip-crossmodule.gif 960 14
to_gif emacs-clip-generated.mp4   emacs-clip-generated.gif   960 14
