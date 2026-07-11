# Lathe — Find Instantiations of a Type

## Problem / motivation

`textDocument/references` returns *all* references to a symbol; there is no way to ask for only the
places a type is **instantiated** (`new X(...)`).
`ReferenceContext` carries only `includeDeclaration`, so the LSP request itself cannot express a
"constructor calls only" filter.

The user wants to find instance-creation sites specifically — every `new X(...)` for a type — as a
distinct query from "all references to `X`".

## Sketch

Model this as a **type-scoped instantiation query**, not a constructor-symbol query: match javac
`NewClassTree` nodes whose resolved created type is the target type (`NewClassTree.getIdentifier()`
→ the type element), reusing the existing `ReferenceTarget`/candidate pipeline with a
`NewClassTree`-only matcher.

Because the *type name* at a `new X(...)` site is always present, this handles the cases where there
is no constructor to target:

- a class with **no declared constructor** (javac synthesizes a positionless default constructor);
- a **record with no explicit canonical constructor** (javac synthesizes the canonical constructor
  from the header; a compact constructor has a position, a fully implicit one does not).

In all of these the constructor symbol exists in the element model but is **positionless**, so
"invoke references on the constructor declaration" has nothing to click — whereas type-scoped
`NewClassTree` matching works uniformly.

Surface it as a dedicated **command / code action — "Find instantiations of `X`"** — anchored on the
type, rather than a hidden mode of `textDocument/references`. That both avoids the missing-clickable-
constructor problem and resolves the type-vs-constructor disambiguation (plain references on the type
name keep meaning "all references").

Per-overload precision (`only calls to Foo(int,int)`) is a later refinement: recover the specific
constructor from a clicked `new X(...)` site (`((JCNewClass) tree).constructor`, which resolves even
to synthetic constructors) or from `ElementFilter.constructorsIn(elements.getAllMembers(typeEl))`.

## Open questions

- **Invocation surface.** Command vs. code action vs. a custom request — a command/code-action is
  standard-compatible; a request-param extension would be off-spec and only help Lathe-aware clients.
- **Scope of "instantiation".** `new X(...)` only, or also `this(...)`/`super(...)` constructor
  delegations? Implicit `super()` calls are positionless and usually not what the user means, so the
  first cut is `NewClassTree` only.
- **Anonymous classes and diamonds.** `new X() { ... }` and `new X<>(...)` are `NewClassTree` nodes
  too; confirm they resolve to the intended type and are included.
- **Call-hierarchy alternative.** `incomingCalls` on a constructor is the LSP-native "who
  instantiates" primitive, but `CallHierarchyItem` requires a range and synthetic constructors have
  none, so it would need a synthesized range — awkward compared with the type-scoped approach.
- **Reuse and freshness.** Same reactor scope, candidate discovery, and staleness surface as Find
  References (see WS-1).

## Milestone candidate

Untriaged; deferred — the user will look into it later. Moderate scope, since it reuses the existing
reference-candidate and attribution pipeline with a `NewClassTree` matcher plus a command surface.
