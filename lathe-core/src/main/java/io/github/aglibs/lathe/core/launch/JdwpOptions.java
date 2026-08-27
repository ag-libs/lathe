package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * The debug mode of a launch. When {@link #enabled()}, the launched JVM opens a suspended JDWP
 * server agent on {@code port} for the debug adapter to attach to; {@link #NONE} is a plain run.
 * Mirrors the {@link LaunchOverlay#NONE} convention so a launch always carries a value rather than
 * a nullable flag.
 */
public record JdwpOptions(int port) {

  private static final String TRANSPORT = "dt_socket";

  /** A normal, non-debug run. */
  public static final JdwpOptions NONE = new JdwpOptions(0);

  public JdwpOptions {
    ValidCheck.check().assertTrue(port >= 0 && port <= 65_535, "port").validate();
  }

  public boolean enabled() {
    return port > 0;
  }

  public String agentArg() {
    return "-agentlib:jdwp=transport=%s,server=y,suspend=y,address=127.0.0.1:%d"
        .formatted(TRANSPORT, port);
  }

  /**
   * Whether {@code line} is the JVM's "agent is listening" banner for this launch's port. The JDWP
   * agent prints it to stdout the moment its socket is ready to accept the debugger, so it is the
   * signal to attach -- used to gate the debug attach on the banner instead of a throwaway TCP
   * probe the agent would misread as a failed debugger handshake.
   */
  public boolean isListeningLine(final String line) {
    return line.contains("Listening for transport %s at address:".formatted(TRANSPORT))
        && line.strip().endsWith(Integer.toString(port));
  }
}
