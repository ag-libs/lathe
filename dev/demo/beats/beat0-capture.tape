# Beat 0 — Capture. Narration captions (burned in post-render):
#   (1) "Capture your build"  (2) "Open a file — Lathe attaches, no setup"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat0-capture.tape
Output "docs/videos/beat0-capture.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 35ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; warm mvnd (build cache off) ---
# MAVEN_ARGS disables the build cache for every mvn call, so the VISIBLE command stays the clean
# README one while the build still compiles (a cache hit would skip compilation -> nothing captured).
# The `|| exit 1` guard means a missing fixture yields an empty clip, never a repo-root `mvn clean`.
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/Main.java" app/src/main/java/com/example/app/Main.java' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/User.java" app/src/main/java/com/example/app/User.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 5s
Type 'clear' Enter
Show

# --- capture once from the real Maven build (capture-only: no test execution) ---
Type 'mvn clean test -Dlathe.capture.only=true'
Sleep 800ms
Enter
Sleep 2200ms      # reactor SUCCESS, Tests run: 0, BUILD SUCCESS

# --- open a class; Lathe reads the reactor it just captured ---
Type 'nvim -n app/src/main/java/com/example/app/Main.java'
Sleep 600ms
Enter
Sleep 2600ms      # Lathe workspace-ready (caption 2 is timed to this editor-visible segment, not the log)
