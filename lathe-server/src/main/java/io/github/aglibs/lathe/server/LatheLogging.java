package io.github.aglibs.lathe.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Shared logging setup for the server entry points (editor and MCP): load the bundled config
 * (ConsoleHandler → stderr) and, when {@code LATHE_DEBUG} is set, raise the lathe logger to FINE.
 */
public final class LatheLogging {

  private static final String LATHE_LOGGER = "io.github.aglibs.lathe";
  // Held so the filter set on it outlives a collection of loggers nothing else references.
  private static final Logger RPC_LOGGER =
      Logger.getLogger("org.eclipse.lsp4j.jsonrpc.RemoteEndpoint");
  private static final String UNMATCHED_CANCEL = "Unmatched cancel notification";

  private LatheLogging() {}

  public static void init() {
    try (final var config = LatheLogging.class.getResourceAsStream("/logging.properties")) {
      if (config != null) {
        LogManager.getLogManager().readConfiguration(config);
      }
    } catch (final IOException e) {
      System.err.printf("[lathe] failed to load logging config: %s%n", e.getMessage());
    }

    // The config's lathe level binds only to a logger that exists; a caller's static LOG predates
    // this call, so without creating the parent here its first lines fall back to root WARNING.
    final var lathe = Logger.getLogger(LATHE_LOGGER);
    final boolean debug = System.getenv("LATHE_DEBUG") != null;
    if (debug) {
      lathe.setLevel(Level.FINE);
    }

    RPC_LOGGER.setFilter(record -> shown(record, debug));
  }

  // lsp4j warns when a $/cancelRequest names a request it has already answered, which the protocol
  // allows: an editor cancels a request still pending on its side while the reply is on its way,
  // as Neovim does when typing cancels completion and semantic tokens. Shown only when debugging;
  // lsp4j's other warnings stay.
  static boolean shown(final LogRecord record, final boolean debug) {
    final String message = record.getMessage();
    return debug || message == null || !message.startsWith(UNMATCHED_CANCEL);
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
