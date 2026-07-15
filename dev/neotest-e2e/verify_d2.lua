-- D2 ("discovery stays current") edge-case verification, driven against a LIVE
-- Lathe server over the multi-module fixture. Separate from driver.lua so the
-- committed e2e signal stays focused; this is a one-off check that mutating a
-- test file's methods (add / rename / remove, with a save, plus add-without-
-- save) is reflected by a fresh discover_positions -- proving the freshness
-- half of D2. The trigger half (neotest re-runs discover on BufWritePost) is
-- established by reading neotest core (client/init.lua BufWritePost autocmd ->
-- _update_positions -> adapter.discover_positions); here we exercise the
-- adapter+server path each such trigger drives.
--
-- It writes a scratch HelloD2Test.java into the fixture's test package (never
-- the committed HelloTest.java) and deletes it at the end.

local spec = require("spec_helper").new()
local nio = require("nio")

local fixture = assert(os.getenv("LATHE_E2E_FIXTURE"), "LATHE_E2E_FIXTURE not set")
local pkg_dir = fixture .. "/jpms/src/test/java/com/example/jpms"
local file = pkg_dir .. "/HelloD2Test.java"
local CLASS = "com.example.jpms.HelloD2Test"

local function source(methods)
  local body = {}
  for _, m in ipairs(methods) do
    body[#body + 1] = "  @Test\n  void " .. m .. "() {}\n"
  end
  return table.concat({
    "package com.example.jpms;",
    "",
    "import org.junit.jupiter.api.Test;",
    "",
    "class HelloD2Test {",
    table.concat(body, "\n"),
    "}",
    "",
  }, "\n")
end

local function id(method)
  return CLASS .. "#" .. method .. "()"
end

-- Write initial content to disk, then open it so the LSP sees a real didOpen.
vim.fn.mkdir(pkg_dir, "p")
local function write_disk(methods)
  local f = assert(io.open(file, "w"))
  f:write(source(methods))
  f:close()
end

write_disk({ "alpha" })

require("lathe").setup()
vim.cmd("edit " .. file)

local attached = vim.wait(30000, function()
  return #vim.lsp.get_clients({ name = "lathe" }) > 0
end, 50)
spec.check("server attaches", attached, true)

--- Replace the whole buffer with source(methods) and, when save is true, :write
--- it (fires didSave + BufWritePost, exactly the save path neotest re-discovers
--- on). When save is false the edit stays in the buffer (didChange only), to
--- test the add-without-save case.
local function set_buffer(methods, save)
  local lines = vim.split(source(methods), "\n", { plain = true })
  vim.bo.modifiable = true
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  if save then
    vim.cmd("write")
  end
end

--- Poll discover_positions until the discovered "test" id set satisfies pred, or
--- a timeout. Returns the final id set either way so a failing check reports the
--- actual contents. Runs inside the caller's nio context.
local function discover_until(pred)
  local adapter = require("lathe.neotest")
  local ids = {}
  local deadline = 6000
  local waited = 0
  while true do
    ids = {}
    local tree = adapter.discover_positions(file)
    if tree then
      for _, pos in tree:iter() do
        if pos.type == "test" then
          ids[pos.id] = true
        end
      end
    end

    if pred(ids) or waited >= deadline then
      return ids
    end

    nio.sleep(200)
    waited = waited + 200
  end
end

local done = false
local r = {}
nio.run(function()
  local ok, err = pcall(function()
    -- Baseline: the single seeded method is discovered.
    r.baseline = discover_until(function(ids)
      return ids[id("alpha")]
    end)

    -- ADD + save: a newly added @Test method appears (the case already seen by hand).
    set_buffer({ "alpha", "beta" }, true)
    r.added = discover_until(function(ids)
      return ids[id("beta")]
    end)

    -- RENAME + save: the renamed method replaces the old id.
    set_buffer({ "alpha", "renamedBeta" }, true)
    r.renamed = discover_until(function(ids)
      return ids[id("renamedBeta")] and not ids[id("beta")]
    end)

    -- REMOVE + save: the removed method disappears.
    set_buffer({ "alpha" }, true)
    r.removed = discover_until(function(ids)
      return not ids[id("renamedBeta")] and ids[id("alpha")]
    end)

    -- ADD without save: a didChange-only edit is still reflected (the server
    -- tracks buffer content, and runnables attributes exactly that content).
    set_buffer({ "alpha", "unsaved" }, false)
    r.unsaved = discover_until(function(ids)
      return ids[id("unsaved")]
    end)
  end)
  r.err = (not ok) and tostring(err) or nil
  done = true
end)
vim.wait(60000, function()
  return done
end, 50)

spec.check("driver finished without error", r.err, nil)
spec.check("D2 baseline: seeded method discovered", (r.baseline or {})[id("alpha")], true)
spec.check("D2 add+save: new method discovered", (r.added or {})[id("beta")], true)
spec.check("D2 add+save: original method still present", (r.added or {})[id("alpha")], true)
spec.check("D2 rename+save: renamed method discovered", (r.renamed or {})[id("renamedBeta")], true)
spec.check("D2 rename+save: old name gone", (r.renamed or {})[id("beta")], nil)
spec.check("D2 remove+save: removed method gone", (r.removed or {})[id("renamedBeta")], nil)
spec.check("D2 remove+save: surviving method still present", (r.removed or {})[id("alpha")], true)
spec.check("D2 add without save: didChange edit reflected", (r.unsaved or {})[id("unsaved")], true)

-- Clean up the scratch file so the fixture is left as it was found.
pcall(function()
  vim.cmd("bwipeout! " .. vim.fn.bufnr(file))
end)
os.remove(file)

spec.finish("verify-d2")
