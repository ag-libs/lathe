package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.analysis.TokenScanner;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

final class LatheLanguageServer implements LanguageServer, LanguageClientAware {

  private static final Logger LOG = Logger.getLogger(LatheLanguageServer.class.getName());

  private final LatheTextDocumentService textDocumentService = new LatheTextDocumentService();

  @Override
  public void connect(final LanguageClient client) {
    textDocumentService.connect(client);
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(final InitializeParams params) {
    final var rootUri = rootUri(params);
    LOG.fine(() -> "[initialize] rootUri=%s client=%s".formatted(rootUri, params.getClientInfo()));

    textDocumentService.setWorkDoneProgressSupported(workDoneProgressSupported(params));
    if (rootUri != null) {
      textDocumentService.initialize(LatheUri.toPath(rootUri));
    } else {
      LOG.warning(() -> "[initialize] no rootUri — module registry not available");
    }

    final var capabilities = createCapabilities();
    return CompletableFuture.completedFuture(new InitializeResult(capabilities));
  }

  static ServerCapabilities createCapabilities() {
    final var capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
    capabilities.setCompletionProvider(new CompletionOptions(false, List.of(".")));
    capabilities.setHoverProvider(true);
    capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
    final var legend =
        new SemanticTokensLegend(TokenScanner.TOKEN_TYPES, TokenScanner.TOKEN_MODIFIERS);
    final var semanticTokensOptions = new SemanticTokensWithRegistrationOptions(legend);
    semanticTokensOptions.setFull(true);
    capabilities.setSemanticTokensProvider(semanticTokensOptions);
    capabilities.setDocumentFormattingProvider(true);
    capabilities.setDefinitionProvider(true);
    capabilities.setDeclarationProvider(true);
    capabilities.setImplementationProvider(true);
    capabilities.setTypeHierarchyProvider(true);
    capabilities.setCallHierarchyProvider(true);
    capabilities.setReferencesProvider(true);
    capabilities.setDocumentSymbolProvider(true);
    capabilities.setFoldingRangeProvider(true);
    final var codeActionOptions = new CodeActionOptions(List.of(CodeActionKind.QuickFix));
    capabilities.setCodeActionProvider(codeActionOptions);
    capabilities.setWorkspaceSymbolProvider(true);
    capabilities.setExecuteCommandProvider(
        new ExecuteCommandOptions(
            List.of(
                LatheWorkspaceService.RUN_TEST_COMMAND,
                LatheWorkspaceService.CANCEL_TEST_COMMAND,
                LatheWorkspaceService.LIST_RUNNABLES_COMMAND,
                LatheWorkspaceService.RESOURCE_REFRESH_COMMAND)));
    return capabilities;
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
  public void cancelProgress(final WorkDoneProgressCancelParams params) {
    textDocumentService.cancelProgress(params);
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocumentService;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return new LatheWorkspaceService(textDocumentService);
  }

  private static String rootUri(final InitializeParams params) {
    final var folders = params.getWorkspaceFolders();
    if (folders != null && !folders.isEmpty()) {
      return folders.getFirst().getUri();
    }

    return params.getRootUri();
  }

  private static boolean workDoneProgressSupported(final InitializeParams params) {
    return params.getCapabilities() != null
        && params.getCapabilities().getWindow() != null
        && Boolean.TRUE.equals(params.getCapabilities().getWindow().getWorkDoneProgress());
  }
}
