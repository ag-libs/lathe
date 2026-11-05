package io.github.aglibs.lathe.analysis.testing;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

// Lathe's loggers at FINE in every test, so each LOG.fine(() -> ...) message is built and a bad
// format fails a test. With the server's logging.properties on the classpath (lathe-server's tests)
// it is also the output format and every record is printed; without it (lathe-analysis's) only the
// console's default INFO is printed.
public final class LoggingConfig implements BeforeAllCallback {

  private static boolean configured;

  // Once per JVM: re-reading the configuration resets every logger, which would drop the handlers
  // that test classes running in parallel have attached.
  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    synchronized (LoggingConfig.class) {
      if (configured) {
        return;
      }

      try (final var is = LoggingConfig.class.getResourceAsStream("/logging.properties")) {
        if (is != null) {
          LogManager.getLogManager().readConfiguration(is);
          Logger.getLogger("").getHandlers()[0].setLevel(Level.ALL);
        }
      }

      Logger.getLogger("io.github.aglibs.lathe").setLevel(Level.FINE);
      configured = true;
    }
  }
}
