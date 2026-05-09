:#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LATHE_ROOT="$(dirname "$SCRIPT_DIR")"

echo "[lathe] building..."
mvn install -pl lathe-server -am -DskipTests -f "$LATHE_ROOT/pom.xml"

echo "[lathe] copying dependencies..."
mvn dependency:copy-dependencies \
    -pl lathe-server \
    -DincludeScope=runtime \
    -DoutputDirectory=target/dependency \
    -f "$LATHE_ROOT/pom.xml" \
    -q

export LATHE_SRC="$LATHE_ROOT"

exec nvim -c "luafile $SCRIPT_DIR/nvim.lua" "$@"
