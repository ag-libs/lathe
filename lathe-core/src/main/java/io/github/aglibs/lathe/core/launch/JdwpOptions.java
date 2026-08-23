package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * The debug mode of a launch. When {@link #enabled()}, the launched JVM opens a suspended JDWP
 * server agent on {@code port} for the debug adapter to attach to
 * (docs/planned/lathe-debug-support.md §5); {@link #NONE} is a plain run. Mirrors the {@link
 * LaunchOverlay#NONE} convention so a launch always carries a value rather than a nullable flag.
 */
public record JdwpOptions(int port) {

  /** A normal, non-debug run. */
  public static final JdwpOptions NONE = new JdwpOptions(0);

  public JdwpOptions {
    ValidCheck.check().assertTrue(port >= 0 && port <= 65_535, "port").validate();
  }

  public boolean enabled() {
    return port > 0;
  }

  public String agentArg() {
    return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:%d"
        .formatted(port);
  }
}
