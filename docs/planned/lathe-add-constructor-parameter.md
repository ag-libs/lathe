# Lathe — Add constructor parameter for a field

Status: proposed.
A request-driven refactor code action that, for a `final` instance field with no initializer, adds a
matching parameter to the class's constructors and binds it to the field — generating a constructor
when none exists.
Sibling of `ExtractFieldProvider` (field-shaped edits, placement, source-span reuse) and
`MissingMethodImplProvider` (member-generating quick fix).

## Motivation

A developer adds a blank `final` field:

```java
private final PricingPolicy policy;
```

and now wants each constructor to accept and store it — the standard IntelliJ *"Add constructor
parameter"* intention. Today Lathe offers nothing on a field declaration, so the developer hand-edits
every constructor's parameter list and body. A blank `final` field is also a compile error until every
constructor assigns it, so this doubles as the natural quick fix for that state.

## Model

It is a **range/cursor-driven refactor**, not a diagnostic quick-fix — so it joins the same dispatch
path as Extract Variable / Extract Field in `SourceAnalysisSession.codeAction`
(`provide(uri, range, analysis)`, added via `addUnique(...)`), with `CodeActionKind.RefactorRewrite`
and the title `Add constructor parameter '<field>'`.

The whole rewrite is one `WorkspaceEdit` over the single file (a list of non-overlapping `TextEdit`s):
one parameter insertion per touched constructor, plus one binding statement (or one forwarded
argument) per constructor, plus — when no constructor exists — one generated constructor.

## Trigger and detection

Offered when the caret (or selection start) is **anywhere on a field declaration** that is:

- a `FIELD` (element kind), resolved via `CodeActionSupport.enclosingVariable(pathAt(...))` — which is
  bounded at the method/class boundary, so a caret in a method body never climbs to a field;
- `final` and **not** `static`;
- **without an initializer** (`VariableTree.getInitializer() == null`) — a field with an initializer
  cannot also be assigned in a constructor;
- declared in a `class` or `enum` (a record's instance state is exactly its components; interface
  fields are implicitly `static final`), mirroring `ExtractFieldProvider.holdsInstanceField`.

## Parameter type and name

The parameter is emitted as `final <type> <name>` — matching the field's own `final` intent and the
project's own parameter style. It reuses the field's **declared-type source span** (substring of
`VariableTree.getType()` via `SourcePositions`, the same source-span technique `ExtractFieldProvider`
uses for the extracted expression) and the field's name. Because the field already compiles with that
spelling, the parameter type is already resolvable in the file — **no import edit is ever needed**, and
generics/arrays are carried verbatim (`List<String>`, `int[]`).

## Per-constructor handling

The class's **explicit source constructors** are its `MethodTree` members with a null return type
(`getReturnType() == null`); a synthesized default constructor never appears among the parsed members,
so this naturally enumerates only what the user wrote. Each explicit constructor is classified:

1. **Already assigns the field** (its body contains an assignment whose LHS resolves to the field
   element) → **skip** (no edit). Re-adding a parameter would double-assign a `final`.
2. **Delegating** (first body statement is a `this(...)` call) → add the parameter and **forward** the
   new argument into the `this(...)` invocation. It does not bind the field itself; the delegation
   target does.
3. **Otherwise** (a "root" constructor that does not assign the field) → add the parameter and append
   the binding `this.<field> = <field>;` as the last body statement.

This keeps a `final` field assigned **exactly once on every construction path**: every path terminates
at a root constructor that binds it once, and delegating constructors thread the value through.

When **no explicit constructor exists**, generate one after the last field:

```java
ClassName(final FieldType field) {
  this.field = field;
}
```

Constructor generation is offered for `class` only; for `enum`, the action edits existing constructors
but does not synthesize one (placement among enum constants is out of scope for this slice).

### Parameter and statement placement

- **Parameter**: appended as `final Type name`. With existing parameters, `, final Type name` after
  the last parameter's end; with an empty list, `final Type name` just after the `(` (located with
  `source.indexOf('(', methodStart)`, a delimiter search of the kind `ExtractFieldProvider` already
  uses for `{`).
- **Binding**: appended as the last statement of the body (after the last statement, or after `{` for
  an empty body), reproducing the body indentation via `CodeActionSupport.lineIndent`.
- **Forwarded argument**: `, name` after the last argument of the `this(...)` call, or `name` before
  its `)` when the call has no arguments.

## Correctness gates

- Not offered unless the field is `final`, non-`static`, initializer-less, in a `class`/`enum`.
- **Parameter-name collision**: if any constructor that would be touched already declares a parameter
  named the same as the field, the whole action is withheld (adding a same-named parameter would be a
  duplicate-parameter compile error). Conservative and whole-action, not per-constructor.
- **Nothing to do**: if every explicit constructor already assigns the field (and one exists), no edit
  is produced and the action is not offered.
- The `this.<field> = <field>` binding intentionally uses the `this.` qualifier, so a parameter that
  shadows the field is correct and normal.

## Non-goals

- **Non-final fields.** Scoped to `final` fields as requested; mutable fields overlap with a plain
  "add parameter" refactor and are noisier. A later slice may extend it.
- **Enum constructor generation** (placement among constants). Existing enum constructors are edited.
- **Sibling blank-final fields.** The action binds only the field under the caret; other uninitialized
  `final` fields (already in an error state) are left to their own invocation.
- **Reordering** parameters or matching field order — the parameter is always appended.
- **Cross-file / superclass** constructor changes; the rewrite is confined to the field's own class.

## Testing

`CodeActionTest` (request-driven, caret on the field), mirroring the Extract Field cases:

- single non-delegating constructor → parameter appended + `this.f = f;` bound;
- multiple constructors → each gets the parameter and its own binding;
- delegating constructor (`this(...)`) → parameter added + argument forwarded, no binding; the target
  constructor is bound;
- no constructor → one generated after the last field;
- a constructor that already assigns the field → skipped;
- a constructor whose parameter already uses the field name → action withheld;
- negatives: non-`final` field, `static` field, field with an initializer, record component, caret in a
  method body → not offered.

Verified end to end against a real multi-module workspace via a `codeAction` probe.

## Relationship to other work

- A concrete slice of the deferred [Basic Refactorings](../potential/lathe-basic-refactorings.md)
  (rename / move / extract) direction, reusing the code-action dispatch and edit-assembly scaffolding
  established by the Extract refactors.
- Distinct from Extract Field (`ExtractFieldProvider`): that introduces a field *initialized from a
  selected expression*; this parameterizes constructors for an *existing* field.
