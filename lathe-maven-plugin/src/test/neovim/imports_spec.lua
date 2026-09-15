-- Verifies lathe.imports (:LatheMissingImports): dispatches the lathe.missingImports command, then
-- auto-adds unambiguous names, prompts one at a time for ambiguous ones, reports unresolvable names,
-- inserts the chosen imports as one edit, notifies with no client / no missing imports, and registers
-- the command plus the client-side "Add missing imports…" handler.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/imports_spec.lua

local spec = require("spec_helper").new()

local imports = require("lathe.imports")

local applied
vim.lsp.util.apply_text_edits = function(edits, _, _)
  applied = edits
end

vim.uri_from_bufnr = function(_)
  return "file:///X.java"
end

local function stub_client(result)
  vim.lsp.get_clients = function(_)
    return {
      {
        name = "lathe",
        offset_encoding = "utf-16",
        request = function(_, method, params, callback, _)
          _G.requested = { method = method, command = params.command, arguments = params.arguments }
          callback(nil, result)
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

do -- unambiguous auto-added, ambiguous prompted, unresolved reported, all in one edit
  applied = nil
  local message
  vim.notify = function(m, _, _)
    message = m
  end
  stub_client({
    insertionRange = { start = { line = 1, character = 0 }, ["end"] = { line = 1, character = 0 } },
    items = {
      { name = "ArrayList", candidates = { "java.util.ArrayList" } },
      { name = "List", candidates = { "java.awt.List", "java.util.List" } },
      { name = "Widget", candidates = {} },
    },
  })
  stub_select(2) -- pick java.util.List for the ambiguous name

  imports.run(0)

  spec.check("dispatch method", _G.requested.method, "workspace/executeCommand")
  spec.check("dispatch command", _G.requested.command, "lathe.missingImports")
  spec.check("argument uri", _G.requested.arguments[1].uri, "file:///X.java")
  spec.check("one edit applied", applied and #applied, 1)
  spec.check(
    "edit adds both chosen imports",
    applied[1].newText,
    "import java.util.ArrayList;\nimport java.util.List;\n"
  )
  spec.check("insertion at server range", applied[1].range.start.line, 1)
  spec.check("summary counts auto and chosen", message:find("1 auto, 1 chosen") ~= nil, true)
  spec.check("summary reports unresolved", message:find("Widget") ~= nil, true)
end

do -- skipping an ambiguous name leaves it out; only the unambiguous one is added
  applied = nil
  stub_client({
    insertionRange = { start = { line = 0, character = 0 }, ["end"] = { line = 0, character = 0 } },
    items = {
      { name = "Instant", candidates = { "java.time.Instant" } },
      { name = "List", candidates = { "java.awt.List", "java.util.List" } },
    },
  })
  stub_select(nil) -- Skip the ambiguous name

  imports.run(0)

  spec.check("skips ambiguous, adds unambiguous", applied[1].newText, "import java.time.Instant;\n")
end

do -- no missing imports -> notify, no edit
  applied = nil
  local message
  vim.notify = function(m, _, _)
    message = m
  end
  stub_client({ insertionRange = nil, items = {} })

  imports.run(0)

  spec.check("no missing imports notifies", message:find("no missing imports") ~= nil, true)
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
