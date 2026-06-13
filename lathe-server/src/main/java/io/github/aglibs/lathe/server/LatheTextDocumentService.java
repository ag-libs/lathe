package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

final class LatheTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getLogger(LatheTextDocumentService.class.getName());
  private static final long DEFAULT_DEBOUNCE_MS = 500;

  private final ServerEventLoop worker = new ServerEventLoop();
  private final long debounceMs;
  private WorkspaceSession session;

  LatheTextDocumentService() {
    this(DEFAULT_DEBOUNCE_MS);
  }

  LatheTextDocumentService(final long debounceMs) {
    this.debounceMs = debounceMs;
  }

  void connect(final LanguageClient client) {
    worker.execute(() -> session = new WorkspaceSession(client, worker, debounceMs));
  }

  void initialize(final Path workspaceRoot) {
    worker.execute(() -> session.initialize(workspaceRoot));
  }

  void didDeleteWatchedFile(final String uri) {
    worker.execute(() -> session.onDeletedFile(uri));
  }

  void close() {
    if (session != null) {
      worker
          .submit(
              () -> {
                session.close();
                return null;
              })
          .join();
    }

    worker.close();
  }

  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final var doc = params.getTextDocument();
    final var uri = doc.getUri();
    final var content = doc.getText();
    final var version = doc.getVersion();
    worker.execute(() -> session.onOpen(uri, content, version));
  }

  @Override
  public void didChange(final DidChangeTextDocumentParams params) {
    final var doc = params.getTextDocument();
    final var uri = doc.getUri();
    final var content = params.getContentChanges().getFirst().getText();
    final var version = doc.getVersion();
    worker.execute(() -> session.onChange(uri, content, version));
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
    final var ctx = params.getContext();
    final var context = ctx != null ? ctx : new CompletionContext(CompletionTriggerKind.Invoked);

    return worker
        .submit(() -> session.completionFuture(uri, pos, context))
        .thenCompose(f -> f)
        .thenApply(LatheTextDocumentService::completionResult);
  }

  static Either<List<CompletionItem>, CompletionList> completionResult(
      final CompletionOutcome outcome) {
    if (outcome.incomplete()) {
      return Either.forRight(new CompletionList(true, outcome.items()));
    }

    return Either.forLeft(outcome.items());
  }

  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(
      final CodeActionParams params) {
    final var uri = params.getTextDocument().getUri();
    final var context = params.getContext();
    return worker.submit(() -> session.codeActionFuture(uri, context)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<SemanticTokens> semanticTokensFull(final SemanticTokensParams params) {
    final var uri = params.getTextDocument().getUri();
    return worker.submit(() -> session.semanticTokensFuture(uri)).thenCompose(f -> f);
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(final ReferenceParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    final var incl = params.getContext().isIncludeDeclaration();
    return worker
        .submit(() -> session.referencesFuture(uri, pos, incl))
        .thenCompose(f -> f)
        .thenApply(r -> r);
  }

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(final SignatureHelpParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    return worker.submit(() -> session.signatureHelpFuture(uri, pos)).thenCompose(f -> f);
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

  @Override
  public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(
      final DocumentOnTypeFormattingParams params) {
    final var uri = params.getTextDocument().getUri();
    final var pos = params.getPosition();
    final String ch = params.getCh();
    final String chCode = ch == null || ch.isEmpty() ? "null" : "0x%X".formatted(ch.codePointAt(0));
    LOG.info(
        () ->
            "[onTypeFormatting] %s line=%d character=%d ch=%s"
                .formatted(uri, pos.getLine(), pos.getCharacter(), chCode));

    // TODO Implement conservative indentation edits for supported on-type triggers.
    return CompletableFuture.completedFuture(List.of());
  }
}
