#!/usr/bin/env bash
# Prepare everything the demo tape needs, on a Linux laptop. Idempotent.
#
#   1. build + install Lathe (server + extension);
#   2. run the multi-module invoker fixture → a synced copy at
#      target/it/multi-module (with .lathe/) and an isolated server cache at
#      target/it-home/.cache/lathe (the output dev/debug-e2e.sh drives);
#   3. take an ISOLATED COPY of the tracked sample config examples/nvim
#      (dev/demo/.nvim), so the demo is reproducible from the repo and never
#      leaks anything from your personal ~/.config. Override with
#      LATHE_DEMO_NVIM_CONFIG to record with a different config. Plugins install
#      into the copy's own data dir and float to their latest versions.
#
# The tape then runs nvim on that copy with LATHE_NVIM_DIR (client = this
# checkout) and LATHE_CACHE (server = the invoker cache). Then:
#   vhs dev/demo/demo.tape        ->   docs/demo.gif
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it-home/.cache/lathe"
src_cfg="${LATHE_DEMO_NVIM_CONFIG:-$repo/examples/nvim}"

echo "[demo] building + installing Lathe…"
(cd "$repo" && mvn -q install -DskipTests)

echo "[demo] building the multi-module invoker fixture…"
(cd "$repo" && mvn -q verify -pl lathe-maven-plugin -Dinvoker.test=multi-module)
[ -x "$cache/current/lathe-launcher.sh" ] \
  || { echo "[demo] server launcher missing at $cache/current — invoker build failed?" >&2; exit 1; }
[ -d "$fixture/.lathe" ] \
  || { echo "[demo] no .lathe/ at $fixture — invoker build failed?" >&2; exit 1; }

echo "[demo] copying the sample Neovim config from $src_cfg …"
[ -d "$src_cfg" ] || { echo "[demo] no config at $src_cfg (set LATHE_DEMO_NVIM_CONFIG)" >&2; exit 1; }
rm -rf "$here/.nvim"
mkdir -p "$here/.nvim/config/nvim" "$here/.nvim/state/nvim"
cp -r "$src_cfg/." "$here/.nvim/config/nvim/"

echo "[demo] warming plugins into the isolated copy…"
export XDG_CONFIG_HOME="$here/.nvim/config" XDG_DATA_HOME="$here/.nvim/data"
export XDG_STATE_HOME="$here/.nvim/state" XDG_CACHE_HOME="$here/.nvim/cache"
export LATHE_NVIM_DIR="$repo/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$cache"
nvim --headless "+Lazy! sync" +qa || true

echo
echo "[demo] ready. Record with:"
echo "  vhs dev/demo/demo.tape        # writes docs/demo.gif"
