-- Keeps the imports fold closed across a format-on-save write (NV-3).
--
-- nvim-ufo's recommended `close_fold_kinds_for_ft = { java = { 'imports' } }` closes the import
-- block only when a buffer is first displayed, never again. Format-on-save rewrites the whole
-- buffer via a full-document textEdit on BufWritePre, which makes ufo recompute folds and reopen
-- the imports fold on every save. We snapshot the fold's closed/open state before the format and,
-- if it was closed, re-close it once ufo has settled -- so a fold the user last chose survives the
-- save, while one they deliberately opened is left open.

local M = {}

local IMPORTS_KIND = 'imports'

--- Extract the 0-based start line of the imports fold from buf_request_sync-shaped foldingRange
--- results (a map of client-id -> { result = { folds... } }). Returns nil when no client reported
--- an imports-kind fold, e.g. a file with fewer than two imports.
---@param results table?
---@return integer?
function M.imports_start_line(results)
  if not results then
    return nil
  end

  for _, response in pairs(results) do
    for _, fold in ipairs(response.result or {}) do
      if fold.kind == IMPORTS_KIND then
        return fold.startLine
      end
    end
  end

  return nil
end

--- Ask the server (synchronously -- this runs on BufWritePre alongside the formatter's own sync
--- format) whether the imports fold is currently closed. Cheap: foldingRange is a parse-only
--- request. Returns false when there is no imports fold.
---@param bufnr integer
---@return boolean
function M.imports_closed(bufnr)
  local params = { textDocument = vim.lsp.util.make_text_document_params(bufnr) }
  local results = vim.lsp.buf_request_sync(bufnr, 'textDocument/foldingRange', params, 500)
  local line = M.imports_start_line(results)
  if not line then
    return false
  end

  return vim.fn.foldclosed(line + 1) ~= -1
end

--- Ensure the fold at `lnum` (1-based) is closed in every window showing the buffer, and report
--- whether it ended up closed. `:foldclose` is a no-op (swallowed) until ufo has (re)created the
--- fold, so this is safe to call before ufo's post-format recompute lands. Native fold commands
--- drive ufo's manual folds directly.
---@param bufnr integer
---@param lnum integer
---@return boolean closed
local function ensure_closed(bufnr, lnum)
  local closed = false
  for _, win in ipairs(vim.fn.win_findbuf(bufnr)) do
    vim.api.nvim_win_call(win, function()
      if vim.fn.foldclosed(lnum) == -1 then
        pcall(vim.cmd, lnum .. 'foldclose')
      end
      if vim.fn.foldclosed(lnum) ~= -1 then
        closed = true
      end
    end)
  end

  return closed
end

-- Re-close cadence: poll every RETRY_MS, up to MAX_ATTEMPTS, and stop once the fold has stayed
-- closed for STABLE_TICKS consecutive polls. ufo recreates the imports fold (open) some time after
-- the format rewrite, so a single early re-close misses it; we keep re-closing until it sticks. A
-- save leaves the buffer quiescent, so ufo recomputes once and the fold then stays closed.
local RETRY_MS = 80
local MAX_ATTEMPTS = 25
local STABLE_TICKS = 3

--- Re-close the imports fold if it was closed before the save, restoring the state the format
--- rewrite reset. No-op when the user had the fold open (`was_closed` false) or the buffer is gone.
---@param bufnr integer
---@param was_closed boolean
function M.reclose_imports(bufnr, was_closed)
  if not was_closed then
    return
  end

  local attempts = 0
  local stable = 0
  local lnum = nil
  local function attempt()
    attempts = attempts + 1
    if not vim.api.nvim_buf_is_valid(bufnr) then
      return
    end

    if not lnum then
      local params = { textDocument = vim.lsp.util.make_text_document_params(bufnr) }
      local results = vim.lsp.buf_request_sync(bufnr, 'textDocument/foldingRange', params, 500)
      local line = M.imports_start_line(results)
      if line then
        lnum = line + 1
      end
    end

    if lnum and ensure_closed(bufnr, lnum) then
      stable = stable + 1
    else
      stable = 0
    end

    if stable < STABLE_TICKS and attempts < MAX_ATTEMPTS then
      vim.defer_fn(attempt, RETRY_MS)
    end
  end

  vim.defer_fn(attempt, RETRY_MS)
end

return M
