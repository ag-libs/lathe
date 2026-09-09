# Lathe demo — ~30s hero. Run from the repo root after ./dev/demo/prepare.sh:
#   vhs dev/demo/demo.tape        ->   docs/demo.gif
#
# Uses an isolated COPY of your Neovim config (dev/demo/.nvim), with the Lathe
# client from this checkout (LATHE_NVIM_DIR) and the server from the invoker
# cache (LATHE_CACHE). The fixture is the multi-module invoker workspace, already
# built with a .lathe/ capture.
#
# Story — one thread: Lathe works from your real Maven build.
#   Beat 1  cross-module go-to-definition (app -> core)
#   Beat 2  hover on an annotation-processor-generated type (Lathe resolves generated sources)
#   Beat 3  run a whole test file, replayed from .lathe/ bytecode (no Maven, no recompile)
#
# NOTE: leader is '\' in this config (init.lua: mapleader = "\\"), so the test
# keymaps are \tf and \to. Sleeps are tuned against the recorded GIF — re-tune if
# the fixture or server timing changes.

Output docs/demo.gif
Require nvim

Set Shell "bash"
# Monaco for the code, with a Nerd-Font fallback so gutter/statusline icons render
# instead of tofu boxes (Monaco ships no Nerd glyphs). Both are installed on the box.
# Render at 1.5x scale (font + dims together): same rows/columns, but ~2x the pixels
# per glyph so Monaco — a bitmap-derived face that anti-aliases soft at small sizes —
# stays crisp after GIF palette quantization.
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 60ms

# --- hidden setup: isolated config copy + Lathe from checkout + invoker server cache ---
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'cd lathe-maven-plugin/target/it/multi-module' Enter
Type 'clear' Enter
Show

# Open a class in the app module; Lathe indexes the whole reactor and reports ready.
# -n disables swap files: VHS kills nvim at tape end with no clean :q, so without this
# every rerun hits an E325 swap-recovery prompt that eats the first beat's keystrokes.
Type 'nvim -n app/src/main/java/com/example/app/Main.java' Enter
Sleep 6s          # server start + workspace index + first paint ("workspace ready")

# --- Beat 1: cross-module go-to-definition (app -> core) ---
Type 'gg'
Type '/upper' Enter         # unique in this file: the cross-module call StringUtils.upper(...)
Sleep 800ms
Type 'gd'                   # telescope lsp_definitions; a single result auto-jumps into core/StringUtils.java
Sleep 1800ms                # dwell on the core module source
Ctrl+O                      # jump back to Main.java
Sleep 800ms

# --- Beat 2: hover a generated type (User is a @Builder record; UserBuilder is generated) ---
Type '/UserBuilder' Enter   # the annotation-processor-generated builder, used on the main() line
Sleep 800ms
Type 'K'                    # hover: AST-resolved info for a type Lathe reads from generated-sources
Sleep 2200ms                # dwell on the hover popup
Escape                      # dismiss the hover float

# --- Beat 3: run the whole test file, replayed from .lathe/ (no Maven, no recompile) ---
Type ':e jpms/src/test/java/com/example/jpms/HelloTest.java' Enter
Sleep 3s                    # neotest discovery: runnable glyphs appear in the gutter
Type '\tf'                  # <leader>tf = Run File Tests (leader = '\')
Sleep 4500ms                # replay + streamed events; gutters flip green, toast fires
Type '\to'                  # <leader>to = Lathe's docked, navigable output split
Sleep 3500ms                # streamed stdout/stderr + the real java launch command

Sleep 500ms
