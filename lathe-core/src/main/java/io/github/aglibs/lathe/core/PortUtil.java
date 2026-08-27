package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.net.ServerSocket;

/** Loopback TCP port helper: allocate a free ephemeral port. */
public final class PortUtil {

  private PortUtil() {}

  /** An OS-assigned free ephemeral port. */
  public static int free() throws IOException {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
