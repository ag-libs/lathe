# Lathe — Semantic Token Highlighting for Type References in Imports

## Problem

When Neovim or VS Code highlights a Java source file, it uses static syntactical parser rules (like Tree-sitter or TextMate regexes) before any language server is active.
Because these parser rules are strictly syntactic, they lack compiler-level semantic context.

A notable symptom is the treatment of all-uppercase type names (such as `UUID`, `URI`, or `URL`) inside `import` statements or type annotations.
Naive syntax rules classify any identifier matching `^[A-Z0-9_]+$` as a constant (`@constant`), coloring it differently from other CamelCase types like `ArrayList` or `Pattern`.

Lathe currently does not publish semantic tokens for standard type symbols (`class`, `interface`, `enum`) or scan import declarations.
Consequently, Neovim falls back to Tree-sitter, which incorrectly colors `UUID` as a constant.

## Goal

Extend Lathe's semantic tokens capability (`textDocument/semanticTokens/full`) to classify and emit tokens for `class`, `interface`, and `enum` types, specifically covering both type references in class bodies and the actual type names in `import` statements.

This will override naive Tree-sitter regex rules on the client, ensuring all class/type symbols are highlighted consistently.

---

## Technical Design

### 1. Registering new token types

We must add `"class"`, `"interface"`, and `"enum"` to the supported token types inside `TokenScanner.java`:

```java
public static final List<String> TOKEN_TYPES =
    List.of("enumMember", "method", "property", "typeParameter", "annotation", "class", "interface", "enum");
```

---

### 2. Type resolution in imports

Currently, `TokenScanner` (which extends `TreePathScanner`) does not override `visitImport`.
To resolve types in imports, we will add an override for `visitImport`:

```java
@Override
public Void visitImport(final ImportTree node, final Void ignored) {
  // If it's a static import, we only care about fields/methods if we want to highlight them,
  // but for a normal class import (e.g. `import java.util.UUID;`), getQualifiedIdentifier()
  // returns the MemberSelectTree corresponding to the full type path.
  if (!node.isStatic()) {
    final var typeTree = node.getQualifiedIdentifier();
    final var element = SourceLocator.elementAt(trees, getCurrentPath());
    if (element != null && isTypeKind(element.getKind())) {
      final String simpleName = element.getSimpleName().toString();
      final long endPos = positions.getEndPosition(cu, typeTree);
      final long nameStart = endPos - simpleName.length();
      if (endPos >= 0 && nameStart >= 0) {
        emitTypeToken(element, nameStart, simpleName.length());
      }
    }
  }
  return super.visitImport(node, ignored);
}
```

---

### 3. Emitting type tokens

We will update `emitIfInteresting` and add a new helper `emitTypeToken` to map compiler `ElementKind` to semantic token types:

```java
private void emitTypeToken(final Element element, final long pos, final int length) {
  final var kind = element.getKind();
  if (kind == ElementKind.CLASS || kind == ElementKind.RECORD) {
    addToken(pos, length, "class", Set.of());
  } else if (kind == ElementKind.INTERFACE) {
    addToken(pos, length, "interface", Set.of());
  } else if (kind == ElementKind.ENUM) {
    addToken(pos, length, "enum", Set.of());
  }
}
```

We will also update `emitIfInteresting` to dispatch to `emitTypeToken` when encountering class/interface/enum elements inside identifiers or member selects.

---

## Verification & Tests

### Unit Tests
Verify the semantic token outputs in `TokenScannerTest`:
- `import_class_has_class_token` — assert `import java.util.UUID;` emits a token of type `class` on the range of the word `UUID`.
- `import_static_class_has_class_token` — assert static class imports are resolved.
- `local_class_reference_has_class_token` — assert class references in code body (declarations, variable types, new expressions) receive correct tokens.

### Client-side Integration
In Neovim, themes that support LSP semantic tokens (like `neodarcula`) will map these to standard highlight groups:
- `@lsp.type.class.java` $\rightarrow$ linked to `@type`
- `@lsp.type.interface.java` $\rightarrow$ linked to `@type` or `@interface`
- `@lsp.type.enum.java` $\rightarrow$ linked to `@type`
This will override the fallback `@constant` highlight for `UUID` inside the client.
