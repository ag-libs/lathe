#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "[lathe] installing server binaries..."
mvn install -f "$REPO_ROOT/pom.xml" -pl lathe-core,lathe-server -am -DskipTests

SERVERS="${LATHE_CACHE:-$HOME/.cache/lathe}/servers"
LAUNCHER="$(ls -1 "$SERVERS"/*-SNAPSHOT/lathe-launcher.sh 2>/dev/null | tail -n1)"
[ -n "$LAUNCHER" ] || LAUNCHER="$(ls -1 "$SERVERS"/*/lathe-launcher.sh 2>/dev/null | tail -n1)"
if [ -z "$LAUNCHER" ] || [ ! -x "$LAUNCHER" ]; then
  echo "[lathe] no launcher under $SERVERS — run 'mvn process-test-classes' in your project first" >&2
  exit 1
fi

exec nvim \
  --cmd "set rtp^=$REPO_ROOT/neovim" \
  --cmd "lua require('lathe').setup()" \
  "$@"
