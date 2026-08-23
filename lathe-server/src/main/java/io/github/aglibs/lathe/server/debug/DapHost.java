package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.ProtocolServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts the Microsoft java-debug {@link ProtocolServer} in-process. It opens a loopback DAP socket
 * and, when the editor's debug client connects, bridges that connection to a {@link
 * LatheProviderContext} on a session thread — no separate adapter process (docs/planned/
 * lathe-debug-support.md §6). Phase 0 proves the adapter runs under JPMS and answers the DAP
 * handshake; launching and attaching to a suspended debuggee arrives in Phase 1.
 */
public final class DapHost {

  private static final Logger LOG = Logger.getLogger(DapHost.class.getName());

  private final ServerSocket serverSocket;

  private DapHost(final ServerSocket serverSocket) {
    this.serverSocket = serverSocket;
  }

  public static DapHost start() throws IOException {
    final var serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    final var host = new DapHost(serverSocket);
    final int port = host.port();
    final var thread = new Thread(host::serve, "lathe-dap-%d".formatted(port));
    thread.setDaemon(true);
    thread.start();
    LOG.info(() -> "[debug] dap host listening port=%d".formatted(port));
    return host;
  }

  public int port() {
    return serverSocket.getLocalPort();
  }

  public void close() throws IOException {
    serverSocket.close();
  }

  private void serve() {
    try (final Socket client = serverSocket.accept()) {
      final var protocolServer =
          new ProtocolServer(
              client.getInputStream(), client.getOutputStream(), new LatheProviderContext());
      protocolServer.run();
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[debug] dap session ended");
    }
  }
}
