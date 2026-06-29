#!/usr/bin/env bash
# Runs the Neovim indent fixtures headlessly. Hard-fails when nvim is missing
# under CI (CI=true) so a misconfigured runner turns the build red; degrades
# gracefully (exit 0) on local machines without nvim. Bound to the `test` phase
# via exec-maven-plugin; runnable by hand too.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
neovim_runtime="$here/../../main/neovim"
spec="$here/indent_spec.lua"

if ! command -v nvim >/dev/null 2>&1; then
  if [[ "${CI:-}" == "true" ]]; then
    echo "[indent-spec] nvim not found on PATH under CI; failing build" >&2
    exit 1
  fi

  echo "[indent-spec] nvim not found on PATH; skipping Neovim indent tests"
  exit 0
fi

echo "[indent-spec] running Neovim indent fixtures"
exec nvim --headless --clean -u NONE \
  --cmd "set rtp+=$neovim_runtime" \
  -l "$spec"
