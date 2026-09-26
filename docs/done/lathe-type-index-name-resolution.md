# Lathe — Type-Index Name Resolution and Ranking

This document covers two related changes to how `WorkspaceTypeIndex` answers simple-name lookups:

- **A — exact-name import resolution** (correctness; the reported bug).
- **B — relevance ordering for prefix search** (quality; a follow-up).

They are separated deliberately: A is a small, targeted correctness fix with a guarantee independent
of workspace size, and B is a broader ranking change that also touches completion ordering and its
tests.

## Background

`WorkspaceTypeIndex.search(prefix, limit)` powers several features:
import resolution, type-name completion, and workspace symbol search.
It walks every entry whose lowercased simple name starts with `prefix`, sorts the matches, and returns
the first `limit`.
The current ordering (`prefixMatches`) is **reactor-owned types first, then alphabetically by
`binaryName`** — there is no relevance signal:
no preference for an exact simple-name match, no preference for JDK / `java.*` types, and the
document-frequency signal that the index already computes (`usageCount`) is not consulted here.
Usage frequency is applied only later, inside `TypeReferenceCompleter`, as a re-rank of whatever
`search` already returned.

The callers and their limits:

| Caller | Limit | Feature |
|---|---|---|
| `ImportCandidates.resolve` | 100 | import quick fix + `lathe.missingImports` |
| `CompletionEngine` | 200 | type-name completion popup |
| `MemberAccessCompleter` | 200 | member completion |
| `WorkspaceSymbolResolver` (`SEARCH_LIMIT`) | 100 | `workspace/symbol` |
| `TypeReferenceCompleter` (validation) | 1000 | completion validation |

## The bug (drives A)

Entering a field whose type is a common JDK generic — for example

```java
package com.example.app;

public record Order(List<String> lines) {}   // List unresolved, no import
```

detects `List` correctly as an unresolved **type** (`resolveKind` returns `TYPE_REF`; it appears in
`lathe.missingImports`), but the import quick fix offers **nothing**, so no code action shows.

The cause is a mismatch between what `ImportCandidates.resolve` needs and what `search` provides.
`resolve` wants the *exact* set of types simple-named `List` (a handful), but it asks `search` for the
top-100 of the `List*` **prefix** and filters to exact matches *afterward*.
Because the top-100 is ordered purely by `binaryName`, every `List*`-prefixed dependency type whose
binary name sorts before `java.util.List` consumes a slot first.
In a dependency-heavy module this pushes the exact matches past the limit:

- `java.util.List` — sorts after roughly a hundred `List…`-prefixed dependency types
  (e.g. `ch.qos.logback…ListAppender`, docker-java's `List*Cmd` family, protobuf, guava, wiremock,
  xerces/wstx), plus even `java.awt.List` which precedes it only because `awt < util`.
- Once ≥100 such entries precede it, `search(simpleName, 100)` never returns `java.util.List`, so
  `resolve` produces zero candidates and no import action.

The same mechanism affects `java.util.Map`, `java.util.Set`, and any common simple name that
dependencies also crowd.
`String` escapes only because `java.lang` is implicitly imported and never needs the index.

Completion (limit 200, plus the usage re-rank) largely survives — `java.util.List` lands inside the
window and floats up because it is heavily used across the reactor — which is why the failure surfaces
specifically on the import code action, not in the completion popup.

## A — exact-name import resolution (correctness)

Import resolution should look up the *exact* simple-name group, not a truncated prefix scan.
The index already groups entries by exact lowercased simple name (`bySimpleNameLower`), so this is a
direct map lookup — no scan, no limit, and immune to how many `List*` siblings exist.

- Add `WorkspaceTypeIndex.searchExact(String simpleName)` returning the full group
  (`bySimpleNameLower.getOrDefault(simpleName.toLowerCase(), List.of())`), unbounded.
  The group is naturally small (the count of types sharing that exact simple name).
- Change `ImportCandidates.resolve` to call `searchExact(simpleName)` instead of
  `search(simpleName, 100)`.
  The existing `simpleName().equals(...)` case-exact filter, `!packageName().isEmpty()`,
  already-imported, and `isImportable` filters are unchanged.

Result on the example: candidates become `{java.awt.List, java.util.List}` — javac's internal
`com.sun.tools.javac.util.List` is dropped by `isImportable` as inaccessible — so the quick fix offers
both, ambiguous, and the client prompts the user to choose.
This is the same behavior the small multi-module fixture already exhibits.

This fixes both the single-name import quick fix and the whole-file `lathe.missingImports` command,
and the guarantee no longer depends on the dependency count.

### A — test plan

- `WorkspaceTypeIndexTest`: `searchExact` returns all exact-name entries and stays correct when many
  `List*` **prefix** siblings exist (positive case plus the >100-siblings regression case that starves
  `search(…, 100)`).
- `CodeActionTest`: a `List<T>` field with 100+ `List*`-prefixed dependency entries in the index still
  yields the `java.util.List` import action — the negative case that fails today.

## B — usage-aware truncation for prefix search (quality)

`prefixMatches` orders `(reactor-first, binaryName)` and *then* applies the limit, so the cap keeps the
alphabetically-first matches rather than the most relevant ones.
Completion already re-ranks what survives the cap by usage frequency
(`TypeReferenceCompleter`, shipped as CQ-0059: `exact-prefix → usageCount → originRank → shorter-FQN →
lexical`), but that runs *after* truncation, and `workspace/symbol` does not re-rank at all — it takes
`searchSymbols` order verbatim and caps at 100.
So a common type whose binary name sorts late is starved before either mechanism can help; concretely,
`java.util.List` (binary-name rank ~107 among `List*`) never reached the symbol results at all.

The minimal fix makes the truncation itself usage-aware, so the limit keeps the types the project
actually references. The `prefixMatches` order becomes:

1. higher `usageCount` (document frequency across the reactor — already computed, previously unused
   here),
2. reactor-owned before external,
3. `binaryName` as the stable tiebreaker.

`usageCount` is the one signal that matters most and is shared with completion's comparator, so this
brings `workspace/symbol` close to completion without duplicating the finer comparator there.
A cold-start index carries no counts, so every entry ties on usage and the order degrades exactly to
today's `(reactor-first, binaryName)` — keeping existing symbol/completion/EG-021 expectations green.
Exact-name/originRank/FQN-length tie-breaks are deliberately left to completion's comparator rather
than relocated here.

### B — test plan

- `WorkspaceTypeIndexTest`: a referenced type outranks an alphabetically-earlier sibling and survives
  a tight cap; a cold-start index (no counts) still falls back to `binaryName` order.
- Verified end-to-end: `workspace/symbol` for `List` now returns `java.util.List` first in a
  dependency-heavy workspace.

## Non-goals

- Unused-import removal or import sorting (a fuller "organize imports") — tracked separately.
- Camel-case / fuzzy matching changes — `searchCamelCase` is untouched.
- Raising the per-caller limits as a workaround: A removes the limit dependency for the import path;
  B fixes ordering so the limit stops hiding relevant results. Neither change simply enlarges a cap.

## Status

- A — done (see gap CA-9); `searchExact` on the import path.
- B — done (see gap CA-9); usage-aware truncation in `prefixMatches`, benefiting `workspace/symbol`
  and the completion cap.
