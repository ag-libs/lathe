# Completion Gaps

## Open

### Gap A — Static-import members not offered as simple names

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
`Elements.getTypeElement` / `Trees` can enumerate the static members of each imported type;
those should be added to the SIMPLE_NAME completion path.

---

### Gap B — Member-access on an unimported simple type name returns nothing

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
finds no declared type to reflect on.  A fallback could re-resolve the receiver token against
the type index and complete on the resulting type's members when the attributed receiver is an
error.

---

### Gap C — Variable offered as completion in its own initializer

**Symptom:** When declaring `String foo = ` and triggering completion, `foo` itself appears in
the candidate list even though it is not yet in scope.  Only variables declared *before* the
cursor should be offered.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "String foo = " at 55  →  foo [Variable]  appears among candidates
```

**Root cause (suspected):** `SimpleNameProposalCollector.addMethodLocals` filters by
`startPosition < cursorOffset`, but the variable tree's start position for `String foo = ` is
the start of the declaration (the `S` of `String`), which is before the cursor.  The filter
should use the *end* of the declarator — or exclude the variable whose declaration contains the
cursor — so the name is only offered after the statement is fully written.

---

### Gap D — No type-based ranking after `=`

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
expected-type ranking (`0_` vs `1_` sort keys) for local variables, but
`addClassMembers()` does not.  The expected-type signal needs to be propagated to the class
member path so that type-compatible fields and methods sort before incompatible ones.

---

### Gap E — No type-index suggestions in expression context

**Symptom:** After `String foo = `, no type names from the type index are offered, so the user
cannot start typing a class name (e.g. `String`, `StringBuilder`, `Optional`) to invoke a
constructor or static factory.  Type-index candidates are only surfaced in TYPE_REFERENCE
context (e.g. after `new`, in parameter position), not in bare expression position after `=`.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "String foo = " at 55  →  0 type-index candidates
inject "Objects"       at 55  →  5 type-index candidates  ← TYPE_REFERENCE works
```

**Root cause (suspected):** The completion engine does not classify the position after `=` as a
context where type-index candidates are useful.  Extending the SIMPLE_NAME / expression path
to also emit type-index entries (filtered to types assignable to the declared type where known)
would cover this case.

---

### Gap F — Import completions missing trailing semicolon

**Symptom:** After `import java.util.`, selecting a type suggestion (e.g. `Map`) produces
`import java.util.Map` with no closing semicolon — the user must type `;` manually.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "import java.util." at 30
  → textEdit.newText = "Map"   (no semicolon)
  → result after selection:  import java.util.Map   ← missing ;
```

**Root cause (suspected):** The `ImportCompletionProvider` reuses the same `CompletionItem`
factories as the regular type-index path and does not append `;` to the `newText`.  The fix is
to set `newText = label + ";"` (or extend the `textEdit` range to cover an existing `;` if
already present) when the completion context is an import declaration.

---

### Gap G — Static import inserts method snippet instead of bare name

**Symptom:** After `import static java.util.Objects.`, selecting a method suggestion (e.g.
`equals`) inserts `equals($1)` — a snippet with a parameter placeholder — instead of the bare
identifier `equals`.  The resulting line becomes
`import static java.util.Objects.equals($1)` which is a syntax error.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "import static java.util.Objects." at 30
  → label:      equals(Object, Object)
  → insertText: "equals($1)"   ← snippet placeholder
  → result after selection:  import static java.util.Objects.equals($1)  ← wrong
  → expected:                import static java.util.Objects.equals;
```

**Root cause (suspected):** The static-import position is handled by the same member-access
`CompletionItemFactory` that produces call-site snippets.  When the position is inside an
`import static` declaration, the item should use only the simple member name (no parentheses,
no snippet) and append `;`.  The `ImportCompletionProvider` (or `SentinelContext`) needs to
detect the `IMPORT` tree context and override the insert text accordingly.

---

### Gap H — No subpackage navigation when typing a fully-qualified name

**Symptom:** Typing `java.` in a method body returns no completions.  The user cannot navigate
from a package prefix to a subpackage or type incrementally — completions only appear once the
full package path is present and a type name follows the final `.`.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "java."                    at 55  →  (no completions)
inject "java.util."               at 55  →  (no completions)
inject "java.util.stream.Stream." at 55  →  9 static methods  ← works only at type level
```

**Root cause (suspected):** The member-access path resolves the receiver from the attributed
AST.  Package identifiers (`java`, `java.util`, …) are not types and produce no element to
reflect on, so the engine returns nothing.  Supporting this would require recognising
package-qualified prefixes and resolving them against the type index or
`Elements.getPackageElement` to enumerate subpackages and types within the package.

---

### Gap I — Static methods offered in instance member-access context

**Symptom:** After an instance expression such as `Stream.of("").`, static methods of `Stream`
(`builder()`, `empty()`, `of(T)`, `of(T[])`, `concat()`, `generate()`, `iterate()`,
`ofNullable()`) are included in the completion list alongside instance methods.  Only instance
members should be offered on an instance receiver.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "java.util.stream.Stream.of(\"\")." at 55
  → of(T)          [Method]  Stream   ← static, should not appear
  → of(T[])        [Method]  Stream   ← static, should not appear
  → builder()      [Method]  Builder  ← static, should not appear
  → filter(…)      [Method]  Stream   ← instance, correct
  → map(…)         [Method]  Stream   ← instance, correct
```

**Root cause:** `ProposalGenerator.proposeMemberAccess` filters to statics when
`isStaticAccess == true`, but when `isStaticAccess == false` (instance receiver) it passes
all members through:

```java
.filter(el -> !isStaticAccess || el.getModifiers().contains(Modifier.STATIC))
```

The condition needs to be two-sided: exclude statics on instance access as well as excluding
instance members on static access.  Fix: replace with

```java
.filter(el -> isStaticAccess
    ? el.getModifiers().contains(Modifier.STATIC)
    : !el.getModifiers().contains(Modifier.STATIC)
      || el.getKind() == ElementKind.ENUM_CONSTANT)
```

(Enum constants are always offered regardless of access style.)

---

### Gap J — No completions after `::` (method reference)

**Symptom:** Typing `String::` (or any `Type::`) returns no completions.  The user cannot
discover available method references by typing `::` and browsing candidates.

**Verified on:** `MongoDbClient.java` (helidon mongodb module)

```
inject "…Stream.of("").map(z -> z.charAt(9)).map(String::" at 55
  →  (no completions)
```

Context works correctly on both sides of the method reference — lambda parameter type
inference and chain type propagation through the method reference both function — but the
`::` position itself is not handled as a completion site.

**Root cause (suspected):** The sentinel parser does not recognise the `MEMBER_REFERENCE`
tree node as a completion context.  Completing after `::` requires enumerating the static
(or instance, for `instance::`) methods of the referenced type that are compatible with the
functional interface expected at the call site.  This is a distinct context from plain
member-access and requires its own handling in `SentinelContext` / `CompletionEngine`.

---

### Gap K — Keywords not filtered by syntactic context (low priority)

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
inject "import "        at 30  →  [] keywords  (static missing)
inject "        return " at 55  →  [if, for, while, do, switch, try,
                                    synchronized, throw, break, continue,
                                    final, var, assert, …]
inject "        String foo = " at 55  →  same full set as above
inject "        if ("   at 55  →  [new, null, true, false, this, super]  ✅
```

**Root cause (suspected):** `KeywordProvider` maintains a flat keyword set for
statement-level contexts.  The context classifier (`SentinelContext`) knows the tree node at
the cursor position but does not yet map all node types to their allowed keyword subsets.
Fixing this requires extending the context classifier to recognise expression-position nodes
(`ASSIGNMENT`, `RETURN`, `VARIABLE` initialiser, …) and narrowing the keyword list
accordingly, and separately handling the `IMPORT` node to offer only `static`.

---

## Closed

All gaps identified before 2026-05-27 have been addressed.
