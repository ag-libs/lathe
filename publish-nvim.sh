#!/usr/bin/env bash
# Publish the Neovim client to the standalone mirror repo (ag-libs/lathe.nvim).
#
# Usage:
#   ./publish-nvim.sh                 # publish the latest v* release tag
#   ./publish-nvim.sh <version>       # publish an explicit version, e.g. 0.2.0
#   ./publish-nvim.sh --dry-run [...] # build + stamp the snapshot locally and print
#                                     # what would be published; no clone/commit/push
#
# The mirror is a generated, snapshot-per-release copy of
# lathe-maven-plugin/src/main/neovim, overlaid with the standalone-only files in
# dev/nvim-mirror (README, :help) plus the repo LICENSE, and with the release
# version stamped into lua/lathe/version.lua.
#
# Like release.sh, it runs entirely locally with your own git credentials — no CI
# secret or PAT: the built-in GITHUB_TOKEN cannot push across repos, but you can.
# Run it after release.sh has tagged the release and you have pushed the tag.
set -euo pipefail

mirror_url="git@github.com:ag-libs/lathe.nvim.git"
mirror_branch="main"
src="lathe-maven-plugin/src/main/neovim"
templates="dev/nvim-mirror"
version_lua="lua/lathe/version.lua"

die() {
  echo "publish-nvim: $*" >&2
  exit 1
}

dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
  dry_run=1
  shift
fi

# Run from the repo root so all paths resolve regardless of the cwd.
cd "$(git rev-parse --show-toplevel)" || die "not inside a git repository"

if [ "$#" -ge 1 ]; then
  version="$1"
else
  git fetch --tags --quiet 2>/dev/null || true
  latest="$(git tag --list 'v*' --sort=-v:refname)"
  latest="${latest%%$'\n'*}" # newest tag (version-sorted) = first line
  [ -n "$latest" ] || die "no v* release tag found — pass an explicit version (e.g. 0.1.0)"
  version="${latest#v}"
fi

# Plain semver only — same contract as release.sh's tags.
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || die "invalid version '$version' — expected x.y.z (no leading 'v', no SNAPSHOT)"

tag="v${version}"

[ -d "$src" ] || die "client source not found at $src"
[ -d "$templates" ] || die "mirror templates not found at $templates"

# Rebuild the mirror tree at $dest from the client source plus the standalone-only
# overlays, then stamp the release version into version.lua. A snapshot, not a merge:
# whatever is not in the source is removed, so the mirror is a pure function of the tag.
build_snapshot() {
  local dest="$1"
  find "$dest" -mindepth 1 -maxdepth 1 -not -name .git -exec rm -rf {} +
  cp -R "$src"/. "$dest"/
  cp -R "$templates"/. "$dest"/
  cp LICENSE "$dest/LICENSE"
  local vfile="$dest/$version_lua"
  sed -i "s/VERSION = '[^']*'/VERSION = '${version}'/" "$vfile"
  grep -q "VERSION = '${version}'" "$vfile" \
    || die "failed to stamp version into $version_lua — did its shape change?"
}

if [ "$dry_run" -eq 1 ]; then
  dest="$(mktemp -d)"
  trap 'rm -rf "$dest"' EXIT
  build_snapshot "$dest"
  echo "Dry run for ${tag} — would publish these files to ${mirror_url}:"
  (cd "$dest" && find . -type f | sort | sed 's/^\./  /')
  echo
  echo "Stamped ${version_lua}:"
  sed 's/^/  /' "$dest/$version_lua"
  echo
  echo "Dry run: nothing cloned, committed, or pushed."
  exit 0
fi

# Real publish: the snapshot must reflect exactly the released commit.
git diff --quiet && git diff --cached --quiet \
  || die "working tree has uncommitted changes — commit or stash first"
git rev-parse -q --verify "refs/tags/${tag}" >/dev/null \
  || die "tag ${tag} not found — run ./release.sh ${version} and push the tag first"
[ "$(git rev-parse HEAD)" = "$(git rev-parse "${tag}^{commit}")" ] \
  || die "HEAD is not at ${tag} — check out the release commit before publishing the mirror"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
clone="$work/mirror"

git clone --quiet "$mirror_url" "$clone" \
  || die "failed to clone ${mirror_url} — create the (empty) repo first"
# Handle both an established mirror and a first-ever, still-empty repo.
git -C "$clone" checkout -B "$mirror_branch" >/dev/null 2>&1 || true

git -C "$clone" rev-parse -q --verify "refs/tags/${tag}" >/dev/null \
  && die "tag ${tag} already exists on the mirror — already published?"

build_snapshot "$clone"
git -C "$clone" add -A

if git -C "$clone" diff --cached --quiet; then
  die "no changes to publish for ${tag} — the mirror already matches this source"
fi

echo "Publishing ${tag} to ${mirror_url}:"
echo
git -C "$clone" --no-pager diff --cached --stat
echo

printf 'Commit, tag %s, and push to the mirror? [y/N] ' "${tag}"
read -r reply || reply=""
case "$reply" in
  y | Y | yes | YES) ;;
  *) die "aborted — nothing pushed" ;;
esac

git -C "$clone" commit -q -m "release ${tag}"
git -C "$clone" tag "$tag"
git -C "$clone" push --quiet origin "$mirror_branch" "$tag"

echo "Published ${tag} to ${mirror_url} (branch ${mirror_branch} + tag ${tag})."
