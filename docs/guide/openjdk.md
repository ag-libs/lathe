# Lathe for OpenJDK

Open the JDK sources in your editor and follow a call across module boundaries, including into internal packages and generated code.
Lathe reads each module's compiler invocation from your OpenJDK `make` build.
That gives it the same module graph, language level, preview settings, and `--patch-module` and `--add-exports` flags.

![Lathe on the OpenJDK source — whole-JDK symbol search, completion, live diagnostics, and cross-module go-to-definition](../openjdk-demo-ecd74b0f.gif)

<!-- The GIF filename carries a content hash (openjdk-demo-<hash>.gif) so browsers never serve a stale
     cached copy: dev/demo-openjdk/record.sh regenerates it, renames it by content hash, deletes the
     previous one, and rewrites the link above. Do not hand-edit the hash. Recorded against a built +
     synced openjdk/jdk checkout. -->

## Setup

Use a buildable OpenJDK checkout and Maven 3.6.3 or later running on JDK 17 or newer.

1. Configure the checkout if needed and build the JDK you want to edit.

   ```sh
   bash configure --with-boot-jdk=/path/to/jdk-N
   make jdk
   ```

2. Add `.lathe/` to `.git/info/exclude` and run the sync from the checkout root.

   ```sh
   mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
   ```

   If you have several configured builds, add `-Dlathe.buildDir=build/<conf>` to select one.
   Sync writes the workspace under `.lathe/`, installs the server under `~/.cache/lathe/`, and records the JDK you built in `.lathe/java-home`.

3. Open `src/java.base/share/classes/java/util/ArrayList.java` in your editor's Lathe client.
   Follow `AbstractList` to its declaration to start exploring.
   Ordinary Java source edits are analyzed live. [When to sync again](#re-syncing)

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
- [Design: OpenJDK support](../done/lathe-openjdk-support.md)
