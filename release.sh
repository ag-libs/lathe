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
# then commits and tags v<version>. It does NOT push. Pushing the tag triggers
# .github/workflows/release.yml, which builds, signs, and publishes to Maven
# Central — no Maven Central or GPG credentials are needed locally.
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

if [ "$#" -ge 1 ]; then
  version="$1"
else
  # No version given: derive the next patch from the latest release tag — the
  # typo-proof default for the common "ship the next beta" case.
  git fetch --tags --quiet 2>/dev/null || true
  latest="$(git tag --list 'v*' --sort=-v:refname)"
  latest="${latest%%$'\n'*}" # newest tag (version-sorted) = first line
  [ -n "$latest" ] \
    || die "no existing release tag — pass an explicit version for the first release (e.g. 0.1.0)"
  latest="${latest#v}"
  [[ "$latest" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] \
    || die "latest release '$latest' is not plain x.y.z — pass an explicit next version"
  version="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"
  echo "release: no version given — next patch after ${latest} is ${version}"
fi

# (#2) Validate before touching anything: plain semver only. Rejects a leading
# 'v', a SNAPSHOT, pre-release qualifiers, and junk — so a typo can never become
# a tag or a published artifact.
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || die "invalid version '$version' — expected x.y.z (e.g. 0.1.0 for beta, 1.0.0 for stable; no leading 'v', no SNAPSHOT)"

tag="v${version}"

# (#3) Preconditions — fail before any edit, commit, or tag exists.
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] || die "must release from 'main' (currently on '$branch')"

git diff --quiet && git diff --cached --quiet \
  || die "working tree has uncommitted changes — commit or stash first"

git rev-parse -q --verify "refs/tags/${tag}" >/dev/null \
  && die "tag ${tag} already exists locally"

if git ls-remote --exit-code --tags origin "${tag}" >/dev/null 2>&1; then
  die "tag ${tag} already exists on origin"
fi

# (#4) Bump ONLY Lathe's version:
#   - the <version> on the line immediately after a lathe-* <artifactId>, and
#   - the <lathe.version> property.
# The [0-9] anchor leaves ${lathe.version} references alone; the artifactId
# address leaves unrelated dependencies' <version> tags untouched.
files=(README.md docs/guide/installation.md)
for f in "${files[@]}"; do
  sed -i \
    -e "\#<artifactId>lathe-[a-z-]*</artifactId>#{n;s#<version>[0-9][^<]*</version>#<version>${version}</version>#;}" \
    -e "s#<lathe.version>[0-9][^<]*</lathe.version>#<lathe.version>${version}</lathe.version>#" \
    "$f"
done

# A no-op means the install docs drifted out from under the patterns above — fail
# loudly rather than commit an empty bump and tag a version the docs never show.
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

echo
echo "Tagged ${tag}. Push to trigger the release workflow:"
echo "  git push origin HEAD ${tag}"
