local M = {}

-- Reads the workspace style file under an already-resolved root (this module never does workspace
-- discovery). Committed lathe-style.json wins over generated .lathe/style.json. Returns the decoded
-- table, or nil when the root is unknown, neither file exists, or the chosen file is malformed.
function M.read(root)
  if not root then
    return nil
  end

  for _, path in ipairs({ root .. "/lathe-style.json", root .. "/.lathe/style.json" }) do
    local f = io.open(path, "r")
    if f then
      local content = f:read("*a")
      f:close()
      local ok, decoded = pcall(vim.json.decode, content)
      return (ok and type(decoded) == "table") and decoded or nil
    end
  end

  return nil
end

return M
