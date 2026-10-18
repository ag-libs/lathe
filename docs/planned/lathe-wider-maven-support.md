# Lathe — Wider Maven Support (Maven 3.6.3+)

## Status

**Proposed.** Lathe's documented floor today is **Maven 3.9+** (`README.md`: "earlier 3.x releases are not supported").
Feedback from real users suggests that most of them run older Maven: distribution packages, wrappers pinned years ago, corporate images.
A spike on 2026-10-18 showed that Lathe can support **Maven 3.6.3+** with one small registration change and one defensive guard.
It also showed that today's behavior on older setups ranges from silent to build-breaking.

This document turns the spike into a design: the new floor, the fixes, and how Lathe detects an unsupported setup and tells the user what to do.

## Goal

1. Lathe works on **Maven 3.6.3 and newer**, with the same features as on 3.9.
2. **Lathe never breaks a user's build.** Where Lathe can't support part of a setup, the build still succeeds, Lathe skips that part, and it says so once, clearly.
3. **No silent failures.** A setup Lathe can't support is detected inside Maven where possible, and by the editor client and sync script where not. The message names the problem and the fix.

## Spike results (2026-10-18)

Setup:
- Maven 3.6.3 (archive checksum verified) on JDK 21.
- The `multi-module` invoker fixture, in a throwaway worktree with its own local repository and Lathe cache.
- Fixture clone adjusted to look like a JDK-21-era project on Maven 3.6: `maven-compiler-plugin` and Surefire pinned, because Maven 3.6.3's defaults (compiler 3.1, Surefire 2.12.4) can't build it at all.

### Finding 1 — on Maven before 3.9, Lathe silently does nothing

Maven loads the extension (its class realm is created), but `lathe:init` and `lathe:sync` are never injected, `.lathe/` is never created, and nothing is logged.

**Cause:**
- Maven discovers extension components through Sisu.
- Maven 3.6.3 ships Sisu 0.3.4, whose embedded ASM (built 2019-11-05) cannot read Java 21 class files (major version 65). A direct test shows its `ClassReader` throws `IllegalArgumentException` on `LatheLifecycleParticipant.class`.
- Sisu skips the component without reporting anything.
- Maven 3.9.16 ships Sisu 1.0.0 with ASM 9.9.1, which is why 3.9 works.

**Fix, verified:** register the participant in a Plexus descriptor (`META-INF/plexus/components.xml`), which Maven reads without parsing class files, *instead of* the Sisu index.
The participant has a no-arg constructor and no injected fields, so nothing else changes.

| Maven | Result with the Plexus descriptor |
|---|---|
| 3.6.3 | fixture verification suite 49/49 (LSP smoke 16, MCP smoke 9, multi-module checks 24); 6/6 compiler captures; test-launch capture |
| 3.9.16 | 49/49; `init` and `sync` each run exactly once (no double registration) |

### Finding 2 — `maven-compiler-plugin` older than 3.6.2 breaks the build

The compiler shim calls plexus-compiler APIs that old compiler plugins don't bundle:

| `maven-compiler-plugin` | Result |
|---|---|
| 3.1 (Maven 3.6.3's default binding), 3.5.1 | build fails: `NoSuchMethodError: CompilerConfiguration.getModulepathEntries()` |
| 3.6.0 | build fails: `NoSuchMethodError: CompilerConfiguration.isParameters()` |
| 3.6.2, 3.7.0, 3.8.1 | works |

This depends on the **compiler plugin version, not the Maven version**: a Maven 3.9 project that pins compiler plugin 3.1 breaks the same way today.
The user sees a wall of class-realm output that doesn't mention Lathe or what to do.

Mitigating context: a project building on JDK 21 effectively can't use compiler plugin 3.1 anyway, because its default `-source 5` is rejected.
The trap is a project that sets `maven.compiler.source`/`target` and leaves the plugin unpinned.

### Finding 3 — the API surface is already 3.6-compatible

- Lathe's Maven modules (`lathe-maven-extension`, `lathe-maven-plugin`, `lathe-install`, `lathe-openjdk-maven-plugin`) compile against `maven-core` / `maven-plugin-api` **3.6.3** without code changes.
- The only snag is a lint: 3.6.3's dependencies put Sisu's annotation processor on the compile path, and `-Xlint:all -Werror` turns its "no processor claimed `@Named`" warning into an error.
- The plugin descriptor currently declares `<requiredMavenVersion>3.9.15</requiredMavenVersion>`, derived from `maven-api.version`. Maven 3.6.3 ran the plugin anyway, but the descriptor should state the real floor.

### Other observations

- **Test capture works with Surefire 2.22.2**, the most common version of the Maven 3.6 era, as well as with 3.5.4.
- The fixture's `verify` module uses JUnit 6.1.3, which Surefire 2.22.2 can't run. That reproduces with Lathe disabled, so it's a JUnit requirement, not Lathe's.

## Decisions

### D1 — Register the extension through a Plexus descriptor, not the Sisu index

`lathe-maven-extension` ships `META-INF/plexus/components.xml`:

```xml
<component-set>
  <components>
    <component>
      <role>org.apache.maven.AbstractMavenLifecycleParticipant</role>
      <role-hint>lathe</role-hint>
      <implementation>io.github.aglibs.lathe.maven.extension.LatheLifecycleParticipant</implementation>
      <instantiation-strategy>singleton</instantiation-strategy>
    </component>
  </components>
</component-set>
```

- `META-INF/sisu/javax.inject.Named` is deleted. Keeping both is harmless on 3.9 (verified: one registration) but leaves two sources of truth.
- `@Named` / `@Singleton` stay on the class only if something else needs them; otherwise they go too.
- A test asserts the jar contains the descriptor and no Sisu index, so a future refactor can't silently reintroduce the Maven 3.6–3.8 no-op.

The plugin (`lathe-maven-plugin`) is unaffected: Mojos are described by `plugin.xml`, which `maven-plugin-plugin` generates, not by Sisu scanning.

### D2 — Compile against the floor: `maven-api.version` = 3.6.3

- The compile target *is* the floor, as the existing pin note says. Compiling against 3.6.3 makes any accidental use of a newer API a compile error, not a runtime surprise.
- `maven-plugin-plugin` then writes `<requiredMavenVersion>3.6.3</requiredMavenVersion>` into the descriptor.
- The Maven modules add `-proc:none`. They need no annotation processing: the Plexus descriptor (D1) and `plugin.xml` are hand-written or produced by plugins, not by processors. This matches what happens under 3.9 today.
- The existing Dependabot pin note for `maven-api.version` changes from 3.9.15 to 3.6.3; bump PRs are still closed.

### D3 — Never break the build: skip-and-warn on old compiler plugins

In `afterProjectsRead`, before injecting the shim into a project, the injector reads that project's effective `maven-compiler-plugin` version (POM-declared or default-bound; both appear in the model, per the original extension spike).

- **3.6.2 or newer:** inject as today.
- **Older, or a version it can't parse** (a property that didn't resolve): do **not** inject the shim for that module.
  The build compiles exactly as without Lathe.
  Lathe logs one warning per build that lists every affected module:

```
[WARNING] [lathe] maven-compiler-plugin 3.1 is too old for Lathe (needs 3.6.2 or newer).
[WARNING] [lathe] Your build is unaffected, but Lathe can't read these modules: core, app, health.
[WARNING] [lathe] Fix: pin a newer version in <pluginManagement>, for example:
[WARNING] [lathe]   <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
```

- The modules are also recorded in `workspace.json` as **skipped, with a reason**. The server can then show the reason when a file from such a module is opened ("Lathe can't analyze this module: maven-compiler-plugin 3.1 is too old…"), instead of a generic "no module found".
- The threshold (`3.6.2`) is a named constant in `LatheLayout.Maven`.

### D4 — Check the Maven version inside Maven

The participant reads the running Maven version (`RuntimeInformation`, available since Maven 3.0).

- **3.6.3 or newer:** proceed silently.
- **Older, but still able to load the extension (3.3.1–3.6.2):** proceed best-effort, and log one warning:
  *"[lathe] Maven 3.5.4 is older than Lathe's supported floor (3.6.3). Lathe may not work correctly. Upgrade Maven or the project's wrapper (`mvn wrapper:wrapper -Dmaven=3.9.11`)."*
  Lathe does not refuse to run: refusing would turn a probably-working setup into a certainly-broken one.
- The floor is a named constant next to D3's.

### D5 — Detect what Maven can't report, from the outside

Maven before 3.3.1 doesn't read `.mvn/extensions.xml` at all, so nothing inside the build can warn.
And any failure that leaves no `.lathe/` looks the same from the editor: "nothing happens".
The client and sync scripts close this gap:

- **Before a build Lathe starts** (sync script, guided setup): determine the effective Maven version from the wrapper's `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties`, or from `mvn -v`. If it's below 3.6.3, say so before running: *"Lathe needs Maven 3.6.3 or newer; this project uses 3.5.4 (from .mvn/wrapper). Upgrade the wrapper: …"*
  With the [Multi-OS](lathe-multi-os.md) D3 decision, this check lives once, in `SyncCommand`.
- **After a build, if `.lathe/` is still missing**, the client's existing "run the build" nudge adds what to check:
  *"If you already built: Lathe needs Maven 3.6.3+ (`mvn -v`) and prints `[lathe]` lines in the build log; if there are none, the extension didn't load. See the troubleshooting guide."*
- **Version-skew protection** (an old `.mvn/extensions.xml` pin) is unchanged and out of scope here.

### D6 — Documented support

| Component | Floor | How it's enforced |
|---|---|---|
| Maven | 3.6.3 | D4 warning inside Maven; D5 pre-flight and post-build hints outside |
| `maven-compiler-plugin` | 3.6.2 | D3 skip-and-warn, per module |
| Surefire (test capture) | 2.22.2 observed working | capture is best-effort; no new guard |
| JDK running the build | 21 | unchanged |

`README.md` and `docs/guide/installation.md` change "Maven 3.9+" to "Maven 3.6.3+", list the compiler-plugin floor, and add a troubleshooting section built around the messages above.

## Testing

- **CI matrix:** add **Maven 3.6.3** and **3.8.8** rows that run the invoker tests, next to 3.9 and 3.10.
  The fixture already pins its plugins for the 3.9 defaults. The older rows need the compiler plugin and Surefire pinned, so the fixture's root POM pins them explicitly, the way a real project on old Maven must.
- **New invoker fixture `old-compiler`:** a small project whose compiler plugin is 3.1, with `maven.compiler.source`/`target` set. It asserts:
  - the build succeeds;
  - the D3 warning appears, naming the module;
  - `workspace.json` records the module as skipped with its reason;
  - no `lsp-params` is written for it.
- **Unit tests:** compiler-plugin version comparison (D3), including unresolved properties and odd version strings; Maven-version gating (D4); the jar-content check (D1).
- **Wrapper-version detection (D5)** is a pure function over `maven-wrapper.properties`, unit-tested on samples.

## Delivery slices

1. **Registration and compile floor** (D1, D2): the Plexus descriptor, `maven-api.version` 3.6.3, `-proc:none`, the jar-content test, and the Maven 3.6.3 / 3.8.8 CI rows.
   **Exit:** the invoker tests pass on 3.6.3, 3.8.8, 3.9 and 3.10.
2. **Never break the build** (D3): the compiler-plugin guard, the warning, the skipped-module record in `workspace.json`, and the `old-compiler` fixture.
   **Exit:** a compiler plugin 3.1 project builds green with a clear warning.
3. **Tell the user** (D4, D5): the Maven-version warning, the server message for skipped modules, the sync pre-flight check, the client nudge text, and the docs (D6).
   **Exit:** each unsupported case in the D6 table produces its message, and the docs show the new floor.

Slice 1 alone already turns Maven 3.6–3.8 from "silently nothing" into "works".
Slice 2 removes the only build-breaking case found.

Rough effort: slice 1 about 1 day, slice 2 1–2 days, slice 3 1–2 days.

## Relationship to other designs

- **[Multi-OS Design](lathe-multi-os.md):** the CI matrix grows along two axes, OS and Maven version. D5's pre-flight check lives in `SyncCommand` (multi-OS D3) and uses its tool resolver (D9) to find the wrapper.
- **[Personal Setup](../potential/lathe-personal-setup.md) (potential):** with real users on older Maven, `-Dmaven.ext.class.path` (works since Maven 3.0.2) is the main personal-mode path, and `~/.m2/extensions.xml` (Maven 3.10+) the minority path. D1 matters for personal mode too: the extension must load on old Maven whichever way it is registered.
- **[Gradle support](lathe-gradle-support.md):** unaffected.

## Open questions

1. **Maven 3.3.1–3.6.2:** worth a CI row, or warn-only (D4)? 3.6.3 is from 2019, and older versions are rare even among old setups.
2. **`maven-compiler-plugin` 3.6.2 vs 3.7.0:** 3.6.2 worked in the spike. Should the floor be the oldest that works, or a round, well-known version?
3. **Plugins between Maven and Lathe that read the compiler configuration** (Error Prone setups, Lombok, `maven-processor-plugin`): they behave the same as on 3.9, but haven't been tested on an old Maven. A real older-Maven project from a user would be the best next check.
4. Should the skipped-module reason (D3) also be surfaced through the MCP server's `get_diagnostics` responses, so agents see it too?
