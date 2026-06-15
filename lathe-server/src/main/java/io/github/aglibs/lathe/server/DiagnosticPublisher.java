package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.module.CompileResponse;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;

final class DiagnosticPublisher {

  private static final Logger LOG = Logger.getLogger(DiagnosticPublisher.class.getName());

  private final LanguageClient client;
  private final DocumentRegistry registry;

  DiagnosticPublisher(final LanguageClient client, final DocumentRegistry registry) {
    this.client = client;
    this.registry = registry;
  }

  boolean publishIfCurrent(final OpenDocument snapshot, final CompileResponse result) {
    if (registry.isStale(snapshot, result.generation())) {
      return false;
    }

    DiagnosticPayloadCodec.serializeDiagnosticData(result.diagnostics());
    client.publishDiagnostics(new PublishDiagnosticsParams(result.uri(), result.diagnostics()));
    client.refreshSemanticTokens();
    return true;
  }

  void refreshTokensIfCurrent(final OpenDocument snapshot, final CompileResponse result) {
    if (registry.isStale(snapshot, result.generation())) {
      client.refreshSemanticTokens();
    }
  }

  void publishEmpty(final String uri) {
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  }

  void publishMissing(final String uri, final String message) {
    LOG.warning(() -> "[compile] no module for %s".formatted(uri));
    client.publishDiagnostics(singleDiag(uri, message, DiagnosticSeverity.Warning));
  }

  void publishError(final OpenDocument snapshot, final CompileMode mode, final Throwable ex) {
    if (registry.isStale(snapshot, snapshot.generation())) {
      return;
    }

    LOG.log(SEVERE, ex, () -> "[compile:%s] failed for %s".formatted(mode.tag, snapshot.uri()));
    final var msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    client.publishDiagnostics(
        singleDiag(snapshot.uri(), "Lathe: %s".formatted(msg), DiagnosticSeverity.Error));
  }

  private static PublishDiagnosticsParams singleDiag(
      final String uri, final String message, final DiagnosticSeverity severity) {
    return new PublishDiagnosticsParams(
        uri,
        List.of(
            new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 1)), message, severity, "lathe")));
  }
}
