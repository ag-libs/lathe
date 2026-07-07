package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheBuildInfo;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public final class LatheServer {

  private static final Logger LOG = Logger.getLogger(LatheServer.class.getName());

  public static void main(final String[] ignored) throws Exception {
    loadLoggingConfig();
    if (System.getenv("LATHE_DEBUG") != null) {
      Logger.getLogger("io.github.aglibs.lathe").setLevel(Level.FINE);
    }

    LOG.info(
        () -> "[startup] server %s starting".formatted(LatheBuildInfo.summary(LatheServer.class)));
    run(System.in, acquireStdout());
  }

  static void run(final InputStream in, final OutputStream out)
      throws ExecutionException, InterruptedException {
    final var server = new LatheLanguageServer();
    final ExecutorService rpcExecutor = Executors.newCachedThreadPool(LatheServer::newRpcThread);
    final Launcher<LanguageClient> launcher =
        LSPLauncher.createServerLauncher(server, in, out, rpcExecutor, consumer -> consumer);
    server.connect(launcher.getRemoteProxy());
    final Future<?> listening = launcher.startListening();
    LOG.info(() -> "[startup] Lathe language server ready");
    try {
      listening.get();
    } finally {
      server.shutdown().join();
      rpcExecutor.shutdownNow();
      LOG.info(() -> "[shutdown] Lathe language server stopped");
    }
  }

  private static Thread newRpcThread(final Runnable task) {
    return new Thread(task, "lathe-jsonrpc");
  }

  private static PrintStream acquireStdout() {
    final var out = System.out;
    System.setOut(System.err);
    return out;
  }

  private static void loadLoggingConfig() {
    try (final var is = LatheServer.class.getResourceAsStream("/logging.properties")) {
      if (is != null) {
        LogManager.getLogManager().readConfiguration(is);
      }
    } catch (final Exception e) {
      System.err.printf("[lathe] failed to load logging config: %s%n", e.getMessage());
      e.printStackTrace(System.err);
    }
  }
}
