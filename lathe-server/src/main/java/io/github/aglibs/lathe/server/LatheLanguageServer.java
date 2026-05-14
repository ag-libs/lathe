package io.github.aglibs.lathe.server;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

final class LatheLanguageServer implements LanguageServer, LanguageClientAware {

  private static final Logger LOG = Logger.getLogger(LatheLanguageServer.class.getName());

  private final LatheTextDocumentService textDocumentService =
      new LatheTextDocumentService(ModuleRegistry.empty());

  @Override
  public void connect(final LanguageClient client) {
    textDocumentService.connect(client);
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(final InitializeParams params) {
    final var rootUri = rootUri(params);
    LOG.fine(() -> "[initialize] rootUri=%s client=%s".formatted(rootUri, params.getClientInfo()));

    if (rootUri != null) {
      final var workspaceRoot = Path.of(URI.create(rootUri));
      textDocumentService.setRegistry(ModuleRegistry.scan(workspaceRoot));
      textDocumentService.setManifest(WorkspaceManifest.load(workspaceRoot));
      textDocumentService.startWatching(workspaceRoot);
    } else {
      LOG.warning(() -> "[initialize] no rootUri — module registry not available");
    }

    final var capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
    capabilities.setHoverProvider(true);
    final var legend =
        new SemanticTokensLegend(TokenScanner.TOKEN_TYPES, TokenScanner.TOKEN_MODIFIERS);
    final var semanticTokensOptions = new SemanticTokensWithRegistrationOptions(legend);
    semanticTokensOptions.setFull(true);
    capabilities.setSemanticTokensProvider(semanticTokensOptions);
    capabilities.setDocumentFormattingProvider(true);
    capabilities.setDocumentRangeFormattingProvider(true);
    capabilities.setDefinitionProvider(true);
    return CompletableFuture.completedFuture(new InitializeResult(capabilities));
  }

  @Override
  public void initialized(final InitializedParams params) {
    LOG.info(() -> "[initialized] handshake complete");
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    LOG.info(() -> "[shutdown] shutdown requested");
    textDocumentService.close();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    LOG.info(() -> "[exit] exiting");
    System.exit(0);
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocumentService;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return new StubWorkspaceService();
  }

  private static String rootUri(final InitializeParams params) {
    final var folders = params.getWorkspaceFolders();
    if (folders != null && !folders.isEmpty()) {
      return folders.getFirst().getUri();
    }

    return params.getRootUri();
  }
}
