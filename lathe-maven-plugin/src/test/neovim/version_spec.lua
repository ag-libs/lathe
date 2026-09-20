-- Verifies lua/lathe/version.lua exposes the fixed {VERSION, PROTOCOL} schema the
-- release-stamp step (publish-nvim.sh) and the deferred client<->server handshake
-- both depend on.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/version_spec.lua

local spec = require("spec_helper").new()

local version = require("lathe.version")

spec.check("VERSION is a string", type(version.VERSION), "string")
spec.check("VERSION is non-empty", version.VERSION ~= "", true)
spec.check("PROTOCOL is a number", type(version.PROTOCOL), "number")
spec.check(
  "PROTOCOL is a positive integer",
  version.PROTOCOL == math.floor(version.PROTOCOL) and version.PROTOCOL >= 1,
  true
)

spec.finish()
