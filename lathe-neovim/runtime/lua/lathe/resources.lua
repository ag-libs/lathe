-- :LatheResourceFind [name] -- find a resource by name across this workspace's reactor and
-- dependency resources, via the server's `lathe.resources` command. Each row shows a source icon and
-- coordinate (module / GAV) then the path, matching the find_files look; fuzzy matching is
-- case-insensitive and client-side, from Telescope's sorter or the `lathe.pick` fallback. A reactor
-- hit opens its editable file; a dependency hit is extracted read-only on open (`lathe.resourceOpen`).

local M = {}

-- Plain-Unicode source glyphs (no Nerd Font dependency), mirroring the origin convention used for
-- located results: a reactor (own-project) resource vs a Maven dependency resource.
local ICON_REACTOR = "\u{25A3}" -- ▣ white square with black centre
local ICON_DEPENDENCY = "\u{25C6}" -- ◆ black diamond

local util = require("lathe.util")
local notify = util.notify

-- "<icon>  <coordinate>" for the origin column, matching the find_files look: the GAV for a
-- dependency, the module for a reactor resource (bare icon when the module is unknown -- a workspace
-- synced by an older plugin, before the module was captured).
function M._origin_label(origin)
  local gav = origin:match("^dep:(.+)$")
  if gav then
    return ICON_DEPENDENCY .. "  " .. gav
  end

  local module = origin:match("^reactor:(.+)$")
  return module and (ICON_REACTOR .. "  " .. module) or ICON_REACTOR
end

-- Server ResourceEntry list -> picker entries displayed as "<icon>  <coordinate>  <path>" (source
-- first, like find_files). `ordinal` keeps the raw name + origin so a query narrows by either. The
-- kind/path/jar/entry fields drive the open. Pure, so it is unit-testable.
function M._entries(result)
  local entries = {}
  for _, r in ipairs(result or {}) do
    entries[#entries + 1] = {
      display = M._origin_label(r.origin) .. "  " .. r.name,
      ordinal = r.name .. "  " .. r.origin,
      kind = r.kind,
      path = r.path,
      jar = r.jar,
      entry = r.entry,
    }
  end
  return entries
end

-- Opens a picked resource: a reactor FILE opens its real editable source; a dependency JAR entry is
-- extracted read-only on the server, then the returned path is opened non-modifiable.
local function open_entry(entry, client)
  if entry.kind == "FILE" then
    vim.cmd.edit(vim.fn.fnameescape(entry.path))
    return
  end

  client:request("workspace/executeCommand", {
    command = "lathe.resourceOpen",
    arguments = { { jar = entry.jar, entry = entry.entry } },
  }, function(err, path)
    if err or not path then
      notify(err and err.message or "could not open resource", vim.log.levels.ERROR)
      return
    end

    vim.schedule(function()
      vim.cmd.edit(vim.fn.fnameescape(path))
      vim.bo.readonly = true
      vim.bo.modifiable = false
    end)
  end)
end

--- Lists every resource in the workspace (reactor + dependencies) and opens the picked one. `query`
--- prefills the Telescope prompt (the prefill-last-query preference); the fallback starts empty.
function M.find(query)
  local client = util.client()
  if not client then
    notify("server not attached (open a Java file first)", vim.log.levels.WARN)
    return
  end

  util.exec(client, nil, "lathe.resources", {}, function(result)
    if not result or #result == 0 then
      notify("no resources found", vim.log.levels.INFO)
      return
    end

    util.pick(M._entries(result), {
      title = "Lathe: resources",
      default_text = query,
      on_choice = function(entry)
        open_entry(entry, client)
      end,
    })
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheResourceFind", function(opts)
    M.find(opts.args ~= "" and opts.args or nil)
  end, { nargs = "?", desc = "Lathe: find a resource by name (reactor + dependencies)" })

  local augroup = vim.api.nvim_create_augroup("LatheResources", { clear = true })
  local cache = require("lathe.workspace").cache_root()
  -- Resource refresh: copy a saved resource into .lathe/ so the next test replay picks it up without
  -- a rebuild. The server maps the file against the real resource roots lathe:sync captured (a save
  -- that maps to no resource root is a no-op there), so forward any non-Java save inside a Lathe
  -- workspace. Skips .java (handled by the LSP) and cache files. The editor-agnostic path
  -- (workspace/didChangeWatchedFiles) can drive the same server command later; this autocmd is
  -- Neovim's uniform, dependency-free trigger.
  vim.api.nvim_create_autocmd("BufWritePost", {
    group = augroup,
    callback = function(ev)
      local name = vim.api.nvim_buf_get_name(ev.buf)
      if name == "" or name:match("%.java$") or vim.startswith(name, cache) then
        return
      end
      for _, client in ipairs(vim.lsp.get_clients({ name = "lathe" })) do
        if client.root_dir and vim.startswith(name, client.root_dir) then
          client:request("workspace/executeCommand", {
            command = "lathe.resource.refresh",
            arguments = { { uri = vim.uri_from_fname(name) } },
          })
          return
        end
      end
    end,
  })
end

return M
