local M = {}

-- Decodes a JSON object file; nil when it is missing or not an object. Plain io and vim.json, so it
-- is safe in async contexts (neotest discovery) as well as on the main loop.
function M.read(path)
  local f = io.open(path, "r")
  if not f then
    return nil
  end

  local content = f:read("*a")
  f:close()
  local ok, decoded = pcall(vim.json.decode, content)
  return (ok and type(decoded) == "table") and decoded or nil
end

return M
