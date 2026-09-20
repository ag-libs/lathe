-- Verifies the client<->server protocol handshake (lathe.check_protocol): a matching protocol is
-- silent; an older or absent server protocol nudges to rebuild the server; a newer server protocol
-- nudges to update the client. Drives check_protocol directly (no live server) and stubs vim.notify.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/handshake_spec.lua

local spec = require("spec_helper").new()

local lathe = require("lathe")
local client_protocol = require("lathe.version").PROTOCOL

local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end

-- Matching protocol: silent.
lathe.check_protocol(client_protocol)
spec.check("matching protocol: silent", #notes, 0)

-- Absent (an old server that predates the capability): nudge to bump the pinned server version.
lathe.check_protocol(nil)
spec.check("absent protocol: one notify", #notes, 1)
spec.check(
  "absent: message says bump lathe-maven-extension",
  notes[#notes] and notes[#notes]:find("lathe-maven-extension", 1, true) ~= nil,
  true
)

-- Older server than client: same "server is older" nudge.
lathe.check_protocol(client_protocol - 1)
spec.check("older server: notify", #notes, 2)
spec.check(
  "older: message says bump lathe-maven-extension",
  notes[#notes] and notes[#notes]:find("lathe-maven-extension", 1, true) ~= nil,
  true
)

-- Newer server than client: nudge to update the plugin.
lathe.check_protocol(client_protocol + 1)
spec.check("newer server: notify", #notes, 3)
spec.check(
  "newer: message says update lathe.nvim",
  notes[#notes] and notes[#notes]:find("update lathe.nvim", 1, true) ~= nil,
  true
)

spec.finish()
