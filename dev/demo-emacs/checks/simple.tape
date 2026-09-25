# Simple flow: pwd -> mvn (lathe:sync, so .lathe is fresh & no stale prompt) -> emacs -Q + Eglot -> goto-def
Output "docs/videos/simple.mp4"
Require emacs
Set Shell "bash"
Set Width 1400
Set Height 800
Set FontSize 22
Hide
Type 'cd ~/work/git/lathe/lathe-maven-plugin/target/it/multi-module' Enter
Type '[ -d .git ] || git init -q .' Enter
Type 'clear' Enter
Sleep 400ms
Show
Type "pwd" Enter
Sleep 1200ms
Type "mvn -q process-test-classes" Enter
Sleep 30s
Type "~/work/git/lathe/dev/demo-emacs/open-emacs.sh app/src/main/java/com/example/app/Main.java" Enter
Sleep 22s
Escape
Type "gg"
Type "9"
Enter
Ctrl+S
Type "StringUtils"
Enter
Escape
Type "b"
Escape
Type "."
Sleep 4s
