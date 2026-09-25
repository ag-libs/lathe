# Level 2 — plain `emacs -nw -Q`, no LSP. Proves a terminal Emacs TUI is captured (redraw/frames).
Output "docs/videos/check2-emacs.mp4"
Require emacs
Set Shell "bash"
Set Width 1000
Set Height 600
Set FontSize 22
Type "emacs -nw -Q"
Enter
Sleep 3s
Type "Hello from Emacs -Q  (typed into *scratch*)"
Sleep 2s
Escape
Type "x"
Type "kill-emacs"
Enter
Sleep 500ms
