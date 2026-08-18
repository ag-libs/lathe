# Lathe — Capture Dependency Isolation (TE-1)

This document describes the design that resolves gap
[TE-1](../gaps/gaps.md#te-1--capture-only-dependencies-leak-into-the-recorded-replay-classpath):
*capture-only dependencies leak into the recorded replay classpath*.
It builds on the run/test capture and replay model in
[lathe-run-test-debug.md](lathe-run-test-debug.md).

---

## Goal

Ensure that enabling capture on a project can never add capture-implementation jars to that
project's test classpath, and can never leave those jars in the recorded `test-launch.json`.

The capture artifact must remain fail-open, must preserve the user's own declared dependencies and
classpath order, and must keep working for both classpath and JPMS Surefire/Failsafe launches.

---

## Problem

The Maven extension injects `lathe-junit` at `test` scope into every project.
`lathe-junit` depends on `lathe-core`, which pulls in Gson, ValidCheck, and their closure.
Those jars land on the real test/Surefire classpath and, because the capture listener filters only
its own jar, they survive into the recorded `test-launch.json`.

Two consequences follow:

- **In-fork pollution** — the closure sits on the same classloader as the user's test code, so a
  test could compile and run against `com.google.gson.Gson` (etc.) even though the project never
  declared it.
- **Replay-template leakage** — `ReplayTransform.forTest` preserves the retained entries, so replay
  runs against a dependency environment the project did not declare.

The single-jar filter cannot be widened safely: Maven mediates the project's own copy of a library
and Lathe's copy into a single classpath entry, so removing "Lathe's Gson" would remove the
project's Gson too.
The fix must therefore keep the closure off the classpath in the first place.

---

## Design decision

`lathe-junit` is repackaged as a shaded uber-jar that bundles its compile closure
(`lathe-core`, Gson, ValidCheck) under a relocated package, and publishes a dependency-reduced POM
so consumers receive no transitive capture dependencies.

The capture listener already filters `lathe-junit`'s own jar; with the closure now *inside* that
jar, nothing capture-related remains on the consumer classpath or in the recorded template.
Relocation additionally prevents any clash with a consumer's own copy of those libraries and
prevents test code from binding to them.

This is a **packaging-only** change: `lathe-junit`'s Java, the capture serialization, and the whole
server read path are untouched.
The dependency-free inline approach was considered and rejected in favor of shading
(see [Rejected alternatives](#rejected-alternatives)).

---

## Shade configuration

`maven-shade-plugin` bound to `package` on `lathe-junit`:

- **Relocate the bundled dependencies** (only these — see the scope note below):
  - `com.google.gson` → `io.github.aglibs.lathe.junit.shaded.gson`
  - `io.github.aglibs.lathe.core` → `io.github.aglibs.lathe.junit.shaded.core`
  - `io.github.aglibs.validcheck` → `io.github.aglibs.lathe.junit.shaded.validcheck`
- **`<createDependencyReducedPom>true</createDependencyReducedPom>`** — the bundled compile
  dependencies vanish from the published POM, so a consumer depending on `lathe-junit` receives only
  the shaded jar.
- **`ServicesResourceTransformer`** — merges/rewrites `META-INF/services` entries from the bundled
  jars.
- **Exclude `module-info.class`** from the bundled dependencies (`lathe-core` carries one) to avoid
  a stray module descriptor in the uber-jar.
- Optionally exclude `com.google.errorprone.annotations` (not needed at runtime).

`provided` (`junit-platform-launcher`) and `test` (`junit-jupiter`, `assertj-core`) dependencies are
neither bundled nor propagated.

**Relocation scope.**
Only the bundled dependencies are relocated.
`lathe-junit`'s own `io.github.aglibs.lathe.junit.*` classes stay at their real names so that:

- the `ServiceLoader` service files keep stable, discoverable provider names, and
- the capture filter continues to identify the shaded jar by its listener's `CodeSource`.

Relocating the own classes would buy nothing (the guard-free, `lathe.skip`-based dogfooding story
below removes the only case where their names could collide) and would risk a second, differently
named listener firing if a relocated copy were ever present.

---

## Capture and replay — unchanged

`lathe-junit` keeps its current listener, `LaunchCapture` argument parsing, `TestLaunchData` usage,
and Gson serialization.
At build and test time it links against the real `lathe-core`; shade rewrites those references to
the relocated package in the published jar.
The server continues to read `test-launch.json` via `Json.read` into `TestLaunchData`, and
`ReplayTransform` is unchanged.
Validation stays in `TestLaunchData`'s compact constructor, and `writeAtomically` is unchanged (used
from `lathe-core`, relocated in the shaded jar).

---

## Why the guarantees still hold

- **No leak.** The listener's `CodeSource` is the shaded `lathe-junit` jar; the filter removes
  exactly that jar, taking the relocated closure with it. The recorded classpath contains no Lathe
  entries.
- **No pollution.** Test code sees only the un-relocated `io.github.aglibs.lathe.junit.*` provider
  classes and the relocated `…shaded.*` closure — it cannot bind to `com.google.gson.Gson`, and a
  consumer's own copy of any bundled library is a different, un-relocated package, so version
  mediation is unaffected.
- **No recapture.** `lathe-junit`'s service providers remain absent from the replay classpath
  (replay uses the test-runner classpath, not `lathe-junit`), preserving the no-recapture guarantee
  for other modules.

---

## Dogfooding note

When the extension runs on Lathe itself, `lathe-junit`'s listener fires from its own `target/classes`
during its own test run — shade only affects the published jar, not `target/classes`.
`lathe-junit` therefore sets Surefire
`<systemPropertyVariables><lathe.skip>true</lathe.skip></...>` so its listener no-ops in its own
build: no self-capture, and inert during replay.
Because `lathe.skip` disables the listener at the source, any self-injection is harmless, so **no
extension self-injection guard is added**.
The flag is harmless in normal builds (no `.lathe` root, so capture already no-ops) and load-bearing
only under dogfooding.
Enabling the extension on Lathe's own reactor is a separate follow-up.

---

## Non-goals

- Relocating `lathe-junit`'s own classes (only the bundled closure is relocated).
- Changing capture serialization, the launch schema, or the server read path.
- Enabling the extension on Lathe's own reactor (separate follow-up).

---

## Rejected alternatives

- **Dependency-free inline** — make `lathe-junit` depend on nothing by inlining the constants and
  IO helpers and moving the argument parser to the server behind a raw JSON envelope. Closes the
  leak equally, but duplicates constants (against the "constants only in `LatheLayout`" rule), drops
  `ValidCheck` on the write side, and spreads the change across `lathe-junit` and the server.
  Shading keeps the Java and the read path untouched, at the cost of build machinery — chosen for
  the smaller, contained delta.
- **Un-relocated shade** — bundling without relocation collides with a consumer's own Gson/core (two
  copies of the same FQN) and re-exposes them to test code. Relocation is required.
- **Runtime closure stripping in the filter** — incorrect: Maven mediates the project's own copy of
  a shared library with Lathe's, so stripping by identity can remove a jar the project declared.
- **Extension self-injection guard** — unnecessary given per-module `lathe.skip`, which disables the
  listener at the source regardless of injection.

---

## Change inventory

| Module | Change |
|---|---|
| `lathe-junit` | Add `maven-shade-plugin` (relocate bundled deps, dependency-reduced POM, services transformer, `module-info` exclusion). Add Surefire `lathe.skip` for dogfooding self-capture. No Java change. |
| `lathe-core` | None — source unchanged; relocated only inside `lathe-junit`'s shaded jar. |
| `lathe-server` | None — reads `test-launch.json` into `TestLaunchData` as today. |

The blast radius is confined to `lathe-junit`'s packaging.
Capture logic, the launch schema, the server read path, and the replay transform are untouched.
