# Lathe — Surround with try-with-resources

Status: proposed.
A request-driven refactor code action that wraps an `AutoCloseable` resource declaration in a
try-with-resources statement.
Sibling of `ExtractVariableProvider` (invocation) and `TryCatchWrapProvider` (edit assembly).

## Motivation

A developer who has written a plain resource declaration —

```java
FooReader r = new FooReader(path);
r.read();
```

wants to manage it with try-with-resources without hand-editing braces and indentation.
This is the standard IDE "Surround with try-with-resources" refactor; Lathe has no equivalent today.

## Model

It is a **range/cursor-driven refactor**, not a diagnostic quick-fix — so it joins the same dispatch
path as Extract Variable and Extract Constant in `SourceAnalysisSession`
(`provide(uri, range, analysis)`, added via `addUnique(...)`), with `CodeActionKind.RefactorRewrite`
and the title "Surround with try-with-resources".
It borrows invocation shape from `ExtractVariableProvider` and edit assembly (statement → wrapped
block, `lineIndent`, source substring) from `TryCatchWrapProvider`.

## Trigger and detection

Offered when the caret (or selection) is **anywhere on a local variable declaration with an
initializer whose type is `AutoCloseable`**:

- `CodeActionSupport.pathAt` → `nearestEnclosingStatement` yields a `VariableTree` with an initializer
  whose parent is a `BlockTree` (the same shape `ExtractVariableProvider` already checks). Because it
  keys off the **declaration statement** — not the initializer expression — the action fires with the
  caret on the type, the name, or the initializer.
- Type gate: `trees.getTypeMirror(declPath)` is assignable to `java.lang.AutoCloseable`, via a small
  new `CodeActionSupport.isAutoCloseable(type)` helper (`analysis.types()` +
  `analysis.elements().getTypeElement("java.lang.AutoCloseable")`).

Verified by probe (current server, `dev/lsp.py` `codeAction`): on a `FooReader r = new FooReader(…);`
line, the caret **on the declaration** yields no actions today (the empty slot this fills), while the
caret **on the initializer** yields `Extract variable` / `Extract field`. So there is no overlap with
the existing expression-scoped refactors, and the declaration statement is a free trigger surface.

## Scope — approach A (chosen)

A resource variable is only in scope inside the `try`, so the body **must** contain the statements
that use it; wrapping the declaration alone would not compile.

**Approach A:** move the declaration **and every following statement to the end of the enclosing
block** into the try body. This always compiles (all users of the resource follow the declaration and
are inside the same block). It may capture statements past the resource's last use — accepted for the
first slice.

```java
// before (caret on the declaration)
FooReader r = new FooReader(path);
r.read();
log(r);

// after
try (FooReader r = new FooReader(path)) {
    r.read();
    log(r);
}
```

Narrowing the body to the resource's last use (approach B) and a selection-based "surround the
selected statements" variant (approach C) are [non-goals](#non-goals) for this slice.

## Edit

Replace from the declaration's start to the end of the enclosing block's statements with a
try-with-resources whose resource is the declaration (minus its trailing `;`) and whose body is the
following statements, re-indented one level. Indent and source-substring handling reuse
`TryCatchWrapProvider`'s approach. When the declaration is the block's last statement, the body is
empty.

## Correctness gates

- The declaration must have an **initializer** (a resource needs one).
- The declared type must be **`AutoCloseable`**.
- The resource is implicitly **final** in try-with-resources; if the variable is **reassigned** later
  in the block, do not offer the action (it would not compile).
- Offer only when the declaration's parent is a `BlockTree` (not a for-header, lambda expression body,
  etc.).

## Non-goals

- **Approach B** — narrowing the body to the resource's last use.
- **Approach C** — selection-based "surround the selected statements", hoisting multiple resource
  declarations into the resource list.
- Adding a `catch`/`throws` when the wrap surfaces a new checked exception (e.g. from `close()`): the
  wrap leaves any resulting unhandled-exception diagnostic, which **composes** with the existing
  try/catch wrap quick-fix (`TryCatchWrapProvider`) the user can then apply.
- Wrapping more than the single declaration's resource in one action.

## Testing

`CodeActionTest`:

- resource declaration with following statements → wrapped, statements moved into the body (approach A);
- non-`AutoCloseable` declaration → not offered;
- declaration without an initializer → not offered;
- reassigned resource → not offered;
- declaration as the block's last statement → empty body.

## Relationship to other work

- Distinct from snippet/template completion (`lathe-snippet-completion.md`): that is an abbreviation →
  snippet at the caret; this is a range-driven refactor of an existing statement.
- Composes with `TryCatchWrapProvider`: if the wrap introduces an unhandled checked exception, the
  developer applies the try/catch wrap next. The two stay separate providers.
