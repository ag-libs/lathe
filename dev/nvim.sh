#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "[lathe] installing server binaries..."
mvn install -f "$REPO_ROOT/pom.xml" -pl lathe-core,lathe-server -am -DskipTests

LAUNCHER="$HOME/.cache/lathe/current/lathe-launcher.sh"
if [ ! -x "$LAUNCHER" ]; then
  echo "[lathe] launcher not found at $LAUNCHER — run 'mvn process-test-classes' in your project first" >&2
  exit 1
fi

export LATHE_LAUNCHER="$LAUNCHER"

exec nvim -c "luafile $SCRIPT_DIR/nvim.lua" "$@"
