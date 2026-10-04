# Lathe OpenJDK demo

A single ~30s hero clip showing Lathe's code intelligence on the **real OpenJDK source**, built on the
JDK's own `javac`. The published artifact is the content-hashed `docs/openjdk-demo-<hash>.gif`, embedded
in [`docs/guide/openjdk.md`](../../docs/guide/openjdk.md).

## What it shows

The cut: a **title card**, one calm Neovim thread that starts from an empty, Lathe-initialized editor
in the JDK folder, then an **end card** (`title-card.ass` / `end-card.ass`, rendered as flat text over
the terminal background).

1. **Workspace symbol search** — from a blank editor, `\ws` finds `HttpHeaders` across the whole JDK
   and opens `java.net.http/.../HttpHeaders.java`.
2. **Completion** — add a `count()` method; `map().` completes `size()` from the build's classpath, valid.
3. **Live diagnostics** — breaking the method (`int bad = map();`) is flagged instantly by `javac`, then undone.
4. **Cross-module go-to-definition** — `gd` on `Map` jumps into `java.base` (the finale).

A blank `nvim` has no `java` filetype to auto-start Lathe, so the tape's hidden setup runs
`:LatheStart` to initialize it on the JDK workspace and warm the index before recording.

## Prerequisites

An **already built + synced** OpenJDK checkout (this demo never builds or syncs the JDK). Prepare it
once per [`docs/guide/openjdk.md`](../../docs/guide/openjdk.md):

```sh
bash configure --with-boot-jdk=/path/to/jdk-N && make jdk
echo '.lathe/' >> .git/info/exclude
mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
```

Also needed on `PATH`: `nvim` (0.12+), `vhs`, `ffmpeg`.

## Record

```sh
./dev/demo-openjdk/prepare.sh                                   # copy + warm the isolated nvim config
LATHE_OPENJDK_DIR=~/git/jdk ./dev/demo-openjdk/record.sh        # -> docs/openjdk-demo-<hash>.gif
```

`LATHE_OPENJDK_DIR` points at your checkout (default `~/git/jdk`). `record.sh` publishes the GIF under a
content-hashed name (busting browser/CDN caches) and rewrites the embed in `docs/guide/openjdk.md` to
match. Sleeps in `openjdk.tape` are tuned against the recorded clip — re-tune if the JDK, server, or
host timing changes.
