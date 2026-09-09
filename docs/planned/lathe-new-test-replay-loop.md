# Lathe — New/Changed-Test Replay Inner Loop

## Status

Planned. Problem confirmed by a probing session against a large private multi-module workspace;
no code yet.

The LSP side of authoring a test is already live end-to-end, but *running* a freshly authored test
is not: replay uses a classpath and a JPMS access set that are frozen at the last capture, so a test
written after that capture cannot be replayed until the user runs a full Maven capture again.

## Problem

A developer's natural test loop is **write test → run it**. In Lathe today the two halves diverge:

- **Analysis is instant.** A brand-new source or test file that was never captured compiles cleanly
  in-server, offers full completion (including cross-module and dependency types), publishes
  as-you-type diagnostics, and is **discovered** as a runnable (method / class / package). The server
  even compiles it to `.lathe/<module>/{classes,test-classes}/…`.
- **Replay is stale.** The run/replay launch reads its classpath and its `--add-opens` set from the
  captured `test-launch.json`, which was written by the last `lathe:sync` / capture. Anything added
  since is invisible to the launch:
  - a **new test class in an already-captured package** fails with `ClassNotFoundException` — its
    `.class` is not on the frozen replay classpath;
  - a **new package** fails with `IllegalAccessException` — the module does not export/open the new
    package to the JUnit runner's unnamed module, because no `--add-opens <module>/<newpkg>=ALL-UNNAMED`
    was captured for a package that did not exist at capture time.

The failures surface as raw JUnit `DiscoveryIssueException` / `IllegalAccessException` stack traces in
the run output, with no hint that the cause is a stale capture.

The only current remedy is a full re-capture, e.g.:

```bash
mvn clean test -Dlathe.capture.only=true -Dmaven.build.cache.enabled=false
```

After that the new package appears in the module's `--add-opens` and the new `.class` files are on the
replay classpath, and both a new-package test and a new-class-in-existing-package test replay green.
So the capture path is correct; the gap is that the inner loop requires a manual, whole-reactor Maven
run to pick up work the server has *already compiled*.

## Evidence (probing session)

Reproduced in a JPMS module (`module-info.java` present) of a large private workspace, genericized here:

- Module `app-common`, module name `com.example.app.common`.
- New class + test in a **new** package `com.example.app.common.scratch`
  (`Widget` / `WidgetTest`): discovery and launch succeed, JUnit reflection fails —
  `module com.example.app.common does not export com.example.app.common.scratch to unnamed module`.
- New test class in an **existing** captured package (`…app.common.util`): launch succeeds, JUnit
  discovery fails — `ClassNotFoundException` for the new class, which is not on the replay classpath.
- After `-Dlathe.capture.only=true`: `test-launch.json` lists the new package in `--add-opens`, the new
  `.class` files are present under `.lathe/<module>/test-classes/…`, and both tests pass on replay.

## Goal

Close the loop so a test authored (or moved to a new package) in the editor can be replayed without a
manual whole-reactor capture — ideally with the same latency as the existing replay for captured tests.

The server already compiles the new file for analysis; the missing piece is making the *launch* aware
of those freshly compiled outputs and of any package added since capture.

## Design options (to evaluate)

1. **Incremental launch augmentation (preferred direction).**
   At launch time, overlay the captured `test-launch.json` with what the server knows now:
   - append the server's own `.lathe/<module>/{classes,test-classes}` output roots to the replay
     classpath if the requested class is not already resolvable from the captured set;
   - derive `--add-opens <module>/<pkg>=ALL-UNNAMED` for any package present in the compiled output
     but absent from the captured access set.
   This keeps the capture as the source of truth for third-party classpath and options, and only
   patches the deltas the server is authoritative about (its own compiled sources and their packages).

2. **Server-triggered scoped capture.**
   When a run targets a class the capture does not cover, run a module-scoped `capture.only` for just
   that module before launching, instead of asking the user to do it. Simpler to reason about, but
   pays a Maven-invocation cost on the first run of any new test.

3. **Detect-and-guide (minimum viable).**
   If neither of the above lands first, detect that the selected test is not in the capture and return
   a clear, actionable message (“test not captured yet — run capture”) instead of a raw JUnit
   stack trace. This is strictly a stopgap; it does not restore the inner loop.

The staleness signal here overlaps with
[Staleness Compile Stamps](lathe-staleness-compile-stamps.md) and
[External-Change Detection](lathe-external-change-detection.md): a per-source compile stamp already
tells the server which sources are newer than the capture, which is exactly the set the launch would
need to overlay.

## Coupled robustness finding — run-command argument handling

Independent of the inner loop, the run path is not defensive about its request shape:

- `LatheWorkspaceService.parseRunTestArgument` does `json.getAsJsonArray("selections").asList()` with
  no null check. A request missing `selections` (e.g. a stale/legacy flat `{selectorKind,
  selectorValue}` shape) throws an uncaught `NullPointerException` from Gson that is surfaced to the
  client as `Internal error` with a full server stack trace.
- Validate the run-test / run-main arguments and return a friendly error (or block with a reason)
  rather than leaking a Gson NPE. Any external caller sending an out-of-date shape should get a clear
  message, not a crash.

## Out of scope (already planned elsewhere)

Separate editing-experience gaps observed in the same session are tracked on their own:

- [Rename](lathe-rename.md) — no `renameProvider` today.
- [Extract Variable](lathe-extract-variable.md) — first extraction refactor.
- [Declaration Name Completion](lathe-declaration-name-completion.md) — covers the override /
  declaration-context completion gap (typing a supertype method in a class body yields a call, not an
  override stub).

Not yet tracked, noted here for triage: no source actions (organize/remove-unused imports, generate
constructor / `equals` / `hashCode` / getters), no inlay hints, and argument-position completion is not
ranked by expected type.
