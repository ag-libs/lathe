local M = {}

-- Profile config supplied by lathe.setup(). `indent_style` selects the profile ("editor_config" or
-- "google"); `continuation_indent`, when non-nil, pins the continuation width, otherwise it is
-- derived as twice the block width.
M.config = { indent_style = "editor_config", continuation_indent = nil }

-- Block indent baseline, in spaces, applied per profile by apply_buffer_options. For editor_config
-- this is only a fallback: native EditorConfig runs after ftplugins and overrides it when a matching
-- `.editorconfig` exists. For google it is the fixed 2-space width.
local BASELINE_BLOCK = { editor_config = 4, google = 2 }

function M.setup(opts)
  opts = opts or {}
  M.config.indent_style = opts.indent_style or M.config.indent_style
  M.config.continuation_indent = opts.continuation_indent
end

-- Apply the profile's baseline indent options to a Java buffer. Called from ftplugin/java.lua at
-- FileType time; for editor_config, native EditorConfig applies afterward and takes precedence.
function M.apply_buffer_options(bufnr)
  local block = BASELINE_BLOCK[M.config.indent_style] or BASELINE_BLOCK.editor_config
  vim.bo[bufnr].expandtab = true
  vim.bo[bufnr].shiftwidth = block
  vim.bo[bufnr].softtabstop = block
  vim.bo[bufnr].tabstop = block
end

-- Block indent width, in display columns, for the current buffer: the effective 'shiftwidth',
-- falling back to 'tabstop' when 'shiftwidth' is 0 (Vim's own rule). Those buffer-local options are
-- set by native EditorConfig (editor_config profile) or the ftplugin baseline (google, or the
-- 4-space fallback), so the indenter follows whichever width the profile resolved.
local function block_width()
  local sw = vim.bo.shiftwidth
  if sw > 0 then
    return sw
  end
  return vim.bo.tabstop
end

-- Continuation indent width: the pinned `continuation_indent` when configured, otherwise twice the
-- block width. EditorConfig has no continuation concept, so this ratio is Lathe's heuristic.
local function continuation_width()
  return M.config.continuation_indent or block_width() * 2
end

local BLOCK_NODES = {
  annotation_type_body = true,
  block = true,
  class_body = true,
  constructor_body = true,
  enum_body = true,
  interface_body = true,
  module_body = true,
  switch_block = true,
}

local function line(lnum)
  return vim.api.nvim_buf_get_lines(0, lnum - 1, lnum, false)[1] or ""
end

local function trim(text)
  return vim.trim(text or "")
end

local function first_nonblank_col(text)
  local col = text:find("%S")
  return col and col - 1 or 0
end

local function previous_nonblank(lnum)
  local prev = vim.fn.prevnonblank(lnum - 1)
  if prev <= 0 then
    return nil, nil
  end
  return prev, line(prev)
end

local function next_nonblank(lnum)
  local next_line = vim.fn.nextnonblank(lnum + 1)
  if next_line <= 0 then
    return nil, nil
  end
  return next_line, line(next_line)
end

local function starts_with_closer(text)
  return text:match("^%s*[})%]]") ~= nil
end

local function starts_with_switch_label(text)
  return text:match("^%s*case%s") ~= nil or text:match("^%s*default%s*[:%-]") ~= nil
end

local function starts_with_selector(text)
  return text:match("^%s*%.") ~= nil
end

local function starts_with_block_comment_open(text)
  return trim(text):match("^/%*%*?") ~= nil
end

local function starts_with_block_comment_star(text)
  local stripped = trim(text)
  return stripped == "*" or stripped:match("^%*%s") ~= nil
end

local function is_block_comment_line(text)
  return starts_with_block_comment_open(text) or starts_with_block_comment_star(text)
end

local function ends_with_block_comment_close(text)
  return trim(text):match("%*/$") ~= nil
end

local function ends_with_block_opener(text)
  return trim(text):match("{%s*$") ~= nil
end

local function ends_with_open_bracket(text)
  return trim(text):match("[%(%[]$") ~= nil
end

local function ends_with_comma(text)
  return trim(text):match(",$") ~= nil
end

local function ends_with_operator(text)
  local stripped = trim(text)
  if stripped == "" then
    return false
  end
  if is_block_comment_line(stripped) then
    return false
  end
  if stripped:match("%-%>$") then
    return true
  end
  return stripped:match("[%.%+%-%*/%%=&|!<>?:]$") ~= nil
end

local function ends_statement(text)
  return trim(text):match("[;}]$") ~= nil
end

local function selector_indent(prev_lnum, prev)
  if not prev_lnum then
    return 0
  end
  if starts_with_selector(prev) then
    return vim.fn.indent(prev_lnum)
  end
  return vim.fn.indent(prev_lnum) + continuation_width()
end

local function blank_selector_indent(prev_lnum, prev, next_lnum, next_line)
  -- An open bracket or block opener on the previous line means the blank line
  -- sits inside that construct, so the continuation rules own it even when the
  -- previous line is itself a selector call such as `.installPlugin(`.
  if prev_lnum and (ends_with_open_bracket(prev) or ends_with_block_opener(prev)) then
    return nil
  end
  -- A selector line that also closes out its statement (e.g. `.build());`)
  -- has finished the chain, not continued it: defer to the statement-end
  -- dedent logic in heuristic_indent instead of staying at the chain depth.
  if prev_lnum and starts_with_selector(prev) and not ends_statement(prev) then
    return vim.fn.indent(prev_lnum)
  end
  if next_lnum and starts_with_selector(next_line) then
    return vim.fn.indent(next_lnum)
  end
  return nil
end

-- A line is continued by the next one when it ends with an open bracket, a
-- list separator, or a binary operator. Block openers ({) and statement
-- terminators (; }) start a new block/statement, so they do not continue.
local function continues_into_next(text)
  return ends_with_open_bracket(text) or ends_with_comma(text) or ends_with_operator(text)
end

-- Indent of the first line of the statement that `lnum` belongs to, found by
-- walking back across continuation lines. Used to dedent the line that follows
-- a completed multi-line statement back to the statement's base column. A
-- selector line (`.foo()`) is a continuation of whatever precedes it even
-- when that earlier line does not itself end with a continuation character
-- (e.g. a closed call like `.builder()`), so the walk must also follow it.
local function statement_start_indent(lnum)
  local l = lnum
  while l > 1 do
    local p = vim.fn.prevnonblank(l - 1)
    if p <= 0 then
      break
    end
    if not continues_into_next(line(p)) and not starts_with_selector(line(l)) then
      break
    end
    l = p
  end
  return vim.fn.indent(l)
end

-- Indentation dictated by how the previous line ends, anchored to a block /
-- continuation model whose widths come from the active profile (block_width /
-- continuation_width). The offset is measured from the previous line itself,
-- except for list separators: a wrapped list breaks after the opening bracket,
-- so every wrapped item sits at the same column and a comma keeps the next item
-- aligned rather than stair-stepping it deeper.
-- Returns nil when the previous line neither opens a block nor continues an
-- expression, leaving the base indent to the caller.
local function continuation_indent(prev_lnum, prev)
  local prev_indent = vim.fn.indent(prev_lnum)
  if ends_with_block_opener(prev) then
    -- A block opener at the end of a wrapped declaration/statement (e.g. a method whose parameters
    -- wrapped onto the next line) sits at continuation depth, so the body must indent from the
    -- statement's first line, not that deeper column. statement_start_indent equals prev_indent when
    -- the opener is not itself a continuation, leaving the common single-line case unchanged.
    return statement_start_indent(prev_lnum) + block_width()
  end
  if ends_with_open_bracket(prev) then
    return prev_indent + continuation_width()
  end
  if ends_with_comma(prev) then
    return prev_indent
  end
  if ends_with_operator(prev) then
    return prev_indent + continuation_width()
  end
  return nil
end

local function parser_root(bufnr, lnum)
  local ok, parser = pcall(vim.treesitter.get_parser, bufnr, "java")
  if not ok or not parser then
    return nil
  end
  parser:parse({ math.max(lnum - 60, 0), lnum + 60 })
  local tree = parser:trees()[1]
  if not tree then
    return nil
  end
  return tree:root()
end

local function node_at_line(root, lnum)
  local text = line(lnum)
  local col = first_nonblank_col(text)
  return root:descendant_for_range(lnum - 1, col, lnum - 1, col + 1)
end

local function nearest_block_indent(node)
  local current = node
  while current do
    if BLOCK_NODES[current:type()] then
      local start_row = current:start()
      return vim.fn.indent(start_row + 1)
    end
    current = current:parent()
  end
  return nil
end

-- The authoritative indent rule: derived from how the previous line ends and
-- how the current line starts, anchored to existing indentation. It is purely
-- text-based, so it behaves identically while the buffer is mid-edit and
-- unparseable -- which is when indentation is requested most.
local function heuristic_indent(lnum, current, prev_lnum, prev)
  if not prev_lnum then
    return 0
  end
  local prev_indent = vim.fn.indent(prev_lnum)
  if starts_with_closer(current) then
    return math.max(prev_indent - block_width(), 0)
  end
  if starts_with_selector(current) then
    return selector_indent(prev_lnum, prev)
  end
  if starts_with_switch_label(current) then
    return prev_indent
  end
  if starts_with_block_comment_open(prev) then
    return prev_indent + 1
  end
  if starts_with_block_comment_star(prev) then
    return prev_indent
  end
  if ends_with_block_comment_close(prev) then
    if trim(current) == "" then
      local next_lnum = vim.fn.nextnonblank(lnum + 1)
      if next_lnum > 0 then
        return vim.fn.indent(next_lnum)
      end
    end

    return math.max(prev_indent - 1, 0)
  end
  local continuation = continuation_indent(prev_lnum, prev)
  if continuation then
    return continuation
  end
  if ends_statement(prev) then
    return statement_start_indent(prev_lnum)
  end
  return prev_indent
end

-- Match a closing }/)/] to the line that opened its block. The tree models
-- block nesting reliably; it is unreliable for Google Java Format's mixed
-- selector/continuation/lambda indentation, so it is used only here.
local function closer_block_indent(bufnr, lnum, current)
  if not starts_with_closer(current) then
    return nil
  end
  local root = parser_root(bufnr, lnum)
  if not root then
    return nil
  end
  return nearest_block_indent(node_at_line(root, lnum))
end

function M.compute(lnum, bufnr)
  local current = line(lnum)
  local prev_lnum, prev = previous_nonblank(lnum)
  local next_lnum, next_line = next_nonblank(lnum)

  if trim(current) == "" then
    local selector = blank_selector_indent(prev_lnum, prev, next_lnum, next_line)
    if selector then
      return selector
    end
  end

  local block = closer_block_indent(bufnr, lnum, current)
  if block then
    return block
  end

  return heuristic_indent(lnum, current, prev_lnum, prev)
end

function M.indentexpr()
  return M.compute(vim.v.lnum, vim.api.nvim_get_current_buf())
end

return M
