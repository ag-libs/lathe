-- Verifies lathe.typehierarchy (:LatheTypeHierarchy): dispatches the lathe.typeHierarchy command and
-- feeds a tagged, ordered entry list (supertypes ▲, self ●, subtypes ▼) into the picker; warns with
-- no client, notifies when the cursor resolves to no type, warns on a truncated result, jumps on
-- select, and registers the command. Telescope is absent headlessly, so the built-in `lathe.pick`
-- fallback is exercised; it is stubbed via package.loaded so no window opens.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/typehierarchy_spec.lua

local spec = require("spec_helper").new()

local picked
package.loaded["lathe.pick"] = {
  pick = function(opts)
    picked = opts
  end,
}

local typehierarchy = require("lathe.typehierarchy")

local function stub_client(handler)
  vim.lsp.get_clients = function(_)
    return { { name = "lathe", offset_encoding = "utf-16", request = handler } }
  end
  vim.lsp.util.make_position_params = function(_, _)
    return { textDocument = { uri = "file:///X.java" }, position = { line = 0, character = 0 } }
  end
end

local function item(name, line)
  return {
    name = name,
    detail = "com.example",
    uri = "file:///" .. name .. ".java",
    range = { start = { line = line, character = 10 } },
  }
end

do -- dispatches lathe.typeHierarchy and feeds tagged, ordered entries into the picker
  local requested
  stub_client(function(_, method, params, callback, _)
    requested = { method = method, command = params.command }
    callback(nil, {
      supertypes = { item("Parent", 0) },
      self = item("Service", 1),
      subtypes = { item("Direct", 2), item("Grandchild", 3) },
      truncated = false,
    })
  end)

  typehierarchy.show(0)

  spec.check("dispatch method", requested and requested.method, "workspace/executeCommand")
  spec.check("dispatch command", requested and requested.command, "lathe.typeHierarchy")
  spec.check("entry count", picked and #picked.items, 4)
  spec.check("supertype tagged first", vim.startswith(picked.items[1].display, "▲"), true)
  spec.check("self tagged second", vim.startswith(picked.items[2].display, "●"), true)
  spec.check("subtype tagged", vim.startswith(picked.items[3].display, "▼"), true)
  spec.check("ordinal is fqn", picked.items[2].ordinal, "com.example.Service")
end

do -- selecting an entry jumps to its location
  local shown
  vim.lsp.util.show_document = function(location, _, _)
    shown = location
  end

  picked.on_choice(picked.items[3])

  spec.check("select jumps to location", shown and shown.uri, "file:///Direct.java")
end

do -- truncated result -> warns
  local warned = false
  vim.notify = function(_, level, _)
    if level == vim.log.levels.WARN then
      warned = true
    end
  end
  stub_client(function(_, _, _, callback, _)
    callback(nil, { supertypes = {}, self = item("Service", 1), subtypes = {}, truncated = true })
  end)

  typehierarchy.show(0)

  spec.check("truncated warns", warned, true)
end

do -- no type under the cursor (nil self) -> notifies, no picker
  local notified = false
  vim.notify = function(_, _, _)
    notified = true
  end
  picked = nil
  stub_client(function(_, _, _, callback, _)
    callback(nil, { supertypes = {}, self = nil, subtypes = {}, truncated = false })
  end)

  typehierarchy.show(0)

  spec.check("no type notifies", notified, true)
  spec.check("no picker opened", picked, nil)
end

do -- no attached client -> warns, no request
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end
  vim.lsp.get_clients = function(_)
    return {}
  end

  typehierarchy.show(0)

  spec.check("no client warns", warned, true)
end

do -- setup registers :LatheTypeHierarchy
  typehierarchy.setup()
  spec.check("LatheTypeHierarchy registered", vim.fn.exists(":LatheTypeHierarchy"), 2)
end

spec.finish("typehierarchy_spec")
