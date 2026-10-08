-- Client version + wire-protocol identity, read by lathe.setup() and (later) the
-- client<->server handshake. VERSION is a placeholder stamped to the release
-- semver by publish-nvim.sh at publish time; PROTOCOL is a coarse integer bumped
-- only on a breaking client<->server contract change (executeCommand names,
-- init_options shape, custom notifications) -- not on every release.
return {
  VERSION = '0.0.0-dev',
  PROTOCOL = 1,
}
