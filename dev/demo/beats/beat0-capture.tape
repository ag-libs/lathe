# Beat 0 — Capture. Narration caption (burned in post-render): "Capture your Maven build — no LSP setup"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat0-capture.tape
Output docs/videos/beat0-capture.mp4
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 35ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; warm mvnd (build cache off) ---
# MAVEN_ARGS disables the build cache for every mvn call, so the VISIBLE command stays the clean
# README one while the build still compiles (a cache hit would skip compilation -> nothing captured).
# The `|| exit 1` guard means a missing fixture yields an empty clip, never a repo-root `mvn clean`.
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'export MAVEN_ARGS="-Dmaven.build.cache.enabled=false"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 5s
Type 'clear' Enter
Show

# --- capture once from the real Maven build (capture-only: no test execution) ---
Type 'mvn clean test -Dlathe.capture.only=true'
Sleep 1s
Enter
Sleep 3s          # reactor SUCCESS, Tests run: 0, BUILD SUCCESS

# --- open a class; Lathe reads the reactor it just captured ---
Type 'nvim -n app/src/main/java/com/example/app/Main.java'
Sleep 1s
Enter
Sleep 3s          # Lathe workspace-ready
