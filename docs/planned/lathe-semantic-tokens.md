# Lathe — Semantic Token Coverage

## Motivation

This work is editor-agnostic. It was originally scoped for VS Code, but a Neovim user has also
requested distinct highlighting for local variables versus class fields — so the semantic-token
improvements below benefit every supported editor, not only VS Code.

## Background

Editor syntax highlighting works in two layers:

1. **Base layer** — a lexer/grammar applied before any LSP.
   In VS Code this is a regex-based TextMate grammar (`java.tmLanguage.json`); in Neovim it is
   tree-sitter. Both cover keywords, string and comment literals, and coarse patterns
   (e.g. `[A-Z][A-Za-z]*` as a probable type name).
2. **Semantic tokens** (overlay layer) — LSP-provided, applied on top.
   VS Code themes map them via `@lsp.type.<name>` and `@lsp.mod.<name>` selectors;
   Neovim maps them via `@lsp.type.<name>` highlight groups.
   Can be disabled per-user (e.g. `"editor.semanticHighlighting.enabled": false` in VS Code).

The difference between editors is the strength of the base layer.
Neovim's tree-sitter produces a precise AST and can classify many identifiers on its own, but its
classification is not always correct or complete — the local-variable-vs-field distinction that
prompted this work is a case tree-sitter does not reliably resolve, so Neovim needs LSP semantic
tokens for it too.
VS Code's TextMate grammar is regex-based and cannot distinguish a local variable from a field
from a parameter at all without semantic information from the LSP.

Consequently, both editors depend on the LSP to emit semantic tokens for the identifier
categories the base layer cannot classify — the same way `vscode-java` (Red Hat / jdtls) does.

## What Lathe currently emits

Legend declared in `TokenScanner`:

```
tokenTypes:     enumMember, method, property, typeParameter, annotation
tokenModifiers: declaration, static, deprecated
```

Coverage rules:

- `enumMember` — enum constant declarations and references (always)
- `typeParameter` — type parameter declarations and references (always)
- `annotation` — annotation names (always); non-standard LSP name but themed by Java-aware themes
- `method` — **only** static or deprecated methods (declarations and references)
- `property` — **only** static or deprecated fields (declarations and references)

## What is missing for full VS Code coverage

### Missing token types

| Type | Covers | Priority |
|---|---|---|
| `class` | Class, interface, enum, record names — declarations and all reference sites | High |
| `parameter` | Method and constructor parameters — declarations and all reference sites | High |
| `variable` | Local variables (including `var`, for-each, resource) — declarations and all reference sites | High |

### Incomplete coverage of existing types

| Type | Current behaviour | Required behaviour |
|---|---|---|
| `method` | Only static or deprecated | All methods — drop the `interestingModifiers` guard |
| `property` | Only static or deprecated fields | All fields — drop the `interestingModifiers` guard |

### Missing modifiers

| Modifier | Covers | Priority |
|---|---|---|
| `abstract` | Abstract classes and methods | Low |
| `readonly` | `final` fields and `final`/effectively-final local variables | Low |

`namespace` (package names in declarations and imports) is omitted intentionally —
themes rarely give it a distinct colour and it adds noise without semantic value.

## Implementation plan

All changes are in `TokenScanner`. The LSP protocol requires that any new type or modifier
is added to the legend (`TOKEN_TYPES` / `TOKEN_MODIFIERS`) before it can be referenced
in emitted tokens. Adding to the legend is additive and backward-compatible.

### Step 1 — Widen `method` and `property` coverage

Remove the `!mods.isEmpty()` guard in `visitMethod` and the matching guard in
`emitIfInteresting` for `ElementKind.FIELD` and `ElementKind.METHOD`.
Every resolved method and field then emits a token; static/deprecated modifiers remain accurate.

Impact: existing `NoToken` tests for `regular_method_decl_has_no_token` and
`instance_field_usage_has_no_token` become assertions of the old behaviour and must be
updated to assert a token is present.

### Step 2 — Add `class`

Add `"class"` to `TOKEN_TYPES`.

Add `visitClass` override: emit the class/interface/enum/record name at declaration.

Update `emitIfInteresting` to handle `ElementKind.CLASS`, `INTERFACE`, `ENUM`, `RECORD`,
`ANNOTATION_TYPE` — emit `"class"` with `"declaration"` modifier at the definition site,
and without `"declaration"` at reference sites.

### Step 3 — Add `parameter` and `variable`

Add `"parameter"` and `"variable"` to `TOKEN_TYPES`.

In `visitVariable`, extend the `ElementKind` switch beyond `ENUM_CONSTANT` and `FIELD`:

- `PARAMETER` → `"parameter"` type, `"declaration"` modifier
- `LOCAL_VARIABLE`, `RESOURCE_VARIABLE`, `BINDING_VARIABLE`, `EXCEPTION_PARAMETER` → `"variable"` type, `"declaration"` modifier

In `emitIfInteresting` (called from `visitIdentifier` and `visitMemberSelect`), add:

- `PARAMETER` → `"parameter"`
- `LOCAL_VARIABLE`, `RESOURCE_VARIABLE`, `BINDING_VARIABLE`, `EXCEPTION_PARAMETER` → `"variable"`

### Step 4 — Optional: `abstract` and `readonly` modifiers

Add `"abstract"` and `"readonly"` to `TOKEN_MODIFIERS`.

In `interestingModifiers`: add `Modifier.ABSTRACT` → `"abstract"`,
`Modifier.FINAL` on a field or variable → `"readonly"`.

## Reference: jdtls legend

For comparison, the legend advertised by `vscode-java` (Eclipse JDT / jdtls):

```
tokenTypes:     namespace, class, interface, enum, enumMember, typeParameter,
                annotation, annotationMember, method, function, property,
                variable, parameter, modifier
tokenModifiers: public, private, protected, static, abstract, final,
                deprecated, readonly, declaration, ...
```

Lathe does not need to match jdtls exactly. The standard LSP type names listed above
(`class`, `parameter`, `variable`) are what VS Code themes use in their `@lsp.type.*` rules.
