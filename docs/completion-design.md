# Completion Design

Describes how `textDocument/completion` works in Lathe.
The implementation is complete and this document reflects current behaviour.
Open behavioural gaps are tracked separately in [completion-gaps.md](completion-gaps.md).

---

## 1. Three-Layer Model

Completion draws on three independent layers, each answering a different question.

**Layer 1 — Live buffer (`content`)**
The current document string as the user is typing.
Answers: where is the cursor, what has the user typed.

**Layer 2 — Sentinel parse**
A fast parse of the live buffer with a sentinel marker injected at the cursor.
Answers: what is the syntactic structure around the cursor — is the cursor after a
dot, inside an argument list, declaring a variable, inside an import.

**Layer 3 — Attributed snapshot**
A fully attributed `CompilationUnitTree` built after a short idle debounce.
Answers: what type does `list` have, what members does `ArrayList` expose, is
this method accessible from here.

These layers are always kept separate.
The live buffer feeds the sentinel parse.
The sentinel parse produces text (receiver name, enclosing class, argument index).
That text is resolved through the attributed snapshot to get types and members.

---

## 2. Data Flow

```
CompletionRequest
  → SentinelInjector      inject sentinel marker at cursor
  → SentinelParser        classify cursor site from parse tree
  → CompletionSite        prefix, replacement range, CompletionMode
  → SemanticCompletionContext   javac scope, ExpectedValue
  → candidate providers   discover CompletionCandidate lists
  → CompletionCandidateRanker   filter, assign sort text
  → CompletionItemPresenter     build LSP CompletionItem
  → CompletionOutcome
```

`CompletionEngine` orchestrates this flow.
It does not contain filtering rules, ranking logic, or LSP item construction.

---

## 3. Cursor Site Classification

### SentinelContext

`SentinelParser` classifies the cursor by inspecting the AST parent of the
injected sentinel node:

| Value | Triggered by |
|---|---|
| `MEMBER_ACCESS` | Cursor after `.` on a receiver expression |
| `SIMPLE_NAME` | Bare name in a statement or expression |
| `TYPE_REFERENCE` | Type-use position (parameter type, field type, cast) |
| `VARIABLE_DECLARATION` | Cursor in the name slot of a variable declaration |
| `ARGUMENT_POSITION` | Cursor inside a method or constructor argument list |
| `CONSTRUCTOR_CALL` | Cursor after `new` |
| `ANNOTATION_CONTEXT` | Cursor inside an annotation |
| `LAMBDA_BODY` | Cursor inside a lambda body |
| `IMPORT` | Cursor inside an import declaration |
| `STATIC_IMPORT` | Cursor inside a static import declaration |
| `MODULE_DIRECTIVE` | Cursor inside a module directive |

### CompletionMode

`CompletionSite` derives a `CompletionMode` from `SentinelContext` and prefix
shape. The mode determines which candidate providers run:

| Mode | When |
|---|---|
| `VALUE` | Lowercase or empty prefix in a non-member context |
| `TYPE` | `TYPE_REFERENCE`, `CONSTRUCTOR_CALL`, or `ANNOTATION_CONTEXT` |
| `MIXED` | Uppercase prefix in a simple-name context |
| `MEMBER` | `MEMBER_ACCESS` |
| `IMPORT` | `IMPORT` |
| `STATIC_IMPORT` | `STATIC_IMPORT` |
| `KEYWORD_ONLY` | Positions where only keywords are valid |

Uppercase prefix in a simple-name context triggers `MIXED` mode, which
includes type-index candidates ranked high. Lowercase prefix uses `VALUE` mode,
which does not query the type index. Java keywords are all lowercase, so
uppercase prefix naturally excludes them through prefix filtering without any
special suppression logic.

---

## 4. Semantic Context

### SemanticCompletionContext

Holds the javac state for the current cursor site:

```java
record SemanticCompletionContext(
    AttributedFileAnalysis analysis,
    Scope scope,
    TypeElement enclosingType,
    ExpectedValue expectedValue,
    boolean staticContext,
    boolean valueContext) {}
```

`analysis` is the attributed snapshot. It may be absent for no-snapshot fallback
paths.

### ExpectedValue

A sealed type that makes the expected-type state explicit:

```java
sealed interface ExpectedValue {
  record Unknown() implements ExpectedValue {}
  record Type(TypeMirror type) implements ExpectedValue {}
  record NoSlot() implements ExpectedValue {}
}
```

`NoSlot` means the cursor is in an argument position but the target callable has
no parameter at that index (e.g. `noArgs(§)`). The ranker returns an empty list
for `NoSlot`, suppressing all completions.

`TypeResolver` derives the expected type from `ReturnTree`, `VariableTree`
initialisers, and `MethodInvocationTree` argument positions.

---

## 5. Candidates

### CompletionCandidate

The internal model for a single offered symbol:

```java
record CompletionCandidate(
    String name,           // bare symbol name; used as filterText
    String label,          // display text in the menu
    CandidateKind kind,
    String detail,         // return type, field type, or qualified name
    String insertText,     // text to insert on commit
    boolean snippet,       // true when insertText contains $1 placeholder
    String sortText,       // pre-assigned sort key (may be null; ranker fills it)
    TypeMirror valueType,  // type produced by accepting this candidate
    String declaringType,  // qualified name of the declaring type
    ImportEdit importEdit) // non-null when accepting requires an import
```

### CandidateKind

```java
enum CandidateKind {
  KEYWORD, LOCAL_VARIABLE, FIELD, METHOD, PACKAGE,
  TYPE_CLASS, TYPE_INTERFACE, TYPE_ENUM
}
```

### ImportEdit

Carries import intent before LSP presentation:

```java
record ImportEdit(String qualifiedName, boolean isStatic) {}
```

`CompletionItemPresenter.applyImportEdits` converts this to
`additionalTextEdits` in the final LSP item, skipping already-imported symbols.

### Candidate Sources

| Class | Produces |
|---|---|
| `SimpleNameProvider` | Locals, parameters, fields, same-class methods, static-import members |
| `CandidateGenerator` | Member-access candidates, nested types; delegates simple-name to `SimpleNameProvider` |
| `CandidateFactory` | Constructs candidates from `TypeElement`, `TypeIndexEntry`, and member `Element` values |
| `KeywordProvider` | Context-valid keywords |
| `ImportCompletionProvider` | Types and sub-packages inside import declarations |
| `TypeIndexValidator` | Filters type-index entries to those resolvable in the current javac snapshot |

---

## 6. Ranking

`CompletionCandidateRanker` applies all filtering and sort-text assignment in one
place. It receives the full candidate list and `SemanticCompletionContext`.

**Filtering rules:**

- `ExpectedValue.NoSlot` → return empty list.
- Value-sensitive context (`valueContext == true` or `ExpectedValue.Type`):
  - Exclude void-returning methods.
  - Exclude methods declared on `java.lang.Object`.

**Sort text assignment:**

| Condition | Sort text |
|---|---|
| Candidate has pre-assigned sort text | Use it as-is |
| Method declared on `java.lang.Object` | `9_name` |
| Assignable to expected type | `0_name` |
| Not assignable to expected type | `1_name` |
| No expected type | `null` (sorted first) |

Member-access sort text is pre-assigned by `CandidateGenerator`:

| Prefix | Members |
|---|---|
| `0_` | All members except Object-declared |
| `9_` | Object-declared methods (`wait`, `notify`, `toString`, `equals`, etc.) |

---

## 7. LSP Presentation

`CompletionItemPresenter` is the only class that creates `CompletionItem`.

### Fields set on every item

| LSP field | Value |
|---|---|
| `label` | `candidate.label()` — method signature for methods, simple name otherwise |
| `filterText` | `candidate.name()` — always the bare symbol name |
| `insertText` | `candidate.insertText()` — method name only, never the signature |
| `detail` | `candidate.detail()` — return type, field type, or qualified name |
| `sortText` | From ranker |
| `kind` | Mapped from `CandidateKind` (see table below) |
| `insertTextFormat` | `Snippet` when `candidate.snippet()` is true, otherwise omitted |
| `textEdit` | Prefix replacement range applied by `applyReplacementRange` |
| `additionalTextEdits` | Import edit when `candidate.importEdit()` is non-null and not yet imported |

### CandidateKind → CompletionItemKind

| CandidateKind | CompletionItemKind |
|---|---|
| `KEYWORD` | `Keyword` |
| `LOCAL_VARIABLE` | `Variable` |
| `FIELD` | `Field` |
| `METHOD` | `Method` |
| `PACKAGE` | `Module` |
| `TYPE_CLASS` | `Class` |
| `TYPE_INTERFACE` | `Interface` |
| `TYPE_ENUM` | `Enum` |

### Method labels and insertion

Methods with parameters use a snippet insert text with a `$1` placeholder for the
first argument position. The label always includes the resolved parameter types:

```
label      = "add(String)"
filterText = "add"
insertText = "add($1)"
```

Parameter types are resolved through the receiver type where possible, so
`List<String>.add§` presents `add(String)`, not `add(E)`.

No-argument methods close themselves:

```
label      = "trim()"
filterText = "trim"
insertText = "trim()"
```

### Import edits

Type-index candidates and direct static-member-fit candidates carry an
`ImportEdit`. `applyImportEdits` checks the live AST for already-imported
symbols and skips those. Edits are inserted after the last existing import
statement, or after the package declaration if no imports exist.

Import edits are part of the initial `textDocument/completion` response.
They are never deferred to `completionItem/resolve`.

### Replacement range

`applyReplacementRange` sets `textEdit` on every item. The range covers exactly
the typed prefix:

```
range.start = first character of the prefix
range.end   = cursor position
newText     = insertText or label
```

An empty prefix produces a zero-width range at the cursor.

---

## 8. Client Baseline

The primary target client is Neovim with `blink.cmp`. All LSP fields that affect
filtering, ranking, and insertion must be present in the initial completion
response because `blink.cmp` uses them directly without a resolve round-trip.

Fields used by `blink.cmp`:
`label`, `filterText`, `insertText`, `textEdit`, `additionalTextEdits`,
`sortText`, `kind`, `detail`, `documentation`, `data`, `completionItem/resolve`.

The implementation follows standard LSP semantics and works correctly in VS Code
without client-specific adjustments.

---

## 9. Future Work

These features are not yet implemented.

**`completionItem/resolve` for documentation**
Initial completion items do not include Javadoc. A resolve handler should load
documentation lazily using the lower-level `JavadocLocator` and `HoverFormatter`
helpers that hover already uses. The `data` field should carry a stable identity
key (kind, owner type, name, parameter types) so the element can be relocated.
Resolve must not change `textEdit`, `filterText`, `sortText`, or
`additionalTextEdits`.

**Signature help (`textDocument/signatureHelp`)**
After the user commits a method with parameters, the editor sends
`signatureHelp`. This shows the method signature as a tooltip while the user
types each argument. Without it, parameter guidance disappears after commit.

**`InsertReplaceEdit` for in-token completion**
When the cursor is inside or before an existing identifier
(`accept(§connectionString)`), a plain `TextEdit` inserts text without
replacing the existing token, producing duplicates. `InsertReplaceEdit` provides
separate insert and replace ranges to handle both cases cleanly.

**Commit characters**
Setting `commitCharacters: ["."]` on method items allows the editor to
auto-commit the completion when the user types `.`, enabling natural method
chaining. This should be validated with `blink.cmp` before enabling, as it can
be disruptive when there are multiple plausible completions.

**Full snippet tab-stop navigation**
The current snippet format uses only `$1` for the first parameter. Full snippet
support would generate `${1:paramName}, ${2:paramName}` tab stops for all
parameters, enabling tab-key navigation through arguments. This requires the
client to advertise `snippetSupport` in its capabilities.
