# Lathe — Fuzzy Method-Name Symbol Search in the Reactor

## Problem / motivation

`workspace/symbol` currently resolves **type names only** — `WorkspaceSymbolResolver` queries
`WorkspaceTypeIndex`, whose entries are types (reactor, dependency, JDK), with the CamelHumps matcher
from [EG-005](../gaps/gaps-archive.md).
There is no way to jump to a method by name across the workspace; method symbols are surfaced only in
the per-file `documentSymbol` outline, i.e. for the file already open.

The user wants to fuzzy-search **method names across the reactor** — type `processOrder` (or an
abbreviation) and jump to the declaration wherever it lives, the way IDEs offer "search everywhere /
go to symbol" for methods, not just types.

## Sketch

Preferred approach — **reuse the Find References machinery rather than build a new index.**
`ReferenceCandidateIndex` is already a live, reactor-wide `Map<token, Set<uri>>` whose key set is an
in-memory identifier dictionary for the whole reactor (method names included), kept current on
open-doc edits and rebuilt on reload. Method search then becomes:

1. **Discovery (cheap):** CamelHumps-match the query against the index key set — pure in-memory
   string matching, no disk or javac — and resolve matched tokens to candidate files via
   `candidateUris(token)`.
2. **Confirmation (parse-only):** parse — not attribute — each candidate file and collect
   `MethodTree` declarations whose name matches, emitting `SymbolInformation` of kind `Method` with
   the declaring type as `containerName` and the declaration position as the location. This is the
   same `CompilationUnitTree.getTypeDecls()` tree walk the CA-4 fix already uses, extended to method
   members — no symbol resolution or classpath needed, so it is much cheaper than reference search's
   transient attribution.
3. **Bounding (reused):** run under `CompilationAdmission` with the existing work-done progress and
   cancellation wiring.

This needs no persistent method index and no sync-time infrastructure — only a fuzzy key-scan query
mode on `ReferenceCandidateIndex` (it exposes exact `candidateUris(token)` today) and a parse-only
method collector.

Alternative — a persistent reactor method-name index populated during `lathe:sync` alongside the
type-index shards. More metadata to build and keep fresh; only worth it if the live parse-on-query
approach proves too slow in practice.

## Open questions

- **Per-keystroke cost (the main risk).** Unlike one-shot Find References, `workspace/symbol` fires
  as the user types, so the candidate-file parse pass runs repeatedly. Needs a minimum query length,
  debounce, superseded-request cancellation (already available), and result caps. Discovery is cheap
  enough to gate on before parsing.
- **Dictionary breadth.** The token key set holds every identifier (fields, locals, types, params),
  not just methods, so a short query matches a large slice and yields many candidate files whose
  parse produces no matching method declaration. Caps and ranking (the ranking EG-005 deferred)
  matter; consider a minimum query length.
- **Presentation and disambiguation.** Many methods share a name across types; results need the
  declaring type and ideally a signature (available syntactically from `MethodTree`), plus sensible
  ordering. Consider suppressing `Object` methods the way member completion suppresses `wait`/
  `notify` (EG-008).
- **Scope.** Reactor-only (matching CamelCase type search) is the natural first cut; dependency/JDK
  would need real method metadata and overlaps the roadmap's tentative "External/JDK method
  implementation indexing if demand justifies method metadata".
- **Freshness.** Discovery inherits the token index's freshness, which shares the reactor staleness
  surface — stale on a source change or branch switch until reload/sync (see WS-1).

## Milestone candidate

Not committed and not soon per the user, but **moderate** rather than heavy: reusing the existing
token index and attribution scaffolding avoids a new indexing subsystem, so this need not wait on
post-M3 method-metadata work. Untriaged pending prioritization and a measurement of the
parse-on-query cost.
