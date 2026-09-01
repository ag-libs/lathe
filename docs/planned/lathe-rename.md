# Lathe — Rename (`textDocument/rename`)

## Status

Planned — M2. Approved design; no code yet. The freshness gate was revised after a code audit of the
reference pipeline — see Correctness gate and Resolved decision 2.

This is the focused design the roadmap's M2 "Refactoring" item calls for
("prepare-rename and exact reactor rename edits, correctness-gated with explicit non-goals").
It builds on the Find References machinery documented in
[lathe-find-references.md](../done/lathe-find-references.md) and the exploratory sketch in
[lathe-basic-refactorings.md](../potential/lathe-basic-refactorings.md).

## Goal

Rename a Java symbol and every reference to it across the reactor from Neovim, as one atomic edit,
correctly enough to trust on real projects.
The design is deliberately scoped to the common ~80% of renames and is **correctness-gated**: a rename
either covers a case safely and with tests, or that case is an explicit non-goal — a rename that
silently misses occurrences corrupts code, which is worse than not offering it.

## Prior art

Both mainstream implementations share one shape — *validate + range → find all references → one atomic
`WorkspaceEdit`, conflict-checked first* — and Lathe follows it.

- **IntelliJ** (`RenameProcessor` / `RenamePsiElementProcessor`): `prepareRenaming` collects the linked
  element set (e.g. a super method pulls in its overrides) and runs `findExistingNameConflicts`, then
  find-usages → `classifyUsages` → `renameElement`. Non-code usages (comments/strings) are opt-in;
  conflicts surface a "Refactor Anyway" dialog.
- **Eclipse JDT LS**: `prepareRename` validates renameability and returns the identifier range;
  `SearchEngine` finds references; an LTK `RenameProcessor` does precondition checks and builds changes
  via `ASTRewrite` → `TextEdit` → LSP `WorkspaceEdit`. Package rename is explicitly partial
  ("not supported"); some non-code updates fall back to fragile text replacement.

### Key insight for Lathe: no `ASTRewrite` needed

Lathe already computes, for a symbol, every occurrence's **semantic name-token range** (from
javac-attributed analysis, not text scanning) via Find References — overload-safe `ReferenceTarget`
identity, record-component ↔ accessor normalization, override-family expansion, open **and** closed-file
search (disk candidates read fresh), read/write roles, and range de-duplication.
Renaming a symbol to a new identifier is therefore **replacing each of those resolved ranges with the
new name** — a semantically-resolved substitution, not a structural rewrite.
This removes the single largest piece of JDT-style complexity: Lathe needs no `ASTRewrite`.
It is not ad-hoc text manipulation — the ranges come from the same attributed analysis Find References
already trusts, satisfying the "no ad hoc Java parsing" rule.

## Design

### `textDocument/prepareRename`

Validate the position and return the identifier range, or fail with a clear message (mirroring JDT's
"cannot rename here").

1. Resolve the `TreePath` at the cursor with `CodeActionSupport.pathAt`, then the `Element` with
   `SourceLocator.elementAt`.
2. Guard — reject when any holds:
   - the file is not reactor-owned (`WorkspaceSession.routeCompiler` is not `CompilerRoute.Module`);
     JDK/dependency sources are read-only.
   - the element does not resolve, or is a non-renameable / synthetic-only kind.
   - the element is an unsupported kind for M2 (see Non-goals — e.g. a package).
3. Return the name-token `Range` at the cursor (the range the client will pre-fill and highlight).

Advertised via `RenameOptions(prepareProvider = true)`.

### `textDocument/rename`

Reuse the references pipeline end to end.

1. Build the `ReferenceTarget` for the resolved element exactly as `references()` does — including
   override-family expansion for methods and record-component normalization (accessor + backing field +
   canonical-constructor parameter).
2. Run the existing scoped search (`DECLARING_FILE` / `DECLARING_MODULE` / `REACTOR_MODULES` from
   `ReferenceTarget.scopeFor`), across open and closed files, honoring the existing work-done progress,
   cancellation, and the process-wide compilation admission cap.
3. Map every occurrence `Range` — the declaration included — to a `TextEdit(range, newName)`.
4. Assemble a `WorkspaceEdit` (see below).
5. Run the conflict checks (see below) first; on a hard conflict, fail the request rather than return a
   corrupting edit.

### `WorkspaceEdit` shape and the type-file rename

For renames that touch only occurrences, return `WorkspaceEdit.changes` — the existing map-of-
`uri → List<TextEdit>` pattern already used by the quick-fix providers (`DeclareVariableProvider`,
`MissingMethodImplProvider`).

Renaming a **public top-level type** additionally requires renaming its file (`Foo.java` → `Bar.java`)
so the file name keeps matching the type.
This needs `WorkspaceEdit.documentChanges` with a `RenameFile` resource operation, which the server
does not use today.
Decision: **include the `RenameFile` op, guarded on the client capability**
(`workspace.workspaceEdit.resourceOperations` includes `rename`; Neovim 0.11+ supports it).
When the type is a public top-level type whose file must move, emit `documentChanges` with the
`TextDocumentEdit`s plus the `RenameFile`; when the client does not advertise resource operations, fall
back to the text edits and report that the file was not renamed.
Non-public or nested types need no file rename and use the plain `changes` map.

### Conflict detection (bounded)

LSP has no "Refactor Anyway" affordance, so the server must be conservative: on a detected hard
conflict, **fail with a message** instead of producing an edit.
M2 does a minimal, cheap set and documents the rest as non-goals:

- the new name is a legal Java identifier (and not a reserved word) — else reject in `prepareRename`
  where possible, otherwise in `rename`.
- a same-scope duplicate for the common kinds: a local/parameter already declared in the same method,
  or a member with the new name already declared on the target's declaring type.

Broader semantic conflicts (shadowing/hiding across scopes, overload collisions that change dispatch,
visibility changes) are **not** detected in M2 and are listed as non-goals.

### Correctness gate — freshness (WS-1)

The one corruption risk is a **missed occurrence**.
An audit of the actual reference pipeline (below) narrows this risk considerably, and relocates it
from the hierarchy index the original design worried about to the candidate token index.

How candidate discovery actually works:

- **Discovery is a maintained grep, not a hierarchy query.** `ReferenceCandidateIndex`
  (`lathe-server/.../server/ReferenceCandidateIndex.java`) is a `token → Set<URI>` map — every Java
  identifier token to the set of files (open *and* closed, reactor-wide) that spell it.
  `candidateUris(name)` returns every file mentioning the name; each is then attributed fresh by javac
  and matched by semantic identity in `ReferenceLocator`. Occurrence *content* is therefore safe —
  candidates are read fresh at search time.
- **The persisted hierarchy index is consulted in exactly one branch.**
  `ReferenceCandidatePlanner.planCandidates` reads `WorkspaceTypeIndex.transitiveSubtypes` **only** for
  **fields** (via `narrowToFamily` → `overrideFamily`), to shrink the name-hit set to files that also
  spell a family type. **Methods** use the broad name set (`return simpleCandidates`) — narrowing was
  deliberately abandoned for them because it missed call sites (FR-011). **Types** discover via
  import-spelling and same-package tokens (`planTypeCandidates`), not subtypes. Locals, parameters,
  constructors, and enum constants use the token index only.
- **The upward override family is computed live, not from the index.**
  `ReferenceTarget.overriddenDeclarers` walks `types.directSupertypes(...)` over the freshly attributed
  model, so a method's inherited-declarer set is always current.

Consequences:

- Methods and types ride grep + fresh compile + live match, with **no dependence on the persisted
  hierarchy index**. The original premise — "method and type renames rely on the type index, so
  freshness-gate them" — does not match the code.
- The **only** correctness dependence on the stale-able `WorkspaceTypeIndex` is **field**
  subtype-narrowing.
- The dominant staleness axis is the **candidate token index for closed files** edited out-of-band
  (branch switch, external tool) — a file that gained an occurrence out-of-editor may not be offered as
  a candidate (WS-1). This affects every reference kind, and it affects Find References *identically*.

So rename introduces **no new staleness** over Find References; both ride the same token index. The only
thing that distinguishes rename is the *write*-vs-*read* blast radius — a missed read is a short list you
re-run, a missed write is a silent half-rename.

Gate for M2:

- Renames whose scope is `DECLARING_FILE` — locals, parameters, exception/lambda parameters, type
  parameters, and private members — are single-file, index-independent, and **unconditionally safe**.
- **Field** rename uses the **broad name set (drop subtype-narrowing) for the rename path**, matching
  what methods already do (FR-011). This removes `WorkspaceTypeIndex` from the rename correctness path
  entirely, so *every* rename kind becomes index-independent and the hierarchy-staleness question
  disappears.
- The residual WS-1 exposure (closed files edited outside the editor) is then **identical to Find
  References**. M2 accepts it with a documented limitation — rename is exactly as complete as
  find-references — rather than building the WS-2 re-sync machinery the earlier design assumed. A
  coarse freshness prompt can be layered on later if the write blast-radius warrants more than parity.

## Scope for M2 (the ~80%)

In — covered and tested:

- **Locals, parameters, exception/lambda parameters, type parameters** — `DECLARING_FILE`, always safe,
  and the bulk of everyday renames.
- **Fields** — private (file), package-private (module), public/protected (reactor). The rename path
  uses the broad name set (no subtype-narrowing) so it stays index-independent (see Correctness gate).
- **Methods** — non-overriding fully; overriding via the existing override-family expansion. Candidate
  discovery is grep-based and the upward override family is computed live, so no freshness gate applies
  (see Correctness gate).
- **Types** (class / interface / enum / record) — reference updates plus the matching file rename for a
  public top-level type (capability-guarded). Discovery is import/package-token based, index-independent.
- **Record components** — accessor, backing field, and canonical-constructor parameter updated together
  (Lathe already unifies these under the accessor identity).

## Non-goals (explicit, documented)

- Renaming external / JDK / dependency symbols — read-only.
- **Package rename** — JDT itself treats this as partial; deferred.
- **Non-code usages** — comments, string literals, Javadoc `{@link}` / `@see` reference text; opt-in later.
- Related-symbol propagation — renaming a field does not rename its getter/setter; renaming an interface
  does not rename an implementation's differently-named member.
- Cross-language / configuration references — Spring XML, `.properties`, service files.
- Broad semantic conflict detection (shadowing/hiding, dispatch-changing overload collisions,
  visibility changes) — see Conflict detection.
- Reflection / string-referenced names.

## Reuse map

Existing, reused as-is:

- `ReferenceTarget` — identity, `scopeFor`, override-family, record-component normalization.
- `ReferenceCandidatePlanner` / `ReferenceCandidateIndex` — candidate discovery (open + disk). Reused
  as-is except that field rename requests the broad name set rather than the subtype-narrowed one, to
  keep the rename path index-independent (see Correctness gate); this is a small planner option, not a
  new discovery mechanism.
- `WorkspaceSession` reference search — scope planning, open + closed-file fan-out, progress,
  cancellation, `CompilationAdmission` cap.
- `ReferenceLocator` — per-occurrence name-token ranges, roles, range de-dup.
- `CodeActionSupport.pathAt`, `SourceLocator.elementAt` — position → element for `prepareRename`.
- `WorkspaceSession.routeCompiler` / `CompilerRoute` — reactor-vs-external guard.

New:

- `RenameProvider` — turns the resolved occurrence set into `TextEdit`s, applies conflict checks, and
  builds the `WorkspaceEdit` (with the optional `RenameFile` op). A single concrete class; no interface
  (one implementation).
- `WorkspaceSession.prepareRenameFuture(uri, position)` and
  `renameFuture(uri, position, newName)` — worker-confined entry points.
- `LatheTextDocumentService.prepareRename(...)` / `rename(...)` — handlers mirroring `definition` /
  `references` (`worker.submit(...) .thenCompose(...)`).
- `LatheLanguageServer.createCapabilities` — `setRenameProvider(new RenameOptions(true))`.

## Testing (the correctness gate)

- Unit, per kind: local, parameter, field × (private / package / public), method (non-override and
  override family), type (with file rename), record component.
- Cross-file: a rename whose edits land in multiple reactor modules.
- Negatives: external/JDK symbol refused; illegal / reserved new name refused; same-scope duplicate
  refused. (No stale-workspace refusal — the rename path is index-independent; see Correctness gate.)
- Resource-op fallback: with `resourceOperations` unadvertised, a public-type rename returns text edits
  and reports the file was not renamed.
- Invoker (`LspSmokeTest`): drive `prepareRename` + `rename` against the multi-module fixture and assert
  the `WorkspaceEdit` touches the expected files.
- Differential-vs-jdtls comparison is post-M3 quality tooling
  ([lathe-jdtls-differential-testing.md](lathe-jdtls-differential-testing.md)), not an M2 gate.

## Resolved decisions

1. **Type file rename** — include the `RenameFile` resource operation, guarded on the client
   `resourceOperations` capability, with a text-edits-only fallback.
2. **Freshness** — revised after a code audit (see Correctness gate). The persisted hierarchy index is
   in the correctness path only for field subtype-narrowing; methods and types ride a grep + live match.
   Rename therefore drops subtype-narrowing on the field path to become fully index-independent, and
   accepts the residual closed-file WS-1 exposure as identical to Find References (documented
   limitation) rather than refusing renames or building WS-2 re-sync machinery for M2. This supersedes
   the original "refuse method/type renames when stale" gate.
3. **Conflict depth** — minimal for M2 (legal identifier + same-scope duplicate); broader conflicts are
   documented non-goals.
