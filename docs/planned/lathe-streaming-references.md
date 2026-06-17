# Streaming References and Progress Reporting

## Context
Lathe’s "Find References" feature (`textDocument/references`) resolves usages by employing a fast token index to prune irrelevant files, followed by strict AST-based validation via `javac` for remaining candidates.

While the candidate pruning is highly aggressive, searches for very ubiquitous method names (e.g., `get()`, `build()`) can still result in tens or hundreds of candidates being sequentially verified by `CompilationWorker`s. Currently, Lathe blocks and accumulates all results before returning a massive `List<Location>` array. This leaves the user staring at a frozen IDE UI for multiple seconds with no feedback.

## Goal
Improve the user experience for long-running reference searches by:
1. Emitting real-time progress indicators (spinners/progress bars) in the IDE.
2. Streaming reference matches dynamically as they are discovered so the user can start reviewing results immediately.

## Proposed Design

We will leverage two standard Language Server Protocol (v3.15+) features: **Work Done Progress** and **Partial Results**.

### 1. Work Done Progress (`WorkDoneProgressParams`)
Lathe will support driving the IDE's progress UI using `$/progress` notifications.

- **Client-Provided Token**: If the client provides a `workDoneToken` in the `ReferenceParams`, Lathe will immediately send a `WorkDoneProgressBegin` notification (e.g., "Finding references...").
- **Progress Updates**: As `WorkspaceSession` iterates through modules or candidate files, it will periodically send `WorkDoneProgressReport` payloads, updating the completion percentage (0-100%).
- **Completion**: Once all module futures resolve, Lathe sends a `WorkDoneProgressEnd` notification.

### 2. Streaming Partial Results (`PartialResultParams`)
Lathe will support streaming `Location[]` arrays dynamically.

- **Chunking Results**: Instead of waiting for `CompletableFuture.allOf()` in `WorkspaceSession.searchFutures` to reduce everything into a single list, the system will hook into individual file-resolution futures.
- **Emitting Chunks**: When a `CompilationWorker` finishes a file (or a batch of 5-10 files) and yields a list of `ReferenceMatch`es, Lathe will check if the client provided a `partialResultToken`. If yes, it sends a `$/progress` notification carrying those specific `Location` items.
- **Final Payload**: If partial results were used, the final RPC response to the `textDocument/references` request will be an empty array or `null`.

### 3. Graceful Fallback
Not all LSP clients (or user configurations) support streaming.
- Lathe must inspect the incoming `ReferenceParams` for the presence of `workDoneToken` and `partialResultToken`.
- If they are omitted, Lathe must fallback to the current behavior: suppressing `$/progress` messages, waiting for all threads to join, and returning the complete, unified `List<Location>` in the standard blocking response payload.

## Future Considerations
- **Other Endpoints**: If this architecture proves successful for `textDocument/references`, the same `WorkDoneProgress` and `PartialResult` infrastructure should be reused for other potentially slow workspace-wide operations, such as `workspace/symbol`.
