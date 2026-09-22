#!/usr/bin/env bash
# End-to-end harness for named run configurations: drives the REAL server over the built
# multi-module invoker fixture -- save a config from a runnable, list/complete it, refuse an
# overwrite, and run it by name (a real replay), asserting the console surfaces the active config.
# Local dev tooling; not shipped, not wired into CI.
#
# Build the fixture first (produces .lathe and the it-home launcher for the working-tree server):
#   mvn verify -pl lathe-maven-plugin -am -Dinvoker.test=multi-module -Dsurefire.skip=true
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it-home/.cache/lathe"
runtime="$repo/lathe-maven-plugin/src/main/neovim"
spec_helper_dir="$repo/lathe-maven-plugin/src/test/neovim"

fail() {
  echo "[run-config-e2e] $1" >&2
  exit 1
}

command -v nvim >/dev/null 2>&1 || fail "nvim not found on PATH"
[ -d "$fixture/.lathe" ] ||
  fail "fixture not built — run: mvn verify -pl lathe-maven-plugin -am -Dinvoker.test=multi-module -Dsurefire.skip=true"
[ -x "$cache/current/lathe-launcher.sh" ] || fail "no launcher under $cache/current"

export LATHE_CACHE="$cache"
export LATHE_E2E_FIXTURE="$fixture"

nvim --headless --clean -u NONE \
  --cmd "set rtp+=$runtime" \
  --cmd "set rtp+=$spec_helper_dir" \
  -l "$here/run-config-e2e-driver.lua"
