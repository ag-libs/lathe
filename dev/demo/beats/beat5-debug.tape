# Beat 5 — Debug. Narration captions (burned in post-render), finalized separately:
#   (1) "Set a breakpoint, debug the replay"
#   (2) "Inspect the frame — the User record's fields"
#   (3) "Live expression eval — user.name().toLowerCase() -> alice"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat5-debug.tape
#
# Story: debug app/Main replayed from .lathe — breakpoint on the println, hit it, inspect the `user`
# record and evaluate a live method call (user.name().toLowerCase() -> "alice") in the dap-view panel,
# then continue. Notes:
#  - First DAP attach is slow (~18s): warm it hidden (run one session, terminate), and close the
#    leftover docked output split before Show so the clip doesn't open on a finished run.
#  - Watch expressions are added via a `:Watch <expr>` command (defined hidden) that calls
#    dap-view's add_expr — robust vs. focusing the panel/REPL by hand under VHS. It shows the value
#    inline in the Watches section (records expand to their fields).
# leader = '\'. Line/motion navigation only.
Output "docs/videos/beat5-debug.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

# --- hidden setup: reset app sources, capture, open Main, warm index + debug attach, then clean up ---
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'export MAVEN_ARGS="-Dmaven.build.cache.enabled=false"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/Main.java" app/src/main/java/com/example/app/Main.java' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/User.java" app/src/main/java/com/example/app/User.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 12s
Type 'clear' Enter
Type 'nvim -n app/src/main/java/com/example/app/Main.java' Enter
Sleep 9s
# a small command that adds an evaluated watch to the dap-view panel (robust vs. panel/REPL focus)
Type ':command! -nargs=1 Watch lua require("dap-view").add_expr(<q-args>, false)' Enter
Sleep 200ms
# warm the debug attach so the visible one is fast, then terminate and clear
Type '9G'
Type '\db'
Sleep 400ms
Type '\dd'
Sleep 22s
Type '\dt'
Sleep 2000ms
Type '\db'
Sleep 500ms
# close the leftover docked output split so the clip opens on a clean editor, not a finished run
Ctrl+W
Type 't'
Ctrl+W
Type 'o'
Type 'gg'
Type ':echo ""' Enter
Sleep 500ms
Show

# --- 1) set a breakpoint on the println and start debugging ---
Type '9G'                 # the println line — `user` is assigned on the line above
Sleep 700ms
Type '\db'                # toggle breakpoint (gutter sign)
Sleep 1200ms
Type '\dd'                # :LatheDebug — replay under the debugger, suspends and hits the breakpoint
Sleep 1000ms             # warmed attach stops by ~3s (fast) — add the first watch promptly so the
                         # empty "No expressions" panel is barely shown

# --- 2) inspect the User record at the paused frame ---
Type ':Watch user'
Enter
Sleep 2800ms             # Watches shows user expanded -> name = "Alice", age = 30

# --- 3) live expression evaluation (method invocation in the debuggee) ---
Type ':Watch user.name().toLowerCase()'
Enter
Sleep 3600ms             # -> "alice"

# --- 4) continue to completion ---
Type '\dc'
Sleep 2800ms             # resumes; the docked output prints ALICE and the session ends

Sleep 800ms
