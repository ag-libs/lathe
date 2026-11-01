package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheBuildInfo;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.TextDocumentService;

public final class LatheServer {

  private static final Logger LOG = Logger.getLogger(LatheServer.class.getName());
  private static final String LSP4J_SERVICES = TextDocumentService.class.getPackageName();

  public static void main(final String[] ignored) throws Exception {
    LatheLogging.init();
    ChildProcesses.destroyOnExit();

    LOG.info(
        () -> "[startup] server %s starting".formatted(LatheBuildInfo.summary(LatheServer.class)));
    run(System.in, acquireStdout());
  }

  static void run(final InputStream in, final OutputStream out)
      throws ExecutionException, InterruptedException {
    final var server = new LatheLanguageServer();
    final ExecutorService rpcExecutor = Executors.newCachedThreadPool(LatheServer::newRpcThread);
    final Launcher<LatheLanguageClient> launcher =
        new Launcher.Builder<LatheLanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(LatheLanguageClient.class)
            .setInput(in)
            .setOutput(out)
            .setExecutorService(rpcExecutor)
            .setExceptionHandler(LatheServer::responseError)
            .create();
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

  // lsp4j's service interfaces answer every request Lathe does not implement by throwing from a
  // default method, which lsp4j reports as an internal error with a SEVERE stack trace; the
  // protocol's answer is "method not found".
  static ResponseError responseError(final Throwable error) {
    final StackTraceElement[] stack = error.getStackTrace();
    if (!(error instanceof UnsupportedOperationException)
        || stack.length == 0
        || !stack[0].getClassName().startsWith(LSP4J_SERVICES)) {
      return RemoteEndpoint.DEFAULT_EXCEPTION_HANDLER.apply(error);
    }

    final String method = stack[0].getMethodName();
    LOG.info(() -> "[request] %s not supported, answered method not found".formatted(method));
    return new ResponseError(
        ResponseErrorCode.MethodNotFound, "Unsupported method: %s".formatted(method), null);
  }

  private static Thread newRpcThread(final Runnable task) {
    return new Thread(task, "lathe-jsonrpc");
  }

  private static PrintStream acquireStdout() {
    final var out = System.out;
    System.setOut(System.err);
    return out;
  }
}
