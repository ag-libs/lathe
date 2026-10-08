#!/usr/bin/env bash
# Prepare the Lathe OpenJDK demo. Idempotent.
#
# Unlike the Maven demo, the "fixture" is an EXTERNAL, already built + synced OpenJDK checkout — this
# script does NOT build or sync the JDK. Prepare the checkout once per the guide (docs/guide/openjdk.md):
#   bash configure --with-boot-jdk=/path/to/jdk-N && make jdk
#   echo '.lathe/' >> .git/info/exclude
#   mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
#
# This script then:
#   1. verifies $LATHE_OPENJDK_DIR is a built + synced checkout (has .lathe/ and .lathe/java-home);
#   2. verifies the Lathe server launcher is linked at $LATHE_OPENJDK_DIR/.lathe (the sync creates it);
#   3. takes an ISOLATED COPY of the sample config examples/nvim (dev/demo-openjdk/.nvim), so the demo
#      is reproducible from the repo and never leaks anything from your personal ~/.config. Override
#      the source config with LATHE_DEMO_NVIM_CONFIG.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
jdk="${LATHE_OPENJDK_DIR:-$HOME/git/jdk}"
src_cfg="${LATHE_DEMO_NVIM_CONFIG:-$repo/examples/nvim}"

[ -d "$jdk/.lathe" ] \
  || { echo "[demo] no .lathe/ at $jdk — build + sync the JDK first (docs/guide/openjdk.md)" >&2; exit 1; }
[ -f "$jdk/.lathe/java-home" ] \
  || { echo "[demo] no .lathe/java-home at $jdk — re-run lathe-openjdk-maven-plugin:sync" >&2; exit 1; }
[ -x "$jdk/.lathe/lathe-launcher.sh" ] \
  || { echo "[demo] no server launcher at $jdk/.lathe — run lathe-openjdk-maven-plugin:sync" >&2; exit 1; }

echo "[demo] copying the sample Neovim config from $src_cfg …"
[ -d "$src_cfg" ] || { echo "[demo] no config at $src_cfg (set LATHE_DEMO_NVIM_CONFIG)" >&2; exit 1; }
rm -rf "$here/.nvim"
mkdir -p "$here/.nvim/config/nvim" "$here/.nvim/state/nvim"
cp -r "$src_cfg/." "$here/.nvim/config/nvim/"

echo "[demo] warming plugins into the isolated copy…"
export XDG_CONFIG_HOME="$here/.nvim/config" XDG_DATA_HOME="$here/.nvim/data"
export XDG_STATE_HOME="$here/.nvim/state" XDG_CACHE_HOME="$here/.nvim/cache"
export LATHE_NVIM_DIR="$repo/lathe-neovim/runtime"
nvim --headless "+Lazy! sync" +qa || true

echo
echo "[demo] ready. Record with:"
echo "  LATHE_OPENJDK_DIR=$jdk ./dev/demo-openjdk/record.sh   # writes docs/openjdk-demo-<hash>.gif"
