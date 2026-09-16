# Beat 1 — Everyday editing. Narration captions (burned in post-render), timed to the on-screen state:
#   (1) "Code completion — from your build"  (2) "Type mismatch — flagged live"
#   (3) "Fixed and saved — no diagnostics"  (3 only appears AFTER the save, when the buffer is truly
#   clean; never over the transient unused-variable hint between the fix and the save).
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
Set TypingSpeed 45ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; reset Main, capture fresh,
# open and warm the index (so completion is ready the instant the clip starts). MAVEN_ARGS keeps the
# build cache off (a cache hit skips compilation -> nothing captured). `|| exit 1` guards the cd. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/Main.java" app/src/main/java/com/example/app/Main.java' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/User.java" app/src/main/java/com/example/app/User.java' Enter
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
Sleep 1200ms              # valid: greeting inferred String

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
Sleep 2600ms             # live diagnostic: "incompatible types: String cannot be converted to int"

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
Sleep 1400ms             # type-mismatch clears (a transient 'unused greeting' hint remains until it's used)

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
Sleep 600ms
Type ':w'
Enter
Sleep 2600ms             # full compile on save: no diagnostics — the clean state caption 3 lands here

