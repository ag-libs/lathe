# Lathe — README & User-Guide Restructure

Design for reshaping the public README from a single ~460-line document into a small,
editor-agnostic **hub** plus linked topic and per-editor guides under `docs/guide/`.
The goal is a README that stays terse and reference-toned (not a marketing page),
covers every important feature through an index rather than exhaustive inline prose,
and extends cleanly as features and editors are added.

**Status.** Design only — not yet implemented.
This document is an outline, not the final copy; the prose of each target file is written at implementation time.
The `## Demo` placeholder has already been added to the current `README.md`.

**Scope.**
In: restructuring `README.md`; creating user-facing guides under `docs/guide/`;
documenting both Maven activation methods (extension and manual POM).
Out: the demo clip itself (placeholder only); the VS Code guide body (stub until support lands);
any change to `docs/` design documents.

---

## 1. Goals and constraints

- **Small, scannable hub** in the same terse reference voice as today — not a marketing white paper.
- **Cover all important features** via a compact index table, not long inline prose.
- **Extensible on two axes:**
  a new feature is a new table row (plus an optional linked doc);
  a new editor is a new guide under `editors/`.
- **Editor-agnostic core.**
  Neovim is the primary editor now; VS Code is planned and must slot in without reworking the hub.

## 2. Principles

- **Progressive disclosure.**
  The hub answers only: what Lathe is, how to install it, what it can do, and where the detail lives.
  Exhaustive material (full LSP list, overlay schema, capture internals, manual POM) moves to linked docs.
- **A feature index table** is both the capability map and the extension point.
- **Concept vs editor separation.**
  Concepts (installation, capture, run configuration, the run/debug model) are editor-neutral;
  editor guides carry install steps, key/command bindings, and editor-specific surfacing only.
- **DRY across editors.**
  The run/debug model is written once in `run-and-debug.md`;
  each editor guide binds it rather than re-explaining it.

## 3. Target structure

### 3.1 Hub `README.md` (outline)

```
# Lathe                    intro (kept verbatim)
## Demo                    placeholder now → later an inline MP4 (GitHub attachment)
## Requirements            Java/Maven, Surefire 3.5.5+, JUnit Platform
## Setup                   default path only: register .mvn/extensions.xml → mvn clean process-test-classes;
                           link to installation.md for manual POM config and when to prefer it
## Editors                 pointer list: Neovim (supported) / VS Code (planned) → editors/*.md
## Features                reference TABLE of editor-neutral capabilities; each row links to detail
## How it works            condensed concepts + links:
   ### Test capture          3-4 sentences + limits    → test-capture.md
   ### Run & debug model     replay JVM, in-process DAP host, output via lathe/testOutput
                             → run-and-debug.md
   ### Run configuration     overlay summary           → run-configuration.md
## Opt-out & CI            kept (tiny)
## Partial builds          kept (tiny)
## Documentation           links: editor guides · concept docs · docs/ design docs
## Troubleshooting         editor-agnostic entries only (editor-specific → each guide)
```

### 3.2 `docs/guide/` tree

```
docs/guide/
├── installation.md        Maven activation: extension (default) + manual POM, and when to use which
├── editors/
│   ├── neovim.md          primary, full coverage
│   └── vscode.md          stub ("planned")
├── run-and-debug.md       editor-agnostic run/debug model
├── run-configuration.md   overlay schema
└── test-capture.md        capture internals
```

### 3.3 Per-document content plan (outline)

- **installation.md**
  - "What Lathe needs in the build" — the three pieces stated once
    (compiler shim via `compilerId=lathe`; `lathe-maven-plugin` `init`/`sync` goals; `lathe-junit` test dep).
  - Method 1 — Maven extension (recommended): the `.mvn/extensions.xml` one-liner; injects all three in memory.
  - Method 2 — Manual POM configuration: wire the same three by hand; parent-vs-module placement.
  - "Which to use" comparison table (pom edits / scope / auditability / when manual wins).
  - Verify, plus shared Opt-out & CI.
- **editors/neovim.md** — install (lazy.nvim); LSP feature→keymap table (the big list moves here);
  Run / Test / Debug bindings; output surface + clickable frames; nvim-dap-view panel; REPL eval;
  `LATHE_DEBUG` logging; nvim-specific troubleshooting.
- **editors/vscode.md** — "Planned" stub with a roadmap/status link; filled in when support lands.
- **run-and-debug.md** — the editor-neutral model: replay JVM, JDWP-suspended launch, in-process
  java-debug host, and where program output streams (`lathe/testOutput`), so both editor guides link here.
- **run-configuration.md** — the overlay schema, fields table, and selection rules.
- **test-capture.md** — capture internals, requirements, and current limitations.

## 4. Content migration map

Current `README.md` section → destination (line ranges approximate; re-locate at implementation time).

| Current section | Destination |
|---|---|
| Installation (extension) | `installation.md` (Method 1) |
| What The Build Writes | `installation.md` or `test-capture.md` |
| Test Capture | `test-capture.md` (condensed summary stays in hub) |
| Neovim → Supported LSP Features | `editors/neovim.md` |
| Neovim → Installation / Running Tests / Debugger / Verbose Logging | `editors/neovim.md` |
| Run Configuration | `run-configuration.md` (summary stays in hub) |
| Opt-out and CI · Partial builds · Troubleshooting | stay in hub |

## 5. Decisions taken

- **Workflow prose lives in the concept doc, bindings live per editor** (over self-contained editor guides),
  to avoid duplication once VS Code is added.
- **Layout:** `docs/guide/` with an `editors/` subfolder, kept separate from `docs/` design documents.
- **installation.md documents both** the extension (default) and manual POM configuration,
  with a factual "when manual wins" list (extension not honored by a non-standard invocation;
  explicit/auditable config; selective per-module activation) — stated as reference, not as a pitch.

## 6. To verify during implementation

- The **manual-POM XML** (compiler-plugin `compilerId`, the `lathe-compiler` plugin dependency,
  `lathe-maven-plugin` executions and phase bindings, coordinates, and the `lathe-junit` test dependency)
  must be lifted from the repo's own `pom.xml` and plugin descriptor — never invented.
- Confirm no content is lost: diff each moved section against its new home before deleting from the hub.
- Fix all cross-links; keep prose in semantic line breaks per the repo markdown style.

## 7. Out of scope / future

- The actual demo clip (placeholder in place; record against a public or `com.example` project only).
- The VS Code guide body — stub until support lands.
- Optional: register the new guides in `design-index.md` and/or `docs/README.md`.

## 8. Suggested implementation order

1. Create the concept docs by moving existing sections
   (`test-capture.md`, `run-configuration.md`, `run-and-debug.md`).
2. Create `installation.md` (extension + verified manual POM).
3. Create `editors/neovim.md` (full) and `editors/vscode.md` (stub).
4. Slim the hub `README.md` to the §3.1 outline; add the Features table, Editors section, and links.
5. Verify links and completeness; land as one docs commit.
