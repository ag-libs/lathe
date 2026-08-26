package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.IProviderContext;
import com.microsoft.java.debug.core.adapter.ProtocolServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts the Microsoft java-debug {@link ProtocolServer} in-process. It opens a loopback DAP socket
 * and, when the editor's debug client connects, bridges that connection to the given provider
 * context on a session thread — no separate adapter process. A debug session passes a context wired
 * to the launched module (source lookup + VM manager); the handshake path passes a bare context.
 *
 * <p>When the client disconnects, {@code onSessionEnd} runs. The debuggee is a replay Lathe owns
 * (launch semantics), so the session context terminates it there rather than leaving it running as
 * an attach client normally would.
 */
public final class DapHost {

  private static final Logger LOG = Logger.getLogger(DapHost.class.getName());

  private final ServerSocket serverSocket;
  private final IProviderContext context;
  private final Runnable onSessionEnd;

  private DapHost(
      final ServerSocket serverSocket,
      final IProviderContext context,
      final Runnable onSessionEnd) {
    this.serverSocket = serverSocket;
    this.context = context;
    this.onSessionEnd = onSessionEnd;
  }

  public static DapHost start(final IProviderContext context, final Runnable onSessionEnd)
      throws IOException {
    final var serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    final var host = new DapHost(serverSocket, context, onSessionEnd);
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
          new ProtocolServer(client.getInputStream(), client.getOutputStream(), context);
      protocolServer.run();
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[debug] dap session ended");
    } finally {
      onSessionEnd.run();
    }
  }
}
