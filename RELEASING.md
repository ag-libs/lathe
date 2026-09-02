# Releasing Lathe

Lathe publishes every module in the reactor, plus the parent POM, to Maven Central. The release build
deploys whatever the reactor contains, so the set stays correct as modules are added or removed.

Releases are cut from a git tag. CI does the signing and publishing, so **no Maven Central or GPG
credentials are needed locally**.

## One-time setup

- The `io.github.ag-libs` namespace is verified on the [Central Portal](https://central.sonatype.com/).
- Org secrets are set: `MAVEN_USERNAME`, `MAVEN_PASSWORD` (a Central Portal user token),
  `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.
- Optional: give the GitHub `release` environment required reviewers, so a tag push waits for your
  approval before publishing.

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

## How it works

- **No version is committed to the POMs.** The workflow stamps the release version from the tag with
  `versions:set` in the CI checkout only (`Option C` versioning) — avoiding both a `RELEASE_TOKEN` push
  and the `${revision}` partial-build pitfalls.
- The `release` profile attaches source and Javadoc JARs, signs artifacts with GPG, and publishes via
  the `central-publishing-maven-plugin` (`autoPublish=true`).
- The **Maven Central badge** in the README tracks the latest published version automatically; only the
  copy-paste `<version>` snippets are bumped, by `release.sh`, in the tagged commit.

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
