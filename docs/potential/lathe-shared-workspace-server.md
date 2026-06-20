# Lathe — Shared Language Server Options

Status: potential architecture retained for design history.
No implementation is planned for beta or v1.

## Current Decision

Keep the current one-language-server-process-per-editor-client model for beta and v1.
The sharing options below are significantly more complex than the problem currently justifies.
They should be reconsidered only after measurements demonstrate that duplicate JVM memory, startup latency, or concurrent output writes materially harm real workflows.

This document is not an implementation plan or roadmap commitment.
Its detailed design is retained so future evaluation can reuse the analysis rather than repeat it.

## Decision History

The discussion progressed through these alternatives:

1. Share one LSP connection between multiple editor processes.
   This was rejected because LSP request IDs, client capabilities, document versions, callbacks, and lifecycle are connection-scoped.
2. Run one daemon per canonical workspace with one independent LSP connection per editor.
   Most of this document develops that option, including session isolation, discovery, Neovim, and VS Code support.
3. Run one global Lathe daemon per server version while isolating canonical workspaces inside it.
   This reduces JVM duplication and simplifies endpoint discovery, but increases crash, heap, garbage-collection, javac-plugin, and annotation-processor blast radius.
4. Keep the existing process model.
   This is the current decision for beta and v1 because it has the strongest operational isolation and requires no daemon lifecycle or multi-client state machinery.

Both daemon options remain potential future changes.
Neither should be implemented without performance evidence and a fresh design review.

## Option A — One Daemon per Workspace

Run one Lathe server process per canonical Maven workspace and allow multiple editor processes to attach to it.
Neovim and VS Code are first-class clients of the same connector and daemon architecture.
Each editor remains an independent LSP client with independent unsaved document state.
Clients attached to different workspaces never share a server process or mutable workspace state.

The design targets local editors running as the same operating-system user.
VS Code Remote is supported when its workspace extension host, connector, and daemon run together in the remote environment.
Non-loopback daemon access and sharing between users are out of scope.

## Motivation

Today every editor process launches a complete Lathe server on stdio.
Servers for the same workspace duplicate the JVM, workspace scan, manifest, type indexes, watcher, module workers, file managers, and analysis caches.
They also point full javac passes at the same `.lathe/<module>/classes/` and generated-source directories without coordinating writes.

Sharing should remove that duplication and serialize Lathe-owned output writes without allowing one editor's unsaved buffer to affect another editor.

Lathe intentionally creates a fresh `JavacTask` for each analysis pass.
The expected savings therefore come from process and workspace infrastructure reuse, not from preserving a long-lived javac task.

## Option A Scope Exclusions

- Collaborative editing or synchronization of unsaved buffers between editors.
- Sharing one JSON-RPC connection between editors.
- Sharing a process across different canonical workspace roots.
- Sharing between different users.
- Replacing Maven's writes to `.lathe/` or coordinating arbitrary external Maven processes.
- Preserving a daemon across machine reboot.
- Adding a general-purpose daemon manager.
- Running Lathe from VS Code for the Web, whose browser extension host cannot launch the Java connector.

## Protocol Constraint

LSP models one client and one server on a JSON-RPC connection.
Request IDs, client capabilities, document versions, server-to-client notifications, shutdown, and exit are connection-scoped.
The protocol does not provide a client identifier for multiplexing multiple editors on one connection.

The daemon must therefore create one `LatheLanguageServer` frontend and one LSP4J launcher for every accepted connection.
The frontends share a workspace runtime behind the LSP boundary.

## Required Invariants

1. A client request is evaluated against that client's unsaved document snapshot.
2. Diagnostics, semantic-token refreshes, prompts, and other callbacks go only to the intended client.
3. Closing or shutting down one client cannot remove another client's documents or close shared workers.
4. Different unsaved contents for the same URI can coexist.
5. A stale result is checked against the originating client and document generation.
6. One canonical workspace root maps to at most one live daemon for a Lathe server version.
7. A daemon accepts initialization only for its configured canonical workspace root.
8. Full compiler passes that write a module's shared outputs are serialized through that module's worker.
9. Workspace reload atomically replaces shared workspace state and invalidates every client's affected snapshots.
10. Failure to discover or join an existing daemon must never silently start two writers for the same daemon identity.

## Process Topology

```text
Editor A
  stdin/stdout
      |
      v
LatheConnector A -------+
                         \
                          v
                    Lathe daemon for /real/workspace
                          ^
                         /
LatheConnector B -------+
      ^
      |
  stdin/stdout
Editor B
```

The connector is a lightweight process launched by an editor language-client integration in the same way as the current server.
It receives the workspace root as an explicit command-line argument, joins or starts the matching daemon, and copies framed LSP bytes in both directions without interpreting requests.

The daemon accepts one local transport connection per editor.
Each accepted connection gets a separate LSP4J launcher, language-server frontend, client proxy, and `ClientSession`.

## Workspace Identity

Each editor integration locates the nearest `.lathe` directory and passes that root explicitly:

```text
lathe-launcher.sh --workspace-root /path/from/root_dir
```

The connector resolves the supplied path with `Path.toRealPath()` before discovery.
This collapses relative paths, `..`, and symlink aliases.
Failure to resolve the root is fatal and is reported on connector stderr.

The daemon identity consists of:

```text
server version + canonical workspace root
```

The server version is represented by the version-specific launcher directory.
Java home and fixed JVM arguments are properties of that installed launcher version and daemon process.
`LATHE_JVM_OPTS` is process-scoped: the first connector that starts the daemon determines its values.
Later connectors join the existing daemon and cannot change JVM options.
This must be documented and logged.

The daemon validates the root URI received in every client's `initialize` request.
An absent root or a root that canonicalizes differently is rejected with an initialization error.
Multiple workspace folders on one connection are rejected because the daemon is intentionally single-workspace.
A multi-root editor creates one LSP client and connector per canonical Lathe root.

## Discovery and Startup

Runtime discovery state belongs under the user cache, not under the project:

```text
~/.cache/lathe/workspaces/<sha256-of-canonical-root>/<version>/
├── startup.lock
├── daemon.lock
├── endpoint.json
└── daemon.log
```

`endpoint.json` contains only local process metadata:

```json
{
  "schemaVersion": "1",
  "workspaceRoot": "/real/workspace",
  "serverVersion": "0.1.0",
  "pid": 12345,
  "port": 43127,
  "token": "random-per-daemon-secret"
}
```

Loopback TCP is preferred over a Unix-domain socket for the first implementation.
The connector hides the transport from editors, TCP works with Java and the existing test infrastructure on all target systems, and the random token protects against unrelated local connections.
The daemon binds only `127.0.0.1` on an ephemeral port.

Discovery uses two locks with different lifetimes:

- a connector holds `startup.lock` while validating or starting a daemon;
- the daemon holds `daemon.lock` exclusively for its entire lifetime.

Startup proceeds as follows:

1. Canonicalize the root and compute the runtime directory.
2. Acquire `startup.lock` exclusively.
3. Read and validate `endpoint.json` if present.
4. Attempt an authenticated connection to the endpoint.
5. If the connection succeeds, release the lock and relay stdio.
6. If it fails, try to acquire `daemon.lock` without blocking.
7. If `daemon.lock` is unavailable, a daemon is still alive or starting; wait for endpoint recovery with a bounded timeout and never start another daemon.
8. If `daemon.lock` is acquired, remove stale endpoint metadata, release the probe lock, and start the daemon while still holding `startup.lock`.
9. The daemon exits immediately if it cannot acquire `daemon.lock`.
10. Wait for a valid endpoint file and authenticated connection with a bounded timeout.
11. Release `startup.lock` and relay stdio.

Holding `startup.lock` across validation and startup prevents two simultaneous editor launches from creating two daemons.
Holding `daemon.lock` for the process lifetime distinguishes a stale endpoint from a temporarily unresponsive live daemon.
PID liveness alone is not sufficient because PIDs are reused.
The connector must authenticate and verify the root and version returned by the daemon handshake.

`endpoint.json` is written atomically only after the daemon has bound its port.
The token uses at least 128 bits from `SecureRandom`.
The runtime directory and files are owner-only where POSIX permissions are available.

## Connector Lifecycle

`LatheConnector` owns no workspace or LSP state.
After the private daemon handshake succeeds, it performs two byte-copy operations:

- editor stdin to daemon socket output;
- daemon socket input to editor stdout.

Editor stderr is not part of LSP framing.
Connector and startup failures are written there.

EOF from editor stdin closes the socket output side and waits briefly for the daemon side to finish.
EOF from the daemon closes connector stdout and exits non-zero unless the client completed normal LSP shutdown.
The connector does not terminate the daemon.

The generated shell launcher continues to own all fixed JVM/module arguments.
It invokes connector mode by default and provides an internal daemon mode used only by the connector:

```text
LatheServer --connect --workspace-root <root> --server-version <version>
LatheServer --daemon --workspace-root <root> --server-version <version>
```

The generated script also passes its own absolute version-specific path to connector mode.
When a daemon is needed, the connector starts that same script in daemon mode instead of trying to reconstruct the current JVM executable, module path, `--add-exports`, `--add-opens`, or user JVM options.

The exact internal command names are not public API.

## Server Ownership Model

The current `WorkspaceSession` combines client-local and workspace-shared state.
It must be split into two ownership levels.

### `WorkspaceRuntime`

One instance exists in the daemon and is confined to one `ServerEventLoop`.
It owns:

- canonical workspace root;
- `WorkspaceManifest`;
- `WorkspaceModuleRegistry`;
- `WorkspaceModuleGraph`;
- base `ReferenceCandidateIndex` built from disk;
- dependency, JDK, and reactor `WorkspaceTypeIndex` state;
- reactor shards;
- `WorkspaceWatcher` and its single periodic poll;
- connected `ClientSession` instances;
- daemon-wide reload and close coordination.

It does not own a `LanguageClient`, open documents, document versions, diagnostics, or per-client debounce state.

### `ClientSession`

One instance exists per accepted LSP connection and is confined to the workspace event loop.
It owns:

- stable internal `ClientId`;
- the connection's `LanguageClient` proxy and capabilities;
- `DocumentRegistry`;
- `DiagnosticPublisher`;
- session-local candidate-index overlay;
- keyed debounce handles;
- per-client POM notification state;
- initialized, shutting-down, and closed lifecycle state.

It routes compiler and feature work through `WorkspaceRuntime`.
It cannot close the shared module registry or event loop.

### LSP frontend

Each accepted connection gets a separate `LatheLanguageServer`, `LatheTextDocumentService`, and `LatheWorkspaceService`.
These become thin adapters bound to one `ClientSession`.

`LatheLanguageServer.shutdown()` closes only its session after outstanding client requests settle.
`exit()` closes that connection; it must not call `System.exit()`.
The daemon process controls its own termination.

## Threading Model

```text
daemon acceptor
  accepts sockets and constructs LSP4J launchers

lathe-workspace-<short-id>
  owns WorkspaceRuntime and every ClientSession
  serializes document state, routing, reload, and client publishing

lathe-module-<name>-<tree>
  one shared worker per ModuleSourceConfig
  serializes javac work and full output writes for that config

lathe-external
  shared external-source worker
```

LSP4J receive threads extract immutable request data and enqueue it with the originating `ClientId`.
Compilation results carry `ClientId`, URI, document generation, and mode back to the workspace event loop.
Publishing occurs only after resolving the matching live session and checking its current generation.

Using one workspace event loop preserves the existing confinement model and makes cross-client close/reload ordering explicit.
It also means a blocking operation on that event loop affects all clients, so file IO and compiler work must remain outside it where practical.

## Document Semantics

Open documents are isolated by session:

```text
Client A: (A, file:///Foo.java) -> content A, version 12, generation 40
Client B: (B, file:///Foo.java) -> content B, version 3, generation 51
```

LSP document versions are never compared across sessions.
Debounce keys become `(ClientId, URI)` so typing in one editor cannot cancel another editor's compile.

All text-document requests use the requesting session's snapshot.
If that session has not opened the URI, features retain the current empty/null behavior unless the endpoint explicitly supports closed files.

Diagnostics and semantic-token refreshes are sent only through the requesting session's client proxy.
`didClose` clears only that session's diagnostics and cache entries.

No conflict warning is needed merely because two sessions contain different unsaved contents.
They are independent views by design.

## Analysis Cache Identity

`SourceAnalysisSession` currently caches analysis by URI, and semantic-token lookup uses URI and client-local version.
That is unsafe when two clients open the same URI.

Introduce a value key:

```java
record DocumentKey(ClientId clientId, String uri) {}
```

Interactive compile, completion, semantic-token, close, and cache-drop APIs use `DocumentKey`.
`CachedFileAnalysis` continues to record source content and version, but the version is meaningful only within the key's client session.

Disk-only analyses used by references and implementation search must not overwrite an interactive cache entry.
They use an uncached path or a separate request-scoped key.

Content-addressed cache sharing between clients is a possible later optimization.
It is not part of the first implementation because cached javac objects have task and file-manager lifetimes that need explicit validation before cross-session reuse.

## Candidate Index and Cross-file Search

The current candidate index is updated with unsaved content and is therefore client-local in effect.
The shared design splits it into:

- a shared base index containing files read from disk;
- one overlay per client containing that client's open-document contents.

A references or implementation request uses:

```text
requesting client's overlay over shared disk base
```

It never searches another client's unsaved overlay.
For a URI open in the requesting client, the overlay shadows the disk entry.
For all other URIs, the disk base is authoritative.

On close, the overlay entry is removed and the shared disk entry becomes visible again.
On deletion or observed disk change, the base index is refreshed once by the workspace runtime.

## Save and Shared Compiler Outputs

Interactive `OPEN` and `FAST` modes analyze only and do not generate class files.
`FULL` mode invokes javac generation and writes shared module classes and generated sources.

All full passes for one `ModuleSourceConfig` already flow through one single-threaded `CompilationWorker` in the shared runtime.
This prevents two Lathe clients from writing that module's outputs concurrently.
Main and test source configs retain separate workers and output directories.

For generated outputs, disk is the workspace authority.
On `didSave`, the runtime should read the saved file from disk when the full compile reaches the worker, rather than compiling an arbitrary client's later unsaved snapshot.
This closes the race where an editor changes its buffer again immediately after save and ensures generated outputs correspond to a persisted state.

Save requests for the same URI that are still queued may be coalesced before compilation.
The executed request reads the latest disk content and fingerprint.
After compilation, diagnostics are published to the originating client only if its current open snapshot still represents the saved content; otherwise an interactive compile remains responsible for its current diagnostics.

Maven can still write `.lathe` outputs concurrently with Lathe.
Cross-process locking with Maven is a separate design problem and is not claimed by this change.

## Workspace Reload

One watcher polls `workspace.json` and reactor POM fingerprints.

When `workspace.json` changes:

1. Load and validate the new manifest.
2. Build a replacement module registry, graph, disk candidate index, reactor shards, and type index.
3. Swap the shared state on the workspace event loop.
4. Advance generations for every open document in every client session.
5. Close the old registry and workers.
6. Schedule recompilation for each session's open documents.
7. Notify every connected initialized client that the workspace reloaded.

Reload work must be deduplicated across clients.

When a POM fingerprint changes, the runtime records one workspace event and offers it once to each connected session.
Each session independently suppresses duplicate prompts while its own `showMessageRequest` is pending.
One client's response does not dismiss another client's prompt.

## File Events

Editor clients may all report the same `workspace/didChangeWatchedFiles` deletion.
The runtime deduplicates identical deletion events by observed filesystem state and performs shared cleanup once.
Each session that has the URI open has its local document removed only when that client sends `didClose`; a filesystem deletion notification does not silently discard unsaved client content.

Shared class-output cleanup and reactor-shard refresh happen once.
Affected sessions receive diagnostics or recompilation according to their own open state.

## Daemon Lifecycle

The daemon tracks live transports independently of initialized sessions.

- LSP `shutdown` closes one `ClientSession` but leaves the connection available for `exit` as required by LSP.
- LSP `exit`, socket EOF, or transport failure removes that session and releases all of its cache entries.
- The workspace runtime remains alive while any session is connected.
- After the final connection closes, an idle timeout starts.
- A new authenticated connection cancels the timeout.
- On timeout, the daemon closes the workspace runtime, workers, acceptor, and endpoint file, then exits normally.

A five-minute default idle timeout balances fast editor restarts against bounded resource retention.
An environment override may be considered later; it is not required in the first slice.

The daemon installs a shutdown hook that closes workers and removes `endpoint.json` only if the file still identifies its PID and token.
Stale metadata is also cleaned by the next connector under the startup lock.

## Failure Isolation

A malformed or disconnected client closes only that transport and session.
Ordinary request exceptions are returned through that client's JSON-RPC connection.

An unrecoverable workspace-runtime or JVM failure affects all clients because they intentionally share one process.
Connectors observe EOF and exit, allowing the editor's normal LSP restart path to create a new daemon.

One client can consume compiler capacity and increase latency for others.
The first implementation uses FIFO ordering within each existing module worker.
If measurements show starvation, scheduling can move to per-client round-robin queues without changing session semantics.

## Logging

Daemon logs go to the versioned workspace runtime directory.
Every client-scoped operation includes a short client ID:

```text
[open] client=3 file:///workspace/Foo.java
[compile:fast] client=3 file:///workspace/Foo.java 82ms diags=1
[disconnect] client=3 reason=eof
```

Workspace operations omit a client ID:

```text
[reload] /workspace modules=12 clients=2
```

The connector logs only discovery, startup, authentication, and transport failures.
It must never write logs to stdout.

`LATHE_DEBUG` is fixed when the daemon starts, like JVM options.
Joining clients cannot change daemon logging level.

## Editor Integration Contract

The daemon is editor-agnostic.
Every editor integration must provide the same boundary behavior:

1. Discover a canonical Lathe root before starting a language client.
2. Start the installed launcher with exactly one `--workspace-root` argument.
3. Create one LSP connection per root and never multiplex roots on a connection.
4. Reuse one language client for buffers belonging to the same root inside one editor process.
5. Let separate editor processes create separate connectors; daemon discovery performs cross-process reuse.
6. Route dependency and JDK source buffers back to the client that produced the navigation result.
7. Stop only the editor's own language clients during extension/plugin shutdown.

The connector preserves a normal stdio LSP boundary, so an editor does not implement daemon discovery, authentication, locking, or TCP transport.

## Neovim Integration

The plugin must compute the `.lathe` root before starting the client and include it in the command.
The current `root_dir` callback already performs discovery, including external source buffers under the Lathe cache.

The current static `cmd = { launcher }` cannot interpolate the root returned asynchronously by `root_dir`.
The plugin must either start Lathe from the root callback with `vim.lsp.start()` or use a Neovim command factory that receives the resolved client configuration.
The first implementation should prefer the explicit `vim.lsp.start()` path because it makes the root argument and Neovim-local reuse predicate visible and testable.

The configuration should start or reuse a Neovim-local LSP client with:

```text
cmd = { launcher, "--workspace-root", root }
root_dir = root
```

Neovim continues to reuse one client inside a single Neovim process for the same root.
Separate Neovim processes launch separate connectors that join the same daemon.

External dependency and JDK source buffers must retain the originating workspace root.
The existing `last_root` fallback remains session-local in the plugin and supplies that root to the connector.

## VS Code Integration

Lathe requires a workspace extension using `vscode-languageclient/node`.
The extension runs in VS Code's workspace extension host and creates ordinary `LanguageClient` instances backed by the installed Lathe launcher.

### Client ownership

The extension owns:

```text
Map<canonical workspace root, LanguageClient>
```

For a single-folder VS Code workspace, the map normally contains one client.
For a multi-root VS Code workspace, the extension discovers `.lathe` independently for each workspace folder, canonicalizes the results, deduplicates aliases, and creates one client per distinct Lathe root.

Each client starts the connector with:

```typescript
const serverOptions: ServerOptions = {
  command: launcher,
  args: ["--workspace-root", canonicalRoot.fsPath],
};
```

The client's initialization parameters contain only that root as its workspace folder.
The extension sets `LanguageClientOptions.workspaceFolder` explicitly for each client so `vscode-languageclient` does not infer the complete multi-root folder list.
The extension listens for workspace-folder additions and removals, starts missing clients, and stops clients whose roots are no longer represented.
A folder removal does not stop a client while another folder in the same VS Code window still canonicalizes to that root.

Two VS Code windows opened on the same root create separate connectors and LSP sessions that join the same daemon.
A VS Code window and a Neovim process opened on the same root do the same.

### Workspace document routing

Each VS Code client uses a root-specific Java document selector.
Nested Lathe workspaces use nearest-`.lathe` ownership, so the nested client receives its files instead of the parent client.

The extension must not configure one language client with all VS Code workspace folders.
Doing so would conflict with the daemon's single-root invariant and would share mutable state across roots.

### External source routing

Dependency and JDK source files live under the shared Lathe cache rather than under a workspace root.
In a multi-root window, a broad cache document selector on every client would send the same `didOpen` and feature requests to multiple daemons.

The VS Code extension therefore maintains a window-local ownership map:

```text
Map<external source URI, canonical Lathe root>
```

The language-client middleware records ownership when a Lathe client returns an external URI from definition, workspace-symbol, or another navigation result.
All clients may advertise the cache path in their document selectors, but middleware forwards `didOpen`, `didClose`, hover, completion, signature help, definition, references, semantic tokens, symbols, folding, code actions, and formatting only through the owning client.
Non-owning clients return no result and do not synchronize the document.

If a user opens a cache source directly without a preceding Lathe navigation result, the extension assigns it to the client for the active Java editor's root.
If no unambiguous active root exists, the extension leaves the source unattached and offers a workspace-root picker rather than broadcasting it.

VS Code represents one file URI as one text document within a window.
If navigation from another root later targets the same external URI, ownership moves to that root and the extension closes it on the previous client before opening it on the new client.
Separate VS Code windows retain independent ownership maps and can analyze the same external URI through different workspace sessions.

External cache files remain read-only, matching the existing Lathe extraction policy.

### Lifecycle and recovery

Extension deactivation calls `stop()` or `dispose()` on every `LanguageClient` it owns.
This sends connection-scoped shutdown and exit messages; it does not stop a daemon used by another editor.

If the daemon dies, the connector exits and the corresponding `LanguageClient` observes server termination.
The extension uses the language client's normal restart path, which launches a new connector and joins or starts the replacement daemon.

### Remote and platform behavior

For Remote SSH, Dev Containers, and Codespaces desktop clients, the Lathe extension runs as a workspace extension.
The launcher, Java process, cache directory, endpoint metadata, and daemon all live in that remote extension-host environment next to the workspace filesystem.
The loopback daemon port is remote-local and is never exposed to the desktop UI process.

VS Code for the Web is unsupported because its browser extension host cannot spawn the Java connector.

Initial VS Code support follows the operating systems supported by Lathe's generated launcher.
Supporting native Windows requires `ServerInstaller` to provide a Windows launcher and platform-correct module-path separators; that packaging work is separate from the multi-client protocol but must be completed before claiming Windows support.

### Extension configuration

The extension resolves the launcher from `LATHE_CACHE` or the default `~/.cache/lathe/current` location, matching Neovim.
If no executable launcher exists, it reports that the user must run `mvn process-test-classes` in the workspace.

Per-root settings are read with that workspace folder as the VS Code configuration scope.
Process-scoped settings such as `LATHE_JVM_OPTS` and `LATHE_DEBUG` affect only daemon creation; when a daemon already exists, the extension reports that a restart is required for those values to take effect.

### Packaging and activation

The extension lives under a top-level `vscode/` directory and is packaged as a VSIX.
Its manifest declares workspace extension placement so remote VS Code installs run it beside the workspace and Java runtime.
Activation is limited to Java documents or workspaces containing `.lathe`; activation alone does not start a client until a valid root is discovered.

The extension does not bundle a Lathe server copy.
It uses the Maven-installed versioned launcher so Neovim and VS Code resolve the same server version and daemon identity.

## Potential Code Changes for Option A

The implementation is expected to affect these areas:

- `LatheServer`: add connector and daemon modes; remove process exit from per-client LSP lifecycle.
- New `DaemonDiscovery`/`DaemonEndpoint`: canonical identity, locking, endpoint validation, startup, and authentication.
- New `LatheConnector`: stdio-to-socket relay.
- New `WorkspaceDaemon`: accept loop, connection lifecycle, idle shutdown, and one `WorkspaceRuntime`.
- `LatheLanguageServer`: bind one frontend to one `ClientSession`; validate initialization root.
- `LatheTextDocumentService` and `LatheWorkspaceService`: route through a bound session instead of owning global close behavior.
- Split `WorkspaceSession` into `WorkspaceRuntime` and `ClientSession`.
- `ServerEventLoop`: support `(ClientId, URI)` scheduling keys or a general value key.
- `CompileRequest` and `CompileResponse`: carry `DocumentKey` and generation.
- `SourceAnalysisSession`: key interactive cache by `DocumentKey`.
- `CompilationWorker`: accept session-aware request identity while remaining shared per module config.
- `ReferenceCandidateIndex`: shared disk base plus session overlays.
- `DiagnosticPublisher`: remain per client and validate against that client's registry.
- `ServerInstaller`: render version and connector arguments into the launcher.
- `LatheLayout`: define versioned workspace runtime names and endpoint metadata names.
- `neovim/lua/lathe.lua`: pass the discovered root explicitly.
- New VS Code workspace extension: one `LanguageClient` per canonical root, multi-root lifecycle, and external-source ownership middleware.
- VS Code extension packaging: workspace extension placement, VSIX build, and extension-host integration tests.

No new Maven module is required initially.
Connector and daemon classes can remain in `lathe-server` and use the same installed runtime graph.

## Potential Implementation Slices for Option A

### Slice 1 — Separate ownership without changing transport

- Extract `WorkspaceRuntime` and `ClientSession` from `WorkspaceSession`.
- Keep the current single stdio connection.
- Move documents, publisher, client callbacks, and debounce state into `ClientSession`.
- Move manifest, registry, indexes, watcher, and reload into `WorkspaceRuntime`.
- Make shutdown close a session, then explicitly close the runtime from the current process owner.

This slice should preserve existing behavior and creates the seam needed for multiple sessions.

### Slice 2 — Session-aware compiler and search state

- Add `ClientId` and `DocumentKey`.
- Key analysis caches, compilation identity, debounce, and stale checks by session.
- Split reference candidate state into disk base and session overlay.
- Add unit tests with two sessions editing the same URI differently.

### Slice 3 — In-process multi-connection daemon

- Add loopback acceptor and authenticated private handshake.
- Create one LSP4J launcher and frontend per accepted connection.
- Implement per-client shutdown/exit and workspace idle shutdown.
- Exercise two socket clients against one runtime in server integration tests.

### Slice 4 — Discovery and connector

- Add canonical workspace hashing, runtime directories, file locking, endpoint metadata, stale recovery, and daemon spawning.
- Add the byte relay and detached daemon logging.
- Test simultaneous connector startup and stale endpoint recovery.

### Slice 5 — Launcher and Neovim integration

- Update generated launcher rendering.
- Pass the explicit root from the Neovim plugin.
- Update dev scripts and Python LSP tooling.
- Extend Maven invoker smoke tests to start two clients through the installed launcher and verify one daemon PID.

### Slice 6 — VS Code integration

- Add the VS Code workspace extension and `vscode-languageclient/node` dependency.
- Create one client per canonical Lathe root and handle multi-root folder changes.
- Set each client's `LanguageClientOptions.workspaceFolder` to its single owning folder/root.
- Add root-specific workspace selectors and external-source ownership middleware.
- Cover extension deactivation, connector failure, and remote extension-host path resolution.
- Add VSIX packaging and VS Code extension-host integration tests.
- Verify one VS Code window can attach to multiple roots without sharing their daemon state.
- Verify VS Code and Neovim clients can share one daemon for the same root.

### Slice 7 — Full-save output authority

- Serialize and coalesce full saves per URI/module.
- Read persisted content for generated-output compilation.
- Verify divergent unsaved buffers do not corrupt diagnostics or outputs.

This slice can land earlier if concurrent output writes are treated as an independent correctness issue.

## Potential Test Strategy

### Unit tests

- Canonical roots reached through symlink and direct paths produce the same daemon identity.
- Different roots and server versions produce different identities.
- Endpoint metadata round-trips and rejects wrong root, version, token, and schema.
- A held daemon lifetime lock prevents stale recovery from starting a duplicate daemon.
- Stale-result checks are isolated by `ClientId` and generation.
- Debouncing one client does not cancel another client for the same URI.
- Closing one session removes only its documents and analysis cache entries.
- Session overlays shadow disk candidates without leaking to other sessions.
- Workspace reload advances generations in every session.
- Final-session removal starts idle shutdown; reconnect cancels it.

### Multi-client server tests

- Two clients initialize the same root and receive independent capabilities and callbacks.
- Both open the same URI with different invalid source and receive diagnostics matching their own content.
- Completion, hover, semantic tokens, references, and formatting use the requesting client's snapshot.
- Client A closes the URI while client B continues to receive correct results.
- Client A shuts down and exits while client B remains operational.
- A workspace reload recompiles and notifies both clients exactly once.
- Duplicate watched-file events perform shared cleanup once.
- Concurrent full saves are serialized by the module worker.

### Connector and process tests

- First connector starts a daemon and completes initialization.
- Second connector for the same root/version joins the same PID.
- Connectors for different roots start different PIDs.
- Two simultaneous first connectors create one daemon.
- A dead PID, reused PID, missing endpoint, bad token, and refused connection recover safely.
- Killing the daemon causes both connectors to terminate and permits clean restart.
- Idle shutdown removes endpoint metadata and closes the process.
- No daemon or connector writes protocol-external bytes to stdout.

### VS Code extension tests

- A single-folder workspace creates one language client with the canonical root argument.
- A multi-root workspace creates one client per distinct Lathe root.
- Symlinked workspace-folder aliases do not create duplicate clients.
- Adding and removing workspace folders starts and stops only affected clients.
- Nested `.lathe` roots route files to the nearest root.
- External navigation records the originating root and synchronizes the source with only that client.
- Reassigning an external source closes it on the previous client before opening it on the new owner.
- Directly opened external sources use the active-root fallback or request an explicit root.
- Extension deactivation stops all owned clients without issuing daemon-global termination.
- Remote extension-host execution resolves launcher and cache paths in the remote environment.

Pure TypeScript tests cover root/client ownership and external-source routing.
VS Code extension-host tests using the supported VS Code test runner cover activation, document selectors, real `LanguageClient` startup, multi-root folder changes, diagnostics, shutdown, and daemon reuse across two client instances.

### Maven invoker verification

The existing LSP smoke test should launch two client connections through the generated script.
It should assert independent diagnostics and lifecycle while reading the daemon PID from validated endpoint metadata.
It must stop both clients and wait for test-configured short idle shutdown rather than forcibly destroying a per-test server process.

## Required Performance Validation Before Reconsideration

Measure before and after with one, two, and four editor clients on the same workspace, including mixed Neovim and VS Code sessions:

- resident memory;
- daemon and connector process count;
- first-client and later-client initialization latency;
- p50/p95 change-to-diagnostics latency under one active client;
- p50/p95 latency when two clients type concurrently in the same and different modules;
- workspace reload duration;
- idle memory after all clients disconnect.

The design is successful only if later clients materially reduce memory/startup cost without pushing normal single-client p95 latency outside Lathe's target.

## Alternatives Considered

### Option B — One global daemon with isolated workspaces

This remains a potential alternative rather than a rejected design.
One Lathe process would serve all canonical roots for one installed server version while retaining independent LSP connections and workspace runtimes:

```text
Lathe daemon for server version
├── WorkspaceRuntime /project-a
│   ├── ClientSession A
│   └── ClientSession B
└── WorkspaceRuntime /project-b
    └── ClientSession C
```

The daemon would own:

```java
Map<WorkspaceKey, WorkspaceRuntimeHandle> workspaces;

record WorkspaceKey(Path canonicalRoot, String serverVersion) {}
```

Each workspace would still own its manifest, module graph, watcher, module registry, reactor indexes, compiler workers, output paths, event loop, and connected client sessions.
Only the JVM, acceptor, authentication endpoint, logging infrastructure, immutable dependency/JDK caches, and idle scheduler would be global.

This option simplifies discovery to one endpoint per server version and saves the JVM baseline when several workspaces are open.
It also fits VS Code multi-root windows naturally because each root-specific `LanguageClient` connects to the same process and selects a different `WorkspaceRuntime`.

Its costs are materially different from Option A:

- a crash or `OutOfMemoryError` affects every open workspace;
- one heap and garbage collector serve the aggregate compiler load;
- javac plugins and annotation processors execute in the same JVM as unrelated projects;
- process-level `LATHE_JVM_OPTS` and debug settings apply to every workspace;
- global compilation limits may be required to prevent one workspace from starving others;
- every request and result must carry `WorkspaceKey`, `ClientId`, document identity, and generation.

Workspace runtimes would need independent event loops so reload or file IO in one project cannot block another.
An idle workspace runtime would close after its last client disconnects, and the global process would exit only after all runtimes were gone and a process-level timeout elapsed.

Option B should be preferred over Option A only if measurements show that JVM duplication across multiple simultaneously open workspaces is a dominant cost and the larger failure boundary is acceptable.

### One LSP connection shared by editors

Rejected.
LSP request IDs, capabilities, versions, callbacks, and lifecycle are connection-scoped, and the protocol has no client discriminator.

### One mutable document map shared by connections

Rejected.
Different unsaved versions of one URI are normal and must not overwrite each other.

### One complete compiler context per client inside the daemon

Not selected initially.
It simplifies cache identity but retains most file-manager and compiler-context duplication.
Session-aware cache keys provide isolation while preserving one module worker.

### Direct editor TCP connections

Not selected initially.
It would require editor-specific daemon discovery, authentication, startup locking, and reconnect behavior.
The connector keeps those concerns editor-independent and preserves the existing stdio LSP configuration shape.

### Derive workspace root from process working directory

Rejected.
Editor working directory and LSP workspace root are independent, and both Neovim and VS Code can open multiple roots from one process.
The root must be passed explicitly.

## Potential Acceptance Criteria for Option A

- Two independent editor processes attached to the same canonical root use one daemon PID.
- Editor processes attached to different canonical roots use different daemon PIDs.
- Neovim and VS Code can attach concurrently to the same daemon without sharing LSP session state.
- A VS Code multi-root window creates one client and daemon identity per canonical Lathe root.
- VS Code external dependency/JDK sources route to exactly one owning workspace client per window.
- VS Code Remote runs connector and daemon state in the workspace extension-host environment.
- The VS Code desktop extension is packaged as a VSIX and passes real extension-host integration tests.
- VS Code for the Web and any platform not supported by the generated launcher are reported as unsupported rather than failing silently.
- Divergent unsaved contents for the same URI produce isolated requests and diagnostics.
- Closing, shutting down, or crashing one client does not interrupt another client.
- Workspace metadata and watchers exist once per daemon.
- Lathe full-save output writes for one module are serialized.
- Startup races cannot produce two authenticated daemons for one root/version identity.
- Stale endpoint state recovers automatically.
- Existing single-client behavior and feature tests remain valid.
- Generated launcher and Maven invoker tests cover the installed end-to-end path.
