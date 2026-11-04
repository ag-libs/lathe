-- What the neotest adapter's modules share: neotest and nio loaded lazily, the debug log, the
-- client lookup, the runnable kinds, and how a test id or a source path names a class.
local M = {}

-- Required lazily (inside functions, not here at module load) so that
-- `require('lathe.neotest')` itself never fails when neotest/nio aren't on
-- the runtimepath -- this file must stay loadable, including by tests that
-- exercise its pure logic, on any machine that doesn't have the optional
-- neotest plugin installed.
function M.nio()
  return require("nio")
end

function M.Tree()
  return require("neotest.types").Tree
end

-- Troubleshooting log for the adapter. Emits at INFO to Neovim's LSP log (:LspLog), gated on
-- LATHE_DEBUG so it matches the server's debug switch. We deliberately do NOT raise nvim's global
-- LSP log level (that is a process-wide setting affecting every server), so these lines appear only
-- when the user has themselves set the LSP log level to INFO or lower. Format mirrors the server's
-- "[operation] target detail Xms outcome" convention.
function M.debug_log(msg)
  if vim.env.LATHE_DEBUG then
    vim.lsp.log.info("[lathe.neotest] " .. msg)
  end
end

-- RunnableKind ordinal -> neotest position type / TestSelectionKind. lsp4j's Gson layer
-- serializes Java enums by ordinal, matching the LSP convention that kind fields like
-- SymbolKind/DiagnosticSeverity are integers (see dev/explore.py's identical handling).
-- Ordinals match RunnableKind's declaration order in lathe-server: MAIN, TEST_METHOD,
-- TEST_CLASS, TEST_PACKAGE, MAIN_CLASS. MAIN(0) and MAIN_CLASS(4) have no entry: a main is
-- not a test, so it stays out of the neotest tree and is handled by lathe.run's gutter
-- instead. TEST_PACKAGE(3) has no entry either: a package spans multiple files, so nesting
-- it as a child of whichever single file's discovery happened to report it puts it at
-- the wrong level in the tree (file contains package, not the other way around).
-- Package-level running is instead bound to the directory node neotest's own tree walk
-- already creates at the right level -- see build_spec's "dir" handling in lathe.neotest.spec.
M.TEST_METHOD_KIND = 1
M.TEST_CLASS_KIND = 2

-- The Lathe client, looked up through nio for the adapter's async callers.
function M.client()
  return M.nio().lsp.get_clients({ name = "lathe" })[1]
end

-- A position id's class (`com.x.FooTest$Inner` for `com.x.FooTest$Inner#m()`), and the class a
-- nested class is declared in; nil past the top.
function M.class_of(id)
  return id:match("^(.-)#")
end

function M.enclosing_class(class)
  return class:match("^(.*)%$[^$]+$")
end

--- FQCN for a test source file, or the dotted package for a test source directory, derived from its
--- absolute path anchored on `/src/<scope>/java/`. nil for a path outside a test source root, so a
--- non-standard-layout node falls back to the aggregate rather than mis-matching.
function M.path_to_qualified(path)
  local rel = path:match("^.-/src/[^/]+/java/(.*)$")
  if not rel then
    return nil
  end

  return (rel:gsub("%.java$", ""):gsub("/", "."))
end

-- Any Java file under a test source root: which of them hold tests is the server's discovery to
-- say, with the build's own test filter, so no class-name pattern is guessed here.
function M.is_test_file(file_path)
  return file_path:match("%.java$") ~= nil and file_path:match("/src/test/") ~= nil
end

return M
