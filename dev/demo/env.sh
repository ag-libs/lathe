# Shared environment for the Lathe demo tapes (dev/demo/beats/*.tape).
# Sourced from each tape's hidden setup with $R = repo root: an isolated copy of your Neovim config,
# the Lathe client from this checkout, the server/launcher from the invoker cache, and the Maven build
# cache turned off so captures actually compile.
export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"
export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"
export MAVEN_ARGS="-Dmaven.build.cache.enabled=false"
