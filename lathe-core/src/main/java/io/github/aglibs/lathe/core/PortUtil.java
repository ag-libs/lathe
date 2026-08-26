package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Loopback TCP port helpers: allocate a free ephemeral port and wait for one to start accepting.
 */
public final class PortUtil {

  private static final long POLL_INTERVAL_MS = 50;
  private static final int PROBE_TIMEOUT_MS = 200;

  private PortUtil() {}

  /** An OS-assigned free ephemeral port. */
  public static int free() throws IOException {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * Blocks until the loopback {@code port} accepts a TCP connection (returns {@code true}) or
   * {@code timeoutMs} elapses (returns {@code false}).
   */
  public static boolean awaitAccepting(final int port, final long timeoutMs) {
    final var timer = Stopwatch.start();
    while (timer.elapsedMs() < timeoutMs) {
      if (accepting(port)) {
        return true;
      }

      sleep();
    }

    return false;
  }

  private static boolean accepting(final int port) {
    try (final var probe = new Socket()) {
      probe.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_TIMEOUT_MS);
      return true;
    } catch (final IOException ignored) {
      return false;
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(PortUtil.POLL_INTERVAL_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for a port", e);
    }
  }
}
