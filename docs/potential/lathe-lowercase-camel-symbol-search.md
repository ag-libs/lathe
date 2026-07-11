# Lathe — Lowercase CamelCase Symbol Search

## Problem / motivation

`workspace/symbol` fuzzy search (the CamelHumps matcher shipped for
[EG-005](../gaps/gaps-archive.md), see [CamelCase Workspace Symbol Matching](../done/lathe-workspace-symbol-camelcase.md))
takes hump boundaries from the *query's own capitalization*.
To match a symbol across humps the user must capitalize each boundary: `"TaskMgr"` reaches
`TaskManager`, but an all-lowercase `"taskmgr"` is treated as a single hump and only matches within
one candidate hump, so it does not span `Task`+`Manager`.

In practice users type symbol searches in lowercase and do not want to reach for Shift to
capitalize CamelHumps.
The done doc records the capital-signalled behavior as intentional, but the request is to relax it
so a lowercase query still spans humps the way IntelliJ's own matcher does.

## Sketch

Let an uncapitalized query match as a subsequence that can span multiple candidate humps, aligning
each query character to candidate hump starts case-insensitively — so `"taskmgr"` reaches
`TaskManager` and `"asf"` reaches `AbstractServerFactory`.
Keep this inside the existing `CamelCaseMatcher` / `WorkspaceTypeIndex.searchCamelCase` path so the
exact-prefix `search()` shared with completion and quick-fix providers stays untouched, and keep the
reactor-only scope.

## Open questions

- **False-positive volume.** Lowercase hump-spanning is far looser than capital-signalled matching
  and will surface many more candidates. This likely forces the result *ranking* that EG-005
  deferred (IntelliJ's `MinusculeMatcher.matchingDegree`) so the best matches are not buried; today
  `searchCamelCase` returns index-iteration order with no scoring.
- **Preserve or replace the current rule.** Should capital-signalled matching keep its stricter
  semantics (an explicit capital still forces a hump boundary), with lowercase getting the looser
  spanning, or should both collapse into one case-insensitive scheme?
- **Interaction with the result cap.** With more matches under the cap, ordering determines what the
  user actually sees — reinforces the ranking question.
- **Scope.** Still CamelHump-aligned only, or does this pull in plain infix/substring matching
  (`"erverfac"`), which EG-005 explicitly left as a separate feature?

## Milestone candidate

M2 — sits with the M2 completion-and-search refinements; could also be untriaged pending the
ranking decision.
