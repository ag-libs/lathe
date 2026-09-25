# Lathe — Change Impact & Verification (`analyze_change`, `verify_change`)

## Status

Proposed. Design approved 2026-09-25; no code yet.
Code-grounded design for two paired MCP tools — a **pre-edit** impact preview (`analyze_change`) and a
**post-edit** scoped recompile (`verify_change`) — and the `LatheEngine` methods behind them.

`verify_change` is deliberately **not** a fresh recompile engine: it is the **agent-facing, synchronous
consumer of the [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md) reaction**, which is
already approved and owns the hard parts (change-set detection, the FULL-compile-from-disk primitive,
topological cross-module ordering, deletion handling, the POM/bulk escape hatch). This doc adds only
what the *agent* path needs on top of that substrate — synchronous invocation and **reporting**
diagnostics back — plus the read-only `analyze_change` sibling, which no doc previously covered.

The MCP tool-surface tables in [AI Agent Integration](lathe-ai-agent-integration.md#mcp-tool-surface)
reference these two as their detailed design.

## Goal

Close the edit loop with two questions an agent actually asks, answered from javac + the Maven reactor
rather than from grep:

- **Before editing:** "what will changing this symbol break?" → `analyze_change`.
- **After editing:** "what did I just break?" → `verify_change`.

`verify_change` is the higher-leverage of the two — a scoped javac replay against the captured
`.lathe/` inputs is the one thing an agent cannot cheaply hand-roll (the same moat as `run_test`).
`analyze_change` is read-only and composes shipped capabilities, so it lands cheaply and pairs
naturally with `rename_symbol` and `find_references`.

## Staleness is the crux — and the substrate now owns most of it

Both tools read `.lathe/`, which may not reflect the working tree. The design turns on distinguishing
**two kinds of staleness**, handled oppositely:

- **In-scope staleness — the signal, not the enemy.** A file the agent just edited has
  `source mtime > compile-stamp` (or no stamp). This is exactly the change-set classification
  [In-Process Workspace Sync §1](../done/lathe-in-process-workspace-sync.md#1-detect-the-change-set-no-new-io)
  already performs (updated + deleted, grouped by module). `verify_change` reuses it — no agent
  cooperation, works even after a raw `sed`.
- **Out-of-scope staleness — the answer-invalidator.** A POM/dependency/JDK change, a new module, a
  non-javac generated source, or a bulk branch-switch means `.lathe/`'s captured **classpath/graph** is
  wrong; an in-process recompile would yield **false diagnostics**. The substrate already routes exactly
  these to the Maven sync prompt / bulk cutoff
  ([escape hatch](../done/lathe-in-process-workspace-sync.md#where-in-process-cannot-win--the-escape-hatch-covers-it)).
  `verify_change` inherits that boundary: when the reaction defers to Maven, `verify_change` **returns
  the same handoff** to the agent rather than a misleading clean bill of health.

The two edge cases the compile-stamp mechanism alone misses are **already solved by the substrate** and
inherited for free: **deletions** (found by scanning stamp *keys* for paths no longer on disk) and
**global staleness** (POM fingerprint / bulk cutoff). This doc therefore does **not** re-specify them;
it consumes them.

## What the agent path adds over the editor reaction

The substrate is built for the editor: it runs in the **idle reconcile**, and it deliberately does
**not** eagerly compile closed cross-module dependents — they *self-heal on open*
([why cross-module is tractable](../done/lathe-in-process-workspace-sync.md#why-cross-module-is-tractable-the-parked-designs-blocker-dissolved)).
An MCP agent is **stateless — it has no open documents**, so "self-heal on open" never fires and there
is nothing to "watch." `verify_change` bridges that gap with two additions:

1. **Synchronous invocation.** Trigger the reaction *now* for the change set (the same
   detect→topo-order→react path the idle tick uses), rather than waiting for a timer.
2. **Report, don't republish.** Collect and return the diagnostics of the changed files **and their
   intra-module callers** — the agent cannot get them by "opening" a file — instead of the substrate's
   `scheduleDownstreamOpenFiles` republish.

## Grounding in the existing engine (verified 2026-09-25)

| Capability | Class / method | Note for this design |
|---|---|---|
| Change-set detect (updated/deleted, per module) | [In-Process Workspace Sync §1](../done/lathe-in-process-workspace-sync.md#1-detect-the-change-set-no-new-io); reads `CompiledStamps.load` | reused wholesale; not re-implemented here |
| FULL compile **from disk** | new primitive introduced by the substrate ([§new primitive](../done/lathe-in-process-workspace-sync.md#the-one-genuinely-new-compiler-primitive)); today `ModuleSourceCompiler.compile()` takes an in-memory buffer | the prerequisite both docs share; `verify_change` waits on it |
| FAST multi-file analyze | `ModuleSourceCompiler.analyzeBatch()` (analyze-only, no `.class`) | used to diagnose caller files cheaply |
| Topo module order | `WorkspaceModuleGraph` topo accessor (added by the substrate) | changed set compiled upstream-first |
| file → module | `WorkspaceModuleRegistry.moduleSourceFor(path)` | empty for a brand-new module → handoff |
| Reactor graph | `WorkspaceModuleGraph.downstreamModuleDirs(moduleDir)` | drives the Tier-3 `mvn` scope |
| Intra-module callers | `ReferenceCandidatePlanner.planCandidates(config, target)` scoped to one module | the caller set `verify_change` eagerly diagnoses |
| Compose targets | `LatheEngine.describe/findImplementations/references` + the graph | `analyze_change` reuses these wholesale |

---

## `analyze_change` — pre-edit impact preview

**Read-only. Composes shipped capabilities; no compile.**

**Input:** `{file, line, column}` — a symbol use/declaration site, consistent with every other position
tool. (The exploratory notes used a `"OrderService#createOrder"` string form; Lathe has no such
resolver, and position addressing is the established contract.)

**New engine method:** `LatheEngine.analyzeChange(Path file, int line, int column)` →
`LatheChangeImpact`, assembled from existing calls:

| Field | Source |
|---|---|
| `symbol`, `publicApi` | `describe()` / symbol info (signature, kind, declaration site, modifiers) |
| `overrideFamily` | `findImplementations()` — overrides / implementations, cross-module |
| `references` (split **production** vs **test**) | `references()`, classified by the owning module's `sourceTree` |
| `affectedModules` | modules that actually contain references (∩ `downstreamModuleDirs`) |
| `relevantTests` | references under a test source tree → their enclosing test class |

**Result record (indicative):**

```text
LatheChangeImpact(
  symbol,              // rendered signature + kind + declaration LatheLocation
  publicApi,           // boolean
  overrideFamily,      // List<LatheLocation>
  productionRefs,      // int
  testRefs,            // int
  affectedModules,     // List<String> (module rel-paths)
  relevantTests,       // List<String> (Class#method or Class)
  staleness            // advisory, see below
)
```

**Staleness contract.** `analyze_change` reflects the **last sync** — correct by construction, because
it is a *pre-edit* tool: run it *before* touching the symbol. It carries the standard `staleModules()`
advisory, strengthened to name when a module *in the computed impact set* is stale (the impact set may
then be incomplete — references added/removed since the last sync are invisible).

---

## `verify_change` — post-edit scoped recompile (Tier 1 + Tier 3)

**New engine method:** `LatheEngine.verifyChange(List<Path> files /* optional */)` →
`LatheVerifyChange`. Not the rejected `verify_build` (an `mvn` wrapper); it drives the in-process
reaction and reports its result, plus a precise `mvn` recommendation Lathe never runs.

### Step 1 — resolve the change set (from the substrate)

Reuse the substrate's classification: updated files (`mtime > stamp` or no stamp) and deleted files
(stamp key with no surviving source), grouped by module. Accept an explicit `files=[…]` override when
the agent knows its own change set.

### Step 2 — escape-hatch gate (inherited)

If the substrate would defer to Maven — POM/module-structure change, non-javac generated sources, a
new module with no `.lathe/` config, or a bulk change set over the cutoff — `verify_change` **skips the
in-process tier** and returns the Tier-3 full-`mvn` handoff with that reason. No in-process compile runs
against a stale classpath.

### Step 3 — Tier 1: react in-process, then diagnose (sub-second)

For the change set, in topological (upstream-first) order:

1. Drive the substrate reaction — FULL compile of updated files from disk into the mirror; apply
   deletions (`deleteClassOutputs` + stamp prune). This writes fresh `.class` so callers resolve the new
   API.
2. Eagerly diagnose the **intra-module callers** of each changed file
   (`ReferenceCandidatePlanner.planCandidates`) via `analyzeBatch()` against the now-updated classpath —
   the report step the editor gets "for free" from open docs.
3. Return the collected diagnostics, grouped per module.

**Diagnostics reported (v1): current in-scope diagnostics only.** There is no stored pre-edit
diagnostic baseline, so "new vs resolved" is not computed. In practice the baseline is empty (a synced
reactor compiles clean), so current diagnostics in the recompiled scope *are* the regressions
(approved decision 1). A git-diff baseline is a possible later refinement.

### Step 4 — Tier 3: cross-module handoff

For a changed **public** API with downstream modules (`downstreamModuleDirs`), Lathe does **not**
eagerly recompile them — consistent with the substrate's own choice not to compile closed cross-module
dependents, and because cross-module annotation-processor aggregation and non-javac generated sources
are reactor properties it will not fake. It emits the minimal correct invocation for the agent to run:

```text
mvn -pl <changed>,<downstream…> -amd process-test-classes
```

Lathe makes the agent's `mvn` precise; it never runs `mvn` itself. Tier 3 is also the fallback for the
Step-2 escape hatch and for cross-module deletions.

Tier 2 (in-process cross-module recompile) stays **deferred**, matching the substrate: the self-healing
argument makes it *analysis*-correct in the codegen-free case, but the cross-module AP / generated-source
edges are exactly why the substrate leaves closed dependents to Maven. Revisit only if measurement shows
the handoff is a real bottleneck.

### Result record (indicative)

```text
LatheVerifyChange(
  changeSet,           // { files[], source: "stamps"|"explicit", deletions[] }
  deferred,            // { toMaven: boolean, reason }  — true ⇒ Tier 1 skipped (Step 2)
  perModule,           // List<{ module, diagnostics[] }>
  crossModule          // { affectedModules[], suggestedMvn }
)
```

### Stamps: follow the substrate

The substrate **records the compile stamp** after a successful in-process FULL compile
([§4](../done/lathe-in-process-workspace-sync.md#4-react-per-module-in-order)) — its whole point is to bring the
mirror current without Maven. `verify_change` drives that same reaction, so it inherits that behaviour
rather than overriding it (this reconciles approved decision 2 with the substrate: the editor and the
agent must not diverge on stamp state). The **cross-module** freshness risk decision 2 worried about is
instead surfaced by the Tier-3 handoff and the still-active POM prompt, not by withholding stamps.
*(P0 spike confirms the substrate's stamp write is in place before `verify_change` relies on it.)*

---

## Approved decisions (with one reconciliation)

1. **v1 diagnostics = current in-scope only** (no new/resolved split). Baseline assumed empty; git-diff
   baseline deferred. *(Unchanged.)*
2. **Stamp handling follows the substrate**, which records stamps on a successful in-process compile.
   *(Reconciled: the original "leave stamps untouched" intent — keep `staleModules()` honest about
   cross-module risk — is now served by the Tier-3 handoff + the retained POM prompt, so `verify_change`
   does not fight the substrate's stamp write. Confirm in P0.)*
3. **Build order:** `verify_change` (Tier 1 + Tier 3) first; `analyze_change` second.

## Phases

1. **P0 — spike.** Confirm the substrate's FULL-compile-from-disk primitive and stamp write land as
   designed, and settle synchronous invocation of the reaction from the MCP engine (the idle-reconcile
   path is timer-driven; `verify_change` needs an on-demand entry).
2. **P1 — `verify_change` engine + tool.** `LatheEngine.verifyChange`: reuse the substrate's change-set
   + reaction, add the eager intra-module caller diagnosis and the Tier-3 handoff; MCP tool + result
   rendering; skill workflow line. **Depends on the substrate landing first.** Exit: an agent edits N
   files and gets a module-scoped recompile plus a precise `mvn` for the cross-module remainder.
3. **P2 — `analyze_change` engine + tool.** `LatheEngine.analyzeChange` composing
   describe/impls/references + graph, prod/test split; MCP tool + rendering. Exit: a pre-edit impact
   summary before a rename or signature change.
4. **P3 — one-shot CLI (packaging follow-on).** Expose `verify_change` as `lathe verify-change --files
   …` so a future deterministic post-edit hook can fire it without an MCP round trip. (The Claude Code
   plugin packaging doc that would consume this was removed from the tree; re-establish that home before
   wiring the hook.)

## Open questions

- **Substrate sequencing** — `verify_change` (P1) hard-depends on
  [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md) landing; if that slips, P2
  (`analyze_change`, no compile dependency) can go first.
- **Synchronous reaction entry** — expose the reaction as a direct call, or briefly drive the reconcile
  and await it? (P0.)
- **`publicApi` precision** — protected/package-private members overridable across modules: treat as
  "effectively public" for impact? (P2 spike.)
- **Intra-module caller closure depth** — one level (matches `call_hierarchy`) or transitive within the
  module? Start one level; revisit if diagnostics miss second-order breakage.

## Non-goals

- **No `mvn`-wrapper `verify_build`** — `verify_change` is in-process javac replay + a precise `mvn`
  *recommendation*, never a reactor build Lathe runs.
- **No faked cross-module freshness** — Tier 3 hands off; codegen/reactor correctness is not simulated.
- **No duplicate change-set/staleness machinery** — the substrate owns detection, deletions, topo order,
  and the escape hatch; this design consumes them.
- **No string-form symbol addressing** for `analyze_change` — position-based, like every other tool.

## Related work

- [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md) — **the substrate.** Owns change-set
  detection, the FULL-compile-from-disk primitive, topo ordering, deletion handling, and the
  POM/bulk escape hatch that `verify_change` drives and inherits.
- [AI Agent Integration](lathe-ai-agent-integration.md) — the MCP tool surface, snippet/authority
  contract, freshness model, and the dropped `verify_build` these two replace honestly.
- [Staleness via Compile Stamps](../done/lathe-staleness-compile-stamps.md) — the per-source stamp map
  the change-set detection reads.
- [Sibling Recompilation](lathe-sibling-recompilation.md) — the in-module dependent refresh reused by
  the substrate's reaction.
