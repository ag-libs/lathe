# Lathe — Debug Support (DAP over JDWP)

Design and phased development plan for debugging Lathe replay launches.
It expands [lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §4.3 (debug is one JDWP argument),
§10.5 (`debug.DebugAdapter`), and §12.9 (initial debug attach slice) into the full "ultimate debug"
feature set, and records the architecture decisions taken in design discussion.

This is a **planning document**: no code exists yet.
The runtime flow, the process model, and the phase boundaries are the authoritative reference for the
implementation slices that follow.

---

## 1. Scope

**In:** breakpoint debugging of Lathe replay launches — tests first, then `main` — driven from the
editor over the Debug Adapter Protocol (DAP), using Microsoft's `java-debug` as the adapter.
Attach-only, Maven-free, on the bytecode Lathe already maintains in `.lathe/`.

**Out (documented limitations, §15):** `forkCount=0` (no replayable launch, so nothing to attach to),
non-JUnit-Platform providers (no capture ⇒ no template), and — until later phases — expression
evaluation and hot code replace.

**Ultimate feature target:** all standard DAP capabilities — line/conditional/logpoint/exception
breakpoints, stepping, call stack, scopes/variables, multi-module source lookup, expression
evaluation, hot code replace, `main` and run-config debug, remote attach, and a second (VS Code)
client — delivered in phases (§13).

---

## 2. Principles and invariants

Debug inherits the run/test invariants and adds three of its own.

- **Lathe never runs Maven.** Debug rides the same captured `test-launch.json` / derived
  `main-launch.json` templates as a normal replay; the only difference is one JVM flag.
- **Attach-only.** Lathe launches the replay JVM with a JDWP server agent and *suspends* it; the
  adapter then attaches. Lathe never asks the adapter to construct or launch a command line — Lathe
  already owns the exact replay argv, so the adapter needs no classpath/main-class resolution logic.
- **The debuggee is a fork.** Debugging requires a separate target JVM by construction (JDWP/JDI is
  cross-VM); this is the Surefire-default fork Lathe already replays. `forkCount=0` is not debuggable.
- **Minimum process footprint.** A debug session spawns exactly **one** process — the replay JVM —
  identical to a non-debug run (§6). The adapter is a thread inside `lathe-server`, not a process.
- **Fail-safe.** A debug failure (adapter error, attach timeout, incomplete image) refuses the session
  cleanly and never corrupts `test-launch.json`, the replay path, or a running editor.

---

## 3. Locked architecture decisions

| Decision | Choice | Rationale |
|---|---|---|
| DAP adapter library | **Microsoft `java-debug`**, embedding `com.microsoft.java.debug.core` only | The only Java DAP implementation; `core` is the reusable engine (§4). |
| Adapter hosting | **In-process in `lathe-server`**, one adapter thread per session | Minimum process count (§6); direct access to Lathe's analysis for source lookup / eval, avoiding an RPC channel back to the server. |
| Launch model | **Attach-only** — replay JVM launched `server=y,suspend=y`, adapter attaches over JDI | No adapter-side command construction; removes all attach-timing races (the JVM parks before `main`). |
| Adapter delivery | **Regular Maven dependency** of `lathe-server` (not a materialized jar) | In-process hosting means `core` is on the server module path; no cache jar, unlike `lathe-test-runner`. |
| Process budget | **1 spawned JVM per session** (= non-debug run); N sessions → N JVMs, adapter stays threads | §6. |
| Editor client | **`nvim-dap`** via a `server`-type adapter on a Lathe-supplied port | Neovim has no native DAP (through 0.12); nvim-dap is the de-facto client (§11). |

### 3.1 Naming — from `Replay*` to `Launch*`

The execution engine is generalized from test-only replay to **{test, main} × {run, debug}**. "Replay"
is accurate only for tests (a test launch replays a captured fork); a `main` launch is *derived*, not
replayed, and debug is a *mode* of either. The umbrella term is therefore **Launch** — the common
action of starting a JVM from a launch template (`test-launch.json` / `main-launch.json`) against
`.lathe/` bytecode.

| Current (test-only) | Generalized | Role |
|---|---|---|
| `ReplayTransform` | `LaunchPlan` (`forTest` / `forMain`) | pure template → argv |
| `ReplayLauncher` | `Launcher` | spawns the JVM |
| `ReplaySession` | `LaunchSession` | owns the process + result streams |
| `ReplayOutcome` | `LaunchOutcome` | result |

- **Source vs. mode:** `forTest`/`forMain` select the *source*; run vs. debug is a *mode* expressed as
  optional **`JdwpOptions`** on the launch request (present ⇒ suspend + attach). No parallel "mode" enum
  is added — `LaunchMode` already means `CLASSPATH`/`MODULE`.
- **Layer split:** the server `run` package stays the discovery/command layer (`Runnable`, `RunTarget`,
  `lathe.run.*`); **`launch`** is the execution engine. A discovered `Runnable` is launched into a
  `LaunchSession`.

Renaming the existing `Replay*` classes is a separate, approved refactor (it touches the current
run/test code), performed alongside Phase 1. This document uses the generalized names throughout.

---

## 4. What Lathe uses from the Microsoft library

`microsoft/java-debug` ships two modules:

- **`com.microsoft.java.debug.core`** — the DAP server engine: `ProtocolServer`, `DebugAdapter`, the
  request handlers, and the provider interfaces (`ISourceLookUpProvider`,
  `IVirtualMachineManagerProvider`, `IEvaluationProvider`, `IHotCodeReplaceProvider`,
  `ICompletionsProvider`, `IStackFrameManager`). **Lathe embeds this.**
- **`com.microsoft.java.debug.plugin`** — the Eclipse JDT / OSGi wrapper: `JavaDebugServer` (opens the
  DAP socket, returns a port) and `JdtProviderContextFactory` (JDT-backed providers), plus the
  `vscode.java.startDebugSession` delegate command. **Lathe does not use this** — it is the
  jdtls-specific glue, and Lathe replaces it.

The seam is `IProviderContext`:

```
new ProtocolServer(socketIn, socketOut, latheProviderContext).start();
// handlers call context.getProvider(ISourceLookUpProvider.class), etc.
```

jdtls supplies a JDT-backed context; **Lathe supplies its own context backed by the `.lathe/`
workspace model.** That provider context — not the DAP wire handling — is the substance of the
integration (§8).

---

## 5. Runtime flow — starting a debug test

Nothing but the MS `core` speaks DAP or JDWP; Lathe launches the JVM, opens the DAP socket, and hands
`core` a provider context plus the attach target.

```
1. Editor: user "debug" on a runnable (RunnableScanner already discovers these)
   nvim-dap (Lathe's setup_dap adapter, type='server')
     └─ start logic → LSP workspace/executeCommand  lathe.debug.start { selector, moduleRel }

2. lathe-server handles lathe.debug.start:
   a. LaunchTemplateReader.read → CompletenessGate.verify → LaunchPlan.forTest(...)   (as run)
   b. allocate free jdwpPort; LaunchPlan emits, ahead of the runner main:
        -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<jdwpPort>
   c. Launcher.launch(...) spawns the launched JVM (LaunchSession).
        suspend=y ⇒ the JVM opens the JDWP socket and BLOCKS before running the test
   d. open a DAP ServerSocket on dapPort; when the client connects, construct
        new ProtocolServer(in, out, LatheProviderContext).start()      ← MS core, on a session thread
   e. return { dapPort, jdwpPort } to the editor

3. nvim-dap connects to 127.0.0.1:<dapPort>
     → DAP initialize
     → DAP attach { hostName:127.0.0.1, port:<jdwpPort>, sourcePaths:[…], projectName:… }
       (Lathe's enrich_config fills these; the JVM is already listening from 2c)

4. MS core attach handler + IVirtualMachineManagerProvider:
     JDI SocketAttachingConnector.attach(127.0.0.1:<jdwpPort>)  → wraps a JDI VirtualMachine

5. nvim-dap: setBreakpoints → configurationDone
     core maps breakpoints to JDI EventRequests, then VirtualMachine.resume()
     → the suspended test JVM runs and stops at breakpoints
     JDI events → DAP stopped / stackTrace / scopes / variables back to nvim-dap

6. Test results still stream over Lathe's NDJSON sink from lathe-test-runner (independent of DAP).
   End: adapter detaches, JVM exits, LaunchSession is cleaned up; cancel via the session command.
```

`suspend=y` is what removes the races: the JVM parks before `main`, so the adapter attaches and sets
breakpoints at leisure, then resumes.

---

## 6. Process model

A minimal debug session is **one spawned process**, and it cannot be fewer.

| Component | Process? | New for debug? |
|---|---|---|
| Neovim + nvim-dap | already running | no |
| `lathe-server` (LSP) | already running | no |
| MS `ProtocolServer` (DAP↔JDI bridge) | **thread in `lathe-server`** | no |
| Launched JVM — debuggee (tests + `lathe-test-runner` + jdwp agent) | **1 process** | yes |

The `-agentlib:jdwp` agent is in-VM (not a process); JDI attach runs inside the adapter thread; Lathe
never spawns Maven. Debug therefore has the **same footprint as `lathe.run.test`** — it only adds a
JVM flag. Zero processes is impossible (there must be a target VM). Out-of-process adapter hosting
would only *add* a process and force an RPC channel for source lookup, so it is rejected.

Concurrency: N simultaneous sessions ⇒ N launched JVMs, each with its own `dapPort`/`jdwpPort` pair; the
adapter stays a set of threads in the single server.

---

## 7. Where JDWP ↔ DAP is bridged

Three sockets; the bridge lives in exactly one place.

| Endpoint | Created by | Protocol |
|---|---|---|
| JDWP listen socket (`jdwpPort`) | the **launched JVM** (`-agentlib:jdwp` agent), launched by `Launcher` | JDWP |
| DAP socket (`dapPort`) | **`lathe-server`**, hosting MS `ProtocolServer` | DAP |
| The bridge | **inside MS `core`** — attach handler → `IVirtualMachineManagerProvider` → JDI `SocketAttachingConnector` dials `jdwpPort`, then translates DAP↔JDI | JDWP ↔ DAP |

Lathe implements none of the translation; it provides the target address and the provider context.

---

## 8. Provider context — mandatory vs. deferred

`LatheProviderContext` returns Lathe implementations of the `core` provider interfaces. Not all are
needed at once.

| Provider | Phase | Role in Lathe |
|---|---|---|
| `IVirtualMachineManagerProvider` | 1 (mandatory) | Return `Bootstrap.virtualMachineManager()`; the attach handler uses its `SocketAttachingConnector`. Nearly trivial. |
| `ISourceLookUpProvider` | 1 (mandatory) | Map a JDI class reference + line ↔ a workspace source file; verify breakpoints. Backed by Lathe's source roots and `.lathe/` type index (§9). The substantive piece. |
| `IStackFrameManager` | 1 (mandatory) | Frame/variable id bookkeeping; typically the `core` default suffices. |
| `ICompletionsProvider` | 2 | REPL / debug-console completions. Optional; can return empty initially. |
| `IEvaluationProvider` | 4 | Expression evaluation / watches. **Hard** — java-debug's evaluator leans on the JDT expression compiler; a javac-based equivalent is its own sub-project. Deferred. |
| `IHotCodeReplaceProvider` | 0 (no-op) → 4 | Redefine classes from refreshed `.lathe/` bytecode on save. **Phase 0 finding:** the adapter's `initialize` handler subscribes to HCR events, so a **no-op** provider must be registered from Phase 0 (`LatheHotCodeReplaceProvider`, returning `Observable.never()`); the real redefinition impl is deferred to Phase 4. |

Minimal attach (Phase 1) needs the first three; a no-op HCR provider is already registered (Phase 0);
evaluation is the only provider still fully deferred.

---

## 9. Source lookup design

`ISourceLookUpProvider` is where Lathe's `.lathe/` knowledge earns its keep — jdtls gets this free from
the JDT project model; Lathe must derive it.

- **Class → source:** resolve a fully-qualified type (from a JDI location) to a source file under the
  module's source roots (recorded in `workspace.json` `resourceRoots`/source roots), including upstream
  reactor modules whose bytecode was rewritten to `.lathe/<dep-rel>` (§4.2 of the run/test/debug doc).
- **Source → breakpoint:** given a file + line, produce the type/line the adapter arms as a JDI request;
  reuse Lathe's existing javac-based analysis rather than any text scanning (CLAUDE.md rule).
- **No ad-hoc parsing:** type and position facts come from Lathe's analysis helpers and the type index,
  never from regex or brace matching.

Because the provider runs in-process, it calls Lathe's analysis directly — the decisive reason for
in-process hosting.

---

## 10. Server command surface

Sibling of the existing run commands (`lathe.run.test`, `lathe.run.cancel`, `lathe.runnables.list`).

- **`lathe.debug.test`** — args `{ moduleRel, selections, token }`; returns `{ dapPort, jdwpPort }`.
  Reads the test template, gates completeness, allocates ports, launches the suspended JVM, opens the
  DAP socket, and readies the in-process `ProtocolServer`.
- **`lathe.debug.main`** — args `{ moduleRel, mainClass, token }`; the main-class twin, reading
  `main-launch.json` instead. Siblings of `lathe.run.test` / `lathe.run.main`.
- **Cancellation / lifecycle** — reuse the existing session mechanism (`lathe.run.cancel` +
  `LaunchSession`), extended to also detach the adapter and close the DAP socket. A dedicated
  `lathe.debug.cancel` is added only if the reuse proves awkward.
- **Session events** — reuse the run event channel for transcript/results; DAP stop/continue/variables
  flow over the DAP socket directly, not over LSP.

The main-class variant (`lathe.debug.main`) is implemented in Phase 3, reading the derived
`main-launch.json` (slice 12.7).

---

## 11. Neovim integration

Neovim provides nothing native — **no built-in DAP through 0.12** (0.12 added `vim.pack` and native-LSP
features, not DAP). Requirements:

- **`nvim-dap`** as a declared dependency of Lathe's Neovim runtime (load order: dap before Lathe's dap
  module). Supported Neovim: **0.11.7 minimum, 0.12.x recommended**.
- A **`setup_dap()`-equivalent** in Lathe's shipped runtime (alongside `lua/lathe.lua`,
  `ftplugin/java.lua`) that registers a `type='server'` adapter whose *start* logic calls
  `workspace/executeCommand lathe.debug.start` and returns `host=127.0.0.1, port=<dapPort>`, with an
  `enrich_config` that fills the DAP `attach` args (`hostName`, `port=<jdwpPort>`, `sourcePaths`,
  `projectName`) — the same shape `nvim-jdtls` uses, minus jdtls.
- **Optional UI:** `nvim-dap-ui` / `nvim-dap-view` guidance; not required.

VS Code (a second DAP client) reuses the same in-process adapter and `lathe.debug.start`; it is Phase 4.

---

## 12. JPMS / dependency integration

`lathe-server` is a JPMS module (`module-info.java`). Embedding `com.microsoft.java.debug.core`
in-process pulls its closure (Gson, `commons-lang3`; JDI is `jdk.jdi`, provided by the JDK) onto the
server module graph.

Open concern to resolve in Phase 0: `core` and its deps are likely **not** modularized, so they enter as
automatic modules. Options: `requires` the automatic modules, or isolate the adapter behind a small
in-process boundary loaded via a dedicated classloader. This is the main integration risk and is the
Phase 0 GO/NO-GO.

**Phase 0 outcome (resolved):** the simple `requires`-automatic-modules path works — no classloader
isolation needed. `com.microsoft.java.debug.core:0.53.1` is a non-modular jar (no
`Automatic-Module-Name`), so it resolves as the automatic module `com.microsoft.java.debug.core`; its
closure is `commons-lang3`, `rxjava`, `reactive-streams`, `commons-io` — **it pulls neither lsp4j nor
gson**, so Lathe's lsp4j 1.0.0 / gson 2.14.0 are untouched (gson is read from Lathe's existing module).
`lathe-server/module-info` needs just `requires com.microsoft.java.debug.core;` and `requires
io.reactivex.rxjava2;` (the latter only because the no-op HCR provider returns an rxjava `Observable`);
the remaining transitive automatic modules are resolved as a group. The launcher requires no change —
`ServerInstaller` builds `--module-path` from `lathe-server`'s resolved runtime closure, so the new jars
are included automatically (§ run/test/debug launcher).

---

## 13. Phased development plan

Each phase is independently reviewable; commit prefixes follow the run/test/debug convention.

### Phase 0 — Foundations (no user-visible debug)

**Scope:** add `com.microsoft.java.debug.core` to `lathe-server` and resolve JPMS integration (§12);
scaffold `LatheProviderContext` (returning stubs), a DAP `ServerSocket` host, and the
`lathe.debug.start` command + Neovim adapter registration returning a dummy port.
**Verify:** a raw DAP `initialize` handshake over the socket; JPMS build clean. No attach yet.
**GO/NO-GO:** `core` runs in-process under JPMS.
**Commit:** `feat: embed java-debug core and scaffold dap host`.

### Phase 1 — Minimal attach: debug one test (slice 12.9)

**Scope:** `LaunchPlan.forTest` gains a debug option emitting the `-agentlib:jdwp` server/suspend
arg; `Launcher` allocates the JDWP port; implement `IVirtualMachineManagerProvider`,
`IStackFrameManager`, and a first `ISourceLookUpProvider` (single module); wire the full flow (§5);
Neovim `setup_dap` attach + resume.
**Features:** line breakpoints, step over/into/out, continue, pause, call stack, scopes/variables, stop.
**Verify:** unit tests for the debug argv + port handling; manual smoke — breakpoint hits in a captured
test, variables inspect, resume to completion; reuse `CompletenessGate` refusal path.
**GO/NO-GO:** a breakpoint in a captured `HelloTest` stops, inspects, and resumes green.
**Commit:** `feat: attach-debug replayed tests over jdwp`.

### Phase 2 — Full test-debug parity

**Scope:** conditional breakpoints, logpoints, hit counts, caught/uncaught exception breakpoints;
multi-module source lookup (breakpoints in upstream reactor deps → `.lathe/<dep-rel>` + their sources);
restart-frame; NDJSON result stream coexisting with a live session; `nvim-dap-ui` guidance.
**Verify:** server tests for source lookup across modules; smoke for each breakpoint kind.
**Commit:** `feat: full breakpoint and multi-module debug support`.

### Phase 3 — Main and run-config debug (gated)

**Scope:** debug `main` via `LaunchPlan.forMain` + `main-launch.json` (**depends on slice 12.7**);
run-config debug entries — `debug:true` with args/jvmArgs/env/cwd (**depends on slice 12.8**).
**Verify:** invoker fixtures debugging the modular `HelloMain` and a classpath `Main`; overlay tests.
**Commit:** `feat: debug main classes and named run configs`.

### Phase 4 — Advanced

**Scope (each its own series):**

- **Expression evaluation** (`IEvaluationProvider`) — the hard sub-project; likely an ecj/JDT eval
  dependency or a javac-based evaluator. `feat: evaluate expressions in the debugger`.
- **Hot code replace** (`IHotCodeReplaceProvider`) — redefine classes from refreshed `.lathe/` bytecode
  on save. `feat: hot code replace from lathe bytecode`.
- **Remote attach** — attach to a user-launched JVM by host:port (spawns 0 processes).
  `feat: attach to a running jvm`.
- **VS Code client** — reuse the in-process adapter and `lathe.debug.start`. `feat: vscode debug client`.
- **Hardening** — concurrent-session port management, adapter-crash/attach-timeout handling, a pinned
  java-debug version guard, CI matrix.

---

## 14. Interactions with pending run/test slices

- **Phases 0–2 are independent** and build on the shipped test-replay path (slice 12.9 territory) with
  no prerequisites.
- **Phase 3 is gated:** `main` debug needs slice **12.7** (main-launch metadata); run-config debug needs
  slice **12.8** (`.lathe-run.json` overlay).

Recommended sequence: **Phases 0–2 (debug tests) → 12.7 → 12.8 → Phase 3**. Alternative: land 12.7/12.8
first and deliver test+main+config debug together in one Phase-3 push. Phase 4 follows in any case.

---

## 15. Documented limitations

- **`forkCount=0`** — no target JVM to attach to; use a forked run.
- **Non-JUnit-Platform providers** — no capture template, so no debug (as for run).
- **Expression evaluation / hot code replace** — unavailable until Phase 4.
- **Filtered resources** — same currency caveat as replay (run/test/debug doc §6).
- **`main` / run-config debug** — unavailable until Phase 3 and its slice prerequisites land.

---

## 16. Open decisions

1. ~~**JPMS embedding of `java-debug.core`** (§12)~~ — **resolved (Phase 0):** plain `requires` of the
   automatic modules; no isolating classloader. See §12 outcome.
2. **Cancellation command** — reuse `lathe.run.cancel` vs. a dedicated `lathe.debug.cancel` (§10).
3. **Evaluation strategy** (Phase 4) — ecj/JDT eval dependency vs. a javac-based evaluator vs. leaving
   eval a documented limitation.
4. **java-debug version pin** and its compatibility guard (Phase 4 hardening).

---

## 17. Change inventory

| Module | Change |
|---|---|
| `lathe-core` | Rename `Replay*` → `Launch*` (§3.1); `LaunchPlan.forTest`/`forMain` gain an optional `JdwpOptions` argument; `LatheLayout` debug constants. |
| `lathe-server` | New `debug` package: DAP socket host, `LatheProviderContext`, `LatheSourceLookUpProvider`, VM-manager/stack-frame providers, `lathe.debug.start` handler, `LaunchSession` debug extension. Depends on `com.microsoft.java.debug.core`. |
| Neovim runtime | `nvim-dap` dependency; `setup_dap`-equivalent adapter registration + `enrich_config`. |
| Build | `com.microsoft.java.debug.core` dependency + JPMS wiring for `lathe-server`. |
| Docs | Cross-link from run/test/debug §12.9 and the roadmap; extend §9 limitations. |

The blast radius grows across phases; Phases 0–2 are confined to `lathe-server`, `lathe-core`'s
transform, and the Neovim runtime.
