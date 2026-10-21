package com.example.verify;

// Lathe writes cmd scripts on Windows and sh scripts elsewhere.
final class Scripts {

  static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private Scripts() {}

  static String name(final String base) {
    return base + (WINDOWS ? ".cmd" : ".sh");
  }
}
