-- Verifies lathe.fold, the NV-3 fix that keeps the imports fold closed across a format-on-save
-- write. Covers the pure imports-fold selection over foldingRange results, the pre-save
-- closed/open snapshot, and the post-save re-close gating (re-close only when it was closed
-- before, no-op when the user had it open).
--
-- Self-contained: stubs the vim.lsp / vim.fn / vim.api surfaces lathe.fold touches, so it runs
-- headlessly with no real server -- run-specs.sh runs every *_spec.lua unconditionally.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/fold_spec.lua

local spec = require("spec_helper").new()
local fold = require("lathe.fold")

-- buf_request_sync-shaped results: a map keyed by client id, each with a `result` fold list.
local function folds(...)
  return { [1] = { result = { ... } } }
end

local IMPORTS = { kind = "imports", startLine = 2, endLine = 13 }
local REGION = { kind = "region", startLine = 15, endLine = 66 }

-- imports_start_line: pure selection of the imports fold's 0-based start line.
spec.check("imports_start_line picks the imports fold", fold.imports_start_line(folds(IMPORTS, REGION)), 2)
spec.check("imports_start_line ignores region-only folds", fold.imports_start_line(folds(REGION)), nil)
spec.check("imports_start_line on no folds", fold.imports_start_line(folds()), nil)
spec.check("imports_start_line on nil", fold.imports_start_line(nil), nil)

-- Stub the LSP + fold surfaces lathe.fold reads. make_text_document_params is called with a bufnr;
-- return a dummy params table so no real buffer is needed.
local orig = {
  buf_request_sync = vim.lsp.buf_request_sync,
  make_params = vim.lsp.util.make_text_document_params,
  foldclosed = vim.fn.foldclosed,
  win_findbuf = vim.fn.win_findbuf,
  win_call = vim.api.nvim_win_call,
  buf_is_valid = vim.api.nvim_buf_is_valid,
  defer_fn = vim.defer_fn,
  cmd = vim.cmd,
}

vim.lsp.util.make_text_document_params = function()
  return { uri = "file:///ws/App.java" }
end

local function stub_folds(list)
  vim.lsp.buf_request_sync = function()
    return { [1] = { result = list } }
  end
end

-- imports_closed: true only when the imports fold is reported and closed at its start line.
stub_folds({ IMPORTS, REGION })
vim.fn.foldclosed = function(lnum)
  return lnum == 3 and 3 or -1 -- fold closed at 1-based line 3 (startLine 2 + 1)
end
spec.check("imports_closed true when the imports fold is closed", fold.imports_closed(0), true)

vim.fn.foldclosed = function()
  return -1 -- nothing closed
end
spec.check("imports_closed false when the imports fold is open", fold.imports_closed(0), false)

stub_folds({ REGION })
spec.check("imports_closed false when there is no imports fold", fold.imports_closed(0), false)

-- reclose_imports: run deferred work synchronously and record foldclose targets.
local closed_at = {}
vim.api.nvim_buf_is_valid = function()
  return true
end
vim.fn.win_findbuf = function()
  return { 1000 }
end
vim.api.nvim_win_call = function(_, fn)
  return fn()
end
vim.fn.foldclosed = function()
  return -1 -- pretend the fold is open so close_fold always issues
end
vim.cmd = function(command)
  local lnum = tostring(command):match("^(%d+)foldclose$")
  if lnum then
    table.insert(closed_at, tonumber(lnum))
  end
end
-- Run only the first scheduled attempt, and swallow its self-reschedule, to keep the test bounded.
local ran = 0
vim.defer_fn = function(fn)
  ran = ran + 1
  if ran == 1 then
    fn()
  end
end

closed_at = {}
ran = 0
stub_folds({ IMPORTS, REGION })
fold.reclose_imports(0, false)
spec.check("reclose_imports no-op when it was open (nothing scheduled)", ran, 0)
spec.check("reclose_imports no-op when it was open (no foldclose)", #closed_at, 0)

closed_at = {}
ran = 0
fold.reclose_imports(0, true)
spec.check("reclose_imports closes the imports fold when it was closed", closed_at[1], 3)

-- Restore stubbed globals before finish() (which calls vim.cmd) so the harness can exit cleanly.
vim.lsp.buf_request_sync = orig.buf_request_sync
vim.lsp.util.make_text_document_params = orig.make_params
vim.fn.foldclosed = orig.foldclosed
vim.fn.win_findbuf = orig.win_findbuf
vim.api.nvim_win_call = orig.win_call
vim.api.nvim_buf_is_valid = orig.buf_is_valid
vim.defer_fn = orig.defer_fn
vim.cmd = orig.cmd

spec.finish("lathe.fold")
