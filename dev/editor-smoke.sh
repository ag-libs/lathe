#!/usr/bin/env bash
# Headless editor smoke over real workspaces: opens a Java file in the user's OWN Neovim config with
# the working-tree Lua client and drives what a user does there (navigation, references, completion
# while typing, diagnostics, format, dependency sources, neotest), one PASS/FAIL line per check.
# Local dev tooling only -- never shipped and not wired into CI.
#
#   dev/editor-smoke.sh <dir-holding-the-clones> [dropwizard|equalsverifier|helidon ...]
#
# Each workspace must be synced (mvn process-test-classes) with the server under test. Edits stay
# in the buffer and are undone; no file is written. Exits 1 if any check fails.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"
projects="$(cd "${1:?usage: dev/editor-smoke.sh <dir-holding-the-clones> [workspace ...]}" && pwd)"
shift
out_dir="$(mktemp -d "${TMPDIR:-/tmp}/lathe-editor-smoke.XXXXXX")"

# file|symbol|member-access text|JDK/dependency type|test file, each relative to the workspace
targets() {
  case "$1" in
    dropwizard) echo "dropwizard-util/src/main/java/io/dropwizard/util/Duration.java|toMilliseconds|TimeUnit.|TimeUnit|dropwizard-util/src/test/java/io/dropwizard/util/DurationTest.java" ;;
    equalsverifier) echo "equalsverifier-core/src/main/java/nl/jqno/equalsverifier/internal/checkers/HierarchyChecker.java|check()|Objects.|Objects|equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/HashCodeTest.java" ;;
    helidon) echo "common/media-type/src/main/java/io/helidon/common/media/type/MediaTypeImpl.java|text()|String.|String|common/media-type/src/test/java/io/helidon/common/media/type/MediaTypesTest.java" ;;
    *) echo "[editor-smoke] unknown workspace: $1" >&2; exit 2 ;;
  esac
}

[ "$#" -gt 0 ] || set -- dropwizard equalsverifier helidon
status=0
for name in "$@"; do
  IFS='|' read -r file symbol dot dep test <<<"$(targets "$name")"
  report="$out_dir/$name.txt"
  echo "=== $name"
  (cd "$projects/$name" &&
    LATHE_NVIM_DIR="${LATHE_NVIM_DIR:-$repo/lathe-neovim/runtime}" \
      SMOKE_OUT="$report" SMOKE_SYMBOL="$symbol" SMOKE_DOT="$dot" SMOKE_DEP="$dep" \
      SMOKE_TEST="$projects/$name/$test" \
      timeout 900 nvim --headless -i NONE "$file" "+luafile $here/editor-smoke/driver.lua" \
      >"$out_dir/$name.nvim.log" 2>&1) || status=1
  cat "$report" 2>/dev/null || { echo "FAIL no report — see $out_dir/$name.nvim.log"; status=1; }
done
exit "$status"
