#!/usr/bin/env bash
# Prepare the Emacs demo. Idempotent. Unlike the Neovim demo, this needs NO editor config:
# the whole point is a stock `emacs -Q` with the built-in Eglot, exactly as a newcomer would try it.
#
#   1. build + install Lathe (server + extension);
#   2. run the multi-module invoker fixture -> a synced copy at
#      lathe-maven-plugin/target/it/multi-module (with .lathe/) plus an isolated server cache at
#      lathe-maven-plugin/target/it-home/.cache/lathe;
#   3. `git init` that fixture copy -- vanilla Emacs finds a project via the version-control backend,
#      and Lathe takes the workspace root verbatim, so a git root == the reactor root is exactly the
#      "git-backed project" the Eglot maintainer recommends trying first.
#
# Then:  vhs dev/demo-emacs/demo.tape        ->   docs/videos/emacs-invoker.mp4
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
fixture="$repo/lathe-maven-plugin/target/it/multi-module"
cache="$repo/lathe-maven-plugin/target/it-home/.cache/lathe"

echo "[demo] building + installing Lathe…"
(cd "$repo" && mvn -q install -DskipTests)

echo "[demo] building the multi-module invoker fixture…"
(cd "$repo" && mvn -q verify -pl lathe-maven-plugin -Dinvoker.test=multi-module)
[ -x "$cache/current/lathe-launcher.sh" ] \
  || { echo "[demo] server launcher missing at $cache/current — invoker build failed?" >&2; exit 1; }
[ -d "$fixture/.lathe" ] \
  || { echo "[demo] no .lathe/ at $fixture — invoker build failed?" >&2; exit 1; }

echo "[demo] making the fixture a git-backed project (so vanilla project.el finds the root)…"
(cd "$fixture" && { [ -d .git ] || git init -q .; } && git add -A >/dev/null 2>&1 || true)

echo
echo "[demo] ready. Record with:"
echo "  vhs dev/demo-emacs/demo.tape        # writes docs/videos/emacs-invoker.mp4"
