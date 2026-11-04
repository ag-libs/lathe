-- When the Lathe server can answer discovery: the workspace has loaded, and a buffer is open on
-- the server. Discovery waits on both, so it never races the server and caches "no tests".
local common = require("lathe.neotest.common")

local M = {}

-- Workspace readiness gate. The Lathe server reports workspace load/reload as a $/progress task
-- under this title (mirrors WorkspaceSession.WORKSPACE_PROGRESS_TITLE); discovery is gated on that
-- progress ending, so a discovery that fires before the server is ready suspends instead of caching
-- "no tests" (the D1 startup race). Neovim backfills the begin title onto the end event, so the
-- title alone identifies the workspace-load completion.
local WORKSPACE_PROGRESS_TITLE = "Lathe: indexing workspace"
local READY_TIMEOUT_MS = 30000
local READY_POLL_MS = 100
local READY_AUGROUP = vim.api.nvim_create_augroup("LatheNeotestReady", { clear = true })
local workspace_ready = false

--- Marks the workspace ready on the first ready signal. Returns true only on that first transition
--- (false on every later reload's progress), so callers can run first-ready-only work exactly once.
--- The flag is await_ready's fast path; when the progress event is missed (the :LatheStart flow, see
--- await_ready) it stays false and await_ready falls back to waiting for the client to attach.
local function signal_ready()
  if workspace_ready then
    return false
  end

  workspace_ready = true
  return true
end

--- Re-runs neotest discovery for open Java test buffers once the workspace is ready, so a summary
--- that cached "no tests" before the server loaded fills in without the user re-opening the file.
local function rediscover_open_buffers()
  common.debug_log("[status] workspace ready → re-discovering open buffers")
  common.nio().run(function()
    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
      if vim.api.nvim_buf_is_loaded(buf) and vim.bo[buf].filetype == "java" then
        local name = vim.api.nvim_buf_get_name(buf)
        if name ~= "" and common.is_test_file(name) then
          -- Wrap the whole access: neotest.run is nil until neotest.setup() has run (e.g. the
          -- direct-drive harness never sets it up), and indexing it must not error the autocmd.
          pcall(function()
            require("neotest").run.get_tree_from_args(name)
          end)
        end
      end
    end
  end)
end

vim.api.nvim_create_autocmd("LspProgress", {
  group = READY_AUGROUP,
  pattern = "end",
  callback = function(ev)
    local client = vim.lsp.get_client_by_id(ev.data.client_id)
    if not client or client.name ~= "lathe" then
      return
    end

    local value = ev.data.params and ev.data.params.value
    if value and value.title == WORKSPACE_PROGRESS_TITLE then
      -- Only on the first ready transition: after that, neotest's own BufWritePost/CursorHold
      -- discovery keeps open buffers current, so re-sweeping on every reload is pure duplication.
      if signal_ready() then
        rediscover_open_buffers()
      end
    end
  end,
})

--- Suspends (event-loop friendly via nio.sleep) until `is_ready` returns true or `timeout_ms`
--- elapses. Bounded, so a signal that never arrives degrades to a timeout rather than a hang.
local function wait_until(is_ready, timeout_ms, poll_ms)
  local waited = 0
  while not is_ready() and waited < timeout_ms do
    common.nio().sleep(poll_ms)
    waited = waited + poll_ms
  end
end

--- Suspends until Lathe is ready to answer discovery, bounded by a timeout after which discovery is
--- attempted anyway -- so a degraded signal means a slower first discovery, never a broken one.
--- Called from neotest's async discovery.
---
--- Fast path: the workspace-load $/progress end was seen (filetype auto-start, where the adapter is
--- already loaded when the server loads). Otherwise we wait for a Lathe client to ATTACH rather than
--- for that progress event -- because in the :LatheStart flow the adapter lazy-loads only when the
--- first Java file opens, AFTER the progress has already fired, so the progress gate would never trip
--- and would block every discovery for the full timeout. Client presence means the server is up;
--- per-file readiness (the doc is open and compiled) is then enforced by await_open_confirmed, and a
--- discovery that still races is re-run by the DiagnosticChanged nudge.
function M.await_ready()
  if workspace_ready then
    return
  end

  wait_until(function()
    return #common.nio().lsp.get_clients({ name = "lathe" }) > 0
  end, READY_TIMEOUT_MS, READY_POLL_MS)
end

-- Whether the Lathe server has each buffer open: set by discovery on the buffer's first
-- diagnostics (see lathe.neotest.discovery), cleared on detach or delete.
local OPEN_CONFIRM_TIMEOUT_MS = 8000
local OPEN_CONFIRM_POLL_MS = 50
local open_confirmed = {}

--- Marks `bufnr` open on the server. True only the first time, so its caller acts once.
function M.confirm_open(bufnr)
  if open_confirmed[bufnr] then
    return false
  end

  open_confirmed[bufnr] = true
  return true
end

function M.is_open_confirmed(bufnr)
  return open_confirmed[bufnr] == true
end

vim.api.nvim_create_autocmd({ "LspDetach", "BufDelete" }, {
  group = READY_AUGROUP,
  callback = function(ev)
    open_confirmed[ev.buf] = nil
  end,
})

--- Suspends until the Lathe server confirms `bufnr` is open (its first diagnostics publish), so a
--- runnables pull is warm rather than racing didOpen. Bounded: after the timeout discovery proceeds
--- anyway, so a missed signal degrades to a possibly-empty discovery, never a hang. Called only for
--- a file that is actually open in the editor -- an unopened file (neotest's project sweep) has no
--- server-side open to wait for and skips this entirely.
function M.await_open_confirmed(bufnr)
  wait_until(function()
    return open_confirmed[bufnr]
  end, OPEN_CONFIRM_TIMEOUT_MS, OPEN_CONFIRM_POLL_MS)
end

return M
