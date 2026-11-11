-- The indenter's tree-sitter path: with a Java parser, a line led by `}` takes the indent of the
-- block it closes from the parse tree. indent_spec.lua covers the text heuristic, which runs where
-- there is no parser (and mid-edit); this covers the parsed case, so it needs a Java parser: CI
-- builds one (.github/actions/neovim), and a developer's Neovim usually has one under its data
-- directory; without one the spec reports itself skipped, except in CI, where it fails.
--
-- Run headless from the repo root:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/indent_tree_spec.lua

local indent = require("lathe.indent")
local spec = require("spec_helper").new()

local parser = vim.fs.joinpath(vim.fn.stdpath("data"), "site", "parser", "java.so")
if not vim.uv.fs_stat(parser) then
  -- CI installs one (.github/actions/neovim), so there a missing parser is a broken setup, as
  -- run-specs.sh treats a missing nvim.
  if vim.env.GITHUB_ACTIONS == "true" then
    spec.fail("no Java parser at " .. parser)
    spec.finish()
  end

  spec.pending("tree-sitter closers", "no Java parser at " .. parser)
  spec.finish("skipped")
end

-- A parser built for another Neovim's tree-sitter ABI does not load: skip, as without one.
local loaded, why = pcall(vim.treesitter.language.add, "java", { path = parser })
if not loaded then
  spec.pending("tree-sitter closers", "the Java parser at " .. parser .. " does not load: " .. why)
  spec.finish("skipped")
end

-- {name, google-java-format output, the closing line (1-based), its indent}
local FIXTURES = {
  {
    "method whose parameters wrap: `}` aligns with the declaration",
    {
      "class C {",
      "  public String create(",
      "      @Valid Owner owner, BindingResult result, RedirectAttributes attributes) {",
      "    return x;",
      "  }",
      "}",
    },
    5,
    2,
  },
  {
    "method whose parameters wrap one per line",
    { "class C {", "  void m(", "      int a,", "      int b) {", "    x();", "  }", "}" },
    6,
    2,
  },
  {
    "constructor whose parameters wrap",
    { "class C {", "  C(", "      int a, int b) {", "    x();", "  }", "}" },
    5,
    2,
  },
  {
    "if whose condition wraps",
    {
      "class C {",
      "  void m() {",
      "    if (first",
      "        && second) {",
      "      x();",
      "    }",
      "  }",
      "}",
    },
    6,
    4,
  },
  {
    "method with a wrapped throws clause",
    { "class C {", "  void m()", "      throws Exception {", "    x();", "  }", "}" },
    5,
    2,
  },
  {
    "anonymous class after a trailing `?`: `}` aligns with `new`",
    {
      "class C {",
      "  void m() {",
      "    Runnable r = cond ?",
      "        new Runnable() {",
      "          public void run() {}",
      "        } : null;",
      "  }",
      "}",
    },
    6,
    8,
  },
  {
    "block lambda after a trailing `&&`: `}` aligns with the lambda's line",
    {
      "class C {",
      "  boolean m() {",
      "    return ready",
      "        && list.stream().anyMatch(x -> {",
      "          return x;",
      "        });",
      "  }",
      "}",
    },
    6,
    8,
  },
  {
    "loop under an outdented label: `}` aligns with the loop",
    {
      "class C {",
      "  void m() {",
      "  outer:",
      "    for (;;) {",
      "      x();",
      "    }",
      "  }",
      "}",
    },
    6,
    4,
  },
  {
    "bare block under a case label: `}` aligns with its `{`",
    {
      "class C {",
      "  void m() {",
      "    switch (k) {",
      "      case A:",
      "        {",
      "          x();",
      "        }",
      "    }",
      "  }",
      "}",
    },
    7,
    8,
  },
  {
    "first case of a switch expression: one block inside the switch",
    {
      "class C {",
      "  int m() {",
      "    return switch (k) {",
      "      case A -> 1;",
      "    };",
      "  }",
      "}",
    },
    4,
    6,
  },
  {
    "case after a case body that wrapped: the previous label's sibling",
    {
      "class C {",
      "  int m() {",
      "    int x =",
      "        switch (k) {",
      "          case A ->",
      "              compute(first, second);",
      "          case B -> 2;",
      "          default -> 3;",
      "        };",
      "  }",
      "}",
    },
    7,
    10,
  },
  {
    "default after a wrapped case body",
    {
      "class C {",
      "  int m() {",
      "    int x =",
      "        switch (k) {",
      "          case A ->",
      "              compute(first, second);",
      "          default -> 3;",
      "        };",
      "  }",
      "}",
    },
    7,
    10,
  },
  {
    "case after a statement-group body with break",
    {
      "class C {",
      "  void m() {",
      "    switch (k) {",
      "      case A:",
      "        x();",
      "        break;",
      "      case B:",
      "        y();",
      "    }",
      "  }",
      "}",
    },
    7,
    6,
  },
  {
    "text block content keeps its indent",
    {
      "class C {",
      "  String s =",
      '      """',
      "        {",
      '          "a": 1',
      "        }",
      '      """;',
      "}",
    },
    5,
    10,
  },
  {
    "a `}` inside a text block is content, not a closer",
    {
      "class C {",
      "  String s =",
      '      """',
      "        {",
      '          "a": 1',
      "        }",
      '      """;',
      "}",
    },
    6,
    8,
  },
  {
    "a new blank line inside a text block takes the line above's indent",
    { "class C {", "  String s =", '      """', "        first", "", '      """;', "}" },
    5,
    8,
  },
  {
    "plain method (control)",
    { "class C {", "  void m() {", "    x();", "  }", "}" },
    4,
    2,
  },
  {
    "else block (control)",
    {
      "class C {",
      "  void m() {",
      "    if (a) {",
      "      x();",
      "    } else {",
      "      y();",
      "    }",
      "  }",
      "}",
    },
    7,
    4,
  },
  {
    "lambda block passed as an argument (control)",
    {
      "class C {",
      "  void m() {",
      "    executor.submit(",
      "        () -> {",
      "          work();",
      "        });",
      "  }",
      "}",
    },
    6,
    8,
  },
  {
    "anonymous class (control)",
    {
      "class C {",
      "  Runnable r =",
      "      new Runnable() {",
      "        public void run() {}",
      "      };",
      "}",
    },
    5,
    6,
  },
}

for _, fixture in ipairs(FIXTURES) do
  local name, lines, target, expected = fixture[1], fixture[2], fixture[3], fixture[4]
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  vim.bo.expandtab = true
  vim.bo.shiftwidth = 2
  vim.bo.tabstop = 2
  spec.check(name, indent.compute(target, vim.api.nvim_get_current_buf()), expected)
end

-- The fixtures pass through the text heuristic too: make sure the tree answered them.
local parsed = pcall(function()
  return vim.treesitter.get_parser(0, "java"):parse()[1]:root()
end)
spec.check("the fixtures were parsed (the tree path ran)", parsed, true)

spec.finish(string.format("%d fixtures", #FIXTURES))
