# Lathe — CamelCase Workspace Symbol Matching

## Objective

Resolve [EG-005](../gaps/gaps-archive.md): `workspace/symbol` only matched an exact prefix of the
simple type name, so abbreviated or infix queries (`"ASF"`, `"TaskMgr"`, `"ServerFactory"` for
`AbstractServerFactory`) returned nothing or missed real matches.

## History

This gap was originally approached as "blank-query browsing" — return every workspace-owned type
on an empty query, so a client could fetch once and fuzzy-filter locally. That design is
superseded (see [Workspace Symbol Browsing](lathe-workspace-symbol-browse.md), reverted) once it
became clear the real problem was the server's matching, not the client's filtering: fixing
matching server-side benefits every client, not just ones willing to fetch-and-locally-filter, and
avoids the multi-second uncapped scan the browsing approach required.

## Algorithm

`CamelCaseMatcher.matches(query, candidateSimpleName)` follows IntelliJ's CamelHumps, as its Go to Class matches a type name ([EG-129](../gaps/gaps-archive.md) replaced the first version, which let a query's letters fall anywhere inside a word):

1. The name's words start at its first letter, after a `_` or `$`, at a digit, at a capital after a lowercase letter or a digit, and at the last capital of an acronym before a lowercase letter: `AbstractServerFactory` → `Abstract`, `Server`, `Factory`; `URLConnection` → `URL`, `Connection`; `Aes128` → `Aes`, `1`, `2`, `8`.
2. The query starts at a word of the name, and each of its letters either continues the run it is in or starts a later word, case ignored; words may be skipped.
   `TaskMan`, `TaMa`, `tm` and `taskman` reach `TaskManager`, and `ServerFactory` reaches `AbstractServerFactory`.
3. A letter never lands inside a word: `Pet` does not reach `I18nPropertiesSyncTest`, and an abbreviation that drops letters (`Mgr`, `TaskMgr`) reaches nothing, in IntelliJ as here.
4. A query with a capital past its first letter marks its words itself, so its lowercase letters only continue a word: `oCT` reaches `OwnerControllerTest`, `OcT` does not.
5. A `_` or `$` in the query skips to the next one in the name.

A run is tried longest first, then shorter, as the rest of the query may need a letter the longer run took; a query and name position that failed once is not tried again.
The rule was checked against IntelliJ 2025.3's own matcher on 2,128 queries over 4,000 type names from Helidon, Dropwizard, Petclinic and Lathe: they agree on every pair but IntelliJ's hard-coded `ln` word (`CurrencyNames_kln`).
IntelliJ also matches a query from inside a word (`ana` in `TaskManager`, with a run of at least three letters before a jump); that is not done here, as it needs results ranked by how well they match.

## Scope decisions

- **Reactor-owned types only**, not JDK/dependency types. The index also carries the full
  JDK+dependency universe; scanning all of it on every keystroke would reintroduce real
  per-keystroke cost, and burying a developer's own code under standard-library matches isn't
  useful. If you want to find a dependency's internal class, you still type its real prefix.
- **Supplementary to `search()`, not a replacement.** `WorkspaceTypeIndex.search()` stays
  untouched — it's shared with `ImportQuickFixProvider`, `MemberAccessCompleter`, and
  `CompletionEngine`, all of which need exact-prefix semantics and would misbehave with fuzzy
  matching. A new method, `searchCamelCase(query, limit)`, carries the new behavior; only
  `WorkspaceSymbolResolver` calls it.
- **Live per-query scan, not a pre-built index.** The gap's original proposal was a secondary
  CamelCase-initialism index built alongside the reactor scan. Implemented differently: a live
  scan over `bySimpleNameLower`'s reactor-owned entries, scored per query. This avoids a second
  index structure to keep in sync with reactor reload/refresh, and the scan itself is cheap
  (reactor-scoped, not the full index) — the real cost driver, the per-result `declarationRange`
  parse, is unaffected since it only runs on whatever survives the existing result cap.
- **No result scoring.** Multiple hump matches for one query are returned in index-iteration order
  (alphabetical by binary name), not ranked by match quality. IntelliJ's own matcher
  (`MinusculeMatcher`) does support ranking (`matchingDegree`), but adding a scorer here was
  deferred as unproven-needed complexity — correctness (does it match at all) was the actual gap;
  ranking is a separate, optional refinement if it turns out to matter in practice.
- **CamelCase-hump matching only, not general infix/substring matching.** The original gap title
  mentioned both. A raw substring that doesn't align to a hump boundary (e.g. `"erverFac"`) is not
  covered — that's a distinct feature (plain `contains()`, no hump structure), not implemented
  here. File a separate gap if it's wanted.

## Flow

`WorkspaceSymbolResolver.resolve(query, typeIndex, sourceDirs)`:

1. Blank/whitespace-only query → `List.of()` immediately (unchanged from before browsing was ever
   introduced).
2. Non-blank query → `mergedEntries()`: `typeIndex.search(query, SEARCH_LIMIT)` (exact prefix,
   everything) and `typeIndex.searchCamelCase(query, SEARCH_LIMIT)` (CamelCase, reactor-only),
   combined and deduplicated by binary name (prefix match wins on overlap), capped at
   `SEARCH_LIMIT`.
3. Each surviving entry goes through the existing `toSymbolInformation`/`declarationRange` path,
   unchanged.

No progress reporting or cancellation wiring — both matching passes are cheap enough (reactor-only
scan, existing result cap) that neither is needed; `LatheWorkspaceService.symbol()` and
`LatheTextDocumentService.workspaceSymbolFuture` are plain passthroughs, same shape as before any
of this work started.

## Affected files

- **Added:** `lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/CamelCaseMatcher.java`
- **Added:** `lathe-server/src/test/java/io/github/aglibs/lathe/server/analysis/CamelCaseMatcherTest.java`
- **Modified:** `WorkspaceTypeIndex.java` — `browseWorkspace()` removed, `searchCamelCase()` added.
- **Modified:** `WorkspaceSymbolResolver.java` — merge logic; blank-query short-circuit restored.
- **Modified:** `WorkspaceSession.java`, `LatheTextDocumentService.java`, `LatheWorkspaceService.java`
  — `CancelChecker`/`ProgressReporter` wiring for workspace-symbol removed (`ProgressReporter`
  itself is untouched and still backs `references()`/`incomingCalls()`).
- **Modified:** `WorkspaceTypeIndexTest.java`, `WorkspaceSymbolTest.java` — see regression targets
  in the archived [EG-005](../gaps/gaps-archive.md) entry.

## Verified

Full `lathe-server` suite (740/740) and live against a real workspace via `dev/explore.py`
(Dropwizard): blank query returns nothing, `"HSC"` finds `HealthStatusChecker` via CamelCase, exact
prefix queries unaffected.
