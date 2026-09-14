# Beat 1 — Everyday editing. Narration captions (burned in post-render):
#   (1) "Code completion — from your build"  (2) "Type errors, caught as you type"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat1-edit.tape
#
# Story: the everyday inner loop before the differentiators — complete a statement, make a type
# mistake, get told instantly, fix it, save clean. All on app/Main.java with line/motion navigation
# only (never /search: the clips are concatenated, so hlsearch must never bleed across a seam).
Output "docs/videos/beat1-edit.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; reset Main, capture fresh,
# open and warm the index (so completion is ready the instant the clip starts). MAVEN_ARGS keeps the
# build cache off (a cache hit skips compilation -> nothing captured). `|| exit 1` guards the cd. ---
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'export MAVEN_ARGS="-Dmaven.build.cache.enabled=false"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/Main.java" app/src/main/java/com/example/app/Main.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 12s
Type 'clear' Enter
Type 'nvim -n app/src/main/java/com/example/app/Main.java' Enter
Sleep 9s          # server start + workspace index warm (completion needs the classpath)
Show

# Navigation notes (this config): the cursor keeps its column after `NG`, so always re-anchor with
# `_` (first non-blank) before a horizontal motion. And blink.cmp maps <Esc> to "cancel the menu",
# so after any insert that may pop completion we press Escape TWICE (close menu, then leave insert).

# --- add a statement with completion (record accessor from the reactor) ---
Type '8G'                 # the `final var user = ...` line
Type 'o'                  # open a new line below it
Type 'final var greeting = user.nam'
Sleep 1400ms              # blink menu: `name() String`
Enter                     # accept -> user.name()  (cursor lands inside the parens)
Escape
Sleep 150ms
Escape
Type 'A'                  # append at line end...
Type ';'                  # ...close the statement
Escape
Sleep 2200ms              # valid: greeting inferred String

# --- introduce an incompatible type: var -> int (2nd word of the greeting line) ---
Type '9G'
Type '_'                  # re-anchor to first non-blank (`final`)
Type 'w'                  # -> `var`
Type 'ciw'
Type 'int'
Sleep 200ms
Escape
Sleep 150ms
Escape                    # 2nd Escape leaves insert (1st only closed the menu)
Sleep 3400ms             # live diagnostic: "incompatible types: String cannot be converted to int"

# --- fix it: int -> var ---
Type '9G'
Type '_'
Type 'w'                  # -> `int`
Type 'ciw'
Type 'var'
Sleep 200ms
Escape
Sleep 150ms
Escape
Sleep 2200ms             # diagnostic clears

# --- use `greeting` so it isn't unused, then save clean ---
Type '10G'               # the println line
Type '_'
Type 'f('                # first '(' -> println(
Type 'ci('               # replace the argument
Type 'greeting'
Sleep 200ms
Escape
Sleep 150ms
Escape
Sleep 800ms
Type ':w'
Enter
Sleep 3000ms             # full compile on save: no diagnostics

Sleep 800ms
