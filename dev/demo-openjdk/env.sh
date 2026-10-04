# Shared environment for the Lathe OpenJDK demo tape (dev/demo-openjdk/openjdk.tape).
# Sourced from the tape's hidden setup with $R = repo root.
#
# Unlike the Maven demo, the "fixture" is an EXTERNAL, already built + synced OpenJDK checkout (see
# docs/guide/openjdk.md). We use an isolated copy of the sample config examples/nvim and the published
# client+server from ~/.cache/lathe/current (installed by `lathe-openjdk-maven-plugin:sync`) — so we do
# NOT override LATHE_CACHE (the client resolves the server from ~/.cache/lathe by default).
export XDG_CONFIG_HOME="$R/dev/demo-openjdk/.nvim/config" XDG_DATA_HOME="$R/dev/demo-openjdk/.nvim/data" XDG_STATE_HOME="$R/dev/demo-openjdk/.nvim/state" XDG_CACHE_HOME="$R/dev/demo-openjdk/.nvim/cache"
export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim"
# Point at a built + synced OpenJDK checkout. Override on the command line for a non-default location:
#   LATHE_OPENJDK_DIR=~/src/jdk ./dev/demo-openjdk/record.sh
export LATHE_OPENJDK_DIR="${LATHE_OPENJDK_DIR:-$HOME/git/jdk}"
