#!/usr/bin/env bash
# Runs every Neovim `*_spec.lua` fixture in this directory headlessly. Each
# spec is self-contained and reports failures via spec_helper.lua, exiting
# `qa!`/`cq!` so this script can detect pass/fail per spec. Hard-fails when
# nvim is missing under CI (CI=true) so a misconfigured runner turns the build
# red; degrades gracefully (exit 0) on local machines without nvim. Bound to
# the `test` phase via exec-maven-plugin; runnable by hand too.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
neovim_runtime="$here/../../main/neovim"

if ! command -v nvim >/dev/null 2>&1; then
  if [[ "${CI:-}" == "true" ]]; then
    echo "[neovim-specs] nvim not found on PATH under CI; failing build" >&2
    exit 1
  fi

  echo "[neovim-specs] nvim not found on PATH; skipping Neovim specs"
  exit 0
fi

status=0
for spec in "$here"/*_spec.lua; do
  name="$(basename "$spec")"
  echo "[neovim-specs] running $name"
  if ! nvim --headless --clean -u NONE \
    --cmd "set rtp+=$neovim_runtime" \
    --cmd "set rtp+=$here" \
    -l "$spec"; then
    echo "[neovim-specs] $name failed" >&2
    status=1
  fi
done

exit "$status"
