# Lathe × Emacs — vanilla emacs -Q + built-in Eglot. The important features, tight.
# Meta is sent as real Alt+ chords (atomic + reliable). Per beat: navigate -> ~0.9s to SEE the
# cursor land -> action -> dwell on the result.
Output docs/videos/emacs-tour.mp4
Require emacs

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 22
Set Width 1600
Set Height 900
Set Padding 20
Set TypingSpeed 10ms

# ---------- off-camera: project setup (git root + Lathe sync), not interesting to watch ----------
Hide
Type 'cd ~/work/git/lathe/lathe-maven-plugin/target/it/multi-module' Enter
Type 'git init -q' Enter
Type 'find . -name "#*#" -delete; find . -name ".#*" -delete' Enter
Type 'cd ~' Enter
Type 'clear' Enter
Sleep 500ms
Show

# ---------- on camera: cd into the module, then how you launch Emacs ----------
Type "cd ~/work/git/lathe/lathe-maven-plugin/target/it/multi-module" Enter
Sleep 1s
Type@10ms `emacs -Q app/src/main/java/com/example/app/Main.java --eval "(progn (require 'eglot) (add-to-list 'eglot-server-programs (list 'java-mode (expand-file-name \"~/.cache/lathe/servers/0.1.0-SNAPSHOT/lathe-launcher.sh\"))) (eglot-ensure))"`
Sleep 2s
Enter
Sleep 5s                            # server connects -> "workspace ready"

# 1) Hover / javadoc on a JDK call (println)
Ctrl+G
Alt+g
Alt+g
Type "9"
Enter
Sleep 700ms
Ctrl+S
Type "println"
Enter
Alt+b
Sleep 1200ms
Alt+x
Type "eldoc-doc-buffer"
Enter
Sleep 4000ms
Ctrl+X
Type "b"
Type "Main.java"
Enter
Ctrl+X
Type "1"
Sleep 1000ms

# 2) Cross-module go-to-definition: StringUtils (app) -> core
Ctrl+G
Alt+g
Alt+g
Type "9"
Enter
Sleep 700ms
Ctrl+S
Type "StringUtils"
Enter
Alt+b
Sleep 900ms
Alt+.
Sleep 3200ms
Alt+x
Type "xref-go-back"
Enter
Sleep 1800ms

# 3) Completion from the build (User record accessors)
Ctrl+G
Alt+g
Alt+g
Type "9"
Enter
Sleep 500ms
Ctrl+E
Enter
Type@90ms "user."
Sleep 500ms
Ctrl+Alt+i
Sleep 3400ms
Ctrl+G
Ctrl+A
Ctrl+K
Sleep 1000ms

# 4) Extract variable (Lathe code action)
Ctrl+G
Alt+g
Alt+g
Type "9"
Enter
Sleep 500ms
Ctrl+S
Type "StringUtils"
Enter
Alt+b
Ctrl+@
Ctrl+Alt+f
Ctrl+Alt+f
Ctrl+Alt+f
Sleep 900ms
Alt+x
Type "eglot-code-action-extract"
Enter
Sleep 3200ms

# 5) Rename across uses
Ctrl+G
Alt+g
Alt+g
Type "9"
Enter
Sleep 500ms
Ctrl+S
Type "upper"
Enter
Alt+b
Sleep 800ms
Alt+x
Type "eglot-rename"
Enter
Type@90ms "greeting"
Sleep 400ms
Enter
Sleep 3200ms

# 6) Live error: insert a type-mismatch line, show diagnostics, then remove it
Ctrl+G
Alt+g
Alt+g
Type "9"
Enter
Sleep 500ms
Ctrl+E
Enter
Type@90ms "int n = greeting;"
Sleep 4000ms
Ctrl+G
Alt+x
Type "flymake-show-buffer-diagnostics"
Enter
Sleep 4000ms
Ctrl+G
Alt+g
Alt+g
Type "10"
Enter
Ctrl+A
Ctrl+K
Ctrl+K
Sleep 2000ms                        # diagnostics clear -> Flymake back to 0
Ctrl+X
Type "1"
Alt+g
Alt+g
Type "1"
Enter                               # cursor to top: a calm final view of the clean, refactored file
Sleep 5000ms                        # hold on clean code, no errors -> graceful end
