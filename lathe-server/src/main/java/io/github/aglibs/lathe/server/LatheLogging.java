package io.github.aglibs.lathe.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Shared logging setup for the server entry points (editor and MCP): load the bundled config
 * (ConsoleHandler → stderr) and, when {@code LATHE_DEBUG} is set, raise the lathe logger to FINE.
 */
public final class LatheLogging {

  private static final String LATHE_LOGGER = "io.github.aglibs.lathe";

  private LatheLogging() {}

  public static void init() {
    try (final var config = LatheLogging.class.getResourceAsStream("/logging.properties")) {
      if (config != null) {
        LogManager.getLogManager().readConfiguration(config);
      }
    } catch (final IOException e) {
      System.err.printf("[lathe] failed to load logging config: %s%n", e.getMessage());
    }

    if (System.getenv("LATHE_DEBUG") != null) {
      Logger.getLogger(LATHE_LOGGER).setLevel(Level.FINE);
    }
  }

  // Attach an appending file sink beside the console one, so the server's logs land in a fixed,
  // agent-independent place regardless of where the host parks stderr. Formatter/level come from
  // logging.properties (SimpleFormatter). An IO failure degrades to console-only, never throws.
  public static void initFile(final Path logFile) {
    try {
      Files.createDirectories(logFile.getParent());
      final var handler = new FileHandler(logFile.toString(), true);
      Logger.getLogger(LATHE_LOGGER).addHandler(handler);
    } catch (final IOException e) {
      System.err.printf("[lathe] failed to open log file %s: %s%n", logFile, e.getMessage());
    }
  }
}
