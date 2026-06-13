# Lathe — Signature Help

## Problem

When typing arguments inside a method invocation `m(...)` or a constructor call `new C(...)`, developers need
real-time guidance on expected parameter names and types.
Without signature help, parameter information disappears immediately after committing a method completion,
forcing the developer to manually trigger hover or consult documentation.

## Goal

Implement `textDocument/signatureHelp` to provide method signature hints showing all overloads, active parameter
highlighting, and parameter label offsets.

## What Was Implemented

### Server capability registration (`LatheLanguageServer`)

```java
capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
```

Trigger characters `(` and `,` cause Neovim / blink.cmp to fire the request automatically.

### Request routing

`LatheTextDocumentService.signatureHelp` → `WorkspaceSession.signatureHelpFuture` →
`CompilationWorker.signatureHelp` → `SourceAnalysisSession.signatureHelp` follows the same pattern as
`hover` and `definition`.

`SourceAnalysisSession.signatureHelp` resolves the cached compilation via `resolve(request)`,
converts the cursor LSP position to a javac source offset with `SourceLocator.toOffset`,
and delegates to `SignatureHelpResolver.resolve`.

### `SignatureHelpResolver`

Single static entry point: `resolve(AttributedFileAnalysis, TreePath, long cursorOffset)`.

**Call-site location** — `findEnclosingCall` walks the `TreePath` upward until it finds a
`MethodInvocationTree` or `NewClassTree`.

**Overload discovery** —
- For method calls: `elements.getAllMembers(owner)` filtered by `ElementKind.METHOD` and matching simple name.
- For constructors: same filtered by `ElementKind.CONSTRUCTOR` and `enclosingElement == owner`.

**Exact overload resolution** — `trees.getElement(new TreePath(callPath, inv.getMethodSelect()))`
identifies which overload javac resolved, so the active signature is highlighted correctly.

**Active parameter index** (`activeParamFromArgs`) — uses javac AST source positions rather than
raw source scanning. For each argument tree, compares `cursorOffset` against `[startPosition, endPosition)`.
If the cursor is past all parsed arguments (in-progress typing, e.g. `foo(a, §`),
returns `args.size()`. Handles nested calls correctly because positions are per-tree, not per-character.

**Signature label building** (`buildSignature`) —
- Return type is prepended for non-constructors using `TypeDisplayFormatter`.
- Each parameter appends `type [name]`; parameter names are suppressed when they match `arg\d+`
  (synthetic names from class files compiled without `-parameters`).
- Character offsets `[start, end]` of each parameter span within the full label are recorded as
  `ParameterInformation.setLabel(Tuple.two(start, end))` — the LSP format for range-based highlighting.

**Logging** — `FINE` log on success: `[signatureHelp] sig=<label> param=<activeParam>`.

### `TypeDisplayFormatter` relocation

Moved from `analysis/completion/` to `analysis/` so both `CandidateFactory` and
`SignatureHelpResolver` can share it without a cross-package dependency.
Class, constructor, and `format()` made `public`.

### `dev/explore.py` — `sig` command

```
sig <line>:<col>
sig after <text>
```

Calls `LatheClient.signature_help` (added to `lsp.py`) and prints all overloads with the active one
marked `>>>` and the active parameter wrapped in `[...]`.
`[signatureHelp]` added to `_LOG_KEYWORDS` so `log` captures the server FINE record.

## Tests

`SignatureHelpTest` covers:
- Cursor in first / second / third argument → correct `activeParameter`.
- Zero-parameter method → empty parameter list.
- Nested call → resolves the innermost invocation.
- Nested call with comma in outer → active param stays 0 for inner.
- Constructor call → shows constructor name, correct active param.
- Cursor outside any call → returns null.
- Parameter label offsets → `[start, end]` offsets index correctly into the label string.

## Known Limitation — Parameter Names from Class Files

When a dependency is compiled without the `-parameters` javac flag, class files store synthetic
parameter names (`arg0`, `arg1`, …) instead of the declared source names.
`buildSignature` suppresses these names to avoid showing misleading labels, falling back to
type-only display (e.g. `void greet(String, int)` instead of `void greet(String name, int count)`).

This affects all JDK and third-party library methods unless the library was built with `-parameters`
(Spring does this; most others do not).

A regression test `signatureHelp_classFileDependency_showsSourceParameterNames` documents the gap
and is currently `@Disabled`.

## Planned Improvement — On-Demand Source Parsing

When `buildSignature` encounters `arg\d+` parameter names, the resolver can look up the source file
for the owning type and parse it with `JavacTask.parse()` (AST only, no attribution) to extract real
parameter names from `MethodTree.getParameters()`.

Approach:
1. Map the type's FQN (`org.example.Foo`) to a `.java` path under `externalSourceDirs`
   (`org/example/Foo.java`).
2. If found, call `JavacTask.parse()` — cheap, no type resolution needed.
3. Match `MethodTree` by simple name and parameter count; extract `VariableTree.getName()`.
4. Cache the parsed `CompilationUnitTree` per source path to avoid repeated parsing.

Disambiguation between same-arity overloads (uncommon) can fall back to type-only display.

Since signature help is user-triggered (typing `(` or `,`), latency for the first parse is
acceptable; subsequent calls hit the cache. The parse runs on the module thread with the result
serialised back to the caller, matching the existing threading model.
