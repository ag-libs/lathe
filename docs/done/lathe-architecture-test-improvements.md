# Lathe — Architecture and Test Improvements

## Goal

Land a small beta cleanup slice that improves maintainability without changing Lathe's threading model or feature
behavior.
The work focuses on two areas:

- extracting focused workspace helpers from `WorkspaceSession`
- consolidating duplicated test fixtures

## WorkspaceSession Extractions

`WorkspaceSession` remains the owner and coordinator of worker-confined workspace state.
The beta cleanup extracts focused helpers with small APIs.

### DocumentRegistry

Add a `DocumentRegistry` that tracks open document state:

- URI
- latest text
- version
- open/close lifecycle
- delete cleanup facts needed by `WorkspaceSession`

This gives open-document behavior a direct test surface while preserving server-worker ownership.

### DiagnosticPublisher

Add a `DiagnosticPublisher` if the extraction stays small.
It should centralize:

- stale-result checks before publishing diagnostics
- `PublishDiagnosticsParams` construction
- empty diagnostic publication on close/delete

`WorkspaceSession` still decides when diagnostics are published.
`DiagnosticPublisher` only prepares and sends the LSP payloads.

## Test Compiler Fixture

Consolidate duplicated javac test setup into a canonical `TestCompiler`.
The fixture should cover:

- javac initialization
- source writing
- AST parsing and attribution helpers
- class/JAR output helpers used by server tests

Existing `TempSourceCompiler` and `SampleFixture` behavior should move into the canonical fixture when it reduces
duplication and keeps tests readable.

## Test Zip/JAR Fixture

Add a shared test-only fixture for zip and JAR creation.
The fixture should replace duplicated test code that currently uses `JarOutputStream`,
`Files.walk`,
or local zip helper methods.

Keep this helper in test sources.
Production `FileUtil` should stay focused on production filesystem behavior.

## Acceptance Criteria

- `WorkspaceSession` keeps the same public behavior and worker-confinement model.
- Open-document lifecycle tests can target `DocumentRegistry` directly.
- Diagnostic publication tests can target `DiagnosticPublisher` directly if that extraction is made.
- Server tests use one canonical compiler fixture for javac setup.
- Zip/JAR test setup uses one shared test fixture.
- No production behavior changes are introduced by the fixture cleanup.
