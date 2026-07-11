-- Pure Java stack-frame parsing and candidate resolution for neotest output
-- navigation (docs/done/lathe-test-output-streaming.md). Kept independent
-- of neotest/nio -- like lathe.indent, this module has no optional-plugin
-- dependency, so it stays testable and loadable on any machine.

local M = {}

-- Matches "at <fqcn>.<method>(<File>.java:<line>)", tolerating the leading
-- indentation real stack traces use and any trailing " ~[...]" module/jar
-- info newer JVMs append -- deliberately not anchored to end-of-line.
local FRAME_PATTERN = "^%s*at%s+(.+)%.([%w_$<>]+)%(([%w_$]+%.java):(%d+)%)"

--- Splits a fully-qualified class name into its package and top-level simple
--- name. `$Nested` suffixes are stripped from the simple name (but not from
--- the fqcn) since the enclosing top-level class is what actually has a
--- source file and a workspace/symbol entry.
local function split_class(fqcn)
  local package, class_part = fqcn:match("^(.*)%.([^.]+)$")
  if not package then
    package, class_part = "", fqcn
  end
  local top_level = class_part:match("^([^$]+)")
  return package, top_level
end

--- Parses one line of captured test output into a stack frame, or returns
--- nil for anything else (`Caused by:` lines, `... N more`, blank lines,
--- non-Java frames). JPMS-style frames have their leading `<module>/` stripped
--- so package splitting stays accurate. The module token can carry a version
--- (`app@1.0-SNAPSHOT/pkg.Cls...`), whose `@`/`-` are not word characters, so
--- everything up to the first `/` is stripped rather than only word chars.
function M.parse_frame(text)
  local fqcn, _method, file, line_str = text:match(FRAME_PATTERN)
  if not fqcn then
    return nil
  end

  fqcn = fqcn:gsub("^[^/]+/", "")
  local package, simple_name = split_class(fqcn)
  return {
    fqcn = fqcn,
    simple_name = simple_name,
    package = package,
    file = file,
    line = tonumber(line_str),
  }
end

--- Picks the workspace/symbol result that best matches a parsed frame:
--- prefer a package match; if exactly one candidate came back at all, accept
--- it even without a package match (a class name unique workspace-wide is
--- the common case); otherwise leave the frame unresolved rather than guess.
function M.pick_candidate(frame, symbols)
  if not symbols or #symbols == 0 then
    return nil
  end

  if #symbols == 1 then
    return symbols[1]
  end

  for _, symbol in ipairs(symbols) do
    if symbol.containerName == frame.package then
      return symbol
    end
  end

  return nil
end

--- Rejoins terminal-wrapped grid rows into logical lines. neotest renders test
--- output into a terminal buffer whose wrap column is the window it rendered in
--- (not necessarily the editor width), so a logical line longer than `width` is
--- hard-wrapped across consecutive rows of exactly `width` cells, the last one
--- shorter. Callers pass the empirical wrap width -- the longest buffer line.
--- Without this, a long stack frame (e.g. a JPMS `module@version/`-qualified
--- one) is split across rows and matches nothing. Returns a list of
--- { text = <joined line>, rows = { <1-based physical row indices> } } so a
--- caller can map a match on the joined text back to the physical rows it
--- spans, for highlighting and jump registration. `width <= 0` disables
--- joining (returns each row as its own logical line).
function M.unwrap(lines, width)
  local logical = {}
  local i = 1
  while i <= #lines do
    local text = lines[i]
    local rows = { i }
    local texts = { lines[i] }
    while width > 0 and #lines[i] == width and i < #lines do
      i = i + 1
      text = text .. lines[i]
      table.insert(rows, i)
      table.insert(texts, lines[i])
    end

    table.insert(logical, { text = text, rows = rows, texts = texts })
    i = i + 1
  end

  return logical
end

return M
