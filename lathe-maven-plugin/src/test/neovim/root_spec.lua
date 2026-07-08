-- Verifies Lathe's exported workspace/cache-root API (lathe.get_root,
-- lathe.cache_root, lathe.ROOT_MARKER) so other Neovim config code can rely
-- on it instead of re-deriving root detection (e.g. a project picker
-- hard-coding the `.lathe` marker name separately). Self-contained: builds
-- and tears down its own fixture tree, independent of any real project or
-- LSP server process.
--
-- Run headless from the repo root (or via run-specs.sh, which runs every
-- `*_spec.lua` file in this directory the same way):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/root_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local project = work .. "/project"
local cache = work .. "/cache"
vim.fn.mkdir(project .. "/src/main/java", "p")
vim.fn.mkdir(cache .. "/deps/some", "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

write_file(project .. "/.lathe", "")
write_file(project .. "/src/main/java/Foo.java", "class Foo {}\n")
write_file(cache .. "/deps/some/Cached.java", "class Cached {}\n")

vim.env.LATHE_CACHE = cache
local lathe = require("lathe")

local project_norm = vim.fs.normalize(project)
local cache_norm = vim.fs.normalize(cache)

spec.check("ROOT_MARKER", lathe.ROOT_MARKER, ".lathe")
spec.check("cache_root() honors $LATHE_CACHE", lathe.cache_root(), cache_norm)

-- A buffer under the cache with no marked project resolved yet, and no
-- memoized last_root, has nothing to fall back to.
local cache_buf = vim.fn.bufadd(cache .. "/deps/some/Cached.java")
spec.check("get_root: cache file with no fallback available", lathe.get_root(cache_buf), nil)

-- A buffer directly under the `.lathe`-marked root resolves via direct match
-- and memoizes it as last_root.
local project_buf = vim.fn.bufadd(project .. "/src/main/java/Foo.java")
spec.check("get_root: marked project root", lathe.get_root(project_buf), project_norm)

-- The same cache buffer now falls back to the memoized/scanned project root.
spec.check(
  "get_root: cache file falls back once a project buffer is known",
  lathe.get_root(cache_buf),
  project_norm
)

vim.fn.delete(work, "rf")
spec.finish()
