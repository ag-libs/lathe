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
  private final DocumentSession session;

  LatheTextDocumentService() {
    this(DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(final long debounceMs) {
    session = new DocumentSession(debounceMs, worker);
  }

  void connect(final LanguageClient client) {
    worker.execute(() -> session.connect(client));
  }

  void initialize(final Path workspaceRoot) {
    worker.execute(() -> session.initialize(workspaceRoot));
  }

  void close() {
    worker
        .submit(
            () -> {
              session.close();
              return null;
            })
        .join();
    worker.close();
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
    return worker.submit(() -> Either.forLeft(session.completion(uri, pos)));
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final var uri = params.getTextDocument().getUri();
    return worker.submit(() -> session.semanticTokens(uri));
  }

  @Override
  public CompletableFuture<Hover> hover(final HoverParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    return worker.submit(() -> session.hover(uri, pos));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      definition(final DefinitionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    return worker.submit(() -> session.definition(uri, pos));
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
