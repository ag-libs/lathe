# Releasing Lathe

Lathe publishes all modules (`lathe-core`, `lathe-junit`, `lathe-test-runner`, `lathe-compiler`,
`lathe-server`, `lathe-maven-extension`, `lathe-maven-plugin`) plus the parent POM to Maven Central.

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
   ./release.sh 0.1.0-beta.1
   ```
   This updates the install-snippet version in `README.md` and `docs/guide/installation.md`, commits,
   and creates the tag `v0.1.0-beta.1`.
3. Push the commit and tag:
   ```bash
   git push origin HEAD v0.1.0-beta.1
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

- Beta: `0.1.0-beta.N`. A tag `v0.1.0-beta.N` releases version `0.1.0-beta.N`.
- `main` stays on `0.1.0-SNAPSHOT`; the release version lives only in the tag and the tagged commit's
  docs.
