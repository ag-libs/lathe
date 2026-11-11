-- Driven by dev/indent-corpus.sh: asks Lathe's indenter, line by line, what indent it computes for
-- each non-blank line of already formatted Java files, and reports where it disagrees with the
-- formatter. Each line is computed against the file as formatted, so one disagreement does not
-- shift the lines after it (as a whole-file `gg=G` would).
--
-- Arguments: <file list> <parser .so> <block> <continuation> <examples per kind>

local list, parser, block, continuation, per_kind = arg[1], arg[2], tonumber(arg[3]), tonumber(arg[4]), tonumber(arg[5])

vim.g.editorconfig = false
vim.treesitter.language.add("java", { path = parser })
local indent = require("lathe.indent")
indent.setup({ indent = { profile = "google", block = block, continuation = continuation } })

-- What a line starts with, to group the disagreements by construct.
local KINDS = {
	{ "closer }", "^}" },
	{ "closer ) or ]", "^[%)%]]" },
	{ "ternary ?", "^%?" },
	{ "ternary :", "^:" },
	{ "chain .", "^%." },
	{ "operator && ||", "^[&|][&|]" },
	{ "operator + - * /", "^[%+%-%*/][^/%*]" },
	{ "lambda ->", "^%->" },
	{ "annotation @", "^@" },
	{ "comment", "^//" },
	{ "javadoc *", "^%*" },
}

local function kind_of(text)
	for _, kind in ipairs(KINDS) do
		if text:match(kind[2]) then
			return kind[1]
		end
	end

	return "other"
end

local files, lines, disagreements = 0, 0, 0
local by_kind, examples = {}, {}
for path in io.lines(list) do
	vim.cmd.edit(vim.fn.fnameescape(path))
	local buf = vim.api.nvim_get_current_buf()
	vim.bo[buf].expandtab = true
	vim.bo[buf].shiftwidth = block
	vim.bo[buf].tabstop = block
	vim.b[buf].lathe_continuation = continuation
	local text = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
	for lnum, current in ipairs(text) do
		if vim.trim(current) ~= "" then
			lines = lines + 1
			local want = #current:match("^%s*")
			local got = indent.compute(lnum, buf)
			if got ~= want then
				disagreements = disagreements + 1
				local kind = kind_of(vim.trim(current))
				by_kind[kind] = (by_kind[kind] or 0) + 1
				examples[kind] = examples[kind] or {}
				if #examples[kind] < per_kind then
					table.insert(
						examples[kind],
						string.format(
							"%s:%d  formatter %d, indenter %d  %s",
							vim.fn.fnamemodify(path, ":."),
							lnum,
							want,
							got,
							vim.trim(current):sub(1, 70)
						)
					)
				end
			end
		end
	end

	files = files + 1
	vim.cmd("bwipeout!")
end

print(
	string.format(
		"%d files, %d non-blank lines: the indenter disagrees on %d (%.2f%%)",
		files,
		lines,
		disagreements,
		lines > 0 and 100 * disagreements / lines or 0
	)
)
local kinds = vim.tbl_keys(by_kind)
table.sort(kinds, function(a, b)
	return by_kind[a] > by_kind[b]
end)
for _, kind in ipairs(kinds) do
	print(string.format("\n%-18s %6d", kind, by_kind[kind]))
	for _, example in ipairs(examples[kind]) do
		print("  " .. example)
	end
end
