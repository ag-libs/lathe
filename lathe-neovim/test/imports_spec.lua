-- Verifies lathe.imports (:LatheMissingImports): dispatches the lathe.missingImports command, then
-- auto-adds unambiguous names, prompts one at a time for ambiguous ones, reports unresolvable names,
-- applies the edits lathe.importEdits returns for the chosen imports, warns when an older server has no
-- such command, notifies with no client / no missing imports, and registers the command plus the
-- client-side "Add missing imports…" handler.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/imports_spec.lua

local spec = require("spec_helper").new()

local imports = require("lathe.imports")

local applied
vim.lsp.util.apply_text_edits = function(edits, _, _)
  applied = edits
end

vim.uri_from_bufnr = function(_)
  return "file:///X.java"
end

-- What a server places for the chosen imports; the client applies it verbatim.
local SERVER_EDITS = {
  { range = { start = { line = 1, character = 0 }, ["end"] = { line = 1, character = 0 } }, newText = "placed by the server" },
}

-- Answers lathe.missingImports with result and lathe.importEdits with edits, recording each request.
local requests
local function stub_client(result, edits)
  requests = {}
  vim.lsp.get_clients = function(_)
    return {
      {
        name = "lathe",
        offset_encoding = "utf-16",
        request = function(_, method, params, callback, _)
          requests[#requests + 1] = { method = method, command = params.command, arguments = params.arguments }
          if params.command == "lathe.importEdits" then
            callback(nil, edits)
          else
            callback(nil, result)
          end
        end,
      },
    }
  end
end

-- Auto-select the given candidate index for every ambiguous prompt (nil = pick "Skip").
local function stub_select(choose)
  vim.ui.select = function(items, _, on_choice)
    on_choice(choose and items[choose] or nil)
  end
end

local function capture_notify()
  local seen = {}
  vim.notify = function(m, level, _)
    seen.message, seen.level = m, level
  end
  return seen
end

do -- unambiguous auto-added, ambiguous prompted, unresolved reported; the server places the edits
  applied = nil
  local seen = capture_notify()
  stub_client({
    items = {
      { name = "ArrayList", candidates = { "java.util.ArrayList" } },
      { name = "List", candidates = { "java.awt.List", "java.util.List" } },
      { name = "Widget", candidates = {} },
    },
  }, SERVER_EDITS)
  stub_select(2) -- pick java.util.List for the ambiguous name

  imports.run(0)

  spec.check("dispatch method", requests[1].method, "workspace/executeCommand")
  spec.check("dispatch command", requests[1].command, "lathe.missingImports")
  spec.check("argument uri", requests[1].arguments[1].uri, "file:///X.java")
  spec.check("edits requested", requests[2].command, "lathe.importEdits")
  spec.check("edits for this buffer", requests[2].arguments[1].uri, "file:///X.java")
  spec.check(
    "edits for the chosen imports",
    vim.deep_equal(requests[2].arguments[1].imports, { "java.util.ArrayList", "java.util.List" }),
    true
  )
  spec.check("server edits applied verbatim", applied, SERVER_EDITS)
  spec.check("summary counts auto and chosen", seen.message:find("1 auto, 1 chosen") ~= nil, true)
  spec.check("summary reports unresolved", seen.message:find("Widget") ~= nil, true)
end

do -- skipping an ambiguous name leaves it out; only the unambiguous one is requested
  applied = nil
  stub_client({
    items = {
      { name = "Instant", candidates = { "java.time.Instant" } },
      { name = "List", candidates = { "java.awt.List", "java.util.List" } },
    },
  }, SERVER_EDITS)
  stub_select(nil) -- Skip the ambiguous name

  imports.run(0)

  spec.check(
    "skips ambiguous, requests unambiguous",
    vim.deep_equal(requests[2].arguments[1].imports, { "java.time.Instant" }),
    true
  )
end

do -- a server without lathe.importEdits answers nothing: warn, apply nothing, claim nothing added
  applied = nil
  local seen = capture_notify()
  stub_client({ items = { { name = "Instant", candidates = { "java.time.Instant" } } } }, nil)

  imports.run(0)

  spec.check("older server: no edit applied", applied, nil)
  spec.check("older server: warns", seen.level, vim.log.levels.WARN)
  spec.check("older server: names the remedy", seen.message:find("update the Lathe build plugin") ~= nil, true)
end

do -- no missing imports -> notify, no edits requested or applied
  applied = nil
  local seen = capture_notify()
  stub_client({ items = {} }, SERVER_EDITS)

  imports.run(0)

  spec.check("no missing imports notifies", seen.message:find("no missing imports") ~= nil, true)
  spec.check("no edits requested", #requests, 1)
  spec.check("no edit applied", applied, nil)
end

do -- no attached client -> warns, no request
  local warned = false
  vim.notify = function(_, level, _)
    if level == vim.log.levels.WARN then
      warned = true
    end
  end
  vim.lsp.get_clients = function(_)
    return {}
  end

  imports.run(0)

  spec.check("no client warns", warned, true)
end

do -- setup registers the command and the client-side code-action handler
  imports.setup()
  spec.check("LatheMissingImports registered", vim.fn.exists(":LatheMissingImports"), 2)
  spec.check("code-action handler registered", type(vim.lsp.commands["lathe.missingImports"]), "function")
end

spec.finish("imports_spec")
