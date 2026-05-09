package io.github.aglibs.lathe.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

final class StubWorkspaceService implements WorkspaceService {

  @Override
  public void didChangeConfiguration(final DidChangeConfigurationParams params) {}

  @Override
  public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {}
}
