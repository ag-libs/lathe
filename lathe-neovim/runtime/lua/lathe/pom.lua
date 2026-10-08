-- Client-side pom.xml support: XSD validation and optional formatting, both via `xmllint`
-- (libxml2). This is pure editor tooling -- the Lathe language server is NOT involved and is
-- never attached to pom.xml (its filetypes stay `java`). Validation runs `xmllint` against the
-- bundled Maven POM schema on open and (debounced) as you type, mapping its errors into a private
-- diagnostic namespace; formatting points the buffer's `formatprg` at `xmllint --format` so `gq`
-- reindents.
--
-- Degrades silently when `xmllint` is not installed or the schema cannot be found: a one-time
-- notification, then no-op, so a runtime without libxml2 is unaffected.

local M = {}

local ns = vim.api.nvim_create_namespace('lathe_pom')

-- Resolved config; replaced by setup(). validate defaults on (the headline feature), format off.
M.config = { validate = true, format = false }

-- Notify once per distinct message, so a missing xmllint or schema warns a single time rather than
-- on every open/save.
local warned = {}
local function warn_once(message)
  if warned[message] then
    return
  end
  warned[message] = true
  vim.notify('Lathe: ' .. message, vim.log.levels.WARN, { title = 'Lathe' })
end

local function has_xmllint()
  return vim.fn.executable('xmllint') == 1
end

-- Whole-buffer contents as one newline-joined string, fed to xmllint over stdin.
local function buffer_text(bufnr)
  return table.concat(vim.api.nvim_buf_get_lines(bufnr, 0, -1, false), '\n')
end

-- Debounce state for live (on-change) validation: one reusable libuv timer per buffer.
local validate_timers = {}
local DEBOUNCE_MS = 250

--- Absolute path to the bundled Maven POM XSD, found on the runtimepath so it resolves for both the
--- cache-unpacked bundle and the standalone plugin.
---@return string? path
function M.schema_path()
  return vim.api.nvim_get_runtime_file('schema/maven-4.0.0.xsd', false)[1]
end

--- Parse `xmllint --schema` stderr into `vim.diagnostic` entries. xmllint prints one error as a
--- triple -- `<file>:<line>: <message>`, then the offending source line, then a caret -- so only
--- the first line of each triple starts with `<file>:<digits>:`; the trailing "<file> fails to
--- validate" summary and the source/caret lines do not match and are ignored.
---@param lines string[] stderr split into lines
---@param filename string the path passed to xmllint (echoed verbatim in each error)
---@return table[] diagnostics
function M.parse_diagnostics(lines, filename)
  local prefix = '^' .. vim.pesc(filename) .. ':(%d+):%s*(.+)$'
  local diags = {}
  for _, line in ipairs(lines) do
    local lnum, message = line:match(prefix)
    if lnum then
      diags[#diags + 1] = {
        lnum = math.max(0, tonumber(lnum) - 1),
        col = 0,
        severity = vim.diagnostic.severity.ERROR,
        source = 'xmllint',
        message = message,
      }
    end
  end
  return diags
end

--- Validate the pom.xml backing `bufnr` against the bundled schema and publish diagnostics.
--- Feeds the live buffer contents to `xmllint` via stdin (not the file on disk), so it reflects
--- unsaved edits and can run on every change. xmllint labels stdin errors with a `-:<line>:` prefix,
--- so diagnostics parse against the `-` filename. `--nonet` keeps it offline.
---@param bufnr integer? defaults to the current buffer
function M.validate(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  if not M.config.validate then
    return
  end

  if not has_xmllint() then
    warn_once('pom.xml validation needs `xmllint` (libxml2) on PATH; skipping.')
    return
  end

  local schema = M.schema_path()
  if not schema then
    warn_once('pom.xml schema not found on runtimepath; skipping validation.')
    return
  end

  vim.system(
    { 'xmllint', '--nonet', '--noout', '--schema', schema, '-' },
    { text = true, stdin = buffer_text(bufnr) },
    vim.schedule_wrap(function(result)
      if not vim.api.nvim_buf_is_valid(bufnr) then
        return
      end
      local lines = vim.split(result.stderr or '', '\n', { trimempty = true })
      vim.diagnostic.set(ns, bufnr, M.parse_diagnostics(lines, '-'))
    end)
  )
end

--- Format the pom.xml in `bufnr` in place via `xmllint --format`, replacing the buffer contents only
--- when xmllint succeeds. A naive `:%!xmllint` would blank the buffer when the pom is invalid, since
--- xmllint writes nothing to stdout on a parse error; this captures the output and leaves the buffer
--- untouched (with a notice) on failure. `--nonet` keeps it offline.
---@param bufnr integer? defaults to the current buffer
function M.format_buffer(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  if not has_xmllint() then
    warn_once('pom.xml formatting needs `xmllint` (libxml2) on PATH; skipping.')
    return
  end

  local output = vim.fn.system({ 'xmllint', '--nonet', '--format', '-' }, buffer_text(bufnr))
  if vim.v.shell_error ~= 0 then
    vim.notify(
      'Lathe: pom.xml not formatted (invalid XML): ' .. vim.trim(output),
      vim.log.levels.WARN,
      { title = 'Lathe' }
    )
    return
  end

  local lines = vim.split(output, '\n')
  if lines[#lines] == '' then
    table.remove(lines)
  end
  vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, lines)
end

-- Point the buffer's formatprg at xmllint so `gq`/`gg=G`-style formatting reindents the pom.
-- pom.xml has no significant mixed content, so `--format` is safe. Indentation follows xmllint's
-- own default (override with the XMLLINT_INDENT env var). `--nonet` keeps it offline.
local function set_formatprg(bufnr)
  if has_xmllint() then
    vim.bo[bufnr].formatprg = 'xmllint --nonet --format -'
  end
end

-- Stop and release a buffer's debounce timer (on buffer wipeout, so timers do not leak).
local function cancel_timer(bufnr)
  local timer = validate_timers[bufnr]
  if not timer then
    return
  end
  timer:stop()
  if not timer:is_closing() then
    timer:close()
  end
  validate_timers[bufnr] = nil
end

-- Debounce M.validate so a burst of keystrokes triggers a single xmllint run once typing settles.
local function schedule_validate(bufnr)
  local timer = validate_timers[bufnr]
  if not timer then
    timer = assert((vim.uv or vim.loop).new_timer())
    validate_timers[bufnr] = timer
  end
  timer:stop()
  timer:start(
    DEBOUNCE_MS,
    0,
    vim.schedule_wrap(function()
      if vim.api.nvim_buf_is_valid(bufnr) then
        M.validate(bufnr)
      end
    end)
  )
end

function M.setup(opts)
  opts = opts or {}
  M.config = {
    validate = opts.validate ~= false,
    format = opts.format == true,
  }

  local augroup = vim.api.nvim_create_augroup('LathePom', { clear = true })

  if M.config.validate then
    -- Validate immediately on open, then live (debounced) on every edit -- buffer contents, not the
    -- saved file -- so schema errors surface as you type rather than only on write.
    vim.api.nvim_create_autocmd('BufReadPost', {
      group = augroup,
      pattern = 'pom.xml',
      callback = function(ev)
        M.validate(ev.buf)
      end,
    })
    vim.api.nvim_create_autocmd({ 'TextChanged', 'TextChangedI' }, {
      group = augroup,
      pattern = 'pom.xml',
      callback = function(ev)
        schedule_validate(ev.buf)
      end,
    })
    vim.api.nvim_create_autocmd('BufWipeout', {
      group = augroup,
      pattern = 'pom.xml',
      callback = function(ev)
        cancel_timer(ev.buf)
      end,
    })
  end

  if M.config.format then
    vim.api.nvim_create_autocmd('BufReadPost', {
      group = augroup,
      pattern = 'pom.xml',
      callback = function(ev)
        set_formatprg(ev.buf)
      end,
    })
  end
end

return M
