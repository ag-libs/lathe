#!/usr/bin/env bash
# Prepare everything the demo tapes need, on a Linux laptop. Idempotent.
#
#   1. build + install Lathe from this checkout (the client's :LatheInit sets up LATHE_VERSION, the
#      snapshot this installs into ~/.m2);
#   2. clone spring-petclinic at a pinned commit into $LATHE_DEMO_WORK (~/lathe-demo, outside this
#      repo) — a public project with no Lathe in its build, the demo's whole subject;
#   3. take an ISOLATED COPY of the tracked sample config examples/nvim (dev/demo/.nvim), so the
#      demo is reproducible from the repo and never leaks anything from your personal ~/.config.
#      Override with LATHE_DEMO_NVIM_CONFIG to record with a different config;
#   4. run :LatheInit once, headless, on that clone. It warms what the on-camera :LatheInit (beat 0,
#      on a fresh clone of it) would otherwise wait for — Maven's wrapper, petclinic's dependencies,
#      Lathe's bootstrap jars, the server install — and leaves the clone set up for beats 1-6.
#
# Then: ./dev/demo/record.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
src_cfg="${LATHE_DEMO_NVIM_CONFIG:-$repo/examples/nvim}"
R="$repo"
# shellcheck source=env.sh
source "$here/env.sh"

echo "[demo] building + installing Lathe…"
(cd "$repo" && mvn -q install -DskipTests)

echo "[demo] cloning spring-petclinic at $PETCLINIC_COMMIT…"
if [ ! -d "$PETCLINIC/.git" ]; then
  mkdir -p "$(dirname "$PETCLINIC")"
  git clone -q "$PETCLINIC_URL" "$PETCLINIC"
fi
git -C "$PETCLINIC" fetch -q origin
git -C "$PETCLINIC" checkout -q -B main "$PETCLINIC_COMMIT"
git -C "$PETCLINIC" reset -q --hard
git -C "$PETCLINIC" clean -q -fdx

echo "[demo] copying the sample Neovim config from $src_cfg …"
[ -d "$src_cfg" ] || { echo "[demo] no config at $src_cfg (set LATHE_DEMO_NVIM_CONFIG)" >&2; exit 1; }
rm -rf "$here/.nvim" "$LATHE_CACHE"
mkdir -p "$here/.nvim/config/nvim" "$here/.nvim/state/nvim"
cp -r "$src_cfg/." "$here/.nvim/config/nvim/"

echo "[demo] warming plugins into the isolated copy…"
nvim --headless "+Lazy! sync" +qa || true

echo "[demo] setting petclinic up with :LatheInit (headless)…"
(cd "$PETCLINIC" && nvim --headless -n "$PETCLINIC_MAIN" -c "luafile $here/init-headless.lua")
[ -x "$PETCLINIC/.lathe/lathe-launcher.sh" ] \
  || { echo "[demo] no launcher at $PETCLINIC/.lathe — :LatheInit failed?" >&2; exit 1; }

echo
echo "[demo] ready. Record with: ./dev/demo/record.sh"
