# Lathe — README & User-Guide Restructure

Design for trimming the public README from a single ~460-line document into a smaller one that
**stays the primary get-started-and-use-it doc for Neovim**, with only the deep-reference bulk moved
into a few linked guides under `docs/guide/`.
The README stays terse and reference-toned (not a marketing page); it becomes small by extracting the
exhaustive *reference* material, not the getting-started or usage flow.

**Audience.** Neovim only, for now.
VS Code is planned but later; the multi-editor structure is deliberately **deferred** to when that work
starts (§7), not built day one.

**Status.** Implemented, with a course correction during the work — see §10.
The Neovim-first hub described in §§1–9 was superseded by an **editor-agnostic** structure: the README
is now a Neovim-free feature reference, and the Neovim keymaps live in a per-editor cheatsheet under
`docs/guide/editors/`. §§1–9 are retained as the design history; §10 records what actually shipped.

**Scope.**
In: trimming `README.md`; extracting reference guides under `docs/guide/`
(`installation.md`, `neovim.md`, `run-configuration.md`, `test-capture.md`);
adding a "Documentation map" block to `CLAUDE.md`.
Out: the demo clip itself (placeholder only);
the multi-editor structure — `editors/`, an editor-agnostic run/debug model doc, a VS Code guide (§7);
repo-wide status-line conventions and CI link-checking (§7).

---

## 1. Goals and constraints

- **Small, scannable README that is still the primary Neovim guide.**
  A user should get from zero to "debugging works" without leaving the landing page.
- **Achieve "small" by extracting reference bulk**, not the usage flow —
  the biggest inline blocks today are the full LSP list, the overlay schema, and the capture internals.
- **Reference tone**, same terse voice as now.
- **KISS / YAGNI.**
  Neovim is the only editor; introduce no cross-editor abstraction until a second editor exists (§7).

## 2. Principles

- **Progressive disclosure.**
  The README carries getting-started, the Neovim usage flow, and a compact capability table;
  deep reference (manual POM, overlay schema, capture internals) is one click away.
- **Don't factor for a second consumer that doesn't exist.**
  The editor-agnostic run/debug model doc and the `editors/` split earn their keep only with ≥2 editors,
  so they are deferred to the VS Code trigger (§7).
- **DRY where it pays now.**
  Cross-editor DRY is deferred; the reference guides avoid duplicating the README beyond a short summary.

## 3. Target structure

### 3.1 Hub `README.md` (outline — Neovim guide stays inline)

```
# Lathe            intro (kept verbatim)
## Demo            placeholder now → later an inline MP4 (GitHub attachment)
## Requirements    Java/Maven, Surefire 3.5.5+, JUnit Platform
## Editor support  Neovim — supported today. VS Code — coming soon; a short, visible note so users
                   know it's on the way and can check back. (One line now; not the structure — §7.)
## Setup           extension one-liner + mvn clean process-test-classes;
                   link → installation.md for manual POM and when to prefer it
## Features        compact TABLE (capability · one-line · link) — at-a-glance, scannable;
                   full bindable-actions catalog (every command/API + suggested keymap) → neovim.md
## Using Lathe (Neovim)
   ### Install       lazy.nvim spec
   ### Run a `main`  :LatheRun · ▶ signs · <leader>rr/rs · docked output split
   ### Tests         neotest keymaps · docked output · clickable stack frames
   ### Debug         :LatheDebug + WHERE OUTPUT GOES (docked split via lathe/testOutput, NOT the DAP
                     REPL; <leader>to) + nvim-dap-view panel + REPL eval
## How it works    condensed concepts + links:
   ### Test capture   3-4 sentences + limits   → test-capture.md
   ### Run config     overlay summary          → run-configuration.md
## Opt-out & CI    kept (tiny)
## Partial builds  kept (tiny)
## Documentation   links: the guides below · docs/design-index.md (design) · docs/status.md (status)
## Troubleshooting the existing entries (kept inline)
```

### 3.2 `docs/guide/` tree (minimal — three reference guides)

```
docs/guide/
├── installation.md        Maven activation: extension (default) + manual POM, and when to use which
├── neovim.md              Neovim reference: every bindable action/command + suggested keymaps
├── run-configuration.md   overlay schema, fields, selection rules
└── test-capture.md        capture internals, requirements, limitations
```

A flat `neovim.md` holds the exhaustive bindable-actions reference; the README keeps a compact
at-a-glance capability table plus the usage flow, and links to it.
No `editors/` dir and no shared `run-and-debug.md` model doc yet — those wait for VS Code (§7),
at which point `neovim.md` moves under `editors/`.

### 3.3 Per-document content plan (outline)

- **installation.md**
  - "What Lathe needs in the build" — the three pieces stated once
    (compiler shim via `compilerId=lathe`; `lathe-maven-plugin` `init`/`sync` goals; `lathe-junit` test dep).
  - Method 1 — Maven extension (recommended): the `.mvn/extensions.xml` one-liner; injects all three in memory.
  - Method 2 — Manual POM configuration: wire the same three by hand; parent-vs-module placement.
  - "Which to use" comparison table (pom edits / scope / auditability / when manual wins).
  - Verify, plus shared Opt-out & CI.
- **neovim.md** — the complete Neovim reference, so users can bind actions their own way:
  - every LSP action Lathe implements, with its `vim.lsp.buf.*` API and any Neovim default
    (definition, declaration, implementation, references, hover, signature help, completion,
    the four code actions, formatting, document/workspace symbols, type hierarchy, call hierarchy,
    diagnostics navigation, semantic tokens, folding);
  - the Lathe commands (`:LatheRun`, `:LatheRunStop`, `:LatheDebug`) and Lua entry points
    (`require("lathe.neotest").open_output()` / `.stop()`, `require("lathe.output")`), and the neotest
    run actions;
  - the recommended nvim-dap debug binds (breakpoint / continue / step / terminate / REPL) — noted as
    nvim-dap's, bound alongside;
  - each action with a **suggested keymap** (users rebind freely);
  - plus install (lazy.nvim), the debug output surface + clickable frames, nvim-dap-view, REPL eval,
    `LATHE_DEBUG` logging, and nvim-specific troubleshooting.
- **run-configuration.md** — the overlay schema, fields table, and selection rules.
- **test-capture.md** — capture internals, requirements, and current limitations.

## 4. Content migration map

Most content **stays in the README**; only the three bulky reference blocks move out,
and the per-endpoint LSP list is compacted into an inline table.
(Line ranges approximate; re-locate at implementation time.)

| Current section | Destination |
|---|---|
| Installation (extension) | summary stays in README **Setup**; full/manual → `installation.md` |
| Test Capture | condensed summary stays in README; internals → `test-capture.md` |
| Run Configuration | summary stays in README; full schema → `run-configuration.md` |
| Neovim → Supported LSP Features | full catalog → `neovim.md`; a compact capability table stays in README **Features** |
| Neovim → Installation / Verbose Logging / full keymap catalog | `neovim.md` |
| Neovim → Running Tests / Debugger (usage flow) | condensed usage **stays in README**; full binds → `neovim.md` |
| Opt-out and CI · Partial builds · Troubleshooting | stay in README |

## 5. Decisions taken

- **The README stays the primary Neovim get-started + usage doc.**
  It becomes small by extracting reference bulk, not by evacuating the usage flow into sub-docs.
- **Four guides are extracted now:** `installation.md`, `neovim.md`, `run-configuration.md`, `test-capture.md`.
- **A dedicated `neovim.md` holds the complete bindable-actions catalog** — every LSP action, Lathe
  command, and Lua entry point with a suggested keymap — so users can rebind freely. The README keeps
  only a compact at-a-glance capability table and the usage flow, linking to it; `neovim.md` is the
  single source of truth for binds (usage examples in the README point to it to avoid drift).
- **The README Features section becomes a compact capability table**, not the ~40-line per-endpoint list.
- **`installation.md` documents both** the extension (default) and manual POM configuration,
  with a factual "when manual wins" list (extension not honored by a non-standard invocation;
  explicit/auditable config; selective per-module activation) — stated as reference, not as a pitch.
- **Multi-editor structure is deferred** with an explicit trigger (§7) rather than built now.
- **But a short "VS Code — coming soon" note ships in the README now** — an expectation-setter so
  interested users wait and check back. This is one visible line, distinct from the deferred structure.
- **A `CLAUDE.md` "Documentation map" block is added** (§8); heavier wiring is deferred (§7).

## 6. To verify during implementation

- The **manual-POM XML** (compiler-plugin `compilerId`, the `lathe-compiler` plugin dependency,
  `lathe-maven-plugin` executions and phase bindings, coordinates, and the `lathe-junit` test dependency)
  must be lifted from the repo's own `pom.xml` and plugin descriptor — never invented.
- Confirm no content is lost: diff each moved section against its new home before deleting from the README.
- Fix all cross-links; keep prose in semantic line breaks per the repo markdown style.

## 7. Deferred: multi-editor structure (trigger — VS Code work starts)

The README already carries a one-line "VS Code — coming soon" note from day one (§3.1, §5) to set
expectations. What is deferred here is the **structure**, introduced **only when VS Code support
begins**, at which point the abstraction has two real consumers and earns its keep:

- add `docs/guide/editors/neovim.md` and `docs/guide/editors/vscode.md`;
- extract the **editor-agnostic run/debug model** into `docs/guide/run-and-debug.md`
  (replay JVM, JDWP-suspended launch, in-process java-debug host, output via `lathe/testOutput`),
  so both editor guides bind it instead of re-explaining it;
- move the existing `neovim.md` (and the README's Neovim usage flow) under `editors/neovim.md`;
  the README becomes an editor-agnostic hub with an **Editors** pointer list.
- optionally then: standardized greppable `Status:` lines, bidirectional guide↔design footers,
  and a CI link-checker.

Rationale: with one editor, a shared-model doc has a single consumer and the `editors/` split adds
navigation cost for no DRY benefit — that is speculative generality (KISS / YAGNI).

## 8. Wiring docs and status for code tools (minimal now)

Keep this cheap; defer the rest to §7.

- **Single-purpose roles** (already true): guides = *how-to*; `design-index.md` routes to design docs;
  `status.md` is the implemented baseline; `roadmap.md` is milestone scope;
  `gaps/gaps.md` is the authoritative, ID-keyed state registry.
- **`CLAUDE.md` "Documentation map"** — a four-line block, the primary hook for code tools:
  behavior/how-to → `docs/guide/`; design/why → `design-index.md`;
  implemented → `status.md`; active work → `gaps/gaps.md`.
- **README "Documentation" section** links `design-index.md` and the guides.
- **Deferred to §7:** repo-wide greppable `Status:` lines, bidirectional footers, CI link-checking —
  each needs an actual consumer before it is worth the maintenance.

## 9. Suggested implementation order

1. Extract the reference guides:
   `installation.md` (extension + verified manual POM), `neovim.md` (complete bindable-actions
   catalog), `run-configuration.md`, `test-capture.md`.
2. Trim the README: replace the per-endpoint LSP list with a compact Features table linking to
   `neovim.md`; add the condensed "How it works" with links; keep Setup and the Neovim usage flow inline.
3. Add the `CLAUDE.md` "Documentation map" and the README "Documentation" links.
4. Verify links and completeness; land as one docs commit.

## 10. What shipped — editor-agnostic restructure

During implementation the Neovim-first framing (§§1–9) kept reading as "Lathe is a Neovim tool,"
because the feature index routed into the Neovim guide. Reviewer feedback surfaced this repeatedly, so
the structure was corrected to put the editor-agnostic story first. This adopts — now, not deferred
(§7) — the `editors/` split, justified by the public "editor-agnostic language server" positioning and
the imminent VS Code client.

Shipped structure:

- **`README.md`** is a **Neovim-free feature reference**: `Setup` (Maven only), a `Features` section of
  editor-agnostic capability tables (code intelligence, diagnostics/formatting, run/test/debug — *what
  each does*, **no key mappings**), an `Editors` table routing to per-editor guides, `How it works`,
  and generic troubleshooting.
- **`docs/guide/editors/neovim.md`** is the **Neovim cheatsheet**: install plus the keymap tables (the
  action, its command/API, Neovim default, and a suggested mapping) for every session. VS Code becomes
  a sibling `editors/vscode.md` when it lands.
- **`docs/guide/`** keeps the editor-agnostic concept docs (`installation.md`, `run-configuration.md`,
  `test-capture.md`).

Net rule: the README (and concept docs) say *what Lathe does*; the `editors/*` cheatsheets say *how you
bind it* in a given client. The feature index never points at an editor — only the `Editors` section
does.
