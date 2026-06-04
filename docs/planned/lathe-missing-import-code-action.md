# Lathe — Missing Import Code Action

## Problem

Lathe already inserts imports from completion items.
When completion proposes a type from the type index, the item carries an `additionalTextEdits` entry that appends the
needed import after the existing import block.

That path works well when the user selects a completion candidate, including in Neovim.
It does not cover the other common editor workflow:
placing the cursor on an unresolved type and invoking `textDocument/codeAction`.

## Goal

Expose missing-import suggestions as LSP quick-fix code actions without changing the existing completion behavior.

Completion should continue to return `additionalTextEdits`.
Code actions should use the same insertion placement logic and return a `WorkspaceEdit`.

## Non-goals

- Organize imports.
- Sort or rewrite the whole import block.
- Remove unused imports.
- Replace completion-side import edits.
- Implement rename or broader source refactorings.

Those are separate features.
The first missing-import slice should be additive and predictable.

## LSP Surface

Advertise code actions from `LatheLanguageServer`:

```java
capabilities.setCodeActionProvider(true);
```

or, if supported cleanly by the current LSP4J version, advertise only quick fixes:

```java
new CodeActionOptions(List.of(CodeActionKind.QuickFix));
```

`LatheTextDocumentService.codeAction(CodeActionParams)` routes through the server worker to `WorkspaceSession`.
The response is a list of `CodeAction` values with:

- `kind = CodeActionKind.QuickFix`
- title such as `Import 'java.util.ArrayList'`
- diagnostics copied from the unresolved-symbol diagnostic when available
- a `WorkspaceEdit` containing one import insertion edit

## Candidate Discovery

The first implementation should be diagnostic-driven.
When the request contains javac diagnostics for an unresolved type, extract the unresolved simple name from the
diagnostic range/content and query the existing `WorkspaceTypeIndex`.

Candidate validation should follow the completion design:
the type index discovers possible fully qualified names, while javac remains the final authority when an attributed
snapshot is available.

If no diagnostic or identifier can be determined, return an empty list.
This keeps `textDocument/codeAction` cheap and avoids speculative actions on unrelated cursor positions.

## Import Edit Reuse

Extract the current completion-private import insertion behavior into a shared helper.
The helper should produce the same edit for both surfaces:

- completion item `additionalTextEdits`
- code action `WorkspaceEdit`

Initial placement stays unchanged:

1. after the last existing import
2. after the package declaration
3. at the top of the file

If the exact import already exists, no action should be returned.
Static imports can use the same helper, but the first code-action slice may focus on normal type imports.

## Neovim Behavior

Adding this server capability is additive for Neovim.

Existing completion import insertion continues to work through completion item `additionalTextEdits`.
Users who invoke `vim.lsp.buf.code_action()` on an unresolved type will now see import quick fixes.
Lightbulb plugins may start showing available actions for unresolved-symbol diagnostics.

The only behavior to avoid is replacing completion-side import edits with code actions.
Completion selection should remain a one-step flow.

## Tests

Unit tests should cover the shared import insertion helper:

- file with package and existing imports
- file with package and no imports
- file with no package
- duplicate import is skipped
- static and non-static import blocks do not corrupt each other

Server/request tests should cover:

- unresolved type diagnostic returns one or more quick-fix actions
- action edit matches the completion insertion placement
- no diagnostic or no candidate returns an empty action list
