# Lathe × Emacs — vanilla, zero-config. Flow:
#   cd into a Maven module  ->  mvn process-test-classes (Lathe syncs .lathe)
#   ->  one command: emacs -Q + built-in Eglot pointed at the Lathe launcher
#   ->  M-. cross-module go-to-definition (app Main -> core StringUtils)
# Recorded against the public multi-module invoker fixture (com.example).
Output docs/videos/emacs-invoker.mp4
Require emacs

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 22
Set Width 1600
Set Height 900
Set Padding 20
# Terminal commands are not the interesting part — type them fast; spend the time on the LSP results.
Set TypingSpeed 20ms

# 1) go to the module
Type "cd ~/work/git/lathe/lathe-maven-plugin/target/it/multi-module" Enter
Sleep 1200ms

# 2) make it a git repo — vanilla project.el finds the reactor root via the VC backend
Type "git init -q" Enter
Sleep 1000ms

# 3) build + sync — Lathe writes .lathe from the real Maven build
Type "mvn clean process-test-classes" Enter
Sleep 3s

# 4) one command: vanilla emacs -Q + Eglot, launcher = the 0.1.0-SNAPSHOT invoker server
Type@20ms `emacs -Q app/src/main/java/com/example/app/Main.java --eval "(progn (require 'eglot) (add-to-list 'eglot-server-programs (list 'java-mode (expand-file-name \"~/.cache/lathe/servers/0.1.0-SNAPSHOT/lathe-launcher.sh\"))) (eglot-ensure))"` Enter
Sleep 5s

# 5) cross-module go-to-definition: StringUtils (app) -> core module
Escape
Type "gg"
Type "9"
Enter
Sleep 600ms
Ctrl+S
Type "StringUtils"
Enter
Escape
Type "b"
Sleep 400ms
Escape
Type "."
Sleep 3500ms

# 6) back to Main, completion on the record — accessors resolved from the reactor
Escape
Type ","              # M-, -> back to Main.java
Sleep 1200ms
Escape
Type "gg"             # M-g g -> goto-line (Escape needed here — this was the gg9 bug)
Type "9"
Enter                 # line 9, inside main() where `user` is in scope
Ctrl+E                # end of line
Enter                 # fresh line below
Type "user."
Escape
Tab                   # M-TAB -> completion-at-point -> *Completions*: name, age, …
Sleep 4000ms
