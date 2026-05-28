# Completion Gaps

## Open

### Gap A — Static-import members not offered as simple names

**Difficulty:** Medium

**Symptom:** When a file has `import static java.util.Objects.requireNonNull`, typing `requireN`
inside a method body returns no completions.  Local variables and type names work; only the
statically-imported identifiers are missing.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "requireN" at 55  →  (no completions)
inject "conf"    at 55  →  config  [Variable]   ← locals DO work
```

**Root cause (suspected):** The simple-name candidate set is built from local variables, fields,
and the type index, but does not consult the compilation unit's `import static` declarations.

**Resolution idea:** Add `addStaticImportMembers()` to `SimpleNameProposalCollector`.  Walk
`CompilationUnitTree.getImports()`, filter to static imports, parse the qualified identifier to
extract the declaring type name and the member name (or `*` for wildcards), resolve the type via
`getTypeElement`, then emit its static members filtered by the declared member name and the
current prefix.  No new infrastructure needed — contained change in one class.

---

### Gap B — Member-access on an unimported simple type name returns nothing

**Difficulty:** Medium

**Symptom:** When `java.util.Objects` is not in the regular imports (only
`import static java.util.Objects.requireNonNull` is present), typing `Objects.` returns no
completions — even though typing the bare `Objects` correctly surfaces `java.util.Objects` via
the type index.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "Objects."            at 55  →  (no completions)
inject "java.util.Objects."  at 55  →  21 items  ← fully-qualified works
inject "Objects"             at 55  →  java.util.Objects [Class]  ← type-ref works
```

**Root cause (suspected):** The member-access path resolves the receiver from the attributed
AST.  When `Objects` is not a regular import, javac attributes it as an error node; the engine
finds no declared type to reflect on.

**Resolution idea:** When the attributed receiver is an error node, extract the receiver token
text from source (the characters before the `.`), feed it into the same `WorkspaceTypeIndex`
prefix lookup that already powers TYPE_REFERENCE completions, then call `getTypeElement` on the
top match and complete on its members exactly as `proposeMemberAccess` does today.  The
TYPE_REFERENCE path resolves `Objects` → `java.util.Objects` purely by index scan (no import
needed), so the same mechanism works here without any new infrastructure.

`WorkspaceTypeIndex` is already threaded all the way to `CompletionEngine` (`this.typeIndex`),
so no plumbing changes are needed — the fallback is a self-contained addition inside
`CompletionEngine.completeMemberAccess`.  When the reactor type index lands it will extend the
same `WorkspaceTypeIndex`, so Gap B will automatically benefit from reactor types too.

---

### Gap C — Variable offered as completion in its own initializer

**Difficulty:** Easy

**Symptom:** When declaring `String foo = ` and triggering completion, `foo` itself appears in
the candidate list even though it is not yet in scope.  Only variables declared *before* the
cursor should be offered.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "String foo = " at 55  →  foo [Variable]  appears among candidates
```

**Root cause (suspected):** `SimpleNameProposalCollector.addMethodLocals` filters by
`startPosition < cursorOffset`, but the variable tree's start position is the start of the
declaration (before the cursor), so the name passes the filter.

**Resolution idea:** Exclude the variable whose declaration *contains* the cursor — i.e. skip
any `VariableTree` whose source range `[start, end]` brackets `cursorOffset`.

---

### Gap D — No type-based ranking after `=`

**Difficulty:** Medium

**Symptom:** When declaring `String foo = ` and triggering completion, items that return or are
a `String` (`MAPPING_QUALIFIER [Field] String`, `dbType() [Method] String`,
`toString() [Method] String`) are not ranked above items of unrelated types
(`client [Field] MongoClient`, `close() [Method] void`, etc.).  All non-keyword items share an
empty `sortText`.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "String foo = " at 55
  → [no sortText]  MAPPING_QUALIFIER   String     ← should rank high
  → [no sortText]  dbType()            String     ← should rank high
  → [no sortText]  client              MongoClient ← should rank low
  → [no sortText]  close()             void        ← should rank low
```

**Root cause (suspected):** `SimpleNameProposalCollector.addVariable()` already applies
expected-type ranking (`0_` vs `1_` sort keys) for local variables, but `addClassMembers()`
does not.

**Resolution idea:** Propagate the `expectedParamType` signal to `addClassMembers()` and apply
the same `0_` / `1_` sort key logic — `0_` for members whose return/field type is assignable to
the expected type, `1_` otherwise.  The expected type for a variable declarator is already
available from `SimpleNameProposalContext`.

---

### Gap E — No type-index suggestions in expression context

**Difficulty:** Medium

**Symptom:** After `String foo = `, no type names from the type index are offered, so the user
cannot start typing a class name (e.g. `String`, `StringBuilder`, `Optional`) to invoke a
constructor or static factory.  Type-index candidates are only surfaced in TYPE_REFERENCE
context.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "String foo = " at 55  →  0 type-index candidates
inject "Objects"       at 55  →  5 type-index candidates  ← TYPE_REFERENCE works
```

**Root cause (suspected):** The engine does not classify the position after `=` as a context
where type-index candidates are useful.

**Resolution idea:** Extend the SIMPLE_NAME / expression path to also emit type-index entries,
optionally pre-filtered to types assignable to the declared type where the expected type is
known.  The type-index lookup already exists; the change is to call it from the expression
context in addition to the TYPE_REFERENCE context.

---

### Gap F — Import completions missing trailing semicolon

**Difficulty:** Easy

**Symptom:** After `import java.util.`, selecting a type suggestion (e.g. `Map`) produces
`import java.util.Map` with no closing semicolon — the user must type `;` manually.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "import java.util." at 30
  → textEdit.newText = "Map"   (no semicolon)
  → result after selection:  import java.util.Map   ← missing ;
```

**Resolution idea:** In `ImportCompletionProvider`, set `newText = simpleName + ";"` (or extend
the `textEdit` range to cover an existing `;` if one is already present on the line).

---

### Gap G — Static import inserts method snippet instead of bare name

**Difficulty:** Easy

**Symptom:** After `import static java.util.Objects.`, selecting a method suggestion (e.g.
`equals`) inserts `equals($1)` — a snippet with a parameter placeholder — instead of the bare
identifier `equals`, producing a syntax error.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "import static java.util.Objects." at 30
  → insertText: "equals($1)"   ← wrong
  → expected:   "equals;"
```

**Resolution idea:** In `ImportCompletionProvider`, detect the `import static` context and
produce items with `newText = simpleName + ";"` (no parentheses, no snippet), overriding the
call-site factory used elsewhere.

---

### Gap H — No subpackage navigation when typing a fully-qualified name

**Difficulty:** Medium

**Symptom:** Typing `java.` in a method body returns no completions.  The user cannot navigate
from a package prefix to a subpackage or type incrementally.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "java."                    at 55  →  (no completions)
inject "java.util."               at 55  →  (no completions)
inject "java.util.stream.Stream." at 55  →  9 static methods  ← works only at type level
```

**Root cause (suspected):** The member-access path requires a type receiver.  Package
identifiers produce no `TypeElement` to reflect on.

**Resolution idea:** Use the type index as a package trie.  When the attributed receiver is a
package (not a type), scan all type-index entries whose qualified name starts with the typed
prefix, extract the next dot-segment, and deduplicate to produce subpackage and type
candidates.  No new external API needed — purely a prefix scan over the existing index.
`SentinelContext` already knows whether the receiver resolved to a `PackageElement`; that flag
can trigger the index scan path.

---

### Gap I — Static methods offered in instance member-access context

**Difficulty:** Easy

**Symptom:** After an instance expression such as `Stream.of("").`, static methods of `Stream`
are included in the completion list alongside instance methods.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "java.util.stream.Stream.of(\"\")." at 55
  → of(T), builder(), empty(), …  ← statics, should not appear
  → filter(…), map(…), …          ← instance, correct
```

**Root cause:** `ProposalGenerator.proposeMemberAccess` filter is one-sided — it excludes
instance members on static access but not statics on instance access.

**Resolution idea:** Make the filter two-sided:
```java
.filter(el -> isStaticAccess
    ? el.getModifiers().contains(Modifier.STATIC)
    : !el.getModifiers().contains(Modifier.STATIC)
      || el.getKind() == ElementKind.ENUM_CONSTANT)
```
Single-line change in `ProposalGenerator`.

---

### Gap J — No completions after `::` (method reference)

**Difficulty:** Hard

**Symptom:** Typing `String::` (or any `Type::`) returns no completions.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "…Stream.of("").map(z -> z.charAt(9)).map(String::" at 55
  →  (no completions)
```

**Root cause (suspected):** The sentinel parser does not recognise the `MEMBER_REFERENCE` tree
node as a completion context.

**Resolution idea:** Extend `SentinelContext` to handle `MEMBER_REFERENCE` nodes.  The LHS
type (e.g. `String`) can be resolved the same way as a static member-access receiver.  The hard
part is filtering: candidates should be methods compatible with the functional interface expected
at the call site, which requires matching the abstract method's arity and parameter types against
each candidate.  Needs significant new infrastructure — no existing path to build on.

---

### Gap K — Keywords not filtered by syntactic context (low priority)

**Difficulty:** Medium

**Symptom:** In several positions the engine offers keywords that are syntactically impossible
there.  Three confirmed cases:

| Position | Wrongly offered | Should be |
|---|---|---|
| `import ` | *(no completions at all)* | `static` + package names |
| `return ` | `if`, `for`, `while`, `do`, `switch`, `try`, `final`, `var`, … | expression keywords only (`new`, `null`, `true`, `false`, `this`, `super`) |
| `String foo = ` | same full statement set | expression keywords only |

The engine does handle some contexts correctly — `if (` and `while (` already return only
expression-level keywords — so the infrastructure exists; it is just not applied uniformly.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "import "               at 30  →  [] keywords  (static missing)
inject "        return "       at 55  →  [if, for, while, do, switch, …]
inject "        String foo = " at 55  →  same full set as above
inject "        if ("          at 55  →  [new, null, true, false, this, super]  ✅
```

**Resolution idea:** Extend `SentinelContext` to map additional tree-node positions to keyword
subsets — expression-position nodes (`ASSIGNMENT`, `RETURN`, `VARIABLE` initialiser) get only
expression-level keywords; the `IMPORT` node gets only `static`.  The keyword sets are finite
and easy to enumerate.

---

## Closed

All gaps identified before 2026-05-27 have been addressed.
