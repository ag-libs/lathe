#!/usr/bin/env bash
# Cut a Lathe release.
#
# Usage:
#   ./release.sh                 # next patch after the latest release tag (e.g. 0.1.0 -> 0.1.1)
#   ./release.sh <version>       # an explicit version, e.g. 0.2.0 (minor) or 1.0.0 (first stable)
#   ./release.sh --dry-run [...] # show the version bump and revert it; no commit or tag
#
# Versioning: 0.x.y is the beta line ("anything may change"); 1.0.0+ is stable.
# There is no -beta qualifier. `main` stays on 0.1.0-SNAPSHOT — the release
# version lives only in the tag; CI stamps it from the tag with versions:set.
#
# Validates the version and preconditions (on main, clean tree, tag unused),
# bumps ONLY Lathe's version in the install docs, shows the diff to confirm,
# then commits and tags v<version>. On a second confirmation it pushes the commit
# and tag, then runs publish-nvim.sh to publish the Neovim client mirror. Pushing
# the tag triggers .github/workflows/release.yml, which builds, signs, and
# publishes to Maven Central — no Maven Central or GPG credentials are needed
# locally.
set -euo pipefail

die() {
  echo "release: $*" >&2
  exit 1
}

dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
  dry_run=1
  shift
fi

# Run from the repo root so doc paths and git reads work regardless of the cwd.
cd "$(git rev-parse --show-toplevel)" || die "not inside a git repository"
# shellcheck source=dev/lib/release-version.sh
. dev/lib/release-version.sh

if [ "$#" -ge 1 ]; then
  version="$1"
else
  # No version given: derive the next patch from the latest release tag — the
  # typo-proof default for the common "ship the next beta" case.
  git fetch --tags --quiet 2>/dev/null || true
  latest="$(latest_release_version)"
  [ -n "$latest" ] \
    || die "no existing release tag — pass an explicit version for the first release (e.g. 0.1.0)"
  version="$(next_patch "$latest")" \
    || die "latest release '$latest' is not plain x.y.z — pass an explicit next version"
  echo "release: no version given — next patch after ${latest} is ${version}"
fi

# (#2) Validate before touching anything: plain semver only. Rejects a leading
# 'v', a SNAPSHOT, pre-release qualifiers, and junk — so a typo can never become
# a tag or a published artifact.
is_semver "$version" \
  || die "invalid version '$version' — expected x.y.z (e.g. 0.1.0 for beta, 1.0.0 for stable; no leading 'v', no SNAPSHOT)"

tag="v${version}"

# (#3) Preconditions — fail before any edit, commit, or tag exists.
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] || die "must release from 'main' (currently on '$branch')"

git diff --quiet && git diff --cached --quiet \
  || die "working tree has uncommitted changes — commit or stash first"

# Release exactly what origin/main has: local-only commits would ship without CI having run on
# them, and a branch behind origin would leave a release commit and tag that cannot be pushed.
git fetch --quiet origin main || die "cannot fetch origin/main"
read -r behind ahead < <(git rev-list --left-right --count origin/main...HEAD)
[ "$ahead" -eq 0 ] || die "main has ${ahead} commit(s) not on origin — push them and let CI pass first"
[ "$behind" -eq 0 ] || die "main is ${behind} commit(s) behind origin — pull first"

git rev-parse -q --verify "refs/tags/${tag}" >/dev/null \
  && die "tag ${tag} already exists locally"

if git ls-remote --exit-code --tags origin "${tag}" >/dev/null 2>&1; then
  die "tag ${tag} already exists on origin"
fi

# (#4) Bump ONLY Lathe's version in the install docs:
#   - the <version> on the line immediately after a lathe-* <artifactId>,
#   - the <lathe.version> property,
#   - the Gradle plugin's id("io.github.ag-libs.lathe") version "x.y.z", and
#   - Gradle-style io.github.ag-libs:lathe-*:x.y.z coordinates (the init script's classpath).
# The [0-9] anchor leaves ${lathe.version} references alone; the artifactId
# address leaves unrelated dependencies' <version> tags untouched.
docs=(README.md docs/guide/installation.md docs/guide/gradle.md)
for f in "${docs[@]}"; do
  sed -i \
    -e "\#<artifactId>lathe-[a-z-]*</artifactId>#{n;s#<version>[0-9][^<]*</version>#<version>${version}</version>#;}" \
    -e "s#<lathe.version>[0-9][^<]*</lathe.version>#<lathe.version>${version}</lathe.version>#" \
    -e "s#\(id(\"io\.github\.ag-libs\.lathe\") version \"\)[0-9][^\"]*\"#\1${version}\"#" \
    -e "s#\(io\.github\.ag-libs:lathe-[a-z-]*:\)[0-9][^\"]*\"#\1${version}\"#" \
    "$f"
done

# The dogfood pin in the root pom.xml (the lathe-maven-extension version) is intentionally NOT
# bumped here: pointing it at the version being released makes the tagged commit unbuildable, since
# CI loads that extension before the build and the version is not published yet. The pin stays on
# the last published version — the editor tooling runs the prior shipped build, which is fine — and
# is bumped by hand after the release is on Maven Central, if desired. (Dogfooding is off for now:
# the root pom.xml has no such pin until it is turned back on.)
files=("${docs[@]}")

# A no-op means the docs drifted out from under the patterns above — fail loudly rather than commit
# an empty bump and tag a version they never show.
git diff --quiet -- "${files[@]}" \
  && die "no version snippets matched in ${files[*]} — did the install docs change shape?"

echo "Version bump for ${tag}:"
echo
git --no-pager diff -- "${files[@]}"
echo

if [ "$dry_run" -eq 1 ]; then
  git checkout -- "${files[@]}"
  echo "Dry run: reverted the doc edits; no commit or tag made."
  exit 0
fi

printf 'Commit this bump and create tag %s? [y/N] ' "${tag}"
read -r reply || reply=""
case "$reply" in
  y | Y | yes | YES) ;;
  *)
    git checkout -- "${files[@]}"
    die "aborted — reverted the doc edits; no commit or tag made"
    ;;
esac

git add "${files[@]}"
git commit -m "release: ${version}"
git tag "${tag}"

# The Neovim client mirror is published on every release, even when the client did
# not change, so its tags always match the server versions on Maven Central. It
# needs the tag on origin first, so it runs right after the push.
echo
printf 'Tagged %s. Push it and publish the Neovim client mirror now? [y/N] ' "${tag}"
read -r reply || reply=""
case "$reply" in
  y | Y | yes | YES) ;;
  *)
    echo "Not pushed. When ready, push to trigger the release workflow, then publish the mirror:"
    echo "  git push --atomic origin HEAD ${tag}"
    echo "  ./publish-nvim.sh ${version}"
    exit 0
    ;;
esac

git push --atomic origin HEAD "${tag}"
./publish-nvim.sh "${version}"
