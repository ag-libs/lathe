#!/bin/sh
# Measures how often Lathe's Neovim indenter disagrees with a formatter, on files that formatter
# already formatted: the indent it computes for each line against the indent the line has.
#
#   dev/indent-corpus.sh [--block N] [--continuation N] [--examples N] [path ...]
#
# Paths are .java files or directories (searched for .java files); the default is this repository's
# own main sources, which google-java-format formats (block 2, continuation 4, the defaults here).
# For AOSP or palantir output, pass --block 4 --continuation 8.
#
# Needs the Java tree-sitter parser, as check-nvim.sh does: LATHE_NVIM_PARSER_RUNTIME names a
# runtime directory holding parser/java.so (default: Neovim's data directory's site/).
# LATHE_NVIM_RUNTIME points at another client runtime, to compare two versions of the indenter.
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${LATHE_NVIM_RUNTIME:-$REPO_ROOT/lathe-neovim/runtime}"
NVIM="${NVIM:-$(command -v nvim || true)}"
PARSER_RUNTIME="${LATHE_NVIM_PARSER_RUNTIME:-${XDG_DATA_HOME:-$HOME/.local/share}/nvim/site}"

block=2
continuation=4
examples=3
while [ $# -gt 0 ]; do
  case "$1" in
    --block) block=$2; shift 2 ;;
    --continuation) continuation=$2; shift 2 ;;
    --examples) examples=$2; shift 2 ;;
    *) break ;;
  esac
done

if [ -z "$NVIM" ]; then
  echo "[indent-corpus] nvim not found on PATH" >&2
  exit 1
fi

if [ ! -f "$PARSER_RUNTIME/parser/java.so" ]; then
  echo "[indent-corpus] Java tree-sitter parser not found at $PARSER_RUNTIME/parser/java.so" >&2
  echo "[indent-corpus] set LATHE_NVIM_PARSER_RUNTIME to a runtime directory containing parser/java.so" >&2
  exit 1
fi

if [ $# -eq 0 ]; then
  set -- "$REPO_ROOT"/lathe-*/src/main/java
fi

list="$(mktemp "${TMPDIR:-/tmp}/indent-corpus.XXXXXX")"
trap 'rm -f "$list"' EXIT INT TERM
for path in "$@"; do
  if [ -d "$path" ]; then
    find "$path" -name '*.java' -type f
  else
    echo "$path"
  fi
done | sort > "$list"

"$NVIM" --headless --clean -u NONE --cmd "set rtp+=$RUNTIME_DIR" \
  -l "$SCRIPT_DIR/indent_corpus.lua" "$list" "$PARSER_RUNTIME/parser/java.so" "$block" "$continuation" "$examples"
