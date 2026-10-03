# Lathe for OpenJDK developers

Lathe gives you the kind of code help an IDE provides — jump to definition, autocomplete, error
checking, hover docs, and search for any type — while you work on the JDK itself, in **any** editor
that speaks LSP (Neovim, VS Code, Emacs, Helix, and more).

It works by reading your **real `make` build**: the exact `javac` command the build ran for each
module, and the JDK you built.
Because of that, the editor and the build always agree — the same module boundaries, the same
`--add-exports` / `--patch-module` flags, the same language level, preview features included.

## In one line

Lathe checks your code with the **real `javac`** from the JDK you just built, so what you see in the
editor matches the build exactly — preview and brand-new syntax included.

## Setup

You need a JDK checkout you can build, and `mvn` on your `PATH` (any stable JDK can run Maven — it
does not have to be the one you are building).

1. **Build the JDK the normal way.**

   ```sh
   bash configure --with-boot-jdk=/path/to/jdk-N
   make jdk
   ```

2. **Run the sync** from the checkout root.
   It reads the build, writes a local `.lathe/` folder, and installs the language server into
   `~/.cache/lathe/`:

   ```sh
   echo '.lathe/' >> .git/info/exclude        # it's local — keep it out of git
   mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
   ```

   It finds your `build/<conf>` on its own (add `-Dlathe.buildDir=build/<conf>` if you have more than
   one). You should see `66 module(s) + 9 tool(s)`.

3. **Open the checkout in your editor** with its Lathe client, and open any JDK file — say
   `src/java.base/share/classes/java/util/ArrayList.java`.
   The server runs on the JDK you built, so the language level lines up.

Then just edit. You only re-run the sync when the build *shape* changes — see
[Keeping it fresh](#keeping-it-fresh).

## What you get

- **Errors that match the build.** The real module graph and compiler flags are used, so you don't see
  made-up errors from the editor and the build disagreeing about what's visible.
- **Jump around the whole JDK.** Go to definition and find usages work across all ~66 modules and into
  the JDK's own source — `java.desktop` into `java.base`, into `sun.*` internals, into generated code —
  landing on real files, not stubs.
- **Autocomplete, hover docs, and parameter hints** over the real module graph.
- **Search for any type by name** across the JDK — including types in files you *just created*, before
  they are built.
- **The `make/` build tools too**, not only the modules.
- **Fast, and it stays fast.** After the first open, each keystroke re-checks the file you are editing
  in a few tens of milliseconds, no matter how big the JDK is.
- **Your editor.** It is a plain language server — use whatever LSP client you like.

## Keeping it fresh

| You did this | Re-run `sync`? |
|---|---|
| Edit, add, or delete a `.java` in a module you built | **No** — picked up live |
| Edit `module-info`, add a module, or regenerate gensrc | **Yes** |
| Rebuild a module (`make <module>-java-only`) and want its symbols current | **Yes** |

The rule of thumb: *file edits are live; build-shape changes need a quick `sync`.*
Lathe never runs `make` for you.

## How it compares to IntelliJ IDEA / Eclipse

IntelliJ IDEA and Eclipse are mature, full IDEs with strong JDK support — including one thing Lathe
cannot do yet: running and debugging jtreg tests.
Lathe is not trying to replace them.
It is a lighter option that works in any editor and makes a different trade-off, and it is fine to use
both.

| | IntelliJ / Eclipse | Lathe |
|---|---|---|
| Analysis engine | Each IDE's own Java front-end | The **real `javac`** from your built JDK |
| Preview & brand-new syntax | Can trail the in-tree compiler | Tracks the build's compiler |
| Module flags (`--add-exports`, patches) | Worked out by the IDE | Taken straight from the build |
| Setup | Full project import | One `sync`; reuses the `make` output |
| Editor | The IDE itself | Any LSP editor |
| Run / debug jtreg | ✅ supported | ⏳ not yet (planned) |
| Footprint | A full IDE's index and build | Light — no second build, no copied bytecode |

In short: the IDEs give you a complete, ready-to-go setup (test running included); Lathe gives you
build-exact code help in whatever editor you already use, with very little setup.
For many people the two work well side by side.

## Not there yet

- **Running and debugging tests (jtreg)**, and code help for `test/` files — planned.
  The idea: build the config for a test file from its `@test` / `@library` / `@modules` tags the moment
  you open it.
- Sources for other operating systems (e.g. the `windows` files on a Linux build), and modules you did
  not build — build the module and it is covered.

## See also

- [How Lathe works](how-it-works.md) — the compiler-driven design.
- [Design: OpenJDK support](../planned/lathe-openjdk-support.md) — why it works the way it does.
