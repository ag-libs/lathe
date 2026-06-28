#!/bin/sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${LATHE_NVIM_RUNTIME:-$REPO_ROOT/lathe-maven-plugin/src/main/neovim}"
NVIM="${NVIM:-$(command -v nvim || true)}"
NVIM_DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
PARSER_RUNTIME="${LATHE_NVIM_PARSER_RUNTIME:-$NVIM_DATA_HOME/nvim/lazy/nvim-treesitter}"

if [ -z "$NVIM" ]; then
  echo "[nvim] nvim not found on PATH" >&2
  exit 1
fi

if [ ! -d "$RUNTIME_DIR" ]; then
  echo "[nvim] runtime not found at $RUNTIME_DIR" >&2
  exit 1
fi

if [ ! -f "$PARSER_RUNTIME/parser/java.so" ]; then
  echo "[nvim] Java tree-sitter parser not found at $PARSER_RUNTIME/parser/java.so" >&2
  echo "[nvim] set LATHE_NVIM_PARSER_RUNTIME to a runtime directory containing parser/java.so" >&2
  exit 1
fi

TMPDIR="${TMPDIR:-/tmp}"
WORKDIR="$(mktemp -d "$TMPDIR/lathe-nvim.XXXXXX")"
trap 'rm -rf "$WORKDIR"' EXIT INT TERM
export XDG_CACHE_HOME="$WORKDIR/cache"
export XDG_STATE_HOME="$WORKDIR/state"

MODULE_INFO="$WORKDIR/module-info.java"
cat >"$MODULE_INFO" <<'EOF'
module io.github.aglibs.lathe.nvimcheck {
requires java.base;
requires java.logging;
}
EOF

"$NVIM" --clean --headless -n \
  +"set nomore" \
  +"set runtimepath^=$RUNTIME_DIR" \
  +"set runtimepath+=$PARSER_RUNTIME" \
  +"edit $MODULE_INFO" \
  +"setlocal filetype=java" \
  +"runtime! ftplugin/java.lua" \
  +"runtime! after/indent/java.lua" \
  +"lua local function fail(msg) io.stderr:write('[nvim] ' .. msg .. '\\n'); vim.cmd('cquit 1') end; local ok, parser = pcall(vim.treesitter.get_parser, 0, 'java'); if not ok or not parser then fail('missing tree-sitter java parser') end; vim.v.lnum = 3; local indent = vim.fn.eval(vim.bo.indentexpr); if indent ~= 2 then fail('expected module directive indent 2, got ' .. tostring(indent)) end" \
  +"qa!"

echo "[nvim] ok $("$NVIM" --version | sed -n '1p')"
