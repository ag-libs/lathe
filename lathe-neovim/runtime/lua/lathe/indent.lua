local M = {}

-- Global fallback indent from lathe.setup({ style = { indent = ... } }), same shape as a workspace
-- style file's `indent`: profile ("google"|"editorconfig") with optional block/continuation widths.
M.config = { profile = "editorconfig" }

-- Block indent baseline, in spaces, per profile. For editor_config this is only a fallback: native
-- EditorConfig runs after ftplugins and overrides it when a matching `.editorconfig` exists. For
-- google it is the fixed 2-space width.
local BASELINE_BLOCK = { editor_config = 4, google = 2 }

function M.setup(opts)
  opts = opts or {}
  M.config = opts.indent or { profile = "editorconfig" }
end

local function buffer_root(bufnr)
  local ok, root = pcall(function()
    return require("lathe.workspace").get_root(bufnr)
  end)
  return ok and root or nil
end

-- Block and pinned-continuation widths for an `indent` table ({profile, block, continuation}), or nil
-- when the table is nil. profile selects the baseline block; block/continuation (> 0) override it. A
-- nil continuation means the caller derives it as twice the block.
local function widths(indent)
  if not indent then
    return nil
  end

  local profile = indent.profile == "google" and "google" or "editor_config"
  local block = (indent.block and indent.block > 0) and indent.block or BASELINE_BLOCK[profile]
  local continuation = (indent.continuation and indent.continuation > 0) and indent.continuation
    or nil
  return block, continuation
end

-- Per-buffer indent: the workspace style file's `indent` (committed/generated, under the root the
-- existing get_root resolves) when present, else the global setup() fallback. The third value says
-- the project owns the widths: its style pins them (a committed indent, or the one the sync derived
-- from its formatter), so .editorconfig must not override them. An editorconfig profile, or only
-- the personal setup() fallback, leaves them to the project's .editorconfig.
local function resolve(bufnr)
  local style = require("lathe.style").read(buffer_root(bufnr))
  local project = style and style.indent
  local block, continuation = widths(project)
  if block then
    -- A project owns its widths when its style pins them: the google profile, or explicit widths.
    local pinned = project.profile == "google"
      or (project.block or 0) > 0
      or (project.continuation or 0) > 0
    return block, continuation, pinned
  end

  local fallback_block, fallback_continuation = widths(M.config)
  return fallback_block, fallback_continuation, false
end

local function set_widths(bufnr, block, continuation)
  vim.bo[bufnr].expandtab = true
  vim.bo[bufnr].shiftwidth = block
  vim.bo[bufnr].softtabstop = block
  vim.bo[bufnr].tabstop = block
  vim.b[bufnr].lathe_continuation = continuation
end

-- Apply the resolved profile's baseline widths to a Java buffer. Called from ftplugin/java.lua at
-- FileType time; native EditorConfig applies afterward and takes precedence, until
-- apply_project_indent restores what the project owns.
function M.apply_buffer_options(bufnr)
  local block, continuation = resolve(bufnr)
  set_widths(bufnr, block, continuation)
end

-- Restore the project's own widths once native EditorConfig has run, so its formatter's indent
-- wins over .editorconfig for Java, as format-on-save would rewrite anything else. EditorConfig
-- keeps every other property, and the indent wherever the project leaves it open.
function M.apply_project_indent(bufnr)
  local block, continuation, project = resolve(bufnr)
  if project then
    set_widths(bufnr, block, continuation)
  end
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

-- Continuation indent width: the width apply_buffer_options pinned for this buffer (from the
-- workspace style file or the global indent fallback), otherwise twice the block width.
local function continuation_width()
  local pinned = vim.b.lathe_continuation
  if pinned and pinned > 0 then
    return pinned
  end

  return block_width() * 2
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

-- The previous line that holds code, skipping blank and `//` comment lines: a comment inside a
-- method chain or an operator chain does not break it.
local function previous_code_line(lnum)
  local prev = vim.fn.prevnonblank(lnum - 1)
  while prev > 0 and trim(line(prev)):match("^//") do
    prev = vim.fn.prevnonblank(prev - 1)
  end
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

-- A continuation line led by a binary operator, as Google Java Format breaks before one. A lone
-- `* / < >` is left out: it also leads javadoc `*` lines, `/*` comments and `<T>` generic
-- declarations; `++ -- -> ::` are not binary operators.
local function starts_with_operator(text)
  local stripped = trim(text)
  if stripped:match("^instanceof%f[%W]") then
    return true
  end
  if stripped:match("^%+%+") or stripped:match("^%-[%-%>]") or stripped:match("^::") then
    return false
  end
  return stripped:match("^[-+&|^?:%%]") ~= nil
    or stripped:match("^[=!<>]=") ~= nil
    or stripped:match("^<<") ~= nil
    or stripped:match("^>>") ~= nil
end

-- A line led by `*` or `/` as a binary operator. Only the statement walk asks, where the lines are
-- code: as a chain anchor a `* ` line could be javadoc.
local function starts_with_multiplicative(text)
  local stripped = trim(text)
  return stripped:match("^[%*/]%s") ~= nil
end

-- A declaration clause Google Java Format wraps onto its own continuation line.
local function starts_with_clause(text)
  local stripped = trim(text)
  return stripped:match("^throws%f[%W]") ~= nil
    or stripped:match("^extends%f[%W]") ~= nil
    or stripped:match("^implements%f[%W]") ~= nil
    or stripped:match("^permits%f[%W]") ~= nil
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

-- How far back a bracket match looks before giving up.
local MAX_BALANCE_LINES = 200
local BALANCE_CHUNK = 20

-- The line's code with string/char literals and comments blanked, so brackets and operators
-- inside them are not counted. Escapes go first so an escaped quote does not end a literal, and
-- char literals (one character, or an escape) before strings so `'"'` does not open one. A `/*`
-- left open runs to the line's end; a `*/` closing a comment opened above is kept for the caller.
local function code_only(text)
  if not text:find("[\"'/\\]") then
    return text
  end

  local code = text:gsub("\\.", "__")
  code = code:gsub("'[^']'", "''")
  code = code:gsub("'__'", "''")
  code = code:gsub('"[^"]*"', '""')
  code = code:gsub("/%*.-%*/", " ")
  code = code:gsub("//.*$", "")
  code = code:gsub("/%*.*$", "")
  return code
end

-- Whether the line's code ends with a binary operator, so the expression continues on the next
-- line. A comment's last character is prose (`// see below:`), and an old-style `case A:` label
-- ends a label, not an expression.
local function ends_with_operator(text)
  if is_block_comment_line(text) or ends_with_block_comment_close(text) then
    return false
  end
  local stripped = trim(code_only(text))
  if stripped == "" or (starts_with_switch_label(stripped) and stripped:match(":$")) then
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

local function is_opener(char)
  return char == "(" or char == "[" or char == "{"
end

local function is_closer(char)
  return char == ")" or char == "]" or char == "}"
end

-- Walking up, the part of a line that is code. Seen from below, a text block or a block comment
-- is closed first and opened further up, so `state` carries whether the walk is inside one.
local function code_walking_up(text, state)
  if state.text_block then
    local open = text:find('"""', 1, true)
    if not open then
      return ""
    end
    state.text_block = false
    text = text:sub(1, open - 1)
  elseif state.comment then
    local open = text:find("/*", 1, true)
    if not open then
      return ""
    end
    state.comment = false
    text = text:sub(1, open - 1)
  end

  local _, delimiters = text:gsub('"""', "")
  if delimiters % 2 == 1 then
    state.text_block = true
    text = text:sub(text:match('.*()"""') + 3)
  end

  local code = code_only(text)
  -- A plain find first: the backtracking match for the last `*/` is costly on every walked line.
  if code:find("*/", 1, true) then
    state.comment = true
    code = code:sub(code:match(".*()%*/") + 2)
  end
  return code
end

-- The line of the opener that `need` unmatched closers at the end of line `lnum - 1` and above
-- reach, and that opener's character; nil when none is found within MAX_BALANCE_LINES.
local function opener_above(lnum, need)
  local limit = math.max(lnum - MAX_BALANCE_LINES, 1)
  local state = { text_block = false, comment = false }
  local last = lnum - 1
  -- Lines are fetched a chunk at a time: most walks end within a few lines.
  while last >= limit do
    local first = math.max(last - BALANCE_CHUNK + 1, limit)
    local lines = vim.api.nvim_buf_get_lines(0, first - 1, last, false)
    for i = #lines, 1, -1 do
      local code = code_walking_up(lines[i], state)
      for c = #code, 1, -1 do
        local char = code:sub(c, c)
        if is_closer(char) then
          need = need + 1
        elseif is_opener(char) then
          need = need - 1
          if need == 0 then
            return first + i - 1, char
          end
        end
      end
    end
    last = first - 1
  end
  return nil
end

-- The line holding the opener that matches the closers `lnum` leaves unmatched, considering the
-- line up to column `last_col` (the whole line when nil); `lnum` itself when it closes nothing
-- opened above, nil when no match is found within MAX_BALANCE_LINES. This is a bracket count over
-- text, not a parse: the indenter must work while the buffer is mid-edit and unparseable, and
-- tree-sitter models only `{}` blocks for it. Brackets in literals and comments are skipped.
local function balancing_line(lnum, last_col)
  local code = code_only(line(lnum))
  if last_col then
    code = code:sub(1, last_col)
  end

  local open, need = 0, 0
  for char in code:gmatch(".") do
    if is_opener(char) then
      open = open + 1
    elseif is_closer(char) then
      if open > 0 then
        open = open - 1
      else
        need = need + 1
      end
    end
  end
  if need == 0 then
    return lnum
  end

  return (opener_above(lnum, need))
end

-- The first line of the bracket groups `lnum` closes: a closer's opener line can itself close a
-- group opened higher up (`}` matching `    int b) {` of a wrapped declaration).
local function bracket_group_start(lnum)
  local l = lnum
  while true do
    local opener = balancing_line(l)
    if not opener or opener == l then
      return l
    end
    l = opener
  end
end

-- Whether a line ends where a new expression begins: an assignment or a lambda arrow.
local function ends_with_expression_start(text)
  local stripped = trim(text)
  return stripped:match("%->$") ~= nil
    or stripped == "="
    or stripped:match("[^=!<>]=$") ~= nil
    or stripped:match("[<>][<>]=$") ~= nil
end

-- The first line of the comma-wrapped list ending at `lnum` when that list is a declaration clause
-- (`implements A,` / `B,`), else nil.
local function clause_list_start(lnum)
  local q = lnum
  while true do
    local p = vim.fn.prevnonblank(q - 1)
    if p <= 0 or not ends_with_comma(line(p)) then
      break
    end
    q = p
  end
  return starts_with_clause(line(q)) and q or nil
end

-- The indent of the line a block's braces belong to: the first line of the bracket groups its `{`
-- line closes, climbing wrapped declaration lines (a return type, a `throws` clause). It stops
-- where a new expression starts, as Google Java Format puts that block under its own line
-- (`switch (x) {` after `var y =`, a lambda's `e -> {` after `(`).
local function block_owner_indent(lnum)
  local l = bracket_group_start(lnum)
  while true do
    local p = vim.fn.prevnonblank(l - 1)
    if p <= 0 then
      break
    end
    local above = line(p)
    if ends_with_comma(above) then
      -- A comma-wrapped `implements A,` / `B {` list belongs to its clause; any other comma ends
      -- an argument or element, where the block's own line starts.
      local q = clause_list_start(p)
      if not q then
        break
      end
      l = bracket_group_start(q)
    elseif
      starts_with_clause(line(l))
      or (ends_with_operator(above) and not ends_with_expression_start(above))
    then
      l = bracket_group_start(p)
    else
      break
    end
  end
  return vim.fn.indent(l)
end

-- A chain continuation (a `.selector` or a leading-operator line) aligns under a line of the same
-- kind, otherwise one continuation past it. The anchor is the line that opened whatever the
-- previous line closes, so a chain that resumes after wrapped arguments (`})` then `.get()`)
-- returns to the chain rather than indenting past the closer.
local function chain_indent(prev_lnum, same_kind)
  if not prev_lnum then
    return 0
  end
  local anchor = balancing_line(prev_lnum) or prev_lnum
  if same_kind(line(anchor)) then
    return vim.fn.indent(anchor)
  end
  return vim.fn.indent(anchor) + continuation_width()
end

-- A leading operator continues the previous line's expression only when that line left the
-- expression open without a marker of its own; after `{ ( [ , ;` or a trailing operator the line
-- is an array element, an argument or a new statement (`-1,`), which the other rules own.
local function continues_with_operator(current, prev)
  if not starts_with_operator(current) or is_block_comment_line(prev) then
    return false
  end

  local code = code_only(prev)
  return not ends_with_block_opener(code)
    and not ends_with_open_bracket(code)
    and not ends_with_comma(code)
    and not ends_with_operator(code)
    and not trim(code):match(";$")
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

-- Indent of the first line of the statement that `lnum` belongs to, found by walking back across
-- bracket groups and continuation lines. Used to dedent the line that follows a completed
-- multi-line statement back to the statement's base column. A line led by a selector (`.foo()`),
-- an operator or a `throws`-like clause continues whatever precedes it even when that earlier line
-- does not itself end with a continuation character, so the walk follows it too.
local function statement_start_indent(lnum)
  local l = bracket_group_start(lnum)
  while l > 1 do
    local p = vim.fn.prevnonblank(l - 1)
    if p <= 0 then
      break
    end
    local above = line(p)
    local text = line(l)
    if
      not ends_with_open_bracket(above)
      and not ends_with_comma(above)
      and not ends_with_operator(above)
      and not starts_with_selector(text)
      and not starts_with_operator(text)
      and not starts_with_multiplicative(text)
      and not starts_with_clause(text)
    then
      break
    end
    l = bracket_group_start(p)
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
    -- A block opener at the end of a wrapped declaration (a method whose parameters wrapped onto
    -- the next line) sits at continuation depth, so the body indents from the line the block
    -- belongs to, not that deeper column; for a single-line opener that is the line itself.
    return block_owner_indent(prev_lnum) + block_width()
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
    local opener = balancing_line(lnum, first_nonblank_col(current) + 1)
    if opener and opener ~= lnum then
      return block_owner_indent(opener)
    end

    -- An empty block: the previous non-blank line is the opener itself, so the closer aligns with it
    -- rather than dedenting a level (which would assume a body line sat between them). This is the
    -- heuristic fallback for when tree-sitter cannot model the block -- invalid or mid-edit code.
    if ends_with_block_opener(prev) then
      return prev_indent
    end

    return math.max(prev_indent - block_width(), 0)
  end
  local code_lnum, code_prev = previous_code_line(lnum)
  if starts_with_selector(current) then
    return chain_indent(code_lnum, starts_with_selector)
  end
  if code_lnum and continues_with_operator(current, code_prev) then
    return chain_indent(code_lnum, starts_with_operator)
  end
  if starts_with_switch_label(current) then
    return prev_indent
  end
  if starts_with_block_comment_open(prev) then
    -- A self-contained single-line block comment (`/** ... */`) is transparent: the following line
    -- stays at the comment's own indent. Only a genuine multi-line opener indents the continuation
    -- `*` by one.
    if ends_with_block_comment_close(prev) then
      return prev_indent
    end

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

  -- A line led by `)` or `]` aligns with the line that opened it; tree-sitter's block model only
  -- knows `{}`, so it would answer with the enclosing block's indent.
  if current:match("^%s*[)%]]") then
    local opener = balancing_line(lnum, first_nonblank_col(current) + 1)
    if opener and opener ~= lnum then
      return vim.fn.indent(opener)
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
