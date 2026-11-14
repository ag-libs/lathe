-- Shared harness for this directory's `*_spec.lua` files: a tiny check/report
-- accumulator so each spec states only its assertions, not the pass/fail
-- bookkeeping and exit-code convention (`qa!` on success, `cq!` on failure)
-- that run-specs.sh relies on to detect failure per spec.

local M = {}

function M.new()
  local failures = 0
  local reporter = {}

  function reporter.ok(msg)
    io.stdout:write("ok    " .. msg .. "\n")
  end

  function reporter.fail(msg)
    failures = failures + 1
    io.stderr:write("FAIL  " .. msg .. "\n")
  end

  --- Assert `actual == expected`, printing a one-line result either way.
  function reporter.check(name, actual, expected)
    if actual == expected then
      reporter.ok(string.format("%s => %s", name, tostring(actual)))
    else
      reporter.fail(
        string.format("%s: expected %s, got %s", name, tostring(expected), tostring(actual))
      )
    end
  end

  --- Record a known, not-yet-implemented behaviour without failing the suite, so
  --- the gap stays visible in output and flips to a real `check` when built.
  function reporter.pending(name, reason)
    io.stdout:write(string.format("pend  %s (%s)\n", name, reason or ""))
  end

  --- Print the failure count (prefixed with an optional summary label) and
  --- exit the way run-specs.sh expects: `qa!` if every check passed, `cq!`
  --- otherwise.
  function reporter.finish(summary_label)
    local prefix = summary_label and (summary_label .. ", ") or ""
    io.stdout:write(string.format("\n%s%d failures\n", prefix, failures))
    vim.cmd(failures == 0 and "qa!" or "cq!")
  end

  return reporter
end

--- Attach a fake Lathe client. `answer` is either a table of results by executeCommand command or
--- a `request(client, method, params, callback, bufnr)` function for anything else. Returns the
--- requests the client received, in order, as `{ method, command, arguments }`. `opts.position`
--- also fixes the cursor position the code under test sends (file:///X.java, 0:0).
function M.fake_client(answer, opts)
  local requests = {}
  local respond = answer
  if type(answer) ~= "function" then
    respond = function(_, _, params, callback, _)
      callback(nil, answer[params.command])
    end
  end

  local fake = {
    id = 1,
    name = "lathe",
    offset_encoding = "utf-16",
    request = function(client, method, params, callback, bufnr)
      requests[#requests + 1] =
        { method = method, command = params.command, arguments = params.arguments }
      return respond(client, method, params, callback, bufnr)
    end,
  }
  vim.lsp.get_clients = function(_)
    return { fake }
  end
  vim.lsp.get_client_by_id = function(id)
    return id == fake.id and fake or nil
  end
  if opts and opts.position then
    vim.lsp.util.make_position_params = function(_, _)
      return { textDocument = { uri = "file:///X.java" }, position = { line = 0, character = 0 } }
    end
  end

  return requests
end

--- No Lathe client attached.
function M.no_client()
  vim.lsp.get_clients = function(_)
    return {}
  end
end

return M
