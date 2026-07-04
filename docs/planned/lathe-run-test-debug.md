# Lathe — Run, Test, and Debug

Post-M3 design **and** implementation/testing plan, merged into one document.
Builds on `lathe-design.md` (especially §5 Compiler Shim and §6 Module Model).
Adopted after the Neovim-focused Maven Central release is stable.

Design intent lives in §1–§13; the module split and build order live in §14–§16.

---

## 1. Principle

Lathe runs and debugs tests **against the bytecode it already maintains in `.lathe/`**, with no Maven
recompilation and no reactor rebuild per invocation.

It does this by **capturing** the exact JVM launch Surefire (or Failsafe) computes for the test fork —
the modular command line, JPMS flags, classpath/modulepath partition, and system properties — and then
**replaying** a fresh JVM from that captured template, with reactor output paths rewritten from Maven's
`target/` to Lathe's `.lathe/<rel>/`.

This is the same capture-by-interposition philosophy as the compiler shim (`lathe-design.md` §5),
applied to execution — and, as of this design, applied the **same way**: as a **push** capture that
rides the user's normal build, not a pull capture the server drives.

### Two invariants that shape everything

- **Lathe never runs Maven.** Capture rides the user's real `mvn test`; replay is a plain JVM the
  server spawns against `.lathe/`. Maven is the sole source of truth for the launch line. The server
  stays a pure reader (`lathe-design.md` §6) — it reads `.lathe/`, it does not orchestrate builds.
  Every execution path Lathe cannot faithfully capture-and-replay is a **documented limitation** whose
  escape hatch is "run Maven yourself" (§11) — never a server-driven Maven delegation.
- **The default fork is the faithful launch.** Surefire's default (`forkCount=1`) forks a real JVM,
  and for a modular project that fork is a correct modular launch (`--module-path` / `--patch-module`
  / `--add-*` partition) — exactly what replay needs. So riding the fork the build already makes is
  both free and JPMS-correct. `forkCount=0` (in-process) has no launch line and is not replayable
  (§11).

### Why capture-replay and not "just run Maven"

Delegating each run to `mvnd -pl <mod> test` forces a bad choice: without `-am` the test resolves
upstream modules from a `target/classes` Maven only refreshes on an explicit build (**stale**); with
`-am` Maven rebuilds the whole upstream reactor every run (**slow**). Capture-replay dissolves this by
rewriting reactor output paths to `.lathe/<dep-rel>/classes` — the copies Lathe keeps current
incrementally (shim copy on every build, LS save passes per file, lock protocol guarding mid-copy
reads). A replayed run gets `-am`-level freshness without `-am`'s recompilation.

`.lathe/<dep-rel>/classes` is exactly as fresh as Lathe's *compilation* view of that module — current
for anything edited/saved or built by Maven, behind only for a reactor dependency changed on disk
out-of-band with no build and no editor touch. A replayed run is never *more* stale than the
diagnostics the user already trusts.

That bounds **staleness**. **Completeness** — whether `.lathe/` holds a whole runnable image — is a
separate invariant (§4.4): a run can be perfectly fresh yet incomplete, and an incomplete image is the
more dangerous failure because it can report a green test for the wrong bytecode.

---

## 2. Architecture overview

Three stages, decoupled in time.

```
CAPTURE (push — rides the user's mvn test)      REPLAY (per run/debug, Maven-free)
────────────────────────────────────────       ─────────────────────────────────────
user runs: mvn test  (<jvm>=shim, pluginMgmt)   server reads .lathe/<rel>/test-launch.json
  Surefire forks the test JVM →                   → rewrite reactor target/ → .lathe/
  the shim (its bin/java) runs:                    → strip jacoco agent + main class/booter args
    snapshot argv+argfile+env → .lathe/<rel>/      → keep JPMS flags verbatim
    then exec real java → TESTS RUN NORMALLY       → add runner + test selection
  server parses the bundle → test-launch.json      → java … (fresh JVM)
                                                       • JDWP-attachable (debug)
                                                       • real-time results via NDJSON
                              DISCOVERY (source-derived, no Maven)
                              ─────────────────────────────────────
                              AST walk over cached CompilationUnitTree
                              → runnables (main / test-class / test-method)
```

- **Capture** rides the fork Surefire already makes; the shim writes a raw bundle into
  `.lathe/<rel>/` and *execs real java so the tests run*. The server parses the bundle into
  `test-launch.json` — a peer of the compiler shim's `lsp-params-*.json`, stored/locked/read the same
  way (§3.5).
- **Replay** is the hot path: no Maven, no compilation, sub-second, driven from the template plus
  `.lathe/` bytecode.
- **Discovery** is independent and always source-derived.

---

## 3. Capture — the push `jvm` shim

### 3.1 Surefire's `jvm` contract (validated against Surefire 3.5.5)

`-Djvm=<path>` overrides the forked test JVM. Surefire validates that path before forking
(`AbstractSurefireMojo#getEffectiveJvm`, `SystemUtils`):

1. `endsWithJavaPath` — the file name starts with `java`, its parent is `bin`. The shim lives at
   `<home>/bin/java`.
2. `toJdkHomeFromJvmExec` — JDK home is the walk-up from `bin/java`.
3. `toJdkVersionFromReleaseFile` — Surefire reads `<home>/release` to decide **modular vs. classpath**
   forking. A missing/misreported `release` silently degrades the fork to non-modular and would defeat
   capture. `<home>/release` **must** mirror the active build JDK.

So the shim cannot be a bare script anywhere — it lives inside a **synthesized JDK home** that mirrors
the active build JDK closely enough to pass validation.

### 3.2 The synthesized capture-JDK home

Installed by `lathe:sync` (idempotent), keyed to the `JdkSourceResolver`-resolved build JDK
(`JAVA_HOME`, else the build JVM's `java.home`):

```
~/.cache/lathe/capture-jdk/
├── bin/
│   └── java          ← the shim (a POSIX shell script)
└── release           ← ABSOLUTE symlink → $JAVA_HOME/release
```

`release` is the **single JDK anchor**, pointing at the JDK home `lathe:sync` resolves *exactly as it
does for JDK-source extraction* (`JdkSourceResolver.resolveHome`): **`JAVA_HOME` if set, else the
running build JVM's `java.home`**, canonicalized (`toRealPath`). So the anchor is correct even when
`JAVA_HOME` is unset. It serves two purposes at once: (1) Surefire reads it for the modular-vs-classpath
fork decision, and (2) the shim derives **real java** from it — `dirname(readlink release)/bin/java` —
so the JVM that runs the tests is, *by construction*, the exact JDK Surefire validated; the two cannot
diverge, and because the home came from `JdkSourceResolver`'s `JAVA_HOME`/`java.home` fallback, the
shim inherits that resolution without re-implementing it in shell. It must be an **absolute** symlink so
`readlink` yields an absolute JDK home. `sync` re-creates it idempotently; a stale symlink makes both
the fork decision and the exec'd JVM point *consistently* at the old JDK, never at mismatched ones.

### 3.3 The shim — self-locating, non-destructive, three modes

The shim is a **pure POSIX shell script**. In push mode nobody sets environment for it, so it is
**self-locating**: it derives real java from the `release` symlink's target — the exact JDK Surefire
validated, so the exec'd JVM cannot diverge from the fork decision — and finds its capture target by
taking the fork's working directory (Surefire forks with CWD = module basedir) and walking up to
`.lathe/`, deriving the module rel path — the same "locate the workspace" algorithm as the compiler
shim (`lathe-design.md` §5). The `$JAVA_HOME`/`PATH` fallbacks fire only when there is no `release`
symlink at all (sync never ran) — a state in which capture is already impossible — so in every working
configuration the exec'd JVM matches the one Surefire validated.

It never runs tests itself; it snapshots then **hands off to real java via `exec`** — same PID, same
argv (incl. the original `@argfile`), same fds/CWD/env, and `java.home` = the real JDK. The process
that runs the tests *is* real java running Surefire's exact launch, so a captured run is
indistinguishable from a normal one. Three modes, selected by environment:

| Env | Snapshot? | Then | Meaning |
|-----|-----------|------|---------|
| `LATHE_CAPTURE=0` | no | `exec` real java | opt-out (tests run, no capture) |
| *(default)* | yes | `exec` real java | **push**: capture **and** run tests |
| `LATHE_CAPTURE_ONLY=1` | yes | `exit 0` | capture, **don't** run tests (user-invoked refresh) |

`LATHE_CAPTURE_ONLY=1` is the destructive-capture behaviour, kept for an explicit
`LATHE_CAPTURE_ONLY=1 mvn surefire:test` "refresh the launch line without paying for a test run." It is
**user-invoked**, not a server command — the server never drives Maven.

Fork detection uses a **Lathe-owned marker, not a Surefire internal**: Lathe registers
`LATHE_CAPTURE_FORK=1` via Surefire `<environmentVariables>` (§3.4), which Surefire sets on the real
test fork's environment but **not** on its version/vm-info probe invocations. So the shim's detection is
`[ "${LATHE_CAPTURE_FORK:-0}" = 1 ]` — it depends on no Surefire class name (`ForkedBooter` is used only
as a corroborating sanity check in the parser, §3.5), needs no argfile expansion (that lives in the
server-side parser), and is reliable under `mvnd` because Surefire sets configured env per fork rather
than inheriting it from the shell/daemon. `<environmentVariables>` is a Map, so the marker survives
`pluginManagement` merge even when a module adds its own env vars.

The shim writes a **raw bundle** into `.lathe/<rel>/` (a `capture/` subdir):

- `capture.argv` — argv verbatim, one token per line.
- `capture.argfile.<i>` — a byte-for-byte copy of each `@file` token (kept alive because Surefire is
  blocked on the fork).
- `capture.env` — `env` dump.
- `capture.ready` — empty marker, written **last via temp-file + atomic rename**, so the server never
  observes a partial bundle (the same lock-deleted-last discipline as the compiler shim).

Reference shim:

```sh
#!/bin/sh
set -u
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Real java = the JDK whose `release` Surefire validated: `release` is an absolute symlink →
# $JAVA_HOME/release, so its target's dir is that JDK home. Derived (not a separate symlink) so the
# exec'd JVM cannot diverge from the JDK Surefire used — same active-$JAVA_HOME anchor as JDK-source sync.
jdk=$(dirname "$(readlink "$here/../release" 2>/dev/null)" 2>/dev/null)
real="$jdk/bin/java"
if [ ! -x "$real" ]; then                       # degraded fallback (sync never ran → no release symlink)
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    real="$JAVA_HOME/bin/java"
  else
    real=$(command -v java) || { echo "lathe: no real java found" >&2; exit 127; }
  fi
fi

[ "${LATHE_CAPTURE:-1}" = 0 ] && exec "$real" "$@"        # opt-out: run, never capture

# Is THIS the real test fork? Detect via a Lathe-owned marker set on the fork through Surefire
# <environmentVariables> (§3.4) — NOT the ForkedBooter class name. Surefire's version/vm-info probes
# don't carry it, so they pass straight through. No argfile expansion needed here (that's the parser's).
[ "${LATHE_CAPTURE_FORK:-0}" = 1 ] || exec "$real" "$@"    # probe / non-fork → transparent, in EVERY mode

# --- from here, this is the real test fork ---
out=$(...)                                                 # walk up from $PWD (fork CWD = module dir) to .lathe/<rel>/capture
if [ -n "$out" ]; then
  : # snapshot argv + each @argfile + env into "$out"; rename capture.ready in LAST (atomic)
fi

[ "${LATHE_CAPTURE_ONLY:-0}" = 1 ] && exit 0               # capture-only: captured, don't run tests
exec "$real" "$@"                                          # push default: run the tests
```

The marker check runs **unconditionally** (before the capture-only branch) so Surefire's probe
invocations pass through transparently in every mode — capturing a probe would either overwrite the
template with `-version` noise (default mode) or break the probe by exiting (capture-only mode). Because
the marker is Lathe-owned, a Surefire class-name rename in a minor upgrade cannot break detection.

**Fail-open is the load-bearing rule:** the shim sits on the user's *real* test path, so every path
that isn't capture-only ends in `exec "$real" "$@"` with the original argv. A snapshot failure means
"no fresh bundle this round," never a broken test run.

### 3.4 Registration — material, in `pluginManagement`

The Surefire/Failsafe change is **material and committed** — a `pluginManagement` block in the parent
POM, visible and reviewed like any other build config, symmetric with the compiler-shim block. The
machine-specific shim path stays out of the committed file by living in a property that **defaults to
empty** (which Surefire treats as "use the normal JVM") and is overridden per developer machine.

```xml
<properties>
  <!-- empty = normal JVM (CI, teammates without Lathe).
       A Lathe dev machine overrides this to ~/.cache/lathe/capture-jdk/bin/java. -->
  <lathe.capture.jvm/>
</properties>

<build>
  <pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <jvm>${lathe.capture.jvm}</jvm>
          <!-- Lathe-owned fork discriminator; Map-merges, so a module's own env vars keep it -->
          <environmentVariables><LATHE_CAPTURE_FORK>1</LATHE_CAPTURE_FORK></environmentVariables>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <jvm>${lathe.capture.jvm}</jvm>
          <environmentVariables><LATHE_CAPTURE_FORK>1</LATHE_CAPTURE_FORK></environmentVariables>
        </configuration>
      </plugin>
    </plugins>
  </pluginManagement>
</build>
```

`pluginManagement` config reaches the lifecycle-bound `surefire:test` / `failsafe:integration-test`
without an explicit `<execution>`, and it adds **no plugin dependency** (unlike the compiler-shim
block) — so committing it imposes nothing on CI/teammates: `${lathe.capture.jvm}` resolves to empty →
the normal JVM. A Lathe developer activates capture per machine (user `-D`/settings properties override
the empty POM default in interpolation):

- `~/.m2/settings.xml` active-profile property `lathe.capture.jvm = ~/.cache/lathe/capture-jdk/bin/java`, or
- `export MAVEN_ARGS="-Dlathe.capture.jvm=$HOME/.cache/lathe/capture-jdk/bin/java"`, or
- ad-hoc `-Dlathe.capture.jvm=<shim>`.

**Load-bearing assumption to verify (spike):** Surefire treats an empty `<jvm>` as "use the default
JVM." If a version does not, default the property to `${java.home}/bin/java` (the build JVM's own home —
always set, and matching `JdkSourceResolver`'s `JAVA_HOME`-else-`java.home` fallback) rather than
`${env.JAVA_HOME}` (which can be unset).

**Later simplification — a core extension (preferred for `jvm`, deferred).** An
`AbstractMavenLifecycleParticipant` (loaded via `.mvn/extensions.xml` or `-Dmaven.ext.class.path`) can
set `<jvm>` in `afterProjectsRead`, replacing the `pluginManagement`-property block entirely: it
computes the shim path at runtime (no machine-path-in-POM, no property indirection) and injects `<jvm>`
**only when the shim is installed** — inert-when-absent for free, no empty-default trick, no risk of
Surefire hard-failing on a missing path. Setting a config value needs **no plugin-realm dependency**
(unlike the compiler shim's `compilerId` injection), so this is the benign, low-risk kind of model
mutation — a cleaner fit for launch registration than for compile. **Deferred from stage 1** (it adds a
core-extension module + its delivery); stage 1 ships on the `pluginManagement`-property above, which
needs no new module. If adopted later, the extension may register `<jvm>` only and leave compile on the
POM (a valid asymmetry) or handle both.

### 3.5 Parsing, storage, and freshness

The **server** parses the raw bundle into `test-launch.json`, reusing `lathe-core` (`Json`, `IOUtil`,
the JDK argfile tokenizer) exactly as the compiler shim's params-writing reuses it — the shim stays
trivial shell, all parsing lives in Java. It:

1. Reads `capture.argv`; for each `@i` token, tokenizes `capture.argfile.<i>` with **JDK argfile rules**
   and splices in place → one flat, ordered token list.
2. Recognizes standard JDK flags → `modulePath`, `classPath`, `patchModules`,
   `addOpens`/`addReads`/`addExports`/`addModules`, and the `-D`/`-X` remainder → `jvmArgs`.
3. Splits at the **main class** — the first token that is neither a JVM option nor an option's value
   (the JVM's own `java [options] MainClass [args]` grammar) — keeping everything before it and
   discarding the main class and trailing booter args. This is **name-agnostic**: it does not depend on
   the `ForkedBooter` string. The captured main class is compared against `ForkedBooter` only as a
   corroborating **sanity check** (log a warning if it differs), never as the split mechanism.
4. Derives `javaHome` from the capture-JDK `release` symlink's target (the resolved JDK home — the same
   anchor the shim uses for real java), `surefireVersion` from the `surefire-booter-<ver>.jar` classpath
   entry, `mainModule` from the `patchModules` key.
5. Writes `.lathe/<rel>/test-launch.json` via `FileUtil.writeAtomically`, stamping `surefireVersion`
   and the freshness fingerprint (below).

`test-launch.json` is a **peer of `lsp-params-*.json`**: on disk in `.lathe/<rel>/`, lock-guarded (the
server waits while `lathe.lock` exists), and read fresh per request (no in-memory cache). The server
holds no persistent launch state; on each `lathe.run` it reads the file and checks freshness, mirroring
the "build-fresh-each-pass" contract.

`TestLaunchData` schema (new record in `lathe-core.schema`, immutable defensive copies, validating
compact constructor):

```json
{
  "schemaVersion": "1",
  "kind": "surefire",
  "surefireVersion": "3.5.5",
  "javaHome": "/opt/amazon-corretto-26/",
  "mainModule": "com.example.jpms",
  "modulePath": ["/ws/mod/target/classes", "/home/u/.m2/.../guava-33.4.0-jre.jar"],
  "classPath": ["/home/u/.m2/.../surefire-booter-3.5.5.jar", "/ws/mod/target/test-classes", "..."],
  "patchModules": { "com.example.jpms": "/ws/mod/target/test-classes" },
  "addOpens":   ["com.example.jpms/com.example.jpms=ALL-UNNAMED"],
  "addReads":   ["com.example.jpms=ALL-UNNAMED"],
  "addExports": [],
  "addModules": ["ALL-MODULE-PATH"],
  "jvmArgs":    ["-Dfoo=bar", "-Xmx512m"],
  "forkedMainClass": "org.apache.maven.surefire.booter.ForkedBooter"
}
```

Paths are recorded **as captured** (Maven's `target/`); the reactor→`.lathe/` rewrite happens at
replay so the rewrite logic lives in one place (§4.1). The `modulePath`/`classPath` partition is
recorded **exactly as Surefire computed it** — an automatic module promoted to the module path by a
`requires` lands on `modulePath` while its transitive plain jars land on `classPath`; no Lathe-side
heuristic reproduces this, capture inherits it. `addReads`/`addOpens` target `ALL-UNNAMED` (Surefire
runs its provider from the classpath), which is why replay needs no rewrite of them (§4.2).

**Freshness — when the template is re-captured.** The launch line depends only on **structural**
inputs, so a stale `test-launch.json` is refreshed only when one moves:

- **POM fingerprint** (module + inherited parents) via `workspace.json` `pomPaths` — Surefire config,
  `argLine`, and the dependency set derive from the POM.
- **`module-info.java` fingerprint** — a changed `requires` can move a dependency between module path
  and class path, i.e. change the very partition capture records.

Both are **content fingerprints, not mtimes** (the shim rewrites the bundle on every fork even when
unchanged). The template records the fingerprint it was captured under. Editing a test or main class
changes `.lathe/` bytecode but not the launch line, so the template stays valid and the inner loop
never re-captures. *Known gap:* an upstream reactor dependency **modularized in place** (its
`module-info` edited with no version bump) can re-partition this module's fork without tripping either
fingerprint — rare; surfaces as a launch/classpath error a manual re-capture clears, not a silent
green.

### 3.6 Shim robustness & bundle integrity

- **Fail-open** (above) under `set -u`: any snapshot failure skips `capture.ready`, never a half-bundle
  presented as complete, never a broken test run.
- **Atomic marker.** `capture.ready` is renamed in after the payloads; the server never consumes a
  partial bundle.
- **Whitespace/specials.** Every expansion is quoted; argv tokens are one-per-line (unambiguous — a
  single argv element cannot contain a newline).
- **Known limitation — newlines in `env`.** `capture.env` is newline-delimited, so a value containing a
  literal newline (rare) is captured incorrectly; `env -0` is not portable to mac/BSD. Best-effort,
  documented.
- **Shell-tested.** The capture path is not covered by the Java tests, so the shim has its own
  shell-level harness feeding known probe/modular-fork argfiles (incl. spaced paths) and asserting the
  exact bundle.

---

## 4. Replay — execute against `.lathe/`

Replay reads the template, applies a deterministic transform, and launches a fresh JVM. No Maven, no
compilation.

### 4.1 The transform

**Keep verbatim:** external (`~/.m2`, JDK) `--module-path`/`--class-path` entries, `--add-modules`,
`--add-reads`, `--add-opens`, `--add-exports`, and `jvmArgs` `-D`/`-X`.

**Rewrite reactor outputs to `.lathe/` (reuse `lathe-design.md` §6 remap):**

- `patchModules[mod] = <ws>/<rel>/target/test-classes` → `.lathe/<rel>/test-classes`.
- any `modulePath`/`classPath` entry equal to a reactor module's main `outputDir` →
  `.lathe/<dep-rel>/classes`; test `outputDir` → `.lathe/<dep-rel>/test-classes`.

**Strip:** the JaCoCo agent (`-javaagent:*jacoco*`); `forkedMainClass` and its trailing booter args.

**Substitute the executor:** append Lathe's test runner and the test selection (§4.3).

### 4.2 Why `--add-reads`/`--add-opens` need no rewrite

Surefire runs its booter and the JUnit provider from the **classpath** (unnamed module), so its
directives target `ALL-UNNAMED`. Lathe's runner also runs from the classpath, so it inherits those
grants unchanged — verified by replaying a real modular test with the captured directives applied
verbatim. **Do not** rewrite them to a named module.

### 4.3 Launcher, selection, and results

- **Launcher.** The captured `classPath` already contains `junit-platform-launcher` (Surefire's
  provider depends on it), so Lathe drives the JUnit Platform `Launcher` from a small bootstrap `main`
  it ships on the classpath (the `lathe-test-runner` jar, §14). Fall back to a bundled launcher if a
  future capture lacks it.
- **Selection.** Lathe drives `selectClass`/`selectMethod` directly, so a named run executes exactly
  the requested class/method. Discovery emits **erased parameter types** in the runnable `id` so
  overloaded/`@ParameterizedTest` methods select correctly. Package/module-wide runs select all
  discovered tests (see the fidelity gap in §9).
- **Results.** The bootstrap `main` registers a `TestExecutionListener` emitting one **NDJSON** record
  per event to a **dedicated results sink** (an append-only file in the session temp dir, path handed
  in via a system property) — never stdout, so program output can't corrupt the stream. The server
  line-reads it for near-real-time `testResult` events.

### 4.4 Completeness invariant — gating, not advisory

A launched JVM needs a **complete, runnable image** on disk (unlike compilation, which tolerates a
partial `.lathe/` plus open buffers). Replay **MUST verify completeness before launching** and refuse
(reporting so in the first `sessionEvent`) if it cannot — a run against an incomplete image can report
a green test for the wrong bytecode.

For every module the transform points into `.lathe/` (target + each reactor dependency on the rewritten
path):

1. **Build settled** — `lathe.lock` absent (or stale ≥ 2 min); else wait then re-check.
2. **Shim has run** — `lsp-params-classes.json` (and `-test-classes.json` for the target) exist.
3. **Output present** — each rewritten `.lathe/<dep-rel>/classes` / `test-classes` exists and is
   non-empty.
4. **Mirror, not merge** — `.lathe/<rel>/classes` is a full copy of the last compile with orphans
   already removed (`lathe-design.md` §5–6), so once (1)–(3) hold there is no partial-image state left;
   the server does not diff class-by-class.

If any check fails, replay does not launch. This gate is about **presence**, not currency — whether the
bytecode reflects the latest *saved* source is the "save, then run" concern handled by the LS save pass.

**Resources.** Build-time freshness is already handled: the compiler shim's bulk copy of
`target/classes` / `target/test-classes` includes Maven's *fully processed* resources — filtered,
`targetPath`-mapped, everything — because `process-resources` ran before the compile the shim rode.
Faithful for every config, no new code.

The gap is a **resource-only edit**: no `.java` is stale, so `maven-compiler-plugin` skips compilation,
the shim never fires, and `.lathe/` keeps the old resource. (The completeness gate above concerns
*presence*; this is *currency*.) It is closed simply: **the LSP always copies changed resources into
`.lathe/`** — no filtering detection, no gate.

The LSP watches each module's resource roots via `workspace/didChangeWatchedFiles` and **byte-copies**
`src/…/resources/<rel>` → `.lathe/<rel>/{classes,test-classes}/<rel>` on change (removes on delete).
This uses `workspace/didChangeWatchedFiles` — a **workspace** capability, independent of the client's
`java`-only document registration — **not** `textDocument/did*` (which never fires for non-`java`
buffers, and Lathe must not register for resource filetypes). So a `.properties`/`.sql`/`.yaml` save is
seen without treating it as an opened document. It fires on **disk save** (matching the "replay
reflects last-saved state" semantics — unsaved edits aren't copied). It requires client support for
`didChangeWatchedFiles` dynamic registration (modern Neovim 0.10+/VS Code have it); without it the
auto-copy degrades to "run `lathe:refresh-resources`/build." Lathe already uses this channel for deleted
`.java` sources (`lathe-design.md` §6). `lathe:sync` records each module's resource root dirs in
`workspace.json` so the LSP watches the right directories (default `src/{main,test}/resources`).

- For a **plain** (unfiltered) resource — the common case, and the one you hit editing a config file and
  re-running a test — this is **exact**: Maven copies plain resources verbatim too, so the byte copy
  matches.
- For a **filtered** resource (Maven `${…}`/`@…@` substitution, `targetPath`, `includes`/`excludes`),
  the copy is the *unfiltered* source — last-saved bytes, not Maven's substituted output. When faithful
  filtering matters, run **`lathe:refresh-resources`** — a Lathe goal that reuses Maven's own
  `MavenResourcesFiltering` to filter the module's resources **directly into
  `.lathe/<rel>/{classes,test-classes}`** (lock-guarded, like the shim), with **no compilation and no
  lifecycle phase** — far faster than `process-test-classes`, and faithful because the filtering is
  Maven's, not reconstructed. (A normal build refreshes them via the shim too.) The *user* invokes it,
  keeping the server free of Maven.

The prior design's LSP-driven `resources:resources` refresh is **dropped** (it ran Maven);
`lathe:refresh-resources` is the user-invoked, write-straight-to-`.lathe/` replacement.

---

## 5. LS command surface

Five `workspace/executeCommand` commands and one streaming notification. The server **never runs
Maven** here — it reads the template and spawns the replay JVM.

- **`lathe.runnables.list`** — given a file URI, a one-pass AST walk over the cached
  `CompilationUnitTree` returns runnables: `public static void main(String[])` for `main`; `@Test`,
  `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, JUnit 4 / TestNG `@Test` for tests. Each has
  `{ id, kind: main|test-class|test-method, label, moduleRel, uri, range }`; `id` is opaque-but-stable
  and carries erased param types for methods.
- **`lathe.run` / `lathe.debug`** — accept a runnable `id` (or `{ module, testPattern }` for
  package-wide runs). The server: reads `test-launch.json` (first parsing a newer capture bundle into it
  if the shim produced one); if the template is **missing or stale** (freshness gate, §3.5) it does
  **not** capture — it returns *"Run `mvn test` to (re)capture module `<rel>`"* (mirrors the compile-side
  "activate module" prompt); otherwise it applies the replay transform + selection, verifies completeness
  (§4.4), for `debug` injects JDWP and starts the in-process DAP adapter (§7), spawns the JVM, and
  returns `{ sessionId, kind }` (debug adds `{ dapHost, dapPort }`), streaming via `lathe/sessionEvent`.
- **`lathe.session.cancel` / `lathe.session.list`** — `cancel` destroys the replay child process by
  `sessionId` (a plain child now — no `mvnd` to detach from); `list` returns live sessions. All are
  cancelled on LS shutdown.

---

## 6. Streaming events — `lathe/sessionEvent`

- `"notice"` — `{ level: "info"|"warn", message, command? }`, an advisory emitted before/at
  `"started"` — the "not replayable here — run Maven" directives (`forkCount=0`, lifecycle IT — §11)
  and the no-template hint (§5). Carries an optional `command` the editor can offer to run (e.g.
  `mvn -pl <mod> lathe:refresh-resources` when the user wants faithful filtered resources).
- `"started"` — `{ pid, jdwpPort? }`, once the JVM is up (and, for debug, JDWP is listening).
- `"output"` — `{ channel: "stdout"|"stderr", data }`, the replayed program's own output verbatim (no
  Maven channel, no log filtering — Lathe launches java directly).
- `"testResult"` — `{ testId, status: passed|failed|skipped, durationMs, message?, stackTrace? }`, from
  the runner's NDJSON stream, per test, never interleaved with `"output"`.
- `"exit"` — `{ exitCode, elapsedMs }`, final.

---

## 7. Debug — JDWP and the in-process DAP adapter

Because Lathe builds the replay command line itself, debug is trivial: inject
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<port>` into the JVM args.

Lathe bundles Microsoft's `java-debug` (JDI-based) and hosts it **in-process** in the Lathe JVM,
mirroring jdtls. Attach-only suffices.

```
nvim-dap ◄──DAP/socket──► [Lathe JVM: java-debug in-process] ──JDI/JDWP/localhost──► [replay JVM]
```

Handshake: allocate JDWP + DAP ports → launch replay with `suspend=y` → scan replay stdout for
`Listening for transport dt_socket at address: <port>` → start the in-process `java-debug` DAP server
and JDI-attach → respond `{ sessionId, kind: "debug", dapHost, dapPort }` → editor connects nvim-dap;
JDI attach resumes from suspend. `suspend=y` guarantees no missed breakpoints; a 30 s timeout on the
scan emits `"exit"` with captured stderr.

---

## 8. Failsafe and integration tests

**Capture transfers for free.** Failsafe is Surefire's twin (same fork machinery, same `jvm` parameter,
same `<environmentVariables>` marker registered for it in §3.4), so the push shim intercepts
identically. No new capture code; `kind` is `failsafe`.

**The lifecycle is the problem.** ITs run between `pre-integration-test` and `post-integration-test`
(Maven-managed servers, containers, reserved ports). Replay does not run those phases, so:

- **Self-provisioning IT** (Testcontainers, embedded servers in `@BeforeAll`) — the test owns its
  environment; capture-replay works.
- **Lifecycle-provisioned IT** — replay is incorrect by construction. **Documented limitation: run
  `mvn verify`.** Lathe does not delegate.

Lathe cannot cheaply distinguish the two statically, so the safe default for `*IT` is to treat
standalone replay as explicit opt-in.

---

## 9. Discovery and routing

1. **What exists** — the AST walk (§5). Source-derived, no Maven.
2. **Unit vs. IT** — **route by convention** (`*Test`/`Test*`/`*Tests` → Surefire; `*IT`/`IT*`/`*ITCase`
   → Failsafe) and let the plugin filter be the judge. Custom include/exclude patterns are a documented
   fidelity gap.
3. **Selection fidelity** — exact for a named class/method (Lathe drives `Launcher` selection). The gap
   is package/module-wide runs, where matching Surefire's include/exclude/tag filtering would require
   reading its config — not reproduced, documented.

---

## 10. Fragility and guardrails

Launch capture depends on **Surefire/Failsafe internals**, not a public API — the primary risk.
(Compile capture, by contrast, rides the *supported* Plexus SPI and is structurally more durable; this
document's mechanism is the fragile tier.)

Internals relied upon (validated on 3.5.5, none contractual): the `@argfile` format and quoting;
`jvm`-path validation (`endsWithJavaPath`) + `release`-file version detection; the modular argfile
layout. Deliberately **not** relied upon: the `ForkedBooter` class name — fork detection uses a
Lathe-owned `<environmentVariables>` marker (§3.3) and the args-split uses the JVM's own command grammar
(§3.5), so a Surefire class-name rename in a minor upgrade breaks neither. Two assumptions the marker
adds — that Surefire applies `<environmentVariables>` to the real fork but **not** to its probes, and
that a Map config child survives `pluginManagement` merge — are spike-verified (§15).

**Why the `jvm` shim and not a supported SPI (checked).** Surefire's extension points do *not* reach
the launch: `<forkNode>` / `ForkNodeFactory` customizes only the fork's **communication channel** (it
is retrieved separately from `ForkConfiguration.createCommandLine(...)` and never sees the argv);
`MasterProcessChannelProcessorFactory` and custom `SurefireProvider`s run *inside* the already-launched
fork. The command line is built by an internal `ForkConfiguration` (three impls Surefire selects
itself) with **no SPI to override, replace, or observe it**. So `-Djvm=<shim>` is the only hook at the
launch point — and even a channel/provider hook would not help, since replay needs a standalone,
reusable launch template, not code running inside Surefire's own fork.

Guardrails (mandatory):

- **Pinned Surefire/Failsafe version matrix in CI** (§16 step 9) — an internals change fails in Lathe's
  own tests, not silently in a user's editor.
- **Version stamp** (`surefireVersion`) in `test-launch.json` → force re-capture on mismatch.
- **Capture-integrity** — `capture.ready` renamed in after payloads (§3.3), so "captured" is
  unambiguous.
- **Completeness gate before launch** (§4.4) — refuse rather than run the wrong bytecode.

There is **no delegation fallback** to guard: cases capture-replay cannot handle are documented
limitations (§11), not server-driven Maven runs.

---

## 11. Documented limitations (no server-side Maven)

The escape hatch for every case below is the user running Maven directly.

- **`forkCount=0`** — in-process execution builds no command line; `<jvm>` is ignored; tests run via
  `InPluginVMSurefireStarter` with an isolated `URLClassLoader` (no module layer), and
  `argLine`/`--add-*`/`--patch-module` are ignored. Not a replayable model. *Run/debug requires forking
  (Surefire default); use `mvn test`.*
- **No template yet** — fresh checkout, module never test-run, or habitual `-DskipTests`
  (`skipTests` compiles tests but skips execution → no fork → no template) / `-Dmaven.test.skip` (skips
  both). Run/debug is unavailable until one real `mvn test`; the server surfaces *"Run `mvn test` to
  enable run/debug for module `<rel>`."* A captured template then survives later skip builds (the launch
  line is structural).
- **Lifecycle-provisioned ITs** (§8) — *use `mvn verify`.*
- **Filtered resources** (§4.4) — the LSP always copies an edited resource to `.lathe/`, so the common
  "edit a config file, run a test" loop just works for **plain** (unfiltered) resources (exact byte
  copy). A **filtered** resource (Maven `${…}`/`@…@` substitution, `targetPath`, `includes`/`excludes`)
  is copied *unfiltered* — for faithful substitution *run `lathe:refresh-resources`* (fast — filters
  straight into `.lathe/`, no compile) or a normal build.
- **Package/module-wide selection** (§9) — Surefire include/exclude/tag filtering not reproduced.
- **Coverage** — JaCoCo stripped from replay; *use `mvn verify`.*
- **Fork mode** — capture targets the **default single reused fork** (`forkCount=1`,
  `reuseForks=true`). `forkCount>1` / `reuseForks=false` are **best-effort**: the shim is fork-count-
  agnostic (it snapshots whichever fork ran), and the template reflects one fork — harmless because
  replay is always a single JVM. `forkCount=0` is unsupported (above). `parallel` / fork parallelism is
  not reproduced (usually preferable for single-test inner-loop runs). The single-fork assumption trims
  the test/fidelity surface, not the shim code (the atomic-write discipline is needed regardless for
  crash-safety and cross-module `mvnd -T` concurrency).

---

## 12. Open issue — run/debug for `main` classes

**Not designed yet; parked deliberately.** Running a `main` must stay in the **push** model (no
server-driven Maven), and the obvious vehicle — `exec:exec -Dexec.executable=<shim>` — has two problems:

- It would need the server to drive `mvn exec:exec` (pull), which this design forbids; there is no
  natural push ride for `main` (users don't routinely launch via `mvn exec:exec`).
- `exec-maven-plugin` **does not support JPMS**: `%modulepath` is never substituted (broken through
  3.6.3; upstream PR #95 unmerged since 2018), and the structured `<modulepath/>` is buggy and needs a
  POM declaration.

Current lean: **run classes off the compile-time module-path partition Lathe already captures** — the
compiler shim's `lsp-params-classes.json` holds a Maven-computed *main compile* partition (remapped to
`.lathe/`) that, combined with the discovery `main` class, could form a modular launch with no
`exec:exec` at all. Both inputs are captured, not invented — **but** composing them re-introduces some
partition logic (compile-vs-runtime scope, runtime-only deps) and needs its own faithfulness spike.

Classpath `main` run+debug was spike-validated (July 2026) via `exec:exec` with no declaration, but
that path is pull; whether/how to offer it push is part of this open issue. **Revisit after test-run
ships.**

---

## 13. Editor integration

The LS exposes data and execution; editors own UI. All commands and `lathe/sessionEvent` are standard
LSP.

- **Neovim (primary):** a `neotest-lathe` adapter (~250 lines Lua) drives `neotest`
  (`discover_positions` → `runnables.list`; `build_spec` → `lathe.run`; `results` ← `testResult`
  events). `nvim-dap` (~30 lines) consumes `lathe.debug`'s `dapPort`. `filter_dir` keeps neotest out of
  `src/main/`, `target/`, `.lathe/`.
- **VS Code (deferred):** an extension maps the same commands onto `TestItem`/`TestRun`. No protocol
  change needed.

---

## 14. Module design (locked)

One new module; everything else routes into existing modules (a new module is justified only by a hard
deployment boundary — per `CLAUDE.md` KISS).

```
lathe-core → lathe-test-runner → lathe-compiler → lathe-server → lathe-maven-plugin
```

| Module | This feature adds | Notes |
|--------|-------------------|-------|
| `lathe-core` | `TestLaunchData` (schema), JDK argfile tokenizer, `LatheLayout` capture / `capture-jdk` constants, **capture-JDK synthesis helper**, **shim shell script (resource)** | shared by server + plugin; no new external deps |
| `lathe-test-runner` (**NEW**, leaf) | bootstrap `main`: JUnit Platform `Launcher` + `TestExecutionListener` → NDJSON | **zero runtime deps**; `junit-platform-launcher` `provided`; **no `lathe-core` dep** (reimplements NDJSON); **no `module-info`** (runs as unnamed module in the replay JVM) |
| `lathe-server` | parser, transform, freshness/completeness gates, discovery, commands, streaming, debug | embeds the runner jar; `java-debug` is a **stage-2-only** dep |
| `lathe-maven-plugin` | `lathe:sync` calls the synthesis helper (capture-JDK: `bin/java` + absolute `release` symlink, from the `JdkSourceResolver`-resolved home) and records per-module `resourceRoots` in `workspace.json` (dirs only — no filtering detection); **new `lathe:refresh-resources` goal** (filters resources via `MavenResourcesFiltering` straight into `.lathe/`, lock-guarded, no compile); invoker + shell-shim tests | + `maven-filtering` (`MavenResourcesFiltering`) |

**Runner → replay JVM.** `lathe-server` takes `lathe-test-runner` as a build dependency (reactor order
+ packaging), embeds its jar as a resource, and **materializes it to
`~/.cache/lathe/lathe-test-runner.jar` on first use** (a server-side materialize for *replay* — distinct
from the capture-JDK, which `lathe:sync` synthesizes). No runtime coordinate resolution, no JPMS
`requires` edge to the runner.

**Not extracted** (deliberately): the parse/transform/replay engine (coupled to the compile pipeline,
`.lathe/` layout, lock protocol), debug (in-process in the server JVM — gated by *deferring the
`java-debug` dependency to stage 2*, not by a module), and parallel-run orchestration.

**Note vs. the old pull design:** there is **no capture-driver** in the server — the shim writes the
bundle during the user's `mvn test`; the server only parses/reads it.

---

## 15. Implementation plan — build order with per-step tests

Conventions (from `CLAUDE.md`): build order above; JUnit 5 + AssertJ, `@TempDir`,
`methodName_condition_result`, no `@Nested`, positive + edge per behaviour; no Mockito in
`lathe-compiler`; reuse `lathe-server` compile-pipeline helpers (read ≥2 neighbours first); async via
`verify(client, timeout(N))`, never `Thread.sleep`; **approval gate** — any new public type/abstraction
STOPs for a design summary + "Approved"; `mvn spotless:apply` after any `.java` change.

**Ordering:** step 1 (`lathe-core`), step 2 (`lathe-test-runner`), and step 7 (discovery walk) are
mutually independent and may proceed in parallel.

### Stage 0 — gating spikes (throwaway, not production)

| Spike | Question | Pass criterion | Priority |
|-------|----------|----------------|----------|
| **S1** | the self-locating shim intercepts the modular Surefire fork on the supported version, execs real java so tests still run, and leaves a replayable bundle | one modular test runs green **and** a hand-replay of the parsed launch runs it green | **first — blocks step 3** |
| **S1b** | the `<environmentVariables>` marker (`LATHE_CAPTURE_FORK`) reaches the real fork but **not** Surefire's probes, and survives `pluginManagement` merge when a module sets its own env vars (§3.3) | marker present on fork, absent on probe; present after merge | **first — the detection mechanism** |
| S2 | `<systemPropertyVariables>` land in the argfile; configured env in `capture.env` (§3.6) | both observed | before "full fidelity" |
| S3 | `.lathe/<rel>/test-classes` carries filtered test resources + `META-INF/services` (§4.4) | resource-reading test passes under replay | before resource work |
| S4 | orphan `.class` removal leaves nothing stale (§4.4) | deleted-source class absent from `.lathe/` | before completeness sign-off |

Genericize S1's captured bundle to `com.example` and **vendor it as the parser's test fixture** — the
parser is tested against reality, not a guess.

### Stage 1 — steps

1. **`lathe-core` foundations** — *approval gate.* `LatheLayout` capture/`capture-jdk` constants;
   `TestLaunchData` record; JDK argfile tokenizer; **capture-JDK synthesis helper** (create the home +
   `bin/java` + an **absolute** `release` symlink; idempotent; takes the **resolved JDK home as input**
   from `JdkSourceResolver.resolveHome` — `JAVA_HOME` else `java.home` — not a re-read of `JAVA_HOME`);
   shim script as a resource. *Tests:* tokenizer vs. known argfiles incl. quoted/spaced (positive +
   edge); `TestLaunchData` invariants + reject-bad; synthesis produces an executable `bin/java` + an
   absolute `release` symlink from which the shim derives real java, under `@TempDir`.
2. **`lathe-test-runner`** (NEW leaf) — *approval gate.* Zero runtime deps, `junit-platform-launcher`
   `provided`, no `module-info`, no `lathe-core` dep. Bootstrap `main`: selectors from the runnable
   `id` (class/method + erased params), drive the `Launcher`, `TestExecutionListener` → NDJSON to the
   sink path from a system property. *Tests:* sample test class → expected pass/fail/skip NDJSON
   (positive); malformed selector fails cleanly (edge).
3. **Shim + synthesis wiring** — wire `lathe:sync` to call the step-1 helper (the server never
   synthesizes it — push-only, no server-driven capture). *Tests* (`lathe-maven-plugin`,
   `ProcessBuilder` + a capture-JDK whose `release` points at a stub JDK whose `bin/java` records argv):
   `LATHE_CAPTURE=0` → transparent, no bundle; no marker (probe) → passthrough; `LATHE_CAPTURE_FORK=1`
   (fork) → correct bundle then exec; `LATHE_CAPTURE_ONLY=1` + marker → bundle then exit, no exec; spaced
   paths survive; a forced snapshot failure writes **no** `capture.ready` and still execs real java
   (fail-open); `lathe:sync` synthesis produces a valid home.
4. **Bundle parser** (server) — *approval gate.* Raw bundle → `TestLaunchData`, reusing the tokenizer +
   `Json`. *Tests* (fixture-driven, the vendored S1 bundle in `@TempDir`): assert module/classpath
   partition, `patchModules`, `add-*`, `jvmArgs`, the **name-agnostic main-class split** (first
   non-option token), `javaHome`/`surefireVersion` derivation, and the `ForkedBooter` sanity-check
   warning when the main class differs. Missing structural landmark (no main-class token, no
   `patchModules`) → fail-closed.
5. **Freshness + completeness gates** (server) — freshness: POM + `module-info.java` content
   fingerprints (§3.5); completeness: the 4-step gate (§4.4). *Tests* (`@TempDir`): freshness — unchanged
   skips, each moved re-captures; completeness — lock present/stale/absent, missing params, empty output
   → launch vs refuse.
6. **Replay transform + launch** (server) — `TestLaunchData` + reactor layout → `java` argv (§4.1);
   materialize the runner jar (step 2) to `~/.cache/lathe/` on first use; append runner + selector;
   launch; line-read the NDJSON sink (§4.3). *Tests:* unit — `target/→.lathe/` rewrite, JaCoCo +
   main class + booter args stripped, `add-*` verbatim, runner+selector appended; invoker (existing modular
   `src/it` fixture) — a real `mvn test` under the shim captures, then replay runs one test green and
   NDJSON matches; `forkCount=0` → documented "run `mvn test`" (no crash).
7. **Discovery + commands + streaming** (server) — *approval gate for command shapes.* `runnables.list`
   AST walk (reuse compile helpers; `id` carries erased params); `lathe.run` / `lathe.session.*` /
   `lathe/sessionEvent`. *Tests* (reuse compile pipeline): discovery — fixture yields
   `main`/`@Test`/`@ParameterizedTest` (positive), nothing for a non-test class (negative); run —
   `verify(client, timeout()).sessionEvent(...)` for started/testResult/exit; cancel kills the process;
   missing template → the "run `mvn test`" message.
8. **CI guardrail** (`lathe-maven-plugin` invoker) — **pinned Surefire/Failsafe version matrix**;
   capture+replay green on each supported version (§10).
9. **Resource copy-on-change + `lathe:refresh-resources`** (§4.4) — `lathe:sync` records `resourceRoots`
   in `workspace.json`; the server registers `workspace/didChangeWatchedFiles` on them and byte-copies a
   changed resource into `.lathe/<rel>/{classes,test-classes}` (removes on delete) — no filtering
   detection; the `lathe:refresh-resources` goal filters via `MavenResourcesFiltering` straight into
   `.lathe/` with no compile. *Tests:* server — a watched-file change copies the file into `.lathe/`
   (positive), a delete removes it (edge), a client without watch capability degrades cleanly (no
   error); `lathe-maven-plugin` invoker — `lathe:refresh-resources` on the modular fixture filters a
   `${…}`/`@…@` resource correctly into `.lathe/` without compiling.

### Stage 2 / 3 — test shape

- **Debug (§7):** first add `java-debug` to `lathe-server` (stage-2-only). JDWP-arg injection needs no
  library (a string on the argv) and could land in stage 1. Unit-test injection; integration-test the
  suspend → scan-stdout → JDI-attach handshake (assert a breakpoint hits) as a slow test with a generous
  timeout. **JPMS check:** `java-debug` resolves against `lathe-server`'s `module-info`.
- **Failsafe (§8):** reuse the invoker harness with `failsafe:integration-test` + a self-provisioning
  IT.
- **`main` run/debug (§12):** blocked on the open-issue spike (compile-path partition).

### Testing layers

| Layer | Where | Covers |
|-------|-------|--------|
| Unit (pure) | `lathe-core`, `lathe-server` | tokenizer, `TestLaunchData`, synthesis, parser, gates, transform, discovery |
| Runner | `lathe-test-runner` | bootstrap `Launcher` → NDJSON |
| Shell harness | `lathe-maven-plugin` | the shim (opt-out/probe/fork/capture-only/spaces/fail-open) via `ProcessBuilder` |
| Invoker integration | `lathe-maven-plugin/src/it` | real `mvn test` under the shim → capture→replay, `forkCount=0`, CI version matrix |
| Server-request | `lathe-server` | commands + streaming (`verify(client, timeout())`) |
| Real-world e2e | `dropwizard` / `helidon` | large-codebase smoke |

**Tricky areas:** real fixtures over hand-written (vendor the S1 bundle); runner classpath hygiene
(zero-dep, no `lathe-core`, or a stray transitive dep shadows the code under test); shim fail-open
(harness must assert a forced failure still execs real java and writes no `capture.ready`); async, not
sleeps; debug-handshake flakiness (real port, generous timeout, integration-only).

---

## 16. Rollout sequencing

1. **Ship first — Surefire unit-test push capture-replay.** `runnables.list`, `lathe.run` for
   test-class/test-method, streaming `testResult`. The freshness win is real here and it stands alone.
2. **Fast-follow — debug + self-provisioning Failsafe ITs.** Debug rides the same replay (§7);
   self-provisioning ITs reuse the capture verbatim (§8).
3. **Resource currency (§4.4).** The LSP copies *any* changed resource into `.lathe/` on save — no
   filtering detection (the common "edit a config file, run a test" loop). `lathe:refresh-resources` is
   the documented, fast way to get faithful *filtered* content into `.lathe/` when a filtered value
   matters. Small and high-value — land it with or right after stage 1.
4. **Deferred — package/module-wide selection fidelity** (§9) and **`main` run/debug** (§12, open
   issue).

Two hardening items **gate stage 1** (both fail silently if skipped, both undermine a green result):
the **completeness invariant** (§4.4, verified before launch) and **capture integrity** (§3.3,
`capture.ready` after payloads). Resources: plain resources are copied on change by the LSP (§4.4);
filtered resources are Maven's job via `lathe:refresh-resources`; the dropped path is the LSP-driven
`resources:resources` refresh (it ran Maven).
