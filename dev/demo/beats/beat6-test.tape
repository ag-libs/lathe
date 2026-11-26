# Beat 6 — Test (fail -> fix -> green). Narration captions (burned in post-render), see beat6-test.ass:
#   (1) "Run the tests — replayed from your capture"
#   (2) "Red — the assertion fails, with the stack trace"
#   (3) "Fix it, run again — green"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat6-test.tape
#
# Story: petclinic's OwnerTests replayed from .lathe/ bytecode. Off camera, one assertion is made to
# expect 2 pets instead of 1. <leader>tf (Run File Tests, neotest + Lathe's adapter) runs it red, the
# docked output shows the failure; put the 1 back, save (the on-save compile updates .lathe/), and
# <leader>tf again -> green. No Maven, no recompile of the project. Each step waits on the screen.
# leader = Space. Line/motion navigation only.
Output "docs/videos/beat6-test.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 45ms

# --- hidden setup: sources reset, then the failing assertion; open the test and wait for the
# workspace, then save it once so .lathe/ holds the failing version. `|| exit 1` guards the cd. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'renice -n 15 $$ >/dev/null' Enter
Type 'cd "$PETCLINIC" || exit 1' Enter
Type 'git checkout -q -- src' Enter
Type 'sed -i "35s/assertEquals(1, /assertEquals(2, /" src/test/java/org/springframework/samples/petclinic/owner/OwnerTests.java' Enter
Type 'clear' Enter
Type 'nvim -n src/test/java/org/springframework/samples/petclinic/owner/OwnerTests.java +35' Enter
Wait+Screen@60s /workspace ready/
Type ':w' Enter
Sleep 3s
Type ':lua Snacks.notifier.hide()' Enter
Type ':echo ""' Enter
Sleep 500ms
Show

# --- 1) run the file's tests -> red, then the docked output with the failure ---
Sleep 800ms
Type ' tf'                # <leader>tf = Run File Tests (neotest + Lathe adapter, replayed from .lathe)
# The toast wraps as "1 passed, 1⏎failed": match the part that stays on one line.
Wait+Screen@60s /1 passed, 1/
Sleep 1500ms
Type ' to'                # <leader>to = Test Output (docked): the AssertionFailedError and its trace
Wait+Screen@20s /expected: <2>/
Sleep 3000ms

# --- 2) back to the editor, expect 1 pet again, save so the on-save compile updates .lathe/ ---
Ctrl+W
Type 't'
Type '35G'
Type '_'
Type 'f2'
Type 'r1'
Sleep 500ms
Type ':w'
Enter
Wait+Screen@15s /written/
Sleep 2000ms

# --- 3) run again -> green ---
Type ' tf'
Wait+Screen@60s /2 passed/
Sleep 3000ms
