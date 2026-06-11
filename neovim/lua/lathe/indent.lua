local M = {}

local BLOCK_INDENT = 2
local CONTINUATION_INDENT = 4

local BLOCK_NODES = {
  annotation_type_body = true,
  block = true,
  class_body = true,
  constructor_body = true,
  enum_body = true,
  interface_body = true,
  switch_block = true,
}

local CONTINUATION_NODES = {
  annotation_argument_list = true,
  argument_list = true,
  array_initializer = true,
  element_value_array_initializer = true,
  formal_parameters = true,
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

local function ends_with_block_opener(text)
  return trim(text):match("{%s*$") ~= nil
end

local function ends_with_continuation(text)
  local stripped = trim(text)
  if stripped == "" then
    return false
  end
  if stripped:match("[%.%,%+%-%*/%%=&|!<>?:]$") then
    return true
  end
  return stripped:match("%-%>$") ~= nil
end

local function selector_indent(prev_lnum, prev)
  if not prev_lnum then
    return 0
  end
  if starts_with_selector(prev) then
    return vim.fn.indent(prev_lnum)
  end
  return vim.fn.indent(prev_lnum) + CONTINUATION_INDENT
end

local function blank_selector_indent(prev_lnum, prev, next_lnum, next_line)
  if prev_lnum and starts_with_selector(prev) then
    return vim.fn.indent(prev_lnum)
  end
  if next_lnum and starts_with_selector(next_line) then
    return vim.fn.indent(next_lnum)
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

local function previous_code_node(root, lnum)
  local prev_lnum, prev = previous_nonblank(lnum)
  if not prev_lnum then
    return nil
  end
  local col = math.max(first_nonblank_col(prev) + #trim(prev) - 1, 0)
  return root:descendant_for_range(prev_lnum - 1, col, prev_lnum - 1, col + 1)
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

local function tree_indent(node, lnum)
  if not node then
    return nil
  end
  local block_depth = 0
  local continuation = false
  local current = node
  while current do
    if current:has_error() then
      return nil
    end
    local kind = current:type()
    local start_row, _, end_row = current:range()
    local spans_line = start_row < lnum - 1 and lnum - 1 <= end_row
    if spans_line and BLOCK_NODES[kind] then
      block_depth = block_depth + 1
    end
    if spans_line and CONTINUATION_NODES[kind] then
      continuation = true
    end
    current = current:parent()
  end
  return (block_depth * BLOCK_INDENT) + (continuation and CONTINUATION_INDENT or 0)
end

local function fallback_indent(current, prev_lnum, prev)
  if not prev_lnum then
    return 0
  end
  local prev_indent = vim.fn.indent(prev_lnum)
  if starts_with_closer(current) then
    return math.max(prev_indent - BLOCK_INDENT, 0)
  end
  if starts_with_selector(current) then
    return selector_indent(prev_lnum, prev)
  end
  if starts_with_switch_label(current) then
    return prev_indent
  end
  if ends_with_block_opener(prev) then
    return prev_indent + BLOCK_INDENT
  end
  if ends_with_continuation(prev) then
    return prev_indent + CONTINUATION_INDENT
  end
  return prev_indent
end

function M.indentexpr()
  local lnum = vim.v.lnum
  local bufnr = vim.api.nvim_get_current_buf()
  local current = line(lnum)
  local prev_lnum, prev = previous_nonblank(lnum)
  local next_lnum, next_line = next_nonblank(lnum)
  local root = parser_root(bufnr, lnum)

  if trim(current) == "" then
    local selector = blank_selector_indent(prev_lnum, prev, next_lnum, next_line)
    if selector then
      return selector
    end
  end

  if not root then
    return fallback_indent(current, prev_lnum, prev)
  end

  local current_node = node_at_line(root, lnum)

  if starts_with_closer(current) then
    return nearest_block_indent(current_node) or fallback_indent(current, prev_lnum, prev)
  end

  if starts_with_selector(current) then
    return selector_indent(prev_lnum, prev)
  end

  if starts_with_switch_label(current) then
    return tree_indent(current_node, lnum) or fallback_indent(current, prev_lnum, prev)
  end

  if prev and ends_with_block_opener(prev) then
    return vim.fn.indent(prev_lnum) + BLOCK_INDENT
  end

  if prev and ends_with_continuation(prev) then
    return vim.fn.indent(prev_lnum) + CONTINUATION_INDENT
  end

  local node = current_node
  if trim(current) == "" then
    node = previous_code_node(root, lnum)
  end

  return tree_indent(node, lnum) or fallback_indent(current, prev_lnum, prev)
end

return M
