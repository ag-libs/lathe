#!/usr/bin/env bash
# Cut a Lathe release.
#
# Usage: ./release.sh <version>       e.g. ./release.sh 0.1.0-beta.1
#
# Bumps the version shown in the install docs, commits, tags v<version>, and
# prints the push command. The Release workflow (.github/workflows/release.yml)
# then builds, signs, and publishes every module to Maven Central from the tag —
# no Maven Central or GPG credentials are needed locally.
set -euo pipefail

version="${1:?usage: release.sh <version>   e.g. ./release.sh 0.1.0-beta.1}"

# Keep the copy-paste install snippets pinned to the released version. The
# regexes are digit-anchored, so the ${lathe.version} references are left alone.
sed -i "s#<version>[0-9][0-9A-Za-z.-]*</version>#<version>${version}</version>#" README.md
sed -i \
  -e "s#<version>[0-9][0-9A-Za-z.-]*</version>#<version>${version}</version>#" \
  -e "s#<lathe.version>[0-9][0-9A-Za-z.-]*</lathe.version>#<lathe.version>${version}</lathe.version>#" \
  docs/guide/installation.md

git add README.md docs/guide/installation.md
git commit -m "release: ${version}"
git tag "v${version}"

echo
echo "Tagged v${version}. Push to trigger the release workflow:"
echo "  git push origin HEAD v${version}"
