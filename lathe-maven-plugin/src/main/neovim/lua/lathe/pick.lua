-- A tiny built-in fuzzy picker: a floating prompt over a results list, filtered live with Neovim's
-- builtin `matchfuzzypos` as you type. Zero dependencies -- it does NOT go through `vim.ui.select`, so
-- the fuzzy experience is identical whether or not the user has Telescope / fzf-lua / snacks. Used by
-- :LatheNew for the destination pick (hundreds of packages), where a numbered `vim.ui.select` list is
-- unusable.
--
--   require("lathe.pick").pick({
--     items = {...},                 -- arbitrary values
--     format = function(item) ... end, -- item -> display string (fuzzy matched on this)
--     title = "Where:",
--     on_choice = function(item) ... end, -- item, or nil on cancel
--   })

local M = {}

local ns = vim.api.nvim_create_namespace("lathe_pick")

-- Filter `entries` (each { item, text }) by `query`, returning the matched entries best-first and the
-- matched-character positions per entry. An empty query keeps the original order and no positions.
function M._filter(entries, query)
	if query == nil or query == "" then
		return entries, {}
	end

	local matched, positions = unpack(vim.fn.matchfuzzypos(entries, query, { key = "text" }))
	return matched, positions
end

function M.pick(opts)
	local format = opts.format or tostring
	local on_choice = opts.on_choice or function() end

	local entries = {}
	for _, item in ipairs(opts.items or {}) do
		entries[#entries + 1] = { item = item, text = format(item) }
	end

	local width = math.max(40, #(opts.title or "") + 4)
	for _, entry in ipairs(entries) do
		width = math.max(width, #entry.text + 2)
	end
	width = math.min(width, math.floor(vim.o.columns * 0.8))
	local height = math.max(1, math.min(#entries, math.floor(vim.o.lines * 0.4)))
	local row = math.floor((vim.o.lines - height - 4) / 2)
	local col = math.floor((vim.o.columns - width) / 2)

	local results_buf = vim.api.nvim_create_buf(false, true)
	local results_win = vim.api.nvim_open_win(results_buf, false, {
		relative = "editor",
		width = width,
		height = height,
		row = row + 3,
		col = col,
		style = "minimal",
		border = "rounded",
		focusable = false,
	})
	vim.wo[results_win].cursorline = true

	local prompt_buf = vim.api.nvim_create_buf(false, true)
	local prompt_win = vim.api.nvim_open_win(prompt_buf, true, {
		relative = "editor",
		width = width,
		height = 1,
		row = row,
		col = col,
		style = "minimal",
		border = "rounded",
		title = opts.title,
	})

	local state = { filtered = entries, positions = {} }
	local resolved = false

	local function finish(item)
		if resolved then
			return
		end
		resolved = true
		pcall(vim.api.nvim_win_close, prompt_win, true)
		pcall(vim.api.nvim_win_close, results_win, true)
		vim.schedule(function()
			on_choice(item)
		end)
	end

	local function selected_line()
		local pos = vim.api.nvim_win_get_cursor(results_win)
		return pos[1]
	end

	local function render()
		local lines = {}
		for i, entry in ipairs(state.filtered) do
			lines[i] = entry.text
		end
		if #lines == 0 then
			lines = { "  (no matches)" }
		end

		vim.bo[results_buf].modifiable = true
		vim.api.nvim_buf_set_lines(results_buf, 0, -1, false, lines)
		vim.bo[results_buf].modifiable = false

		vim.api.nvim_buf_clear_namespace(results_buf, ns, 0, -1)
		for line, cols in ipairs(state.positions) do
			for _, c in ipairs(cols) do
				pcall(vim.api.nvim_buf_set_extmark, results_buf, ns, line - 1, c, {
					end_col = c + 1,
					hl_group = "Special",
				})
			end
		end
	end

	local function move(delta)
		local n = #state.filtered
		if n == 0 then
			return
		end
		local line = ((selected_line() - 1 + delta) % n) + 1
		vim.api.nvim_win_set_cursor(results_win, { line, 0 })
	end

	local function refilter()
		state.filtered, state.positions = M._filter(entries, vim.api.nvim_get_current_line())
		render()
		if #state.filtered > 0 then
			pcall(vim.api.nvim_win_set_cursor, results_win, { 1, 0 })
		end
	end

	local function accept()
		local entry = state.filtered[selected_line()]
		finish(entry and entry.item or nil)
	end

	local function cancel()
		finish(nil)
	end

	local function map(lhs, fn)
		vim.keymap.set("i", lhs, fn, { buffer = prompt_buf, nowait = true })
	end

	map("<CR>", accept)
	map("<Esc>", cancel)
	map("<C-c>", cancel)
	for _, lhs in ipairs({ "<Down>", "<C-n>", "<C-j>" }) do
		map(lhs, function()
			move(1)
		end)
	end
	for _, lhs in ipairs({ "<Up>", "<C-p>", "<C-k>" }) do
		map(lhs, function()
			move(-1)
		end)
	end

	vim.api.nvim_create_autocmd("TextChangedI", { buffer = prompt_buf, callback = refilter })
	vim.api.nvim_create_autocmd("BufLeave", { buffer = prompt_buf, once = true, callback = cancel })

	render()
	vim.cmd("startinsert")
end

return M
