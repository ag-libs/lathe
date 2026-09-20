package io.github.aglibs.lathe.server;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Shared logging setup for the server entry points (editor and MCP): load the bundled config
 * (ConsoleHandler → stderr) and, when {@code LATHE_DEBUG} is set, raise the lathe logger to FINE.
 */
public final class LatheLogging {

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
      Logger.getLogger("io.github.aglibs.lathe").setLevel(Level.FINE);
    }
  }
}
