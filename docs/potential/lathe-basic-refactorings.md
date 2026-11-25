# Lathe — Refactorings and Code Actions

**Status (2026-11-25):** rename and every extraction (variable, field, constant, method, each with a "replace all occurrences" form) have shipped
([Rename](../done/lathe-rename.md), [Extract Variable](../done/lathe-extract-variable.md),
[Extract Method](../done/lathe-extract-method.md)).
Move remains a request.
A comparison with jdtls on spring-petclinic (2026-11-25, below) lists the code actions jdtls offers and Lathe does not; they are requests, not planned work, tracked as [EG-114](../gaps/gaps.md), deferred while the feature freeze holds.

## Problem / motivation

Lathe offers navigation, completion, quick fixes and the basic refactorings, but an editor user coming from jdtls (VS Code's Java extension, nvim-jdtls) meets fewer code actions.
The gap shows most on errors: jdtls offers a fix for almost every compile error, Lathe for a few kinds (a missing import, an unreported exception, an undeclared variable, a missing abstract method).

What Lathe offers today, through the [code action providers](../done/lathe-code-actions.md):

- Quick fixes: import a type or "Add missing imports…", add `throws`, wrap in try/catch, declare a local variable, implement abstract methods, add a constructor parameter for a field.
- Refactorings: extract variable, field, constant and method (each optionally replacing every occurrence), surround with try-with-resources, convert to `var` and replace `var` with its type.

## jdtls comparison (2026-11-25)

The probe asked both servers for code actions at 11 positions, 3 selections and 4 injected errors in spring-petclinic (`500158f`): jdtls 1.63.0 with vscode-java's defaults, and Lathe `0.1.0-SNAPSHOT` at `bfba80c`.
What jdtls offers that Lathe does not, by group:

| Group | jdtls action | Where it showed |
|---|---|---|
| Quick fix | Create method | a call to an undefined method |
| Quick fix | Change to `<similar type>`; Create class, interface, enum, record | a misspelled type (`Strng`) |
| Quick fix | Remove the variable; Add `@SuppressWarnings("unused")` | an unused local |
| Quick fix | Add return statement; Change return type | a missing return |
| Source | Generate getters, setters, `toString()` | on a class or field |
| Source | Add Javadoc comment | on a method or field |
| Source | Organize imports; Sort members | anywhere in a class |
| Source | Add `final` (to one variable, or "where possible") | anywhere |
| Refactor | Inline method; Inline local variable | on a method, a local |
| Refactor | Change signature | on a method |
| Refactor | Make static | on a method or a selection |
| Refactor | Introduce parameter | on a selected expression |
| Refactor | Split variable declaration; Change the type of a variable | on a local |

jdtls also advertises capabilities Lathe does not: code lens and selection range (already [EG-064 and EG-018](../gaps/gaps.md)), inlay hints and on-type formatting (deferred, see [status](../status.md)), and range formatting.

Not compared: snippet templates (`sysout`, `fore`), as the probe's client did not announce snippet support; and move, which neither server offered at the probed positions.
jdtls failed one request: a code action on the selection `pet.getName()` in `PetValidator` threw a `NullPointerException` in its Make Static refactoring.

## Sketch

Every action keeps Lathe's rules: computed from the attributed javac tree, never from text; returned as a `WorkspaceEdit`; inserted code formatted in the project's style; reactor sources only.
Each fits the existing provider dispatch (a diagnostic's classified payload for a quick fix, the request's range for a refactoring), so none needs a new mechanism.

Suggested order, by what a user meets most and what costs least:

1. **Quick fixes on errors** — create method (the signature from the call's argument types and expected return type), change a misspelled type to a near name from the type index, remove an unused local, add a return statement.
   These are the gap users notice first, and each builds on the classification `enrichWithContext()` already does.
2. **Generate source** — getters and setters, `toString()`, and organize imports as an action of its own (today it runs only as part of formatting).
   Mechanical, from the class's fields; records and Lombok classes need no getters.
3. **Inline local variable** and **split variable declaration** — single-method edits on the attributed tree.
4. **Inline method, change signature, make static, introduce parameter** — cross-file edits that need the reference search rename already uses, with its freshness rules.
5. **Move** (from the original request) — a top-level type through `WorkspaceEdit` resource operations (`RenameFile`), rewriting its `package` and every import of it; a member by cutting it and updating references.

Add `final`, Add Javadoc comment and Sort members are left out: style choices a formatter or a linter owns, with little value in a language server.

## Open questions

- **Which errors get a fix first.** The quick-fix list came from four injected errors; a count of the diagnostics real edits produce (the editor smoke, QA sessions) would rank them better.
- **Near-name matching for a misspelled type.** The type index ranks candidates; how many to offer, and whether to include JDK types the file does not import.
- **Create method's placement and modifiers.** In the calling class or the receiver's type (a reactor type only), static when called from a static context, the visibility the call needs.
- **Cross-file refactorings' freshness.** Inline method and change signature share rename's reference search and its staleness surface: a missed closed-file use is a correctness bug, not a stale hint.
- **Parameter prompts.** Change signature and introduce parameter need input (the new order, a name); LSP has no standard dialog, so each editor client needs a command, as rename uses the client's own prompt.
- **Move's resource operations.** Needs `documentChanges` with `RenameFile`; confirm the client capability (`workspace.workspaceEdit.resourceOperations`) in Neovim and VS Code.

## Milestone candidate

Untriaged.
Group 1 (quick fixes on errors) is the candidate for the next feature milestone; groups 2 and 3 are moderate; group 4 and move are heavier and need per-operation designs.
