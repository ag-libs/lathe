#!/usr/bin/env bash
# Publish the Neovim client to the standalone mirror repo (ag-libs/lathe.nvim).
#
# Usage:
#   ./publish-nvim.sh                  # publish the latest v* release tag
#   ./publish-nvim.sh <version>        # publish an explicit version, e.g. 0.2.0
#   ./publish-nvim.sh --dry-run [ver]  # build + stamp the snapshot and print what would
#                                      # be published; no clone/commit/push. With a tag
#                                      # that exists it previews the tagged tree; without
#                                      # one (a pre-release version) it previews the
#                                      # current working tree.
#
# The mirror is a generated, snapshot-per-release copy of
# lathe-maven-plugin/src/main/neovim, overlaid with the standalone-only files in
# dev/nvim-mirror (README, :help) plus the repo LICENSE, and with the release
# version stamped into lua/lathe/version.lua. For a real publish the snapshot is
# taken from a fresh checkout of the release tag, so it is a pure function of the
# tag — no working-tree or branch state can leak in, and it can be run from any
# branch or worktree.
#
# Like release.sh, it runs entirely locally with your own git credentials — no CI
# secret or PAT: the built-in GITHUB_TOKEN cannot push across repos, but you can.
# Run it after release.sh has tagged the release AND you have pushed the tag.
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
# shellcheck source=dev/lib/release-version.sh
. dev/lib/release-version.sh

# Refresh tags so version derivation and the tag checkout see what is on origin.
git fetch --tags --quiet origin 2>/dev/null || true

if [ "$#" -ge 1 ]; then
  version="$1"
else
  latest="$(latest_release_version)"
  [ -n "$latest" ] || die "no v* release tag found — pass an explicit version (e.g. 0.1.0)"
  if [ "$dry_run" -eq 1 ]; then
    # No version given for a preview: show the upcoming release (next patch), built
    # from the working tree — not a re-publish of the latest, already-shipped tag.
    version="$(next_patch "$latest")" \
      || die "latest tag v$latest is not x.y.z — pass an explicit version to preview"
  else
    version="$latest" # a real publish targets the latest release tag
  fi
fi

# Plain semver only — same contract as release.sh's tags.
is_semver "$version" \
  || die "invalid version '$version' — expected x.y.z (no leading 'v', no SNAPSHOT)"

tag="v${version}"

work="$(mktemp -d)"
wt=""
cleanup() {
  [ -n "$wt" ] && git worktree remove --force "$wt" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# A real publish must reflect exactly the released commit and only run once the
# monorepo release is actually out — so require the tag on origin, and take the
# snapshot from a fresh checkout of it. A dry run of an already-tagged version
# previews the same; a dry run of a not-yet-tagged version previews the working
# tree, so you can inspect a release before cutting it.
if [ "$dry_run" -eq 0 ]; then
  git ls-remote --exit-code --tags origin "refs/tags/${tag}" >/dev/null 2>&1 \
    || die "tag ${tag} is not on origin — push the monorepo release first: git push origin ${tag}"
fi

if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null 2>&1; then
  wt="$work/tree"
  git worktree add --detach --quiet "$wt" "$tag" || die "failed to check out ${tag}"
  source_dir="$wt"
  source_sha="$(git -C "$wt" rev-parse HEAD)"
else
  echo "note: tag ${tag} not found — previewing from the working tree"
  source_dir="."
  source_sha="$(git rev-parse HEAD)"
fi

commit_subject="release ${tag}"
commit_body="Generated from lathe@${source_sha}."

# Rebuild the mirror tree at $dest from the (tagged) client source plus the
# standalone-only overlays, then stamp the release version into version.lua. A
# snapshot, not a merge: whatever is not in the source is removed.
build_snapshot() {
  local dest="$1"
  [ -d "$source_dir/$src" ] || die "$src not found at ${tag} — does this tag predate the client tooling?"
  [ -d "$source_dir/$templates" ] || die "$templates not found at ${tag}"
  [ -f "$source_dir/LICENSE" ] || die "LICENSE not found at ${tag}"
  find "$dest" -mindepth 1 -maxdepth 1 -not -name .git -exec rm -rf {} +
  cp -R "$source_dir/$src"/. "$dest"/
  cp -R "$source_dir/$templates"/. "$dest"/
  cp "$source_dir/LICENSE" "$dest/LICENSE"
  local vfile="$dest/$version_lua"
  sed -i "s/VERSION = '[^']*'/VERSION = '${version}'/" "$vfile"
  grep -q "VERSION = '${version}'" "$vfile" \
    || die "failed to stamp version into $version_lua — did its shape change?"
}

if [ "$dry_run" -eq 1 ]; then
  dest="$work/snapshot"
  mkdir -p "$dest"
  build_snapshot "$dest"
  echo "Dry run for ${tag} — would publish these files to ${mirror_url}:"
  (cd "$dest" && find . -type f | sort | sed 's/^\./  /')
  echo
  echo "Stamped ${version_lua}:"
  sed 's/^/  /' "$dest/$version_lua"
  echo
  echo "Would commit on branch ${mirror_branch}:"
  echo "  ${commit_subject}"
  echo "  ${commit_body}"
  echo "Would tag: ${tag}"
  echo
  echo "Dry run: nothing cloned, committed, or pushed."
  exit 0
fi

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
echo "Commit: ${commit_subject} — ${commit_body}"
echo

printf 'Commit, tag %s, and push to the mirror? [y/N] ' "${tag}"
read -r reply || reply=""
case "$reply" in
  y | Y | yes | YES) ;;
  *) die "aborted — nothing pushed" ;;
esac

git -C "$clone" commit -q -m "${commit_subject}" -m "${commit_body}"
git -C "$clone" tag "$tag"
git -C "$clone" push --quiet origin "$mirror_branch" "$tag"

echo "Published ${tag} to ${mirror_url} (branch ${mirror_branch} + tag ${tag})."
