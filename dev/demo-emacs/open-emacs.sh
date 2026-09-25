#!/usr/bin/env bash
# Open a Java file in vanilla `emacs -Q` with the built-in Eglot wired to the INSTALLED Lathe
# launcher — the "one line of Elisp" the Eglot maintainer describes, as a single shell command.
# No config files, no packages. Override the launcher with LATHE_LAUNCHER if needed.
set -euo pipefail
launcher="${LATHE_LAUNCHER:-$HOME/.cache/lathe/current/lathe-launcher.sh}"
exec emacs -nw -Q \
  --eval "(progn
            (require 'eglot)
            (add-to-list 'eglot-server-programs (list 'java-mode \"$launcher\"))
            (setq eglot-sync-connect 30)
            (add-hook 'java-mode-hook #'eglot-ensure))" \
  "$@"
