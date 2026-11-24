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

2. Run the sync from the checkout root.
   Sync writes a `.gitignore` inside `.lathe/`, so git ignores the directory with no change to the checkout.

   ```sh
   mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
   ```

   If you have several configured builds, add `-Dlathe.buildDir=build/<conf>` to select one.
   The sync does nothing when `-Dlathe.disabled=true` is set, or when the `CI` environment variable is set unless `-Dlathe.disabled=false` is passed; it says so in its `[sync] disabled` line.
   Sync writes the workspace under `.lathe/`, installs the server under `~/.cache/lathe/`, and records the JDK you built in `.lathe/java-home`.

3. Open `src/java.base/share/classes/java/util/ArrayList.java` in your editor's Lathe client.
   Follow `AbstractList` to its declaration to start exploring.
   Ordinary Java source edits are analyzed live.
   [When to sync again](#re-syncing)

## What you get

Lathe provides diagnostics, completion, hover, and workspace symbol search for the modules you built and the Java tools under `make/`.
Navigation and references work across module boundaries, including internal packages and generated sources.
The server uses the module graph and compiler flags from your chosen build.

## Re-syncing

| Change | Re-sync? |
|---|---|
| Edit / add / delete a `.java` in a built module | No — live |
| Edit `module-info`, add a module, regenerate gensrc | Yes |
| Rebuild a module and want its symbols current | Yes |

Lathe never runs `make`.

## Current limits

Run jtreg tests with the OpenJDK build.
Lathe currently provides code intelligence for built modules and their platform sources.
Editing jtreg tests, debugging them, and analyzing source overlays for other platforms are outside that support.

## See also

- [How Lathe works](how-it-works.md)
- [Design: OpenJDK support](../done/lathe-openjdk-support.md)
