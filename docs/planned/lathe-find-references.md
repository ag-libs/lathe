# Lathe — Find References Design

Working design draft.
This document describes `textDocument/references` and the foundation it should leave for later rename/refactor work.

---

## 1. Goal

Find references should answer the editor question:

> Where is this exact Java symbol referenced in the workspace?

The first implementation should be exact for symbols it searches,
including sibling reactor modules that can legally reference the target through Maven/JPMS relationships.
It should prefer returning fewer correct results over broad textual matches.

The feature uses javac attribution as the source of truth.
Text scanning is only a candidate-discovery optimization.

---

## 2. Non-Goals

Find references v1 does not implement rename,
but it should produce the symbol identity,
candidate discovery,
scope planning,
and reference-location machinery that rename can reuse.
Rename still needs additional policy around comments,
strings,
file names,
constructors,
imports,
overrides,
and generated sources.

Find references v1 does not return references inside dependency or JDK sources.
Lathe may later support source browsing references for external sources, but normal project editing should focus on reactor source first.

Find references v1 does not implement hierarchy-aware semantic expansion.
For example, references to an interface method and references to overriding implementation methods are separate exact symbols unless a later
"find implementations" or rename design intentionally links them.
This is especially important for rename,
where hierarchy expansion must be an explicit policy decision rather than an accidental side effect of find references.

Find references v1 does not search comments, string literals, resource files, XML, or generated source output.

---

## 3. Existing Architecture

Current symbol features already have the important first step:

```text
LSP cursor position
  -> WorkspaceSession routes to the owning ModuleSourceWorker
  -> SourceAnalysisSession resolves the cached attributed file
  -> SourceLocator.elementAt(...) returns the javac Element at the cursor
```

Definition then maps that element to one declaration location.
Find references should reuse the same cursor-element resolution and add a workspace search phase.

The current server threading model matters:

- `lathe-worker` owns workspace state, open documents, routing, and stale checks.
- each `ModuleSourceWorker` owns one javac-backed `SourceAnalysisSession`.
- feature requests must not read `openDocuments` from module workers.

Find references therefore needs workspace-level orchestration in `WorkspaceSession`,
with per-module attribution work delegated to the relevant module workers.

The current `workspace.json` does not yet contain enough reactor module relationship metadata.
Find references v1 should add that metadata rather than defer sibling-module search,
because the same relationship graph is needed to make later rename practical.

---

## 4. Symbol Identity

The first step of every references request is resolving a stable target identity from the cursor.

Proposed model:

```java
record ReferenceTarget(
    String displayName,
    ElementKind kind,
    String declaringModuleRel,
    String declaringPackageName,
    String ownerBinaryName,
    String erasedDescriptor,
    String qualifiedName,
    SearchScope scope) {}
```

The concrete fields can be refined during implementation.
The important property is that the identity is derived from javac `Element` data,
not from source text.

### Type Symbols

For classes, interfaces, enums, records, annotation types, and type parameters:

- use the qualified name for top-level and member types
- use the declaring element path for local and anonymous types when needed
- search references by comparing attributed `Element` equality where possible

### Executables

For methods and constructors:

- include the owning type
- include name, parameter count, and erased parameter types
- treat constructors as the constructor symbol, not only the class symbol

Overloads must not collapse together.
`foo(String)` and `foo(Integer)` are different reference targets even when the source token is the same.

### Variables

For fields, enum constants, parameters, resources, exception parameters, lambda parameters, and locals:

- include the enclosing executable or type identity
- restrict locals and parameters to the declaring source file
- keep field searches wider according to accessibility

---

## 5. Search Scope

Search scope should be conservative and cheap before it becomes broad.

### Scope Rules

| Target | v1 Scope |
|---|---|
| local variable | declaring file only |
| parameter | declaring file only |
| lambda parameter | declaring file only |
| private field/method/type | declaring top-level source file only |
| package-private member/type | same package in the owning module source tree |
| protected/public member/type | owning module plus downstream reactor modules that can read or depend on it |
| exported dependency/JDK symbol | open files only, then no reactor-wide scan in v1 |

The sibling/downstream rule is intentionally part of v1.
Without it,
find references works for small single-module cases but fails the first time a public API is consumed by a neighboring module.
That would also make rename design suspect,
because rename needs the same search planning to know which files are even eligible for edits.

For public and protected symbols,
search the declaring module and all reactor modules whose captured Maven model can see the declaring module output.
In JPMS modules,
the candidate module must also read the declaring Java module and the declaring package must be exported when the target is outside the
declaring module.
Javac attribution remains the final authority,
so the relationship graph only decides where to spend search work.

Package-private symbols stay in the declaring package and declaring module.
Private members stay in the declaring top-level source file.

---

## 6. Reactor Relationship Metadata

Find references v1 needs the server to know which reactor modules can reference another reactor module.

The relationship graph is derived entirely on the server from the existing `lsp-params-*.json` files.
No changes to `workspace.json` or the Maven plugin are required.

### Derivation from classpath entries

Each `ModuleSourceConfig` records the raw classpath and modulepath written by the compiler.
`ModuleSourceConfig.remappedClasspath()` converts entries that live under the workspace root to their
`.lathe/` mirrors:

- `<module>/target/classes` → `.lathe/<module>/classes`
- `<module>/target/<artifact>.jar` → `.lathe/<module>/classes`
- `<module>/target/<artifact>-tests.jar` → `.lathe/<module>/test-classes`

Every `ModuleSourceConfig.latheClassesDir()` returns exactly one of those mirror paths.
The direct reactor dependencies of a config are therefore the remapped classpath entries that match
a known `latheClassesDir()` value from another config (self-references excluded).

This inference is always current because the compiler rewrites the lsp-params files on every build,
including incremental ones.
The one case it misses — a reactor module resolving from `~/.m2` during a partial build — is also the
case where `workspace.json` is not updated, so both approaches degrade equally there.

### Graph structure

The server builds an immutable relationship graph on workspace load:

```java
final class WorkspaceModuleGraph {
  static WorkspaceModuleGraph build(List<ModuleSourceConfig> allConfigs);
  List<ModuleSourceConfig> referenceSearchScope(ModuleSourceConfig declaring);
}
```

`build` derives direct dependencies from `remappedClasspath()` and `remappedModulepath()`,
then precomputes the transitive downstream set for each module dir.

`referenceSearchScope` returns all configs whose module dir is the declaring module dir or is
transitively downstream of it.
The declaring module's own configs are always included.

The graph is a planning aid,
not a semantic authority.
Every reported reference must still pass javac element matching in the candidate file.

### Transitive Downstream Search

For classpath projects,
search the declaring module and every transitive downstream reactor module.

Example:

```text
app -> service -> api
```

For a public symbol declared in `api`,
the search scope is:

```text
api, service, app
```

For v1,
JPMS projects use the same Maven dependency edges as classpath projects.
Finer-grained narrowing using `requires transitive` and `exportedPackages` is post-v1.

This graph still only selects candidate files.
If the graph is too broad,
javac element matching filters false positives.
If the graph is too narrow,
find references and rename can miss legal references,
so relationship metadata is part of v1.

For v1,
Maven dependency edges alone are sufficient for scope planning.
JPMS `requires`/`exportedPackages` narrowing is deferred post-v1 (see dropped Slice 5 note below).

### Live module-info.java (not needed)

A live server overlay for `module-info.java` edits is not required.
`ModuleSourceCompiler` uses `--patch-module` to compile each file,
writing the source to a persistent temp directory shared across all compilations for a module worker.
When `module-info.java` is opened or edited,
it is written to the patch directory and javac reads it as the live module descriptor for all subsequent compilations in that module.
Attribution therefore already reflects `module-info.java` edits without any server-side parsing or overlay.

The scope planning graph (which modules to search) still comes from the plugin-provided Maven edges and does not update automatically when `module-info.java` changes.
This is an acceptable limitation:
newly added `requires` declarations usually mean new code that does not yet have references to find,
and the search scope errs on the side of broad rather than narrow.

---

## 7. Candidate Discovery

Javac attribution is too expensive to run over every source file on every request.
Candidate discovery should be a text prefilter that only decides which files might contain references.

For each target:

1. derive one or more spelling candidates
2. scan source files in the planned search scope for those tokens
3. include open-document content from `openDocuments` instead of disk content
4. send only candidate files to module workers for attribution

Spelling candidates include the simple type name,
method name,
field name,
constructor type name,
and imported simple name where relevant.

The text scanner must be token-aware enough to avoid obvious substring noise.
`Customer` should not match `CustomerId` unless the searched name is actually `CustomerId`.

It does not need to parse Java.
False positives are acceptable because javac validation filters them out.

### Live Candidate Index

Reference search should maintain a lightweight live candidate index,
but not a second semantic compiler instance.

The useful live cache is textual and planning-oriented:

```java
record ReferenceCandidateIndex(
    Map<String, Set<String>> tokenToUris,
    Map<String, SourceFingerprint> fingerprints) {}
```

The index maps Java identifier tokens to files that currently contain them.
Open documents update the index from in-memory content.
Closed files can be indexed from disk and refreshed by file watchers or workspace reload.

This cache helps both references and future rename:

- quickly find files containing a type,
  method,
  field,
  or constructor spelling
- avoid disk-wide scans during every request
- keep open-document edits visible before save

It must not decide correctness.
The final result still comes from javac attribution and element matching.

The index is owned by `WorkspaceSession` and lives on the `lathe-worker` thread.
All reads and writes go through the `lathe-worker` executor.
LSP events (`didOpen`, `didChange`, `didClose`) dispatch debounced update tasks to `lathe-worker`.
File watcher events do the same.
`ModuleSourceWorker`s never access the index directly;
they receive immutable `ReferenceSearchFile` inputs and return matches.

### Background Warming

The server may warm reference planning in the background after edits:

1. update the current file's token index immediately
2. debounce a background task
3. identify likely symbol spellings near the cursor or edited token
4. compute likely candidate files from `ReferenceCandidateIndex`
5. optionally ask existing `ModuleSourceWorker`s to attribute candidate files lazily

Do not create a second full module source instance just for references.
The existing module workers should remain the single semantic authority for each module source.

If semantic reference matches are cached later,
the cache key must include at least:

- target identity
- candidate file URI
- candidate file content fingerprint or open-document version
- module source config identity
- module graph or live JPMS overlay version
- classpath/modulepath config version

For v1,
the required cache is the token candidate index.
Background semantic result caching should wait until synchronous references are correct and performance data shows it is needed.

---

## 8. Reference Matching

Each candidate file is attributed with the same javac invocation shape Lathe already uses for diagnostics and hover.
Then a scanner walks the `CompilationUnitTree` and compares resolved elements against the `ReferenceTarget`.

Proposed helper:

```java
final class ReferenceLocator {
  List<Location> references(AttributedFileAnalysis analysis, ReferenceTarget target, boolean includeDeclaration);
}
```

The scanner should visit the syntax forms that map to user-visible reference tokens:

- identifiers
- member selects
- method invocations
- constructor calls
- class declarations when `includeDeclaration=true`
- method and variable declarations when `includeDeclaration=true`
- imports, including static imports
- annotations
- method references, when completion gap J is eventually addressed

The returned range should cover the symbol name token,
not the whole expression.

For example:

```java
foo.bar().baz()
```

A reference to `bar` should cover only `bar`.

---

## 9. Open Documents

Open documents are authoritative.
If a candidate file is open, the search must use the in-memory content and version from `openDocuments`.

WorkspaceSession should create immutable search inputs on `lathe-worker`:

```java
record ReferenceSearchFile(String uri, String content, int version, ModuleSourceConfig config) {}
```

Module workers receive only these immutable values.
They do not read workspace state.

Stale protection is different from diagnostics:

- references are request/response, not published later
- if an open document changes while search is running, the result may be stale
- v1 can accept this race because the next user request will use the newer content
- a later cancellation design can drop work by request id

---

## 10. Rename Fit

Rename should build on this design,
but it must not be implemented as "find references plus text replacement".

Find references provides reusable pieces:

- `ReferenceTarget` for exact symbol identity
- `WorkspaceModuleGraph` for relationship-aware search planning
- token-aware candidate discovery
- javac-backed `ReferenceLocator` for exact source ranges
- open-document snapshot handling

Rename adds policy:

- validate that the target kind is renameable
- validate the new Java identifier or type name
- decide whether class rename also renames the file
- decide whether constructor declarations are renamed with type declarations
- decide whether imports are edited directly or left to import optimization
- decide whether overridden/overriding methods participate
- detect conflicts in the target scopes before producing a `WorkspaceEdit`
- return edits for open documents and disk-backed files through one consistent workspace edit builder

The design should therefore keep reference discovery result objects richer than plain `Location` internally:

```java
record ReferenceMatch(
    String uri,
    Range range,
    ReferenceRole role,
    ReferenceTarget resolvedTarget) {}

enum ReferenceRole {
  DECLARATION,
  READ,
  WRITE,
  INVOCATION,
  IMPORT,
  TYPE_USE
}
```

`textDocument/references` can map `ReferenceMatch` to `Location`.
Rename can use the same matches plus role information to decide which edits are legal.

---

## 11. LSP Shape

Advertise:

```java
capabilities.setReferencesProvider(true);
```

Add:

```java
LatheTextDocumentService.references(ReferenceParams params)
WorkspaceSession.referencesFuture(String uri, Position pos, boolean includeDeclaration)
ModuleSourceWorker.references(...)
SourceAnalysisSession.references(...)
```

Return `List<? extends Location>`.
Return an empty list when:

- the file is not open
- no module route exists
- no element exists at the cursor
- the target kind is unsupported
- no candidate files validate

Log at `FINE`:

```text
[references] file:///... 37ms target=foo candidates=12 hits=4
```

Do not log source lines or source content.

---

## 12. Implementation Slices

### Slice 1 — Exact Same-File References

Implement cursor target resolution and `ReferenceLocator` for the current attributed file only.
This proves element matching, token ranges, declaration inclusion, overloaded methods, fields, locals, parameters, and types.

`ReferenceTarget` is not needed for same-file matching.
Use javac `Element` equality directly;
all elements are resolved in the same `SourceAnalysisSession` javac context so identity comparison is reliable.
`ReferenceTarget` becomes necessary in Slice 2 when the scanner crosses `ModuleSourceWorker` boundaries.

### Slice 2 — Open Files in Same Module

Search all currently open files in the same module.
This makes the feature useful during active edits without requiring disk-wide work.

### Slice 3 — Same-Module Disk Search

Add token prefilter over same-module source roots.
Attribute only matching files and merge the results with open-file results.

### Slice 4 — Reactor Relationship Graph

Build `WorkspaceModuleGraph` on server load by deriving direct reactor dependencies from the
remapped classpath entries already present in each `ModuleSourceConfig`.
No plugin or schema changes are needed.
Extend `referencesFuture` to search transitive downstream modules for public and protected symbols.
Add `SearchScope` to `ReferenceTarget` so the search orchestrator knows whether to widen beyond
the declaring module.

### Slice 5 — Live JPMS Overlay (dropped)

Not required.
`ModuleSourceCompiler` already reads live `module-info.java` source via `--patch-module`,
so attribution reflects edits without any server-side parsing overlay.
Scope planning uses classpath-derived reactor edges and does not need an overlay for v1 correctness.
See section 6 for details.

### Slice 6 — Live Candidate Index ✅

`ReferenceCandidateIndex` maintains a token → `Set<URI>` map built synchronously from all source
roots on workspace load.
`update(uri, content)` keeps it current on `didOpen` and `didChange`;
`remove(uri)` handles close and delete.
`SourceFileScanner` was deleted — the index lookup replaces the per-request disk scan entirely.

### Slice 7 — Scope Tightening ✅

`SearchScope` has three values: `DECLARING_FILE`, `DECLARING_MODULE`, and `REACTOR_MODULES`.
Locals, parameters, and private members use `DECLARING_FILE` and short-circuit to the cursor file
only, with no index lookup and no disk reads.
Package-private symbols (`DECLARING_MODULE`) filter candidates to the declaring package directory
across all source trees of the module.
Performance caps (partial results, file-count limits) are deferred post-v1.

### Slice 8 — Rename Foundation ✅

`ReferenceLocator` returns `List<ReferenceMatch>` instead of `List<Location>`.
Each match carries a `ReferenceRole`: `DECLARATION`, `IMPORT`, `INVOCATION`, `TYPE_USE`, `READ`,
or `WRITE`.
Roles are derived from the AST context: assignment LHS → `WRITE`, method-select of a
`MethodInvocationTree` → `INVOCATION`, type element kinds → `TYPE_USE`, import trees → `IMPORT`,
declaration visitors → `DECLARATION`.
`ReferenceMatch` validates its invariants (non-blank URI, non-null range and role, non-negative
positions, start not after end) via `ValidCheck`.
`textDocument/references` maps matches to `Location` at the `WorkspaceSession` boundary;
rename will use the richer internal type directly.

---

## 13. Tests

Unit coverage should start at `ReferenceLocator` level with attributed fixture sources:

- type declaration and type-use references
- field declaration, reads, writes, and member selects
- overloaded methods
- constructors and `new`
- local variables and parameters scoped to one file
- imports and static imports
- `includeDeclaration=true` and `false`

Workspace-level tests implemented:

- `WorkspaceModuleGraphTest` — graph derivation, direct/transitive downstream, self-reference exclusion, multi-source-tree modules
- `ReferenceCandidateIndexTest` — build, update, remove, open-file override, deduplication
- `ReferenceLocatorTest` — roles (READ, WRITE, INVOCATION, IMPORT, DECLARATION, TYPE_USE), scope assignment, import false-positive regression

Workspace-level tests deferred post-v1:

- live `module-info.java` overlay updates search scope without Maven sync
- JPMS package export filtering before javac validation
- full integration test for private-member file scope and package-private package scope at the `WorkspaceSession` level
- empty result for missing route or unsupported cursor position (covered informally by explorer probes)

Rename-fit coverage is met: `ReferenceMatch` roles are tested at unit level.
`textDocument/references` behaviour is verified manually via `dev/explore.py`.

Regression tests should prefer small source fixtures over large integration projects.
Large project checks can use `dev/explorer` manually when troubleshooting performance or sorting behavior.

---

## 15. Known Performance Issue — JDK and Dependency Symbols

### Observation

Probed against `DropwizardResourceConfig.java` in the dropwizard workspace (916 source files, 36 modules):

| Symbol | Source | Hits |
|---|---|---|
| `String` | `java.lang` | 2355 |
| `Collections` | `java.util` | 210 |
| `Objects` | `java.util` | 111 |
| `ArrayList` | `java.util` | 51 |
| `UUID` | `java.util` | 29 |
| `Pattern` | `java.util.regex` | 24 |

For comparison, reactor-module symbols from the same file (`forTesting`, `getUrlPattern`) returned 5–31 hits — appropriate and fast.

### Root cause

Section 5 specifies that JDK and dependency symbols should be restricted to **open files only**,
with no reactor-wide scan.
This restriction was never implemented.

`ReferenceTarget.scopeFor` assigns `REACTOR_MODULES` to any `public` element regardless of whether
it comes from the reactor or from an external JAR.
`WorkspaceSession.referencesFuture` then plans the search using the cursor file's declaring module
as the root of the downstream graph.
For a file in a central module like `dropwizard-jersey`,
that graph includes most of the project.
The candidate index finds the token in nearly every source file,
and every file is attributed by javac.

`String` alone causes ~900 javac attributions per request.

### Proposed fix

Detect at search-planning time whether the target element belongs to a reactor module.
The simplest signal: the target's `qualifiedName` (the declaring class's binary name) can be resolved
against the reactor type index — if `WorkspaceTypeIndex` or the reactor shard map contains a matching
entry, the element is from the reactor; otherwise treat it as external.

If external, skip the candidate index lookup and the module-worker scan entirely.
Search only the currently open documents, matching the design doc's scope rule.

The fix lives in `WorkspaceSession.referencesFuture`, after the target is resolved,
before `searchFutures` is called.
`ReferenceTarget` does not need a new field;
the reactor-vs-external check is a planning-layer concern.

### Probe artifact — `Set` and `HashSet` returning identical results

`refs "Set"` in `explore.py` does a text substring search.
The first occurrence of `"Set"` in `DropwizardResourceConfig.java` falls inside a larger identifier,
causing the cursor to land on `HashSet` rather than `Set`.
Both probes therefore target the same element and produce the same results.
This is a probe usability gap, not a defect in the reference engine.
Use a more specific context string (e.g., `refs "import java.util.Set"`) to target the exact symbol.

---

## 14. Resolved Questions

- **Public type references in sibling reactor modules — v1 or post-v1?**
  Resolved as v1. `WorkspaceModuleGraph` derives reactor scope from classpath entries.

- **Should references include import statements?**
  Yes. `ReferenceLocator.visitImport` reports the final type name with role `IMPORT`.

- **Should constructor references be returned when finding references on the class name?**
  No. Class and constructor targets remain separate.
  `ReferenceTarget.from` produces distinct targets for `CLASS` vs `CONSTRUCTOR` element kinds.

- **Should overridden methods be grouped?**
  No. Exact find references only. Hierarchy-aware grouping belongs in a separate feature.

- **Should the server keep a semantic reference cache live?**
  Not for v1. `ReferenceCandidateIndex` is the live textual cache.
  Semantic result caching is deferred until performance data shows it is needed.
