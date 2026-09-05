-- Verifies lathe.instances (:LatheInstances): dispatches the lathe.instantiations command and fills
-- the quickfix from the returned locations, warns with no client, notifies on an empty result, and
-- registers the command. The LSP request and quickfix-item conversion are stubbed, so this loads
-- headlessly like the other specs.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/instances_spec.lua

local spec = require("spec_helper").new()
local instances = require("lathe.instances")

local function stub_client(handler)
  vim.lsp.get_clients = function(_)
    return { { name = "lathe", offset_encoding = "utf-16", request = handler } }
  end
  vim.lsp.util.make_position_params = function(_, _)
    return { textDocument = { uri = "file:///X.java" }, position = { line = 0, character = 0 } }
  end
end

do -- dispatches lathe.instantiations and fills the quickfix from the locations
  local requested
  stub_client(function(_, method, params, callback, _)
    requested = { method = method, command = params.command }
    callback(nil, { { uri = "file:///X.java", range = { start = { line = 2, character = 4 } } } })
  end)
  vim.lsp.util.locations_to_items = function(_, _)
    return { { filename = "/X.java", lnum = 3, col = 5, text = "new X()" } }
  end

  instances.find(0)

  spec.check("dispatch method", requested and requested.method, "workspace/executeCommand")
  spec.check("dispatch command", requested and requested.command, "lathe.instantiations")
  local qf = vim.fn.getqflist({ title = true, items = true })
  spec.check("quickfix title", qf.title, "Lathe: instantiation sites")
  spec.check("quickfix item count", #qf.items, 1)
end

do -- no attached client -> warns, no request
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end
  vim.lsp.get_clients = function(_)
    return {}
  end

  instances.find(0)

  spec.check("no client warns", warned, true)
end

do -- empty result -> notifies rather than opening an empty quickfix
  local notified = false
  vim.notify = function(_, _, _)
    notified = true
  end
  stub_client(function(_, _, _, callback, _)
    callback(nil, {})
  end)

  instances.find(0)

  spec.check("empty result notifies", notified, true)
end

do -- setup registers :LatheInstances
  instances.setup()
  spec.check("LatheInstances registered", vim.fn.exists(":LatheInstances"), 2)
end

spec.finish("instances_spec")
