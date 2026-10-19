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
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/root_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local project = work .. "/project"
local cache = work .. "/cache"
vim.fn.mkdir(project .. "/src/main/java", "p")
vim.fn.mkdir(cache .. "/deps/com.example/some/1.0", "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

write_file(project .. "/.lathe", "")
write_file(project .. "/src/main/java/Foo.java", "class Foo {}\n")
write_file(cache .. "/deps/com.example/some/1.0/Cached.java", "class Cached {}\n")

vim.env.LATHE_CACHE = cache
local lathe = require("lathe")

-- Buffer names are symlink-resolved (macOS temp dirs sit under /var -> /private/var), so roots are too.
local project_norm = vim.fs.normalize(vim.uv.fs_realpath(project))
local cache_norm = vim.fs.normalize(vim.uv.fs_realpath(cache))

spec.check("ROOT_MARKER", lathe.ROOT_MARKER, ".lathe")
spec.check("cache_root() honors $LATHE_CACHE", lathe.cache_root(), cache_norm)

-- A buffer under the cache with no marked project resolved yet, and no
-- memoized last_root, has nothing to fall back to.
local cache_buf = vim.fn.bufadd(cache .. "/deps/com.example/some/1.0/Cached.java")
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

-- $LATHE_CACHE reached through a symlink (e.g. a symlinked home): buffer names are resolved, so a
-- cache buffer must still be recognized as one.
local cache_link = work .. "/cache-link"
assert(vim.uv.fs_symlink(cache, cache_link))
vim.env.LATHE_CACHE = cache_link
spec.check("cache_root() resolves a symlinked $LATHE_CACHE", lathe.cache_root(), cache_norm)
spec.check(
  "get_root: cache file under a symlinked $LATHE_CACHE falls back",
  lathe.get_root(vim.fn.bufadd(cache_link .. "/deps/com.example/some/1.0/Cached.java")),
  project_norm
)

vim.fn.delete(work, "rf")
spec.finish()
