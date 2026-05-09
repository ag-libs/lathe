package io.github.aglibs.lathe.server;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class LoggingConfig implements BeforeAllCallback {

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    try (final var is = LoggingConfig.class.getResourceAsStream("/logging.properties")) {
      LogManager.getLogManager().readConfiguration(is);
    }
    Logger.getLogger("").getHandlers()[0].setLevel(Level.ALL);
    Logger.getLogger("io.github.aglibs.lathe").setLevel(Level.FINE);
  }
}
