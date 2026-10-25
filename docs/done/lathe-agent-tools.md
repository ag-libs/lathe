# Lathe — Agent Tools: Annotations, Format, Imports, Outline, Workspace Summary

## Status

**Shipped 2026-10-24** (PR #48, one commit per gap).
Implements the five accepted gaps from the agent-tooling landscape review —
MC-21 to MC-25, now in [the gap archive](../gaps/gaps-archive.md#mcp--agent-facade-resolved), each
with what was built and where it differs from the design below.
The review that found them is in
[AI Agent Integration — Landscape update](../planned/lathe-ai-agent-integration.md#landscape-update-oct-2026).

## Goal

Give an agent what other code-intelligence servers give it and Lathe does not: clients that know which
tools only read (MC-21), a formatter so its edits pass Spotless (MC-22), the missing imports it gets
wrong most (MC-23), a file's structure without reading it (MC-24), and the workspace's modules in one
call (MC-25).

## Design

Common to every new tool:

- Text in the grep-style forms the MCP-quality work settled on: one `path:line: …` line per located
  result (`LatheMcpTools.locationLine`), workspace-relative paths from the engine
  (`LatheEngine.displayPath`).
- The existing file guard (`isJavaSource` / `notAJavaSource`) for a `file` argument.
- A tool that writes goes through the path `rename_symbol` already uses (`writeAndCommit`): reactor
  files only, under the reactor lock, the change compiled into the mirror before the tool returns.
- Annotations per MC-21 on every new tool.

### MC-21 — tool annotations (`LatheMcpTools` only)

The SDK in use (2.1.0) has `McpSchema.Tool.Builder.annotations(McpSchema.ToolAnnotations)`.
One small helper builds the annotations; every tool declares them:

- `readOnlyHint=true`: `get_diagnostics`, `get_definition`, `find_references`, `find_implementations`,
  `call_hierarchy`, `describe_symbol`, `search_symbols`, `analyze_change`, `verify_change`, and the new
  `outline` and `workspace_summary`.
- `destructiveHint=true`: `rename_symbol`.
- Neither: `run_test` (runs code, writes no source); `format_file` and `fix_imports` are
  `readOnlyHint=false`, `destructiveHint=false`, `idempotentHint=true`.

Test: the registration test (`LatheMcpToolsTest.all_registersEveryTool_withDeclaredInputSchemas` or a
sibling) asserts each tool's hints.
Do not claim client behaviour in the guide that is not confirmed (the review's sources were secondary).

### MC-22 — `format_file`

- Engine `format(Path)`: the editor's formatting request (`LatheTextDocumentService.formatting`, which
  runs the project's pinned formatter), its edits applied with `SourceLocator.applyEdits`, written as
  above.
- Answer: `Formatted Foo.java (N change(s)).` or `Foo.java was already formatted.`

### MC-23 — `fix_imports`

- Engine `fixImports(Path)`: `missingImportsFuture` (the engine behind `lathe.missingImports`, CA-7)
  gives each unresolved name with its candidates.
  Add the **unambiguous** imports, with the import-edit code of the existing import quick fix
  (`ImportQuickFixProvider` / `ImportCandidates`), written as above.
- Answer: the imports added, then each ambiguous name with its candidates, e.g.
  `List: java.util.List, java.awt.List`, which the agent adds itself.

### MC-24 — `outline`

- Engine `outline(Path)` over the document-symbol request (`LatheTextDocumentService.documentSymbol`).
- Answer: one line per member, indented by nesting: `kind name signature  path:line`.

### MC-25 — `workspace_summary` (no arguments)

- A session method, run on the event loop (session state is touched only there), collecting: the
  modules (`session.modules()`), each one's upstream modules (the module graph), whether its test launch
  is captured, the sync command and the build tool.
- Answer: workspace root, module count and sync command, then one line per module, e.g.
  `dropwizard-core  depends on: dropwizard-util, dropwizard-jetty, …  tests: captured`.
- The routing instructions (`LatheMcpServer.INSTRUCTIONS`) tell the agent to call it first, as gopls
  does with `go_workspace`.

## Per commit

Tests (MCP tools test, engine tests after the neighbouring ones); docs (the agent guide's tool table,
`status.md`, the README table, the gap moved to `gaps-archive.md` with its regression targets);
`mvn spotless:apply`, a reactor `mvn verify` (invoker included), and the `dev/` probes on EqualsVerifier,
Dropwizard and Helidon. `dev/agent_task.py` gains calls to the new tools so their answers get reviewed
on a real task. Show the diff and a proposed commit message, and commit only on approval.

## Working notes (from the MCP-quality work)

- Run targeted tests with `-am`, or they compile against stale `~/.m2` jars.
- After installing a new Lathe, an mvnd daemon keeps the old extension: the user runs `mvnd --stop`.
- `~/.cache/lathe/servers/0.1.0-SNAPSHOT/lathe-mcp.args` is shared by every checkout and rewritten by
  the last sync; check a probe's `[startup] … log=` line to know which code it ran.
- The probes default `LATHE_JVM_OPTS=-Xmx2g`; the test repos are `../dropwizard`, `../helidon`,
  `../equalsverifier`.
- The user's merge driver silently keeps one side without a terminal: after a rebase, check for
  dropped changes and redo a merge from the index stages with `git merge-file`.
- KISS, DRY and functional style; comments short and well wrapped (reflow after `spotless:apply`).
