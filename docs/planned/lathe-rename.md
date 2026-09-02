# Lathe — Rename (`textDocument/rename`)

## Status

Planned — M2. Approved design; no code yet. The freshness gate was revised after a code audit of the
reference pipeline — see Correctness gate and Resolved decision 2. A second audit re-verified the
freshness argument against `ReferenceCandidatePlanner` and resolved the constructor, conflict-depth,
and enum-constant edges — see Resolved decisions 4–6.

**Scope narrowed for the first delivery.** After a complexity review the feature is split into two
slices: **Slice 1** — local-scope renames (locals, parameters, exception/lambda parameters, type
parameters), single-file and unconditionally safe; **Slice 2** — member and in-place type renames
(fields, methods, record components, constructor redirect, and nested / non-public top-level types).
Together they cover ~85% of everyday renames. The one deferred piece is the **public top-level type
file rename**, which needs resource-operation plumbing and is carved out as a focused follow-up (see
Deferred).

This is the focused design the roadmap's M2 "Refactoring" item calls for
("prepare-rename and exact reactor rename edits, correctness-gated with explicit non-goals").
It builds on the Find References machinery documented in
[lathe-find-references.md](../done/lathe-find-references.md) and the exploratory sketch in
[lathe-basic-refactorings.md](../potential/lathe-basic-refactorings.md).

## Goal

Rename a Java symbol and every reference to it across the reactor from Neovim, as one atomic edit,
correctly enough to trust on real projects.
The design is deliberately scoped to the common ~85% of renames and is **correctness-gated**: a rename
either covers a case safely and with tests, or that case is an explicit non-goal — a rename that
silently misses occurrences corrupts code, which is worse than not offering it.

## Prior art

Both mainstream implementations share one shape — *validate + range → find all references → one atomic
`WorkspaceEdit`, conflict-checked first* — and Lathe follows it, except it deliberately skips the
up-front conflict check in favour of a permissive substitution (see Conflict detection).

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
2. **Constructor redirect** — when the resolved element is a `CONSTRUCTOR`, a rename is really a rename
   of the enclosing type (a constructor's name is fixed to the class name). Resolve to the enclosing
   `TypeElement` and proceed as a type rename, matching IntelliJ/JDT. The pre-filled range in this case
   is still the identifier under the cursor (the type simple name spelled by the `new Foo(...)` /
   declaration), which equals the type's simple name.
3. Guard — reject when any holds:
   - the file is not reactor-owned (`WorkspaceSession.routeCompiler` is not `CompilerRoute.Module`);
     JDK/dependency sources are read-only.
   - the element does not resolve, or is a non-renameable / synthetic-only kind.
   - the element is an unsupported kind for M2 (see Non-goals — e.g. a package, an enum constant).
   - the element is a **public top-level type** whose file would have to move — deferred (see
     Deferred). Nested and non-public top-level types are fine.
4. Return the name-token `Range` at the cursor (the range the client will pre-fill and highlight).

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
5. Validate the new name (legal identifier / not reserved) first; otherwise apply the edits without a
   collision scan (see Conflict detection).

### `WorkspaceEdit` shape

Every rename in this scope touches only occurrences inside existing files, so the result is always a
`WorkspaceEdit.changes` map — the `uri → List<TextEdit>` pattern already used by the quick-fix
providers (`DeclareVariableProvider`, `MissingMethodImplProvider`).
No resource operations, no file moves, no document versioning.

Renaming a **public top-level type** would additionally require moving its file (`Foo.java` →
`Bar.java`) so the file name keeps matching the type, which needs `documentChanges` with a `RenameFile`
resource operation the server does not use today.
That whole path — capability capture, `documentChanges` ordering, versioning, and the resource-op
fallback — is **deferred** (see Deferred).
`prepareRename` refuses a public top-level type with a clear message rather than emit a knowingly-broken
edit: a public type whose file name no longer matches will not compile.
Nested types and non-public top-level types need no file move and rename normally through the `changes`
map.

### Conflict detection (deliberately minimal)

The chosen behaviour is **close to a semantically-resolved find-and-replace**: the server substitutes
the new name at every resolved occurrence and does **not** try to pre-empt name collisions. The
rationale is that every collision such a scan could catch surfaces to the user as an immediate
compile-error diagnostic — never silent corruption, since each edit is a semantically-resolved
name-token range — which the user can then undo or fix. An up-front conflict scan therefore buys little
and costs real complexity (element enumeration, `TypeElement` re-resolution, overload-vs-duplicate
disambiguation).

The one check kept is input validity:

- the new name is a legal Java identifier and not a reserved word (`SourceVersion.isName` /
  `isKeyword`) — reject in `prepareRename` where possible, otherwise in `rename`.

Explicitly **not** checked — each becomes a visible compile error or a user-noticeable change if it
occurs:

- **member duplicate** — a member with the new name already on the declaring type. For methods a
  name-only scan cannot distinguish a real signature clash from a legal new overload, so the check is
  dropped rather than guessed.
- local/parameter same-scope duplicate (would need a block-scope AST walk).
- shadowing/hiding across scopes, dispatch-changing overload collisions, visibility changes.
- **override-relationship break** — renaming a method that overrides a read-only (JDK/dependency)
  method renames the reactor members but not the read-only declaration, so the override silently stops
  applying. Accepted: the reference set is already bounded to the declaring type and its subtypes (not
  every `equals` / `toString` call reactor-wide), and the read-only declaration is never a reactor file,
  so the only effect is a behaviour change that is the user's to notice.

The only genuinely silent failure mode is a *missed occurrence*, which the correctness gate below bounds
to find-references parity (and which the field path widens to the broad name set to minimise).

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
  import-spelling and same-package tokens (`planTypeCandidates`), not subtypes. Locals and parameters use
  the token index directly; **constructors and enum constants** narrow to their declaring type via
  `narrowToFamily(Set.of(qualifiedName), …)`, which unions the declaring-type simple-name files with
  static-import sites and **never calls `transitiveSubtypes`** — so they, too, are index-independent.
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
  (This is a *safety* tier, not a slice boundary: Slice 1 ships the locals / parameters / type
  parameters; private fields and methods, though equally single-file-safe, ship with the other members
  in Slice 2.)
- **Field** rename uses the **broad name set (drop subtype-narrowing) for the rename path**, matching
  what methods already do (FR-011). This removes `WorkspaceTypeIndex` from the rename correctness path
  entirely, so *every* rename kind becomes index-independent and the hierarchy-staleness question
  disappears.
- The residual WS-1 exposure (closed files edited outside the editor) is then **identical to Find
  References**. M2 accepts it with a documented limitation — rename is exactly as complete as
  find-references — rather than building the WS-2 re-sync machinery the earlier design assumed. A
  coarse freshness prompt can be layered on later if the write blast-radius warrants more than parity.

## Scope for M2 (the ~85%)

Delivered in two slices — **Slice 1** is local-scope only (unconditionally safe, no shared-API change,
nothing to approve); **Slice 2** adds the members and in-place types (cross-module search and the field
broad-name-set option — the one remaining shared-API change).

In — covered and tested:

- **Locals, parameters, exception/lambda parameters, type parameters** — `DECLARING_FILE`, always safe,
  and the bulk of everyday renames.
- **Fields** — private (file), package-private (module), public/protected (reactor). The rename path
  uses the broad name set (no subtype-narrowing) so it stays index-independent (see Correctness gate).
- **Methods** — non-overriding fully; overriding via the existing override-family expansion. Candidate
  discovery is grep-based and the upward override family is computed live, so no freshness gate applies
  (see Correctness gate).
- **Types** (class / interface / enum / record) — reference updates only, via the `changes` map.
  Discovery is import/package-token based, index-independent. Nested and non-public top-level types
  rename in place; a **public top-level type**, whose file would have to move, is refused for now (see
  Deferred). A rename invoked on a **constructor** redirects here (see `prepareRename`), so a
  constructor of a public top-level type is likewise refused until the file-rename follow-up lands.
- **Record components** — accessor, backing field, and canonical-constructor parameter updated together
  (Lathe already unifies these under the accessor identity). Because the component and its accessor
  share one identity, the accessor's call sites (`r.foo()`) are renamed with it — the one intended
  case where a "field-like" rename also renames its "getter", and correct Java semantics.

Enum constants are *not* in M2 scope (the pipeline supports them and they are index-independent, but
they are deferred to keep the tested surface focused — see Non-goals).

## Non-goals (explicit, documented)

- Renaming external / JDK / dependency symbols — read-only.
- **Public top-level type file rename** — moving `Foo.java` → `Bar.java` needs `documentChanges` +
  `RenameFile` resource operations, client capability capture, and document versioning; deferred to a
  focused follow-up (see Deferred). Type *reference* renames that need no file move are in scope.
- **Enum-constant rename** — supported by the pipeline and index-independent, but deferred from M2 to
  keep the tested-kind surface focused; a small, safe follow-up.
- **Package rename** — JDT itself treats this as partial; deferred.
- **Local/parameter same-scope duplicate detection** — needs a block-scope AST walk rather than an
  element scan; not done (see Conflict detection). Only the illegal-identifier check applies.
- **Non-code usages** — comments, string literals, Javadoc `{@link}` / `@see` reference text; opt-in later.
- Related-symbol propagation — renaming a field does not rename its getter/setter; renaming an interface
  does not rename an implementation's differently-named member.
- Cross-language / configuration references — Spring XML, `.properties`, service files.
- Broad semantic conflict detection (shadowing/hiding, dispatch-changing overload collisions,
  visibility changes) — see Conflict detection.
- Reflection / string-referenced names.

## Deferred to a follow-up — public top-level type file rename

Renaming a public top-level type requires moving its `.java` file so the file name keeps matching the
type. That is the one piece of this feature that needs machinery the initial delivery does not build,
and it is carved out as a focused follow-up:

- `WorkspaceEdit.documentChanges` with a `RenameFile` resource operation (the `changes` map cannot move
  files).
- **Capability capture** — `initialize` must persist the client's
  `workspace.workspaceEdit.resourceOperations` flag (today the server keeps only
  `window.workDoneProgress`); `RenameProvider` reads it to decide whether to emit the `RenameFile` op,
  with a text-edits-only fallback when it is absent (`workspace.workspaceEdit.resourceOperations`
  includes `rename`; Neovim 0.11+ supports it).
- **Ordering and versioning** — the type file's `TextDocumentEdit`s target the old URI and **must
  precede** the `RenameFile` op; open documents use `OptionalVersionedTextDocumentIdentifier` (version
  from the open-document store), closed files a null version.

Until this lands, `prepareRename` refuses a public top-level type (and a constructor that redirects to
one) with a clear "not supported yet" message. Every other rename — including nested and non-public
top-level types — is unaffected.

## Reuse map

Existing, reused as-is:

- `ReferenceTarget` — identity, `scopeFor`, override-family, record-component normalization.
- `ReferenceCandidatePlanner` / `ReferenceCandidateIndex` — candidate discovery (open + disk). Reused
  as-is except that field rename requests the broad name set rather than the subtype-narrowed one, to
  keep the rename path index-independent (see Correctness gate); this is a small planner option, not a
  new discovery mechanism.
- `WorkspaceSession.referencesFuture(uri, pos, includeDeclaration=true, …)` — the entire scoped search
  (scope planning, open + closed-file fan-out, progress, cancellation, `CompilationAdmission` cap). It
  already takes an `includeDeclaration` flag, so rename passes `true` and consumes the returned
  `List<Location>` directly — every occurrence *and* the declaration, as name-token ranges.
- `ReferenceLocator` — per-occurrence name-token ranges, roles, range de-dup.
- `CodeActionSupport.pathAt`, `SourceLocator.elementAt` — position → element for `prepareRename`.
- `WorkspaceSession.routeCompiler` / `CompilerRoute` — reactor-vs-external guard.

New:

- `RenameProvider` — turns the resolved occurrence `Location`s into per-URI `TextEdit`s, validates the
  new name (legal identifier / reserved word — the only check; see Conflict detection), and builds the
  `WorkspaceEdit.changes` map. A single concrete class; no interface (one implementation).
- `WorkspaceSession.prepareRenameFuture(uri, position)` and
  `renameFuture(uri, position, newName)` — worker-confined entry points.
- `LatheTextDocumentService.prepareRename(...)` / `rename(...)` — handlers mirroring `definition` /
  `references` (`worker.submit(...) .thenCompose(...)`), logged under a `[rename]` tag.
- `LatheLanguageServer.createCapabilities` — `setRenameProvider(new RenameOptions(true))`.

The client capability capture and `RenameFile` op that a public-type file rename would need are part of
the deferred follow-up (see Deferred), not this delivery.

## Testing (the correctness gate)

- Unit, per kind: local, parameter, field × (private / package / public), method (non-override and
  override family), type (nested / non-public top-level, `changes` map only), record component (accessor
  + backing field + canonical parameter, plus accessor call sites).
- Constructor redirect: `prepareRename` on a constructor resolves to the enclosing type; `rename`
  produces the type rename. A constructor of a public top-level type redirects to a refused type rename
  (file move deferred).
- Cross-file: a rename whose edits land in multiple reactor modules.
- Negatives: external/JDK symbol refused; illegal / reserved new name refused; enum constant refused
  (deferred kind); public top-level type refused (deferred file-move kind). (No member-duplicate or other
  collision refusal — permissive by design, see Conflict detection. No stale-workspace refusal — the
  rename path is index-independent; see Correctness gate.)
- Permissive collision: renaming to a name already used on the declaring type produces the edits (and a
  resulting compile error), not a refusal.
- Invoker (`LspSmokeTest`): drive `prepareRename` + `rename` against the multi-module fixture and assert
  the `WorkspaceEdit` touches the expected files.
- Differential-vs-jdtls comparison is post-M3 quality tooling
  ([lathe-jdtls-differential-testing.md](lathe-jdtls-differential-testing.md)), not an M2 gate.

## Open items before implementation

Not design decisions — verification and integration to close before or while coding:

- **Find References per-kind coverage audit.** Rename edits exactly what `referencesFuture` returns, so
  it is only as correct as Find References is for each in-scope kind. Confirm the pipeline has passing
  coverage for every kind before trusting a *write* — especially **type parameters** (`planCandidates`
  routes them through the broad default, not the locals branch), and exception/lambda parameters and
  record components.
- **Neovim client wiring.** Advertising `renameProvider` is not enough; the Lua client needs a keymap to
  `vim.lsp.buf.rename()` (which drives `prepareRename` automatically). Pick a keybinding and add the
  `README.md` / `docs/guide/editors/neovim.md` entry, including the per-buffer (non-atomic) undo caveat.
- **Broad-name-set parameter shape.** The field "skip subtype-narrowing" signal threaded through
  `referencesFuture` → `planCandidates` is the one shared-API change; settle its shape (boolean flag vs.
  a small options record) before Slice 2.
- **Work tracking.** Rename has no `gaps.md` ID; add entries per slice plus one for the deferred
  public-type file rename.

## Resolved decisions

1. **Type file rename — deferred.** Public top-level type rename needs a `RenameFile` resource
   operation (plus capability capture and versioning) and is split into a focused follow-up (see
   Deferred). The initial delivery refuses a public top-level type in `prepareRename`; all other type
   renames proceed through the `changes` map.
2. **Freshness** — revised after a code audit (see Correctness gate). The persisted hierarchy index is
   in the correctness path only for field subtype-narrowing; methods and types ride a grep + live match.
   Rename therefore drops subtype-narrowing on the field path to become fully index-independent, and
   accepts the residual closed-file WS-1 exposure as identical to Find References (documented
   limitation) rather than refusing renames or building WS-2 re-sync machinery for M2. This supersedes
   the original "refuse method/type renames when stale" gate.
3. **Conflict depth — permissive (find-and-replace).** Only the new name's validity (legal identifier /
   reserved word) is checked; no member-duplicate or other collision scan. Every collision such a scan
   could catch surfaces as a compile-error diagnostic, not silent corruption, so the up-front scan (and
   its `TypeElement` re-resolution) is dropped in favour of simplicity. See Conflict detection.
4. **Constructor rename → redirect to type** — a constructor's name is fixed to the class name, so
   `prepareRename` on a constructor resolves to the enclosing type and the rename proceeds as a type
   rename (matching IntelliJ/JDT), rather than rejecting or attempting an independent constructor rename.
5. **Enum-constant rename deferred** — the pipeline supports enum constants and they are
   index-independent (`narrowToFamily` over the declaring type, no `transitiveSubtypes`), but they are
   held out of M2 to keep the tested-kind surface focused; a small, safe follow-up.
6. **Freshness re-verified** — a second audit confirmed against `ReferenceCandidatePlanner` that
   `transitiveSubtypes` is reached only via the field `overrideFamily` path; every other kind (methods
   broad, types by import/package token, constructors/enum constants by declaring-type family, locals by
   token index) never consults the persisted index. Dropping field subtype-narrowing therefore makes the
   whole rename path index-independent, as Decision 2 asserts.
