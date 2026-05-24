# Completion Presentation Working Design

This document captures the working design for how Lathe should present Java completion items to developers.
It complements [completion_engine_implementation_guide.md](completion_engine_implementation_guide.md)
and [lathe-type-index.md](lathe-type-index.md).

The completion engine answers what symbols are valid at the cursor.
This document answers what the user should see,
what text should be inserted,
how results should be ranked,
and how documentation should be loaded.

The first target client is Neovim with `blink.cmp`.
The design should still follow LSP semantics closely enough to work well in VS Code later.

---

## 1. Goals

- Present Java completions in a way that feels predictable and IDE-grade.
- Keep completion fast enough for interactive typing.
- Separate candidate discovery from item presentation.
- Make insertion exact and conservative.
- Avoid expensive documentation lookup during the initial completion request.
- Leave room for type-index completion and future import edits.

Non-goals for the first slice:

- Snippet insertion for method calls.
- Automatic import edits for type-index candidates.
- Full Javadoc loading during `textDocument/completion`.
- Client-specific behavior for VS Code beyond standard LSP fields.

---

## 2. Client Baseline

The current Lathe Neovim config uses `blink.cmp`:

```lua
require("blink.cmp").setup({
  fuzzy = { implementation = "lua" },
  sources = {
    default = { "lsp", "path", "buffer" },
    per_filetype = {
      java = { "lsp" },
    },
  },
})
```

For Java files, completion comes only from LSP.
This means Lathe controls the menu quality directly.

`blink.cmp` supports the LSP fields that matter for this design:

- `label`
- `labelDetails`
- `kind`
- `detail`
- `documentation`
- `sortText`
- `filterText`
- `insertText`
- `textEdit`
- `additionalTextEdits`
- `data`
- `completionItem/resolve`

The first implementation should not depend on client-specific formatting.
It should send correct standard LSP completion items.

---

## 3. LSP Field Contract

Lathe should treat these fields as separate contracts:

| Field | Purpose |
|---|---|
| `label` | Primary visible text in the menu. |
| `labelDetails` | Later enhancement for signature and origin details. |
| `kind` | Icon/category: method, field, variable, class, interface, enum. |
| `detail` | Compact type information, such as return type or qualified type name. |
| `documentation` | Rich Markdown documentation, filled lazily when practical. |
| `sortText` | Stable server-side ranking key. |
| `filterText` | Text used for fuzzy filtering; usually the simple symbol name. |
| `textEdit` | Exact replacement range and inserted source text. |
| `insertText` | Fallback insertion text when `textEdit` is not used. |
| `additionalTextEdits` | Future import edits. |
| `data` | Resolve key used by `completionItem/resolve`. |

The completion response must include fields that affect filtering, sorting, and insertion.
These must not change during `completionItem/resolve`.

Documentation and some detail fields may be filled lazily during resolve.

---

## 4. Candidate Metadata

Completion presentation should be based on a small internal candidate model,
not only on raw `Element` values.

For member-access completions,
Lathe should preserve:

```text
receiverType
declaringType
memberName
resolvedSignature
returnType
isStatic
isDeprecated
```

`receiverType` is the type of the expression at the cursor.
For example:

```java
ArrayList<String> list;
list.§
```

The receiver type is:

```text
java.util.ArrayList<String>
```

`declaringType` is the type that actually declares the member.
For example:

```text
ArrayList.trimToSize()
List.get(int)
Collection.add(E)
Object.toString()
```

The receiver type is used for generic substitution.
For example,
`List<String>.add§` should present:

```text
add(String)
```

not:

```text
add(E)
```

The declaring type is used for ranking,
documentation lookup,
and optional disambiguation in the UI.

The first Neovim-oriented implementation may show only the return type in `detail`.
It should still compute and retain the declaring type so sorting and later resolve behavior are correct.

Later, clients that present `labelDetails` well may receive:

```text
label                    = "add"
labelDetails.detail       = "(String)"
labelDetails.description  = "Collection"
detail                   = "boolean"
```

Until then,
the portable first slice should keep the signature in `label` and the return type in `detail`.

---

## 5. Presentation Rules

### 5.1 Methods

Initial Neovim-friendly shape:

```text
label      = "subList(int, int)"
filterText = "subList"
textEdit   = replace typed prefix with "subList"
detail     = "List<String>"
kind       = Method
sortText   = stable relevance key
```

The parameter types in `label` should be resolved through the receiver type when possible.
This keeps generic APIs useful:

```text
List<String>.add§      -> add(String)
Map<String, User>.put§ -> put(String, User)
```

The label may contain the method signature.
The inserted source text must not contain the signature unless snippet mode is intentionally enabled.

This avoids inserting invalid source like:

```java
list.subList(int, int)
```

Overloads should remain separate items:

```text
add(E)                 boolean
add(int, E)            void
addAll(Collection)     boolean
addAll(int, Collection) boolean
```

Each overload inserts only the method name.

### 5.2 Fields

```text
label      = "name"
filterText = "name"
textEdit   = replace typed prefix with "name"
detail     = "String"
kind       = Field
```

Field type should also be resolved through the receiver type when possible.
For example,
generic holder fields should display the substituted type rather than the raw type variable.

### 5.3 Local Variables and Parameters

```text
label      = "users"
filterText = "users"
textEdit   = replace typed prefix with "users"
detail     = "List<User>"
kind       = Variable
```

If the type is not cheaply available in the current simple-name scan,
the first implementation may omit `detail`.

### 5.4 Nested Types

```text
label      = "Entry"
filterText = "Entry"
textEdit   = replace typed prefix with "Entry"
detail     = "Map.Entry<K,V>"
kind       = Interface
```

### 5.5 Type-Index Candidates

The type index is not implemented yet,
but its presentation contract should be fixed now.

For broad simple-name type completion:

```text
label      = "ArrayList"
filterText = "ArrayList"
textEdit   = replace typed prefix with "ArrayList"
detail     = "java.util.ArrayList"
kind       = Class
sortText   = stable type relevance key
```

Duplicate simple names should be shown as distinct items:

```text
List    java.util.List
List    java.awt.List
```

The fully qualified name belongs in `detail` for the first slice.
Later, clients that present `labelDetails` well can receive:

```text
label                    = "ArrayList"
labelDetails.description = "java.util"
detail                   = "java.util.ArrayList"
```

Do not store Javadocs in the type index.
The type index is for discovery and cheap ranking.
Documentation should be resolved lazily from javac/source when possible.

---

## 6. Exact Replacement

Lathe should prefer `textEdit` over bare `insertText`.

For:

```java
list.sub§
```

Selecting `subList(int, int)` should replace only `sub` with `subList`.

Conceptual edit:

```text
range.start = tokenStart
range.end   = cursor
newText     = "subList"
```

This keeps insertion deterministic across Neovim and VS Code.
It also avoids relying on each client to infer the prefix replacement from `insertText`.

If `textEdit` is not added in the first implementation,
Lathe must still set `insertText` to the source text,
not the display label.

### 6.1 Position Encoding

Java source offsets are not LSP positions.
When Lathe computes a replacement range from source offsets,
it must convert back to LSP `Position` values through the same coordinate model used elsewhere in the server.

Do not send byte offsets.
Do not assume Neovim stores positions as bytes.

For ordinary Java identifiers,
the replacement range should stay on one line:

```text
range.start.line == range.end.line
range.start.character <= range.end.character
```

The range should contain only the identifier prefix or token being replaced.

### 6.2 InsertReplaceEdit

Neovim users often trigger completion before an existing token:

```java
accept(§connectionString)
```

A zero-width edit would duplicate the token:

```java
accept(connectionStringconnectionString)
```

Lathe should strongly consider using `InsertReplaceEdit` for this case:

```text
insert range  = cursor..cursor
replace range = cursor..endOfIdentifier
newText       = "connectionString"
```

For typed prefixes inside an existing token:

```java
accept(conn§ectionString)
```

the insert range can replace only the typed prefix,
while the replace range can replace the full identifier.

This matches common Java IDE behavior:
completion replaces the identifier under the cursor,
but still supports plain insertion when the user is at an empty position.

### 6.3 Filtering and Overloads

If the visible label is:

```text
add(String)
```

then filtering and insertion must still use:

```text
add
```

Method overloads should have distinct labels but the same insert text:

```text
label      = "add(String)"       text edit = "add"
label      = "add(int, String)"  text edit = "add"
filterText = "add"
```

Until snippets are deliberately enabled,
selecting any overload inserts only the method name.

### 6.4 Resolve Invariant

`completionItem/resolve` must not change fields that affect insertion,
filtering,
or ranking:

```text
textEdit
insertText
filterText
sortText
additionalTextEdits
```

These fields must be final in the initial completion response.
Resolve may fill documentation and other non-critical display fields.

---

## 7. Ranking

Lathe should assign stable `sortText` values.
Do not rely only on stream order.

Suggested member-access buckets:

| Bucket | Meaning |
|---|---|
| `1000` | Members declared directly on the concrete receiver type. |
| `2000` | Members declared on receiver interfaces and non-`Object` supertypes. |
| `3000` | Static helper members when the receiver is a type. |
| `7000` | `Object` boilerplate and other low-value inherited members. |
| `8000` | Deprecated members. |
| `9000` | Low-confidence fallback candidates. |

The bucket decision should use both `receiverType` and `declaringType`.
For example,
when completing on `ArrayList<String>`,
members declared by `ArrayList` should rank before members inherited from `List`,
and both should rank before `Object.toString()`.

When completing static access on an enum type,
enum constants must be treated as useful static members,
not filtered out by a method/field-only member predicate.

Suggested simple-name buckets:

| Bucket | Meaning |
|---|---|
| `0000` | Locals and parameters. |
| `1000` | Fields in the enclosing type. |
| `2000` | Methods in the enclosing type. |
| `3000` | Nested/current-package types. |
| `4000` | Imported types. |
| `5000` | `java.lang` types. |
| `6000` | Other type-index candidates. |
| `7000` | Keywords. |

Sort keys should include a normalized name and overload index:

```text
2000_subList_000
2000_subList_001
```

This makes ordering deterministic for both Neovim and VS Code.

---

## 8. Documentation

Documentation should be loaded lazily with `completionItem/resolve`.

Initial completion response should include:

```text
label
kind
detail
sortText
filterText
textEdit
data
```

Resolve may fill:

```text
documentation
```

It may also fill richer non-critical detail if the client advertised support,
but it must not alter sorting, filtering, or insertion fields.

### 8.1 Reusing Hover

Do not call `CompilationContext.hover(...)` from completion.
That method is cursor-oriented.

Reuse the lower-level pieces instead:

- `HoverFormatter`
- `JavadocLocator`
- `WorkspaceManifest.originLabel(...)`

Introduce a shared helper conceptually named `ElementDocumentation`.
It should accept an already-resolved element and format the same Markdown shape hover uses.

Conceptual inputs:

```text
Element element
TypeMirror type
FileAnalysis snapshot
List<Path> sourceRoots
WorkspaceManifest manifest
JavaFileManager fileManager
```

Conceptual output:

```text
Optional<MarkupContent>
```

Resolution flow:

1. Locate Javadoc with `JavadocLocator`.
2. Resolve origin label from the workspace manifest.
3. Format with `HoverFormatter`.

### 8.2 Attributed Candidate Docs

For method, field, variable, and nested-type completions generated from javac elements,
the resolve key should contain enough information to find the element again.

Possible data fields:

```json
{
  "kind": "member",
  "uri": "file:///...",
  "owner": "com.example.Foo",
  "name": "bar",
  "params": ["java.lang.String", "int"]
}
```

The exact shape can be simpler at first if completion resolve remains module-worker local.

### 8.3 Type-Index Candidate Docs

For type-index candidates:

```json
{
  "kind": "type",
  "uri": "file:///...",
  "qualifiedName": "java.util.ArrayList"
}
```

Resolve should try:

1. `Elements.getTypeElement(qualifiedName)` when a cached analysis exists.
2. Existing source-root / external-source lookup through the same source machinery used by hover and definition.
3. Return the unchanged item if no source or javac context is available.

Completion must never fail because documentation cannot be resolved.

---

## 9. Type Index Interaction

The type index should feed simple-name type completions.
It should not change the member-access path.

Initial type-index slice:

1. Query in-memory index by prefix.
2. Rank cheaply.
3. Return type candidates with `isIncomplete=true` when validation is not available.
4. Use `label`, `detail`, `kind`, `filterText`, `sortText`, and `textEdit`.
5. Do not add import edits yet.

Later validated slice:

1. Use cached javac `Elements.getTypeElement(qualifiedName)`.
2. Filter inaccessible types when the current context is known.
3. Prefer same package, imported packages, and `java.lang`.
4. Mark incomplete when the validation deadline is reached.

Future import-edit slice:

```text
textEdit.newText       = "ArrayList"
additionalTextEdits    = import java.util.ArrayList;
```

Import edits should be added only after there are focused tests for import placement,
duplicate imports,
same-package types,
`java.lang`,
static imports,
and JPMS visibility.

---

## 10. Snippets and Parentheses

Do not use method-call snippets in the first presentation slice.

Good first behavior:

```java
list.subList
```

Later optional behavior:

```java
list.subList(${1:fromIndex}, ${2:toIndex})
```

Snippet insertion should depend on client capabilities and a deliberate Lathe setting.
Neovim users often have strong preferences here,
and automatic parentheses can be disruptive when completion is used for method references.

Commit characters can be considered later.
For example, accepting a method completion with `(` could insert the method name and let normal typing provide the parenthesis.

---

## 11. Implementation Shape

Keep candidate discovery and presentation separate.

Suggested package-private classes:

```text
CompletionItemPresenter
CompletionSortKey
CompletionResolveData
CompletionMemberCandidate
ElementDocumentation
```

`ProposalGenerator` should continue finding candidates.
It should produce enough candidate metadata for presentation:

```text
receiverType
declaringType
member element
resolved member type
prefix
replacement range
```

It should delegate item construction to `CompletionItemPresenter`.

`CompletionEngine` should remain orchestration:

1. Build/inject sentinel.
2. Parse sentinel.
3. Resolve receiver/context.
4. Ask `ProposalGenerator` for candidates.
5. Return presented LSP items.

Do not put source lookup or Javadoc parsing into `ProposalGenerator`.

---

## 12. Tests

### Presentation Tests

Add focused tests around item shape,
not only candidate presence.

Method item:

```text
memberAccess_methodCompletion_formatsLabelAndInsertText
```

Assert:

```text
label      = "add(String)"
filterText = "add"
textEdit.newText = "add"
detail     = "boolean"
kind       = Method
```

Field item:

```text
memberAccess_fieldCompletion_setsTypeDetail
```

Assert:

```text
label      = "name"
filterText = "name"
textEdit.newText = "name"
detail     = "String"
kind       = Field
```

Variable item:

```text
simpleName_localVar_setsInsertAndFilterText
```

Overloads:

```text
memberAccess_overloads_preservedAsSeparateItems
```

Assert separate labels but identical insertion text:

```text
add(String)      -> add
add(int, String) -> add
```

Regression:

```text
memberAccess_methodCompletion_doesNotInsertSignature
```

This protects the most important presentation bug.

### Text Edit Tests

Test exact prefix replacement:

```text
memberAccess_prefixTyped_textEditReplacesPrefixOnly
```

For:

```java
list.sub§
```

Assert:

```text
range.start = position of "s" in "sub"
range.end   = cursor
newText     = "subList"
```

Also cover an empty prefix:

```java
list.§
```

The edit range should be zero-width at the cursor.

Cover an existing token under the cursor:

```java
accept(§connectionString)
accept(conn§ectionString)
```

When using `InsertReplaceEdit`,
assert both ranges:

```text
insert range  = cursor..cursor or prefixStart..cursor
replace range = tokenStart..tokenEnd
newText       = "connectionString"
```

Completion insertion should be verified in Neovim with `blink.cmp`,
not only through programmatic LSP probes.
Manual smoke cases:

```text
list.ad§
list.§
accept(§connectionString)
accept(conn§ectionString)
list.add§
```

### Documentation Tests

The first presentation slice may not implement resolve,
but the design should be tested when it lands.

Suggested tests:

```text
completionResolve_sameFileMethod_addsJavadoc
completionResolve_reactorSourceMethod_addsJavadoc
completionResolve_missingSource_returnsUnchangedItem
completion_doesNotLoadDocumentationEagerly
```

### Type-Index Tests

When type-index completion lands:

```text
simpleName_typeIndexCandidate_usesSimpleLabelAndQualifiedDetail
simpleName_duplicateTypeNames_disambiguatedByDetail
simpleName_typeIndexCandidate_replacesPrefixOnly
simpleName_typeIndexFallback_returnsIncompleteList
completionResolve_typeIndexCandidate_resolvesDocsWhenElementAvailable
```

---

## 13. References

- LSP 3.17 completion specification:
  `textDocument/completion`, `completionItem/resolve`, `CompletionItem`, `CompletionItemLabelDetails`.
- VS Code API:
  `CompletionItemProvider`, especially the rule that sort/filter/insert/range fields must not change during resolve.
- Eclipse JDT `CompletionProposal`:
  replacement range, completion string, proposal kind, relevance, and proposal metadata.
- `blink.cmp` LSP support:
  support for label details, detail, documentation, sort/filter text, text edit, additional edits, and resolve.

---

## 14. Working Decisions

- Use `textEdit` for exact insertion when practical.
- Prefer `InsertReplaceEdit` for completion before or inside an existing identifier.
- Never let the visible method signature become the inserted source text.
- Keep snippets and commit characters out of the first insertion slice.
- Keep Javadocs out of the type index.
- Reuse hover formatting through lower-level element documentation helpers.
- Prefer lazy documentation via `completionItem/resolve`.
- Keep snippets and import edits as later slices.
