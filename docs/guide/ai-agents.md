# Lathe for AI agents (MCP)

Lathe exposes its analysis to AI coding agents through an **MCP server** (`lathe-mcp-server`).
It is the same build-derived engine that powers the editor, offered as a curated set of tools over
the [Model Context Protocol](https://modelcontextprotocol.io) — so an agent gets javac-accurate,
cross-module code intelligence instead of guessing from `grep`.

The wedge is the operations text search gets wrong on a real reactor: compiler-truth diagnostics on
the exact build classpath, navigation that follows into dependencies and generated sources, finding
every real use (and every implementer/override) of a symbol, safe reactor-wide rename, and replaying
a test without a build.

## Prerequisite: a populated `.lathe/`

The server reads from Lathe's build capture, exactly like the editor.
Without it the server still starts, but every tool answers with where it looked and how to set the project up.
For Maven, register the Lathe extension and run the first build once at the reactor root; it records the
project's setup without running the tests:

```bash
mvn clean test -Dlathe.capture.only=true
```

That build does two things at once: it writes `.lathe/`, and its `lathe:sync` step installs the MCP
launcher (below). If the server reports a missing `.lathe/`, this is the fix.
For `run_test`, the module's test launch must also be captured once:
`.lathe/lathe-sync.sh --tests <module>` does that without running the tests (see
[test capture](test-capture.md)); `run_test` names this command when the capture is missing.

## The launcher

There is no separate install. `lathe:sync` generates the launcher during the build above and exposes
it two ways:

- **Per-project** (recommended) — a launcher pinned to the server version *this* project uses:

  ```
  .lathe/lathe-mcp-launcher.sh
  ```

  On Windows it is `.lathe\lathe-mcp-launcher.cmd`, started through `cmd /d /s /c`.
  Re-created by every build, so it always matches the project's pinned server and carries no absolute,
  per-machine path (a committed registration stays portable across machines of the same OS family).

- **User-global** — a machine-wide copy of the newest installed server version's launcher:

  ```
  ~/.cache/lathe/latest/lathe-mcp-launcher.sh
  ```

  (`lathe-mcp-launcher.cmd` on Windows.) Use this for a single, user-scoped registration that works across all your projects (a relative
  per-project path does not resolve reliably at user scope). It runs whatever server version you most
  recently installed — fine when your repos track one version; prefer the per-project launcher when
  they are pinned to different Lathe versions.

Both locate the reactor the same way — by walking up from the server's working directory to the
nearest `.lathe/` — so either one must be launched with your project as its cwd.

## Register with Claude Code

From inside your project, point Claude Code at the launcher:

```bash
cd your-project
claude mcp add --transport stdio lathe -- .lathe/lathe-mcp-launcher.sh
```

- **Run it from the project root.** The server finds its reactor by walking up from its working
  directory to the nearest `.lathe/`, so the client must launch it with the project as its cwd — which
  is also what resolves the relative launcher path.
- **Scope.** The default scope is this project, for you only. Add `--scope project` to write a shared
  `.mcp.json` you can commit so teammates get the same setup — the relative path keeps it portable.
- **One registration for every project** — register the user-global launcher once at user scope:

  ```bash
  claude mcp add --scope user --transport stdio lathe -- ~/.cache/lathe/latest/lathe-mcp-launcher.sh
  ```

  It resolves in whatever project you launch `claude` from (the server still finds that project's
  reactor via its cwd). It runs the newest installed server rather than each project's pinned version —
  see the launcher note above.
- **Verify** with `claude mcp list`, or run `/mcp` inside a session — you should see `lathe` and its
  tools.

## Other MCP clients

Lathe speaks standard stdio MCP, so any MCP-capable agent (for example OpenAI Codex CLI or Gemini
CLI) can use it. Two facts are all a client needs:

- the command is `.lathe/lathe-mcp-launcher.sh` (relative to the project root), or
  `~/.cache/lathe/latest/lathe-mcp-launcher.sh` for one config shared across projects, and
- it must run with your project as its working directory (that is how Lathe locates the reactor, and
  what resolves the relative launcher path).

Consult that client's own MCP-server configuration docs for the exact config file and syntax; only
the Claude Code setup above has been validated.

## The tools

Every located result is one line, `path:line: code`, as grep prints it: the path is relative to the
workspace, and a result outside the reactor is tagged `[EXTERNAL]` (a dependency or the JDK, shown by
its URI) or `[GENERATED]`.
The structured result carries the same, with the full `uri` and `origin`.

| Tool | Input | Use it for |
|------|-------|-----------|
| `workspace_summary` | — | every module, the reactor modules it depends on (all on its classpath, transitive ones included with Maven), and whether its test launch is captured, plus the sync command; call it first in an unfamiliar project |
| `get_diagnostics` | `file` | compiler errors and warnings, plus unused-code hints, for one file, on the real classpath — no build; says so when no module compiles the file |
| `get_definition` | `file, line, column` | resolve a symbol to its definition, including into dependencies, the JDK, and generated sources |
| `find_references` | `file, line, column` | every real use of a symbol across the reactor — resolves overloads/inheritance, unlike `grep` |
| `find_implementations` | `file, line, column` | the implementers of an interface, or the overrides of a method |
| `call_hierarchy` | `file, line, column, direction` (optional) | callers (`incoming`, the default) or callees (`outgoing`) of a method, across modules |
| `search_symbols` | `query` | find a type by name prefix, simple or qualified (`java.util.Map.Entry`, `util.Duration`), across the reactor, dependencies, and JDK; reactor types also match by CamelHumps (`ASF` finds `AbstractServerFactory`) |
| `outline` | `file` | a file's types, fields, constructors and methods in source order, indented by nesting, each with its line and declaration — the structure without reading the file |
| `describe_symbol` | `file, line, column` | a symbol's rendered signature, type, and javadoc, without opening the file |
| `rename_symbol` | `file, line, column, newName` | rename a symbol across the whole reactor and **apply the edits to disk** |
| `run_test` | `file, scope, method` | replay a test method / class / package from captured bytecode — no reactor build |
| `analyze_change` | `file, line, column` | before an edit: a symbol's override family, production vs test reference counts, the modules that use it, and the relevant tests |
| `format_file` | `file` | format a file in place with the formatter the project pins (its Spotless step), so edits pass the build's formatting check; answers with the lines it rewrote |
| `fix_imports` | `file` | add the imports a file is missing — each unresolved type with exactly one match, plus static imports the file's scope already uses elsewhere; the ambiguous ones are listed with their candidates for you to pick, and names with no match are listed too |
| `verify_change` | `files` (optional) | after edits: recompile the changed files and their callers, and report errors first, one line each (callers: errors only) |

The file tools take an existing `.java` file; any other path answers with an error.
`format_file` answers with an error, not "already formatted", when the project pins no formatter or
its jars are not resolved yet.
Positions are 1-based and must be on a name: a position on a keyword, on whitespace or past the end of
a line answers with an error that shows the line, rather than with the enclosing method or class.
`find_references`, `find_implementations`, `call_hierarchy`, and
`search_symbols` accept an optional `maxResults` (default 50); `run_test` takes an optional `scope`
(`class` — default — / `method` / `package`).

Each tool declares the MCP annotations that say whether it writes:
the query tools (`outline` and `workspace_summary` included) and `verify_change` are `readOnlyHint`, `rename_symbol` is `destructiveHint`, and
`run_test` is neither (it runs code but writes no source), and `format_file` and `fix_imports` are
neither but `idempotentHint` (running them twice changes nothing more).
What a client does with them is up to the client.

The server also sends routing guidance at connection time (which tool fits which task), so a client
that reads server instructions steers itself.

## Freshness — what the results reflect

Because the protocol is stateless, every tool reads the target file **from disk** at call time and
compiles it against the captured `.lathe/` classpath. So `get_diagnostics` is accurate for *this*
file after your edit, with no Maven:

- **Single-file** correctness (the file you just edited) is live — in-process `javac`, no Maven.
- **Other files** you change out of band (any module) are reconciled **in-process, automatically**:
  every tool call first runs a write pass that recompiles externally changed sources into the mirror in
  dependency order, so cross-module results — references and renames included — see them without
  Maven. Deletions are cleaned up too.
- **Maven is still needed** only for POM / dependency / module-structure changes and for reactor-bound
  code generation (annotation processors, non-javac generated sources) — Lathe does not fake those.

Every tool first compiles the files you changed, however many, so its answer reflects your edits.
What it still cannot vouch for comes as a note at the end of the result:

- **`Not compiled:`** lists files whose code does not compile, each with its first error, e.g.
  `Not compiled: app/src/main/java/Foo.java:12: cannot find symbol`.
  Fix the code; no sync helps. Until then, other modules see the file's last good classes. The note clears on the next query after the fix.
- **`Sync required:`** names what only a sync fixes, with the command to run: changed build files, or a `.lathe/` written by another Lathe version.
- **`Not checked:`** names a file no module compiles, so nothing about it was checked.
  For a file of a module the last build compiled, it gives the sync that adds it; for one outside them
  (a module the build's profiles or JDK leave out) it says Lathe cannot check it, since no sync would.
  `get_diagnostics` then answers "Foo.java was not checked." rather than reporting no errors.
- **`A build is running`** means the answer reflects Lathe's classes from before that build; re-query once it ends.

`rename_symbol` is the one tool that writes: it applies javac-computed edits to disk and **refuses to
touch a file outside the reactor**.
It also refuses a symbol declared outside the project (JDK or a dependency) or in generated source,
and a method overriding one, since renaming only its uses would break them; the error says which.
It refuses a method one of whose overrides also overrides an unrelated method, which the rename would
leave behind.
It refuses a new name the owner already declares or inherits, a sibling type already has, or a variable in scope already uses.
Renaming a public top-level type also renames its file.
The changed files are compiled into the mirror before the tool returns, however many there are, so follow-up queries see the new names.

### Verifying a change

`verify_change` with no arguments checks what changed since the last check: the files changed on disk
plus the files Lathe's own tools wrote (a rename), each with its callers in its own and in dependent
modules.
Pass `files` to check exactly those instead.
It leads with the error count ("No errors — …" or "N error(s)") and lists each diagnostic on one
grep-style line, errors first: `path:line:column: severity: message`.
It reports errors and warnings of the changed files, but only errors of their callers, whose warnings
were there before the change.
Unused-code hints are left out (`get_diagnostics` on one file still shows them).
When the change reaches other modules, it names them (up to ten in the text, all of them in the
structured `affectedModules`) and gives the sync command that builds them, e.g.
`.lathe/lathe-sync.sh core`.
With no errors their callers have already compiled, so it says that build is optional.
If an in-process check cannot be trusted (a build-file change waiting for a sync, or a running build),
it says so and gives that command instead.

## Usage logging

Every tool call emits one line to the server's stderr and to a per-session log file,
`~/.cache/lathe/logs/mcp-<yyyyMMdd-HHmmss>-<pid>.log`:

```
[tool] find_references file=/…/Foo.java line=88 column=20 142ms ok
[tool] search_symbols query=ServerFactory maxResults=50 12ms ok
```

This records which tool ran, on what, how long it took, and whether it succeeded — enough to tally
tool usage over a session. It is always on (no `LATHE_DEBUG` needed). Read the log file: a client
such as Claude Code keeps only the server's startup stderr, whose `[startup] … log=<path>` line names
the file. Each line starts with an ISO-8601 timestamp and its zone offset
(`2026-10-24T16:21:40.134+0200`), so it lines up with a client's UTC logs.
A starting server keeps the newest 100 session logs and deletes older ones, with the lock files that
sessions killed outright leave behind.
Set `LATHE_DEBUG=1` for finer detail.

## Troubleshooting

- **Every tool answers "Lathe found no `.lathe/`"** — the agent was started outside a Lathe-enabled
  build: start it at the reactor root, or run the first build there (`mvn clean test -Dlathe.capture.only=true`); see
  the prerequisite above. The server stays up, so the agent sees this reason rather than a server that
  failed to connect; restart the agent's session after the build.
- **`run_test` says the build's test filter leaves a class out** — it is an integration test (Failsafe)
  or another class the build's test run excludes; Lathe cannot capture or run it, so run it with the
  build.
- **Launcher not found** — the launcher is written by `lathe:sync`, so it appears only after a build;
  confirm the Lathe extension is registered (see [installation.md](installation.md)).
- **Tools resolve nothing / results look stale** — check the result's notes: fix the code for
  `Not compiled:`, run the command a `Sync required:` or `Not checked:` note names; a cold index also needs a moment to
  warm on the first query.
- **The server uses a lot of memory** — each agent session runs its own server, and with no heap limit
  each may grow toward a quarter of the machine's memory on a large reactor; cap it with
  `LATHE_JVM_OPTS` (see [tuning the server JVM](installation.md#tuning-the-server-jvm-lathe_jvm_opts)).
- **Sync fails after upgrading Lathe, or a "build is running" note never clears** — an mvnd daemon
  keeps the Lathe extension it first loaded; run `mvnd --stop` after installing a new version.
