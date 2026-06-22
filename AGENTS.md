# Lathe — AI Agent Guide

## Design Documents

Design documents live in `docs/`.

Before making non-trivial changes, read the relevant design document first:

- Overall design: `docs/lathe-design.md`
- Roadmap: `docs/roadmap.md`

## Working style

- **CRITICAL**: If a change touches multiple classes, changes a public API, or introduces a new
  abstraction, you **MUST STOP**. You are forbidden from writing code until you output a structured
  design summary and explicitly wait for the user to reply "Approved".
- **NEVER** run `git commit` or `git push` autonomously. You MUST show the `git diff` and wait for
  user authorization.
- **Ask before coding when in doubt** — if the approach is unclear or multiple valid designs exist,
  present the options and ask the user to choose before writing any code.
- **Comprehensive Commit Messages** — all commit messages must be comprehensive and clear,
  describing both the "what" and the "why" of the changes, and following standard prefix naming (
  e.g., `refactor:`, `test:`, `docs:`, `feat:`).
- **No backward compatibility requirement** — the tool has no external adopters yet; schema changes,
  format changes, and API changes may break existing files without migration support.

---

## Modules

| Module               | Role                                                                                                                                                             |
|----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `lathe-core`         | Shared utilities (property file helpers, future shared code); no external deps; has `module-info.java`                                                           |
| `lathe-compiler`     | Plexus compiler SPI (`hint=lathe`)                                                                                                                               |
| `lathe-maven-plugin` | `lathe:init` (auto-bound to `initialize`, creates `.lathe/`) and `lathe:sync` (auto-bound to `process-test-classes`) Mojos; owns Maven invoker integration tests |
| `lathe-server`       | LSP server; reads files produced by the other two; JPMS module; regular dep on `lathe-core`                                                                      |

Build order: `lathe-core` → `lathe-compiler` → `lathe-server` → `lathe-maven-plugin`.

`lathe-compiler`, `lathe-maven-plugin`, and `lathe-server` use `lathe-core` for shared layout and
property-file helpers.

- `groupId`: `io.github.ag-libs`
- Package root: `io.github.aglibs.lathe`
- Java 21: `<maven.compiler.release>21</maven.compiler.release>`
- Repo root: this directory

---

## Build commands

```bash
mvn install -DskipTests                                          # build and install all modules
mvn verify -pl lathe-maven-plugin                                # all test layers (unit + invoker)
mvn verify -pl lathe-maven-plugin -Dsurefire.skip=true           # invoker integration tests only
mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module    # one specific invoker project
```

## Test projects

**dropwizard** (`~/git/dropwizard`) — used to test lathe end-to-end with a large real-world
codebase.
Requires Java 25 (Corretto 25); set `JAVA_HOME=/opt/amazon-corretto-25.0.0.36.2-linux-x64` before
building.
Run `mvn process-test-classes` to trigger `lathe:sync` and verify the launcher and workspace
manifest.

**helidon** (`../helidon` or `~/git/helidon`) — another large real-world codebase used for
end-to-end testing and validation. Run `mvn process-test-classes` similarly to trigger
synchronization.

`spotless:check` runs on `verify` — run `spotless:apply` to fix formatting before committing.
After a large Java change, agents may run `mvn spotless:apply` before tests to normalize formatting.

---

## Coding style

- **Java formatting**: Google Java Format via Spotless.
- **CRITICAL WORKFLOW**: Immediately after you modify ANY `.java` file, you **MUST** execute
  `mvn spotless:apply` in the terminal to normalize the formatting. **DO NOT** end your turn or
  present the code to the user until Spotless has successfully run.
- **Records**: Use `record` for all value types (params, results, entries, config objects). **ALL
  records MUST have a compact constructor** (e.g., `public MyRecord { ... }`) that implements
  invariants and validates parameters (e.g. using `ValidCheck.check()`).
- **Variables (`var` vs Types)**:
    * **NEVER** write `final Type x = new X()` — **MUST** use `var` for all constructor calls
      and array creation, even when the declared type would be a supertype.
    * **MUST** use `var` when the return type name appears literally in the method name
      (`getSourcePositions`, `offsetToPosition`, `toPath`, `toString`) or the method is a
      same-class factory (`Stopwatch.start()`, `Trees.instance()`).
    * **NEVER** use `var` for cross-class factory/utility methods, map/collection accessors,
      or any stream terminal — **MUST** write the explicit type (`List<T>`, `Map<K,V>`, `Set<T>`).
    * **NEVER** omit `final` on local variables, parameters, or fields unless the variable is
      explicitly reassigned.
- **KISS & Architecture**:
    * **NEVER** introduce Interfaces for classes that only have a single implementation.
    * **NEVER** introduce Design Patterns (Factories, Builders, Strategies) unless explicitly
      requested.
    * **NEVER** nest `if/else` statements more than 2 levels deep. You **MUST** use early returns (
      guard clauses).
    * **NEVER** hardcode directory names, file names, or shared configuration keys. They **MUST** be
      defined as constants exclusively inside `LatheLayout.java` or `LatheFlags.java`.
    * **NEVER** put business logic directly inside Maven Mojo `execute()` methods.
- **DRY (Don't Repeat Yourself)**:
    * **The 3-Line Rule:** If you copy and paste a block of 3 or more lines (e.g. Future routing or
      telemetry logging) across more than 3 methods, you **MUST** extract that logic into a
      `private` helper method.
    * **Anti-Spaghetti Rule:** **NEVER** prematurely extract logic that is only used *once* into its
      own helper method.
- **Lambdas & Streams**:
    * **Prefer** extracting `{}` block-lambda bodies into a named `private` method passed via method
      reference — this applies especially to stream pipeline operations (`map`, `flatMap`, `filter`)
      and thread/executor factories, where the extracted name adds clarity.
      **Exception:** if the lambda closes over several local variables that would all become parameters
      of the extracted method, an inline block body is acceptable — the added parameter list
      would obscure rather than clarify.
    * **NEVER** use Java Streams for operations that require mutable state, graph traversal, or
      side-effects. You **MUST** use explicit `for` or `while` loops.
- **Strict Utilities**:
    * **NEVER** use `System.currentTimeMillis()` or `System.nanoTime()`. You **MUST** use
      `Stopwatch`.
    * **NEVER** write raw `try-catch` blocks for simple IO closures if `IOUtil` can handle it.
    * **NEVER** write custom file-walking or path-manipulation logic in local classes. You **MUST**
      use or extend `FileUtil`.
- **Strings**:
    * **NEVER** use chained `+` concatenation for more than two parts. You **MUST** use
      `"%s.%s".formatted(...)`.
    * **DO NOT** use `StringBuilder` unless you are appending incrementally inside a loop.
- **General Rules**:
    * **NEVER** use `Optional<T>` as a class field or a method parameter. It **MUST ONLY** be used
      as a return type.
    * **NEVER** write comments that explain *WHAT* the code is doing. Only write comments if the
      *WHY* is highly unintuitive.
    * Always use `{}` braces on `if`/`else`, even for single-line bodies.
    * **Immutable Collections**: Methods **MUST** return or produce immutable collections (
      `List.of`, `Map.copyOf`, etc.). Any collection passed into a `record` constructor **MUST** be
      defensively copied to an immutable version inside the compact constructor (e.g.,
      `this.items = List.copyOf(items)`).
    * No nullable annotations (`@Nullable`, `@NonNull`).

## Markdown style

- Use semantic line breaks for prose: one sentence per line, or one natural clause per line for long
  sentences.
- Do not hard-wrap prose in the middle of a phrase only to satisfy a fixed column.
- Preserve code blocks, tables, property examples, and directory trees as-is.
- Prefer dedicated formatting-only commits when reflowing large existing documents.

---

## Logging

**`lathe-compiler`** — SLF4J logger.
Visible via Maven logging output.

- `DEBUG` — workspace root found/skipped, params written, copy duration
- `WARN` — post-compile, copy, and lock cleanup errors

**`lathe-maven-plugin`** — Mojo logger via `getLog()`.

**`lathe-server`** — JUL only.
`LATHE_DEBUG=1` enables `FINE` level.

| Level     | When to use                                                                                                                                                                                          |
|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SEVERE`  | Unrecoverable errors: compile/feature failures surfaced to the client, unexpected exceptions. Always include the `Throwable`.                                                                        |
| `WARNING` | Actionable problems the user should know about: no module found for an open file, workspace config missing.                                                                                          |
| `INFO`    | Lifecycle events that show the server is working: server start/stop, workspace load/reload, file open/close/save, compile result with diag count and elapsed time. One line per user-visible action. |
| `FINE`    | High-frequency or low-value detail: per-keystroke change events, format requests, classpath/option setup, individual diagnostic entries. Enabled only when `LATHE_DEBUG=1`.                          |
| `FINEST`  | Rarely needed deep internals. Avoid unless a feature explicitly calls for it.                                                                                                                        |

Guidelines:

- **CRITICAL**: Every single `LOG.info`, `LOG.warning`, and `LOG.fine` message **MUST** follow this
  exact format: `[operation] target detail outcome`.
- **Timing:** For async operations, heavy computations, or network/disk I/O, you **MUST** append the
  duration (`Xms`) to the end of the string. For instantaneous lifecycle events (e.g. server start),
  omit the timing.
- **NEVER** emit unstructured trace spam (e.g., `LOG.info("enter method")` or
  `LOG.info("[feature] called")`). All logs must be tied to a distinct, timed outcome.
- **NEVER** log raw file content, source code lines, or AST nodes at any log level.
- **INFO fires once per user action** — opening a file, saving, a compile completing. It must not
  fire on every keystroke (`onChange`) or on every request that may repeat rapidly.
- **Compile results always at INFO** — include mode tag, URI, elapsed ms, and diag count.
- **FINE for timed sub-operations** — hover resolution, definition lookup, completion steps.

Logger field name: `LOG` (static final).
Always use lambda form, single space after the operation tag:

```java
LOG.info(() ->"[open] %s".

formatted(uri));
    LOG.

info(() ->"[open] %s %dms diags=%d".

formatted(uri, t.elapsedMs(),diags.

size()));
    LOG.

fine(() ->"[change] %s".

formatted(uri));
    LOG.

log(Level.SEVERE, e, () ->"[save] failed for %s".

formatted(uri));
```

Message format: `[operation] uri-or-module detail Xms outcome`

---

## Testing

### Framework and style
- JUnit 5 + AssertJ in all modules; **NEVER** use Mockito inside `lathe-compiler`.
- Use `@TempDir` for all filesystem tests.
- **ALL** test method names **MUST** match `methodName_condition_result` with underscores.
  **NEVER** use camelCase for test names. (`resolve_missingJavaHome_returnsMissing`)
- **NEVER** use `@Nested` — flatten all tests into the top-level class with descriptive prefixed names.
- Always include both a positive case and a negative/edge case per behaviour.

### How to write a new test

Test utilities and fixtures evolve. **Before writing any new test, you MUST read at least 2 existing
test classes in the same package** to discover the current fixtures, mock setups, and patterns.
**NEVER** guess or invent new test infrastructure without checking existing tests first.

1. **Follow the nearest existing test pattern.** Open a neighbouring test, understand what fixture it
   uses, and mirror that approach. Do not introduce new boilerplate unless no existing pattern fits.

2. **Reuse the compilation pipeline — never replicate it.** `lathe-server` already has helpers that
   compile Java source and return an attributed AST. Before writing a `javac` call by hand, check
   what neighbouring tests do and use the same path.

3. **Never duplicate setup across test classes.** If multiple tests need the same compile or file
   setup, extract it into a shared helper in the same package or extend an existing base class.

4. **Prefer real objects over mocks.** Mock only at the boundary where a real object would require
   network I/O, a file-system side-effect, or disproportionate multi-class setup.

5. **Use `Mockito.verify(client, timeout(N))` instead of `Thread.sleep` for async assertions.**
   Hard-coded sleeps cause flakiness under load.

Every test module needs `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

`lathe-server` tests require a `LoggingConfig` JUnit extension registered via
`META-INF/services/org.junit.jupiter.api.extension.Extension`.
It loads the production `logging.properties` so log output is consistent during test runs.
