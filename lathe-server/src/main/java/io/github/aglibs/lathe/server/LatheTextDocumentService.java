package io.github.aglibs.lathe.server;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

final class LatheTextDocumentService implements TextDocumentService {

  private static final long DEFAULT_DEBOUNCE_MS = 500;

  private final ServerWorker worker = new ServerWorker();
  private final long debounceMs;
  private WorkspaceSession session;

  LatheTextDocumentService() {
    this(DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(final long debounceMs) {
    this.debounceMs = debounceMs;
  }

  void connect(final LanguageClient client) {
    session = new WorkspaceSession(client, worker, debounceMs);
  }

  void initialize(final Path workspaceRoot) {
    worker.execute(() -> session.initialize(workspaceRoot));
  }

  void close() {
    worker.submit(this::closeSession).join();

    worker.close();
  }

  private Void closeSession() {
    if (session != null) {
      session.close();
    }

    return null;
  }

  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    final var content = params.getTextDocument().getText();
    worker.execute(() -> session.onOpen(uri, content));
  }

  @Override
  public void didChange(final DidChangeTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    final var content = params.getContentChanges().getFirst().getText();
    worker.execute(() -> session.onChange(uri, content));
  }

  @Override
  public void didClose(final DidCloseTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    worker.execute(() -> session.onClose(uri));
  }

  @Override
  public void didSave(final DidSaveTextDocumentParams params) {
    final var uri = params.getTextDocument().getUri();
    final var content = params.getText();
    worker.execute(() -> session.onSave(uri, content));
  }

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
      final CompletionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    return worker
        .submit(() -> session.completionFuture(uri, pos))
        .thenCompose(f -> f)
        .thenApply(Either::forLeft);
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final var uri = params.getTextDocument().getUri();
    return worker.submit(() -> session.semanticTokensFuture(uri)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<Hover> hover(final HoverParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    return worker.submit(() -> session.hoverFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(final DefinitionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    return worker.submit(() -> session.definitionFuture(uri, pos)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(
      final DocumentFormattingParams params) {
    final var uri = params.getTextDocument().getUri();
    return worker.submit(() -> session.format("format", uri));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
      final DocumentRangeFormattingParams params) {
    final var uri = params.getTextDocument().getUri();
    return worker.submit(() -> session.format("rangeFormat", uri));
  }
}
