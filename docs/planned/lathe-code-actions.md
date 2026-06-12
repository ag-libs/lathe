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

### Why the current `diagnostic.data` encoding is insufficient

Even after fixing the type check, the bare string carries only the unresolved name.
It cannot distinguish a type reference (`List<String> items`) from a variable reference
(`result = compute()`). Both produce `compiler.err.cant.resolve.location`.
Any future provider needs to know the intent at the point the diagnostic was created.

---

## 2. Design

### 2.1 Structured `diagnostic.data` payload

Replace the bare string with a `JsonObject` set directly on `diagnostic.data`:

```json
{"simpleName": "List",   "kind": "TYPE_REF"}
{"simpleName": "result", "kind": "VARIABLE_REF"}
```

Using `JsonObject` (not a Java record) gives a stable identity through the LSP round-trip:
`JsonObject` → serialized as JSON object → LSP4J deserializes back as `JsonObject`.
No conversion surprises regardless of whether there was a round-trip.

```java
JsonObject payload = new JsonObject();
payload.addProperty("simpleName", simpleName);
payload.addProperty("kind", kind);  // "TYPE_REF" or "VARIABLE_REF"
diagnostic.setData(payload);
```

At `codeAction` time: `diag.getData() instanceof JsonObject jo` — works identically whether
the diagnostic arrived from the client (round-trip) or was passed directly in a test.

### 2.2 Classification: post-processing in `compile()`

`toLsp()` stays static and unchanged — it does position mapping and extracts the raw name from the
source span. A new post-processing step `enrichWithContext()` is called after `filterAndMap()` in
`compile()`, where the attributed analysis is available:

```
compile()
  → compiler.compile()            // raw javac diagnostics + fileAnalysis
  → filterAndMap(raw, content)    // toLsp: position mapping, raw name in data (unchanged)
  → enrichWithContext(diags, run.fileAnalysis())  // NEW: replaces data with JsonObject payload
  → return enriched diags
```

`enrichWithContext` iterates the diagnostics. For each `compiler.err.cant.resolve` with a valid
position, it uses `SourceLocator.pathAt` to get the `TreePath` at the diagnostic offset and
inspects the parent node to classify:

```
leaf   = path.getLeaf()                 // IdentifierTree for the unresolved name
parent = path.getParentPath().getLeaf()

parent is VariableTree  and parent.getType() == leaf  → TYPE_REF
parent is MethodTree    and parent.getReturnType() == leaf  → TYPE_REF
parent is ClassTree     (extends/implements clause)  → TYPE_REF
parent is ParameterizedTypeTree  → TYPE_REF
null path or any other parent  → VARIABLE_REF  (safe fallback: no spurious import offered)
```

This is AST-based, not message-string parsing — reliable across javac versions and locales.

### 2.3 Stale source

By `codeAction` time the file may have been edited since the diagnostic was published.
This is solved the same way as JDT.LS and rust-analyzer: `ensureAttributedAnalysis` recompiles
from the current document content at `codeAction` time. The payload carries only semantic
context (kind + name), not offsets or version-specific positions. The fix is computed fresh
against the current analysis. If the diagnostic no longer applies, the provider returns nothing.

### 2.4 `CodeActionProvider` interface

```java
interface CodeActionProvider {
    List<Either<Command, CodeAction>> provide(
        String uri,
        Diagnostic diag,
        String simpleName,
        AttributedFileAnalysis analysis,
        WorkspaceTypeIndex typeIndex);
}
```

`SourceAnalysisSession.codeAction` becomes a dispatcher:

```
for diag in diagnostics:
    if diag.getData() is not JsonObject → skip
    simpleName = jo["simpleName"]
    kind       = jo["kind"]

    TYPE_REF     → ImportQuickFixProvider
    VARIABLE_REF → DeclareVariableProvider
```

Each provider is independently testable. New fix kinds add a new class with no changes to
existing ones.

### 2.5 Selection flow

Actions are returned with a fully-populated `WorkspaceEdit` (eager). When the user selects
an action in Neovim:

1. Neovim reads `action.edit` and calls `vim.lsp.util.apply_workspace_edit()`
2. The `WorkspaceEdit` contains a `TextEdit` inserting `"import java.util.List;\n"` at the
   insertion range computed by `ImportAnalyzer`
3. The import appears in the buffer — no further LSP round-trip, no `codeAction/resolve` needed

---

## 3. Providers

### 3.1 `ImportQuickFixProvider` (TYPE_REF)

Direct extraction of the existing `buildQuickFix` logic into a standalone class.
Queries the type index with `simpleName`, filters already-imported types and inaccessible types,
returns one `CodeAction` per candidate with an import `WorkspaceEdit`.

### 3.2 `DeclareVariableProvider` (VARIABLE_REF)

For an unresolved name on the left-hand side of an assignment (`result = expr`):

1. Walk the AST from the unresolved name up to the enclosing `ExpressionStatementTree`
2. Get the start offset of that statement and convert to an LSP `Position`
3. Use `trees().getTypeMirror(rhsPath)` to infer the type of the right-hand side expression
4. If type resolves to a concrete named type → emit `TypeName result = `
5. If type is unavailable or error → emit `var result = ` as fallback
6. `TextEdit` inserts the declaration prefix at the statement start

For a variable used in a non-assignment position (argument, operand): declare on a new line
before the enclosing statement, initialized to `null` or a zero value.

---

## 4. Logging

Two levels: INFO for the per-request summary (always visible), FINE for the per-diagnostic detail
(enabled when debugging).

### `SourceAnalysisSession.codeAction` — INFO

One summary line in, one out, matching the pattern of `compile` and `completion`:

```
[codeAction] file:///Foo.java diags=3
[codeAction] file:///Foo.java 12ms actions=2
```

### Per-diagnostic routing — FINE

One line per diagnostic showing the decoded payload and which provider was selected (or skipped):

```
[codeAction:diag] code=compiler.err.cant.resolve.location kind=TYPE_REF name=List
[codeAction:diag] code=compiler.err.cant.resolve.location kind=VARIABLE_REF name=result → no provider
```

Surfaces `kind=null` or missing-payload cases that would otherwise silently produce zero actions.

### Per-candidate decisions inside `ImportQuickFixProvider` — FINE

One line per type index candidate with the skip reason:

```
[codeAction:import] java.util.List → added
[codeAction:import] com.example.List → already imported, skipped
[codeAction:import] com.internal.List → inaccessible, skipped
[codeAction:import] com.other.List → not in elements, skipped
```

The temporary `LOG.info` currently in `SourceAnalysisSession` for `diag data type=...` is removed
once the design lands — the per-diagnostic routing log above replaces it.

---

## 5. Testing Strategy

### Layer 1 — Classification tests (new, in `CodeActionTest`)

Call `session.compile()` and inspect the `data` field of the returned diagnostics.
Assert `JsonObject` with correct `kind` for each AST position:

| Source pattern | Expected kind |
|---|---|
| `ArrayList list;` (field type) | `TYPE_REF` |
| `ArrayList<String> m()` (return type) | `TYPE_REF` |
| `result = 1;` (LHS assignment) | `VARIABLE_REF` |
| `new Foo(result)` (argument) | `VARIABLE_REF` |

### Layer 2 — Updated existing tests

After `enrichWithContext` is wired in, `session.compile()` produces `JsonObject` data.
The existing four tests in `CodeActionTest` automatically exercise the `JsonObject` path —
no structural changes needed beyond any assertion updates on `data` type.

### Layer 3 — Explicit round-trip test (regression guard)

One test that manually constructs a `JsonObject` payload without going through `compile()`,
simulating exactly what the client sends back after the LSP round-trip:

```java
// populate analysis cache
session.compile(uri, source, 1, CompileMode.OPEN);

// simulate client round-trip: data arrives as JsonObject, not String
JsonObject payload = new JsonObject();
payload.addProperty("simpleName", "ArrayList");
payload.addProperty("kind", "TYPE_REF");
Diagnostic diag = new Diagnostic(range, "cannot find symbol", Error, "lathe");
diag.setData(payload);

var actions = session.codeAction(uri, source, 1, new CodeActionContext(List.of(diag)), typeIndex);
assertThat(actions).hasSize(1);
```

This is the test that would have caught the original `JsonPrimitive` bug.
Its existence documents that the round-trip path is consciously tested.

### Layer 4 — Headless Neovim probe (manual, pre-ship)

The existing `dev/` capture script run against a real workspace.
After the implementation, the script must return `got N action(s)` with N > 0.
Not part of the automated test suite — requires a live workspace and installed server.

---

## 6. Work Items

| # | Item | Scope | Status |
|---|---|---|---|
| 1 | `enrichWithContext()` post-pass in `compile()` | New method, AST classification | Planned |
| 2 | `CodeActionProvider` interface + dispatcher in `codeAction()` | Refactor, no behaviour change | Planned |
| 3 | `ImportQuickFixProvider` — extract from `buildQuickFix` | Refactor | Planned |
| 4 | Round-trip test: `JsonObject` payload through `codeAction` | Closes the test gap | Planned |
| 5 | `DeclareVariableProvider` — LHS-assignment case | New feature | Planned |
| 6 | `DeclareVariableProvider` — argument-position case | New feature (follow-on) | Planned |

Items 1–4 are a single coherent change: the `JsonPrimitive` bug is fixed as a side-effect of
switching to `JsonObject` payload, and the provider framework is established.
Items 5–6 add behaviour without touching items 1–4.
