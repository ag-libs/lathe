# Releasing Lathe

Lathe publishes every module in the reactor, plus the parent POM, to Maven Central. The release build
deploys whatever the reactor contains, so the set stays correct as modules are added or removed.

Releases are cut from a git tag. CI does the signing and publishing, so **no Maven Central or GPG
credentials are needed locally**.

The standalone Neovim client (`ag-libs/lathe.nvim`) is published as a separate, local step after the
tag is out — see [The Neovim client mirror](#the-neovim-client-mirror).

## One-time setup

- The `io.github.ag-libs` namespace is verified on the [Central Portal](https://central.sonatype.com/).
- Org secrets are set: `MAVEN_USERNAME`, `MAVEN_PASSWORD` (a Central Portal user token),
  `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.
- Optional: give the GitHub `release` environment required reviewers, so a tag push waits for your
  approval before publishing.
- The standalone Neovim client mirror `github.com/ag-libs/lathe.nvim` exists (created empty), and you
  can push to it. Publishing the client uses **your own git credentials over SSH**, not CI — the
  built-in `GITHUB_TOKEN` cannot push across repos, so there is no PAT or deploy key to manage.

## Cutting a release

1. Make sure `main` is green (CI passed for the commit you're releasing).
2. Bump the docs and create the tag:
   ```bash
   ./release.sh 0.1.1          # or just ./release.sh for the next patch
   ```
   This validates the version, checks preconditions (on `main`, clean tree, tag unused), updates the
   install-snippet version in `README.md` and `docs/guide/installation.md`, shows the diff for you to
   confirm, then commits and creates the tag `v0.1.1`. Use `--dry-run` to preview without committing.
3. Push the commit and tag:
   ```bash
   git push origin HEAD v0.1.1
   ```
4. The **Release to Maven Central** workflow runs: it stamps the version, builds, signs, and publishes
   all modules. If the `release` environment requires approval, approve the run.
5. Verify the artifacts at
   [central.sonatype.com/namespace/io.github.ag-libs](https://central.sonatype.com/namespace/io.github.ag-libs).
6. **Publish the Neovim client mirror — only if it changed.** In step 2, `release.sh` prints a reminder
   when the client sources (`lathe-maven-plugin/src/main/neovim`, `dev/nvim-mirror`, or
   `publish-nvim.sh`) changed since the previous tag. If it did, once the tag is on origin:
   ```bash
   ./publish-nvim.sh 0.1.1          # or just ./publish-nvim.sh for the latest tag
   ```
   This snapshots the client **from the release tag**, overlays the standalone-only files
   (`dev/nvim-mirror` + the repo `LICENSE`), stamps the version into `lua/lathe/version.lua`, shows the
   diff, and — on your `[y/N]` confirmation — pushes a `release vX.Y.Z` commit + tag to
   `ag-libs/lathe.nvim`. Preview first with `./publish-nvim.sh --dry-run`. If the client did not change,
   skip this — the mirror keeps its own version cadence.

## How it works

- **No version is committed to the POMs.** The workflow stamps the release version from the tag with
  `versions:set` in the CI checkout only (`Option C` versioning) — avoiding both a `RELEASE_TOKEN` push
  and the `${revision}` partial-build pitfalls.
- The `release` profile attaches source and Javadoc JARs, signs artifacts with GPG, and publishes via
  the `central-publishing-maven-plugin` (`autoPublish=true`).
- The **Maven Central badge** in the README tracks the latest published version automatically; only the
  copy-paste `<version>` snippets are bumped, by `release.sh`, in the tagged commit.

## The Neovim client mirror

The Neovim client lives in the monorepo (`lathe-maven-plugin/src/main/neovim`) and ships two ways:

- **Bundled** in the `lathe-maven-plugin` jar and unpacked to `~/.cache/lathe/current/neovim` by
  `lathe:sync` — the zero-plugin cache path, delivered automatically by the Maven release above.
- **Standalone**, as `github.com/ag-libs/lathe.nvim`, so it is installable by any plugin manager or
  Neovim 0.12+'s `vim.pack`, and discoverable on dotfyle / awesome-neovim.

`ag-libs/lathe.nvim` is a **generated, one-way mirror** — never edit it directly; issues and PRs go to
the monorepo. `publish-nvim.sh` publishes it entirely locally (no CI secret or PAT, mirroring
`release.sh`): it snapshots the client from the release tag, so the mirror is a pure function of the
tag, and records the source commit SHA in the mirror commit message.

Its version cadence is **independent of the server**: the mirror is published only when the client
changed, so its latest tag may lag Maven Central. See
[docs/planned/lathe-standalone-nvim-plugin.md](docs/planned/lathe-standalone-nvim-plugin.md) for the
design and the client↔server protocol.

## Versioning

Lathe follows semantic versioning. `0.x.y` is the **beta** line — per semver §4 the public API is
unstable and may change between any two `0.x.y` releases, so the leading `0` *is* the beta signal and
there is no `-beta` qualifier. `1.0.0` is the first stable release; `1.x.y`+ carry the usual semver
compatibility guarantees.

- A tag `vX.Y.Z` releases version `X.Y.Z` (e.g. `v0.1.1`, `v0.2.0`, `v1.0.0`). Releases start at
  `0.1.1` (the `0.1.0-SNAPSHOT` line's first published version).
- `./release.sh` with no argument cuts the next **patch** (`0.1.1` → `0.1.2`); pass an explicit version
  for a feature/minor bump (`0.2.0`) or the first stable (`1.0.0`).
- `main` stays on `0.1.0-SNAPSHOT`; the release version lives only in the tag. The script never edits
  the POMs — CI stamps the tag's version with `versions:set` in the ephemeral checkout.
