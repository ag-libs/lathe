#!/usr/bin/env bash
# Runs the Neovim indent fixtures headlessly. No-op (exit 0) when nvim is not
# installed, so the build degrades gracefully on machines and CI without it.
# Invoked by the `neovim-indent-tests` Maven profile; runnable by hand too.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
neovim_runtime="$here/../../main/neovim"
spec="$here/indent_spec.lua"

if ! command -v nvim >/dev/null 2>&1; then
  echo "[indent-spec] nvim not found on PATH; skipping Neovim indent tests"
  exit 0
fi

echo "[indent-spec] running Neovim indent fixtures"
exec nvim --headless --clean -u NONE \
  --cmd "set rtp+=$neovim_runtime" \
  -l "$spec"
