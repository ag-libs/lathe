-- Verifies lathe.new (:LatheNew): picks the kind, resolves the buffer context and creates via the
-- server's lathe.resolveContext + lathe.createType commands, then writes/opens the returned file and
-- places the caret. The LSP client and the pickers are stubbed, so this loads headlessly. The client
-- holds no Java logic -- the server owns placement/skeleton/caret -- so the tests assert flow and IO.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/new_spec.lua

local spec = require("spec_helper").new()
local new = require("lathe.new")

-- A fake Lathe client answering executeCommand from `responses` (keyed by command); returns the list
-- of {command, argument} requests it received, in order.
local function stub_client(responses)
  local requests = {}
  vim.lsp.get_clients = function(_)
    return {
      {
        name = "lathe",
        request = function(_, _, params, callback, _)
          table.insert(requests, { command = params.command, argument = params.arguments[1] })
          callback(nil, responses[params.command])
        end,
      },
    }
  end
  return requests
end

local function stub_pickers(kindIndex, name)
  vim.ui.select = function(items, _, cb)
    cb(items[kindIndex])
  end
  vim.ui.input = function(_, cb)
    cb(name)
  end
end

-- ── _lines ───────────────────────────────────────────────────────────────────

do
  spec.check("lines drop the trailing empty", table.concat(new._lines("a\nb\n"), "|"), "a|b")
  spec.check("lines keep interior blanks", table.concat(new._lines("a\n\nb\n"), "|"), "a||b")
end

-- ── create() end to end ──────────────────────────────────────────────────────

do -- latheNew_kindThenName_createsViaServerAndWritesTheReturnedFile
  local path = vim.fn.tempname() .. "/module/src/main/java/com/example/Foo.java"
  local content = "package com.example;\n\npublic class Foo {\n\n}\n"
  local requests = stub_client({
    ["lathe.resolveContext"] = { moduleRel = "module", scope = "main", pkg = "com.example" },
    ["lathe.createType"] = { path = path, content = content, caret = { line = 3, character = 0 } },
  })
  stub_pickers(1, "Foo") -- Class

  new.create()

  spec.check("resolveContext first", requests[1] and requests[1].command, "lathe.resolveContext")
  spec.check("createType second", requests[2] and requests[2].command, "lathe.createType")
  local args = (requests[2] and requests[2].argument) or {}
  spec.check("createType type is the picked wire token", args.type, "class")
  spec.check("createType moduleRel from context", args.moduleRel, "module")
  spec.check("createType kind from context scope", args.kind, "main")
  spec.check("createType pkg from context", args.pkg, "com.example")
  spec.check("createType name from prompt", args.name, "Foo")
  spec.check("file created", vim.fn.filereadable(path), 1)
  spec.check(
    "content written exactly",
    table.concat(vim.fn.readfile(path), "\n"),
    "package com.example;\n\npublic class Foo {\n\n}"
  )
  spec.check("buffer opened", vim.api.nvim_buf_get_name(0):match("Foo%.java$") ~= nil, true)
end

do -- latheNew_recordInTestScope_mapsWireTokenAndCarriesScope
  local path = vim.fn.tempname() .. "/Point.java"
  local requests = stub_client({
    ["lathe.resolveContext"] = { moduleRel = "app", scope = "test", pkg = "com.verify" },
    ["lathe.createType"] = {
      path = path,
      content = "public record Point() {\n}\n",
      caret = { line = 0, character = 20 },
    },
  })
  stub_pickers(3, "Point") -- Record

  new.create()

  local args = (requests[2] and requests[2].argument) or {}
  spec.check("record wire token", args.type, "record")
  spec.check("test scope carried as kind", args.kind, "test")
end

do -- latheNew_serverNotAttached_errorsCleanly
  vim.lsp.get_clients = function(_)
    return {}
  end
  local picked = false
  vim.ui.select = function(_, _, _)
    picked = true
  end
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end

  new.create()

  spec.check("no server warns", warned, true)
  spec.check("no server never opens the kind picker", picked, false)
end

do -- latheNew_noContext_errorsCleanlyWithoutCreating
  local requests = stub_client({}) -- resolveContext resolves to nil
  stub_pickers(1, "Foo")
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end

  new.create()

  spec.check("no context warns", warned, true)
  spec.check("no context does not call createType", requests[2], nil)
end

do -- open_existingFile_refusesToOverwrite
  local path = vim.fn.tempname() .. "/Dup.java"
  vim.fn.mkdir(vim.fn.fnamemodify(path, ":h"), "p")
  vim.fn.writefile({ "existing" }, path)
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end

  new._open({ path = path, content = "new\n", caret = { line = 0, character = 0 } })

  spec.check("existing file warns", warned, true)
  spec.check("existing file not overwritten", table.concat(vim.fn.readfile(path), "\n"), "existing")
end

do -- setup registers :LatheNew
  new.setup()
  spec.check("LatheNew registered", vim.fn.exists(":LatheNew"), 2)
end

spec.finish("new_spec")
