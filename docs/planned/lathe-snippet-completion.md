# Lathe — Snippet (Template) Completion

Status: proposed.
Captures the decisions for a minimal set of jdtls-style code templates delivered through standard LSP
completion.
Builds on the completion engine in `lathe-design.md`.

## Motivation

Editors let a developer type a short abbreviation and expand it to a common construct — IntelliJ live
templates, Eclipse/jdtls templates (`psvm`, `sout`, `ctor`, …).
Lathe has no such expansions today.
This adds a deliberately small, built-in set so the highest-frequency boilerplate is one abbreviation
away, through the same completion mechanism a developer already relies on for auto-import.

## Scope

Three templates, nothing more (deliberately minimal):

| Trigger | Context | Expands to |
|---|---|---|
| `psvm` | class body | `public static void main` method |
| `ctor` | class body | a constructor named after the enclosing class |
| `sout` | statement | `System.out.println(…);` |

Expansions (`$0` marks the final cursor position):

```java
// psvm
public static void main(String[] args) {
    $0
}

// ctor  (EnclosingClass is the current type's simple name)
EnclosingClass($0) {
}

// sout
System.out.println($0);
```

## Not an LSP feature — the standard vehicle

LSP has no "template", "abbreviation", or "macro" request.
The vehicle is an ordinary `textDocument/completion` item with `insertTextFormat = Snippet`
(LSP-defined) and snippet syntax (here only the final-cursor `$0`).
This is exactly how jdtls, clangd, and rust-analyzer deliver templates — no protocol extension, and
the same invoke-and-accept flow as auto-import.

## Decisions

1. **Adopt the jdtls approach**: built-in templates surfaced as snippet completion items via standard
   LSP — not a bespoke mechanism and not delegated to a client-side snippet engine.
2. **Minimal set**: `psvm`, `sout`, `ctor`. No others in this slice.
3. **Single `$0` cursor stop only** — no numbered tab stops. Tab-jumping is explicitly not wanted, so
   the whole expansion is one accept keystroke.
4. **Ungated snippet emission (Decision A)**: emit `InsertTextFormat.Snippet` items unconditionally,
   consistent with Lathe's existing snippet items (method-call cursor placement). Gating on the
   client's `completionItem.snippetSupport` with a plain-text fallback (Decision B) is a documented
   follow-up, not this slice.
5. **`ctor` derives its name** from the enclosing class (`ParsedSentinel.enclosingClass()`).
6. **try-with-resources wrapping is out of scope** and a separate subsystem — a code action (sibling
   of `TryCatchWrapProvider`), not a template. See [Relationship to other work](#relationship-to-other-work).

## Contexts (gating)

- `psvm`, `ctor`: a class-body member-start position — `enclosingClass != null`,
  `enclosingMethod == null`, at a member-declaration slot (the position override/type completion
  already use).
- `sout`: a statement position inside a method body.

Gating reuses the signals `KeywordProvider` already uses (`SentinelInjector.Context` plus
`ParsedSentinel`'s enclosing class/method), so a template never appears where it would not compile.

## Architecture

- A new `TemplateCompletionProvider` in the completion package, mirroring
  `KeywordProvider.suggestCandidates(parsed, prefix, context)` → `List<CompletionCandidate>`,
  prefix-filtered and context-gated. The template set is a small built-in table (trigger → context →
  snippet body), so adding a row later is trivial.
- Merged at the same call sites as `KeywordProvider` — the statement path in `CompletionEngine` and
  the class-body/type-reference path in `TypeReferenceCompleter` — reusing the established
  completion-source pattern rather than adding new routing.
- `CandidateKind.SNIPPET` is added and mapped by `CompletionItemPresenter` to
  `CompletionItemKind.Snippet` and `InsertTextFormat.Snippet`, reusing the presenter's existing
  `candidate.snippet()` path.
- Each item: `label` = trigger, `detail` = a short description, a single `$0` cursor.

## Editor experience

The item behaves like any completion (in feel, like auto-import): type the trigger, accept, done.

- With the recommended blink.cmp (which injects `snippetSupport`) or Neovim 0.11+ built-in
  `vim.lsp.completion` + `vim.snippet`, the snippet expands with the cursor at `$0`.
- A single accept keystroke completes the expansion; there is no tab-stop navigation. Accepting the
  top item is the client's normal completion-accept (`<C-y>`, or a user mapping that selects the first
  item and accepts).
- Under Decision A, an older LSP-only client without snippet expansion would see a literal `$0` — the
  same pre-existing exposure Lathe's other snippet items already carry; Decision B removes it.

## Testing

New `CompletionTemplateTest` (via `CompletionTestSupport`):

- `psvm` and `ctor` are offered at a class-body slot, with the correct expansion, `Snippet` kind, and
  `ctor` using the enclosing class name.
- `sout` is offered in a method body with the correct expansion.
- Negative gating: no `sout` in a class body; no `psvm` mid-statement.

## Non-goals

- User-configurable or editable templates.
- Numbered tab stops / multi-placeholder snippets.
- `completionItem/resolve` laziness.
- Any template beyond the three (e.g. `fori`, `iter`, `try`).
- Wrapping an existing statement — that is the future try-with-resources code action.

## Relationship to other work

- A sibling in spirit to `:LatheNew` (scaffolding) and override completion, but distinct: this is
  abbreviation → snippet at the cursor.
- **try-with-resources wrapping** (future) is a code action on a selected or existing statement — the
  sibling of `TryCatchWrapProvider`, producing a `WorkspaceEdit` — a different subsystem, triggered by
  a range rather than a typed prefix. A future `twr` *template* (an empty skeleton), if ever wanted,
  would be one more row in this table; the *wrap* refactor stays in the code-action path. LSP code
  actions cannot portably carry snippet tab stops, so that wrap will place plain text.
