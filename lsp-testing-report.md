# Lathe LSP — Systematic Testing Report (Dropwizard + Helidon)

**Date:** 2026-09-29
**Lathe version:** 0.1.12 (published server/extension)
**Workspaces:**
- Dropwizard (`~/git/dropwizard`, 5.0.3-SNAPSHOT, ~37 modules) — captured on Corretto **25**
- Helidon (`~/git/helidon`, ~330 modules) — captured on Corretto **26** (`-Dversion.java=26`)

Both captured with `mvn clean test -Dlathe.capture.only=true` (tests **not** executed).

> **Status: RESOLVED (2026-09-30).** Both findings below were one bug and are now fixed — the
> generated-sources mirror is scope-split (main → `generated-sources`, test → `generated-test-sources`),
> mirroring the existing `classes`/`test-classes` split, in `LatheCompiler.mirrorGeneratedSources`
> (write) and `ModuleSourceConfig` (read), keyed off `LatheLayout.generatedSourcesDir(sourceTree)`.
> Verified by re-capturing Helidon with the fixed build: empty main mirrors dropped **71 → 2**, and the
> probe now resolves `def`/`sym` for `WebServerConfig` into the mirror and reports clean diagnostics on
> the previously-broken test converter. The 2 residual empty mirrors are a **separate, pre-existing**
> limitation — see [Residual](#residual-separate-pre-existing-limitation). Details per finding below.

## Method

Driven by the moat axes in `docs/planned/lathe-mcp-value-benchmark.md` (cross-module signature
change, overload-sensitive rename, implement-an-interface, safe removal, **generated-source use**,
who-ultimately-calls). Each axis was exercised as an LSP feature via the launcher-independent probe
`dev/explore.py` (plus a small read-only `rename` probe using `LatheClient` — rename has no
`explore.py` command). Parallel Explore sub-agents produced raw probe output; **every gap claim was
then independently re-verified against grep/find ground truth** by the author, because the sub-agents
produced several false positives (documented below).

Probes are read-only. The `rename` probe only requests a `WorkspaceEdit`; nothing is written to disk.

---

## Headline findings

| # | Severity | Area | Finding | Status |
|---|----------|------|---------|--------|
| 1 | **HIGH** | Generated-source capture | 71 of 93 Helidon modules have an **empty** `.lathe/<module>/generated-sources` mirror → their generated `*Config` / `*Config.Builder` / `*Impl` types are invisible to go-to-definition and workspace-symbol. Defeats Lathe's unique moat for most of Helidon. | ✅ Fixed |
| 2 | LOW–MED | Generated-source capture | Test-scoped generated sources (`generated-test-sources`) are mirrored into the **main** `generated-sources` folder and compiled with the main classpath → spurious "package … does not exist" diagnostics. | ✅ Fixed |

**Both are the same concrete bug** in `LatheCompiler.syncOutput` (deterministic — not caching, not
config; see [Root cause](#root-cause-one-bug-behind-both-gaps)), **now fixed** (see
[Resolution](#resolution)).

## Root cause (one bug behind both gaps)

`lathe-compiler/.../LatheCompiler.java` `syncOutput()` mirrors the compile's generated-sources dir
into `.lathe/<module>/`:

```java
final var outputDir = Path.of(config.getOutputLocation());
FileUtil.replaceDir(outputDir, moduleDir.resolve(outputDir.getFileName()));          // classes → "classes" / "test-classes"  (scope-derived ✓)
final var genSources = config.getGeneratedSourcesDirectory();
if (genSources != null && Files.isDirectory(genSources.toPath())) {
  FileUtil.replaceDir(genSources.toPath(), moduleDir.resolve(LatheLayout.GENERATED_SOURCES)); // ALWAYS "generated-sources"  (shared ✗)
}
```

`syncOutput` runs **once per compiler execution** — for `default-compile` (main; gen dir =
`target/generated-sources/annotations`) **and** `default-testCompile` (test; gen dir =
`target/generated-test-sources/test-annotations`). Confirmed in the capture log: **377 "capture
complete" lines for 282 modules** (~2 per module).

The **compiled-classes** mirror is written to a scope-specific directory (`outputDir.getFileName()` →
`classes` for main, `test-classes` for test — the same split the server models as
`SourceScope.MAIN`/`TEST`). But the **generated-sources** mirror is hard-coded to the single constant
`LatheLayout.GENERATED_SOURCES` ("generated-sources") for **both** executions. Since `default-testCompile`
runs *after* `default-compile` and `FileUtil.replaceDir` **deletes the destination before copying**,
the test compile always overwrites the main compile's generated-source mirror:

| test-gen dir at test-compile time | `replaceDir` effect on the mirror | result |
|---|---|---|
| **absent** (`Files.isDirectory` false → block skipped) | untouched — main gen survives | ✅ correct (the 22 "good" modules) |
| **present but empty** | wiped, nothing copied | **Gap 1** (71 modules) |
| **present with test-generated files** | main replaced by test-scoped sources | **Gap 2** (false diagnostics) |

Behavioral proof:

| Module | main gen | test-gen dir | `.lathe` mirror | outcome |
|---|---|---|---|---|
| `data/sql/common` | 2 | absent | 2 (main) | ✅ correct |
| `common/features/metadata` | 3 | absent | 3 (main) | ✅ correct |
| `webserver/webserver` | 19 (incl. `WebServerConfig`) | present, **empty** | **0** | Gap 1 |
| `webserver/graphql` | 1 | present, **2 files** | 2 (**test** converter) | Gap 2 |

**Not caching.** A re-run reproduces it identically (the collision is deterministic in the compile
ordering). Helidon's build cache is disabled (`.mvn/cache-config.xml` → `<enabled>false</enabled>`),
and the earlier failed first capture is irrelevant because the successful run did a full `clean`.
Dropwizard escapes it only because its sole generated module has no test-generated sources.

## Resolution

Fixed by giving the generated-sources mirror the same main/test split the classes mirror already has,
in three focused commits:

- **`LatheLayout.generatedSourcesDir(sourceTree)`** — a sibling of `paramsFileName`/
  `compiledStampsFileName` that maps `classes → generated-sources` and `test-classes →
  generated-test-sources`. The Maven source path is still read from `getGeneratedSourcesDirectory()`;
  only the Lathe-owned destination name is scope-derived.
- **`LatheCompiler.mirrorGeneratedSources`** (write) — mirrors each compile's output to its
  scope-specific dir, so `default-testCompile` no longer overwrites `default-compile`.
- **`ModuleSourceConfig.generatedSourcesDir()` / `searchRoots()`** (read) — resolves the scope-specific
  dir; the test scope also searches the main mirror, since test code can reference the module's main
  annotation-processor output.

**Verification** (re-captured Helidon with the fixed build, Corretto 26):

| Check | Before | After |
|---|---|---|
| Modules with generated Java but an empty main mirror | 71 / 93 | **2 / 93** (both a separate limitation — see below) |
| `def WebServerConfig` / `.Builder` | no definition found | resolve into `.lathe/…/generated-sources/WebServerConfig.java` |
| `sym WebServerConfig` | generated interface absent | `[Interface] WebServerConfig` + `WebServerConfigImpl` present |
| Test converter (`generated-test-sources`) | in main mirror, 7 false errors | in `generated-test-sources`, diagnostics clean |

Covered by unit tests (`GeneratedSourcesMirrorTest`, `ModuleSourceConfigTest`) and the `multi-module`
invoker fixture, all green.

### Residual (separate, pre-existing limitation)

The 2 modules still showing an empty main mirror (`common/common`, `data/codegen/parser`) are **not**
this bug: their generated Java lives under source roots *other than* the compiler's annotation-processor
dir — build-helper `generated-sources/templates/` (`Version.java`) and ANTLR `generated-sources/antlr4/`
(`MethodName.java`, `QueryParams.java`). Lathe mirrors only `getGeneratedSourcesDirectory()` (the
`…/annotations` dir), so plugin-added generated roots are not captured. Both were empty before and after
this fix. A follow-up could mirror all compile-source roots that fall under `target/generated-sources`.

Everything else tested — cross-module references, callers/callees, implementations, type hierarchy,
rename (incl. overload precision), definition into dependencies/JDK, workspace symbol (CamelHumps),
hover/javadoc, diagnostics on real sources, default-method references — **worked correctly**, on both
repos, at Helidon's 330-module scale.

---

## GAP 1 — Generated-source navigation broken for most Helidon modules (HIGH)

**Symptom.** On `webserver/webserver/.../WebServer.java` (which itself compiles clean —
`diagnostics: none`, so the compiler resolves the generated type):

```
def  WebServerConfig            → (no definition found)
def  WebServerConfig.Builder    → (no definition found)
hover WebServerConfig           → "interface WebServerConfig" (no javadoc)
sym  WebServerConfig            → only WebServerConfigSupport / …Blueprint / …Test
                                   (the GENERATED interface + Impl are absent)
```

**Contrast — a module whose mirror IS populated (`data/sql/common`):**

```
def ConnectionConfig  → /home/…/helidon/.lathe/data/sql/common/generated-sources/
                        io/helidon/data/sql/common/ConnectionConfig.java:51:18   ✓
sym ConnectionConfig  → [Interface] ConnectionConfig + [Class] ConnectionConfigImpl
                        both resolving into .lathe/.../generated-sources/         ✓
```

Same result for `ContextRecordConfig`, `LimitUsingConfig`, `FeatureMetadata` (all populated) vs.
`WebServerConfig`, `ListenerConfig`, `SniConfig` (all empty-mirror → generated type missing).

**Root cause.** See [Root cause (one bug behind both gaps)](#root-cause-one-bug-behind-both-gaps):
the test compile's generated-sources mirror clobbers the main compile's, because both write to the
same `.lathe/<module>/generated-sources`. The server indexes/navigates generated sources **via that
mirror**, *not* from `target/generated-sources` (which the *compiler* uses on the sourcepath — hence
clean compiles), so the wiped modules lose all navigation. Empty for 71 of 93 modules with generated
Java:

```
modules with generated main sources: 93   populated mirror: 22   EMPTY mirror: 71
```

The empty-mirror set includes the highest-value config surfaces:
`webserver/webserver` (19 generated files), `json/binding` (61), `validation/validation` (32),
`telemetry/opentelemetry-config` (26), `messaging` (16), `service/registry` (12),
`fault-tolerance` (11), `webclient/api` (10), `metrics/api` (9), `http/http` (5),
`config/config` (4), `common/common` (1), `http/media/*`, `security/*`, `webserver/observe/*`, …

The compiler recorded the correct generated root in `lsp-params-classes.json`
(`…/target/generated-sources/annotations`), and the 19 files exist there — so this is a **sync/mirror
completeness bug**, not a language-analysis bug. Either sync must populate the mirror for every
module, or the indexer/definition provider must also read `target/generated-sources` directly.

**Impact.** Go-to-definition, workspace-symbol, and hover-javadoc on generated builder/config types
fail for the majority of Helidon's public configuration API — precisely the "generated-source use"
axis the benchmark doc calls "unique vs a generic LSP".

**Repro.**
```bash
# empty-mirror module → def fails, sym misses the generated type
printf 'def 55:14\nsym WebServerConfig\n' | \
  python3 dev/explore.py ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/WebServer.java
# populated module → def resolves into .lathe/.../generated-sources
printf 'def 30:35\n' | \
  python3 dev/explore.py ~/git/helidon/data/sql/common/src/main/java/io/helidon/data/sql/common/SqlDriver.java
# quantify
find ~/git/helidon -type d -path '*/target/generated-sources'   # compare each to .lathe/<mod>/generated-sources
```

---

## GAP 2 — False-positive diagnostics on mirrored test-scoped generated sources (LOW–MED)

Opening `.lathe/webserver/graphql/generated-sources/…/GraphQlServiceTest_ExtensionPayload__GeneratedConverter.java`:

```
diagnostics: 7 error(s)
[ERROR] 42:95  package GraphQlServiceTest does not exist
[ERROR] 55:52  package GraphQlServiceTest does not exist
… (×7)
```

The file is generated under `target/generated-test-sources/test-annotations` (test scope) and
references its enclosing **test** class `GraphQlServiceTest`, but it is mirrored into the **main**
`generated-sources` folder and compiled against the main classpath, where the test class is absent.
Scope (main vs test) is not preserved for generated sources in the mirror. Low user impact (few open
generated converters directly), but it is the same capture subsystem as GAP 1 and worth fixing
together.

---

## Verified strengths (no gaps found)

All checked against grep/find ground truth on real cross-module symbols.

- **Cross-module references / callers / callees** — Dropwizard `Managed.start/stop` (spans lifecycle,
  hibernate, db, metrics, migrations, health), `Environment.lifecycle()` (28 refs across client/core/
  hibernate/testing), `ServerFactory.build`. Precise on common names — `Managed.start` refs correctly
  **exclude** `Thread.start()`. Callees resolve into other modules and JDK.
- **Implementations / type hierarchy** — Dropwizard `Managed`, `ConfiguredBundle`, `ConnectorFactory`;
  Helidon `Weighted` (82 impls incl. transitive across ~20 modules), `MediaSupport` (correctly
  distinguishes `MediaSupport` from `MediaSupportProvider`), `ConfigSource`. Cross-module.
- **Rename (reactor-wide, with override family + precision)** — `ServerFactory.build` → 24 edits/11
  files incl. the two overrides. **Overload-sensitive**: renaming `DataSourceFactory.setUrl`
  correctly **excludes** the identically-named `DAOTest.Builder.setUrl` (a different method); renaming
  the *builder's* `setUrl` reaches `dropwizard-example` + `dropwizard-testing`, proving both modules
  are indexed.
- **Definition into dependencies & JDK** — Jetty, Jersey, Jackson, Tomcat-JDBC, Guava, Metrics
  (`~/.cache/lathe/deps/…`) and JDK sources (`~/.cache/lathe/jdks/corretto-…`) on both repos.
- **Definition into generated (when mirror is populated)** — works (see GAP 1 contrast).
- **Workspace symbol** — CamelHumps (`JCB`→JerseyClientBuilder, `WCB`→WebServerConfigBlueprint),
  workspace-first ordering, dependency/JDK types included, empty result for nonsense queries.
- **Hover / javadoc** — Markdown rendering with `@param`/`@see`, for local, dependency, and JDK APIs.
- **Diagnostics on real sources** — clean files clean across many modules; genuine warnings surfaced
  (error-prone-style checks in Dropwizard). No false positives on hand-written source.
- **Default-method references** — `ConfigSource.init()` → 3 refs incl. cross-module
  `ConfigSourceRuntimeImpl:236` (see false-alarm #2 below).
- **Scale** — Helidon 330-module reactor: server starts, indexes, and answers `sym`/`def`/`hover` in
  seconds; ~13–40 s first-server warm-up.

---

## Sub-agent false alarms (disproved — recorded to prevent recurrence)

The parallel agents skewed optimistic ("production-ready") **and** raised gaps that verification
refuted:

1. **"Rename misses 2 `setUrl` call sites" (claimed HIGH)** — FALSE. Those calls are on
   `DAOTest.Builder.setUrl`, a *different* method than `DataSourceFactory.setUrl`. Lathe's exclusion
   is correct overload precision — a moat win, not a miss. The agent grepped `.setUrl(` without
   checking receiver type.
2. **"`ConfigSource.init()` references = 0" (claimed HIGH)** — FALSE. Bad cursor position + no
   warm-up. With the cursor on the identifier it returns **3** references, stable across warm-up.
3. **"impl results capped / missing providers"** — display cap in the probe, not the server; the
   "missing" providers implement `ConfigSourceProvider` (a different type).
4. **"generated `Builder` inner class not indexed"** — artifact of querying `sym WebServerConfig.Builder`
   (dotted nested name); moot given GAP 1.
5. **"type hierarchy shows only direct subtypes"** — by design (one level at a time).

**Lesson for the probe harness:** cursor must land *inside* the identifier (0-based line/col; multi-line
signatures bite); first reference/impl call may need warm-up; always confirm receiver type before
calling a rename/refs exclusion a "miss".

---

## Tooling gaps found in the test harness (not the product)

- `dev/explore.py` has **no `rename` / `prepareRename` command**, so the headline rename feature can't
  be exercised without a custom `LatheClient` script. Worth adding a `rename`/`prepare-rename` probe.
- `refs`/`callers` display is capped with "… N more"; fine for the probe, but confirm the server
  returns the full set to real clients (it does — the cap is display-only).

---

## Suggested next steps

1. ✅ **Done** — GAP 1 + GAP 2 fixed (scope-split mirror); see [Resolution](#resolution).
2. **Follow-up (optional):** mirror plugin-added generated-source roots under `target/generated-sources`
   (build-helper `templates/`, ANTLR `antlr4/`), not just the annotation-processor dir — the
   [residual](#residual-separate-pre-existing-limitation) 2-module case.
3. Add a `rename` probe to `dev/explore.py` (rename currently has no probe command).
4. The upcoming **MCP** exercise reads the same capture: with the fix in place, `get_definition` /
   `search_symbols` / `describe_symbol` on Helidon generated types now resolve — worth confirming there.
