#!/usr/bin/env bash
# Prepare everything the demo tape needs, on a Linux laptop. Idempotent.
#
#   1. build + install Lathe (server + extension);
#   2. run the multi-module invoker fixture → a synced copy at
#      target/it/multi-module (with .lathe/) and an isolated server cache at
#      target/it-home/.cache/lathe (the output dev/debug-e2e.sh drives);
#   3. take an ISOLATED COPY of your real Neovim config (dev/demo/.nvim), so the
#      demo looks like your editor — your colorscheme, treesitter, cmp, neotest,
#      and the Lathe integration — without touching your live ~/.config or
#      ~/.local/share. Plugins install into the copy's own data dir, pinned to
#      your active lazy-lock.
#
# The tape then runs nvim on that copy with LATHE_NVIM_DIR (client = this
# checkout) and LATHE_CACHE (server = the invoker cache). Then:
#   vhs dev/demo/demo.tape        ->   docs/demo.gif
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it-home/.cache/lathe"
src_cfg="${LATHE_DEMO_NVIM_CONFIG:-$HOME/.config/nvim}"

echo "[demo] building + installing Lathe…"
(cd "$repo" && mvn -q install -DskipTests)

echo "[demo] building the multi-module invoker fixture…"
(cd "$repo" && mvn -q verify -pl lathe-maven-plugin -Dinvoker.test=multi-module)
[ -x "$cache/current/lathe-launcher.sh" ] \
  || { echo "[demo] server launcher missing at $cache/current — invoker build failed?" >&2; exit 1; }
[ -d "$fixture/.lathe" ] \
  || { echo "[demo] no .lathe/ at $fixture — invoker build failed?" >&2; exit 1; }

echo "[demo] copying your Neovim config from $src_cfg …"
[ -d "$src_cfg" ] || { echo "[demo] no config at $src_cfg (set LATHE_DEMO_NVIM_CONFIG)" >&2; exit 1; }
rm -rf "$here/.nvim"
mkdir -p "$here/.nvim/config/nvim" "$here/.nvim/state/nvim"
cp -r "$src_cfg/." "$here/.nvim/config/nvim/"
# Pin plugin versions to your active lockfile if present (your init redirects it to state/).
for lock in "$HOME/.local/state/nvim/lazy-lock.json" "$src_cfg/lazy-lock.json"; do
  [ -f "$lock" ] && cp "$lock" "$here/.nvim/state/nvim/lazy-lock.json" && break
done

echo "[demo] warming plugins into the isolated copy…"
export XDG_CONFIG_HOME="$here/.nvim/config" XDG_DATA_HOME="$here/.nvim/data"
export XDG_STATE_HOME="$here/.nvim/state" XDG_CACHE_HOME="$here/.nvim/cache"
export LATHE_NVIM_DIR="$repo/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$cache"
nvim --headless "+Lazy! sync" +qa || true

echo
echo "[demo] ready. Record with:"
echo "  vhs dev/demo/demo.tape        # writes docs/demo.gif"
