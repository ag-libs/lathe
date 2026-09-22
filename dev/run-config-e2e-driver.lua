-- Headless E2E for named run configurations, driven against the LIVE server over the built
-- multi-module invoker fixture. Exercises the whole pipeline a user drives: save a config from a
-- runnable, list it, complete its name, and run it by name (a real replay), asserting the console
-- surfaces the active config. Local dev tooling; not shipped.

local spec = require("spec_helper").new()

local fixture = assert(os.getenv("LATHE_E2E_FIXTURE"), "LATHE_E2E_FIXTURE not set")
local main_file = fixture .. "/jpms/src/main/java/com/example/jpms/HelloMain.java"
local run_json = fixture .. "/.lathe/run.json"
os.remove(run_json)

-- Headless: auto-dismiss the server's staleness sync prompt (a showMessageRequest) so it never
-- blocks on input, and neuter vim.ui.select for the same reason.
vim.lsp.handlers["window/showMessageRequest"] = function()
  return vim.NIL
end
vim.ui.select = function(_, _, on_choice)
  on_choice(nil)
end

require("lathe").setup()
vim.cmd("edit " .. main_file)

local attached = vim.wait(30000, function()
  return #vim.lsp.get_clients({ name = "lathe" }) > 0
end, 50)
spec.check("server attaches on cold open", attached, true)
if not attached then
  spec.finish("run-config-e2e")
  return
end

local client = vim.lsp.get_clients({ name = "lathe" })[1]

local function exec(command, args)
  local res = client:request_sync("workspace/executeCommand", {
    command = command,
    arguments = { args },
  }, 30000, 0)
  assert(res, command .. " returned nil")
  assert(not res.err, command .. " err: " .. vim.inspect(res.err))
  return res.result
end

-- 1. Save the main under the module as a named config -> writes .lathe/run.json only.
local saved = exec("lathe.runconfig.save", {
  name = "dev",
  moduleRel = "jpms",
  kind = "MAIN",
  mainClass = "com.example.jpms.HelloMain",
  overwrite = true,
})
spec.check("save returns the name", saved and saved.name, "dev")
spec.check("save wrote .lathe/run.json", vim.fn.filereadable(run_json), 1)

-- 2. List configs -> the saved one is selectable.
local configs = exec("lathe.runconfigs.list", {})
local listed
for _, c in ipairs(configs or {}) do
  if c.name == "dev" then
    listed = c
  end
end
spec.check("list includes the saved config", listed ~= nil, true)
spec.check("list carries the pinned target", listed and listed.target, "com.example.jpms.HelloMain")

-- 3. Client completion over the cached names.
local run = require("lathe.run")
run.refresh_configs()
vim.wait(3000, function()
  return #run.complete_config("d") > 0
end, 50)
spec.check("completion offers the config name", run.complete_config("d")[1], "dev")

-- 4. Refusing to overwrite without the bang.
local res = client:request_sync("workspace/executeCommand", {
  command = "lathe.runconfig.save",
  arguments = { { name = "dev", moduleRel = "jpms", kind = "MAIN", mainClass = "com.example.jpms.HelloMain" } },
}, 30000, 0)
spec.check("save without bang refuses an existing name", res.err ~= nil, true)

-- 5. Run the config by name -> a real replay of the pinned main.
local outcome = exec("lathe.run.named", { name = "dev", token = "e2e-run" })
spec.check("run.named launched", outcome and outcome.launched, true)
spec.check("run.named exited 0", outcome and outcome.exitCode, 0)

-- 6. The console header surfaced the active config.
vim.wait(2000, function()
  local lines = require("lathe.output").lines() or {}
  for _, l in ipairs(lines) do
    if l == "config: dev" then
      return true
    end
  end
  return false
end, 50)
local header_seen = false
for _, l in ipairs(require("lathe.output").lines() or {}) do
  if l == "config: dev" then
    header_seen = true
  end
end
spec.check("console header shows config: dev", header_seen, true)

-- 7. Running an unknown config errors cleanly.
local miss = client:request_sync("workspace/executeCommand", {
  command = "lathe.run.named",
  arguments = { { name = "nope", token = "e2e-miss" } },
}, 30000, 0)
spec.check("unknown config errors", miss.err ~= nil, true)

spec.finish("run-config-e2e")
