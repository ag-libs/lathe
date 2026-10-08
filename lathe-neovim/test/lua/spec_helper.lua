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
      reporter.fail(string.format("%s: expected %s, got %s", name, tostring(expected), tostring(actual)))
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

return M
