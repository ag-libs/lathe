# Lathe for OpenJDK

Lathe provides code intelligence — go-to-definition, find-references, completion, diagnostics, hover,
and workspace symbol search — for the OpenJDK source, in any LSP editor. It is built on the JDK's own
`javac`, so analysis matches what the compiler sees.

This front-end reads the **`make` build** instead of Maven: the per-module `javac` invocation the build
ran, and the exploded JDK you built. The editor uses the build's own configuration — the same module
graph, the same `--add-exports` / `--patch-module` flags, the same language level (preview included).

## Setup

Prerequisites: a buildable JDK checkout, and `mvn` on `PATH` (any stable JDK runs Maven).

```sh
bash configure --with-boot-jdk=/path/to/jdk-N   # if not already configured
make jdk                                         # build the exploded image
echo '.lathe/' >> .git/info/exclude              # local state, keep out of git
mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
```

`sync` reads the build, writes `.lathe/` in the checkout, and installs the server into
`~/.cache/lathe/`. It discovers `build/<conf>` automatically (`-Dlathe.buildDir=build/<conf>` to pick
one). Then open any source — e.g. `src/java.base/share/classes/java/util/ArrayList.java` — in your
editor's Lathe client; the server runs on the JDK you built via `.lathe/java-home`.

File edits are analysed live; re-run `sync` only on build-shape changes (below).

## What you get

- Diagnostics matching the build — the real module graph and compiler flags.
- Go-to-definition and find-references across all ~66 modules and into the JDK's own source (incl.
  `sun.*` internals and generated sources), resolving to real files.
- Completion, hover, and signature help over the module graph.
- Workspace symbol search, including types in just-created, not-yet-built files.
- The `make/` build tools, not only the modules.
- Constant per-keystroke cost regardless of JDK size — dependencies come from the build's compiled
  output, not re-parsed source.

## Re-syncing

| Change | Re-sync? |
|---|---|
| Edit / add / delete a `.java` in a built module | No — live |
| Edit `module-info`, add a module, regenerate gensrc | Yes |
| Rebuild a module and want its symbols current | Yes |

Lathe never runs `make`.

## Compared to IntelliJ IDEA / Eclipse

The IDEs are full environments and run and debug jtreg tests, which Lathe does not yet. Lathe is
lighter, editor-agnostic, and reads the build directly:

| | IntelliJ / Eclipse | Lathe |
|---|---|---|
| Analysis engine | Each IDE's own front-end | The build's `javac` |
| Preview / new syntax | Can trail the in-tree compiler | Tracks the build's compiler |
| Module flags | Worked out by the IDE | Taken from the build |
| Setup | Project import | One `sync` over the `make` output |
| Run / debug jtreg | Supported | Not yet |

## Not supported yet

- jtreg tests — running, debugging, and `test/` analysis. Planned: derive a test's config from its
  `@test` / `@library` / `@modules` tags on open.
- Other-platform source overlays, and modules that were not built.

## See also

- [How Lathe works](how-it-works.md)
- [Design: OpenJDK support](../planned/lathe-openjdk-support.md)
