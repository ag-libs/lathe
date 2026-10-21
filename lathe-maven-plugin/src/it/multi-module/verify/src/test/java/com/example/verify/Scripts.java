package com.example.verify;

// Lathe writes cmd scripts on Windows and sh scripts elsewhere.
final class Scripts {

  static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private Scripts() {}

  static String name(final String base) {
    return base + (WINDOWS ? ".cmd" : ".sh");
  }

  // A cmd launcher can't exec, so on Windows the JVM is its child and outlives it; left running, it
  // holds this fork's inherited output pipes open and the fixture build never ends.
  static void stop(final Process launcher) {
    launcher.descendants().forEach(ProcessHandle::destroyForcibly);
    launcher.destroyForcibly();
  }
}
