# Lathe — Emacs, completion beat. Run from repo root after prepare.sh:
#   vhs dev/demo-emacs/completion.tape   ->   docs/videos/emacs-completion.mp4
#
# As-you-type Corfu popup, candidates resolved from the build classpath (the
# lsp4j ServerCapabilities setters). Auto-fold OFF; config load + connect happen
# hidden so the clip opens on the ready file. Deterministic nav via M-g M-g.

Output docs/videos/emacs-completion.mp4
Require emacs

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 70ms

Hide
Type 'R="$PWD"' Enter
Type 'export LATHE_DEMO_ELPA="$R/dev/demo-emacs/emacsd/elpa/" LATHE_SKIP_PKG_INSTALL=1' Enter
Type 'clear' Enter
Type 'emacs -nw -q -l dev/demo-emacs/demo-init.el --eval "(setq lathe-fold-imports-on-open nil)" lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheLanguageServer.java' Enter
Sleep 20s
Show

# Go to the createCapabilities body (line 84), where `capabilities` is in scope.
Escape
Type "gg"           # M-g g -> goto-line
Type "84"
Enter
Sleep 800ms
Ctrl+E              # end of line
Enter               # fresh line below (auto-indented)
Sleep 400ms

# Type a member access; Corfu pops up as we type, filtered to setXxx.
Type "capabilities.set"
Sleep 2800ms        # dwell on the popup (candidates from lsp4j on the classpath)

Ctrl+G              # dismiss the popup, leave the buffer as we found it
Ctrl+A
Ctrl+K
Ctrl+K
Sleep 1000ms
