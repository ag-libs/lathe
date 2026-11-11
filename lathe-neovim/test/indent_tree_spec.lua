-- The indenter's tree-sitter path: with a Java parser, a line led by `}` takes the indent of the
-- block it closes from the parse tree. indent_spec.lua covers the text heuristic, which runs where
-- there is no parser (and mid-edit); this covers the parsed case, so it needs a Java parser. CI has
-- none and reports the spec skipped; a developer's Neovim usually has one under its data directory.
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
  spec.pending("tree-sitter closers", "no Java parser at " .. parser)
  spec.finish("skipped")
end

vim.treesitter.language.add("java", { path = parser })

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

spec.finish(string.format("%d fixtures", #FIXTURES))
