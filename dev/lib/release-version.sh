# Shared release-version helpers, sourced by release.sh and publish-nvim.sh.
# Pure functions: they echo a result or return a status and never exit, so each
# caller keeps its own error prefix and control flow. They read git tags but do
# not fetch — the caller decides when to `git fetch --tags`.
#
# Requires bash: next_patch uses BASH_REMATCH (both callers have a bash shebang).

# Echo all v* tags, newest (highest version) first.
release_tags() {
  git tag --list 'v*' --sort=-v:refname
}

# Echo the newest release version (the latest v* tag without its leading 'v'),
# or nothing when there are no release tags.
latest_release_version() {
  local newest
  newest="$(release_tags | sed -n '1p')"
  printf '%s' "${newest#v}"
}

# Succeed iff $1 is a plain x.y.z semver (no leading 'v', no SNAPSHOT/qualifier).
is_semver() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

# Echo the next patch version after $1 (x.y.z -> x.y.(z+1)); fail if $1 is not x.y.z.
next_patch() {
  [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
  printf '%s.%s.%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "$((BASH_REMATCH[3] + 1))"
}
