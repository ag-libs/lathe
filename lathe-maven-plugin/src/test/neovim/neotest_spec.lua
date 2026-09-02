-- Verifies lathe.neotest's position-forest builder: the pure, neotest/nio-independent
-- transformation from lathe.runnables.list's flat, parentId-linked RunTarget list into
-- the nested-list shape neotest.types.Tree.from_list expects. Exercises exactly the bug
-- class found during manual validation: a class's real parent (its package) is
-- deliberately not a tracked node, and naively treating "grouped under some parentId key"
-- as "has a real parent" silently dropped every top-level class from the tree.
--
-- Self-contained: does not require neotest or nio to be installed. lathe.neotest itself
-- requires them lazily (only inside functions that actually talk to a live client), so
-- require('lathe.neotest') and _build_position_forest work without either on the
-- runtimepath -- this spec would otherwise break the build on any machine that doesn't
-- have the optional neotest plugin installed, since run-specs.sh runs every *_spec.lua
-- file unconditionally.
--
-- Run headless from the repo root (or via run-specs.sh, which runs every `*_spec.lua`
-- file in this directory the same way):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/neotest_spec.lua

local spec = require("spec_helper").new()
local adapter = require("lathe.neotest")

local FILE = "/workspace/demo/src/test/java/demo/FooTest.java"

--- Builds the {stream, text} tagged transcript shape LaunchOutcome.output now
--- carries (stream 0 = stdout) from plain strings, so these fixtures mirror the
--- real wire shape the adapter consumes.
local function transcript(...)
  local lines = {}
  for _, text in ipairs({ ... }) do
    lines[#lines + 1] = { stream = 0, text = text }
  end
  return lines
end

--- Flattens the nested-list forest (Tree.from_list's expected shape: a leaf position
--- table, or `{position, child_subtree, ...}`) into a plain id -> position map, so
--- assertions can check reachability and fields without hand-walking the nesting.
local function flatten(forest)
  local by_id = {}
  local function visit(entry)
    if entry.id then
      by_id[entry.id] = entry
      return
    end
    by_id[entry[1].id] = entry[1]
    for i = 2, #entry do
      visit(entry[i])
    end
  end
  visit(forest)
  return by_id
end

-- Case 1: method -> class -> package. The package is deliberately not a
-- tracked node here: nesting it under whichever single file's discovery
-- happened to report it puts it at the wrong tree level (a file is inside
-- its package, not the reverse). Package-level running is bound instead to
-- the directory node (see _package_for_dir's own coverage below), so the
-- class must fall through to the file root directly, not get silently
-- dropped because it's "grouped" under the package's id in the parent-
-- linking pass.
do
  local targets = {
    {
      id = "demo.FooTest#bar_condition_result()",
      parentId = "demo.FooTest",
      kind = 1, -- TEST_METHOD
      label = "bar_condition_result",
      moduleRel = "demo",
      range = { start = { line = 5, character = 2 }, ["end"] = { line = 7, character = 3 } },
    },
    {
      id = "demo.FooTest",
      parentId = "demo",
      kind = 2, -- TEST_CLASS
      label = "FooTest",
      moduleRel = "demo",
      range = { start = { line = 2, character = 0 }, ["end"] = { line = 8, character = 1 } },
    },
    {
      id = "demo",
      parentId = "",
      kind = 3, -- TEST_PACKAGE, excluded from the forest entirely
      label = "demo",
      moduleRel = "demo",
      range = { start = { line = 0, character = 0 }, ["end"] = { line = 8, character = 1 } },
    },
  }

  local forest = adapter._build_position_forest(FILE, targets)
  local by_id = flatten(forest)

  spec.check("root is the file", forest[1].type, "file")
  spec.check("forest has file root + one top-level entry", #forest, 2)
  spec.check("package excluded from forest", by_id["demo"], nil)

  local class_pos = by_id["demo.FooTest"]
  spec.check("class reachable from forest", class_pos ~= nil, true)
  spec.check("class type", class_pos and class_pos.type, "namespace")
  spec.check("class selector kind", class_pos and class_pos.lathe_selector_kind, "CLASS")

  local method_pos = by_id["demo.FooTest#bar_condition_result()"]
  spec.check("method reachable from forest", method_pos ~= nil, true)
  spec.check("method type", method_pos and method_pos.type, "test")
  spec.check("method selector kind", method_pos and method_pos.lathe_selector_kind, "METHOD")
end

-- _package_for_dir: derives {moduleRel, package} from a directory path in
-- standard Maven layout, the same convention RunnableScanner.packageName()
-- uses server-side. Pure function, no workspace/LSP needed.
do
  local module_rel, package_name = adapter._package_for_dir(
    "/home/user/git/helidon/dbclient/mongodb/src/test/java/io/helidon/dbclient/mongodb",
    "/home/user/git/helidon"
  )
  spec.check("moduleRel derived from standard layout", module_rel, "dbclient/mongodb")
  spec.check("package derived from standard layout", package_name, "io.helidon.dbclient.mongodb")

  local single_module_rel, single_package = adapter._package_for_dir(
    "/workspace/demo/src/main/java/demo", "/workspace"
  )
  spec.check("moduleRel for a single-module project", single_module_rel, "demo")
  spec.check("package for src/main (not just src/test)", single_package, "demo")

  spec.check(
    "nil for the module root itself (no src/*/java segment)",
    adapter._package_for_dir("/home/user/git/helidon/dbclient/mongodb", "/home/user/git/helidon"),
    nil
  )
  spec.check(
    "nil for the default/unnamed package (src/test/java itself)",
    adapter._package_for_dir("/workspace/demo/src/test/java", "/workspace"),
    nil
  )
  spec.check(
    "nil when the derived module path escapes the workspace root",
    adapter._package_for_dir("/other/place/src/test/java/demo", "/workspace"),
    nil
  )
end

-- Case 2: nested class. Inner's real parent is Outer -- a class that itself
-- has no test of its own, so Outer is not a tracked node either. Inner must
-- still fall through to the file root (not vanish) rather than being
-- silently dropped for lacking a tracked parent.
do
  local targets = {
    {
      id = "demo.Outer$Inner#nested_condition_result()",
      parentId = "demo.Outer$Inner",
      kind = 1,
      label = "nested_condition_result",
      moduleRel = "demo",
      range = { start = { line = 4, character = 4 }, ["end"] = { line = 6, character = 5 } },
    },
    {
      id = "demo.Outer$Inner",
      parentId = "demo.Outer", -- Outer itself is never emitted: no test of its own
      kind = 2,
      label = "Inner",
      moduleRel = "demo",
      range = { start = { line = 3, character = 2 }, ["end"] = { line = 7, character = 3 } },
    },
  }

  local forest = adapter._build_position_forest(FILE, targets)
  local by_id = flatten(forest)

  spec.check("nested case: forest has file root + one top-level entry", #forest, 2)
  spec.check("nested class reachable despite untracked enclosing class", by_id["demo.Outer$Inner"] ~= nil, true)
end

-- is_test_file mirrors Surefire's own default include patterns
-- (Test*.java, *Test.java, *Tests.java, *TestCase.java).
do
  local test_root = "/p/src/test/java/demo/"
  spec.check("Test*.java matches", adapter.is_test_file(test_root .. "TestFoo.java"), true)
  spec.check("*Test.java matches", adapter.is_test_file(test_root .. "FooTest.java"), true)
  spec.check("*Tests.java matches", adapter.is_test_file(test_root .. "FooTests.java"), true)
  spec.check("*TestCase.java matches", adapter.is_test_file(test_root .. "FooTestCase.java"), true)
  spec.check("plain class does not match", adapter.is_test_file(test_root .. "Foo.java"), false)
  spec.check("non-.java file does not match", adapter.is_test_file(test_root .. "FooTest.txt"), false)
  -- A `Test*`-named class in src/main is a fixture/builder, not a Surefire test: must not match.
  spec.check(
    "Test*-named class in src/main is not a test",
    adapter.is_test_file("/p/src/main/java/demo/TestDataBuilder.java"),
    false
  )
end

local function read_file(path)
  local f = assert(io.open(path, "r"))
  local content = f:read("*a")
  f:close()
  return content
end

-- Minimal stand-in for neotest.types.Tree (get_key/iter_nodes only), so the results() reconciliation
-- tests run without the real neotest plugin installed -- like the rest of this spec. `nodes_by_id` maps
-- each id to `{ id, type, children }`; iter_nodes yields a node then its whole subtree, depth-first.
local function fake_tree(nodes_by_id)
  local function node_for(id)
    return {
      data = function()
        return nodes_by_id[id]
      end,
      iter_nodes = function()
        local ids = {}
        local function collect(i)
          table.insert(ids, i)
          for _, child_id in ipairs(nodes_by_id[i].children or {}) do
            collect(child_id)
          end
        end
        collect(id)
        local idx = 0
        return function()
          idx = idx + 1
          if ids[idx] then
            return idx, node_for(ids[idx])
          end
        end
      end,
    }
  end
  return {
    get_key = function(_, id)
      return nodes_by_id[id] and node_for(id) or nil
    end,
  }
end

-- results() must always write neotest.Result.output as a path to a file
-- containing the real content, never raw text in the field itself.
do
  local POS_ID = "demo.FooTest#bar()"
  local function result_for(context)
    return adapter.results({ context = context })[POS_ID]
  end

  local passed = result_for({
    position_id = POS_ID,
    outcome = { launched = true, exitCode = 0, output = transcript("line one", "line two") },
  })
  spec.check("passing result status", passed.status, "passed")
  spec.check("passing result output file content", read_file(passed.output), "line one\nline two")

  local failed = result_for({
    position_id = POS_ID,
    outcome = { launched = true, exitCode = 1, output = transcript("boom") },
  })
  spec.check("failing result status", failed.status, "failed")
  spec.check("failing result output file content", read_file(failed.output), "boom")

  local blocked = result_for({
    position_id = POS_ID,
    outcome = { launched = false, blockedReasons = { "no runner jar" } },
  })
  spec.check("blocked result status", blocked.status, "failed")
  spec.check("blocked result output file content", read_file(blocked.output), "BLOCKED: no runner jar")

  local errored = result_for({ position_id = POS_ID, err = { message = "timeout" } })
  spec.check("errored result status", errored.status, "failed")
end

-- results() must resolve every descendant of the position actually run, not
-- just that position's own id. Reproduces the "stuck running forever" bug:
-- neotest.Client:run_tree marks every id in the run's whole subtree as
-- running up front (client/init.lua's update_running(adapter_id, root.id,
-- pos_ids), built from tree:iter()), but only clears whichever ids
-- results() returns -- so a class or package result that reports just its
-- own id leaves every sibling method/class stuck showing "running"
-- indefinitely. A minimal fake tree (get_key/iter_nodes only) reproduces
-- this without requiring the real neotest.types.Tree, keeping this spec
-- installable-neotest-independent like the rest of the file. Also proves
-- results() scopes correctly to just the run position's own subtree (via
-- tree:get_key), not the whole tree it's handed -- build_spec's file-run
-- fan-out passes the same outer file tree to every per-class results()
-- call, so naively resolving "everything in tree" would incorrectly stamp
-- sibling classes' methods with the wrong class's result.
do
  local nodes = {
    ["demo.FooTest"] = { id = "demo.FooTest", children = { "demo.FooTest#a()", "demo.FooTest#b()" } },
    ["demo.FooTest#a()"] = { id = "demo.FooTest#a()", children = {} },
    ["demo.FooTest#b()"] = { id = "demo.FooTest#b()", children = {} },
    ["demo.OtherTest"] = { id = "demo.OtherTest", children = { "demo.OtherTest#c()" } },
    ["demo.OtherTest#c()"] = { id = "demo.OtherTest#c()", children = {} },
  }
  local tree = fake_tree(nodes)

  local results = adapter.results({
    context = {
      position_id = "demo.FooTest",
      outcome = { launched = true, exitCode = 0, output = transcript("ok") },
    },
  }, nil, tree)

  local method_a = results["demo.FooTest#a()"]
  spec.check("class result present", results["demo.FooTest"] ~= nil, true)
  spec.check("sibling method a resolved, not stuck running", method_a ~= nil, true)
  spec.check("sibling method b resolved, not stuck running", results["demo.FooTest#b()"] ~= nil, true)
  spec.check("method a inherits class status", method_a and method_a.status, "passed")
  spec.check("unrelated class in the same outer tree left untouched", results["demo.OtherTest"], nil)
  spec.check("unrelated class's method left untouched", results["demo.OtherTest#c()"], nil)
end

-- With real per-test results on outcome.testResults, results() must mark
-- exactly the methods that failed rather than fanning one aggregate status
-- out to every sibling. Reuses the same minimal fake tree as the fan-out
-- reproduction above. Each testResults entry carries its server-derived
-- positionId (RunnableScanner.methodTarget's "<class>#<method>(<erasedParams>)"
-- format); results() keys off it directly, no client-side reconstruction.
do
  local nodes = {
    ["demo.FooTest"] = {
      id = "demo.FooTest",
      children = { "demo.FooTest#a()", "demo.FooTest#b()", "demo.FooTest#c(java.lang.String,int)" },
    },
    ["demo.FooTest#a()"] = { id = "demo.FooTest#a()", children = {} },
    ["demo.FooTest#b()"] = { id = "demo.FooTest#b()", children = {} },
    ["demo.FooTest#c(java.lang.String,int)"] = {
      id = "demo.FooTest#c(java.lang.String,int)",
      children = {},
    },
  }
  local tree = fake_tree(nodes)

  local results = adapter.results({
    context = {
      position_id = "demo.FooTest",
      outcome = {
        launched = true,
        exitCode = 1,
        output = transcript("transcript"),
        testResults = {
          { positionId = "demo.FooTest#a()", status = "passed", failureMessage = "", failureLine = -1 },
          { positionId = "demo.FooTest#b()", status = "failed", failureMessage = "expected true", failureLine = 12 },
          { positionId = "demo.FooTest#c(java.lang.String,int)", status = "passed", failureMessage = "", failureLine = -1 },
        },
      },
    },
  }, nil, tree)

  local method_a = results["demo.FooTest#a()"]
  local method_b = results["demo.FooTest#b()"]
  local method_c = results["demo.FooTest#c(java.lang.String,int)"]
  spec.check("passing method marked passed, not the aggregate failure", method_a and method_a.status, "passed")
  spec.check("failing method marked failed", method_b and method_b.status, "failed")
  spec.check("failing method carries its failure message", method_b and method_b.short, "expected true")
  -- F1: a failing method exposes a neotest.Result.error so neotest's diagnostic consumer sets a
  -- vim.diagnostic on the failing line. failureLine is a 1-based Java source line; neotest wants
  -- 0-based, so 12 -> 11. Passing methods (failureLine -1, empty message) expose no diagnostic.
  spec.check("failing method exposes one diagnostic error", method_b and method_b.errors and #method_b.errors, 1)
  spec.check(
    "diagnostic message is the failure message",
    method_b and method_b.errors and method_b.errors[1].message,
    "expected true"
  )
  spec.check(
    "diagnostic line is 0-based (failureLine 12 -> 11)",
    method_b and method_b.errors and method_b.errors[1].line,
    11
  )
  spec.check("passing method exposes no diagnostics", method_a and method_a.errors, nil)
  spec.check("param method keyed by its server positionId", method_c and method_c.status, "passed")
  spec.check("per-test result reuses the run transcript", read_file(method_a.output), "transcript")
  spec.check("class namespace node still gets the aggregate status", results["demo.FooTest"].status, "failed")
end

-- A @ParameterizedTest emits one record per invocation, all collapsing onto
-- the method's single position id. results() must roll them up
-- worst-status-wins -- a method with any failing invocation shows failed,
-- independent of the order the invocation records arrive in.
do
  local function run(invocations)
    return adapter.results({
      context = {
        position_id = "demo.FooTest#p(java.lang.String)",
        outcome = { launched = true, exitCode = 1, output = transcript("t"), testResults = invocations },
      },
    })["demo.FooTest#p(java.lang.String)"]
  end

  local function invocation(status, message)
    return {
      positionId = "demo.FooTest#p(java.lang.String)",
      status = status,
      failureMessage = message or "",
      failureLine = -1,
    }
  end

  local pass_then_fail = run({ invocation("passed"), invocation("failed", "second blew up") })
  local fail_then_pass = run({ invocation("failed", "first blew up"), invocation("passed") })
  spec.check("passed invocation does not mask a later failure", pass_then_fail.status, "failed")
  spec.check("a later passing invocation does not clear an earlier failure", fail_then_pass.status, "failed")
  spec.check("rolled-up failure keeps a failure message", fail_then_pass.short, "first blew up")
end

-- A package (dir) run whose subtree spans several classes must roll each
-- container node up from its OWN leaf results, not paint every descendant with
-- the run-wide aggregate. Reproduces the reported bug: one failing test in the
-- package reddened every passing .java file/class too, because file and class
-- nodes never receive a per-test result and so inherited the aggregate failure.
-- The run position itself (the dir) still shows the aggregate; only its
-- descendants roll up.
do
  local dir = "/workspace/demo/src/test/java/demo"
  local pass_file = dir .. "/AlphaTest.java"
  local fail_file = dir .. "/BetaTest.java"
  local nodes = {
    [dir] = { id = dir, type = "dir", children = { pass_file, fail_file } },
    [pass_file] = { id = pass_file, type = "file", children = { "demo.AlphaTest" } },
    ["demo.AlphaTest"] = { id = "demo.AlphaTest", type = "namespace", children = { "demo.AlphaTest#ok()" } },
    ["demo.AlphaTest#ok()"] = { id = "demo.AlphaTest#ok()", type = "test", children = {} },
    [fail_file] = { id = fail_file, type = "file", children = { "demo.BetaTest" } },
    ["demo.BetaTest"] = { id = "demo.BetaTest", type = "namespace", children = { "demo.BetaTest#bad()" } },
    ["demo.BetaTest#bad()"] = { id = "demo.BetaTest#bad()", type = "test", children = {} },
  }
  local tree = fake_tree(nodes)

  local results = adapter.results({
    context = {
      position_id = dir,
      outcome = {
        launched = true,
        exitCode = 1,
        output = transcript("transcript"),
        testResults = {
          { positionId = "demo.AlphaTest#ok()", status = "passed", failureMessage = "", failureLine = -1 },
          { positionId = "demo.BetaTest#bad()", status = "failed", failureMessage = "boom", failureLine = 7 },
        },
      },
    },
  }, nil, tree)

  spec.check("dir run position shows the aggregate failure", results[dir].status, "failed")
  spec.check("passing file rolls up to passed, not the aggregate", results[pass_file].status, "passed")
  spec.check("passing class rolls up to passed", results["demo.AlphaTest"].status, "passed")
  spec.check("passing method stays passed", results["demo.AlphaTest#ok()"].status, "passed")
  spec.check("failing file rolls up to failed", results[fail_file].status, "failed")
  spec.check("failing class rolls up to failed", results["demo.BetaTest"].status, "failed")
  spec.check("failing method stays failed", results["demo.BetaTest#bad()"].status, "failed")
end

-- The reported package-run bug: neotest discovers positions only for OPEN files, so a package run's
-- tree has childless file/dir nodes for every file the user never opened -- yet the run produced a real
-- result for each of those tests. results() must match those results to the childless container by NAME
-- (file path -> FQCN, dir path -> package) so a passing but never-opened file shows passed, not the
-- run-wide aggregate failure. Reproduces the exact shape seen in the diagnostic log.
do
  local base = "/w/m/src/test/java/com/x"
  local pass_file = base .. "/AlphaTest.java"
  local fail_file = base .. "/BetaTest.java"
  local sub = base .. "/sub"
  local sub_file = sub .. "/GammaTest.java"
  -- Every file/dir node is CHILDLESS -- none of these files was opened, so discovery never populated
  -- their namespace/method positions. Only container nodes exist under the run position.
  local nodes = {
    [base] = { id = base, type = "dir", children = { pass_file, fail_file, sub } },
    [pass_file] = { id = pass_file, type = "file", children = {} },
    [fail_file] = { id = fail_file, type = "file", children = {} },
    [sub] = { id = sub, type = "dir", children = { sub_file } },
    [sub_file] = { id = sub_file, type = "file", children = {} },
  }
  local tree = fake_tree(nodes)

  local results = adapter.results({
    context = {
      position_id = base,
      outcome = {
        launched = true,
        exitCode = 1,
        output = transcript("transcript"),
        testResults = {
          { positionId = "com.x.AlphaTest#ok()", status = "passed", failureMessage = "", failureLine = -1 },
          { positionId = "com.x.AlphaTest#ok2()", status = "passed", failureMessage = "", failureLine = -1 },
          { positionId = "com.x.BetaTest#bad()", status = "failed", failureMessage = "boom", failureLine = 3 },
          { positionId = "com.x.sub.GammaTest#g()", status = "passed", failureMessage = "", failureLine = -1 },
        },
      },
    },
  }, nil, tree)

  spec.check("dir run position shows the aggregate failure", results[base].status, "failed")
  spec.check("never-opened passing file matched by name -> passed", results[pass_file].status, "passed")
  spec.check("never-opened failing file matched by name -> failed", results[fail_file].status, "failed")
  spec.check("nested package dir matched by prefix -> passed", results[sub].status, "passed")
  spec.check("file in a nested package matched by name -> passed", results[sub_file].status, "passed")
end

-- root() resolves the nearest .lathe marker walking up from a nested path,
-- the same fixture-building approach as root_spec.lua's own coverage of
-- lathe.get_root -- this is neotest's own project-root hook, a separate
-- entry point from that one, so it gets its own direct check.
do
  local work = vim.fn.tempname()
  local project = work .. "/project"
  vim.fn.mkdir(project .. "/src/test/java/demo", "p")
  local marker = io.open(project .. "/.lathe", "w")
  marker:write("")
  marker:close()

  local nested = project .. "/src/test/java/demo/FooTest.java"
  spec.check("root() finds the marked root from a nested path", adapter.root(nested), project)
  spec.check("root() returns nil with no marker above", adapter.root(work), nil)

  vim.fn.delete(work, "rf")
end

-- filter_dir() prunes build output and VCS/workspace-metadata directories
-- from neotest's workspace-wide discovery walk.
do
  spec.check("filter_dir excludes target", adapter.filter_dir("target", "app/target", "/root"), false)
  spec.check("filter_dir excludes .lathe", adapter.filter_dir(".lathe", ".lathe", "/root"), false)
  spec.check("filter_dir excludes .git", adapter.filter_dir(".git", ".git", "/root"), false)
  spec.check("filter_dir keeps src", adapter.filter_dir("src", "app/src", "/root"), true)
end

-- build_spec with strategy == "dap" (neotest's summary `d`/`D` and run.run({strategy="dap"})) must
-- return a DEBUG spec: a spec.strategy the dap strategy launches through the lathe adapter, carrying
-- the run token and selection -- and must NOT fire a lathe.run.test replay (that would spawn a second,
-- un-debugged JVM). A minimal synchronous nio stub lets build_spec run headlessly; the fake client
-- records every executeCommand so we can assert none was issued.
do
  local executed = {}
  package.loaded["nio"] = {
    control = {
      queue = function()
        return { put_nowait = function() end, get = function() end }
      end,
      future = function()
        return { set = function() end, wait = function() end }
      end,
    },
    run = function() end,
    lsp = {
      get_clients = function()
        return {
          { request = { workspace_executeCommand = function(arg)
            executed[#executed + 1] = arg
          end } },
        }
      end,
    },
  }

  local pos = {
    id = "com.example.FooTest#bar()",
    type = "test",
    name = "bar",
    lathe_module_rel = "app",
    lathe_selector_kind = "METHOD",
  }
  local tree = {
    data = function()
      return pos
    end,
    iter_nodes = function()
      return function()
        return nil
      end
    end,
  }

  local built = adapter.build_spec({ tree = tree, strategy = "dap" })

  spec.check("dap build_spec carries a lathe strategy config", built.strategy and built.strategy.type, "lathe")
  spec.check("dap strategy carries a run token", type(built.strategy.lathe_token), "string")
  spec.check(
    "dap strategy carries the selection",
    built.strategy.lathe_selections[1].selectorValue,
    pos.id
  )
  spec.check("dap context is flagged debug", built.context.debug, true)
  spec.check("dap build_spec fires no lathe.run.test replay", #executed, 0)

  package.loaded["nio"] = nil
end

-- The debug-run fallback: when lathe/testFinished never arrives, results() must still complete from
-- neotest's dap-strategy exit code rather than hang, so _synthesized_outcome maps that code into a
-- launched, result-less outcome.
do
  local zero = adapter._synthesized_outcome({ code = 0 })
  spec.check("synthesized outcome is launched", zero.launched, true)
  spec.check("synthesized outcome takes exit code 0", zero.exitCode, 0)
  spec.check("synthesized outcome has no per-test results", #zero.testResults, 0)

  spec.check("synthesized outcome takes a non-zero exit code", adapter._synthesized_outcome({ code = 1 }).exitCode, 1)
  spec.check("synthesized outcome defaults a missing code to -1", adapter._synthesized_outcome(nil).exitCode, -1)
end

-- NV-2: the one-line run-completion toast (_run_summary) over the run context + per-test map. Counts
-- come from the method-level `real` map; a failure/blocked/errored/skip run is WARN, a clean one INFO;
-- elapsed ms renders as one-decimal seconds and is omitted when untimed.
do
  local real_pass = { ["C#a()"] = { status = "passed" }, ["C#b()"] = { status = "passed" } }
  local pass = adapter._run_summary({ label = "C", outcome = { launched = true, exitCode = 0 } }, real_pass, 1800)
  spec.check("all-pass summary text", pass.text, "C — 2 passed, 0 failed, 0 skipped (1.8s)")
  spec.check("all-pass summary is INFO", pass.warn, false)

  local real_mixed = {
    ["C#a()"] = { status = "passed" },
    ["C#b()"] = { status = "failed" },
    ["C#c()"] = { status = "skipped" },
  }
  local mixed = adapter._run_summary({ label = "C", outcome = { launched = true, exitCode = 1 } }, real_mixed, 2300)
  spec.check("mixed summary text", mixed.text, "C — 1 passed, 1 failed, 1 skipped (2.3s)")
  spec.check("mixed summary is WARN", mixed.warn, true)

  local blocked = adapter._run_summary(
    { label = "C", outcome = { launched = false, blockedReasons = { "no launch template" } } },
    {},
    nil
  )
  spec.check("blocked summary text", blocked.text, "C — blocked: no launch template")
  spec.check("blocked summary is WARN", blocked.warn, true)

  local errored = adapter._run_summary({ label = "C", err = "boom" }, {}, 500)
  spec.check("errored summary text", errored.text, "C — errored (0.5s)")
  spec.check("errored summary is WARN", errored.warn, true)

  local skipped = adapter._run_summary({ label = "Foo.java", skip_reason = "no tests found in this file" }, {}, nil)
  spec.check("skip summary text", skipped.text, "Foo.java — no tests found in this file")
  spec.check("skip summary is WARN", skipped.warn, true)

  local untimed = adapter._run_summary({ label = "C", outcome = { launched = true, exitCode = 0 } }, real_pass, nil)
  spec.check("untimed summary omits the seconds suffix", untimed.text, "C — 2 passed, 0 failed, 0 skipped")
end

spec.finish()
