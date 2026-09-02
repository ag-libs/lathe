# Installing Lathe into a Maven build

Lathe is activated per Maven build in one of two ways:

1. **The Maven extension** (recommended) — register `lathe-maven-extension` once, either as a core
   extension in `.mvn/extensions.xml` or as a build extension in the reactor-root `pom.xml`. No other
   POM edits.
2. **Manual POM configuration** — declare the same wiring by hand. More verbose, but explicit and
   versioned in the POM.

Both produce the identical effective build.

> **Build from source.** Lathe is not yet published to a Maven repository, so build and install it
> first:
>
> ```bash
> git clone https://github.com/ag-libs/lathe.git
> cd lathe
> mvn install -DskipTests
> ```

## What Lathe needs in the build

Both methods add the same three pieces, for the whole reactor:

- the **`lathe-compiler` shim** on `maven-compiler-plugin`, selected by `compilerId=lathe`, for every
  module — it records the exact `javac` inputs the language server reads;
- the **`lathe-maven-plugin` goals** `init` (bound to `initialize`) and `sync` (bound to
  `process-test-classes`), once at the reactor root;
- the **`lathe-junit` test-scoped dependency**, for every module — it enables run/test capture.

Coordinates: groupId `io.github.ag-libs`; artifacts `lathe-compiler`, `lathe-maven-plugin`,
`lathe-junit`; use one Lathe version throughout.

## Method 1 — Maven extension (recommended)

Register `lathe-maven-extension` once, in either location. Both drive the same in-memory injection.

As a **core extension**, in `.mvn/extensions.xml` at the reactor root (the directory you run `mvn`
from) — the earliest, most reliable hook, with no `pom.xml` changes anywhere:

```xml
<extensions>
    <extension>
        <groupId>io.github.ag-libs</groupId>
        <artifactId>lathe-maven-extension</artifactId>
        <version>0.1.1</version>
    </extension>
</extensions>
```

Or as a **build extension**, in the reactor-root `pom.xml` — one POM edit, no `.mvn/` directory. It
must live in the reactor-root (or parent) POM to cover every module:

```xml
<build>
    <extensions>
        <extension>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-maven-extension</artifactId>
            <version>0.1.1</version>
        </extension>
    </extensions>
</build>
```

Before the build runs, the extension injects the three pieces into the effective model, in memory, for
every resolved project — including modules with their own compiler configuration or a separate parent
POM. All injected artifacts use the extension's own version, so they stay in lockstep with the version
you register, and a stale Lathe version already in a POM is overwritten.

## Method 2 — Manual POM configuration

Declare the same three pieces in your **parent `pom.xml`**. Pin one version with a property:

```xml
<properties>
    <lathe.version>0.1.1</lathe.version>
</properties>
```

**1. Compiler shim** — in the parent `<build><plugins>` so every module inherits it:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerId>lathe</compilerId>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-compiler</artifactId>
            <version>${lathe.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

**2. Init/sync goals** — at the reactor root only, so `<inherited>false</inherited>` keeps them from
running once per module. The goals carry their own default phases (`init` → `initialize`,
`sync` → `process-test-classes`), so no `<phase>` is needed:

```xml
<plugin>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-plugin</artifactId>
    <version>${lathe.version}</version>
    <inherited>false</inherited>
    <executions>
        <execution>
            <id>lathe-init</id>
            <goals><goal>init</goal></goals>
        </execution>
        <execution>
            <id>lathe-sync</id>
            <goals><goal>sync</goal></goals>
        </execution>
    </executions>
</plugin>
```

**3. Capture dependency** — in the parent `<dependencies>` so every module carries it at `test` scope:

```xml
<dependency>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-junit</artifactId>
    <version>${lathe.version}</version>
    <scope>test</scope>
</dependency>
```

Each block is easy to get subtly wrong — a wrong phase, a missing `inherited`, a `compilerId` typo, or
a module that overrides `maven-compiler-plugin` and drops the `compilerId` — which is exactly what the
extension removes. If a module declares its own compiler configuration, preserve `<compilerId>lathe</compilerId>`
there too.

## Initialize

With either method, generate the Lathe metadata. What gets written depends on how far the build runs:

| Command | Writes | Enables |
|---|---|---|
| `mvn process-test-classes` | compiler params, `workspace.json`, `main-launch.json` | LSP intelligence + `main` run/debug |
| `mvn test` (or `verify` / `install`) | the above **plus** each module's `test-launch.json` | + test run/debug |
| `mvn test -Dlathe.capture.only=true` | the same, **without executing the tests** | + test run/debug, fast |

**Recommended first run** — generate everything, without paying for a test run:

```bash
mvn clean test -Dlathe.capture.only=true
```

`-Dlathe.capture.only=true` registers a filter that excludes every test from execution while the fork
still writes its launch snapshot; `clean` is required on the first run so Maven compiles through the
Lathe shim rather than skipping up-to-date output. If your build sets
`<failIfNoTests>true</failIfNoTests>`, add `-DfailIfNoTests=false` so the test-free run stays green.

After that, any normal build (`mvn test` / `verify` / `install`) refreshes the templates automatically.
Add `.lathe/` to `.gitignore`.

## What and where Lathe writes

Lathe writes to two locations: `.lathe/` inside the project, and `~/.cache/lathe/` on the machine.

**In the project — `.lathe/`** (per-build, add to `.gitignore`):

- `lathe:init` creates `.lathe/` at the workspace root on the first build, at the `initialize` phase.
- The compiler shim writes compilation-parameter files under `.lathe/` as each module compiles — the
  classpath, module path, source roots, generated-source locations, annotation-processor settings, and
  other `javac` inputs the language server needs.
- `lathe:sync` writes `workspace.json` and each module's derived `main-launch.json`. The write is
  skipped when the content is unchanged, so a no-op build does not trigger a server reload.
- `lathe-junit` writes each module's `test-launch.json` from inside the Surefire fork during the `test`
  phase — so test run/debug needs a build that reaches `test` (see the table above and
  [test-capture.md](test-capture.md)).

**On the machine — `~/.cache/lathe/`** (machine-wide, regenerable, override with `-Dlathe.cache=<dir>`):

- `servers/<version>/` — the unpacked language server and its editor client; `current` symlinks the
  active version.
- `deps/` and `jdks/` — dependency and JDK **source** trees. `lathe:sync` resolves each dependency's
  `-sources` JAR through Maven and extracts it here, and extracts the JDK's sources when available;
  these back go-to-definition into library and JDK code. A dependency with no published `-sources` JAR
  is recorded as missing and simply not navigable.
- `type-index/` — the symbol index behind workspace symbol search.

Everything under `~/.cache/lathe/` is derived and safe to delete; the next `sync`/build rebuilds what
it needs.

## Verify

After the recommended first run above, confirm `.lathe/` exists at the reactor root and that each
module directory holds its parameter files plus a `test-launch.json` (proof that capture ran). If
`.lathe/` is missing, see the README **Troubleshooting** section (and confirm you are running `mvn`
from the directory that contains `.mvn/`).
