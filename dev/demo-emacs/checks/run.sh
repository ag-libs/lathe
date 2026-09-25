#!/usr/bin/env bash
# Render each mini check tape and report which produced a non-empty .mp4. Run from the repo root:
#   ./dev/demo-emacs/checks/run.sh
# A ladder: 1 bare terminal -> 2 plain Emacs -> 3 Emacs+Eglot connect -> 4 plain Neovim.
# The lowest level that yields NO file is where capture breaks.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
cd "$(git rev-parse --show-toplevel)"
for tape in "$here"/check*.tape; do
  base="$(basename "$tape" .tape)"
  out="docs/videos/$base.mp4"
  rm -f "$out"
  printf '=== %-16s ' "$base"
  vhs "$tape" >"/tmp/$base.log" 2>&1
  if [ -f "$out" ]; then
    sz=$(stat -c%s "$out")
    dur=$(ffprobe -v error -show_entries format=duration -of default=nk=1:nw=1 "$out" 2>/dev/null)
    echo "OK  ${sz} bytes, ${dur:-?}s  -> $out"
  else
    echo "NO FILE  (see /tmp/$base.log)"
  fi
done
