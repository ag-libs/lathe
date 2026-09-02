# STATUS: FIRST DRAFT — not final. The scenario needs a substantial rework: the current beats do not
# showcase Lathe's features well (e.g. `/name` lands on the parameter, not the return; the completion
# popup doesn't reliably land). Timings are untuned. Treat this as a starting point, not the demo.
#
# Lathe demo — short hero (~18s). Run from the repo root after ./dev/demo/prepare.sh:
#   vhs dev/demo/demo.tape        ->   docs/demo.gif
#
# Uses an isolated COPY of your Neovim config (dev/demo/.nvim), with Lathe pointed
# at this checkout (LATHE_NVIM_DIR) and the server at the invoker cache (LATHE_CACHE).
# Beats: completion -> cross-module go-to-definition -> run the test file.
# Sleep values are first-pass guesses — watch the GIF and tune them.

Output docs/demo.gif
Require nvim

Set Shell "bash"
Set FontSize 16
Set Width 1200
Set Height 720
Set Padding 20
Set TypingSpeed 55ms

# --- hidden setup: isolated config copy + Lathe from checkout + invoker server cache ---
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'cd lathe-maven-plugin/target/it/multi-module' Enter
Type 'clear' Enter
Show

# launch your config (via the copy) on a cross-module class
Type 'nvim app/src/main/java/com/example/app/FormalGreeter.java' Enter
Sleep 5s          # [TUNE] server start + workspace index + first paint

# --- Beat 1: completion (nvim-cmp popup on a String member) ---
Type '/name' Enter          # jump to the `name` use in the return expression
Type 'ea'                   # append after the word, enter insert
Type '.'                    # cmp completion on String pops up
Sleep 2500ms      # [TUNE]
Escape
Type 'u'                    # undo the stray '.', leave the file clean
Sleep 800ms

# --- Beat 2: cross-module go-to-definition (app -> core) ---
Type '/Greeter' Enter       # the `implements Greeter`
Type 'gd'                   # your gd = telescope lsp_definitions
Sleep 1500ms
# If a 1-item Telescope picker appears instead of an auto-jump, uncomment:
# Enter
Sleep 1500ms      # [TUNE] show core/.../Greeter.java
Ctrl+O                      # jump back
Sleep 600ms

# --- Beat 3: run the test file (replay from .lathe/ bytecode, no Maven) ---
Type ':e jpms/src/test/java/com/example/jpms/HelloTest.java' Enter
Sleep 2s          # [TUNE] neotest discovery / gutter glyphs
Type ' tf'                  # <leader>tf = Run File Tests
Sleep 3500ms      # [TUNE] replay + streamed events; gutters flip green, toast fires
Type ' to'                  # <leader>to = Test Output (docked, navigable)
Sleep 3s          # [TUNE] show streamed stdout/stderr + the run command line

Sleep 1500ms
