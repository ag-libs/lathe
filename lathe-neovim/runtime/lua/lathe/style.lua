local M = {}

local function decode(path)
  local f = io.open(path, "r")
  if not f then
    return nil
  end

  local content = f:read("*a")
  f:close()
  local ok, decoded = pcall(vim.json.decode, content)
  return (ok and type(decoded) == "table") and decoded or nil
end

-- Reads the workspace style file under an already-resolved root (this module never does workspace
-- discovery). Committed lathe-style.json wins over generated .lathe/style.json section by section,
-- as the server reads it: a committed indent alone keeps the generated formatter. Returns the
-- decoded table, or nil when the root is unknown or neither file exists or decodes.
function M.read(root)
  if not root then
    return nil
  end

  local committed = decode(root .. "/lathe-style.json")
  local generated = decode(root .. "/.lathe/style.json")
  if not committed or not generated then
    return committed or generated
  end

  return vim.tbl_extend("keep", committed, generated)
end

return M
