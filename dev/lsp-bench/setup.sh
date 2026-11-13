#!/usr/bin/env bash
# Prepares what the benchmark runs, into .work/ (README.md, Servers and Projects):
#   - jdtls at the pinned version;
#   - the Lathe Gradle plugin and the Lathe jars it resolves, from a Lathe build of the pinned commit;
#   - a clone of each project at its pinned commit, from the local clone where there is one.
# The pinned Lathe build itself is installed into ~/.m2 beforehand, from a clean checkout:
#   git worktree add --detach <dir> <commit>; cd <dir>
#   mvn versions:set -DnewVersion=<version> -DprocessAllModules -DgenerateBackupPoms=false
#   mvn install -DskipTests -Dspotless.check.skip=true
#
#   LATHE_BUILD=<dir> dev/lsp-bench/setup.sh [project ...]
set -euo pipefail
cd "$(dirname "$0")"
WORK=$PWD/.work
: "${LATHE_BUILD:?set LATHE_BUILD to the checkout the pinned Lathe was built in}"

JDTLS_TARBALL=jdt-language-server-1.63.0-202610191322.tar.gz
JDTLS_URL=https://download.eclipse.org/jdtls/milestones/1.63.0/$JDTLS_TARBALL
mkdir -p "$WORK/servers" "$WORK/repos"

echo "== jdtls"
if [ ! -d "$WORK/servers/jdtls/plugins" ]; then
  [ -f "$WORK/servers/$JDTLS_TARBALL" ] || curl -fsSL -o "$WORK/servers/$JDTLS_TARBALL" "$JDTLS_URL"
  mkdir -p "$WORK/servers/jdtls"
  tar xzf "$WORK/servers/$JDTLS_TARBALL" -C "$WORK/servers/jdtls"
fi

echo "== Lathe Gradle plugin and jars"
rm -rf "$WORK/lathe-gradle-repo"
mkdir -p "$WORK/lathe-gradle-repo"
cp -r "$LATHE_BUILD/lathe-gradle-plugin/target/lathe-repo/." "$WORK/lathe-gradle-repo/"
cp -r "$LATHE_BUILD/lathe-gradle-plugin/target/gradle/fixtures-repo/." "$WORK/lathe-gradle-repo/"

projects=("$@")
if [ ${#projects[@]} -eq 0 ]; then
  mapfile -t projects < <(python3 -I -c "import sys; sys.path.insert(0, '.'); import projects; print('\n'.join(projects.PROJECTS))")
fi
for name in "${projects[@]}"; do
  read -r url commit < <(python3 -I -c "import sys; sys.path.insert(0, '.'); import projects
p = projects.PROJECTS['$name']; print(p['url'], p['commit'])")
  echo "== $name ${commit:0:10}"
  [ -d "$WORK/repos/$name" ] || git clone -q --no-checkout "$url" "$WORK/repos/$name"
  repo=$WORK/repos/$name
  # The branch switch checks out an older commit: a shallow local clone lacks its history, a
  # partial one its files, so both come from the local clone's own origin.
  if [ -d "$url" ] && origin=$(git -C "$url" remote get-url origin 2>/dev/null); then
    if [ "$(git -C "$url" rev-parse --is-shallow-repository)" = true ]; then
      git -C "$repo" fetch -q --depth=120 "$origin" "$(git -C "$repo" rev-parse "$commit")"
    fi
    if [ -n "$(git -C "$url" config remote.origin.partialclonefilter || true)" ] \
        && ! git -C "$repo" remote | grep -qx upstream; then
      git -C "$repo" remote add upstream "$origin"
      git -C "$repo" config remote.upstream.promisor true
      git -C "$repo" config remote.upstream.partialclonefilter blob:none
      git -C "$repo" config extensions.partialClone upstream
    fi
  fi
  git -C "$repo" checkout -q --detach "$commit"
done
echo "Next, per project: python3 bench.py <project> --prime (downloads dependencies, not measured)."
