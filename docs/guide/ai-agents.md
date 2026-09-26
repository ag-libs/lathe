# Lathe for AI agents (MCP)

Lathe exposes its analysis to AI coding agents through an **MCP server** (`lathe-mcp-server`).
It is the same build-derived engine that powers the editor, offered as a curated set of tools over
the [Model Context Protocol](https://modelcontextprotocol.io) — so an agent gets javac-accurate,
cross-module code intelligence instead of guessing from `grep`.

The wedge is the operations text search gets wrong on a real reactor: compiler-truth diagnostics on
the exact build classpath, navigation that follows into dependencies and generated sources, finding
every real use (and every implementer/override) of a symbol, safe reactor-wide rename, and replaying
a test without a Maven build.

## Prerequisite: a populated `.lathe/`

The server reads from Lathe's build capture and **refuses to start without it**, exactly like the
editor. Register the Lathe Maven extension and run a build once at the reactor root — the quickest is:

```bash
mvn process-test-classes
```

That build does two things at once: it writes `.lathe/`, and its `lathe:sync` step installs the MCP
launcher (below). If the server reports a missing `.lathe/`, this is the fix.

## The launcher

There is no separate install. `lathe:sync` generates the launcher during the build above and installs
it to a stable, version-independent path:

```
~/.cache/lathe/current/lathe-mcp-launcher.sh
```

(`current` is a symlink Lathe keeps pointing at the installed server version, so the path stays valid
across upgrades.)

## Register with Claude Code

From inside your project, point Claude Code at the launcher:

```bash
cd your-project
claude mcp add --transport stdio lathe -- ~/.cache/lathe/current/lathe-mcp-launcher.sh
```

- **Run it from the project root.** The server finds its reactor by walking up from its working
  directory to the nearest `.lathe/`, so the client must launch it with the project as its cwd.
- **Scope.** The default scope is this project, for you only. Add `--scope project` to write a shared
  `.mcp.json` you can commit so teammates get the same setup.
- **Verify** with `claude mcp list`, or run `/mcp` inside a session — you should see `lathe` and its
  tools.

## Other MCP clients

Lathe speaks standard stdio MCP, so any MCP-capable agent (for example OpenAI Codex CLI or Gemini
CLI) can use it. Two facts are all a client needs:

- the command is `~/.cache/lathe/current/lathe-mcp-launcher.sh`, and
- it must run with your project as its working directory (that is how Lathe locates the reactor).

Consult that client's own MCP-server configuration docs for the exact config file and syntax. The
setups below have not been validated against every client, so their own documentation is the
authority.

## The tools

All results carry a source snippet and an `origin` (reactor / dependency / JDK / generated), never a
bare `file:line`.

| Tool | Input | Use it for |
|------|-------|-----------|
| `get_diagnostics` | `file` | compiler errors/warnings for one file, on the real classpath — no Maven |
| `get_definition` | `file, line, column` | resolve a symbol to its definition, including into dependencies, the JDK, and generated sources |
| `find_references` | `file, line, column` | every real use of a symbol across the reactor — resolves overloads/inheritance, unlike `grep` |
| `find_implementations` | `file, line, column` | the implementers of an interface, or the overrides of a method |
| `call_hierarchy` | `file, line, column, direction` | callers (`incoming`) or callees (`outgoing`) of a method, across modules |
| `search_symbols` | `query` | find a type by name (CamelHumps: `ASF` finds `AbstractServerFactory`) across the reactor, dependencies, and JDK |
| `describe_symbol` | `file, line, column` | a symbol's rendered signature, type, and javadoc, without opening the file |
| `rename_symbol` | `file, line, column, newName` | rename a symbol across the whole reactor and **apply the edits to disk** |
| `run_test` | `file, scope, method` | replay a test method / class / package from captured bytecode — no reactor build |

Positions are 1-based. `find_references`, `find_implementations`, and `call_hierarchy` accept an
optional `maxResults` (default 50); `run_test` takes an optional `scope`
(`class` — default — / `method` / `package`).

The server also sends routing guidance at connection time (which tool fits which task), so a client
that reads server instructions steers itself.

## Freshness — what the results reflect

Because the protocol is stateless, every tool reads the target file **from disk** at call time and
compiles it against the captured `.lathe/` classpath. So `get_diagnostics` is accurate for *this*
file after your edit, with no Maven:

- **Single-file** correctness (the file you just edited) is live — in-process `javac`, no Maven.
- **Other files** you change out of band (any module) are reconciled **in-process, automatically**:
  Lathe watches the workspace and FULL-recompiles externally changed sources into the mirror in
  dependency order (a short debounce after the edit settles), so cross-module results refresh without
  Maven. Deletions are cleaned up too.
- **Maven is still needed** only for POM / dependency / module-structure changes and for reactor-bound
  code generation (annotation processors, non-javac generated sources) — Lathe does not fake those.

While a referenced module's source is newer than its compiled classes (e.g. in the moment before the
reconcile catches up), results append a **`Stale:`** note listing those modules; it clears once the
in-process recompile lands, or you can force it with `mvn process-test-classes`. `rename_symbol`
force-refreshes so its own result is current.

`rename_symbol` is the one tool that writes: it applies javac-computed edits to disk and **refuses to
touch a file outside the reactor**.

## Usage logging

Every tool call emits one line to the server's stderr:

```
[tool] find_references file=/…/Foo.java line=88 column=20 142ms ok
[tool] search_symbols query=ServerFactory maxResults=50 12ms ok
```

This records which tool ran, on what, how long it took, and whether it succeeded — enough to tally
tool usage over a session. It is always on (no `LATHE_DEBUG` needed); MCP clients typically capture a
server's stderr in their own logs. Set `LATHE_DEBUG=1` for finer detail.

## Troubleshooting

- **"No `.lathe/`" / server won't start** — run a build at the reactor root
  (`mvn process-test-classes`); see the prerequisite above.
- **Launcher not found** — the launcher is written by `lathe:sync`, so it appears only after a build;
  confirm the Lathe extension is registered (see [installation.md](installation.md)).
- **Tools resolve nothing / results look stale** — check for a `Stale:` note and re-run the build; a
  cold index also needs a moment to warm on the first query.
