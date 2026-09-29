# Lathe — OpenJDK Support

## Status

**Proposed.** Lathe today derives its whole model from a **Maven** build (the
[Maven extension](../done/lathe-maven-extension.md) injects the compiler shim and `init`/`sync` goals),
with a **Gradle** front-end designed in parallel ([Gradle Support](lathe-gradle-support.md)). Both
produce the same `.lathe/` contract that the language server, `lathe-test-runner`, the MCP server, and
all editor clients read. This document designs a third front-end for the **OpenJDK build itself** — a
GNU Make build — so a JDK developer editing `java.base` (or any module) gets the same javac-accurate
code intelligence, derived from the real build.

No OpenJDK code exists yet. This is a design, sliced for incremental delivery, and it is deliberately
**narrower** than the Maven/Gradle front-ends: code intelligence first, test execution (jtreg) out of
scope for the MVP.

Findings below are grounded in a read of `openjdk/jdk` at the makefile level
(`make/common/JavaCompilation.gmk`, `make/CompileJavaModules.gmk`, `make/common/Modules.gmk`,
`make/common/MakeBase.gmk`, `make/ide/idea`, `make/ide/eclipse`).

## Goal

Give a developer on the **OpenJDK source tree** the same "the tool understands my build" experience
Lathe gives Maven/Gradle users: javac-accurate diagnostics, completion, and cross-module navigation for
the JDK's own Java modules — including the module-system flags (`--system none`, `--release`,
`--add-exports`, `--patch-module`) and generated sources that the JDK's own `make idea` / `make eclipse`
generators drop. Register once, build once, point the editor at the cache.

## Architectural constraint — `.lathe/` is the seam

Identical to the Gradle design: the server, runner, MCP server, and clients **only read `.lathe/`**
(plus the machine-wide `~/.cache/lathe/`); none of them know Maven, Gradle, or Make exists. OpenJDK
support is therefore **a front-end that emits the same files** — everything downstream is reused. The
relevant subset for the MVP:

| File | Schema (`lathe-core`) | Produced by (OpenJDK) |
|---|---|---|
| `.lathe/<module>/lsp-params-<tree>.json` | `ModuleConfigData` | read from the build's per-module compile descriptors |
| `.lathe/<module>/{classes}` | (mirrored bytecode) | mirror `<jdk-out>/modules/<module>` |
| `.lathe/<module>/lsp-stamps-<tree>.json` | `CompiledStampsData` | source mtimes at capture time |
| `.lathe/workspace.json` | `WorkspaceManifestData` | module map from `FindAllModules` |

`test-launch.json` / `main-launch.json` are **not** produced by the MVP (see
[Run and test — jtreg](#run-and-test--jtreg-deferred)).

## Design principle — observe, don't reconstruct (the build already wrote it down)

Lathe's fidelity comes from riding the **real** build rather than modelling one. OpenJDK makes this
easier than any other front-end, because **the build already persists the fully-resolved javac
invocation to disk for every module.** Tracing `SetupJavaCompilation` (the single macro every module
compile flows through) to `DependOnVariable`:

- `SetupJavaCompilation` accumulates the complete flag set into `$1_FLAGS`
  (`JavaCompilation.gmk:277`): `-g -Xlint:all`, the `TARGET_RELEASE` (`--release` or `-source/-target`),
  `-implicit:none -Xprefer:source`, `-encoding utf-8`, `-cp …`, plus the per-module `JAVAC_FLAGS` —
  which for JDK modules is exactly `--module-source-path … --module-path … --system none` plus any
  per-module snippet additions (`CompileJavaModules.gmk:123-128`).
- It writes that value to **`<jdk-out>/modules/<module>/_the.<module>.vardeps`** via `DependOnVariable`
  (`MakeBase.gmk:308`, `JavaCompilation.gmk:476`) — a durable, make-includable file, present after any
  build (it is the build's own incremental-change mechanism, not a debug artifact).
- It writes the full source list (the `@argfile`, **including generated sources**) to
  **`<jdk-out>/modules/<module>/_the.<module>_batch.filelist`** (`JavaCompilation.gmk:482`).
- The compiled bytecode lands in **`<jdk-out>/modules/<module>/`**, and `ExecuteWithLog` additionally
  records the exact command line.

**Therefore the capture is: read the build's own descriptors.** No compiler replacement, no log
parsing, no tree patching, no layout reconstruction. This is the purest form of "observe, don't
reconstruct" — the build did the work and left the answer on disk. It is a strictly better capture than
the three obvious alternatives (see [Alternatives](#alternatives-considered)).

## Scope

**In:**

- Compiler-argument capture for every JDK **module** (`ModuleConfigData`) by reading the per-module
  build descriptors, plus the bytecode mirror and compile stamps.
- Multiple source roots per module (share + OS overlays + generated sources) with correct override
  precedence.
- Module-system flags (`--system none`, `--release`, `--module-source-path`, `--module-path`,
  `--add-exports`, `--patch-module`) carried through verbatim.
- `workspace.json` for the ~66-module set; registration and `.lathe/` opt-in gating.
- The in-process add/edit/delete refresh model, reused from
  [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md).

**Out (this design):**

- **Run / test / debug.** JDK tests use **jtreg**, not JUnit Platform / Surefire, so the `lathe-junit`
  in-fork capture does not apply. A jtreg launch-capture is a separate, later design.
- HotSpot / native (C/C++) code — Lathe is Java-only.
- The langtools bootstrap (interim compiler) as a *build* concern — transparent to capture.
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema.

## What is reused vs new

**Reused unchanged:** `lathe-core` (schema, `LatheLayout`, `LatheFlags`, `LatheWorkspace`, `LatheLock`,
type-index), `lathe-server`, `lathe-mcp-server`, all editor clients, and the in-process workspace-sync
reaction in the server.

**Not needed here:** `lathe-junit` / `lathe-test-runner` (no MVP test capture); **plexus-java
`LocationManager`** (the module/classpath split is explicit in the captured flags, so nothing to infer);
dependency `-sources` resolution (the JDK source *is* the code being edited — navigation targets are all
in-tree).

**New:** a small `lathe-openjdk` module (depends on `lathe-core`) — a JVM tool that reads a JDK build
output directory's per-module descriptors and emits `.lathe/`. It is a normal Maven module (no Gradle
distribution artifacts needed, unlike the Gradle plugin), the direct analog of `lathe-maven-plugin`.

## OpenJDK source structure (primer)

The JDK's Java layout is unusual enough to state explicitly, because it drives the capture shape.

- **Flat module list.** `src/` holds ~66 Java modules as siblings — 22 `java.*` (platform) and 44
  `jdk.*` (tooling/JDK-specific) — plus `hotspot` (C++), `demo`, `sample`. No reactor/parent hierarchy;
  the module is the unit, mapping 1:1 onto `.lathe/<module>/`.
- **Multi-root modules with override precedence.** A module's source roots come from `SRC_SUBDIRS`
  (`Modules.gmk:81`), in precedence order: `<os>/classes`, `<os-type>/classes`, `share/classes`.
  `share/classes` is universal (66/66); OS overlays are sparse (`windows/classes` 15, `unix/classes`
  10, …). **First-found wins** — an OS-specific file overrides the shared one of the same name
  (`JavaCompilation.gmk:367`). So one module routinely has 3–4 source roots with ordering that must be
  preserved.
- **`module-info.java` lives only in `share/classes`** — one per module.
- **Generated sources are first-class and large.** `GENERATED_SRC_DIRS` (`Modules.gmk:67`) folds
  `<build>/support/gensrc` into every module's roots; much of `java.base` (charset coders, `Buffer` /
  `VarHandle` families, `CharacterData`, module loader maps, …) is generated. Editing without gensrc on
  the source path yields a flood of false errors.
- **Module-graph compilation, not classpath.** Each module compiles with `--module-source-path` +
  `--module-path` + `--system none` + `--release` (`CompileJavaModules.gmk:105-128`); output to
  `<build>/jdk/modules/<module>/`.
- **Tests live in a separate tree** (`test/jdk`, `test/langtools`, …) under jtreg — not a Maven
  `src/test/java` layout, not JUnit/Surefire.

## Capture — compiler arguments & the bytecode mirror

For each module, `lathe-openjdk` reads the two durable descriptors the build already wrote and produces
`ModuleConfigData`:

| `ModuleConfigData` | Source in the OpenJDK build |
|---|---|
| `sourceRoots` (ordered) | `FindModuleSrcDirs` result / the roots implied by the filelist — share + OS overlays + gensrc, precedence preserved |
| `classpath` | the `-cp` entry in `_the.<module>.vardeps` (usually empty for modules) |
| `modulepath` / module flags | `--module-source-path`, `--module-path`, `--system none` from the vardeps — carried verbatim, **not inferred** |
| `compilerArgs` (incl. `--add-exports`, `--patch-module`, `-XD…`, `-Xlint`) | the residual flags in the vardeps |
| `release` / `encoding` | `--release`/`-source/-target` and `-encoding utf-8` from the vardeps |
| `outputDir` | `<jdk-out>/modules/<module>` |
| (source files) | `_the.<module>_batch.filelist` — the exact `@argfile`, gensrc included |

The **bytecode mirror** copies `<jdk-out>/modules/<module>/` into `.lathe/<module>/classes`, and stamps
record source mtimes at capture time — the same shape as `LatheCompiler.syncOutput` and the
[compile-stamps design](../done/lathe-completion-expectations.md).

The one genuinely OpenJDK-specific correctness point is **source-root ordering**: the editor must
resolve the same OS-overlay file `javac` did, so `sourceRoots` preserves the `<os>` → `<os-type>` →
`share` precedence.

## JPMS (Java modules)

The JDK is the hardest possible JPMS case — every module is real, `java.base` is patched during
bootstrap, and nothing is a plain classpath library — yet capture makes it **easy**, because the module
directives are not inferred, they are read:

- **Module path / `--system none` / `--add-exports` / `--patch-module`** are all literally in
  `_the.<module>.vardeps`. Carried through verbatim; no plexus-java placement step, unlike Gradle.
- **`module-info.java`** is the single share-tree marker per module.
- No whitebox-test quadrant in the MVP (tests are out of scope), so the subtle case that dominates the
  Gradle design does not arise here.

## Generated sources

Solved for free by the descriptor capture: `_the.<module>_batch.filelist` already lists the gensrc'd
files, and `FindModuleSrcDirs` already includes `<build>/support/gensrc/<module>` as a source root. The
only requirement is that the build has run the `gensrc` phase before capture — which any normal `make`
does. Navigation into a generated `CharacterData` or `VarHandle` resolves to the real generated file.

## Run and test — jtreg (deferred)

JDK tests are driven by [jtreg](https://openjdk.org/jtreg/) (`@test`/`@run` tags), a bespoke harness in
the separate `test/` tree. Lathe's test-launch capture ([Run, Test, Debug](../done/lathe-run-test-debug.md))
depends on the `lathe-junit` `LauncherSessionListener` firing inside a **JUnit Platform** fork
(Surefire, or Gradle's worker); jtreg is neither, so that mechanism does not port. Run/test/debug is
therefore **out of the MVP**; developers keep using `make test`/jtreg as today. A jtreg-shaped launch
capture (observing the JVM jtreg forks per `@run`) is a candidate follow-up, not part of this design.

## `workspace.json`

- **Module map** — `FindAllModules` (the module-info scan, `Modules.gmk:124`) enumerates the ~66
  modules; each gets a `.lathe/<module>/` entry.
- **`pomPaths`** has no direct analog; the field carries the module source dirs / makefile snippet
  paths as the "project descriptor" locations.
- **`resourceRoots`** — the non-`classes` module dirs that ship as resources (`conf/`, `data/`), plus
  the module output.
- **`runnerClasspath`** — omitted (no MVP runner).

## Delivery & gating

Registration mirrors the JDK's own IDE generators (`make idea`, `make eclipse`) — personal, no edits to
the tracked tree:

- **Make target** (primary) — a small include that adds a `lathe` target, run after a build:
  `make jdk lathe` or `make <module>-java-only lathe`. This is the analog of `make idea` and fits the
  build's existing pattern (a `MakeFileStart.gmk`/`Modules.gmk`-based target that reads module vars and
  writes a file).
- **Standalone CLI** (underlying implementation) — `lathe-openjdk sync --build-dir
  build/<conf>` — for environments where adding a make target is undesirable; the make target is a thin
  wrapper over it.

**Gating** reuses `LatheFlags`: opt in with `mkdir .lathe`; disable with a property / `CI` env, same
precedence as Maven/Gradle. `.lathe/` is git-ignored (`.git/info/exclude` or `.gitignore`).

## End-user setup (developer workflow)

```bash
git clone https://github.com/openjdk/jdk && cd jdk
bash configure --with-boot-jdk=/path/to/jdk-N     # normal JDK setup, unchanged
echo ".lathe/" >> .git/info/exclude
mkdir .lathe                                       # opt-in gate

make jdk lathe                                     # build once; capture reads the descriptors
```

Then open the repo in any Lathe editor client (Neovim, VS Code, Emacs, IntelliJ-via-LSP — all reused
unchanged). The developer immediately gets javac-accurate:

- **diagnostics that match the build**, with `--add-exports` / `--patch-module` / `--system none` /
  `--release` honoured — no false errors of the kind `make idea`/`make eclipse` produce by dropping the
  module flags;
- **completion / hover / signature help** over the real module graph;
- **go-to-definition / find-references across all ~66 modules** (e.g. `java.desktop` → `java.base`),
  resolving to real source, not stubs;
- **navigation into generated sources** and correct resolution of **OS-overlay** files.

| `make idea` / `make eclipse` (status quo) | Lathe |
|---|---|
| reconstruct module source roots from layout; drop module-info (Eclipse); no javac flags | replay the build's *actual* javac configuration per module |
| editor disagrees with the build on module boundaries | editor and build agree by construction |

## Editing loop & refresh model

File-level edits are handled **in-process** by the server (reusing
[In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md)); a `make` re-run is needed only
when the build *configuration* changes:

| Action | Re-run `make`? | Why |
|---|---|---|
| Add / edit / delete `.java` in an existing module root | **No** | the root and flags are already captured; javac resolves the new file on the source path |
| Add a new package under an existing root | **No** | same |
| Edit `module-info` (`requires` / `exports` / `opens`) | **Yes** | changes the module graph and the `--add-*` flags |
| Add a whole new module | **Yes** | `workspace.json` must learn the module |
| Change that regenerates gensrc | **Yes** | the generated source only appears after the `gensrc` phase |

The mental model is identical to Maven/Gradle Lathe — *file edits are in-process; only build-shape
changes need a sync* — with one OpenJDK twist: `module-info` edits carry more weight here because they
drive the module flags. Refresh is scoped and fast via the build's own incrementality:
`make <module>-java-only lathe` runs in milliseconds when nothing else changed.

## Testing (end-to-end)

The obstacle is that a realistic fixture requires a **built JDK**, which is expensive. Two layers:

1. **Descriptor-fixture unit tests.** Check in captured real `_the.<module>.vardeps` /
   `_the.<module>_batch.filelist` samples (small, path-normalized) and assert `lathe-openjdk` produces
   the correct `ModuleConfigData` — including multi-root ordering, `--system none`, `--add-exports`, and
   gensrc roots. No JDK build needed; runs in CI.
2. **Cross-tool contract test (gated).** On a machine with a built JDK, run capture against
   `build/<conf>`, then boot `lathe-server` against the produced `.lathe/` and assert javac-accurate
   diagnostics on a known module (e.g. edit `java.base`, expect zero false errors; resolve a symbol into
   another module and into gensrc). Gated behind a profile because of the build cost.

**Fixture focus:** a multi-root module (share + OS overlay), a heavily-generated module (`java.base`),
and a cross-module navigation case — so the structural claims are all under test. Add-file-without-
rebuild is an explicit case (proves the in-process refresh model).

## Slicing

1. **Descriptor capture (`ModuleConfigData` + mirror + stamps)** — the `lathe-openjdk` reader over one
   module's `_the.*.vardeps` / `_the.*_batch.filelist`, with multi-root ordering. Delivers code
   intelligence for a single module.
2. **Full module set + `workspace.json`** — enumerate via `FindAllModules`; capture all ~66 modules;
   cross-module navigation. Completes the editor experience.
3. **Registration + gating** — the `make lathe` target / CLI, `.lathe/` opt-in, git-ignore.
4. **In-process refresh wiring + tests** — confirm add/edit/delete without rebuild; the fixture matrix.
5. **(Later, separate design) jtreg launch capture** — run/test/debug.

## Alternatives considered

- **Parse the command-line log (`LOG=cmdlines`).** The quick prototype, but fragile: the javac server
  logs a client invocation (needs `--disable-javac-server`), and large compilations pass sources via
  `@argfiles` that must be read and copied before overwrite. Superseded by reading the descriptors,
  which are the already-expanded, durable form of the same data.
- **Patch / wrap `SetupJavaCompilation`.** The faithful analog of the Maven compiler shim, but a patch
  to their tree — an upstream-maintenance burden. Unnecessary, because the macro *already* persists the
  descriptors we need.
- **Reconstruct from `src/` layout.** This is exactly what `make idea` / `make eclipse` do, and their
  known lossiness (no `--add-exports`/`--patch-module`/`--release`; Eclipse excludes `module-info.java`)
  is the drift Lathe exists to avoid. Rejected as a real feature — the same reasoning the Gradle design
  uses to reject Tooling-API model reconstruction.
- **Upstream `make java-compile-commands`** (a Java analog of the existing native `make
  compile-commands`, editor-neutral, build-team-maintained). The ideal long-term interface, but not
  Lathe's decision and only winnable with a working prototype. A candidate follow-up once descriptor
  capture proves the output shape on `build-dev@openjdk.org`.

## Open decisions

1. **Registration surface** — a bundled `make lathe` include vs a standalone `lathe-openjdk` CLI the
   developer points at `build/<conf>`. Leaning: CLI as the implementation, make target as the ergonomic
   wrapper.
2. **Descriptor parse stability** — the exact on-disk format of `_the.<module>.vardeps` (a make
   `X_old := …` line) is confirmed from the makefiles but not yet from a live build; lock it against a
   real `make java.base` before finalizing the reader.
3. **Minimum JDK / build version** — confirm the descriptor mechanism and `SRC_SUBDIRS`/`FindAllModules`
   contracts across the JDK versions Lathe intends to support (they are stable but version-check).
4. **Multi-root override representation** — carry ordered `sourceRoots` and rely on the server's
   first-found resolution, vs an explicit per-file override map. Ordered roots is the KISS default.
5. **jtreg capture** — whether a future run/test slice observes the JVM jtreg forks per `@run`, or is
   left to the existing jtreg tooling. Out of scope now; noted so it is not assumed solved.

## Non-goals (this design)

- jtreg run/test/debug in the MVP; HotSpot / native code; the langtools bootstrap as a build concern.
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema — the whole point is
  that they are untouched.
