# Level 3 — `emacs -nw -Q` + built-in Eglot connecting to Lathe. Proves the LSP connect renders.
# Needs the invoker fixture built (./dev/demo-emacs/prepare.sh). Run from the repo root.
Output "docs/videos/check3-eglot.mp4"
Require emacs
Set Shell "bash"
Set Width 1400
Set Height 800
Set FontSize 20
Hide
Type 'R="$PWD"' Enter
Type 'ln -sf "$R/lathe-maven-plugin/target/it-home/.cache/lathe/current/lathe-launcher.sh" /tmp/lathe-launcher.sh' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type '[ -d .git ] || git init -q .' Enter
Type 'clear' Enter
Type 'emacs -nw -Q app/src/main/java/com/example/app/Main.java' Enter
Sleep 3s
Show
Escape
Type "x"
Type "eglot"
Enter
Sleep 700ms
Type "/tmp/lathe-launcher.sh"
Enter
Sleep 20s          # server start + reactor index warm; echo area -> "workspace ready"
Escape
Type "x"
Type "kill-emacs"
Enter
Sleep 500ms
