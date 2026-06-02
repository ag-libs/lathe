# Lathe — Java Language Server Design (v3)

## 1. Vision & Scope

Lathe is a JPMS-first Java Language Server for Maven projects.
Every existing Java LSP reimplements Maven's project model —
parsing POMs, guessing classpaths, playing catch-up with plugins.
Lathe sidesteps this entirely: a compiler shim captures the exact javac invocation parameters Maven used,
and the language server uses them directly.

This makes Lathe correct by construction for projects where other tools fail:
- JPMS monorepos with complex module path configurations
- Heavy annotation processing setups (MapStruct, Dagger, Hibernate metamodel) —
  AP runs on `didSave`, not on every keystroke, keeping `didChange` latency low
  while preserving full AP correctness at save boundaries
- Custom Maven plugins that add source roots or modify compilation parameters

JPMS projects define Lathe's primary correctness target,
especially reactor type discovery, exported-package visibility, and module-aware completion.
Ordinary classpath Maven projects are still supported for the core features that replay Maven's exact javac invocation:
diagnostics, hover, semantic tokens, formatting, and many definition cases.
Full non-JPMS type discovery is left as a focused future contribution based on classpath scanning.
Lathe does not try to support split-package behavior beyond whatever javac accepts from the captured Maven invocation.
Lathe does not support Lombok.
It operates on source as written.

Lathe assumes a workflow of small modules and small files.
The language server holds no per-module javac state between requests — every compilation pass is built fresh.
This trades a fixed per-pass cost for the absence of an entire class of staleness and memory-growth bugs.
The target is 500ms p95 from `didChange` debounce-fire to `publishDiagnostics` for a representative file (≤500 LOC) in
a representative module (≤50 source files, ≤50 classpath entries).
AP-heavy modules meet this budget because annotation processing runs only on `didSave`.

---

## 2. Components

Four components, no circular dependencies.

**`lathe-core`** — shared filesystem, JSON-serialization, schema, file, and timing helpers used by the compiler shim,
Maven plugin, and server.
It is a JPMS module.

**`lathe-compiler`** — a Plexus compiler SPI implementation.
Registered as the compiler for `maven-compiler-plugin`.
Delegates to real javac unchanged, then writes compilation parameters to `.lathe/` and copies compiled artifacts to
`.lathe/<rel>/classes/`, `.lathe/<rel>/test-classes/`, and `.lathe/<rel>/generated-sources/` after each build.

**`lathe-maven-plugin`** — provides `lathe:init` and `lathe:sync`.
`init` auto-binds to the `initialize` phase; its only job is `Files.createDirectories(.lathe/)`.
It runs silently before compilation so the shim always finds `.lathe/` on the very first build.
`sync` auto-binds to `process-test-classes`; it refreshes shared dependency/JDK sources,
writes `workspace.json` (skipped when content is unchanged or when Maven is invoked with `-pl`),
and owns all integration tests via maven-invoker.
Neovim headless e2e (Layer 3) is a planned test layer, not yet implemented.

**`lathe-server`** — the LSP server.
Reads params files written by the shim and the workspace manifest written by the Maven plugin, builds a fresh
`JavacTask` per compilation pass, and serves LSP requests.
It reads dependency/JDK sources from `~/.cache/lathe/`.
Stale-POM detection (watching POM changes and prompting the user to re-sync) is a planned feature,
not yet implemented — `StubWorkspaceService.didChangeWatchedFiles` is currently empty.
Type indexes are a later slice.

```
lathe-core
    provides   → layout, JSON, schema, file, and timing helpers

lathe-compiler
    implements → Plexus compiler SPI
    delegates  → plexus-compiler-javac
    depends on → lathe-core

lathe-maven-plugin
    provides   → init + sync goals
    reads      → Maven session/reactor/dependency resolution
    writes     → .lathe/ (init), .lathe/workspace.json (sync)
    writes     → ~/.cache/lathe/ (dependency sources, JDK sources)
    depends on → lathe-core

lathe-server
    reads        → .lathe/ params files + workspace manifest
    reads/writes → .lathe/<rel>/classes/, .lathe/<rel>/test-classes/, .lathe/<rel>/generated-sources/
    reads        → ~/.cache/lathe/ (sources)
    depends on   → lathe-core, lsp4j, google-java-format
```

`lathe:init` does not install server binaries.
`lathe:sync` installs the server launcher into `~/.cache/lathe/servers/<version>/` and updates the `~/.cache/lathe/current` symlink.

---

## 3. Setup & User Configuration

### One-time POM configuration

Two blocks in the parent `pom.xml`:

```xml
<!-- compiler shim -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <dependencies>
    <dependency>
      <groupId>io.github.ag-libs</groupId>
      <artifactId>lathe-compiler</artifactId>
      <version>0.1.0</version>
    </dependency>
  </dependencies>
  <configuration>
    <compilerId>lathe</compilerId>
  </configuration>
</plugin>

<!-- init + sync goals: bind to initialize and process-test-classes -->
<plugin>
  <groupId>io.github.ag-libs</groupId>
  <artifactId>lathe-maven-plugin</artifactId>
  <version>0.1.0</version>
  <inherited>false</inherited>
  <executions>
    <execution>
      <id>lathe-init</id>
      <goals>
        <goal>init</goal>
      </goals>
    </execution>
    <execution>
      <id>lathe-sync</id>
      <goals>
        <goal>sync</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Maven only runs a third-party plugin during normal lifecycle builds when the plugin has bound executions.
Lathe therefore still needs the two executions above for `mvn package`, `mvn install`,
or `mvn process-test-classes` to refresh the workspace automatically.
Both mojos declare their default phase via `@Mojo(defaultPhase = ...)`, so those executions can omit `<phase>`.
`init` defaults to `initialize` (before compilation); `sync` defaults to `process-test-classes` (after test compilation).
`<inherited>false</inherited>` ensures both goals run once from the reactor root, not once per module.
Any normal `mvn process-test-classes`, `mvn package`, or `mvn install` run heals Lathe state automatically.

### Initializing

```bash
mvn io.github.ag-libs:lathe-maven-plugin:VERSION:init
```

Create `.lathe/` at the Maven session top-level project if missing.

`lathe:init` does not validate Maven configuration.
Maven setup can be inherited from parents, profiles, plugin management, or external build conventions,
so v1 treats POM validation as out of scope.
Missing or incorrect setup is surfaced later by the compiler shim, sync goal, or language server based on files that
Lathe actually needs.

### Ongoing workflow

```bash
mvn io.github.ag-libs:lathe-maven-plugin:VERSION:init       # once: create .lathe/
mvnd process-test-classes                                   # refresh Lathe — shim writes params, sync refreshes metadata
```

Run `mvnd process-test-classes` when you want to refresh Lathe directly;
ordinary `mvnd package` and `mvnd install` runs also reach this phase and refresh Lathe automatically.
The shim updates `lsp-params-classes.json` and `lsp-params-test-classes.json` for every module it compiles.
The bound `lathe:sync` goal then resolves external dependency source JARs,
refreshes extracted dependency sources under `~/.cache/lathe/deps/`,
writes the minimal dependency-source manifest to `.lathe/workspace.json`,
and installs the server launcher into `~/.cache/lathe/servers/<version>/` (idempotent).
Stale-POM fingerprints and type indexes are later sync slices.
The LS currently reads shim params on startup and registry reload;
it also reads the workspace manifest for dependency/JDK source roots, external-source classpaths,
and hover origin labels.

If a module has no params file yet (first checkout, new module added), the LS surfaces:
"Run `mvn process-test-classes` to activate module `<relativePath>`."

Future stale-POM detection will compare watched POM content against hashes in the workspace manifest and surface:
"Maven project changed. Run `mvn process-test-classes` to refresh Lathe."

Re-run `lathe:init` when setting up a checkout or after changing Lathe POM configuration.
The next `process-test-classes`, `package`, or `install` run refreshes params and synchronized metadata.

### JVM customization

```bash
export LATHE_JVM_OPTS="-Xmx4g -Xms512m -XX:+UseZGC"
```

Set in `.bashrc` or `.zshrc`.
Nothing committed to the project.

### Javac API Boundary

Lathe server code may use the public `javax.tools`, `javax.lang.model`, and exported `com.sun.source.*` APIs.
It must not import `com.sun.tools.javac.*`.
JVM `--add-exports` / `--add-opens` flags for `com.sun.tools.javac.*` are allowed only for third-party modules that
require them, such as `com.google.googlejavaformat` and classpath-loaded javac plugins.
Interactive attribution (`didOpen` / `didChange`) does not replay javac plugins from captured Maven compiler arguments.
Options such as `-Xplugin:ErrorProne` and Error Prone's `-Xep*` flags are stripped for interactive passes.
Full save passes replay them for Maven-faithful diagnostics.

---

## 4. File Layout

Lathe uses two directories with distinct purposes.

**`.lathe/`** — workspace-level, project-specific metadata and LS bytecode.
Created by `lathe:init`.
Written by the shim on every compile for params files and class copies,
and by `lathe:sync` for the workspace manifest.
Written by the LS for compilation outputs.
Add to `.gitignore`.
The directory structure mirrors the Maven project hierarchy —
each module's subdirectory is keyed by its path relative to the workspace root and contains all params, lock,
and output files for that module.

**`~/.cache/lathe/`** — user-level cache, shared across all projects on the machine.
JDK sources and dependency sources are written by `lathe:sync`.
Server distributions and indexes are later sync slices.
Never needs to be gitignored.

```
~/.cache/lathe/
├── servers/                              ← server launchers installed by lathe:sync
│   └── 0.1.0/
│       └── lathe-launcher.sh            ← generated; --module-path points at absolute .m2 paths
├── current -> servers/0.1.0/
├── jdks/                                ← JDK sources extracted by lathe:sync, once per vendor/version
│   └── Eclipse-Adoptium/
│       └── 21.0.7/
│           └── java/lang/String.java
├── deps/                                ← dependency source jars extracted by lathe:sync
│   └── com.google.guava:guava:32.0.0-jre/
│       └── com/google/common/collect/ImmutableList.java
└── type-index/                           ← future shared type indexes
    └── jars/
        └── com.google.guava/
            └── guava/
                └── 32.0.0-jre.index

.lathe/
├── workspace.json                                 ← written by lathe:sync
├── module-a/
│   ├── lsp-params-classes.json                    ← written by shim
│   ├── lsp-params-test-classes.json               ← written by shim
│   ├── lathe.lock                                 ← written by shim
│   ├── classes/                                   ← seeded by shim, written by LS
│   │   └── com/example/alpha/
│   │       └── Foo.class
│   ├── test-classes/                              ← seeded by shim, written by LS
│   │   └── com/example/alpha/
│   │       └── FooTest.class
│   └── generated-sources/                         ← seeded by shim, written by LS
│       └── com/example/alpha/
│           └── FooBuilder.java
└── platform/
    └── core/                                      ← nested module, path preserved
        ├── lsp-params-classes.json
        ├── lsp-params-test-classes.json
        ├── lathe.lock
        ├── classes/
        │   └── com/example/platform/
        │       └── Bar.class
        ├── test-classes/
        │   └── com/example/platform/
        │       └── BarTest.class
        └── generated-sources/
            └── com/example/platform/
                └── BarBuilder.java
```

**Nothing committed to the repo.** `.lathe/` is gitignored and fully regenerated.
`~/.cache/lathe/` is outside the project entirely.

**Shared across projects.** JDK sources and dependency sources are shared across all projects on the machine.
Future server distributions and type indexes will be shared the same way.
Extracted or built once per version, reused everywhere.

**Survives `mvn clean`.** Both directories are outside Maven's build output management.
`mvn clean` deletes `target/` only — `.lathe/<rel>/classes/`, `.lathe/<rel>/test-classes/`,
and `.lathe/<rel>/generated-sources/` survive intact.

**Concurrent safety.** `lathe:sync` writes extracted sources to a temp directory first,
then atomically renames into the shared cache where the platform supports it.
Future server distribution and index writes should use the same pattern.

### Workspace manifest

`lathe:sync` writes `.lathe/workspace.json` after a successful refresh.
The manifest is workspace-local because it records the current Maven project shape,
but it points at user-cache entries shared across workspaces.
It is not the workspace bootstrap marker.
The `.lathe/` directory is the stable signal that a checkout is Lathe-enabled,
while `workspace.json` is a disposable synchronized snapshot that may be missing or stale.

```json
{
  "schemaVersion": "1",
  "workspaceRoot": "/workspace",
  "jdk": {
    "vendor": "Eclipse-Adoptium",
    "version": "21.0.7",
    "status": "PRESENT",
    "home": "/usr/lib/jvm/temurin-21",
    "sourceZip": "/usr/lib/jvm/temurin-21/lib/src.zip",
    "sourceDir": "/home/user/.cache/lathe/jdks/Eclipse-Adoptium/21.0.7"
  },
  "dependencySources": [
    {
      "gav": "com.google.guava:guava:32.0.0-jre",
      "jar": "/home/user/.m2/repository/com/google/guava/guava/32.0.0-jre/guava-32.0.0-jre.jar",
      "status": "PRESENT",
      "dir": "/home/user/.cache/lathe/deps/com.google.guava/guava/32.0.0-jre",
      "classpath": [
        "/home/user/.m2/repository/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar"
      ]
    },
    {
      "gav": "org.example:no-sources:1.0",
      "jar": "/home/user/.m2/repository/org/example/no-sources/1.0/no-sources-1.0.jar",
      "status": "MISSING",
      "dir": null,
      "classpath": []
    }
  ]
}
```

`lathe:sync` writes the manifest atomically:
write a complete temporary file in `.lathe/`,
then move it over `workspace.json`.
The LS therefore sees either the previous complete manifest or the next complete manifest,
never a partially written file.

The first manifest format is intentionally minimal.
It records the workspace root and external dependency source lookup entries.
It does not describe the full Maven reactor yet.
The server still discovers modules from the shim-written `lsp-params-*.json` files.
Later manifest slices may add `module.N.*` blocks, POM hashes, server distribution paths, and stale-project fingerprints.

For dependency source lookup,
the server matches absolute classpath and modulepath JAR paths from the params files against `dependencySources[].jar`.
If `dependencySources[].status` is `present`,
the server uses `dependencySources[].dir` as the extracted source root for that JAR.
`dependencySources[].classpath` stores the compile/provided external JARs from the first reactor module that resolved
the dependency.
The server uses those entries, plus the dependency's own JAR, when compiling opened dependency source files.
`dependencySources[].gav` is retained for diagnostics and logging,
but the JAR path is the lookup key.
Missing source JARs are recorded with `status` set to `missing` and no `dir`.

JDK sources are the implemented sync slice after dependency sources.
`lathe:sync` reads `JAVA_HOME`, extracts `$JAVA_HOME/lib/src.zip` to
`~/.cache/lathe/jdks/<sanitizedVendor>/<sanitizedVersion>/` when present,
and records `jdk.home`, `jdk.vendor`, `jdk.version`, `jdk.status`, `jdk.sourceZip`, and `jdk.sourceDir` in the manifest.
The server uses `jdk.sourceDir` directly and does not derive or rediscover the JDK source cache path.

When reactor module metadata is added to the manifest,
module identity will be the path relative to the workspace root,
for example `module-a` or `platform/core`.
Those future module blocks will point at the main and test params files produced by the shim.
The params files remain the compile source of truth:
main dependencies live in `lsp-params-classes.json`,
and test dependencies live in `lsp-params-test-classes.json`.
The manifest must not duplicate per-module classpath or test dependency lists.

The LS loads the manifest into an immutable `WorkspaceManifest` snapshot during startup and registry reload.
`WorkspaceManifest.load(workspaceRoot)` reads `.lathe/workspace.json`,
builds binary-JAR to source-root maps,
builds source-root to per-dependency classpath maps,
and reads `jdk.sourceDir` and `jdk.version`.
The snapshot is also reloaded whenever `workspace.json` or params-file changes are detected.
Reloading the snapshot clears cached external source analyses so definition and hover use the new source/classpath map.
Feature handlers read that snapshot directly; handlers do not re-read `workspace.json` on every request.
Future slices will add stale-POM checks and module-to-params metadata.

---

## 5. Compiler Shim

The shim is a Plexus compiler SPI implementation (`org.codehaus.plexus.compiler.Compiler`, hint `lathe`).
The Maven compiler plugin invokes it instead of javac directly.
The shim delegates to real javac unchanged — the build is unaffected — then captures the invocation parameters.

### Locating the workspace

**Module root** — `CompilerConfiguration.getWorkingDirectory()`,
which Maven sets to the module's base directory for each compilation invocation.

**Workspace root** — walk up from the module root until a directory containing `.lathe/` is found.
If not found — the shim skips writing params silently.
The LS will prompt the user to run `mvn lathe:init`.

**Relative path** — the module root path relative to the workspace root.
Used as the key for the module's `.lathe/` subdirectory:

```
workspaceRoot = /workspace
moduleRoot    = /workspace/platform/core
relativePath  = platform/core
outputDir     = .lathe/platform/core/
```

### Invocation sequence

1. Locate workspace root and derive relative path — skip silently if `.lathe/` is not found
2. Create `.lathe/<relativePath>/` if it does not exist, then write empty `lathe.lock`
3. Delegate to `plexus-compiler-javac` — real javac runs, AP executes, generated sources are produced
4. In `finally` — write params file
5. Copy all files from `outputDir` to `.lathe/<relativePath>/classes/` (main) or `.lathe/<relativePath>/test-classes/`
   (test)
6. If AP ran, copy all files from `target/generated-sources/annotations/` to `.lathe/<relativePath>/generated-sources/`
7. Delete `lathe.lock` — last action, signals LS that params and bytecode are both ready

Steps 4–7 run unconditionally in `finally` — regardless of compilation success or failure.
The copied bytecode reflects the actual state of the module after this compile, which is exactly what the LS needs.

**Lock ordering.** The lock is deleted last, after the copy is complete.
The LS waits while the lock exists — there is no concurrent access to `.lathe/<rel>/classes/` between shim and LS,
so a plain bulk copy is sufficient.
No per-file atomicity is needed.

Source tree (main vs test) is detected from the output directory — whether it contains `classes` or `test-classes`.

### Lock file protocol

`lathe.lock` is an empty file.
The LS uses its modification time:

- **Lock exists, mtime < 2 minutes** — Maven is compiling.
  LS pauses compilation passes for this module
- **Lock exists, mtime ≥ 2 minutes** — Maven was killed.
  Treat as stale, ignore
- **Lock deleted** — compilation finished.
  LS re-reads the module's params file on its next compilation pass for any open file in that module.
  No state to invalidate — the next pass builds a fresh `JavacTask` from the new params.

### `lsp-params-classes.json`

```json
{
  "sourceTree": "classes",
  "outputDir": "/workspace/module-a/target/classes",
  "generatedSourcesDir": "/workspace/module-a/target/generated-sources/annotations",
  "sourceRoots": [
    "/workspace/module-a/src/main/java",
    "/workspace/module-a/target/generated-sources/annotations"
  ],
  "classpath": [
    "/root/.m2/repository/com/google/guava/guava/32.0.0-jre/guava-32.0.0-jre.jar"
  ],
  "modulepath": [
    "/workspace/platform/core/target/classes"
  ],
  "processorPath": [
    "/root/.m2/repository/org/mapstruct/mapstruct-processor/1.5.5/mapstruct-processor-1.5.5.jar"
  ],
  "release": "21",
  "encoding": "UTF-8",
  "parameters": true,
  "enablePreview": false,
  "proc": "",
  "compilerArgs": [
    "-Xlint:unchecked",
    "-Amapstruct.defaultComponentModel=spring"
  ]
}
```

`projectDir` and `buildDir` are not stored —
the LS derives `projectDir` from the params file's location within `.lathe/`,
and `buildDir` by convention (`<projectDir>/target`).

### Overhead

Shim overhead above normal javac is one directory walk, one file write,
and a bulk directory copy per compilation invocation.
The copy cost is proportional to the module's class file count — typically 50–200ms I/O for a small module.
Safe with parallel builds (`mvnd -T N`) — each module writes to its own independent `.lathe/<rel>/` directory.
Copy duration is logged when `LATHE_DEBUG=1`.

---

## 6. Language Server — Module Model

### Startup

The LS currently scans `.lathe/` at the workspace root,
finds all `lsp-params-*.json` files,
and derives the module registry from them.
`workspace.json` is written by sync and read for dependency/JDK source lookup,
but it is not yet used for module discovery.
For each params file:

- **Relative path** — the directory containing the params file, relative to `.lathe/`.
  A file at `.lathe/platform/core/lsp-params-classes.json` belongs to module `platform/core`.
- **Project directory** — `<workspaceRoot>/<relativePath>`.
- **Reactor dependency graph** — built by cross-referencing all modules' `outputDir` values against each other's
  classpath entries.
  If module A's `classpath.N` matches module B's `outputDir`, A depends on B.
  Both main and test classpath entries are cross-referenced.

No `JavacTask` is created at startup.
No `CustomFileManager` is created.
Startup cost is bounded by the number of params files, not by reactor complexity or classpath size.

If `.lathe/` does not exist: "Run `mvn lathe:init` then `mvn process-test-classes` to initialize Lathe."
If `.lathe/` is present but dependency source lookup is unavailable,
the LS may still serve compiler-backed features from params files and should prompt for
`mvn process-test-classes` only when a feature needs synced source metadata.
If a module directory has no params files: "Run `mvn process-test-classes` to activate module `<relativePath>`."

The LS watches `workspace.json` and `lsp-params-*.json` files.
Manifest or params changes trigger a full LS reload:
module source configs are re-scanned from `.lathe/`,
all `ModuleSourceWorker` instances and result caches are dropped,
and open files are re-attributed.
The user sees a brief "Workspace reloaded" notification.

### Compilation state model

Lathe holds no long-lived cross-module javac symbol cache.
Every operation that needs javac — diagnostics on `didChange`, member-access completion, find-references attribution
against open files, hover, semantic tokens — builds a fresh `JavacTask` from the module's params and runs the pass.
When an attributed result is cached for later hover, definition, or semantic-token requests,
the cached `AttributedFileAnalysis` retains the javac task-backed state needed by `Trees` and the attributed
`CompilationUnitTree`.
Those references are dropped when the cached context is replaced, invalidated, or dropped on `didClose`.

The only durable per-module-source state is:

- **Parsed params** — read from disk on startup and re-read after the module's `lathe.lock` disappears.
- **`ModuleSourceWorker`** — one lazy worker per `ModuleSourceConfig`, which means main and test params get
  separate workers.
  The worker owns one long-lived `SourceAnalysisSession`.
- **`SourceAnalysisSession`** — owns one `JavaSourceCompiler`, feature helpers, and the per-file analysis cache.
- **`ModuleSourceCompiler`** — one instance per module source tree.
  Owns one `StandardJavaFileManager`.
  The file manager is initialized eagerly in the constructor, sets explicit javac locations
  (`CLASS_OUTPUT` → `.lathe/<rel>/classes`, `SOURCE_OUTPUT` → `.lathe/<rel>/generated-sources`),
  and holds no attributed javac task state.
  `ModuleSourceCompiler` is closed when its `SourceAnalysisSession` closes during workspace reload or server shutdown.
  There is no LRU — workers are created on demand and live for the duration of the current `WorkspaceModules` snapshot.

_v1 simplification — the temp-dir approach is straightforward to implement and test.
A future version may replace it with in-memory `JavaFileObject` serving to avoid the disk round-trip._
- **Per-file result cache** — `Map<Path, CompileResponse>` keyed by document content hash.
  Holds the post-attribution `AttributedFileAnalysis` from the most recent pass for each open file.
  The analysis includes `Trees`, `CompilationUnitTree`, and pre-computed semantic tokens.
  Because `Trees` is backed by javac task state, the analysis intentionally keeps that state reachable while cached.
  At most one entry per currently-open file.
  The previous context is dropped when replaced by a new compile result,
  invalidated on next `didChange` for that file,
  dropped on `didClose`,
  and cleared during registry reload or server shutdown.
  Used by hover, definition, and semantic-tokens to avoid re-running javac for read-only queries.

Because there is no symbol cache across passes, there is no cross-module staleness.
When module B is recompiled by Maven, the shim copies fresh bytecode to `.lathe/platform/core/classes/`;
when the LS compiles a file in B, it writes directly to `.lathe/platform/core/classes/`.
Either way, module A's next compilation pass reads B's current `.class` from `.lathe/platform/core/classes/` via the
file manager.
No invalidation logic, no propagation graph, no `Symtab` eviction.

### LS write semantics

Maven owns `target/classes/`, `target/test-classes/`, and `target/generated-sources/annotations/`.
The LS never writes there.
The LS owns `.lathe/<rel>/classes/`, `.lathe/<rel>/test-classes/`, and `.lathe/<rel>/generated-sources/`.
Maven never writes there.
The two output spaces are fully separate.

**Classpath remapping.** The params file records Maven's output paths.
For example: `classpath.0=/workspace/platform/core/target/classes`.
Before building a `JavacTask`, the LS rewrites the classpath to point at the `.lathe/` copies instead:

- Any `classpath.N` or `modulepath.N` entry matching a reactor module's `outputDir` from
  `lsp-params-classes.json` is remapped to `.lathe/<that-module-rel>/classes/`.
- Any entry matching a reactor module's `outputDir` from `lsp-params-test-classes.json` is remapped to
  `.lathe/<that-module-rel>/test-classes/`.
- The `generatedSourcesDir` entry in `sourceRoots.N` is remapped to `.lathe/<this-module-rel>/generated-sources/`.

Non-reactor entries (JARs in `~/.m2/repository/`) are used as-is.
The `outputDir` field in the params file is used only as a lookup key for this remapping —
the LS never reads from or writes to it.

**LS output redirection.** When javac calls `getJavaFileForOutput(location, ...)`
during a compilation pass, the `CustomFileManager` intercepts and redirects:

- `StandardLocation.CLASS_OUTPUT` → `.lathe/<this-module-rel>/classes/`
- `StandardLocation.SOURCE_OUTPUT` → `.lathe/<this-module-rel>/generated-sources/`

This is independent of `outputDir`.
The LS writes compiled artifacts to `.lathe/` regardless of what Maven's output directory is.

**Orphan handling.** When a compile pass produces fewer class files than the previous pass for the same source:

- Before the pass, snapshot `.lathe/<rel>/classes/` entries matching the source basename pattern (`Foo.class`,
  `Foo$*.class`).
- During the pass, capture files actually written via `getJavaFileForOutput`.
- After the pass, delete `snapshot \ written` from `.lathe/<rel>/classes/`.

Same logic applies to `.lathe/<rel>/generated-sources/` for AP outputs.

**Source file deletion** (via `didChangeWatchedFiles`):
walk `.lathe/<rel>/classes/<package>/` for class files matching the deleted source's basename (including `$Inner`
variants) and delete them.
Walk `.lathe/<rel>/generated-sources/` for matching generated sources and delete them.

**Maven recompiles the module** (lock file deleted):
the shim has already copied fresh bytecode to `.lathe/<rel>/classes/` and generated sources to
`.lathe/<rel>/generated-sources/` as part of the compile.
The LS re-reads params on the next pass automatically.
No LS-side action needed.

### Single-file compilation semantics

Each pass compiles exactly one source file — the file being edited or opened.
Dependencies within the same module are resolved by the `CustomFileManager` based on whether each sibling file is
currently open:

- **Open sibling files** — served from the module's temp directory (current content written there on
  `didOpen`/`didChange`).
  javac resolves them via the prepended temp source root; no class file is generated for them in this pass.
- **Closed sibling files** — resolved from `.lathe/<rel>/classes/` (main) or `.lathe/<rel>/test-classes/` (test) via
  CLASS_PATH.
  Their on-disk source is not read.
- **Reactor dependencies** — resolved from `.lathe/<that-module-rel>/classes/` for main deps,
  or `.lathe/<that-module-rel>/test-classes/` for test-scoped deps such as shared test utilities.
- **JAR and JDK dependencies** — resolved from `~/.m2/repository/` and the JDK as normal.

**New files** not yet compiled (no `.class` in `.lathe/<rel>/classes/`) are visible because `didOpen` writes them
to the module's temp directory immediately.
That makes them resolvable via the temp source root.

### File-to-module matching

When `didOpen` fires, the LS finds the owning module by scanning loaded params for a `sourceRoots.N` entry that is a
prefix of the opened file path.
Main and test are distinguished — a file under `src/test/java/` matches `lsp-params-test-classes.json`.
Generated source files under `.lathe/<rel>/generated-sources/` match correctly
because the LS registers this directory as a source root.

### `didChange` flow — fast pass

```
didChange received (LSP receive thread)
  → capture URI and full-content snapshot
  → enqueue didChange work on the server worker

server worker:
  → store the open-document snapshot
  → publish empty diagnostics
  → cancel any pending debounce for this URI
  → schedule debounce (500ms)

debounce fires (server worker):
  → wait for the file's own module's lathe.lock to disappear if present
  → wait for any direct reactor dependency's lathe.lock to disappear if present
  → build fresh JavacTask from params (proc=none — AP skipped)
  → single-file attribution pass for the changed file
  → flush file manager after the pass as needed
  → publish diagnostics and store `AttributedFileAnalysis` in the analysis cache
```

AP is skipped on the fast pass.
Target: 500ms p95.

### `didSave` flow — full pass

```
didSave received (LSP receive thread)
  → capture URI
  → enqueue didSave work on the server worker

server worker:
  → cancel any pending debounce
  → wait for the file's own module's lathe.lock to disappear if present
  → wait for any direct reactor dependency's lathe.lock to disappear if present
  → build fresh JavacTask from params + CustomFileManager (proc= from params — AP runs)
  → single-file compilation pass for the changed file
  → flush file manager after the pass
  → write .class to .lathe/<rel>/classes/ and generated sources
     to .lathe/<rel>/generated-sources/, publish diagnostics, store `AttributedFileAnalysis` in the analysis cache
```

Target: ~1–2s p95 for AP-heavy modules.

### `didOpen` flow — full pass

Cancels any in-flight pass for the module and any pending debounce,
then runs immediately with no debounce, using the same full-pass logic as `didSave`.
First-file-in-module open also pays for `CustomFileManager` creation and params-file parse.

If the module has no params yet: "Run `mvn process-test-classes` to activate module `<relativePath>`."

Target: ~1s p95.

### `didClose` flow

Delete the file from the module's temp directory.
Drop and close its result cache entry.
Publish empty diagnostics array to clear client display.
Cached file managers remain bounded by the LRU and are closed on eviction or registry reload.

### File deletion

`workspace/didChangeWatchedFiles` with a deleted event:

1. Remove from `CustomFileManager` in-memory tracking
2. Drop result cache entry
3. Delete corresponding `.class` files from `.lathe/<rel>/classes/` or `.lathe/<rel>/test-classes/`
4. For any other open file in the module, the next `didChange` or read-cache miss will compile against current state
   automatically

### Threading

Lathe uses one server worker thread for all work that touches mutable server state or javac-backed objects.
LSP4J message threads and the workspace watcher capture immutable request data, enqueue work, and return futures.
The server worker owns `WorkspaceSession`, `WorkspaceModules`, open-document snapshots, routing, stale checks,
client publishing, debounced compilation, and workspace reload.
Each `ModuleSourceWorker` or external worker owns its `SourceAnalysisSession`, `JavaSourceCompiler`,
and javac-backed analysis cache on a single worker thread.
This keeps javac file managers thread-confined and avoids method-level synchronization around compiler internals.
If profiling later shows this is too restrictive, the worker boundary can split into per-module or project/external
lanes without changing the request boundary: immutable data in, LSP DTOs or client notifications out.

**Dependency lock wait.** Before starting any pass,
the server worker checks not only the file module's lock but also the locks of all direct reactor dependencies
(derived from the in-memory reactor graph).
This prevents reading stale `.lathe/<dep-rel>/classes/`
while the shim is mid-copy for a dependency during a parallel `mvnd -T N` build.

**Resource cleanup.** `AttributedFileAnalysis` retains any javac task-backed state needed by cached `Trees`.
The public `JavacTask` API has no supported close method,
so cleanup means dropping cached context references when replaced,
invalidated, dropped on `didClose`, or cleared during registry reload and server shutdown.
The closeable javac resource Lathe owns is the `StandardJavaFileManager` inside each `JavaSourceCompiler`;
it is flushed after full passes and closed on workspace reload and server shutdown.

---

## 7. Language Server — Features

### Member access completion

> Full design: [completion-design.md](done/completion-design.md)

Completion uses three layers kept strictly separate:

**Layer 1 — Live buffer** — the current content string.
Used for prefix extraction and scanning only.
Never passed to javac at completion time.

**Layer 2 — Parse-only sentinel** — a synthetic copy of the live buffer with the cursor token replaced by
`__LATHE_SENTINEL__` is parsed by javac with no attribution (~10ms).
The sentinel node's ancestor chain reveals syntactic context: member access, simple name, argument position,
constructor call, annotation.
A per-file sentinel cache avoids re-parsing while the user extends the current token —
cache hits cost under 1ms with no javac invocation.

**Layer 3 — Background attributed snapshot** — the `CachedFileAnalysis` produced by the debounced attribution pass.
Used read-only at completion time for receiver type lookup, local variable scanning, and member enumeration.
No attribution runs during completion.

Injection: backward scan extracts the prefix and detects STATEMENT vs EXPRESSION context;
forward scan counts unmatched open delimiters;
the file tail is preserved so lambda-enclosing method calls remain parseable.
`__LATHE_SENTINEL__` is followed by `";"` in STATEMENT context;
in EXPRESSION context the tail provides the necessary closing tokens.

Fallback: when sentinel parse fails or the receiver type cannot be resolved from the snapshot,
index-backed type-name proposals are returned when the type index is available, otherwise an empty list.
The fallback never returns an error — always a possibly-empty list.

### Go-to-definition

**Reactor types in an open-file module** — if the target source file is open, return the declaration location from that
file's cached `CompilationUnitTree`, or run a fresh pass for that file if its cache is empty.

**Reactor types in a module with no open files** —
locate the source file via the target module's `sourceRoots.N` entries, return the `file://` URI.

**JDK types** — `lathe:sync` writes `jdk.sourceDir` to `.lathe/workspace.json` when
`$JAVA_HOME/lib/src.zip` is present.
The server resolves attributed JDK elements through `SYSTEM_MODULES`,
then searches `jdk.sourceDir` for the matching source file.

**Dependency types** — the binary JAR path is resolved from the attributed element through the active javac file manager.
`lathe:sync` resolves the matching source artifacts through Maven, extracts available source JARs to
`~/.cache/lathe/deps/<gav-path>/`, and records source status in `.lathe/workspace.json`.
The LS uses that state:

1. `dependencySources[].status=present` → return the extracted `file://` URI under `dependencySources[].dir`
2. `dependencySources[].status=missing` or no matching JAR entry → return no location
3. no workspace manifest → prompt the user to run `mvn process-test-classes` when opening files outside any module

Extracted sources are shared across all projects.

The baseline protocol result is a plain `file://` URI under the extracted cache directory.
This keeps Neovim and simple LSP clients working without extension-specific URI support.
Clients that support virtual read-only documents may present the same target through a Lathe-owned URI scheme:

```text
lathe-source://dep/io/grpc/grpc-api/1.73.0/io/grpc/DecompressorRegistry.java
lathe-source://jdk/Amazon.com-Inc./26/java.base/java/util/Collection.java
```

In the first implementation, `lathe-source://` is only a presentation layer backed by the already-extracted source
file.
The VS Code extension can translate returned cache `file://` locations to `lathe-source://` before opening them.
A later server capability can let clients advertise `lathe.externalSourceUriScheme=lathe-source`,
after which the server may return `lathe-source://` directly for dependency/JDK source locations.
The storage model stays unchanged until Lathe can read source archives lazily.

### Find-references

**Open-file AST scan** — scan the cached `CompilationUnitTree` of each open file across all modules with open files.
Trigger a compilation pass for files with empty cache.
In the current single-worker model, scans that read javac-backed cached analysis run on the server worker.
Parallel per-module scans are a future optimization if profiling shows find-references latency needs it.

**Closed-module Class-File API scan** — parallel scan of `.class` files in both `.lathe/<rel>/classes/` and
`.lathe/<rel>/test-classes/` across all reactor modules with no currently-open files, using virtual threads.
`InvokeInstruction` and `FieldInstruction` entries identify references.
`LineNumberTable` gives line numbers from bytecode.
Modules with no `.lathe/<rel>/classes/` are silently skipped.

**Limitations** — surfaced explicitly:
- After an API change in a dependency, closed-module `.class` files may reference the old method descriptor until `mvn
  process-test-classes` runs.
- Constants inlined by the compiler, `@PolymorphicSignature` targets,
  and `invokedynamic`-based method references are missed in closed-module scans.
- Partially-open modules have a blind spot: the open-document AST scan covers only the open files,
  and the bytecode scan skips the module entirely because it has open files.
  References in the remaining closed files of a partially-open module are not found.
  Closing all files in the module (or opening all of them) eliminates the gap.

### Opening dependency source files

`ExternalCompiler` handles source files outside any reactor module when their path is under a manifest
dependency source root, `jdk.sourceDir`, or a client virtual URI that resolves to one of those extracted files.
It owns a reusable `StandardJavaFileManager`, a temp source root, and a `ModuleAnalysis`.
For dependency sources it sets `CLASS_PATH` from the manifest's per-dependency classpath entries plus the dependency's
own JAR;
for JDK sources the classpath is empty.
The file being edited is copied to the temp source root before attribution so unsaved content is analyzed.
Diagnostics are published for opened dependency sources.
Attributed trees and semantic tokens are cached by external source URI until close, shutdown, or manifest reload.

### Hover

Served from the result cache.
`Trees.getPath()` at cursor → `Trees.getElement()` → `Trees.getTypeMirror()`.
Formats type signature and javadoc as markdown.
No recompilation.
If cache is empty, trigger a pass first.

**Origin label** — the hover response includes a `*source: …*` footer derived from `WorkspaceManifest.originLabel()`:

- **JDK types** — element's enclosing `ModuleElement` is found in `SYSTEM_MODULES` → `java.lang (JDK 21.0.7)`
- **Modular dependency types** — element's `ModuleElement` is found on `MODULE_PATH`;
  JAR path resolved from the class file URI → matched against `dependencySources[].jar` →
  `io.grpc (com.google:grpc-core:1.60.0)`; if no GAV match, module name alone is shown
- **Classpath dependency types** — class file URI resolved via `CLASS_PATH` → JAR path → GAV lookup →
  `com.google.guava:guava:32.0.0-jre`
- **Reactor types** — class file has a `file:` URI under `.lathe/<moduleName>/classes/…` →
  segment after `.lathe/` extracted → `dropwizard-jetty`
- If no origin can be determined, the footer is omitted.

### Semantic tokens

`TreeScanner` walk over the cached `CompilationUnitTree`.
Computed once per compilation pass and cached alongside the `CompilationUnitTree`.
Invalidated on next `didChange`.

The v1 legend covers tokens that require javac attribution to classify: enum constants, type parameters, annotations,
and methods or fields that carry `static` or `@Deprecated`.
Plain instance methods, instance fields, parameters, local variables, and type name references are not yet covered
and are a known gap — VS Code relies on semantic tokens for identifier-level highlighting because it uses TextMate
grammars rather than tree-sitter.

Full VS Code coverage requires adding `class`, `parameter`, and `variable` token types and widening `method` and
`property` to cover all instances, not just static or deprecated ones.
See [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) for the detailed plan.

### Formatting

`google-java-format` as a library.
Full file and range formatting.
`FormatterException` caught gracefully on syntax errors.

---

## 8. Remaining Features

The following features build naturally on the model established in sections 6 and 7.

### Diagnostics

Pushed via `textDocument/publishDiagnostics` after every compilation pass —
fast pass on `didChange`, full pass on `didSave` and `didOpen`.
On `didClose` — publish empty array to clear client display.

AP processor diagnostics are only updated by full passes.
During fast passes, AP-derived diagnostics remain frozen at the state from the last full pass.

### Code actions

**Add missing import** — when a type name is unresolved, search the type index for candidates (depends on type index, future work).
User selects one, import statement inserted.

**Organize imports** — sort and remove unused imports.
Pure source text manipulation.

### Document symbols

Walk the file's `CompilationUnitTree`, emit `DocumentSymbol` for each class, method, and field declaration.

### Workspace symbols

Prefix search on the type index — JDK types, reactor types, and JAR dependency types — regardless of
which modules are currently open.
Depends on the type index (future work).

### Refactoring strategy

Lathe should not try to clone the full Eclipse JDT refactoring stack.
Its core responsibility is to provide compiler-accurate facts from Maven's real javac state:
symbol identity, diagnostics, definitions, references, module ownership, source roots, test roots,
dependency/JDK source locations, and stale-workspace state.

Native LSP refactorings are limited to deterministic, fast edits where Lathe can produce a precise `WorkspaceEdit`
without design judgment or long-running iteration:

- rename local variables, parameters, private members, and tightly scoped in-module symbols
- add missing import
- organize imports
- simple file-local or module-local source rewrites with clear symbol identity

Large or design-sensitive refactorings are delegated to agentic tools that can plan,
edit multiple files, run Maven/tests, interpret failures, and ask the user when architectural choices are needed:

- public API rename across JPMS reactor modules
- move type or split package/module boundaries
- migrate test frameworks or dependency APIs
- broad generated-source/AP-sensitive rewrites
- change-signature, extract-method, and inline operations when they affect multiple modules

To support those tools, Lathe exposes refactoring facts rather than trying to own every refactoring workflow:

- `lathe/symbolAt` — stable symbol identity at a position
- `lathe/references` — source and bytecode-backed references with known limitations
- `lathe/moduleGraph` — Maven reactor/module ownership and dependency edges
- `lathe/affectedModules` — modules likely affected by a symbol or file change
- `lathe/compileState` — stale manifest, missing params, diagnostics, and source availability
- `lathe/runTestsForSymbol` — post-v1 test selection built on the run/test/debug design

This makes Lathe a JPMS/Maven-accurate Java backend for editors and coding agents,
not a feature-for-feature clone of JDT LS.

### Rename (post-v1)

Native rename starts with local and in-module symbols.
Cross-module public API rename is an agent-oriented workflow that consumes Lathe reference/module facts,
applies edits, runs verification, and iterates.

### Inlay hints (post-v1)

Parameter name hints on method call arguments via `MethodInvocationTree` and `ExecutableElement.getParameters()`.

### Signature help (post-v1)

Enumerate overloads when cursor is inside method call parentheses.
Reuses the sentinel technique.

### Run, test, and debug (post-v1)

Editor-driven run/test/debug by spawning `mvnd` from the LS and attaching DAP to JDWP —
see [lathe-run-test-debug.md](planned/lathe-run-test-debug.md).

---

## 9. Distribution

### Server distribution

`lathe:sync` installs the server launcher that matches the Maven plugin version into
`~/.cache/lathe/servers/<version>/` when the launcher script is missing there (idempotent).
It then updates `~/.cache/lathe/current` to point at that version.

Upgrading the Maven plugin and running any build that reaches `process-test-classes` also updates the user-level
server installation.
Old versions are left in place; they are small and can be deleted manually.

Editor integrations stay thin.
They do not need to understand Maven or resolve Lathe artifacts;
they launch `~/.cache/lathe/current/lathe-launcher.sh` and let Maven keep that target current.

### Server upgrade and restart

When server distribution sync is implemented,
`lathe:sync` is the only component that installs server distributions and moves `~/.cache/lathe/current`.
The launcher does not poll for updates and does not run a restart loop.
It starts exactly one server process using the module path from the `current` distribution at process start.

The running server records its own implementation version.
When manifest server-version fields are implemented and the server reads `.lathe/workspace.json`,
it compares that version with `server.version` from the manifest.
If they differ, the server reports that the workspace was synced with a different Lathe server version.
The server should continue serving requests when the manifest schema is compatible,
but features that require a newer manifest schema may degrade with a clear message.

The restart policy belongs to the editor integration:

- **Neovim v1** — show a normal LSP message:
  `Lathe server updated. Restart the language server.`
  Users can run `:LspRestart`.
- **VS Code extension** — listen for a Lathe-specific server-update notification,
  stop the current `LanguageClient`,
  and start a new one from `~/.cache/lathe/current/lathe-launcher.sh`.
  The restarted client naturally picks up the new module path because `current` has already been updated by
  `lathe:sync`.

The custom notification payload is intentionally small:

```json
{
  "runningVersion": "0.1.0",
  "workspaceVersion": "0.2.0",
  "launcher": "/home/user/.cache/lathe/current/lathe-launcher.sh"
}
```

This keeps upgrade behavior explicit without teaching the shell launcher about workspace roots,
LSP initialization state, exit-code protocols, or crash-loop handling.

### Launcher script

The launcher starts `lathe-server`.
JDK compiler internals are exported/opened only to classpath javac plugins and third-party formatter modules that
require them.
It is installed by `lathe:sync` as part of the server distribution.

```sh
#!/bin/sh
exec java \
  # Classpath javac plugins (e.g. Error Prone via -Xplugin:) run in the unnamed module
  # and access javac internals directly. Without ALL-UNNAMED exports, didSave full passes
  # that replay -Xplugin:ErrorProne throw IllegalAccessError.
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  ... (all 10 packages) ...
  --add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  # google-java-format is a named module on the module path; use module-qualified exports.
  --add-exports jdk.compiler/com.sun.tools.javac.api=com.google.googlejavaformat \
  ... (9 packages, no processing) ...
  --add-opens jdk.compiler/com.sun.tools.javac.code=com.google.googlejavaformat \
  --add-opens jdk.compiler/com.sun.tools.javac.comp=com.google.googlejavaformat \
  --module-path /abs/.m2/.../lathe-server.jar:/abs/.m2/.../lathe-core.jar:... \
  -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer "$@"
```

The module path is a colon-separated list of absolute `.m2` JAR paths rendered by `ServerInstaller` at install time;
no staging `lib/` directory is created.
`LATHE_JVM_OPTS` support is a planned addition for users who need heap or GC tuning.

### Neovim

```lua
vim.lsp.config('lathe', {
    cmd = { vim.env.HOME .. '/.cache/lathe/current/lathe-launcher.sh' },
    filetypes = { 'java' },
    root_dir = function(fname)
        return vim.fs.root(fname, '.lathe')
    end,
})
vim.lsp.enable('lathe')
```

Static personal configuration — never project-specific, never committed.
The `current` symlink means this config does not need version-specific edits.

For dependency and JDK source files opened from the extracted cache,
the Neovim integration should mark buffers read-only at the editor layer rather than changing filesystem permissions.
It should also attach the already-running Lathe client silently so hover, definition, diagnostics, and semantic tokens
continue to work without prompting on each dependency source buffer.
The first version keeps `file://` buffers for maximum compatibility:

```lua
local function lathe_readonly_external_sources(args)
    local name = vim.fs.normalize(vim.api.nvim_buf_get_name(args.buf))
    local cache = vim.fs.normalize(vim.fn.expand('~/.cache/lathe'))
    if vim.startswith(name, cache .. '/deps/') or vim.startswith(name, cache .. '/jdks/') then
        vim.bo[args.buf].readonly = true
        vim.bo[args.buf].modifiable = false
        vim.bo[args.buf].bufhidden = 'hide'
    end
end

vim.api.nvim_create_autocmd({ 'BufReadPost', 'BufNewFile' }, {
    pattern = '*.java',
    callback = lathe_readonly_external_sources,
})
```

A later Neovim plugin may also support `lathe-source://` buffers by resolving URI content through a Lathe command and
attaching the already-running LSP client.
That is optional because editor-local read-only `file://` buffers already solve accidental edits.

### VS Code (post-v1)

A minimal `vscode-lathe` extension starts the launcher script and connects via LSP.
It surfaces `LATHE_JVM_OPTS` as a VS Code setting.
It requires disabling `vscjava.vscode-java-pack` — documented as a prerequisite.

VS Code should present dependency/JDK sources as read-only virtual documents using a `TextDocumentContentProvider`
registered for `lathe-source`.
The first implementation can be extension-local and still use the server's current extracted `file://` locations:

1. request definition from Lathe;
2. if the returned URI is under `~/.cache/lathe/deps/` or `~/.cache/lathe/jdks/`, convert it to `lathe-source://`;
3. open the virtual document;
4. map `lathe-source://` back to the extracted file path inside `provideTextDocumentContent()`.

The extension should set the document language to Java after opening the virtual document.
VS Code treats provider-backed documents as read-only, so no filesystem permission changes are needed.

When this behavior is proven, the extension can advertise a Lathe client capability:

```json
{
  "lathe": {
    "externalSourceUriScheme": "lathe-source"
  }
}
```

The server can then return `lathe-source://` locations directly for supporting clients while continuing to return
`file://` for clients that do not advertise the capability.

---

## 10. Testing Strategy

### Layer 1 — Unit tests (JUnit 5)

Compiler engine tests with no LSP involvement.
`JavacTask` initialization from params files, single-file compilation, class file writing, diagnostics.
Fast — milliseconds per test.

### Layer 2 — Integration (maven-invoker)

`lathe-maven-plugin` owns all integration and e2e tests.
Invoker runs `lathe:init` and `mvn process-test-classes` against real test projects under `src/it/`:

```
lathe-maven-plugin/src/it/
├── simple-module/
├── jpms-project/
│   ├── module-a/
│   └── platform/
│       └── core/
└── annotation-processing/
```

Each project has an `invoker.properties` declaring the goals to run and a `post-build.sh` verifying the output —
checking `.lathe/` was created, params files were written, class files copied to `.lathe/<rel>/classes/` and
`.lathe/<rel>/test-classes/`.
No Groovy scripts.

### Layer 3 — Neovim headless e2e

Bound to `post-integration-test` via the exec plugin.
Runs after all invoker tests succeed.
Neovim 0.11+ built-in LSP — no plugins required.

```
lathe-maven-plugin/src/it/neovim/
├── minimal_init.lua
├── harness.lua
├── run_tests.lua
└── tests/
    ├── diagnostics_spec.lua
    ├── definition_spec.lua
    └── references_spec.lua
```

Tests open files from the invoker-prepared `target/it/jpms-project/`, send LSP requests, assert responses using
`vim.wait()` polling — no fixed sleeps.
Exit code propagates to Maven.

### Running locally

```bash
mvn install -DskipTests                                          # build and install
mvn verify -pl lathe-maven-plugin                                # all layers
mvn verify -pl lathe-maven-plugin -DskipNeovimTests              # invoker only
mvn verify -pl lathe-maven-plugin -Dinvoker.test=jpms-project    # one project
```

### CI — GitHub Actions

```yaml
jobs:
  build:
    steps:
      - run: mvn install -DskipTests
      - uses: actions/upload-artifact@v4
        with:
          name: maven-repo
          path: ~/.m2/repository/io/github/ag-libs

  test:
    needs: build
    strategy:
      matrix:
        include:
          - invoker-test: simple-module
            skip-neovim: true
          - invoker-test: jpms-project
            skip-neovim: false
          - invoker-test: annotation-processing
            skip-neovim: true
    steps:
      - uses: actions/download-artifact@v4
        with:
          name: maven-repo
          path: ~/.m2/repository/io/github/ag-libs
      - run: |
          mvn verify -pl lathe-maven-plugin \
            -Dinvoker.test=${{ matrix.invoker-test }} \
            -DskipNeovimTests=${{ matrix.skip-neovim }}
```

---

## 11. What's Not Supported

**Full non-JPMS type discovery** — core classpath Maven projects work for features that replay captured javac params:
diagnostics, hover, semantic tokens, formatting, and many definition/member-access cases.
The deliberate gap is type-name completion and add-missing-import for reactor and dependency types.
Without `module-info.java`, `getModuleElement()` cannot enumerate a module's exported types because there is no named
module or JPMS exports boundary.
A fallback using `fileManager.list()` on `.lathe/<rel>/classes/` and dependency classpath entries would close this gap
by indexing public top-level classes.
That fallback is a focused community contribution target.

**Split-package support** — Lathe does not try to model or repair split-package behavior.
It relies on the captured Maven javac invocation and accepts what javac accepts.

**Gradle** — Lathe hooks into the Maven compiler plugin via the Plexus SPI.
Gradle support would require a separate Gradle plugin writing the same params file format.
The LS itself is build-tool-agnostic and would work unchanged.

**Lombok** — Lathe operates on source as written.
Lombok rewrites the AST during annotation processing in ways that conflict with Lathe's per-pass model.
Not advertised and not tested.

**Multi-JDK projects** — Lathe uses `$JAVA_HOME` consistently, matching Maven's behaviour.
Projects requiring multiple JDKs for different modules are not supported in v1.

**IDE plugins beyond Neovim and VS Code** — Lathe speaks standard LSP.
Any LSP-capable editor can connect using the launcher script.
