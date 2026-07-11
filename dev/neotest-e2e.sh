#!/usr/bin/env bash
# End-to-end harness for the Lathe neotest adapter: drives the REAL adapter
# against a LIVE server and a real replay over the built multi-module invoker
# fixture, asserting the acceptance criteria in
# docs/planned/lathe-neotest-experience.md. Local dev tooling only -- never
# shipped and not wired into CI.
#
# Assumes nvim and the neotest / nvim-nio / plenary plugins are installed, and
# that the multi-module fixture has been built at least once:
#   mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it-home/.cache/lathe"
runtime="$repo/lathe-maven-plugin/src/main/neovim"
spec_helper_dir="$repo/lathe-maven-plugin/src/test/neovim"
plugins="${XDG_DATA_HOME:-$HOME/.local/share}/nvim/lazy"

fail() {
  echo "[neotest-e2e] $1" >&2
  exit 1
}

command -v nvim >/dev/null 2>&1 || fail "nvim not found on PATH"
[ -d "$fixture/.lathe" ] ||
  fail "fixture not built — run: mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module"
[ -x "$cache/current/lathe-launcher.sh" ] || fail "no launcher under $cache/current"
for plugin in neotest nvim-nio plenary.nvim; do
  [ -d "$plugins/$plugin" ] || fail "missing Neovim plugin: $plugin (looked in $plugins)"
done

export LATHE_CACHE="$cache"
export LATHE_E2E_FIXTURE="$fixture"

nvim --headless --clean -u NONE \
  --cmd "set rtp+=$runtime" \
  --cmd "set rtp+=$spec_helper_dir" \
  --cmd "set rtp+=$plugins/neotest" \
  --cmd "set rtp+=$plugins/nvim-nio" \
  --cmd "set rtp+=$plugins/plenary.nvim" \
  -l "$here/neotest-e2e/driver.lua"
