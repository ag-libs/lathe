# Lathe — Completion Presentation

## Status

Implemented:

- Slice 1 — type rows use `CompletionItem.labelDetails.description` for package display.
- Slice 2 — method and field presentation use a UI-only `TypeMirror` display formatter.
- Slice 3 — method labels are bare names,
  with parameters in `labelDetails.detail`,
  return type in `labelDetails.description`,
  and richer method summaries in `detail`.
- Slice 4 — annotation element completions route through `CompletionCandidate` and `CompletionItemPresenter`.

Deferred:

- Slice 5 — client-capability fallback for older clients.
  Lathe is unreleased,
  so the server currently always emits the modern `labelDetails` shape.

## Problem

Lathe completion candidates already carry enough semantic information to insert the right text,
rank candidates,
and add import edits.
The final LSP response still uses a compact presentation shape:
method labels include parameter types,
type completions put the fully qualified name only in `detail`,
and no completion item uses `labelDetails`.

Modern Java clients are tuned around the JDT LS completion shape.
For type completions,
JDT LS shows the simple type name as the label and the package as the row description.
For methods,
JDT LS separates the method name,
parameter list,
return type,
and richer detail string.

Lathe should adopt that protocol shape while keeping the implementation small and aligned with the existing
`CompletionCandidate` and `CompletionItemPresenter` boundary.

## Goals

- Emit `CompletionItem.labelDetails` for type and method completions.
- Show type packages in the completion row without baking package text into the label.
- Show generic method parameter and return types in completion rows.
- Keep insertion behavior unchanged:
  simple type names insert simple type names,
  methods insert method-call snippets,
  and import edits remain attached to the original completion item.
- Preserve `filterText` as the bare symbol name so fuzzy matching remains stable.
- Keep all LSP item construction centralized in `CompletionItemPresenter`.

## Non-goals

- Client-capability fallback for older clients.
  Lathe is unreleased,
  and the initial implementation may always emit `labelDetails`.
- Signature help.
  Completion labels can display generic parameter types,
  but signature-help request handling is separate future work.
- Full JDT LS internal architecture parity.
  Lathe should match the response contract,
  not copy JDT LS provider structure.
- Documentation or Javadoc population through `completionItem/resolve`.
- Changing completion ranking,
  candidate discovery,
  import insertion,
  or typed-slot filtering.

## Target LSP Shapes

Type completion:

```json
{
  "label": "ArrayList",
  "kind": "Class",
  "filterText": "ArrayList",
  "insertText": "ArrayList",
  "detail": "java.util.ArrayList",
  "labelDetails": {
    "description": "java.util"
  }
}
```

Method completion:

```json
{
  "label": "add",
  "kind": "Method",
  "filterText": "add",
  "insertText": "add($1)",
  "detail": "List.add(E e) : boolean",
  "labelDetails": {
    "detail": "(E e)",
    "description": "boolean"
  }
}
```

For overloaded methods,
the display should rely on `labelDetails.detail`,
`detail`,
and the existing replacement/insert fields rather than embedding the signature in `label`.

## Candidate Model

Extend `CompletionCandidate` with presentation-only fields:

```java
String labelDetail;
String labelDescription;
```

These map directly to:

```text
CompletionItem.labelDetails.detail
CompletionItem.labelDetails.description
```

The existing `detail` field remains the richer item detail string.
For types,
it remains the fully qualified name.
For methods,
it should become a method summary such as:

```text
List.add(E e) : boolean
```

## Type Display Formatter

Add a small display formatter for `TypeMirror` values.
This formatter is for UI text only;
it must not replace semantic `TypeMirror` values used for assignability or filtering.

Expected output examples:

```text
java.util.List<java.lang.String>         -> List<String>
java.util.Map<java.lang.String, V>       -> Map<String, V>
T[]                                      -> T[]
? extends java.lang.Number               -> ? extends Number
? super java.lang.String                 -> ? super String
```

The formatter should handle:

- `DeclaredType` with generic type arguments.
- `ArrayType`.
- `TypeVariable`.
- `WildcardType`.
- Primitive and other simple fallback kinds.

For declared types,
use the element simple name plus recursively formatted type arguments.
For unresolved or awkward cases,
fall back to `type.toString()` rather than failing completion.

## Method Type Substitution

Method completion must format the member-substituted executable type when a receiver type is available.
For member access,
derive the executable type with:

```java
(ExecutableType) types.asMemberOf(receiverType, method)
```

Use that substituted executable type for both:

- parameter display
- return type display

This matters for generic receivers.
For example,
`List<String>.get(int)` should display `String`,
not the declaration variable `E`.

If `asMemberOf` fails,
fall back to the declaration parameter and return types,
matching the current defensive behavior.

## Type Candidates

For type-index and javac type candidates:

- `label`: simple type name
- `filterText`: simple type name
- `insertText`: simple type name
- `detail`: fully qualified name
- `labelDetails.description`: package name

Examples:

```text
ArrayList    java.util
String       java.lang
MyType       com.example
```

For default-package types,
omit `labelDetails.description`.

## Method Candidates

For method candidates:

- `label`: method simple name
- `filterText`: method simple name
- `insertText`: existing method-call insert text or snippet
- `labelDetails.detail`: formatted parameter list including names when available
- `labelDetails.description`: formatted return type
- `detail`: declaring simple type,
  method name,
  formatted parameter list,
  and return type

Parameter names should come from `ExecutableElement.getParameters()`.
When parameter names are synthetic or unavailable,
the formatter may omit names and show only types.

Examples:

```text
add        (E e)                         boolean
subList    (int fromIndex, int toIndex)  List<E>
entrySet   ()                            Set<Map.Entry<K, V>>
```

The zero-argument method detail should be `()`.

## Field Candidates

Field and enum-constant candidates can also use generic type display:

- `label`: field name
- `filterText`: field name
- `insertText`: field name
- `detail`: formatted field type
- `labelDetails.description`: formatted field type

This is not required for JDT LS type/package parity,
but it keeps member rows consistent.

## Presenter

`CompletionItemPresenter` remains the only class that creates LSP `CompletionItem` objects.
It should create `CompletionItemLabelDetails` when either candidate label-detail field is present.

The annotation-element completion path currently constructs `CompletionItem` directly in `CompletionEngine`.
Move it through `CompletionCandidate` or a small presenter method before this work is considered complete.

## Tests

Add focused completion tests for:

- type-index candidate includes `labelDetails.description = "java.util"`.
- `java.lang` type candidate includes package description.
- default-package or package-less type candidate omits description.
- method candidate has bare method `label`,
  parameter list in `labelDetails.detail`,
  and return type in `labelDetails.description`.
- generic receiver substitution:
  `List<String>.get(...)` displays return type `String`.
- generic return display:
  `Map<String, Integer>.entrySet()` displays `Set<Entry<String, Integer>>` or the chosen simple nested-type form.
- field candidate displays generic field type without changing insertion.
- annotation-element completion no longer bypasses `CompletionItemPresenter`.

Existing tests that search for method labels containing `name(` should be updated to assert `filterText`,
`labelDetails.detail`,
or `detail` depending on the behavior being tested.

## Implementation Slices

### Slice 1 — Type Rows

Add label-details fields to `CompletionCandidate`,
populate package descriptions for type candidates,
and emit `CompletionItemLabelDetails` from the presenter.

This gives the immediate visible row improvement:

```text
ArrayList    java.util
```

### Slice 2 — Display Type Formatter

Add the `TypeMirror` display formatter and use it for field and method return/parameter presentation.
Keep the old semantic type fields unchanged.

### Slice 3 — Method Row Shape

Switch method labels to bare method names,
move parameter display to `labelDetails.detail`,
move return display to `labelDetails.description`,
and make `detail` a fuller method summary.

Update overload-sensitive tests to avoid relying on label text alone.

### Slice 4 — Presenter Completeness

Remove direct `CompletionItem` construction from annotation-element completions.
All completion items should pass through `CompletionItemPresenter`.

### Slice 5 — Optional Capability Fallback

If Lathe later needs to support older clients,
store `completionItem.labelDetailsSupport` from `InitializeParams`
and choose between modern and fallback labels at presentation time.

This slice is intentionally deferred until there is a concrete client that needs it.
