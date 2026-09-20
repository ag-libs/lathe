package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.run.TestEventParams;
import io.github.aglibs.lathe.server.run.TestFinishedParams;
import io.github.aglibs.lathe.server.run.TestOutputParams;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;

/**
 * A {@link LatheLanguageClient} that discards every server-to-client message. Used by {@link
 * LatheEngine}, which drives the same analysis the LSP handlers do but reads results directly
 * rather than over JSON-RPC, so nothing needs to reach a remote client.
 */
final class NoopLanguageClient implements LatheLanguageClient {

  @Override
  public void telemetryEvent(final Object object) {}

  @Override
  public void publishDiagnostics(final PublishDiagnosticsParams diagnostics) {}

  @Override
  public void showMessage(final MessageParams messageParams) {}

  @Override
  public CompletableFuture<MessageActionItem> showMessageRequest(
      final ShowMessageRequestParams requestParams) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void logMessage(final MessageParams message) {}

  @Override
  public CompletableFuture<Void> refreshSemanticTokens() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void sync(final LatheSyncParams params) {}

  @Override
  public void testOutput(final TestOutputParams params) {}

  @Override
  public void testEvent(final TestEventParams params) {}

  @Override
  public void testFinished(final TestFinishedParams params) {}
}
