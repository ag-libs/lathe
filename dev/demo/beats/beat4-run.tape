# Beat 4 — Run (modular). Narration captions (burned in post-render), finalized separately:
#   (1) "Run a modular main — replayed from your capture"
#   (2) "Live JUL output — running Java on the captured module path" (stdout vs stderr colour-coded)
#   (3) "The launch command — a plain modular java run"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat4-run.tape
#
# Story: run jpms/HelloMain at beat 3's end state (logger + requires java.logging). <leader>rr
# (:LatheRun) replays it from captured .lathe bytecode — no Maven, no recompile — and the two JUL
# INFO lines stream into Lathe's docked output split, a second apart (Thread.sleep) so it reads live.
# The final-state sources are copied from dev/demo/beats/beat4-fixture/ (beat 3 produces this state;
# each clip is independent, so beat 4 establishes it directly). leader = '\'.
Output "docs/videos/beat4-run.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 45ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; write the beat-3 end state
# (logger + requires), capture so .lathe has the classes + main-launch.json, open HelloMain, warm. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/dev/demo/beats/beat4-fixture/HelloMain.java" jpms/src/main/java/com/example/jpms/HelloMain.java' Enter
Type 'cp "$R/dev/demo/beats/beat4-fixture/module-info.java" jpms/src/main/java/module-info.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 12s
Type 'clear' Enter
Type 'nvim -n jpms/src/main/java/com/example/jpms/HelloMain.java' Enter
Sleep 9s
Show

# --- run the modular main; the JUL lines stream into the docked output split ---
Type '11G'                # onto the `public static void main(...)` line
Sleep 800ms
Type '\rr'                # <leader>rr = :LatheRun — replay from .lathe, output docks and streams
Sleep 2200ms             # launch command + "INFO: starting"
Sleep 2200ms             # ~1s later (Thread.sleep) "INFO: done" appears — visibly live
Sleep 800ms              # brief dwell on the completed run

# focus the docked output, jump to line 1 (the runner command), wrap it so the whole modular
# `java --module-path ... -m com.example.jpms/...HelloMain` launch — replayed from .lathe — is visible
Ctrl+W
Type 'j'
Sleep 700ms
Type 'gg'
Sleep 500ms
Type ':setlocal wrap'
Enter
Sleep 2600ms

