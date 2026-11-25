# Beat 1 — Everyday editing. Narration captions (burned in post-render), timed to the on-screen state:
#   (1) "Code completion — from your build"  (2) "Type mismatch — flagged live"
#   (3) "Fixed and saved — no diagnostics"  (3 only appears AFTER the save, when the buffer is truly
#   clean).
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat1-edit.tape
#
# Story: the everyday inner loop on petclinic's Owner.getPet — retype `pet.getName()` with completion
# (inherited from NamedEntity, through the build's classpath), make the local an `int`, get told
# instantly, put `String` back, save clean. Line/motion navigation only (never /search: the clips are
# concatenated, so hlsearch must never bleed across a seam). Each step waits on the screen, not a
# fixed time: the machine may be busy.
Output "docs/videos/beat1-edit.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 45ms

# --- hidden setup: the clone prepare.sh set up with :LatheInit, sources reset; open Owner and wait
# for the workspace, so completion is ready the instant the clip starts. `|| exit 1` guards the cd. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'renice -n 15 $$ >/dev/null' Enter
Type 'cd "$PETCLINIC" || exit 1' Enter
Type 'git checkout -q -- src' Enter
Type 'clear' Enter
Type 'nvim -n "$PETCLINIC_MAIN" +149' Enter
Wait+Screen@60s /workspace ready/
Sleep 3s                  # the toast fades before Show
Show

# Navigation notes (this config): the cursor keeps its column after `NG`, so always re-anchor with
# `_` (first non-blank) before a horizontal motion. And blink.cmp maps <Esc> to "cancel the menu",
# so after any insert that may pop completion we press Escape TWICE (close menu, then leave insert).

# --- retype the initializer with completion: getName() comes from NamedEntity ---
Type '149G'
Type '_'
Type 'f='
Type 'w'                  # -> `pet`
Type 'D'                  # drop `pet.getName();`
Type 'a'
Type 'pet.getNa'
Wait+Screen@10s /getName\(\)/
Sleep 900ms               # read the menu
Enter                     # accept -> pet.getName()
Escape
Sleep 150ms
Escape
Type 'A'
Type ';'
Escape
Sleep 1000ms

# --- an incompatible type: String -> int ---
Type '149G'
Type '_'
Type 'ciw'
Type 'int'
Sleep 200ms
Escape
Sleep 150ms
Escape
Wait+Screen@15s /cannot be converted to int/
Sleep 2000ms              # read the live diagnostic

# --- fix it: int -> String, then save clean ---
Type '149G'
Type '_'
Type 'ciw'
Type 'String'
Sleep 200ms
Escape
Sleep 150ms
Escape
Sleep 1200ms              # the diagnostic clears
Type ':w'
Enter
Wait+Screen@15s /written/
Sleep 2000ms              # full compile on save: no diagnostics
