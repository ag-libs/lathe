#!/bin/sh
# Pre-populate the isolated package dir for the Emacs demo so the recording runs
# offline (no MELPA fetch mid-take). Run once from the repo root:
#   ./dev/demo-emacs/prepare.sh
#
# Installs into dev/demo-emacs/emacsd/elpa (override with LATHE_DEMO_ELPA). The
# tape then loads the same dir with LATHE_SKIP_PKG_INSTALL=1.
set -e
cd "$(git rev-parse --show-toplevel)"

: "${LATHE_DEMO_ELPA:=$PWD/dev/demo-emacs/emacsd/elpa/}"
export LATHE_DEMO_ELPA
mkdir -p "$LATHE_DEMO_ELPA"

echo "Installing demo packages into $LATHE_DEMO_ELPA ..."
emacs -Q --batch -l dev/demo-emacs/demo-init.el --eval '(kill-emacs 0)'
echo "Done. Record with:  vhs dev/demo-emacs/demo.tape"
