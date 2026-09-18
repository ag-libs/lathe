# Lathe — Emacs, imports fold beat. Run from repo root after prepare.sh:
#   vhs dev/demo-emacs/fold.tape        ->   docs/videos/emacs-fold.mp4
#
# Shows Lathe's LSP folding range collapsing the whole import block to one line
# (the same idea as the Neovim client), toggled with C-c l TAB. Auto-fold is
# disabled here so the imports start expanded and the collapse is visible.
#
# The config load + server connect is done under Hide (VHS drops hidden time from
# the output), so the clip opens on the ready file and stays short.

Output docs/videos/emacs-fold.mp4
Require emacs

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

Hide
Type 'R="$PWD"' Enter
Type 'export LATHE_DEMO_ELPA="$R/dev/demo-emacs/emacsd/elpa/" LATHE_SKIP_PKG_INSTALL=1' Enter
Type 'clear' Enter
# Open with auto-fold OFF so the import block is visible first, and wait (hidden)
# for the full config load + server "workspace ready".
Type 'emacs -nw -q -l dev/demo-emacs/demo-init.el --eval "(setq lathe-fold-imports-on-open nil)" lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheLanguageServer.java' Enter
Sleep 20s
Show

Sleep 1800ms      # dwell on the full import block (2..28)

# Collapse the imports (C-c l TAB -> lathe-toggle-imports-fold)
Ctrl+C
Type "l"
Tab
Sleep 2600ms      # the whole block becomes a single "import … (N lines)" summary

# Expand again to show it is live and reversible
Ctrl+C
Type "l"
Tab
Sleep 2200ms

# Collapse once more for a clean ending
Ctrl+C
Type "l"
Tab
Sleep 1800ms
