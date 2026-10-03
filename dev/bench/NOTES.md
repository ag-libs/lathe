# Harness wiring notes (M1 prep)

Operational findings for building `run.py`/`score.py`. Not pre-registration; implementation detail.

## Confirmed headless Claude Code invocation (CLI 2.1.193)

Single variable between arms = MCP presence. Everything else identical.

**Baseline** (grep-only, MCP guaranteed off):

```
claude -p "<problem_statement>" \
  --output-format stream-json --verbose \
  --permission-mode bypassPermissions \
  --model <pinned> \
  --strict-mcp-config \
  --max-budget-usd <cap>
```

`--strict-mcp-config` with **no** `--mcp-config` → zero MCP servers (ignores the user's own
configured servers). This is what makes the baseline clean and reproducible.

**Treatment** (same + lathe):

```
claude -p "<problem_statement>" \
  --output-format stream-json --verbose \
  --permission-mode bypassPermissions \
  --model <pinned> \
  --strict-mcp-config \
  --mcp-config '{"mcpServers":{"lathe":{"command":"<abs>/lathe-mcp-launcher.sh"}}}' \
  --max-budget-usd <cap>
```

- `--permission-mode bypassPermissions` → fully non-interactive; both arms keep the identical
  built-in toolset (Read/Grep/Glob/Bash/Edit/Write), so the only delta is the lathe MCP server.
- The lathe MCP resolves its workspace from **cwd**, so `run.py` must spawn `claude` with
  `cwd = <task worktree root>` (must contain a valid `.lathe/`).
- `--model` pinned per run for reproducibility (record the exact id in each result row).

## Metrics extraction

- `--output-format json` (single result) carries: `usage{input_tokens, output_tokens,
  cache_creation_input_tokens, cache_read_input_tokens, …}`, `total_cost_usd`, `num_turns`,
  `duration_ms`, `result`. → **primary tokens-to-outcome + cost + wall-time**.
- `--output-format stream-json --verbose` additionally streams every assistant message with
  `tool_use` content blocks (`name`, `input`). → **tool-call-mix secondary** (grep/read/bash vs
  `mcp__lathe__*`). `run.py` parses the stream and keeps the final `result` event for usage.

## OPEN RISK — `.lathe/` is gitignored, so a fresh `git worktree` has none

`.lathe/` is build output (gitignored). A fresh `git worktree add` contains only tracked files →
**no `.lathe/` → the MCP server refuses to run** (and the single-file compile has no captured
classpath). The pre-registration protocol step 1 ("git worktree add a clean tree") must be amended
with how `.lathe/` is provisioned. Candidate resolutions:

1. **Copy `.lathe/` into the worktree** after creation. Cheapest, but `.lathe/` manifests may hold
   **absolute paths** into the original tree's `target/` — diagnostics would compile against the
   original tree's classpath, not the worktree's. Fine for single-file nav/diagnostics; wrong for
   cross-module freshness.
2. **Run a capture build in the worktree** (`mvn … -Dlathe.capture.only=true`). Correct and
   self-contained, but adds a full build per instance (the real onboarding cost; one-time per task,
   not charged to agent metrics).
3. **Full reactor copy incl. `target/` + `.lathe/`** (rsync), run in place. Self-contained, heavy
   disk, only correct if `.lathe/` uses relative paths.

**RESOLVED → option (2).** Checked the live dropwizard `.lathe/`: **136 files hold absolute
`$HOME`-rooted paths**, and `.lathe/workspace.json` pins the workspace root at the *original*
tree (`…/lathe/../dropwizard`). So option (1) (naive copy) is wrong — a copied `.lathe/` compiles
against the original tree and resolves its root away from the worktree. Option (3) inherits the same
absolute-path problem.

Decision: **`run.py` provisions `.lathe/` by running a capture build inside each task worktree**
(`mvn … -Dlathe.capture.only=true`), so the mirror's paths are native to that worktree. Uniform
across synthetic-on-HEAD and historical instances (no special-case fast path), correct for
cross-module edits, and it is precisely the documented onboarding cost — charged to image/setup, not
to the agent's per-run metrics. Verify the exact capture command + build time in M1 before scaling.

Per-task worktrees are `git worktree add` on the **dropwizard/helidon** repos (not this lathe repo).

### VALIDATED `.lathe/` provisioning recipe (2026-10-03, dropwizard)

Confirmed end to end: a fresh worktree capture that the snapshot MCP loads (36 module configs, ~24 s).
`run.py` must, for the **treatment** arm only:

1. **Copy the untracked extension wiring** into the worktree:
   `cp <ref-repo>/.mvn/extensions.xml <worktree>/.mvn/extensions.xml`.
   `git worktree` does NOT copy it — `.mvn/extensions.xml` (which wires `lathe-maven-extension`
   0.1.12) is **untracked** in the dropwizard checkout. Without it, `process-test-classes` never
   triggers `lathe:sync` and no `.lathe/` is produced.
2. **Full-reactor `clean` capture** (NOT `-pl`): `lathe` deliberately skips `workspace.json` on a
   partial (`-pl`) reactor (`SyncCoordinator` logs "partial reactor (-pl) — skipping workspace.json
   write"), and the MCP needs `workspace.json` at the root. And **`clean` is mandatory**: without it an
   incremental build skips compilation, the lathe compiler's class-copy never fires, and the mirror's
   `classes/` is empty → the symbol index is empty → `search_symbols`/`find_references` return nothing
   (while `get_diagnostics` still works, since it compiles on demand). So:
   ```
   JAVA_HOME=/opt/amazon-corretto-25.0.0.36.2-linux-x64 \
   mvn -q clean process-test-classes \
       -Dspotless.check.skip=true -Denforcer.skip=true -Dpgpverify.skip=true
   ```
   - `-Dpgpverify.skip=true` is REQUIRED: dropwizard's `pgpverify-maven-plugin` rejects the unsigned
     `io.github.ag-libs:lathe-junit` artifact the extension pulls in.
   - `-Dspotless.check.skip`/`-Denforcer.skip` keep the capture from failing on unrelated hygiene gates.
3. Verify `test -f <worktree>/.lathe/workspace.json` before launching the treatment agent; a missing
   file means the capture failed and the task run must be marked errored, not silently grep-only.

The **baseline** arm needs none of this (no MCP) — it runs in a plain worktree.

## M1 dry-run findings (dw-01, 2026-10-03)

First end-to-end paired run of `run.py` + `score.py` on dw-01 (opus). Pipeline proven; results below.

- **Harness bug found & fixed:** the user's `~/.claude/settings.json` sets
  `disableBypassPermissionsMode: disable`, so `--permission-mode bypassPermissions` is overridden and
  all Edits are denied (first baseline run produced an empty patch; the agent asked for write
  permission). Fix: `--permission-mode acceptEdits` + explicit `--allowedTools` whitelist (identical
  across arms; treatment adds `mcp__lathe`). Re-ran clean.
- **Baseline RESOLVED dw-01** — 490 s, $0.78, 22 turns, 13 grep/read/bash, 0 missed sites. It found
  all four sites *including* the grep-invisible anonymous `ViewRenderer` in `ViewBundleTest` via the
  compiler. Confirms the pre-registered prediction: compiler-enforced tasks do NOT discriminate on
  missed-sites.
- **Treatment ran but used Lathe 0 times** — 161 s, $0.63, 22 turns, 12 grep/read/bash,
  `lathe_tool_calls=0`, also RESOLVED. The cheaper/faster numbers are **variance, not Lathe value**:
  the agent never invoked the MCP. MCP connection is fine (a forced probe successfully called
  `mcp__lathe__search_symbols`); the `init` snapshot showing `status: pending` is just pre-connection.
- **Adoption is the real first result:** unprompted, the agent does not reach for Lathe on a task it
  can close with grep + the compiler. This is the Level-1 adoption signal the pre-registration names —
  a tool-description/routing problem, and exactly why dw-01 is a weak discriminator and dw-02
  (overload rename, silently-wrong) matters.
- **RESOLVED — empty symbol index:** `search_symbols` returned 0 in the captured worktree not from
  warmup but because the capture omitted `clean`, so compilation was skipped and the mirror's `classes/`
  was empty. With `mvn clean process-test-classes`, the mirror populates and `search_symbols` returns
  the expected symbols with snippets. `run.py` now uses `clean`. (Verified against the full
  `../dropwizard/.lathe`, where the tools worked all along.)

### Open methodology question raised by the dry run

Do we measure **natural adoption** (report that it's low → a tool-description fix) or **value
conditional on use** (instruct the agent that Lathe tools are available, so we measure the tool, not
the agent's habit)? These answer different questions; the headline run should state which, up front.
