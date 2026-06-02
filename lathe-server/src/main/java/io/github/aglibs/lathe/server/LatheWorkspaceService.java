package io.github.aglibs.lathe.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.services.WorkspaceService;

final class LatheWorkspaceService implements WorkspaceService {

  private final LatheTextDocumentService textDocumentService;

  LatheWorkspaceService(final LatheTextDocumentService textDocumentService) {
    this.textDocumentService = textDocumentService;
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
