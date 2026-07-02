package io.github.aglibs.lathe.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

final class LatheWorkspaceService implements WorkspaceService {

  private final LatheTextDocumentService textDocumentService;

  LatheWorkspaceService(final LatheTextDocumentService textDocumentService) {
    this.textDocumentService = textDocumentService;
  }

  @Override
  public CompletableFuture<
          Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
      symbol(final WorkspaceSymbolParams params) {
    return textDocumentService.workspaceSymbolFuture(params).thenApply(Either::forLeft);
  }

  @Override
  public void didChangeConfiguration(final DidChangeConfigurationParams params) {}

  @Override
  public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {
    params.getChanges().stream()
        .filter(event -> event.getType() == FileChangeType.Deleted)
        .map(FileEvent::getUri)
        .forEach(textDocumentService::didDeleteWatchedFile);
  }
}
