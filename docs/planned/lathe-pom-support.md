# Lathe — `pom.xml` Support (Completion & Diagnostics)

## Status

Proposed — no code yet. Approved architecture pending; sub-decisions open (see Open decisions).

This is a new area: Lathe is a Java-only LSP server today, and this design adds a second, self-contained
file-type path for `pom.xml` — schema-driven structure completion and XSD diagnostics, plus
build-derived coordinate/property/reactor completion read from a `lathe:sync` capture. It touches all
four modules and the Neovim client, so it is sliced to ship the no-capture parts first.

## Goal

Give a developer editing a reactor `pom.xml` the same "the tool understands my build" experience Lathe
already gives for Java: valid-element completion, coordinate/version/property/module completion drawn
from the *actual* reactor, and inline diagnostics — all offline, with no Maven invocation and no network
from the server, consistent with Lathe's build-derived model.

## Scope

Confirmed with the user:

- **Data source** — all coordinate/version/property/reactor intelligence is **captured by `lathe:sync`
  into `.lathe/`** and read by the server. No `~/.m2` walk at request time, no Maven Central, no network.
- **Completion** — all four: (1) XML structure/schema (valid child elements), (2) dependency/plugin
  coordinates (`groupId`/`artifactId`/`version`), (3) `${…}` property references, (4) reactor/parent
  references (`<module>` names, parent coordinates, sibling modules).
- **Diagnostics** — XML well-formedness + Maven POM schema (XSD) validation **only**. Not unresolved
  dependencies, not duplicate/redundant declarations, not unresolved-property checks (those are possible
  later slices, explicitly out of scope now).

## Architectural constraint

Lathe's whole model is build-derived: the server never runs Maven and never touches the network; it
reads what `lathe:sync` captured into `.lathe/` — exactly as dependency sources, type-index shards, and
`workspace.json` already are. POM intelligence follows the same contract: `lathe:sync` writes it, the
server reads it. XML structure and schema validation need no captured data (they come from a bundled
schema), so those can ship before any capture work exists.

## Slice 0 — client attachment (prerequisite)

The server already accepts any `file://` URI — `LatheTextDocumentService.ignoreNonFile` rejects only
non-file schemes, with no `.java` check (`LatheUri.isFileUri`). The only gate is the Neovim client,
which hard-limits attachment to `filetypes = { 'java' }` (`neovim/lua/lathe.lua`). So "make LSP work
with pom.xml" reduces to teaching the client to attach Lathe to `pom.xml` buffers.

**Attach by filename, keep the filetype as `xml`.** A dedicated/compound filetype (`xml.pom`) risks
breaking treesitter and other plugins that key on the exact filetype string. Leaving the buffer as an
ordinary `xml` buffer preserves treesitter, syntax, and folding untouched; we only add a `pom.xml`-only
autocmd that starts the Lathe client for that buffer:

```lua
vim.api.nvim_create_autocmd({ 'BufReadPost', 'BufNewFile' }, {
  pattern = 'pom.xml',
  callback = function(args) vim.lsp.start(vim.lsp.config.lathe, { bufnr = args.buf }) end,
})
```

`filetypes = { 'java' }` stays as-is so we do not grab every `.xml` file. Root detection is unaffected:
`root_dir` walks up for the `.lathe` marker, which a pom inside a Lathe workspace satisfies.

## Server design (KISS)

The POM path shares **none** of the javac stack (`SourceAnalysisSession`, `CompilationWorker`,
`CompilerRoute`, `CompilationAdmission`); forcing it through the javac-shaped route/worker abstraction
would be a poor fit. Instead:

- **Dispatch seam** — one filename branch in `WorkspaceSession`: if the document is `pom.xml`
  (`LatheLayout.POM_XML`), hand off to `PomService` for `onOpen`/`onChange`/`onSave`/`completion`;
  everything non-POM is unchanged. `routeCompiler`/`CompilerRoute`/`CompilationWorker` are not touched —
  POM never enters the javac route.
- **Reuse as-is** — `DocumentRegistry` (content-agnostic) for buffer tracking; `DiagnosticPublisher`
  (generic) for publishing. No new worker/thread pool — POM analysis is a parse + validate + map lookup,
  cheap enough to run on the workspace worker, off the compilation-admission path.
- **Capabilities** — no new registration except adding `<` as a completion trigger character;
  diagnostics already push via `publishDiagnostics`.

New classes (JDK-native, zero new dependencies):

| Class | Responsibility |
|---|---|
| `PomService` | Orchestrates the POM path: on open/change/save → validate + publish; on completion → context + suggest. |
| `PomValidator` | Diagnostics. JDK `javax.xml` only: namespace-aware `SAXParser` (well-formedness) + `javax.xml.validation.Validator` against the bundled XSD; a custom `ErrorHandler` turns `SAXParseException` (line/column) into LSP `Diagnostic`s. |
| `PomContext` | A small, tolerant offset scanner: from the cursor, scan back over tags to the enclosing element path (e.g. `project → dependencies → dependency → version`) and the token kind (element-name vs. text). No full XML DOM/AST — just "where am I". |
| `PomSchema` | Loads the bundled Maven POM XSD once; hands `PomValidator` its `Validator` and DOM-walks the XSD into an `element → allowed-children` map so structure completion is derived, not hardcoded. |
| `PomCompletion` | The only real logic: switch on `PomContext`'s enclosing element — generic element → allowed children; inside coordinate elements → coordinates/versions from the index; `<module>`/parent → reactor siblings; text with `${` → property names. |
| `PomIndex` | Reads the captured `pom-index.json` at workspace load (mirrors how `workspace.json` is read). Absent (project not synced with this feature) → completion degrades to structure-only, no failure. |

Bundled resource: the Apache-licensed Maven `4.0.0` POM XSD in `lathe-server` resources.

**Net server footprint:** ~5 new classes + 1 bundled XSD + a one-branch dispatch hook.

## Capture (`lathe:sync` → `.lathe/pom-index.json`)

`lathe:sync` already holds the resolved `MavenProject` list (`SyncCoordinator.run`) and writes
`workspace.json` via `WorkspaceManifestWriter`. A sibling `PomIndexWriter` writes `pom-index.json`:

- **lathe-core** — `LatheLayout.POM_INDEX_JSON = "pom-index.json"` + a schema-version constant; a
  `schema/PomIndexData` record (+ nested records) serialized by the existing `Json` helper: reactor
  modules (gav, relative path, parent gav, child `<module>` dirs), per-module effective properties, and
  a coordinate catalog (`groupId → artifactId → [versions]`).
- **lathe-maven-plugin** — `PomIndexWriter`, wired into `SyncCoordinator.run()` beside
  `WorkspaceManifestWriter`, reading coordinates/properties/parent/modules straight off the resolved
  projects.

**Coordinate-catalog scope (open decision, see below):** start with the reactor + managed-dependency +
plugin coordinate set, enriched with the versions of *those* artifacts present in the local repo (so
"complete the version of a dependency I already use" and "reactor sibling" work offline and cheap). Defer
a full `~/.m2` walk (comprehensive but large/slow; better as a later globally-cached index).

## Slicing

1. **Diagnostics** (no capture) — Slice 0 client attach + dispatch seam + `PomContext`-independent
   `PomValidator` + `PomSchema` validator + bundled XSD → well-formedness & schema diagnostics.
2. **Structure completion** (no capture) — `PomContext` + `PomSchema` element model + `PomCompletion`
   structure suggestions.
3. **Capture + data-driven completion** — `PomIndexWriter` writes `pom-index.json`; `PomIndex` reads it;
   `PomCompletion` adds coordinate, property, and reactor/parent suggestions.

## Alternatives considered

### Neovim-only XSD validation (no server diagnostics)

XSD validation needs no captured data — only the schema — so it can be done entirely client-side,
removing `PomValidator` and the bundled XSD from the server and dropping the diagnostics slice to zero
server code. Two client-side mechanisms:

- **`xmllint --schema` (libxml2) via `nvim-lint`/an autocmd.** Lightest: bundle (or cache) the Maven XSD
  and run `xmllint --noout --schema maven-4.0.0.xsd pom.xml` on save, mapping its line-numbered errors to
  diagnostics. Diagnostics-only. Cost: an external binary dependency (ubiquitous on Linux/macOS, not
  default on Windows) that must degrade gracefully when absent.
- **`lemminx` (Eclipse XML LSP) via lspconfig.** A mature XML language server that does XSD validation
  *and* schema-driven completion/hover natively, honoring `xsi:schemaLocation`; `lemminx-maven` adds
  Maven-specific completion. Cost: a second Java process to install/manage, and it overlaps most of this
  design — it would coexist with Lathe on the pom buffer (LSP allows multiple servers per buffer), with
  Lathe contributing only the build-derived reactor completion nobody else can.

**Implication.** If a client-side XML tool already covers XSD validation (and, with lemminx, structure
completion), Lathe's *unique* value on `pom.xml` is only the build-derived, reactor-aware completion from
`.lathe/pom-index.json`. That argues for a leaner server that does **only** the reactor completion and
delegates generic XML/XSD to the client. The trade is a client-side dependency (`xmllint` or `lemminx`)
and a less self-contained install versus Lathe's "one tool" story. Recorded as the primary open decision.

### Embed LemMinX inside `lathe-server`

Rejected under KISS: it drags in a large transitive tree and is effectively a second LSP engine, against
the project's single-implementation / no-heavy-framework preference. The JDK-native path
(`javax.xml` + a small scanner + the bundled XSD) covers the confirmed scope with zero dependencies.

## Open decisions

1. **Where does XSD validation live — server (`PomValidator`) or Neovim client (`xmllint`/`lemminx`)?**
   Server keeps the "one tool, no external binary" story; client-side is less server code and, via
   lemminx, also yields structure completion for free. See Alternatives.
2. **XML engine** — JDK-native (recommended) vs. embed LemMinX. Native chosen under KISS above.
3. **Coordinate-catalog scope** — reactor/managed set first (recommended) vs. full `~/.m2` index.

## Non-goals (this design)

- Unresolved-dependency, duplicate/redundant, and unresolved-property **diagnostics** (later slices).
- Effective-model computation in the server (that is the sync-side capture's job).
- Cross-POM / parent-chain resolution in the server beyond what the capture records.
- Any XML file other than `pom.xml`.
