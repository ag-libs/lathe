# Lathe — AI Agent Guide

Full design: `lathe-design-v3-latest.md`. Phase plan and implementation recipes: `lathe-plan.md`.
Before implementing any phase, read that phase's section in `lathe-plan.md`.

---

## Modules

| Module | Role |
|---|---|
| `lathe-core` | Shared utilities (property file helpers, future shared code); no external deps; has `module-info.java` |
| `lathe-compiler` | Plexus compiler SPI (`hint=lathe`); shades `lathe-core` |
| `lathe-maven-plugin` | `lathe:init` Mojo; owns all integration and e2e tests; shades `lathe-core` |
| `lathe-server` | LSP server; reads files produced by the other two; JPMS module; regular dep on `lathe-core` |

Build order: `lathe-core` → `lathe-compiler` → `lathe-server` → `lathe-maven-plugin`.

`lathe-compiler` and `lathe-maven-plugin` shade `lathe-core` so it does not appear as a transitive dependency for users. `lathe-server` declares it as a regular compile dependency.

- `groupId`: `io.github.ag-libs`
- Package root: `io.github.aglibs.lathe`
- Java 21: `<maven.compiler.release>21</maven.compiler.release>`
- Repo root: `/home/ag-libs/design/lathe`

---

## Build commands

```bash
mvn install -DskipTests                                         # build and install all modules
mvn verify -pl lathe-maven-plugin                               # all test layers
mvn verify -pl lathe-maven-plugin -DskipNeovimTests             # invoker only
mvn verify -pl lathe-maven-plugin -Dinvoker.test=jpms-project   # one invoker project
```

`spotless:check` runs on `verify` — run `spotless:apply` to fix formatting before committing.

---

## Coding style

- `final` on all fields, local variables, and parameters — omit only when reassignment is needed
- `var` unless the type is not obvious from the right-hand side
- Streams, lambdas, and method references over imperative loops
- Records for all value types (params, results, entries, config objects)
- `Optional<T>` as return type only — never as a field or parameter
- Immutable collections: `List.of`, `Map.of`, `Set.of`
- No nullable annotations (`@Nullable`, `@NonNull`, etc.)
- No comments unless the WHY is non-obvious

---

## Properties file format

All `.lathe/` property files are written and read with `java.util.Properties`.

Indexed lists use the pattern `key.0=`, `key.1=`, etc. Two shared helpers:

```java
static void putIndexed(Properties props, String key, List<?> values) {
    IntStream.range(0, values.size())
        .forEach(i -> props.setProperty(key + "." + i, values.get(i).toString()));
}

static List<String> readIndexed(Properties props, String key) {
    return IntStream.iterate(0, i -> props.containsKey(key + "." + i), i -> i + 1)
        .mapToObj(i -> props.getProperty(key + "." + i))
        .toList();
}
```

---

## Logging

**`lathe-compiler`** — Plexus logger via `getLogger()` from `AbstractCompiler`. Visible via `mvn -X`.
- `DEBUG` — workspace root found/skipped, params written, copy duration
- `WARN` — errors in finally copy block

**`lathe-maven-plugin`** — Mojo logger via `getLog()`.

**`lathe-server`** — JUL only. `LATHE_DEBUG=1` enables `FINE` level.
- `INFO` — server lifecycle only (startup, reload, shutdown)
- `FINE` — all timed operations

Logger field name: `LOG` (static final). Always use lambda form, single space after the operation tag:
```java
LOG.fine(() -> "[change] %-20s %-35s %dms".formatted(moduleRel, fileRel, ms(t)));
LOG.log(Level.SEVERE, e, () -> "[copy] %s failed".formatted(moduleRel));
```

Message format: `[operation] module-rel file-rel detail Xms outcome`

---

## Testing

- JUnit 5 + AssertJ in all modules
- Mockito available but **not used in `lathe-compiler`**
- `@TempDir` for all filesystem tests
- Always include both positive and negative cases
- Group related assertions — few meaningful test methods per class

Every test module needs `src/test/resources/junit-platform.properties`:
```properties
junit.jupiter.extensions.autodetection.enabled=true
```

`lathe-server` tests need a `LoggingConfig` JUnit extension (registered via `META-INF/services`) that loads the production `logging.properties` — see Phase 3 in `lathe-plan.md` for details.
