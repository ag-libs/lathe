package com.example.itsupport;

import java.nio.file.Path;

// What the fixture's checks need to know about the OS they run on.
public final class Os {

  public static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private Os() {}

  // Lathe writes cmd scripts on Windows and sh scripts elsewhere.
  public static String script(final String base) {
    return base + (WINDOWS ? ".cmd" : ".sh");
  }

  // A launcher the server under test installed (lathe-launcher, lathe-mcp-launcher), in the fixture's
  // Lathe cache.
  public static Path launcher(final String base) {
    return Path.of(System.getProperty("lathe.cache"))
        .resolve("servers")
        .resolve(System.getProperty("lathe.version"))
        .resolve(script(base));
  }

  // A cmd launcher can't exec, so on Windows the JVM is its child and outlives it; left running, it
  // holds this fork's inherited output pipes open and the fixture build never ends.
  public static void stop(final Process launcher) {
    launcher.descendants().forEach(ProcessHandle::destroyForcibly);
    launcher.destroyForcibly();
  }

  // A path as it reads inside a JSON string: a Windows path's backslashes are escaped.
  public static String json(final Path path) {
    return path.toString().replace("\\", "\\\\");
  }
}
