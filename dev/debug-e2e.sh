#!/usr/bin/env bash
# End-to-end debug harness for Lathe: drives the full attach flow -- lathe.debug.test launches a
# captured test suspended under a JDWP agent and opens an in-process DAP host; the probe speaks raw
# DAP to it exactly as nvim-dap would, sets a breakpoint in HelloTest, and asserts it stops,
# inspects, and resumes green. This is the automatable Phase 1 GO/NO-GO -- no editor, no human.
# Local dev tooling only; never shipped, not wired into CI.
#
# Runs against the PUBLIC multi-module invoker fixture. Because debug adds the java-debug jars to
# the server's module path, the fixture's launcher must be regenerated from a freshly installed
# server -- so this script builds by default. Set SKIP_BUILD=1 to reuse an already-built fixture.
#
# A different workspace/file can be probed by passing them through (e.g. a local project):
#   ./dev/debug-e2e.sh /abs/workspace /abs/workspace/.../SomeTest.java 42 methodName
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it-home/.cache/lathe"
default_file="$fixture/jpms/src/test/java/com/example/jpms/HelloTest.java"
default_line=16
default_method=greet_returnsExpectedMessage
default_main_file="$fixture/jpms/src/main/java/com/example/jpms/HelloMain.java"
default_main_line=8
default_main_class=com.example.jpms.HelloMain

fail() {
  echo "[debug-e2e] $1" >&2
  exit 1
}

workspace="${1:-$fixture}"
file="${2:-$default_file}"
line="${3:-$default_line}"
method="${4:-$default_method}"

refresh_invoker_server() {
  # The fixture launcher loads Lathe's own jars from the invoker's target/local-repo, which the
  # invoker populates ONCE and never refreshes for non-reactor dependencies -- so a plain rebuild
  # leaves stale jars there (missing the debug wiring). Copy each freshly installed version across.
  local artifact m2 inv jar ver
  for artifact in lathe-core lathe-server; do
    m2="$HOME/.m2/repository/io/github/ag-libs/$artifact"
    inv="$repo/lathe-maven-plugin/target/local-repo/io/github/ag-libs/$artifact"
    [ -d "$m2" ] && [ -d "$inv" ] || continue
    find "$m2" -name "$artifact-*.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' | while read -r jar; do
      ver="$(basename "$(dirname "$jar")")"
      [ -d "$inv/$ver" ] && cp "$jar" "$inv/$ver/" && echo "[debug-e2e] refreshed invoker $artifact $ver"
    done
  done
}

if [ -z "${SKIP_BUILD:-}" ] && [ "$workspace" = "$fixture" ]; then
  echo "[debug-e2e] installing server and rebuilding the multi-module fixture..."
  (cd "$repo" && mvn -q install -DskipTests)
  [ -d "$fixture/.lathe" ] || (cd "$repo" && mvn -q verify -pl lathe-maven-plugin -Dinvoker.test=multi-module)
  refresh_invoker_server
fi

[ -d "$workspace/.lathe" ] || fail "workspace not built (.lathe missing): $workspace"
[ -x "$cache/current/lathe-launcher.sh" ] || fail "no launcher under $cache/current (build the fixture first)"
command -v python3 >/dev/null 2>&1 || fail "python3 not found on PATH"

export LATHE_LAUNCHER="$cache/current/lathe-launcher.sh"

echo "[debug-e2e] probing test $file:$line (method $method)"
python3 "$here/debug_probe.py" \
  --workspace "$workspace" \
  "$file" \
  --line "$line" \
  --method "$method"

# On the default fixture, also exercise main-class debug (Part A) and read-only expression
# evaluation (eval v1). set -e aborts the harness if any probe fails.
if [ "$workspace" = "$fixture" ] && [ "$file" = "$default_file" ]; then
  echo "[debug-e2e] probing main $default_main_file:$default_main_line ($default_main_class)"
  python3 "$here/debug_probe.py" \
    --workspace "$workspace" \
    "$default_main_file" \
    --line "$default_main_line" \
    --main "$default_main_class"

  eval_common=("$here/debug_probe.py" --workspace "$workspace" "$default_main_file"
    --line "$default_main_line" --main "$default_main_class")
  echo "[debug-e2e] evaluating expressions at $default_main_file:$default_main_line"
  python3 "${eval_common[@]}" --eval "args.length" --expect "0"
  python3 "${eval_common[@]}" --eval "1 + 2" --expect "3"
  echo "[debug-e2e] conditional breakpoints"
  python3 "${eval_common[@]}" --condition "args.length == 0"
  python3 "${eval_common[@]}" --condition "args.length == 99" --expect-nostop

  echo "[debug-e2e] this / instanceof / method calls at the test breakpoint"
  test_eval=("$here/debug_probe.py" --workspace "$workspace" "$file" --line "$line" --method "$method")
  python3 "${test_eval[@]}" --eval "this instanceof java.lang.Object" --expect "true"
  python3 "${test_eval[@]}" --eval '"hi".toUpperCase().length()' --expect "2"
  python3 "${test_eval[@]}" --eval '"a" + 1 + true' --expect '"a1true"'
fi
