# Lathe — Roadmap

## Near-term

**Simplify `compileWith` in `LatheTextDocumentService`**
Self-contained refactor, low risk, already identified.
Clean this up before the codebase grows around it.

**Shim correctness drift**
Several known issues deferred from the design review:
- Move params writing, class copying, generated-source copying, and lock cleanup into a true
  `finally` path around `javacCompiler.performCompile()`.
- Make silent javac failure surface as an `IOException` instead of being swallowed.
- Redirect accidental stdout logging away from the LSP stdio pipe before starting the server.

## Medium-term

**Maven-managed server distribution**
Install the server binary under `~/.cache/lathe/servers/<version>/` via `lathe:sync`.
This is the prerequisite for external adoption — without it, users must build from source.
Unlocks writing real setup documentation and onboarding external users.

## Longer-term

**Stale-POM detection**
Once the refresh mechanism and distribution story are solid:
prompt the user in the editor when a `pom.xml` changes after the last sync run,
so they know to re-run the documented Maven lifecycle command.
