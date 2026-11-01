# Lathe — MCP Answers an Agent Can Trust

## Status

Approved and implemented 2026-11-01 on branch `mcp-trust` (PR #73), one commit per gap; each gap's
resolution is in [gaps-archive.md](../gaps/gaps-archive.md).

## Goal

Every answer an agent gets from `lathe-mcp-server` is either right or says why it may not be,
and says it in a form every MCP client shows the model.
Then fewer round trips for the questions agents ask most.

The slice: [MC-58](../gaps/gaps-archive.md#mc-58--claude-code-shows-the-structured-content-so-an-agent-never-sees-the-notes--done),
MC-37, MC-47, MC-35, MC-44, MC-43 (the notes reach the agent and are correct);
MC-62, MC-46 (`verify_change` tells the truth);
MC-51, MC-48 (navigation answers point at the right line);
MC-30, MC-49, MC-38, MC-60 (fewer round trips);
MC-28 (rename says what it left alone);
MC-32 (the agent is told to verify).
Value and size per gap are in [gaps.md](../gaps/gaps.md).

## What other Java MCP servers taught us

Surveyed 2026-11-01: the MCP server built into IntelliJ IDEA (`plugins/mcp-server` in intellij-community),
Dukehoff MCP for Java (NetBeans' javac-based engine, headless),
numsu/java-lsp-mcp and Serena (both on Eclipse jdtls), and the thinner jdtls wrappers.

- **Which part of a result the model sees.**
  Claude Code, Codex CLI and VS Code Copilot hand the model `structuredContent` (stringified) and drop the text;
  Gemini CLI and Cursor show only the text.
  Codex, VS Code and Gemini verified in their sources; Claude Code and Cursor from their issue trackers.
  The spec does not say which a client should show (SEP-1624, which would have, was closed unmerged).
  So text is the only form every client shows the same way, and Lathe drops `structuredContent`.
  None of the jdtls servers returns it either.
- **Addressing a symbol.** NetBeans takes `com.acme.Foo#bar(String,int)`, Serena `Class/method[1]`,
  IntelliJ's call analysis a qualified name (listing the signatures when ambiguous).
  For positions, numsu makes the column optional (the innermost declaration on the line) and clamps a column
  past the end; IntelliJ's rename takes the symbol's name plus an optional exact snippet of the line.
  Lathe takes the cheap form first: `column` optional, a `symbol` name to pick the occurrence on the line.
- **Refusals.** IntelliJ answers each refusal with a fixed kind (`index_not_ready`, `plan_stale`, …) and a hint;
  read tools say `partialResultReason` when indexing was running.
  Lathe's notes already carry more state than any of them; what MC-35 adds is the same shape:
  the reason, then the one action that helps.
- **Rename.** NetBeans lists the string literals that still hold the old name and compiles after the refactor,
  reporting only new errors; IntelliJ changes comments and text only on request.
  Serena's own evaluation found a bare "Success" unhelpful: agents want the changed files.
  MC-28 follows NetBeans: list the leftovers, edit none.
- **Size.** IntelliJ caps test output at 10,000 characters and gives the full file's path;
  its call analysis has `depth`, `maxChildren` and "… and n more".
  Serena answers nothing over its cap, which its users report as a problem: Lathe truncates and says so.
- **Tests that do not compile.** numsu answers `status:"compile-failed"` with the errors, not empty counts.
- **Guidance.** NetBeans measured a `CLAUDE.md` section moving tool use from 0 of 3 sessions to 3 of 3.
  IntelliJ sends no server instructions and puts the bounds in tool descriptions instead.

Ideas new to Lathe, recorded as gaps for later: grouping references by caller (MC-63),
a safe delete that lists what blocks it (MC-64), structural search and replace (MC-65),
and a rename preview (MC-66).

## Design

### Text only (MC-58)

`LatheMcpTools.result` builds every answer; it stops attaching `structuredContent`,
and the per-tool maps that fed it go.
No tool declares an `outputSchema`, so nothing else changes on the wire.
MC-33 (declare an `outputSchema`) closes as a non-goal.

### Notes and refusals (MC-37, MC-47, MC-35, MC-44, MC-43)

- `workspace_summary` carries the same notes as the other tools: a schema mismatch and a pending POM sync.
- A file a rename moved is not "not checked".
- Each refusal names its real cause and the one action that helps,
  and only a refusal whose cause a sync fixes names the sync.
- With no `.lathe/` at start, each call looks again, so a build that creates it is picked up without a restart.
- Expected refusals log at INFO without a stack trace; SEVERE stays for the unexpected.

### `verify_change` (MC-62, MC-46)

- A dependent module is named, with the sync that builds it, only when the change can reach it:
  never for a test source; for main sources, only when a changed file declares a type the dependent can see.
- A changed `lombok.config` counts as a change to the sources below it.

### Navigation (MC-51, MC-48)

- A record's implicit accessor points at its record component.
- Outgoing calls are listed at their call sites, in source order.

### Fewer round trips (MC-30, MC-49, MC-38, MC-60)

- Position tools take `column` as optional and an optional `symbol`:
  the occurrence of that name on the line, else the only name on it.
  `outline` gives each declaration's name column.
- `call_hierarchy` and `search_symbols` take `scope` (`reactor` or `all`);
  outgoing calls default to `reactor`, and reactor symbols rank first.
- A `*` in a symbol query is ignored at either end.
- `run_test` returns the tail of a failing test's output, capped,
  and says when the test does not compile rather than reporting empty counts.

### Rename (MC-28)

After a rename, the remaining occurrences of the old name in reactor files are listed as "not renamed — review",
capped, without editing them.

## Verification

Each commit carries unit or engine tests; each phase is probed end to end on Dropwizard, EqualsVerifier
and Helidon with the `dev/` MCP probes, running this branch's server build.
