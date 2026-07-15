-- Verifies lathe.stacktrace's pure parsing and candidate-resolution logic
-- used to navigate stack frames in neotest's output buffer
-- (docs/done/lathe-test-output-streaming.md). Self-contained: no neotest,
-- nio, or live LSP connection required -- lathe.stacktrace has no optional
-- plugin dependency, so this spec runs the same way neotest_spec.lua does.
--
-- Run headless from the repo root (or via run-specs.sh, which runs every
-- `*_spec.lua` file in this directory the same way):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/stacktrace_spec.lua

local spec = require("spec_helper").new()
local stacktrace = require("lathe.stacktrace")

--- Checks a list of {field, expected} pairs against a parsed frame in one
--- call, guarding each lookup with `frame and` so an unexpectedly nil frame
--- (a parse regression) reports a clean per-field failure instead of a hard
--- Lua error that would abort the rest of this spec.
local function check_frame_fields(label, frame, fields)
  for _, field in ipairs(fields) do
    spec.check(("%s %s"):format(label, field[1]), frame and frame[field[1]], field[2])
  end
end

-- parse_frame: a normal, top-level-class frame.
do
  check_frame_fields("plain frame", stacktrace.parse_frame("\tat com.example.FooTest.fails(FooTest.java:42)"), {
    { "fqcn", "com.example.FooTest" },
    { "simple_name", "FooTest" },
    { "package", "com.example" },
    { "file", "FooTest.java" },
    { "line", 42 },
  })
end

-- parse_frame: nested/anonymous class frames resolve to the enclosing
-- top-level class, since that is what actually has a source file.
do
  check_frame_fields("nested frame", stacktrace.parse_frame("\tat com.example.Outer$Inner.fails(Outer.java:10)"), {
    { "simple_name", "Outer" },
    { "package", "com.example" },
  })
  check_frame_fields("anonymous class frame", stacktrace.parse_frame("\tat com.example.FooTest$1.run(FooTest.java:20)"), {
    { "simple_name", "FooTest" },
  })
  check_frame_fields(
    "lambda frame",
    stacktrace.parse_frame("\tat com.example.FooTest.lambda$test$0(FooTest.java:15)"),
    { { "simple_name", "FooTest" } }
  )
end

-- parse_frame: default package (no dot before the method name still splits
-- correctly since fqcn only requires one or more characters, not a dot).
do
  check_frame_fields("default package frame", stacktrace.parse_frame("\tat FooTest.fails(FooTest.java:5)"), {
    { "simple_name", "FooTest" },
    { "package", "" },
  })
end

-- parse_frame: JPMS module-qualified frames have the leading "<module>/"
-- stripped so package splitting stays accurate.
do
  check_frame_fields(
    "module-qualified frame",
    stacktrace.parse_frame("\tat java.base/java.lang.Thread.run(Thread.java:840)"),
    {
      { "fqcn", "java.lang.Thread" },
      { "simple_name", "Thread" },
      { "package", "java.lang" },
    }
  )
end

-- parse_frame: a module token can carry a version (app@1.0-SNAPSHOT/...),
-- whose "@" and "-" are not word characters; the whole "<module>/" prefix must
-- still be stripped so the class resolves.
do
  check_frame_fields(
    "versioned module frame",
    stacktrace.parse_frame(
      "\tat com.example.app@1.0-SNAPSHOT/com.example.app.pipeline.rules.DiscountWrapperTest.fails(DiscountWrapperTest.java:68)"
    ),
    {
      { "fqcn", "com.example.app.pipeline.rules.DiscountWrapperTest" },
      { "simple_name", "DiscountWrapperTest" },
      { "package", "com.example.app.pipeline.rules" },
      { "line", 68 },
    }
  )
end

-- parse_frame: trailing " ~[...]" JVM location info does not break the match
-- (pattern is not anchored to end-of-line).
do
  local frame = stacktrace.parse_frame("\tat com.example.FooTest.fails(FooTest.java:42) ~[test-classes/:?]")
  spec.check("frame with trailing jar info still parses", frame and frame.line, 42)
end

-- parse_frame: non-frame lines all return nil.
do
  spec.check(
    "'Caused by:' line does not parse",
    stacktrace.parse_frame("Caused by: java.lang.AssertionError: boom"),
    nil
  )
  spec.check("'... N more' line does not parse", stacktrace.parse_frame("\t... 3 more"), nil)
  spec.check("blank line does not parse", stacktrace.parse_frame(""), nil)
  spec.check("plain message line does not parse", stacktrace.parse_frame("expected: <1> but was: <2>"), nil)
end

-- Shared pick_candidate fixtures: the same frame and "wrong package"
-- candidate recur across every case below, so they're declared once here
-- rather than rebuilt identically in each do-block.
local pick_frame = { simple_name = "FooTest", package = "com.example" }
local other_package_symbol = { containerName = "com.other", label = "FooTest" }

-- pick_candidate: a single candidate is accepted even without a package
-- match -- a class name unique workspace-wide is the common case.
do
  spec.check(
    "single candidate accepted without package match",
    stacktrace.pick_candidate(pick_frame, { other_package_symbol }),
    other_package_symbol
  )
end

-- pick_candidate: multiple candidates require an exact package match.
do
  local match = { containerName = "com.example", label = "FooTest" }
  spec.check(
    "package match wins among multiple candidates",
    stacktrace.pick_candidate(pick_frame, { other_package_symbol, match }),
    match
  )
end

-- pick_candidate: ambiguous (no package match among several) or empty
-- results resolve to nothing rather than guessing.
do
  spec.check(
    "no package match among several is unresolved",
    stacktrace.pick_candidate(pick_frame, { other_package_symbol, other_package_symbol }),
    nil
  )
  spec.check("empty results are unresolved", stacktrace.pick_candidate(pick_frame, {}), nil)
  spec.check("nil results are unresolved", stacktrace.pick_candidate(pick_frame, nil), nil)
end

spec.finish()
