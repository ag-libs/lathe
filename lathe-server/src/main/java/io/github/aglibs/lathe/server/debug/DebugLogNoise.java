package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.Configuration;
import com.sun.jdi.VMDisconnectedException;
import java.util.logging.Filter;
import java.util.logging.Logger;

/**
 * Silences the Microsoft java-debug library's session-teardown noise. When the debuggee disconnects
 * at the end of a session, the library's telemetry ({@code UsageDataSession}) still inspects the
 * JDI event and logs it at SEVERE with a {@link VMDisconnectedException} on the {@code java-debug}
 * logger. That is expected teardown, not an error, so drop exactly those records while leaving
 * every other java-debug log intact.
 */
final class DebugLogNoise {

  private DebugLogNoise() {}

  static void suppressDisconnectNoise() {
    final Logger javaDebug = Logger.getLogger(Configuration.LOGGER_NAME);
    javaDebug.setFilter(filter(javaDebug.getFilter()));
  }

  static Filter filter(final Filter previous) {
    return record ->
        !(record.getThrown() instanceof VMDisconnectedException)
            && (previous == null || previous.isLoggable(record));
  }
}
