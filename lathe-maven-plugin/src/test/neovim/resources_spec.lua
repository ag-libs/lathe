-- Verifies lathe.resources (:LatheResourceFind): dispatches the lathe.resources command, maps the
-- result into "<name>  <origin>" picker entries, and opens the picked one -- a reactor FILE opens its
-- editable path, a dependency JAR dispatches lathe.resourceOpen and opens the extracted path. Warns
-- with no client and notifies on an empty result. Telescope is absent headlessly, so the built-in
-- `lathe.pick` fallback is exercised, stubbed via package.loaded so no window opens.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/resources_spec.lua

local spec = require("spec_helper").new()

local picked
package.loaded["lathe.pick"] = {
  pick = function(opts)
    picked = opts
  end,
}

local resources = require("lathe.resources")

local function stub_client(handler)
  vim.lsp.get_clients = function(_)
    return { { name = "lathe", request = handler } }
  end
end

do -- _entries maps a resource list into "<icon>  <coordinate>  <path>" picker entries
  local entries = resources._entries({
    { name = "a.txt", origin = "reactor:core", kind = "FILE", path = "/w/a.txt", jar = "", entry = "" },
  })

  -- display leads with the source icon + module (find_files look); ordinal keeps name + origin
  spec.check("entry display icon+module+path", entries[1].display, "\u{25A3}  core  a.txt")
  spec.check("entry ordinal keeps name and origin", entries[1].ordinal, "a.txt  reactor:core")
  spec.check("entry kind", entries[1].kind, "FILE")
  spec.check("entry path", entries[1].path, "/w/a.txt")
end

do -- origin labels: dependency GAV, reactor module, and bare reactor (un-resynced workspace)
  spec.check("dependency label", resources._origin_label("dep:g:a:1"), "\u{25C6}  g:a:1")
  spec.check("reactor module label", resources._origin_label("reactor:app"), "\u{25A3}  app")
  spec.check("bare reactor label", resources._origin_label("reactor"), "\u{25A3}")
end

do -- dispatches lathe.resources and feeds entries into the picker; open routes by kind
  local commands = {}
  stub_client(function(_, method, params, callback, _)
    commands[#commands + 1] = params.command
    if params.command == "lathe.resources" then
      callback(nil, {
        {
          name = "com/x/schema.graphqls",
          origin = "reactor:app",
          kind = "FILE",
          path = "/w/app/src/main/resources/com/x/schema.graphqls",
          jar = "",
          entry = "",
        },
        {
          name = "META-INF/config.xml",
          origin = "dep:g:a:1",
          kind = "JAR",
          path = "",
          jar = "/j/lib.jar",
          entry = "META-INF/config.xml",
        },
      })
    else
      callback(nil, "/cache/lib/META-INF/config.xml")
    end
  end)

  resources.find("schema")

  spec.check("dispatch method command", commands[1], "lathe.resources")
  spec.check("entry count", picked and #picked.items, 2)
  spec.check(
    "display leads with source icon + module + path",
    picked.items[1].display,
    "\u{25A3}  app  com/x/schema.graphqls"
  )

  local orig_cmd, orig_schedule = vim.cmd, vim.schedule
  local edited
  vim.cmd = { edit = function(arg) edited = arg end }
  vim.schedule = function(fn) fn() end

  -- a reactor FILE opens its editable path directly
  picked.on_choice(picked.items[1])
  spec.check("file entry opens its editable path", edited, "/w/app/src/main/resources/com/x/schema.graphqls")

  -- a dependency JAR extracts on the server (lathe.resourceOpen), then opens the returned path
  picked.on_choice(picked.items[2])
  spec.check("jar entry dispatches lathe.resourceOpen", commands[#commands], "lathe.resourceOpen")
  spec.check("jar entry opens the extracted path", edited, "/cache/lib/META-INF/config.xml")

  vim.cmd, vim.schedule = orig_cmd, orig_schedule
end

do -- no client -> warns
  vim.lsp.get_clients = function(_)
    return {}
  end
  local warned = false
  vim.notify = function(_, level, _)
    if level == vim.log.levels.WARN then
      warned = true
    end
  end

  resources.find(nil)

  spec.check("no client warns", warned, true)
end

do -- empty result -> notifies
  stub_client(function(_, _, _, callback, _)
    callback(nil, {})
  end)
  local informed = false
  vim.notify = function(_, level, _)
    if level == vim.log.levels.INFO then
      informed = true
    end
  end

  resources.find(nil)

  spec.check("empty result notifies", informed, true)
end

spec.finish()
