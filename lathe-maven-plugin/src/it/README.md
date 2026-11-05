# Invoker fixtures

Real Maven projects the invoker builds with Lathe enabled, each checked by JUnit tests inside the fixture (or by the build's own result).
There are no Groovy or BeanShell verify scripts: a fixture proves what it tests through its design, its `invoker.properties`, or its tests.

| Fixture | What it covers |
|---|---|
| `setup-support` | Installs `lathe-it-support`, the jar the other fixtures' tests share (`LspSession`, `McpSession`, `Os`, `Await`); runs first, alone, and with Lathe enabled, so its sync fills the shared Lathe cache (JDK sources and type index) before the fixtures that build in parallel. |
| `capture-only-suite` | A capture-only sync on a JUnit `@Suite`: the suite discovers its tests and none of them runs. |
| `formatters` | One project synced once per Spotless formatter, palantir then Eclipse; the second build also checks the first one's `jvm.args` is gone. |
| `lombok` | A Lombok project as its users build it, checked against the real LSP and MCP servers (`LombokTest`); JDK 17 to 27 only. |
| `multi-module` | The main fixture, a reactor of six modules (below), built twice: a full `test`, then a scoped sync with one `-pl` per module. |
| `old-compiler` | A compiler plugin older than Lathe supports: the requirements check fails the build. |

`multi-module` holds `core` and `app` (a plain dependency chain), `health` (a custom output directory), `jpms` (a named module), `shapes` (test shapes the editor replays, some failing on purpose) and `verify`.
`verify` runs the checks: `MultiModuleTest` reads the sync output, `LspSmokeTest` drives the real language server and `McpSmokeTest` the MCP server.
Its first build seeds stale captures from `stale-captures/` (profile `seed-stale-captures`), which the sync must prune.

## Running

```bash
mvn verify -pl '!lathe-neovim,!lathe-vscode' -Dmaven.test.skip.exec=true    # every fixture, building the Lathe jars they need
mvn verify -pl '!lathe-neovim,!lathe-vscode' -Dmaven.test.skip.exec=true -Dinvoker.test=setup-support,multi-module    # one fixture
```

Each build's log is `lathe-maven-plugin/target/it/<fixture>/build.log`.
The fixtures resolve into `target/local-repo`, served from `~/.m2` through `settings.xml`, and the servers they start install into the cache `target/it home/.cache/lathe` (a path with a space, on purpose).

## Conventions

- A wait polls through `Await.until` with a timeout, and the invoker kills a fixture build after 600 s, so a hang fails as that fixture.
- `capture-only-suite`, `multi-module/shapes` and `multi-module/jpms` pin JUnit 5 on purpose; the other fixtures use the build's JUnit 6.
- A fixture pins its plugin versions as a real project on Maven 3.6 must, and enables Lathe the way a user does (`.mvn/extensions.xml`), so each one stays a project as users build it.
