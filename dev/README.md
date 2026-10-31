# Lathe — Dev Tooling

Scripts for building and testing the server locally against real projects.
All files live in `dev/` and are never shipped with the distribution.

---

## Scripts

| File | Purpose |
|---|---|
| `nvim.sh` | Build server + open Neovim with Lathe attached |
| `check-nvim.sh` | Headless Neovim smoke check for the Lathe runtime |
| *(moved)* | The neotest end-to-end check now lives in `lathe-neovim/test/e2e/` and runs in the build (below) |
| `debug-e2e.sh` | Headless end-to-end check of the debug attach flow (DAP over JDWP) against a live server + real replay |
| `debug_probe.py` | Raw DAP client the debug harness drives (launch → attach → breakpoint → inspect → resume) |
| `lsp.py` | Python LSP client library (`LspClient` base + `LatheClient` preset) and CLI diagnostics tool |
| `jdtls.py` | `JdtlsClient` preset — runs Eclipse jdtls on Corretto 25 for differential testing |
| `jdtls_diff.py` | Differential tester — drives Lathe and jdtls over identical probes, reports divergences |
| `explore.py` | Interactive LSP shell — explore any file like an engineer would |
| `mcp.py` | Minimal MCP stdio client (`McpClient`) and CLI for the Lathe MCP server |
| `mcp_battery.py` | MCP smoke on a real workspace: every tool, break → verify → repair, rename round-trip |
| `mcp_staleness.py` | MCP answers after outside changes (file tools, git, a build) on a real workspace |
| `lsp_staleness.py` | The same outside changes seen by an editor session, polled up to a deadline |
| `mixed_staleness.py` | An editor and an MCP session on one workspace at once (EqualsVerifier) |
| `verify_scale.py` | `verify_change` on a type with ~300 callers: time, files checked, server memory (Helidon) |
| `agent_task.py` | A realistic agent task through MCP only, printing every answer for review (Dropwizard) |
| `editor-smoke.sh` | Headless editor smoke on real workspaces, in your own Neovim config |

---

## jdtls_diff.py — differential testing against jdtls

Drives Lathe and Eclipse jdtls over identical probe points on the same project and reports where they
disagree, as triage candidates for the [gap logs](../docs/gaps/gaps.md). Design and rationale:
[lathe-jdtls-differential-testing.md](../docs/done/lathe-jdtls-differential-testing.md).

```bash
# Whole capability surface on one or more files (or a directory):
python3 dev/jdtls_diff.py path/to/File.java
python3 dev/jdtls_diff.py --methods hover,references,rename path/to/File.java
python3 dev/jdtls_diff.py --out dev/jdtls-diff-report.md --max-files 6 path/to/src/main/java/pkg
```

Requirements and behaviour:

- **jdtls** on `PATH` (or `JDTLS_LAUNCHER`), run on **Corretto 25** (`JDTLS_JAVA_HOME`, default
  `/opt/amazon-corretto-25.0.0.36.2-linux-x64`) — jdtls 1.51 cannot run on JDK 26.
- Self-contained coexistence: `JdtlsClient` passes `-Dlathe.disabled=true` as a jdtls-only JVM arg, so
  jdtls imports a plain-`javac` build path even on a Lathe-enabled repo, with no project change.
- First run per workspace imports the project into a cached jdtls `-data` dir (`~/.cache/jdtls-diff/…`);
  later runs reuse it and start in seconds.
- Output: a categorised markdown report — per-capability agreement table plus `lathe_missing` / `differ`
  gap candidates, with a known-difference allowlist for deliberate divergences.

---

## The neotest end-to-end check (moved to `lathe-neovim/test/e2e/`)

Drives the real neotest adapter (`lathe.neotest`) through a headless Neovim against a live Lathe
server and a real replay run over the built `multi-module` invoker fixture — discovery, run, and
per-test results as a user would drive them. It is the red/green signal the
[neotest experience spec](../docs/done/lathe-neotest-experience.md) is written against; each
assertion names the acceptance-criterion id (D1, D3, R1, R3, …) it covers, and known gaps are
printed as `pend` rather than failing until the behaviour is built.

It runs in `lathe-neovim`'s `integration-test` phase, after the headless specs, so CI runs it on
the rows that install Neovim. By hand:

```bash
# one-time: build the fixture so .lathe/ and the launcher cache exist
mvn verify -pl '!lathe-neovim,!lathe-vscode' -Dmaven.test.skip.exec=true -Dinvoker.test=multi-module

lathe-neovim/test/e2e/run-e2e.sh
```

It clones `neotest`, `nvim-nio` and `plenary.nvim` at the commits `lathe-neovim/test/e2e/plugins.txt`
pins, into `lathe-neovim/target/`. The driver is `lathe-neovim/test/e2e/driver.lua`; it reuses
`spec_helper.lua` from the plugin's Neovim spec suite.

---

## debug-e2e.sh — end-to-end debug attach harness

Drives the whole debug flow with no editor and no human — the automatable Phase 1 GO/NO-GO for
[debug support](../docs/done/lathe-debug-support.md). It asks the live server to
`lathe.debug.test` a captured test (launched suspended under a JDWP agent, with an in-process DAP
host), then `debug_probe.py` speaks raw DAP to that host exactly as nvim-dap would —
`initialize → attach → setBreakpoints → configurationDone → stopped → stackTrace/scopes/variables →
continue → terminated` — and asserts the breakpoint stops on the expected line, inspects the frame,
and resumes green.

```bash
# builds the server (debug adds jars to the module path) and the multi-module fixture, then probes
./dev/debug-e2e.sh

# reuse an already-built fixture (skip the mvn build)
SKIP_BUILD=1 ./dev/debug-e2e.sh
```

By default it breakpoints `HelloTest.java:16` in the public `multi-module` invoker fixture. A
different workspace/file can be probed by passing them through (e.g. a local project):

```bash
./dev/debug-e2e.sh /abs/workspace /abs/workspace/.../SomeTest.java 42 methodName
```

Only `python3` is required — no Neovim, no nvim-dap. On failure it prints the server-side debug log
(the DAP host and source-lookup path) to show the cause. Because debug puts the java-debug jars on
the server's module path, the fixture's launcher must come from a freshly installed server, so the
harness builds by default; `SKIP_BUILD=1` reuses whatever is already built.

---

## Release smoke on real workspaces — mcp_battery.py, editor-smoke.sh

Two batteries for checking a server build against Dropwizard, EqualsVerifier and Helidon clones before
a release.
Sync each workspace with the build under test first (`mvn process-test-classes`; Helidon's HEAD
needs JDK 27, Dropwizard JDK 25).
`mcp_battery.py`'s two `run_test` checks also need a captured test launch: run its test module once
with `.lathe/lathe-sync.sh --tests <module>` (or `mvn test` on one test class).

```bash
# MCP: every tool once, a break/verify/repair cycle, and a cross-module rename round-trip
python3 -I dev/mcp_battery.py ~/git/dropwizard dropwizard

# Editor: navigation, references, completion while typing, diagnostics, format, dependency
# sources and neotest, driven inside your own Neovim config with this checkout's Lua client
dev/editor-smoke.sh ~/git                    # all three; or name some: ~/git helidon
```

Both print one `PASS`/`FAIL` line per check and exit 1 if any check fails.
`mcp_battery.py` restores every file it touched; the editor smoke never writes a file.
`mcp_battery.py` uses the launcher `mcp.py` picks: `LATHE_MCP_LAUNCHER`, else the newest cached server.

---

## Staleness after outside changes — mcp_staleness.py, lsp_staleness.py

Change a real workspace the way an agent's file tools, git or a build would, then check that every
answer reflects the disk: types added, renamed, moved and deleted (nested and secondary ones too),
a broken upstream file, a POM edit (live and while no server ran), a test resource added and
deleted, a test edited before `run_test`, a Helidon blueprint whose builder must be regenerated, a
build running meanwhile, two sessions, and a source-only branch switch.
They target the same Dropwizard, EqualsVerifier and Helidon clones as the release smoke above, and
restore every file they touch.

```bash
python3 -I dev/mcp_staleness.py ~/git/helidon helidon             # every scenario
python3 -I dev/mcp_staleness.py ~/git/dropwizard dropwizard add verify
LATHE_PROBE_OLD_REF=HEAD~150 python3 -I dev/mcp_staleness.py ~/git/dropwizard dropwizard branch
python3 -I dev/lsp_staleness.py ~/git/equalsverifier equalsverifier
```

The MCP probe asks each question once and, if the answer is wrong, again after 3 and 8 s, tagging
the failure `[LAG]` or `[NEVER]`; the LSP probe polls the diagnostics the server pushes for an open
file, as an editor shows them.

Three more look at agents' day-to-day use:

```bash
python3 -I dev/mixed_staleness.py ~/git/equalsverifier   # editor + agent: rename, saves, mass edit, undo
python3 -I dev/verify_scale.py ~/git/helidon             # verify_change on GenericType (~300 callers)
python3 -I dev/agent_task.py ~/git/dropwizard            # find, understand, rename, edit, verify, test, undo
```

`mixed_staleness.py` acts as Neovim does beside an agent: it reloads a buffer the agent rewrote and
saves its own edits, and checks both servers' logs for errors. `verify_scale.py` prints each call's
time, the server's memory, and the `[verify]` log lines. `agent_task.py` judges nothing: it prints every
answer in full, to read the way an agent would.

The dev clients (`mcp.py`, `lsp.py`) start servers with `LATHE_JVM_OPTS=-Xmx2g` unless the variable is
set: Lathe itself sets no heap limit.

---

## nvim.sh — interactive testing in a real editor

```bash
./dev/nvim.sh /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/Scheduling.java
```

On each launch it:
1. Builds `lathe-server` and its dependencies from source
2. Opens Neovim with the built classes (no installed JAR needed)
3. Starts the LSP server automatically on Java files

**Debug port** (attach a debugger):
```bash
LATHE_DEBUG_PORT=5005 ./dev/nvim.sh path/to/File.java
```

---

## check-nvim.sh — headless Neovim runtime smoke check

```bash
./dev/check-nvim.sh
```

The script uses `nvim` from `PATH`, starts it with `--clean --headless`,
loads the Lathe runtime for that process only,
and verifies Java Tree-sitter indentation for `module-info.java`.
It redirects Neovim cache and state writes into a temporary directory.

By default it checks the source runtime under `lathe-neovim/runtime`.
To check the runtime installed by `lathe:sync` into a workspace, point at that workspace's `.lathe/neovim`:

```bash
LATHE_NVIM_RUNTIME="/path/to/workspace/.lathe/neovim" ./dev/check-nvim.sh
```

If your Java parser is not under the common `nvim-treesitter` lazy path,
point the script at a runtime directory containing `parser/java.so`:

```bash
LATHE_NVIM_PARSER_RUNTIME="$HOME/.local/share/nvim/site/pack/plugins/start/nvim-treesitter" \
  ./dev/check-nvim.sh
```

---

## explore.py — interactive LSP shell

`explore.py` opens a single Java file in the Lathe server and drops into a
read-eval-print loop.  You interact with the file the same way a software
engineer would: look at the code, position the cursor at an interesting spot,
ask for completions or hover information, temporarily inject a new line to
see what the engine suggests there, then reset.

No probes are hard-coded.  The file and the questions are entirely up to you.

### Open-file limit

The server retains attributed javac analysis for recently used open files.
That cache is bounded by an LRU with a default cap of **100 open-document analyses**.
Evicted documents stay open; the next feature request recompiles that file and re-enters it into the LRU.

Scripted or agent-driven exploration should still avoid unbounded open sets on memory-constrained machines.
When the goal does not require accumulated open documents, prefer open/probe/close loops.
When stress-testing eviction, monitor heap/RSS and server logs with `LATHE_DEBUG=1`.

### Usage

**Interactive REPL**
```
python3 dev/explore.py path/to/File.java
```

**Single inline command** (remaining args are joined as one command line):
```
python3 dev/explore.py File.java complete after "handler.getServletContext()."
python3 dev/explore.py File.java hover "MongoClients"
python3 dev/explore.py File.java inject "MongoClients."
```

**Piped / sub-agent** (one command per stdin line, no prompt):
```
printf 'grep setAttribute\ncomplete after "handler.getServletContext()."\n' \
    | python3 dev/explore.py AbstractServerFactory.java
```

**External dependency or JDK cache source** (open a file outside the workspace,
but initialize Lathe with the real workspace):
```
python3 dev/explore.py --workspace /home/ag-libs/git/dropwizard \
    /home/ag-libs/.cache/lathe/deps/io.dropwizard.metrics/metrics-core/4.2.38/com/codahale/metrics/MetricRegistry.java
```

Use `--workspace` when the file being probed does not live under a `.lathe/`
directory, such as dependency or JDK sources extracted into the Lathe cache.

### Commands

| Command | What it does |
|---|---|
| `show [<line>]` | Print file with line numbers, centred on `<line>` (1-based) if given |
| `grep <pattern>` | Show every line containing `<pattern>` with its line number |
| `complete <line>:<col>` | Completions at the given 0-based position |
| `complete after <text>` | Find first occurrence of `<text>`, complete right after it |
| `accept <line>:<col> <selector>` | Select one completion item and show the accepted edit |
| `accept after <text> <selector>` | Accept a completion right after the first matching text |
| `accept inject <code> [at <line>] <selector>` | Inject a temporary line, accept one completion, and show the edit |
| `hover <line>:<col>` | Hover info (type, docs) at the given 0-based position |
| `hover <text>` | Hover info at the first occurrence of `<text>` |
| `sig <line>:<col>` | Signature help (overloads, active parameter) at the given 0-based position |
| `sig after <text>` | Find first occurrence of `<text>`, show signature help right after it |
| `definition <line>:<col>` | Definition site of the symbol (alias: `def`) |
| `declaration <line>:<col>` | Declaration site of the symbol (alias: `decl`) |
| `refs <line>:<col>` | Known call/use sites of the symbol (alias: `refs <text>`) |
| `refs <text>` | Find first occurrence of `<text>`, request references there |
| `impl <line>:<col>` | Concrete implementations of the type/method (alias: `implementation`, `impl <text>`) |
| `impl <text>` | Find first occurrence of `<text>`, request implementations there |
| `hierarchy <line>:<col>` | Full type hierarchy: item + supertypes + subtypes (alias: `hier`, `hierarchy <text>`) |
| `hierarchy <text>` | Find first occurrence of `<text>`, show its type hierarchy |
| `callers <line>:<col>` | Incoming callers of the method (alias: `callers <text>`) |
| `callers <text>` | Find first occurrence of `<text>`, show its incoming callers |
| `callees <line>:<col>` | Outgoing callees of the method (alias: `callees <text>`) |
| `callees <text>` | Find first occurrence of `<text>`, show its outgoing callees |
| `sym <query>` | Search the workspace type index for types matching `<query>` (prefix match) |
| `symbols` | Hierarchical document symbols for the open file (alias: `outline`) |
| `folds` | Folding ranges for the open file (alias: `folding`) |
| `diagnostics` | Compiler errors and warnings (alias: `diag`) |
| `runnables` | List discovered run targets (main methods, test methods/classes/packages), numbered for `run` |
| `run <index> [expect-exit <n>]` | Replay the runnable at `<index>` from captured `.lathe/` bytecode; prints `[BLOCKED]`/`[PASSED]`/`[FAILED]` |
| `inject <code> [at <line>]` | Insert a temporary line, complete at its end |
| `reset` | Discard injected content, restore original server view |
| `log [<n>]` | Last `<n>` relevant server log lines (default 20) |
| `help` | Print the command reference |
| `quit` / `exit` / `q` | End the session |

### Assertion qualifiers

Append to `complete`, `inject`, or `refs` commands to turn observation into a
pass/fail check.  Multiple qualifiers may be combined freely.

**Completion / injection assertions** (match against completion item labels):

| Qualifier | Meaning |
|---|---|
| `expect <label> [<label> …]` | Each label must appear as a prefix of at least one returned item |
| `min <n>` | At least `n` items must be returned |
| `max <n>` | At most `n` items may be returned |
| `filter <prefix>` | Every item's label must start with `prefix` (case-insensitive) |

**Reference assertions** (match against result file paths):

| Qualifier | Meaning |
|---|---|
| `expect <substr> [<substr> …]` | Each substring must appear in at least one result's file path |
| `min <n>` | At least `n` references required |
| `max <n>` | At most `n` references allowed |

On success the output line reads `[PASS]`.  On failure it reads `[FAIL]` with
a reason, and the process exits with code 1.  This makes assertions composable
in shell scripts.

```bash
# assert that setAttribute is offered after the chained call
python3 dev/explore.py AbstractServerFactory.java \
    complete after "handler.getServletContext()." expect setAttribute min 1

# assert that a member-access after injection surfaces the right factory method
python3 dev/explore.py MongoDbClient.java \
    inject "MongoClients." expect create min 1

# assert that a bare-dot with no receiver returns nothing
python3 dev/explore.py MongoDbClient.java \
    inject "." max 0

# assert that a public method has at least one cross-module reference
python3 dev/explore.py StringUtils.java refs "upper" min 1 expect "Main.java"

# assert that a private member has no references outside its file
python3 dev/explore.py StringUtils.java refs "StringUtils()" max 0

# pipe multiple reference checks in one server session
printf 'refs "upper" min 1 expect Main.java\nrefs "StringUtils()" max 0\n' \
    | python3 dev/explore.py StringUtils.java
```

### Probing code actions and refactorings end to end

`actions` lists the code actions for a selection, given the way an editor sends it: a caret
(`12:8`), a range (`12:8-14:23`), whole lines as Neovim's `V` sends them (`lines 13-15`, 1-based),
or the span of a text (`"a.trim()"`). `apply` then applies one of them to the live buffer, prints
the diff, and fails the probe if the server reports any error in the rewritten code, so a refactoring
that breaks the file is caught end to end. `absent` asserts a refusal.

```bash
# extract method on an expression offers both actions, and the rewrite compiles
printf 'actions "a.trim().length() * 2" expect "Extract method"\napply "Extract method"\n' \
    | python3 dev/explore.py Service.java

# a line-wise selection finds the expression; a constant is not extracted into a method
python3 dev/explore.py Service.java actions lines 41-43 expect "Extract variable"
python3 dev/explore.py Service.java actions "1 + 2" absent "Extract method"
```

The probe runs the installed `0.1.0-SNAPSHOT` server (see `lsp.py`), so install the checkout first.

### Probing accepted completion edits

`accept` inspects what selecting one completion item would do.
It prints the raw selected `CompletionItem`,
applies `textEdit` and `additionalTextEdits` in memory,
and marks the inferred post-accept cursor with `§`.

Selectors can be combined:

| Selector | Meaning |
|---|---|
| `label <label>` | Item label must equal `<label>` |
| `filter-text <text>` | Item `filterText` must equal `<text>` |
| `label-detail <detail>` | Item `labelDetails.detail` must equal `<detail>` |
| `detail-contains <text>` | Item `detail` must contain `<text>` |
| `index <n>` | Choose the nth item after filters, default `0` |

For `accept inject`,
the injected code may contain `§` to place the completion cursor before trailing text.
The marker is removed from the temporary content.

```bash
python3 dev/explore.py /path/to/File.java \
    accept inject 'LOGGER.de' at 63 filter-text debug label-detail '(String)'

python3 dev/explore.py /path/to/File.java \
    accept inject 'import static java.util.Collections.emptyL' at 43 label emptyList

python3 dev/explore.py /path/to/File.java \
    accept inject 'import static java.util.Collections.emptyL§;' at 43 label emptyList
```

### Probing find-references

`refs <text>` is the primary tool for exploring and verifying reference behaviour.
Position the cursor by naming a token — no need to know the exact line and column.
Reference searches display LSP work-done progress as candidates complete.

Long searches can be cancelled through either standard protocol route after a candidate threshold:

```bash
python3 dev/explore.py File.java refs String cancel-progress 100
python3 dev/explore.py File.java refs String cancel-request 100
```

Lifecycle probes verify bounded server exit during an active search:

```bash
python3 dev/explore.py File.java refs String shutdown-after 100
python3 dev/explore.py File.java refs String eof-after 100
```

After completion or cancellation, run `hover <text>` in the same interactive session to verify that the server remains responsive.

**What to probe:**

- **Public methods** — expect cross-module hits (`expect <filename>`), exact count (`min`/`max`)
- **Private members** — scope must not leave the file (`max 0`)
- **Package-private members** — scope must not leave the module
- **Imports** — the import statement itself should count as one reference
- **Enum constants** — `refs "CONSTANT_NAME"` should find all qualified uses
- **Annotations** — `refs "MyAnnotation"` should find annotation use sites
- **Overloaded methods** — two overloads must not bleed into each other

### Typical workflow

```
> grep getServletContext
    577      handler.getServletContext().setAttribute("org.eclipse.jetty.server.webapp...");

> complete after "handler.getServletContext()."
  completing after "handler.getServletContext()."  →  position 576:36
  ────────────────────────────────────────────────────────────────
  ...
  >>> 577    handler.getServletContext().setAttribute("org.eclipse.jetty.server...
  ────────────────────────────────────────────────────────────────
  28 item(s):
    setAttribute  [Method]  void
    getAttribute  [Method]  Object
    ...

> hover "getServletContext"
  found "getServletContext" at 576:11
  javax.servlet.ServletContext Handler.getServletContext()

> inject "MongoClients."
  injected at line 101:  "        MongoClients."
  completing at 100:20
  ────────────────────────────────────────────────────────────────
  3 item(s):
    create  [Method]  MongoClient
    createWithCustomClass  [Method]  MongoClient
    ...

> reset
  reset to original content

> quit
```

### Columns are 0-based

`complete` and `hover` take **0-based** line and column numbers, matching the
LSP wire protocol and the coordinates logged by the Lathe server.  `show` and
`grep` report **1-based** line numbers (as editors do).  `complete after <text>`
and `hover <text>` handle the conversion automatically.

---

## lsp.py — LSP client library

`lsp.py` is the shared foundation for `explore.py` and ad-hoc probes.
It handles work-done progress and wraps the Lathe server process in synchronous and cancellable asynchronous APIs.

There is intentionally no Python test suite for `dev/`.
These scripts are developer tools, not part of the Maven build or release gate.
When changing them, validate the affected path with direct commands against a real workspace.

Useful manual validations:

```bash
python3 -m py_compile dev/lsp.py dev/explore.py

LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol cancel-progress 100
LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol cancel-request 100
LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol shutdown-after 100
LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol eof-after 100
```

These probes cover progress parsing, progress-token cancellation, `$/cancelRequest` cancellation,
bounded shutdown, EOF handling, and post-cancellation responsiveness.
They do not launch Neovim and they are not a replacement for Java-side LSP request tests.

### CLI — print diagnostics for a file

```bash
python3 dev/lsp.py path/to/Foo.java
python3 dev/lsp.py path/to/Foo.java:42:10   # also prints hover at line 42, col 10 (1-based)
python3 dev/lsp.py --workspace /home/ag-libs/git/dropwizard \
    /home/ag-libs/.cache/lathe/jdks/corretto-25.0.0.36.2/java.base/java/lang/String.java
```

### Importable `LatheClient`

```python
import sys; sys.path.insert(0, "/home/ag-libs/git/lathe/dev")
from lsp import LatheClient, find_workspace_root
from pathlib import Path

file = Path("/home/ag-libs/git/helidon/.../Scheduling.java")
with LatheClient.start(find_workspace_root(file)) as c:
    diags    = c.open(file)
    items    = c.completion(file, line=10, col=5)   # 0-based
    hover    = c.hover(file, line=10, col=5)
    sig      = c.signature_help(file, line=10, col=5)
    defs     = c.definition(file, line=10, col=5)
    decl     = c.declaration(file, line=10, col=5)
    refs     = c.references(file, line=10, col=5)
    impls    = c.implementation(file, line=10, col=5)
    hier     = c.prepare_type_hierarchy(file, line=10, col=5)
    supers   = c.type_hierarchy_supertypes(hier[0]) if hier else []
    subs     = c.type_hierarchy_subtypes(hier[0]) if hier else []
    chier    = c.prepare_call_hierarchy(file, line=10, col=5)
    incoming = c.call_hierarchy_incoming(chier[0]) if chier else []
    outgoing = c.call_hierarchy_outgoing(chier[0]) if chier else []
    fmt      = c.formatting(file)
    symbols  = c.document_symbols(file)
    folds    = c.folding_ranges(file)
    wsym     = c.workspace_symbol("MyClass")
    runnable = c.runnables(file)                     # discover test/main targets
    outcome  = c.run_test("module-rel", "METHOD", "com.example.FooTest#bar()")
    diags    = c.save(file)                          # trigger re-analysis
```

### Environment variables

| Variable | Default | Effect |
|---|---|---|
| `LATHE_DEBUG` | off | Set to `1` for verbose server logs on stderr |
| `LATHE_TIMEOUT` | `15` | Per-request timeout in seconds |

### Prerequisite: build the server

```bash
mvn install -pl lathe-server -am -DskipTests
```

Or run `./dev/nvim.sh` once — it builds automatically.
