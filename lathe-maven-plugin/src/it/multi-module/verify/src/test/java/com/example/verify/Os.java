package com.example.verify;

import java.nio.file.Path;

// What the fixture's checks need to know about the OS they run on.
final class Os {

  static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private Os() {}

  // Lathe writes cmd scripts on Windows and sh scripts elsewhere.
  static String script(final String base) {
    return base + (WINDOWS ? ".cmd" : ".sh");
  }

  // A cmd launcher can't exec, so on Windows the JVM is its child and outlives it; left running, it
  // holds this fork's inherited output pipes open and the fixture build never ends.
  static void stop(final Process launcher) {
    launcher.descendants().forEach(ProcessHandle::destroyForcibly);
    launcher.destroyForcibly();
  }

  // A path as it reads inside a JSON string: a Windows path's backslashes are escaped.
  static String json(final Path path) {
    return path.toString().replace("\\", "\\\\");
  }
}
