#!/usr/bin/env bash
# End-to-end check of the Lathe neotest adapter: drives the REAL adapter against a LIVE server and
# real replays over the built multi-module invoker fixture (driver.lua holds the checks). Bound to
# lathe-neovim's integration-test phase after the headless specs; runnable by hand once the fixture
# is built:
#   mvn verify -pl '!lathe-neovim,!lathe-vscode' -Dmaven.test.skip.exec=true -Dinvoker.test=multi-module
#
# neotest, nvim-nio and plenary are cloned at the commits plugins.txt pins, into target/. In GitHub
# Actions a missing nvim, plugin or fixture fails the build; elsewhere it skips with a message.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
module="$(cd "$here/../.." && pwd)"
repo="$(cd "$module/.." && pwd)"

fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it home/.cache/lathe"
plugins="$module/target/e2e-plugins"

# The workflow runs Maven under `env -u CI`, so CI is never set here; GITHUB_ACTIONS is.
unavailable() {
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    echo "[neotest-e2e] $1; failing build" >&2
    exit 1
  fi

  echo "[neotest-e2e] $1; skipping"
  exit 0
}

command -v nvim >/dev/null 2>&1 || unavailable "nvim not found on PATH"
[ -x "$fixture/.lathe/lathe-launcher.sh" ] || unavailable "multi-module fixture not built at $fixture"

rtp=()
while read -r name url commit; do
  [[ -z "$name" || "$name" == \#* ]] && continue
  dir="$plugins/$name"
  if [ ! -d "$dir/.git" ]; then
    git clone --quiet "$url" "$dir" || unavailable "cannot clone $name from $url"
  fi

  git -C "$dir" checkout --quiet "$commit" 2>/dev/null ||
    { git -C "$dir" fetch --quiet origin && git -C "$dir" checkout --quiet "$commit"; } ||
    unavailable "cannot check out $name at $commit"
  rtp+=(--cmd "set rtp+=$dir")
done <"$here/plugins.txt"

export LATHE_CACHE="$cache"
export LATHE_E2E_FIXTURE="$fixture"

echo "[neotest-e2e] running driver.lua against $fixture"
nvim --headless --clean -u NONE \
  --cmd "set rtp+=$module/runtime" \
  --cmd "set rtp+=$module/test" \
  "${rtp[@]}" \
  -l "$here/driver.lua"
