package io.github.aglibs.lathe.itsupport;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// What the fixture's checks need to know about the OS they run on.
public final class Os {

  public static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private static final long STOP_GRACE_SECONDS = 5;

  private Os() {}

  // Lathe writes cmd scripts on Windows and sh scripts elsewhere.
  public static String script(final String base) {
    return base + (WINDOWS ? ".cmd" : ".sh");
  }

  // A launcher the server under test installed (lathe-launcher, lathe-mcp-launcher), in the
  // Lathe cache the build under test used (lathe.cache).
  public static Path launcher(final String base) {
    return Path.of(System.getProperty("lathe.cache"))
        .resolve("servers")
        .resolve(System.getProperty("lathe.version"))
        .resolve(script(base));
  }

  // A cmd launcher can't exec, so on Windows the JVM is its child and outlives it; left running, it
  // holds this fork's inherited output pipes open and the fixture build never ends. The tree is
  // asked to stop first, so a JVM's shutdown hooks run (a coverage agent writes its data in one),
  // and forced after a grace period; on Windows asking is already forcing.
  public static void stop(final Process launcher) {
    final List<ProcessHandle> tree =
        Stream.concat(launcher.descendants(), Stream.of(launcher.toHandle())).toList();
    tree.forEach(ProcessHandle::destroy);
    CompletableFuture.allOf(
            tree.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
        .completeOnTimeout(null, STOP_GRACE_SECONDS, TimeUnit.SECONDS)
        .join();
    tree.forEach(ProcessHandle::destroyForcibly);
  }

  // A path as it reads inside a JSON string: a Windows path's backslashes are escaped.
  public static String json(final Path path) {
    return path.toString().replace("\\", "\\\\");
  }
}
