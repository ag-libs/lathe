-- Verifies the client<->server protocol handshake (lathe.check_protocol): a matching protocol is
-- silent; an older or absent server protocol nudges to rebuild the server, or to run :LatheInit in
-- a project set up from outside; a newer server protocol nudges to update the client. Drives check_protocol directly (no live server) and stubs vim.notify.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/handshake_spec.lua

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
spec.check(
  "absent: message names the Lathe build plugin generically",
  notes[#notes] and notes[#notes]:find("Lathe build plugin", 1, true) ~= nil,
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

-- An older server in a project that does not register Lathe: :LatheInit sets up the new version.
local work = vim.fn.tempname()
vim.fn.mkdir(work .. "/personal", "p")
vim.fn.writefile({ "<project/>" }, work .. "/personal/pom.xml")
vim.fn.mkdir(work .. "/team/.mvn", "p")
vim.fn.writefile({ "<project/>" }, work .. "/team/pom.xml")
vim.fn.writefile(
  {
    "<extensions><extension><artifactId>lathe-maven-extension</artifactId></extension></extensions>",
  },
  work .. "/team/.mvn/extensions.xml"
)
lathe.check_protocol(client_protocol - 1, work .. "/personal")
spec.check(
  "older server, set up from outside: message names :LatheInit",
  notes[#notes]:find(":LatheInit", 1, true) ~= nil
    and notes[#notes]:find("lathe-maven-extension", 1, true) == nil,
  true
)
lathe.check_protocol(client_protocol - 1, work .. "/team")
spec.check(
  "older server, Lathe registered: message says bump the build plugin",
  notes[#notes]:find("lathe-maven-extension", 1, true) ~= nil,
  true
)
vim.fn.delete(work, "rf")

spec.finish()
