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
the JDK's own Java modules — including the module-system flags (`--system none`, `--add-exports`,
`--patch-module`, and the `-source/-target` level) and generated sources that the JDK's own
`make idea` / `make eclipse` generators drop. Register once, build once, point the editor at the cache.

## Architectural constraint — `.lathe/` is the seam

Identical to the Gradle design: the server, runner, MCP server, and clients **only read `.lathe/`**
(plus the machine-wide `~/.cache/lathe/`); none of them know Maven, Gradle, or Make exists. OpenJDK
support is therefore **a front-end that emits the same files** — everything downstream is reused. The
relevant subset for the MVP:

| File | Schema (`lathe-core`) | Produced by (OpenJDK) |
|---|---|---|
| `.lathe/<module>/lsp-params-<tree>.json` | `ModuleConfigData` | read from the build's per-module compile descriptors |
| `.lathe/<module>/classes` | (mirrored bytecode) | mirror `<jdk-out>/modules/<module>` |
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
- `ExecuteWithLog` writes the **exact javac command line** to
  **`<jdk-out>/modules/<module>/_the.<module>_batch.cmdline`** *unconditionally, before each compile*
  (`MakeBase.gmk:370`) — not just on failure. This is the cleanest capture target (a real command that
  references the `@filelist`), and it is what the reader parses; the `.vardeps` is a fallback/cross-check.
- The compiled bytecode lands in **`<jdk-out>/modules/<module>/`** (intermixed with the `_the.*` marker
  files).

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
- Module-system flags (`--system none`, `-source/-target`, `--module-source-path`, `--module-path`,
  `--add-exports`, `--patch-module`) carried through verbatim.
- `workspace.json` for the ~66-module set; registration and `.lathe/` opt-in gating.
- The in-process add/edit/delete refresh model, reused from
  [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md).
- A **runtime-JDK version gate**: run the server on a javac ≥ the mainline feature version, else fail
  loudly (see [javac fidelity](#javac-fidelity--the-servers-runtime-jdk-the-real-hard-part)).

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

**New:** a single `lathe-openjdk-maven-plugin` module (depends on `lathe-core`). It is a Maven **plugin**
with one goal, `sync`, declared `@Mojo(requiresProject = false)` so it runs in the JDK checkout (which is
not a Maven project) with no pom. The `SyncMojo` is a thin adapter; the descriptor reader and the
`.lathe/` writer live in ordinary, unit-testable classes in the same module (no business logic in
`execute()`, per house rules). Maven itself is the bootstrap — see [Delivery & gating](#delivery--gating).
No standalone CLI and no shaded jar: Maven resolves the plugin and its transitive deps (incl.
`lathe-core`) from Central, honouring the user's `settings.xml` (mirrors/proxies).

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
  `--module-path` + `--system none` + `-source/-target N` (`TARGET_RELEASE_NEWJDK`,
  `CompileJavaModules.gmk:105-128` / `JavaCompilation.gmk:48`) — **not `--release`** (that is only the
  JDK 8 legacy path); output to `<build>/jdk/modules/<module>/`.
- **Tests live in a separate tree** (`test/jdk`, `test/langtools`, …) under jtreg — not a Maven
  `src/test/java` layout, not JUnit/Surefire.

## Capture — compiler arguments & the bytecode mirror

Capture per module is **three reads and one copy**: parse `.cmdline` for the flags, read `.filelist`
for the sources, take the module dir (minus `_the.*`) as the bytecode mirror. The file inventory under
`<jdk-out>/modules/<module>/`:

| File | Role | Use |
|---|---|---|
| `_the.<module>_batch.cmdline` | the real javac command line (always written, `MakeBase.gmk:370`) | **primary — parse flags** |
| `_the.<module>_batch.filelist` | the `@argfile`: every source file, gensrc included | source list → roots/stamps |
| `_the.<module>.vardeps` | change-detection fingerprint (`<var>_old := <cmd> <flags> <meta>`) | fallback / cross-check |
| `_the.<module>_batch.log`, `_the.<module>_batch[.modfiles[.fixed]]`, `_the.<module>.config_vardeps`, `_the.<module>-javacserver.conf`, `_the.<module>_pubapi`, `_the.<module>_internalapi` | build plumbing (logs, incremental, javac-server, API digest) | ignore |
| `<pkg>/**/*.class`, `module-info.class` | compiled bytecode | **mirror** (everything except `_the.*`) |

**Parsing `.cmdline` → `ModuleConfigData`.** The command is
`<launcher> <FLAGS> <API_DIGEST_FLAGS> -XDmodifiedInputs=<f> -d <BIN> <HEADERS_ARG> @<FILELIST>`, so the
reader:

1. **strips the launcher prefix** — everything up to and including `…com.sun.tools.javac.Main` (interim
   compiler) or `javacserver.Main --conf=<f>` (javac-server mode); the rest are javac args;
2. **drops build-mechanics flags** — `-XDmodifiedInputs=…`, the API-digest plugin (`-Xplugin:"depend …"`,
   `-XDinternalAPIPath`, `-XDLOG_LEVEL`), `-d <dir>` (we set our own `outputDir`), `-h <dir>` (native
   headers), and `@<filelist>` (read separately);
3. **maps the rest** → `encoding`, `-cp` (usually empty), and the module directives
   `--module-source-path` / `--module-path` / `--system none` / `--add-exports` / `--add-reads` /
   `--patch-module` **carried verbatim — never inferred** (contrast the Gradle front-end, which must run a
   plexus-java split); the remaining `-g`/`-Xlint…`/`-implicit:none`/`-XDstringConcat=inline` pass through
   as `compilerArgs`.

Whitespace tokenization is safe: sources are behind `@filelist`, and path-valued flags are single
`PathList` tokens (no spaces).

**Source level must stay `-source/-target`, not `--release` — they conflict with `--system none`.** The
JDK compiles main modules with `-source N -target N --system none` (not `--release`). This matters because
`lathe-server`'s `ModuleSourceCompiler` emits `--release` from `ModuleConfigData.release`, and javac
**rejects `--release` together with `--system`** (*"option --system cannot be used together with
--release"*). So the OpenJDK reader **leaves `ModuleConfigData.release` empty** and carries `-source/-target
N` inside `compilerArgs` alongside `--system none` — otherwise every module compile in the server would
fail. (Verify against a live build; noted in open decisions.)

**`sourceRoots` (ordered).** Preferred: the `make lathe` target dumps each module's `FindModuleSrcDirs`
result (precedence-ordered), exactly as `make idea` dumps `MODULE_ROOTS` — authoritative, and these *are*
top-level make functions so no macro patching is needed. Fallback (standalone): derive roots from the
`.filelist` by matching each file against the known boundaries (`…/src/<mod>/{os,os-type,share}/classes/`,
`…/support/gensrc/<mod>/`) and ordering by the `SRC_SUBDIR` precedence (`Modules.gmk:81`).

**Why parse `.cmdline` rather than have make dump the flags?** The per-module `$1_FLAGS` is internal to
`SetupJavaCompilation`'s eval — not a top-level make variable — so make cannot echo it without *patching*
the macro (rejected). `.cmdline` is how those flags escape to disk. So the clean division is: **make
dumps the top-level facts it does expose** (module list, `FindModuleSrcDirs`, `SUPPORT_OUTPUTDIR`, boot
JDK — `env.cfg`-style, no JSON), and **the plugin parses `.cmdline` for the flags** and writes
schema-correct `.lathe/` via `lathe-core`.

The **bytecode mirror** copies `<jdk-out>/modules/<module>/` into `.lathe/<module>/classes`, **excluding
the `_the.*` markers**; stamps record source mtimes at capture time — the same shape as
`LatheCompiler.syncOutput` and the [compile-stamps design](../done/lathe-completion-expectations.md).

Two policy decisions: **keep `-Xlint…` but drop `-Werror`** — the editor should show the build's warnings
as warnings, not fail-fatal errors; and **`.cmdline` is primary, `.vardeps` the fallback** if a module
dir lacks the `.cmdline`.

The one genuinely OpenJDK-specific correctness point is **source-root ordering**: the editor must
resolve the same OS-overlay file `javac` did, so `sourceRoots` preserves the `<os>` → `<os-type>` →
`share` precedence.

## JPMS (Java modules)

The JDK is the hardest possible JPMS case — every module is real, `java.base` is patched during
bootstrap, and nothing is a plain classpath library — yet capture makes it **easy**, because the module
directives are not inferred, they are read:

- **Module path / `--system none` / `--add-exports` / `--patch-module`** are all literally in the captured
  command (`_the.<module>_batch.cmdline`). Carried through verbatim; no plexus-java placement step, unlike
  Gradle.
- **`module-info.java`** is the single share-tree marker per module.
- No whitebox-test quadrant in the MVP (tests are out of scope), so the subtle case that dominates the
  Gradle design does not arise here.

## Generated sources

Solved for free by the descriptor capture: `_the.<module>_batch.filelist` already lists the gensrc'd
files, and `FindModuleSrcDirs` already includes `<build>/support/gensrc/<module>` as a source root. The
only requirement is that the build has run the `gensrc` phase before capture — which any normal `make`
does. Navigation into a generated `CharacterData` or `VarHandle` resolves to the real generated file.

## javac fidelity — the server's runtime JDK (the real hard part)

Capturing the flags is the easy, stable part. The genuine risk is that **mainline JDK source uses a
language level newer than most installed JDKs**, and Lathe analyzes with the javac of *its own runtime*:
`JavaSourceCompiler.COMPILER = ToolProvider.getSystemJavaCompiler()` (an in-process `JavacTask`). So the
analysis language level **equals the JDK the server is launched on** — nothing in the captured flags can
change that.

**The failure is binary, not gradual.** Mainline is JDK 28 (`version-numbers.conf`) and modules compile
`-source 28 -target 28` (`JavaCompilation.gmk:48`). Passing `-source 28` to a javac older than 28 fails
outright (*"invalid source release: 28"*) — **every** module errors, not just files using new syntax.
The whole feature hinges on one variable: which JDK runs the server.

**Half the problem is already handled.** Because the build uses `--system none --module-source-path …`,
javac reads platform types (`java.lang.String`, …) **from the source tree, not its runtime**. So the
runtime's *class library* being old doesn't matter — only the *compiler binary's language level* does.
That reduces the risk to one dimension: the server's javac version.

**That dimension is a launcher decision Lathe already owns.** `getSystemJavaCompiler()` binds to the
launch JVM, and the cache launcher chooses it. The rule: **launch the server on a javac ≥ the mainline
feature version.** Three tiers:

| Tier | javac | Fidelity | Trade-off |
|---|---|---|---|
| Built image `<build>/images/jdk` (or the interim compiler) | in-tree, version-exact | perfect, incl. just-landed syntax | the JDK under development may be unstable |
| **EA build of the feature version** (jdk.java.net) | stable, same feature version | finalized + preview features | lags mainline by one EA snapshot |
| GA | once the version ships | fine post-release | n/a while the version is in development |

The `sync` step already parsed the required release, so the launcher can **validate and fail loudly** —
"this JDK source needs javac ≥ 28; the server is on 25 — point it at an EA or the built image" — instead
of emitting a screen of false errors. That turns the scary failure into a one-line setup instruction.

**This is a differentiator, not just a mitigation.** IntelliJ ships its own reimplemented parser, which
structurally lags preview features (the exact "false red errors / IDE not compiling" complaint JDK
developers report). Lathe uses a *real* javac, so on an EA or the in-tree compiler it is as accurate as
the build — and more accurate than IntelliJ on precisely those features.

**Residual costs (stated honestly):** it is a moving target (28 now, 29 next) — but "use a new-enough
javac" is launcher config, not a code change; and the narrow gap (syntax in mainline but not yet in any
EA) is closed only by the built-image tier. The load-bearing assumption to verify is that the launcher
can **pin the server's JVM per workspace** (see open decisions).

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

**The trigger is a `make lathe` target that invokes the Maven goal directly by coordinate** — no
generated pom, no CLI, no shaded jar. Maven's direct-invocation grammar plus `requiresProject = false`
lets the plugin run in the JDK checkout, and Maven does all resolution/bootstrapping:

```make
lathe:
	mvn io.github.ag-libs:lathe-openjdk-maven-plugin:$(if $(LATHE_VERSION),$(LATHE_VERSION):,)sync \
	    -Dlathe.buildDir=$(OUTPUTDIR)
```

The `make lathe` include is the "extension" — the registered-once artifact (analog of the Maven
`.mvn/extensions.xml`), personal and requiring no edits to the tracked tree, exactly like `make idea`.
Its only job is to supply the live `$(OUTPUTDIR)` (and, optionally, the module list) and call the goal;
all real work is in the plugin. Run it after a build: `make jdk lathe` or
`make <module>-java-only lathe`.

**Version resolution — versionless by default, pin optional.** Maven's three-part
`groupId:artifactId:goal` form omits the version and resolves the latest release from Central metadata,
so the zero-config path needs neither a pom nor a `settings.xml` change:

- **No `.lathe/lathe.version`** → `…-maven-plugin:sync` → latest release.
- **`.lathe/lathe.version` present** → `…-maven-plugin:<version>:sync` → pinned, for teams wanting
  reproducibility (the POM-`<version>` analog).

Versionless is safe for **bundle agreement**: whatever plugin version Maven resolves, `SyncMojo` installs
the server/client **of its own version** into `~/.cache/lathe/`, so the capture tool, server, and client
never mismatch. The only thing given up is cross-machine/time determinism — a non-issue because `.lathe/`
is local and git-ignored.

**Always the canonical coordinate; never a `settings.xml` edit.** The goal is invoked by its full
`groupId:artifactId[:version]:goal` name. We deliberately do **not** use the short plugin prefix
(`mvn lathe-openjdk:sync`), because that would require every user to add a `<pluginGroups>` entry to
their `settings.xml` — an edit we do not expect anyone to make. The canonical form needs no `settings.xml`
change at all. (Lathe still *reads* an existing `settings.xml` for the org's mirrors/proxies during
resolution — it just never asks the user to modify it.)

**Discovery.** `-Dlathe.buildDir` (passed by the make target from the live `$(OUTPUTDIR)`) is the
authoritative source — the configured build's own output, not a guessed conf name. Run standalone
without it, the goal scans `build/*/spec.gmk`; with several configs it requires an explicit
`-Dlathe.buildDir` rather than guessing. Within the build dir it discovers modules by scanning
`jdk/modules/*/` for `_the.<module>_batch.cmdline` (the primary capture artifact, and the same
`_the.*_batch` family the freshness check keys off), and **logs any modules skipped** because they were
not built (partial builds like `make java.base-java-only` capture only what is present — no silent
truncation).

**Offline / air-gapped** JDK CI runs `mvn -o` against a pre-populated `~/.m2`.

**Gating** reuses `LatheFlags`: `.lathe/` opt-in, disable via property / `CI` env, same precedence as
Maven/Gradle. Running the explicit `make lathe` target is itself the opt-in, so creating `.lathe/` on
invocation is consistent; if a user wires `lathe` into the default build, the flag gating applies first.
`.lathe/` is git-ignored (`.git/info/exclude` or `.gitignore`).

## End-user setup (developer workflow)

Prerequisite: `mvn` on the machine (Maven is the bootstrap — it fetches the plugin + deps from Central).

```bash
git clone https://github.com/openjdk/jdk && cd jdk
bash configure --with-boot-jdk=/path/to/jdk-N     # normal JDK setup, unchanged
echo ".lathe/" >> .git/info/exclude
mkdir .lathe                                       # opt-in gate

make jdk lathe                                     # build once, then:
                                                   #   mvn …:lathe-openjdk-maven-plugin:sync
                                                   #   reads the descriptors → .lathe/, installs the bundle
```

Then open the repo in any Lathe editor client (Neovim, VS Code, Emacs, IntelliJ-via-LSP — all reused
unchanged). The developer immediately gets javac-accurate:

- **diagnostics that match the build**, with `--add-exports` / `--patch-module` / `--system none` /
  `-source/-target` honoured — no false errors of the kind `make idea`/`make eclipse` produce by dropping
  the module flags;
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

## Freshness & staleness

Two kinds of freshness, handled very differently. Most of the machinery is reused; only the build-shape
detection is new and OpenJDK-specific.

**Per-source edit freshness — self-healing, reuse the shipped stamps.** Lathe already ships per-source
compile stamps (`CompiledStampsData` in `.lathe/<module>/lsp-stamps-<tree>.json`; `isStaleSource` compares
a source's live mtime to its recorded stamp). For Maven these are written by `LatheCompiler.syncOutput`;
here the **`sync` goal writes them** — it already reads `_the.<module>_batch.filelist`, so it stats each
listed source and records its mtime. The consequence is that **edits need no freshness detection at all**:
an edited file's mtime exceeds its stamp, so the server marks it stale and analyzes it **in-process** (the
source overlay), giving live diagnostics whether or not `make`/`make lathe` ran. This falls out of the
existing stamp + in-process-analysis path unchanged.

**Build-shape freshness — detected, then prompted.** The only case that truly needs a re-sync is a
configuration change (the "Yes" rows above: `module-info`/flags, a new module or source root, a gensrc
regeneration, or `make reconfigure` / a different `build/<conf>`). The build hands us a free marker:
`SetupJavaCompilation` **touches `_the.<module>_batch` after every module compile** (`JavaCompilation.gmk:518`),
so its mtime is that module's last-compile time. So:

- `sync` records each module's `_the.<module>_batch` mtime (and the `spec.gmk`/conf identity) at capture.
- A lightweight check (on file-open / the existing watcher tick) compares the current marker mtime against
  the recorded one; newer ⇒ the module was rebuilt since capture ⇒ its params may be stale.
- The response is a **prompt, never an action** — the analog of Maven's POM-change prompt: *"`java.base`
  changed since last sync; run `make java.base-java-only lathe`"*, optionally surfaced as a
  command/code-action. **The server never runs `make`** (same rule as LSP never running Maven).

**The mirror's staleness is low-severity here.** Because JDK modules compile with
`--module-source-path <all sources> --system none`, the analysis javac resolves cross-module types **from
source**, not from the bytecode mirror. So a stale mirror barely affects code intelligence — what actually
matters is picking up **new files / changed config**, which is exactly what the prompt covers. The mirror
still feeds the reactor type-index, so `sync` refreshes it, but its staleness is not correctness-critical
the way it is for Maven's dependency jars.

**Maintaining it.** Primary: scoped `make <module>-java-only lathe` (ms when nothing changed — the analog
of `mvn process-test-classes`). Optional: the `make lathe` include can wire `lathe` as a
finalizer/dependency of the module `-java` targets so every `make <module>-java` also refreshes capture,
approaching Maven's build-bound automatic sync — opt-in, since it touches the build graph; the default
stays explicit. A filesystem watch over all ~66 modules' markers is **out of the MVP** (heavier than the
file-open check + explicit re-sync warrant).

| Concern | Mechanism | New? |
|---|---|---|
| Per-source edit freshness | `CompiledStampsData` stamps + `isStaleSource` + in-process analysis | reused; `sync` writes the stamps |
| Structural add/edit/delete reaction | [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md) | reused unchanged |
| Build-shape re-sync detection | compare `_the.*_batch` marker mtime vs recorded; prompt `make lathe` | **new, small, OpenJDK-specific** |
| Automatic refresh | `lathe` finalizer on `-java` targets | new, optional |

## Testing (end-to-end)

The obstacle is that a realistic fixture requires a **built JDK**, which is expensive. Two layers:

1. **Descriptor-fixture unit tests.** Check in captured real `_the.<module>_batch.cmdline` /
   `_the.<module>_batch.filelist` samples (small, path-normalized) and assert the parser produces
   the correct `ModuleConfigData` — including launcher-prefix stripping, multi-root ordering,
   `--system none`, `--add-exports`, `-Werror` dropped, and gensrc roots. No JDK build needed; runs in CI.
2. **Cross-tool contract test (gated).** On a machine with a built JDK **and a javac ≥ the mainline
   feature version**, run capture against `build/<conf>`, then boot `lathe-server` (on that javac — see
   [javac fidelity](#javac-fidelity--the-servers-runtime-jdk-the-real-hard-part)) against the produced
   `.lathe/` and assert javac-accurate diagnostics on a known module (e.g. edit `java.base`, expect zero
   false errors; resolve a symbol into another module and into gensrc). Gated behind a profile because of
   the build cost and the JDK-version requirement.

**Fixture focus:** a multi-root module (share + OS overlay), a heavily-generated module (`java.base`),
and a cross-module navigation case — so the structural claims are all under test. Add-file-without-
rebuild is an explicit case (proves the in-process refresh model).

## Slicing

1. **Descriptor capture (`ModuleConfigData` + mirror + stamps)** — the `.cmdline` parser (launcher strip,
   flag mapping) over one module's `_the.*_batch.cmdline` / `_the.*_batch.filelist`, with multi-root
   ordering, wired into the `sync` goal. Delivers code intelligence for a single module. **Pairs with the
   server-JVM version gate** — this slice is only meaningful when the server runs on a javac ≥ mainline.
2. **Full module set + `workspace.json`** — enumerate via `FindAllModules`; capture all ~66 modules;
   cross-module navigation. Completes the editor experience.
3. **Registration + gating** — the `make lathe` target invoking the goal by canonical coordinate,
   `.lathe/` opt-in, git-ignore, bundle install into `~/.cache/lathe/`.
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

1. **Server-JVM pinning (the load-bearing assumption).** javac fidelity requires launching the server on
   a javac ≥ the mainline feature version (28 today). Verify the cache launcher can **pin the server's
   JVM per workspace** (to an EA or the built image), and confirm `-source/-target` + `--system none`
   behave on an EA javac. This is the item that decides whether the feature is low-risk — the flag capture
   is the easy part. See [javac fidelity](#javac-fidelity--the-servers-runtime-jdk-the-real-hard-part).
2. **Descriptor parse stability + source-level mapping** — the exact on-disk content of
   `_the.<module>_batch.cmdline` / `_the.<module>.vardeps` is confirmed from the makefiles but not yet from
   a live build; lock it against a real `make java.base` before finalizing the parser. Confirm in the same
   pass that carrying `-source/-target` + `--system none` (with `ModuleConfigData.release` left empty)
   compiles cleanly in the server — i.e. that we correctly avoid the `--release`/`--system` conflict.
   *Gates implementation of the reader.*
3. **Minimum JDK / build version** — confirm the descriptor mechanism and `SRC_SUBDIRS`/`FindAllModules`
   contracts across the JDK versions Lathe intends to support (they are stable but version-check).
4. **Multi-root override representation** — carry ordered `sourceRoots` and rely on the server's
   first-found resolution, vs an explicit per-file override map. Ordered roots is the KISS default.
5. **jtreg capture** — whether a future run/test slice observes the JVM jtreg forks per `@run`, or is
   left to the existing jtreg tooling. Out of scope now; noted so it is not assumed solved.

**Settled** (earlier open questions, now decided): single `lathe-openjdk-maven-plugin` module with a
`sync` goal (`requiresProject = false`); trigger is `make lathe` invoking the goal by **canonical
coordinate** (versionless by default, `.lathe/lathe.version` to pin); no standalone CLI, no shaded jar,
no `settings.xml` edit; partial builds capture-present-and-log-skipped; `-Dlathe.buildDir` authoritative
with a `build/*/spec.gmk` fallback that requires an explicit dir when several configs exist.

## Non-goals (this design)

- jtreg run/test/debug in the MVP; HotSpot / native code; the langtools bootstrap as a build concern.
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema — the whole point is
  that they are untouched.
