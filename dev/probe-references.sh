#!/bin/sh
# Probe find-references correctness against the multi-module IT project.
# Exits 1 if any assertion fails.
#
# Prerequisites: run once to build the IT project:
#   mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module -DskipNeovimTests
#
# Usage: sh dev/probe-references.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROBE="python3 $SCRIPT_DIR/explore.py"
IT="$SCRIPT_DIR/../lathe-maven-plugin/target/it/multi-module"
CORE="$IT/core/src/main/java/com/example/core/StringUtils.java"
APP="$IT/app/src/main/java/com/example/app/Main.java"

if [ ! -f "$CORE" ]; then
  echo "IT project not found — run: mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module -DskipNeovimTests"
  exit 1
fi

PASS=0
FAIL=0

run() {
  desc="$1"; shift
  printf "  %-55s " "$desc"
  if "$@" 2>/dev/null | grep -q '\[PASS\]'; then
    echo "[PASS]"
    PASS=$((PASS + 1))
  else
    echo "[FAIL]"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== find-references probes (multi-module IT) ==="
echo ""

# public method — must find cross-module call in app/
run "public method: finds downstream reactor call" \
  $PROBE "$CORE" refs "upper" min 1 expect "Main.java"

# public method — result set is exactly 1 (only call in app/Main.java)
run "public method: exactly one reference across reactor" \
  $PROBE "$CORE" refs "upper" min 1 max 1

# private constructor — DECLARING_FILE scope, no cross-module hits
run "private ctor: no cross-module references" \
  $PROBE "$CORE" refs "StringUtils()" max 0

# class type — import in app is a reference
run "public class: import in app counts as reference" \
  $PROBE "$CORE" refs "StringUtils" expect "Main.java"

# import false-positive regression: no package-segment hits
run "import: no false positives at package segments" \
  $PROBE "$APP" refs "StringUtils" max 5

# cross-module only — nothing in core itself
run "cross-module call site found" \
  $PROBE "$CORE" refs "upper" expect "com/example/app"

echo ""
echo "=== results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
