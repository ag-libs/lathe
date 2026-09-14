# Beat 5 — Debug. Narration captions (burned in post-render), finalized separately:
#   (1) "Set a breakpoint, debug the replay"
#   (2) "Variables at the frame — inspect the User record"
#   (3) "Live expression eval — user.name().toLowerCase() -> alice"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat5-debug.tape
#
# Story: debug app/Main replayed from .lathe — breakpoint on the println, hit it, inspect the `user`
# record in the dap-view panel, and evaluate a live method call in the REPL (user.name().toLowerCase()
# -> "alice"), then continue. The first DAP attach is slow (~18s), so warm it hidden and reset, so the
# visible attach is fast. leader = '\'. Line/motion navigation only.
Output "docs/videos/beat5-debug.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

# --- hidden setup: isolated config + Lathe + invoker cache; reset app sources, capture, open Main,
# warm the index AND the debug attach (run one session + terminate so the visible attach is fast). ---
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
# warm the debug attach: run one session to the breakpoint, then terminate and clear it
Type '9G'
Type '\db'
Sleep 500ms
Type '\dd'
Sleep 22s
Type '\dt'
Sleep 2000ms
Type '\db'
Sleep 500ms
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
Sleep 6000ms             # attach (warmed) + stop; dap-view panel opens with scopes (args, user)

# --- 2) live expression evaluation at the paused frame ---
Type '\dr'                # toggle the dap REPL
Sleep 1200ms
Type 'i'                  # into the REPL prompt
Type 'user.name().toLowerCase()'
Enter
Sleep 2600ms             # -> "alice"
Escape

# --- 3) continue to completion ---
Type '\dc'
Sleep 2500ms

Sleep 800ms
