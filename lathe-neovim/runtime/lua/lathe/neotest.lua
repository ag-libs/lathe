-- neotest adapter for Lathe (requires https://github.com/nvim-neotest/neotest).
--
-- Installation: alongside require('lathe').setup(), configure neotest with this adapter:
--   require('neotest').setup({ adapters = { require('lathe.neotest') } })
--
-- Discovery and execution both go through the already-running Lathe LSP server rather
-- than spawning Maven or a treesitter-query scan: discover_positions calls
-- lathe.runnables.list (real attributed-analysis discovery, not syntax guessing), and
-- build_spec calls lathe.run.test synchronously via nio.lsp, replaying from captured
-- .lathe/ bytecode -- no Maven invocation, no recompilation. build_spec is not declared
-- async but neotest always invokes it from inside TestRunner:run_tree's async chain, so
-- a yielding nio.lsp call inside it is valid.
--
-- The adapter is this table; its work lives in lathe.neotest.*: readiness (when the server can
-- answer), discovery (the position tree), spec (runs and debug sessions), results (what a run
-- reports) and ui (re-run failed, diff, quickfix). None loads neotest or nio until it runs, so
-- requiring this module works without them.
local discovery = require("lathe.neotest.discovery")
local results = require("lathe.neotest.results")
local spec = require("lathe.neotest.spec")
local ui = require("lathe.neotest.ui")

local M = {}
M.name = "neotest-lathe"

M.root = discovery.root
M.filter_dir = discovery.filter_dir
M.is_test_file = require("lathe.neotest.common").is_test_file
M.discover_positions = discovery.discover_positions
M.build_spec = spec.build_spec
M.results = results.results

-- Commands to bind: stop the runs in flight, re-run the first failing test, compare a failure's
-- values, list the last run's tests, and open the shared test console.
M.stop = spec.stop
M.run_first_failed = ui.run_first_failed
M.open_diff = ui.open_diff
M.open_results = ui.open_results
M.open_output = require("lathe.output").open

return M
