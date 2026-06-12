# Lathe — Code Actions: Design and Implementation Plan

This document describes the confirmed design for a robust and extensible `textDocument/codeAction`
implementation, replacing the current flat single-method handler.

---

## 1. Current State and Known Bug

### Two import-insertion paths

Lathe has two surfaces that insert an `import` statement into a file.

**Completion path** — when the user selects a type-index candidate from the completion menu,
`CompletionItemPresenter.applyImportEdits` attaches an `additionalTextEdits` entry to the `CompletionItem`.
This path works correctly.

**Code action path** — `SourceAnalysisSession.codeAction` looks at `CodeActionContext.diagnostics`,
extracts the simple name from each diagnostic's `data` field, queries `WorkspaceTypeIndex`,
and returns one `CodeAction` per candidate with a `WorkspaceEdit` containing the import edit.
**This path is broken in real editor sessions** due to the `JsonPrimitive` bug described below.

### The `JsonPrimitive` bug

`Diagnostic.data` in LSP4J is annotated `@JsonAdapter(JsonElementTypeAdapter.Factory.class)`.
The adapter always returns a `JsonElement` on deserialization — for a JSON string `"ArrayList"` it
returns `JsonPrimitive("ArrayList")`, not `String`.

The current handler checks `instanceof String`, which always fails on the round-trip.
No code actions are ever returned from a real editor session.

Unit tests pass because they compile and call `codeAction` in the same JVM with no JSON round-trip.

---

## 2. Design

### 2.1 `DiagnosticPayload`

A single record with a `Kind` enum and a `String name` field.
`name` carries the symbol the diagnostic is about — a type name, variable name, exception FQN,
or class name depending on kind.

```java
record DiagnosticPayload(Kind kind, String name) {
    enum Kind { TYPE_REF, VARIABLE_REF, UNREPORTED_EXCEPTION, MISSING_METHOD_IMPL }
}
```

Wire format: `{"kind":"TYPE_REF","name":"List"}` — flat, unambiguous, trivial to deserialize.

This record lives in the `analysis` package with zero JSON dependencies.
JSON conversion is handled exclusively in `WorkspaceSession` (the LSP boundary).

### 2.2 Classification: `enrichWithContext()` post-pass in `compile()`

`toLsp()` stays static and unchanged. A new post-processing step `enrichWithContext()` is called
after `filterAndMap()` in `compile()`, where the attributed analysis is available.

```
compile()
  → compiler.compile()             // raw javac diagnostics + fileAnalysis
  → filterAndMap(raw, content)     // toLsp: position mapping (unchanged)
  → enrichWithContext(diags, analysis)  // NEW: sets DiagnosticPayload on relevant diagnostics
  → return enriched diags
```

`enrichWithContext` switches on diagnostic code:

| Diagnostic code | Classification | Data source |
|---|---|---|
| `compiler.err.cant.resolve.location` | `TYPE_REF` or `VARIABLE_REF` | AST parent inspection |
| `compiler.err.unreported.exception.*` | `UNREPORTED_EXCEPTION` | Message string extraction |
| `compiler.err.does.not.override.abstract` | `MISSING_METHOD_IMPL` | Class name from AST |

For `cant.resolve`, the AST parent of the `IdentifierTree` at the diagnostic position determines the kind:

```
parent is VariableTree  (type position)  → TYPE_REF
parent is MethodTree    (return type)    → TYPE_REF
parent is ClassTree     (extends/implements) → TYPE_REF
parent is ParameterizedTypeTree          → TYPE_REF
null path or any other parent            → VARIABLE_REF (safe fallback)
```

### 2.3 JSON codec in `WorkspaceSession`

`WorkspaceSession` owns both directions:

**Write** — before `publishDiagnostics`, convert `DiagnosticPayload` → `JsonObject`:
```java
// {"kind":"TYPE_REF","name":"List"}
JsonObject toJson(DiagnosticPayload p) {
    JsonObject jo = new JsonObject();
    jo.addProperty("kind", p.kind().name());
    jo.addProperty("name", p.name());
    return jo;
}
```

**Read** — in `codeActionFuture`, extract payloads from `CodeActionContext` before passing to session:
```java
DiagnosticPayload fromJson(Object data) {
    if (data instanceof DiagnosticPayload dp) return dp;  // in-process / test
    if (data instanceof JsonObject jo) return new DiagnosticPayload(
        Kind.valueOf(jo.get("kind").getAsString()),
        jo.get("name").getAsString());
    return null;
}
```

`SourceAnalysisSession.codeAction()` signature changes to accept `List<DiagnosticPayload>` directly —
no JSON in the analysis layer.

### 2.4 Stale source

By `codeAction` time the file may have been edited. `ensureAttributedAnalysis` recompiles from
current document content — the same approach used by JDT.LS and rust-analyzer.
The payload carries only semantic context (kind + name), not offsets or version-specific positions.

### 2.5 `CodeActionProvider` interface

```java
interface CodeActionProvider {
    List<Either<Command, CodeAction>> provide(
        String uri,
        Diagnostic diag,
        DiagnosticPayload payload,
        AttributedFileAnalysis analysis,
        WorkspaceTypeIndex typeIndex);
}
```

`SourceAnalysisSession.codeAction()` becomes a dispatcher: extract payloads (via `WorkspaceSession`),
call the matching provider, deduplicate results by title.

```java
switch (payload.kind()) {
    case TYPE_REF             -> importProvider.provide(...)
    case VARIABLE_REF         -> declareProvider.provide(...)
    case UNREPORTED_EXCEPTION -> throwsProvider.provide(...)
    case MISSING_METHOD_IMPL  -> methodProvider.provide(...)
}
```

### 2.6 Shared utilities: `CodeActionSupport`

Package-private static utility class in the `analysis` package. Used by multiple providers:

- **Type name formatter** — converts `DeclaredType` to simple-name string
  (e.g. `java.util.stream.Stream<java.lang.String>` → `Stream<String>`).
  Handles declared types, primitives, arrays, wildcards, and type variables.
- **Enclosing method finder** — returns the `MethodTree` and its `TreePath` that encloses
  a given source offset. Used by `AddThrowsProvider`.
- **Class body end position** — returns the source offset just before the closing `}` of a
  `ClassTree`. Used by `MissingMethodImplProvider`.

### 2.7 Providers

Each provider constructs its own `ImportAnalyzer` when it needs to add imports.
All providers may emit compound `WorkspaceEdit` objects (primary edit + import insertions).

#### `ImportQuickFixProvider` — `TYPE_REF`

Extracts the existing `buildQuickFix` logic from `SourceAnalysisSession`.
Queries the type index with `payload.name()`, filters already-imported and inaccessible types,
returns one `CodeAction` per candidate with an import `WorkspaceEdit`.

#### `DeclareVariableProvider` — `VARIABLE_REF`

At `codeAction` time, finds the `AssignmentTree` at the diagnostic range position and
attempts to infer the RHS type via `trees().getTypeMirror(rhsPath)`.

- If type is a concrete `DeclaredType` or `PrimitiveType` → `TypeName x = ...` + import if needed
- If type is error/null, or context is not a local variable → `var x = ...`

Only valid in method body context; action is suppressed for field-level assignments.

#### `AddThrowsProvider` — `UNREPORTED_EXCEPTION`

Finds the enclosing method via `CodeActionSupport.enclosingMethod()`.
Appends the exception simple name to the `throws` clause (or creates it).
Adds an import for the exception type if not already present and not in `java.lang`.

#### `MissingMethodImplProvider` — `MISSING_METHOD_IMPL`

Finds the `ClassTree` via `payload.name()` in the attributed analysis.
Collects all abstract methods from supertypes using `elements().getAllMembers()`,
filters already-implemented ones, generates `@Override` stubs for all remaining methods.
Inserts all stubs before the closing `}` of the class body.
Adds imports for all parameter and return types not already present.
Multiple diagnostics for the same class produce the same action — deduplicated by title in the dispatcher.

### 2.8 Selection flow

Actions are returned with fully-populated `WorkspaceEdit` objects (eager, no `codeAction/resolve`).
Neovim applies the edit directly via `vim.lsp.util.apply_workspace_edit()`.

---

## 3. Logging

**`SourceAnalysisSession.codeAction` — INFO**

One summary line in, one out:
```
[codeAction] file:///Foo.java diags=3
[codeAction] file:///Foo.java 12ms actions=2
```

**Per-diagnostic routing — FINE**

```
[codeAction:diag] code=compiler.err.cant.resolve.location kind=TYPE_REF name=List
[codeAction:diag] code=compiler.err.unreported.exception kind=UNREPORTED_EXCEPTION name=java.io.IOException
```

**Per-candidate decisions inside providers — FINE**

```
[codeAction:import] java.util.List → added
[codeAction:import] com.example.List → already imported, skipped
```

---

## 4. Testing Strategy

**Layer 1 — Classification tests**

Call `session.compile()`, inspect `diagnostic.getData()`, assert `DiagnosticPayload` with correct
`kind` and `name` for each AST position and diagnostic code.

**Layer 2 — Updated existing tests**

After `enrichWithContext` is wired in, existing tests automatically go through the `DiagnosticPayload`
path. No structural changes needed.

**Layer 3 — Round-trip test (regression guard)**

Manually construct a `JsonObject` payload simulating the client round-trip:
```java
JsonObject jo = new JsonObject();
jo.addProperty("kind", "TYPE_REF");
jo.addProperty("name", "ArrayList");
diag.setData(jo);
// assert actions returned
```

**Layer 4 — Headless Neovim probe (manual, pre-ship)**

Re-run the existing capture script. Must return `got N action(s)` with N > 0.

---

## 5. Work Items

| # | Item | Scope |
|---|---|---|
| 1 | `DiagnosticPayload` record + `Kind` enum | New file, `analysis` package |
| 2 | `enrichWithContext()` post-pass in `compile()` | Multi-code classification |
| 3 | JSON codec in `WorkspaceSession` (write + read) | LSP boundary |
| 4 | `CodeActionProvider` interface | New file |
| 5 | `ImportQuickFixProvider` | Extract from `buildQuickFix` |
| 6 | Dispatcher in `codeAction()` + deduplication | Replaces flat loop |
| 7 | Replace `CodeActionTest` | Classification + round-trip + provider tests |
| 8 | `DeclareVariableProvider` | Type inference + `var` fallback |
| 9 | `AddThrowsProvider` | Throws clause + import |
| 10 | `CodeActionSupport` utilities | Type formatter + method/class finders |
| 11 | `MissingMethodImplProvider` | Stub generation |

Items 1–7 are one coherent change: infrastructure + first working provider.
Items 8–9 each add one provider with no changes to items 1–7.
Item 10 is introduced when needed by items 8–9 or 11.
Item 11 is the most complex, stands alone.
