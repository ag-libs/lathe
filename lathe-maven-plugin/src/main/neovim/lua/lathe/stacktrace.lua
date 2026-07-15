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

return M
