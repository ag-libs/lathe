# Beat 5 — Debug. Narration captions (burned in post-render), see beat5-debug.ass:
#   (1) "Set a breakpoint and debug the test"
#   (2) "Inspect the paused frame"
#   (3) "Evaluate a call: pet.getName().toUpperCase() → BUDDY"
#   (4) "Continue — the test passes"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat5-debug.tape
#
# Story: debug petclinic's OwnerTests.addPetAddsPersistedPet, replayed from .lathe/ — a breakpoint on
# the assertion, :LatheDebug, then the paused frame in dap-view: watch the owner's pets and evaluate a
# method call in the debuggee, then continue. Notes:
#  - The first debug attach is slow: it is warmed off camera (one session, terminated), and the
#    leftover output split is closed before Show.
#  - Watches are added with a `:Watch <expr>` command (defined off camera) that calls dap-view's
#    add_expr — robust next to focusing the panel by hand under VHS.
# leader = Space. Line/motion navigation only.
Output "docs/videos/beat5-debug.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 45ms

# --- hidden setup: the clone prepare.sh set up, sources reset; open the test, wait for the
# workspace, warm the debug attach, then clean up. `|| exit 1` guards the cd. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'renice -n 15 $$ >/dev/null' Enter
Type 'cd "$PETCLINIC" || exit 1' Enter
Type 'git checkout -q -- src' Enter
Type 'clear' Enter
Type 'nvim -n src/test/java/org/springframework/samples/petclinic/owner/OwnerTests.java +34' Enter
Wait+Screen@60s /workspace ready/
Type ':command! -nargs=1 Watch lua require("dap-view").add_expr(<q-args>, false)' Enter
Type '34G'
Type ' db'
Sleep 400ms
Type ' dd'
Sleep 25s
Type ' dt'
Sleep 3s
Type ' db'
Sleep 500ms
Type ':only' Enter
Type 'gg'
Type ':lua Snacks.notifier.hide()' Enter
Type ':echo ""' Enter
Sleep 500ms
Show

# --- 1) a breakpoint on the assertion, then debug the test ---
Type '34G'                # assertTrue(owner.getPets().contains(pet)) — owner and pet are in scope
Sleep 700ms
Type ' db'                # toggle breakpoint (gutter sign)
Sleep 1200ms
Type ' dd'                # :LatheDebug — replay under the debugger, stops at the breakpoint
Wait+Screen@60s /Listening for transport/
Sleep 2000ms              # the debugger attaches and the frame pauses on the breakpoint

# --- 2) inspect the paused frame ---
Type ':Watch owner.getPets()'
Enter
Wait+Screen@20s /size=1/
Sleep 2000ms

# --- 3) live expression evaluation (a method call in the debuggee) ---
Type ':Watch pet.getName().toUpperCase()'
Enter
Wait+Screen@20s /"BUDDY"/
Sleep 2500ms

# --- 4) continue to the end ---
Type ' dc'
Wait+Screen@30s /1 passed/
Sleep 2000ms              # the test finishes: "1 passed"
