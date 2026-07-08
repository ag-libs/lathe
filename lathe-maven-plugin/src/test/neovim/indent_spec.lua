-- Client-neutral indentation contract for Lathe's Java indenter.
--
-- Each fixture is a Google-Java-Format snippet plus the 1-based line whose
-- indentation we assert. The fixtures describe the *behaviour* the indenter
-- must produce; they are intentionally editor-agnostic so they can also serve
-- as the acceptance spec for the future VS Code language-configuration indent
-- rules (and any server-side onTypeFormatting), not just this Neovim indentexpr.
--
-- Run headless from the repo root:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/indent_spec.lua
--
-- With --clean the Java tree-sitter parser is absent, so this exercises the
-- text-heuristic path — exactly the mid-edit (unparseable) situation a user is
-- in when pressing Enter. The runner re-indents the target line via `==`, which
-- invokes the buffer's indentexpr with v:lnum set the same way Neovim does.

local indent = require("lathe.indent")
local spec = require("spec_helper").new()

-- {name, lines, target_line (1-based), expected_indent}
local FIXTURES = {
  {
    "record component: first wraps under the header (the reported gap)",
    { "public record Foo(", "int a," },
    2,
    4,
  },
  {
    "record component: siblings align, no stair-stepping",
    { "public record Foo(", "    int a,", "String b) {}" },
    3,
    4,
  },
  {
    "wrapped argument: first arg indents one level past the call",
    { "target =", "    new Builder(", "first," },
    3,
    8,
  },
  {
    "wrapped argument: later args stay aligned with the first",
    { "foo(", "    a,", "b," },
    3,
    4,
  },
  {
    "assignment RHS wraps one continuation level",
    { "var x =", "y;" },
    2,
    4,
  },
  {
    "block body indents one block level",
    { "void m() {", "body();" },
    2,
    2,
  },
  {
    "block closer dedents back to the block owner",
    { "void m() {", "  body();", "}" },
    3,
    0,
  },
  {
    "completed multi-line statement dedents to the statement base",
    { "x =", "    foo(", "        a);", "next();" },
    4,
    0,
  },
  {
    "method-chain selector indents one level under the receiver",
    { "receiver", ".first()" },
    2,
    4,
  },
  {
    "method-chain selector keeps following selectors aligned",
    { "receiver", "    .first()", ".second()" },
    3,
    4,
  },
  {
    "selector chain into nested constructor args (new line before the arg)",
    { "receiver.create()", "    .register(", "        new Handler(", "value));" },
    4,
    12,
  },
  {
    "blank line inside a selector call's args sits inside the call, not aligned to the selector",
    { "receiver", "    .register(", "" },
    3,
    8,
  },
  {
    "blank line between selector segments aligns with the chain",
    { "receiver", "    .first()", "", "    .second()" },
    3,
    4,
  },
  {
    "blank line after a completed multi-line statement dedents to the statement base",
    { "var built =", "    new Builder(arg);", "" },
    3,
    0,
  },
  {
    "blank line after a selector line that also closes the statement dedents to the statement base",
    {
      "    super(",
      "        DbClientContext.builder()",
      "            .statements(builder.statements())",
      "            .build());",
      "",
    },
    5,
    4,
  },
  {
    "blank line after a wrapped statement inside a lambda body returns to the body indent",
    { "  items.forEach(", "      value -> {", "        var built =", "            make(value);", "" },
    5,
    8,
  },
  {
    "blank line inside a nested wrapped call (new line before the inner call)",
    { "x =", "    obj.stream()", "        .collect(", "" },
    4,
    12,
  },
  {
    "blank line after a single-line statement in a lambda body stays at body indent",
    { "exceptionally(", "    e -> {", "      log(message);", "" },
    4,
    6,
  },
  {
    "regression: sequential statements keep the same indent",
    { "int x = 1;", "int y = 2;" },
    2,
    0,
  },
  {
    "regression: annotation and the line it annotates align",
    { "@Override", "void run() {}" },
    2,
    0,
  },
  {
    "javadoc: first body line indents to star column",
    { "  /**", "" },
    2,
    3,
  },
  {
    "javadoc: blank line after punctuated text stays at star column",
    { "  /**", "   * Creates an instance.", "", "   */" },
    3,
    3,
  },
  {
    "regression: blank line between javadoc and constructor aligns with member indent",
    { "  /**", "   * Creates an instance.", "   */", "", "  Foo() {" },
    4,
    2,
  },
  {
    "regression: constructor after inserted javadoc blank stays at member indent",
    { "  /**", "   * Creates an instance.", "   */", "", "  Foo() {" },
    5,
    2,
  },
  {
    "regression: body after wrapped method declaration indents from the declaration, not the wrap",
    { "void method(", "    int a) {", "" },
    3,
    2,
  },
  {
    "regression: body after multi-line wrapped method declaration",
    { "void method(", "    int a,", "    int b) {", "" },
    4,
    2,
  },
}

local function compute_indent(lines, target)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  vim.bo.expandtab = true
  vim.bo.shiftwidth = 2
  vim.bo.tabstop = 2
  -- Evaluate the indenter directly so blank target lines work too: `==` leaves
  -- an otherwise-empty line empty, so indent() could not observe the result.
  return indent.compute(target, vim.api.nvim_get_current_buf())
end

for _, fixture in ipairs(FIXTURES) do
  local name, lines, target, expected = fixture[1], fixture[2], fixture[3], fixture[4]
  local ok, actual = pcall(compute_indent, lines, target)
  if not ok then
    spec.fail(string.format("%s: %s", name, actual))
  elseif actual ~= expected then
    spec.fail(string.format("%s: expected %d, got %d", name, expected, actual))
  else
    spec.ok(string.format("%s (%d)", name, expected))
  end
end

spec.finish(string.format("%d fixtures", #FIXTURES))
