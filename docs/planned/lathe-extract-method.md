# Lathe — Extract Method (`refactor.extract.method`)

## 1. Goal

Select code, invoke code actions, and get **Extract method**: the selection becomes a new private method,
and the selection is replaced by a call to it.
It is the last missing piece of the basic extract set (variable, field, constant ship today) and the one
users miss most while coding (gap CA-16, section 4).

The bar is the same as for the other extractions: **offered only when the result compiles and behaves the
same**; a refactor that silently changes behaviour is worse than none.
Every case the analysis cannot prove is refused, not guessed.

---

## 2. Current state and what is reused

No extract method exists; `ExtractionSupport.KINDS` lists variable, field and constant.
Extract variable was built as the scaffolding for it
([Reuse map](../done/lathe-extract-variable.md#reuse-map-for-the-later-extract-method-slice)), and CA-16 added
the selection and safety work this design leans on:

| Need | Existing piece |
|---|---|
| A selection → the code it means, line-wise too | `ExtractionSupport.selectedExpression` and its span scan (`soleTreeWithin`) |
| What an expression reads | `ExtractionSupport.readVariables` |
| Writable types | `CodeActionSupport.isDenotable`, `TypeDisplayFormatter` |
| Imports for a generated signature | `CodeActionSupport.importEditFor`; `MissingMethodImplProvider.collectFqns` |
| Re-indenting moved code | `CodeActionSupport.reindent`, `indentUnit`, `lineIndent` |
| Range-driven provider dispatch and the advertised kinds | `SourceAnalysisSession.codeAction`, `ExtractionSupport.KINDS` |
| Renaming the generated method afterwards | rename (`grn` in Neovim) |

javac's own flow analysis (`Flow`, definite assignment) is internal API, which Lathe does not use
([public javac API only](../../CLAUDE.md)).
The analysis below is a deliberately conservative use/def scan over the attributed tree with public API
(`TreePathScanner`, `Trees.getElement`, element kinds and source positions); where it cannot decide, it refuses.

---

## 3. User-facing behaviour

One action, `Extract method 'extracted'`, kind `refactor.extract.method`, in the code-action menu for a
selection (and for a caret inside an expression, like extract variable).

| Selection | Result |
|---|---|
| An expression (`a.trim().length() * 2`) | `private int extracted(String a) { return a.trim().length() * 2; }`; the expression becomes `extracted(a)` |
| Statements, nothing used afterwards | `private void extracted(…) { … }`; the statements become `extracted(…);` |
| Statements declaring a local used afterwards | `T x = extracted(…);` and the method ends `return x;` |
| Statements assigning an earlier local used afterwards | `x = extracted(…);` |
| Statements calling methods that throw checked exceptions they don't catch | the new method declares them (`throws IOException`) |
| Inside a static method, a static initializer, or a static nested context | the new method is `static` |

Refused, with no action offered (each one listed in the tests):

- the selection is not one expression nor a run of whole sibling statements of one block (or `case:` list);
- more than one local would have to come back out (Java has one return value);
- a `return`, or a `break`/`continue`/`yield` whose target lies outside the selection, inside it
  (outside nested lambdas and classes);
- an earlier local assigned only in a branch or loop of the selection and read afterwards
  (its value may or may not change — the scan cannot prove which);
- it uses a local class or record declared earlier in the method (invisible to the new method);
- it assigns a `final` field (only a constructor or initializer may), or contains `this(…)`/`super(…)`;
- a local that comes back out is captured by a lambda or inner class anywhere in the scope
  (a second assignment would make it no longer effectively final);
- it rethrows a catch parameter (`catch (Exception e) { throw e; }`: Java's precise rethrow throws less
  than the parameter's type says, and an over-broad `throws` would not compile at the call site);
- a parameter, return or thrown type cannot be written (`isDenotable`);
- the enclosing method's type variables are used (until slice 4 adds type parameters).

The generated name is `extracted` (uniquified against the class's methods); the user renames it with rename,
as for extract variable — Neovim code actions carry no snippet placeholders.

---

## 4. Technical design

A new package-private `ExtractMethodProvider` with the range-driven signature of its siblings,
registered in `SourceAnalysisSession.codeAction`;
`ExtractionSupport.METHOD_KIND = "refactor.extract.method"` joins `KINDS`, which the server already advertises.
The use/def scan lives in its own small class, `RegionFlow`, because it is the one hard, separately testable part.

### 4.1 Selection → region

- **Expression form**: `ExtractionSupport.selectedExpression` as today (caret, exact, line-wise).
- **Statement form**: the outermost trees inside the selection (the span scan of `soleTreeWithin`, returning the
  list instead of requiring one) must all be statements, consecutive children of the same block or `case:`
  statement list.
  One whole expression statement selected is the statement form, not the expression form.

The region is a list of tree paths plus its source span.

### 4.2 Data flow (`RegionFlow`)

Scope: the innermost executable enclosing the region — a method, constructor, lambda body or initializer
block. Fields are not locals: the new method reaches them as the old code did.

For every local variable element of that executable (local, parameter, exception parameter, resource,
pattern binding), from one `TreePathScanner` pass recording each identifier's element, its position, and
whether the use is a write (assignment, compound assignment, `++`/`--`):

| Local is | and in the region it is | and outside the region | It becomes |
|---|---|---|---|
| declared before | read | — | a **parameter** |
| declared inside | — | read after the region | an **output**; its declaration moves to the call site |
| declared before | written | read after its declaration, anywhere outside the region | an **output** (`x = extracted(…)`) |

"Outside the region" for outputs is deliberately broader than "after the region": in a loop, a read before
the region in the next iteration is a later read, and text order cannot tell; any outside read counts, so the
scan over-approximates and refuses rather than drop a needed value.

An earlier local that is an output must be assigned by a top-level statement of the region (definitely, on
every path); assigned only inside a branch or loop, the region is refused — without javac's
definite-assignment analysis the old value may still be needed.
An output that is also read in the region before its assignment is a parameter as well.
Parameter types are each variable's **declared** type (`VariableElement.asType()`), not the type at a use,
so a `List<?>` stays `List<?>` rather than an unwritable captured type.
More than one output: refused.

Uses inside lambdas and local or anonymous classes within the region count as reads of the outer locals;
their own parameters and locals are internal to the region.

### 4.3 Control flow

A scan of the region (not descending into lambdas or class bodies) refuses:

- `return` (slice 4 accepts one case: the region is the method's tail and ends in `return expr;` → the call
  site becomes `return extracted(…);`);
- `break`, `continue` and `yield` whose target statement (labelled, or the innermost loop/switch) is outside the
  region.

`throw` is fine: exceptions propagate through the new method.

### 4.4 Signature

- **Name**: `extracted`, made unique against every method the class declares **or inherits**: a new
  `extracted(int)` beside an inherited `extracted(long)` could change which one existing calls pick.
- **Placement**: a member of the class declaring the enclosing executable (an anonymous or local class
  included, so `this` keeps its meaning), inserted right after the enclosing member, at that member's
  indentation.
- **Modifiers**: `private`; `static` when the enclosing executable is static (a static method, a static
  initializer, a field initializer of a static field). A non-static context keeps an instance method even if
  the region touches no instance state — inferring `static` is a later option, not a correctness need.
  In an interface the enclosing default method makes it a `private` instance method (Java 9+).
- **Parameters**: the inputs, in order of first use in the region; `final` when every parameter of the
  enclosing method is `final` (the file's style), else plain.
- **Return type**: the expression's type, the output's type, or `void`.
- **Throws**: the checked exceptions the region can throw and does not catch:
  each method or constructor invocation's thrown types from its instantiated `ExecutableType`
  (`Trees.getTypeMirror` on the invocation's method select, so `orElseThrow(X::new)` yields `X`),
  each `throw` expression's type, and the `close()` exceptions of resources the region's own
  try-with-resources declares; minus those caught by a `try` inside the region whose body holds the throwing
  site (multi-catch unions included); checked only (not `RuntimeException`/`Error` subtypes); subtypes of
  another listed type dropped. Nested lambdas and classes are not scanned — their exceptions are theirs.
- **Imports**: for the parameter, return and thrown types, as the extract variable import edit does.

### 4.5 Edits (one `WorkspaceEdit`)

1. Replace the region with the call:
   expression form `extracted(a, b)`; statements `extracted(a, b);`, `T x = extracted(a, b);` or
   `x = extracted(a, b);`.
2. Insert the method after the enclosing member: its signature, then the region's source re-indented one
   level in (`CodeActionSupport.reindent`), then `return x;` for an output, or `return <expression>;` for the
   expression form.
3. Import edits.

All text comes from the region's own source span and javac-rendered types; nothing is re-parsed.
The user's format-on-save normalizes layout.

### 4.6 Compile check (second line of defence)

javac's own flow analysis (definite assignment, reachability, effectively-final) is internal, so the scan
approximates; a mistake it makes would show up as code that no longer compiles.
Before the edit reaches the user, the provider compiles the file with the edit applied, in process (about
100 ms for a file), and offers nothing if that compile reports an error.
To keep that cost off every code-action request, the action is offered by title only and its edit is
computed and checked in `codeAction/resolve`, which the client sends only when the user picks it
(Neovim supports it); the server gains `resolveProvider` and a resolve handler.

The check catches everything that breaks compilation (a missed capture, an over-broad `throws`, an overload
clash, a `final` field); it cannot catch a rewrite that compiles but behaves differently (a missed output),
which rests on the scan's rules and the behaviour tests (section 7).

### 4.7 Cost

One scan of the enclosing executable per request with a non-empty selection, on the already attributed tree:
no compile, no `codeAction/resolve`, comparable to extract variable's replace-all scan.

---

## 5. Slices

Each slice is offered only for what it fully handles, and refuses the rest.

1. **Expression → method.** Inputs, static context, throws, imports. Smallest useful slice, no outputs or
   jumps by construction.
2. **Statements, no output.** The statement-form selection, the control-flow refusals.
3. **Statements with one output.** Declared inside (declaration moves out) or an earlier local assigned at the
   region's top level.
4. **Generic methods and tail `return`.** Copy the enclosing method's type parameters when the region uses
   them; a region ending the method with `return expr;` becomes `return extracted(…);`.

Refinements to consider once the measurement (section 7) shows which refusals matter: leave a trailing
`break;` of a `case:` body in place instead of refusing; accept an output assigned in every branch of a complete
`if`/`else` or exhaustive `switch`; narrow the loop over-approximation when the region is not inside a loop
relative to the variable's declaration.

Later, separately designed if wanted: replacing duplicates of the extracted code elsewhere in the class,
inferring `static`, choosing parameter order or the name before applying.

---

## 6. Required changes

- `analysis/ExtractMethodProvider.java` (new, package-private): selection → region → `RegionFlow` → signature →
  edits.
- `analysis/RegionFlow.java` (new, package-private): inputs, outputs and refusals for a region of an
  executable; a value type for the result.
- `ExtractionSupport`: `METHOD_KIND` in `KINDS`; the span scan also returns all outermost trees (statement form).
- `SourceAnalysisSession.codeAction`: one more `addUnique` line.
- Code-action resolve: `resolveProvider` in the capabilities and a `resolveCodeAction` handler that computes,
  compile-checks and returns the edit (section 4.6).
- No change to the protocol, the clients, the capabilities beyond the new kind, or any module's dependencies.

---

## 7. Test plan

In `CodeActionTest`, with the `«…»` selection markers and the applied-edit compile check (`assertExtracts` /
`assertCompilesClean`) the CA-16 work introduced; one case per rule, no two cases on the same path:

- **Offered and compiles**, with the rewritten text asserted where the shape matters: an expression; void
  statements; an output declared inside; an earlier local assigned at the top level; a static context; a
  lambda-body region capturing outer locals; a region inside an anonymous class using `this`; checked
  exceptions (a method call, a `throw`, a generic `orElseThrow`, a resource's `close()`), one caught inside the
  region and so not declared; `final` parameters mirrored.
- **Refused**: two outputs; `return`; `break` to an outer loop; a conditionally assigned output; a local class
  used; a non-denotable type; a partial statement selection; statements from two blocks.
- **Coverage measurement** (a dev probe, not a unit test): select every run of 1–5 consecutive statements
  in real methods (Lathe, Helidon, Dropwizard), ask the provider, and tally offered versus each refusal reason,
  as the CA-16 matrix did for extract variable; it turns section 8's estimate into numbers and picks the
  refinements worth doing.
- **Behaviour, not only compilation**: a few cases run the original and the rewritten method (compiled in the
  test) and compare results, since a missed output compiles but changes behaviour — the one failure mode a
  compile check cannot see.

---

## 8. Edge cases and non-goals

Expected coverage (an estimate until measured): the expression form nearly always applies; for runs of
statements in typical service code roughly 60–75%, the misses dominated by several values flowing out,
`continue`/`return` in loop bodies, and variables assigned in branches — where IntelliJ also refuses or
produces a holder object.

- **Several return values** (IntelliJ's holder object or array): non-goal; refused.
- **Moving the method to another class**, changing visibility of what it uses: non-goal.
- **Field initializer regions** (`String f = a() + b();`): expression form only; placed after the field,
  `static` for a static field.
- **Comments inside the region** move with it (they are in the span); comments around it stay.
- **Selections across a lambda boundary** (part inside a lambda, part outside) are not one block's statements
  and are refused.
- **Shadowing**: the new method's parameter names are the locals' names, so a field of the same name stays
  shadowed exactly as it was inside the region.

---

## 9. Open questions

- **Default name**: `extracted`, or a name derived from the code (the output's name, `compute` + the returned
  type)? Rename makes either cheap to fix.
- **Placement**: right after the enclosing member (IntelliJ's choice) or at the end of the class?
- **Milestone**: the roadmap's Refactoring section lists only rename (shipped); this needs a slot.
