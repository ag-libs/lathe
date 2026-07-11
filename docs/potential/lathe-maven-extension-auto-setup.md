# Lathe — Maven Extension for Automatic POM Setup

## Problem / motivation

Enabling Lathe today is a manual, three-block hand-edit of the parent `pom.xml`
(see [lathe-design.md §3](../lathe-design.md), "One-time POM configuration"):

1. `maven-compiler-plugin` with a `lathe-compiler` dependency and `<compilerId>lathe</compilerId>`.
2. `lathe-maven-plugin` with `init` + `sync` executions and `<inherited>false</inherited>`.
3. A `lathe-junit` test-scope dependency for run/test capture.

This is error-prone, easy to get subtly wrong (wrong phase, missing `inherited`, compiler-id typo),
and couples every adopting project's source-controlled POM to Lathe.
The goal is that the user installs **one artifact — the Lathe Maven extension — and edits no POM at
all**; everything else is injected into the effective build in memory.

## Sketch

Ship a single Maven **core extension** artifact, registered once in `.mvn/extensions.xml` and
inherited by every module and every build of the reactor, implemented as an
`AbstractMavenLifecycleParticipant`.
Injection is **in-memory only** — in `afterProjectsRead()` the extension mutates the resolved
`MavenProject` model; it never reads or rewrites any `pom.xml` on disk.
It injects everything the manual setup does today, plus the test-capture shim:

- the `maven-compiler-plugin` `lathe-compiler` dependency and `<compilerId>lathe</compilerId>`;
- the `lathe-maven-plugin` `init`/`sync` executions, applied only at the reactor root;
- the `lathe-junit` test-scope capture dependency;
- the **Surefire and Failsafe test-fork shim** — put `lathe-junit` on both the Surefire (unit) and
  Failsafe (integration) test-runtime classpaths and apply any fork configuration needed so the
  in-fork JUnit Platform `LauncherSessionListener` capture activates automatically (see
  [lathe-run-test-debug.md](../planned/lathe-run-test-debug.md)).

The extension resolves the Lathe version from its own coordinates, so every injected plugin and
dependency version stays in lockstep with the extension.

## Driving the workspace refresh

With the shim injected, the workspace refresh rides the ordinary test lifecycle: `mvn test` (unit,
Surefire) or `mvn verify` (integration, Failsafe) both refresh Lathe as a side effect of the fork
launch, rather than depending on the current `process-test-classes`-bound `lathe:sync`.

That leaves the case where a user wants to refresh the workspace / capture the fork template *without
actually running the suite*. Today that is `-DlatheSkipTests=true`
(`LatheFlags.TEST_CAPTURE_SKIP_EXECUTION`), a skip-framed property. The preference is to move to a
**capture-scoped** signal instead, converging with the `-Dlathe.capture.only` property and the
deferred `CaptureOnlyPostDiscoveryFilter` already sketched in
[lathe-run-test-debug.md](../planned/lathe-run-test-debug.md) — one capture-only concept the user
sets, rather than a Lathe-specific "skip tests" flag alongside Maven's own `-DskipTests` /
`-Dmaven.test.skip`.

## Open questions

- **Merge, don't clobber.** Injecting `<compilerId>lathe</compilerId>` and compiler dependencies
  must merge with a project's existing `maven-compiler-plugin` configuration rather than overwrite
  it; likewise executions must not duplicate ones a project already declares.
- **Root-only semantics.** The current setup uses `<inherited>false</inherited>` so `init`/`sync`
  run once from the reactor root; the extension must reproduce that (act only on the top-level
  project), not once per module.
- **Capture-only signal.** Settle the property that means "refresh/capture but do not run tests":
  rename `latheSkipTests` to a capture-scoped name and, ideally, unify it with
  `-Dlathe.capture.only` and the `CaptureOnlyPostDiscoveryFilter` from lathe-run-test-debug, so there
  is a single capture concept rather than a skip flag. Also decide how it should interact with
  Maven's own `-DskipTests` / `-Dmaven.test.skip` (the latter skips test compilation, so no fork and
  no capture).
- **Is one-time registration meaningfully less friction?** Registering the extension in
  `.mvn/extensions.xml` is still one edit — but it is a single non-POM file, inherited by all
  modules, versus per-block parent-POM edits. Worth confirming the win is real for the target
  workflow.
- **Bootstrapping and distribution.** The extension artifact must itself be resolvable before the
  build starts (Maven Central at M3); a first build without network access would not self-configure.
- **Conflicts with the current stance.** lathe-design.md deliberately treats POM validation/injection
  as out of scope for the M3 initial release, surfacing misconfiguration later via the shim/sync/
  server. Automating setup reverses that decision and is a larger architectural move than the other
  potential items — it needs an explicit design before it can be planned.
- **Interaction with other tooling.** Model mutation changes the effective build seen by other
  plugins and by anything that reads the resolved model; reproducibility and third-party plugin
  interaction need checking.

## Milestone candidate

M3 (installation and upgrade readiness) thematically, but likely post-M3 given the scope and the
reversal of the current "POM setup is manual and unvalidated" stance; untriaged until a full design
is written.
