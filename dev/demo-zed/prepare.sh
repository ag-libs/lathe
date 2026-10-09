#!/usr/bin/env bash
# Prepare the Zed demo. Idempotent.
#
#   1. build + install Lathe (server + extension);
#   2. run the multi-module invoker fixture -> a synced copy at
#      lathe-maven-plugin/target/it/multi-module (with .lathe/);
#   3. drop the documented .zed/settings.json into it (Lathe in the Java extension's jdtls slot);
#   4. `git init` it so Zed shows a normal project.
#
# Then:  ./dev/demo-zed/record.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
fixture="$repo/lathe-maven-plugin/target/it/multi-module"

echo "[demo] building + installing Lathe…"
(cd "$repo" && mvn -q install -DskipTests)

echo "[demo] building the multi-module invoker fixture…"
(cd "$repo" && mvn -q verify -pl lathe-maven-plugin -Dinvoker.test=multi-module)
[ -x "$fixture/.lathe/lathe-launcher.sh" ] \
  || { echo "[demo] server launcher missing at $fixture/.lathe — invoker build failed?" >&2; exit 1; }

echo "[demo] writing the fixture's .zed/settings.json…"
mkdir -p "$fixture/.zed"
cp "$here/fixture-settings.json" "$fixture/.zed/settings.json"

(cd "$fixture" && { [ -d .git ] || git init -q .; })

echo
echo "[demo] ready. Record with:  ./dev/demo-zed/record.sh"
