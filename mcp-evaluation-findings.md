# Lathe MCP — Evaluation Findings & Pre-Ablation Readiness

**Date:** 2026-09-30
**Status:** In progress — paused before the efficiency measurement. Next: some code changes
(see [Pre-flight](#pre-flight-code-changes-before-the-ablation)), then run the MCP-vs-grep ablation.
**Companion docs:** [`lsp-testing-report.md`](lsp-testing-report.md) (LSP round + the generated-sources
bug) · [`docs/planned/lathe-mcp-value-benchmark.md`](docs/planned/lathe-mcp-value-benchmark.md) (the
ablation design this feeds into).

## Goal

Establish whether the `lathe-mcp-server` is ready to be measured against a grep/sed-only baseline on
moat-shaped, cross-module tasks (the SWE-bench-style ablation in the benchmark design), and fix any
blockers found while exercising it on two real reactors.

## Setup

- **Workspaces:** Dropwizard (`~/git/dropwizard`, ~37 modules, Corretto 25) and Helidon
  (`~/git/helidon`, ~330 modules, Corretto 26, captured with `-Dversion.java=26`).
- **Server:** `0.1.0-SNAPSHOT` (working-tree build with this session's fixes), launcher
  `~/.cache/lathe/servers/0.1.0-SNAPSHOT/lathe-mcp-launcher.sh`.
- **Driver:** `dev/mcp.py` (stdio JSON-RPC; `LATHE_MCP_LAUNCHER=… python3 dev/mcp.py <repo>`).
  **MCP tools use 1-based line/column** (the LSP probe `dev/explore.py` is 0-based).
- **Tools exposed (11):** `get_diagnostics`, `get_definition`, `find_references`,
  `find_implementations`, `call_hierarchy`, `search_symbols`, `describe_symbol`, `rename_symbol`,
  `run_test`, `analyze_change`, `verify_change`.

## What we exercised

### Robustness sweep — 66/66 calls OK, zero failures

Every position tool (`get_definition`, `find_references`, `find_implementations`, `call_hierarchy`,
`describe_symbol`, `analyze_change`) across target kinds — **type, method, setter, field, interface,
generated interface, generated builder, default method** — on both repos, plus `search_symbols`
(5 queries × 2 repos) and `verify_change`.

- No crashes. `analyze_change` (which had been crashing — see fixes) now works on every kind
  including generated types.
- `call_hierarchy` / `find_implementations` return a graceful "none" on non-applicable targets
  (fields, setters) rather than erroring.

### Representative exchanges (model I/O)

Read side — **cross-module SPI** (Dropwizard `ConnectorFactory`, mirrors the HTTP/2 + unix-socket
connector PRs):

- `find_implementations` → 5 implementers across `dropwizard-jetty` / `-http2` / `-unix-socket`,
  with `[REACTOR]` tags and source context.
- `analyze_change` on `ConnectorFactory.build(...)` → signature + javadoc, then:
  `references: 9 production, 4 test`, `affected modules: core, http2, jetty, unix-socket`,
  `relevant tests: HttpConnectorFactoryTest, HttpsConnectorFactoryTest`, `override family: 5`.

Read side — **generated-source navigation** (Helidon `WebServerConfig`, mirrors "Add server config
option" PRs). This is the moat that the mirror bug had broken:

- `search_symbols WebServerConfig` → the `@Generated` interface is indexed.
- `get_definition` → resolves into `.lathe/…/generated-sources/WebServerConfig.java` tagged
  **`[GENERATED]`** (a grep-only agent cannot reach this — it is annotation-processor output, not in
  `src/`).
- `describe_symbol` → the Blueprint-derived contract/javadoc.

Write side — the **mutation loop** (Dropwizard `DataSourceFactory.setUrl → setJdbcUrl`, reverted
after):

- `rename_symbol` → 9 edits across 8 files in 4 modules, applied to disk; correctly **excluded** the
  same-named `DAOTest.Builder.setUrl` and Tomcat `poolConfig.setUrl` (overload precision). Emitted a
  **Stale** warning that the edit made compiled classes lag the source.
- `verify_change` → 10 cross-module diagnostics ("cannot find symbol `setJdbcUrl`"). **This is the
  freshness boundary, not a bug** — in-process verify is single-file fresh but sees the *captured*
  (not-yet-rebuilt) classes of the edited declaring module; the tool told the agent to run
  `mvn process-test-classes`. It washes out in the ablation oracle (both arms run real `mvn`).

## Timings (server-side `[tool] …Xms`; client-side within ~5 ms)

| Tool | Repo | Time | Note |
|---|---|---|---|
| `describe_symbol` | Helidon | **121 ms** | warm |
| `get_definition` | Helidon | **221 ms** | warm |
| `find_implementations` | Dropwizard | 1385 ms | |
| `rename_symbol` | Dropwizard | 1805 ms | 9 edits / 4 modules |
| `search_symbols` | Helidon | 2660 ms | first call warms the index |
| `verify_change` | Dropwizard | 2935 ms | recompile change set |
| `analyze_change` | Dropwizard | 4411 ms | heaviest — ref sweep + attribute |

Steady-state navigation is sub-250 ms; impact/analysis tools cost more because they compile. First
call of a given kind includes index/compile warm-up (2–13 s).

## Bugs found and fixed this session (on `main`, unreleased)

1. **Generated-sources mirror clobbered across scopes** (HIGH). `LatheCompiler.syncOutput` mirrored
   the main and test compile's annotation-processor output to the same `.lathe/<mod>/generated-sources`,
   so the later test compile wiped/replaced the main compile's generated sources → 71/93 Helidon
   modules had empty mirrors → broken `get_definition`/`search_symbols` on generated `*Config` types.
   Fixed by scope-splitting the mirror (`generated-sources` / `generated-test-sources`).
   Commits `336b4686`, `02a31327`, `20931f27`. Verified: empty mirrors 71 → 2. Full write-up in
   `lsp-testing-report.md`.
2. **`analyze_change` / `find_references` crash on Helidon** (HIGH, introduced by the first fix).
   Making the test scope also search the *main* generated mirror created double-ownership of
   generated files → `ModuleSourceCompiler.writeTempFile` threw `no source root for <file>`
   (surfaced as `analyze_change` "[engine] request failed"). Fixed by keeping each generated mirror
   owned by a single scope. Commit `004e78e4`. Verified: `find_references`=151 on `WebServerConfig`,
   `analyze_change` OK, 0 engine errors.

Residual (separate, pre-existing, **not** fixed): 2 modules (`common/common`, `data/codegen/parser`)
whose generated Java lives under non-annotation roots (build-helper `templates/`, ANTLR `antlr4/`)
are still not mirrored — Lathe mirrors only the compiler's `getGeneratedSourcesDirectory()`.

## Readiness verdict

**No showstoppers.** All 11 tools are robust across target kinds on both reactors, both moat axes
(cross-module structure + generated-source navigation) work through MCP, and the two bugs found while
exercising it are fixed and merged.

## Pre-flight code changes (before the ablation)

Ordered by impact on the measurement's validity.

1. **Freshness protocol for the mutation arm — the one real methodological risk.**
   `verify_change` is single-file fresh but reports stale cross-module errors after a declaring module
   is edited. It washes out in scoring (real-`mvn` oracle) but can induce the *distrust tax* in-loop
   (agent chasing false errors), which would understate MCP's efficiency. Decide between:
   - **(cheap)** state in the tool descriptions / system prompt that `verify_change` is single-file
     fresh and cross-module correctness needs `mvn process-test-classes`; or
   - **(feature)** have `rename_symbol` / `verify_change` recompile edited modules into `.lathe` first
     (relates to the in-process workspace-sync work).
2. **Release + pin + parity.** The fixes are on `main` but **unpushed/unreleased**; Helidon is pinned
   to `0.1.0-SNAPSHOT` (working tree) and Dropwizard was captured on `0.1.12` (pre-fix). Cut a release
   (e.g. 0.1.13) with the fix, pin it in the treatment image, and re-capture Dropwizard on it (parity).
3. **Warm-up handling.** Measure steady-state; footnote the one-time cold start / `.lathe` onboarding
   the same way the benchmark doc footnotes onboarding cost.
4. **Residual generated roots (optional).** Mirror plugin-added generated-source roots under
   `target/generated-sources` (build-helper `templates/`, ANTLR `antlr4/`), not just `…/annotations`.

Minor: `find_references` defaults to `maxResults=50` (display cap) — completeness-sensitive tasks must
page or raise it. `rename_symbol` writes to disk (no dry-run) — correct for a git-diff prediction.

## Reproduction

```bash
export LATHE_MCP_LAUNCHER=~/.cache/lathe/servers/0.1.0-SNAPSHOT/lathe-mcp-launcher.sh
python3 dev/mcp.py ~/git/dropwizard            # handshake + tools/list
# call a tool via the McpClient in dev/mcp.py (1-based line/col); server logs [tool] …Xms on stderr
```

## Current state / caveats

- `main` is ahead of `origin/main` and **unpushed**; fixes not yet released.
- `helidon/.mvn/extensions.xml` currently pins `lathe-maven-extension 0.1.0-SNAPSHOT` (for the
  verification re-capture); Dropwizard pins `0.1.12`.
- Branch `fix/generated-sources-scope-split` still exists (fast-forwarded into `main`).
- Both workspaces' `.lathe/` reflect the fixed capture (Helidon) / original capture (Dropwizard).
