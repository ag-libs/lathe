# Lathe — Maven Extension for Automatic POM Setup

Replace the manual three-block parent-`pom.xml` edit with a single Maven **core extension** that
injects all Lathe build configuration into the effective model **in memory**, editing no POM.
The user registers one artifact in `.mvn/extensions.xml`; the compiler shim, the `init`/`sync`
executions, the `lathe-junit` test dependency, and the test-fork capture wiring are all injected at
`afterProjectsRead()`.

This design promotes and supersedes the former `potential/lathe-maven-extension-auto-setup.md` note
(now removed), resolving its open questions per the decisions in §5.

---

## 1. Problem

Enabling Lathe today is a manual, error-prone hand-edit of the parent `pom.xml`
([lathe-design.md §3](../lathe-design.md), "One-time POM configuration"):

1. `maven-compiler-plugin` with a `lathe-compiler` dependency and `<compilerId>lathe</compilerId>`.
2. `lathe-maven-plugin` with `init` + `sync` executions and `<inherited>false</inherited>`.
3. A `lathe-junit` test-scope dependency for run/test capture.

Each block is easy to get subtly wrong — wrong phase, missing `inherited`, `compilerId` typo, a
version drift between the three artifacts — and every mistake couples the adopting project's
source-controlled POM to Lathe.
The goal is that the user installs **one artifact — the Lathe Maven extension — and edits no POM**;
everything else is injected into the effective build in memory, versioned in lockstep with the
extension.

## 2. Precedent

In-memory model mutation from an `AbstractMavenLifecycleParticipant` is the standard way third-party
tooling injects build configuration without touching a project's POM:

- **Takari / `takari-lifecycle`** and the Tycho/OSGi lifecycle participants mutate the resolved
  reactor in `afterProjectsRead()` — add plugins, rewrite executions, extend classpaths — before the
  build executes.
- **`git-commit-id`** and various enforcer-style extensions register as core extensions via
  `.mvn/extensions.xml` and are inherited by every module and every build of the reactor with no
  per-module POM edit.

The extension is loaded into Maven's core classloader before projects are read, so it can see and
mutate every `MavenProject` the session resolved. We follow that shape exactly.

## 3. Design

### 3.1 New module: `lathe-maven-extension`

A new module, built **after `lathe-maven-plugin`** (so the plugin it references is installed first):

```
lathe-core → lathe-compiler → lathe-server → lathe-maven-plugin → lathe-maven-extension
```

- Packaging `jar`, marked as a Maven core extension (Sisu component, `META-INF/sisu` index via the
  `sisu-maven-plugin` or `@Named`/`@Singleton` annotations).
- `groupId` `io.github.ag-libs`, artifact `lathe-maven-extension`, package
  `io.github.aglibs.lathe.maven.extension`.
- Depends on `lathe-core` (for `LatheLayout`/`LatheFlags` constants) and on the Maven core APIs
  (`maven-core`, `maven-model`, `plexus-utils` for `Xpp3Dom`), all `provided` — the extension runs
  inside Maven and must not shade Maven's own classes.

### 3.2 Entry point

```java
@Named("lathe")
@Singleton
public final class LatheLifecycleParticipant extends AbstractMavenLifecycleParticipant {
  @Override public void afterProjectsRead(final MavenSession session) { ... }
}
```

`afterProjectsRead()` is the single hook. It **never reads or writes any `pom.xml` on disk** — it
mutates the already-resolved `MavenProject.getModel()` objects in `session.getProjects()`.

Business logic does **not** live in the participant method (mirrors the Mojo `execute()` rule).
The participant delegates to focused injector classes (§3.4), each unit-testable against a plain
`Model`.

### 3.3 Version self-resolution

The extension resolves its own version from
`META-INF/maven/io.github.ag-libs/lathe-maven-extension/pom.properties` on its own classloader,
yielding a single `latheVersion`. Every injected plugin and dependency (`lathe-compiler`,
`lathe-maven-plugin`, `lathe-junit`) uses that version, so all Lathe artifacts stay in lockstep with
the extension the user registered. No version is hardcoded and none is read from the project.

### 3.4 Injection steps

Constants (plugin/dependency coordinates, `compilerId` value `lathe`, phase names, execution ids)
are defined once in `lathe-core` — extend `LatheLayout` / add a small `LatheCoordinates` holder
rather than hardcoding strings in the extension.

**(a) Compiler shim — every project.**
For each project in `session.getProjects()`:

- Locate `org.apache.maven.plugins:maven-compiler-plugin` in the build; create it if absent.
- Add `io.github.ag-libs:lathe-compiler:${latheVersion}` to its `<dependencies>` if not already
  present.
- Set `<compilerId>lathe</compilerId>` in its `<configuration>`.

Merge policy is **extension wins** (§5): a pre-existing `compilerId` or a stale `lathe-compiler`
version is overwritten so the effective build always matches the extension. Merge is done on the
`Xpp3Dom` configuration tree (replace the `compilerId` node, keep unrelated compiler config such as
`<release>`, `<annotationProcessorPaths>`).

**(b) `init` / `sync` executions — reactor root only.**
On `session.getTopLevelProject()` only, inject `io.github.ag-libs:lathe-maven-plugin:${latheVersion}`
with two executions:

| Execution id | Goal   | Default phase (from the Mojo) |
|--------------|--------|-------------------------------|
| `lathe-init` | `init` | `initialize`                  |
| `lathe-sync` | `sync` | `process-test-classes`        |

Both mojos already declare `defaultPhase` and are `aggregator = true` / root-gated, so injecting them
only on the top-level model reproduces today's `<inherited>false</inherited>` semantics — they run
once from the reactor root, not per module. If the user already declares one of these executions,
extension-wins replaces its configuration but does not create a duplicate execution id.

**(c) `lathe-junit` test dependency — every project.**
Add `io.github.ag-libs:lathe-junit:${latheVersion}` at `test` scope to every project's
`<dependencies>` if absent (extension-wins on version). This puts the capture
`LauncherSessionListener` on both the Surefire (unit) and Failsafe (integration) test-runtime
classpaths through the normal test scope, so capture activates on any `mvn test` / `mvn verify`
fork ([lathe-run-test-debug.md §3](lathe-run-test-debug.md)).

**(d) Surefire / Failsafe — document, do not inject (§5).**
The extension does **not** pin or floor the Surefire/Failsafe version. Capture requires a
JPMS-capable provider (e.g. Surefire ≥ 3.5.x); an unpinned/old provider degrades gracefully (the
listener is `.lathe/`-gated and fails open — no capture, not a broken build). The precondition is
documented in the setup guide and surfaced by the server's readiness messaging rather than forced
onto the effective build, keeping the extension's footprint on third-party plugins minimal.

### 3.5 Driving the workspace refresh

With `lathe-junit` on both test classpaths, the workspace refresh rides the ordinary test lifecycle:
`mvn test` (Surefire) and `mvn verify` (Failsafe) both refresh Lathe as a side effect of the fork
launch, in addition to the existing `process-test-classes`-bound `lathe:sync`.

For "refresh / capture the fork template **without running the suite**", introduce a capture-scoped
signal `-Dlathe.capture.only` (§5). It replaces the current skip-framed
`latheSkipTests` (`LatheFlags.TEST_CAPTURE_SKIP_EXECUTION`) and converges with the
`CaptureOnlyPostDiscoveryFilter` sketched in [lathe-run-test-debug.md](lathe-run-test-debug.md) — one
capture-only concept rather than a Lathe-specific "skip tests" flag beside Maven's own `-DskipTests`
/ `-Dmaven.test.skip`. (`-Dmaven.test.skip` skips test compilation, so there is no fork and no
capture — documented as the one case where capture cannot run.)

## 4. User-facing setup

Before — three POM blocks (§3 of lathe-design.md). After — one file:

```xml
<!-- .mvn/extensions.xml -->
<extensions>
  <extension>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-extension</artifactId>
    <version>0.1.0</version>
  </extension>
</extensions>
```

`mvn io.github.ag-libs:lathe-maven-plugin:VERSION:init` (or any `mvn test` / `package` / `install`,
which now reaches the injected `init` execution) creates `.lathe/`. No parent-POM edit is required at
any point.

## 5. Resolved open questions

| Question | Decision |
|----------|----------|
| Merge vs. clobber when a project already declares Lathe config | **Extension wins** — overwrite `compilerId`, execution config, and dependency versions so the effective build always matches the registered extension version; never create duplicate executions. |
| "Refresh/capture without running tests" signal | **Introduce `-Dlathe.capture.only`**, deprecate/remove `latheSkipTests`; unify with the `CaptureOnlyPostDiscoveryFilter` from lathe-run-test-debug. |
| JPMS-capable Surefire/Failsafe precondition | **Document, do not inject** — no version pin; rely on graceful fail-open and document the requirement. |
| First-pass scope | **This design first** — it reverses lathe-design.md's "POM setup is manual and unvalidated" M3 stance, so it required an explicit design before planning. |

## 6. Implementation plan

1. **`lathe-core`** — add coordinate/`compilerId`/execution-id/phase constants (extend `LatheLayout`
   or add `LatheCoordinates`); add `LatheFlags.CAPTURE_ONLY` (`lathe.capture.only`) and migrate
   `isTestExecutionSkipped()` to read it, removing `TEST_CAPTURE_SKIP_EXECUTION`.
2. **New `lathe-maven-extension` module** — POM (packaging `jar`, `provided` Maven deps, Sisu index),
   `LatheLifecycleParticipant`, and one injector class per §3.4 step (compiler shim, root executions,
   junit dep) operating on a `Model`.
3. **Version resolver** — read own `pom.properties` for `latheVersion`.
4. **Unit tests** — per injector, against hand-built `Model`s: fresh project, project with an
   existing `maven-compiler-plugin` (extension-wins merge), project already declaring the executions
   (no duplicate), non-root module (no executions). AssertJ on the mutated model.
5. **Invoker IT** in `lathe-maven-plugin` (owns invoker ITs) — a project with **only**
   `.mvn/extensions.xml` and no Lathe POM blocks; assert `.lathe/`, `lsp-params-*.json`, and the
   test-launch capture template are all produced by a plain `mvn verify`.
6. **Docs** — update lathe-design.md §3 to present the extension as the primary setup path (manual
   blocks retained as the fallback), and the run-test-debug capture-only section to reference
   `lathe.capture.only`.

## 7. Open items for implementation

- **Sisu registration mechanics** — confirm whether `@Named`/`@Singleton` + the `sisu-maven-plugin`
  index is sufficient for a core extension, or whether a `META-INF/plexus/components.xml` /
  `plexus.xml` entry is also needed for the `AbstractMavenLifecycleParticipant` role.
- **Bootstrapping/distribution** — the extension must be resolvable before the build starts (Maven
  Central at M3); a first offline build cannot self-configure. Document.
- **Third-party model consumers** — mutating the effective model changes what other plugins and
  anything reading the resolved model see; check reproducibility and interaction with common plugins
  (flatten, shade, enforcer).

## 8. Milestone

M3 (installation and upgrade readiness) thematically; sequence relative to Maven Central publishing
(the extension must be published before it is registerable). Post-M3 acceptable given the scope and
the reversal of the current manual-setup stance.
