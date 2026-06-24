package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

class LatheLanguageServerTest {

  @Test
  void createCapabilities_supportedFeatures_advertisesProviders() {
    final var capabilities = LatheLanguageServer.createCapabilities();

    assertThat(capabilities.getTextDocumentSync().getLeft()).isEqualTo(TextDocumentSyncKind.Full);
    assertThat(capabilities.getCompletionProvider()).isNotNull();
    assertThat(capabilities.getHoverProvider().getLeft()).isTrue();
    assertThat(capabilities.getSignatureHelpProvider()).isNotNull();
    assertThat(capabilities.getSemanticTokensProvider()).isNotNull();
    assertThat(capabilities.getDocumentFormattingProvider().getLeft()).isTrue();
    assertThat(capabilities.getDefinitionProvider().getLeft()).isTrue();
    assertThat(capabilities.getImplementationProvider().getLeft()).isTrue();
    assertThat(capabilities.getTypeHierarchyProvider().getLeft()).isTrue();
    assertThat(capabilities.getReferencesProvider().getLeft()).isTrue();
    assertThat(capabilities.getDocumentSymbolProvider().getLeft()).isTrue();
    assertThat(capabilities.getFoldingRangeProvider().getLeft()).isTrue();
    assertThat(capabilities.getCodeActionProvider().getRight().getCodeActionKinds()).isNotEmpty();
    assertThat(capabilities.getWorkspaceSymbolProvider().getLeft()).isTrue();
  }

  @Test
  void createCapabilities_includesCallHierarchyProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities();

    assertThat(capabilities.getCallHierarchyProvider().getLeft()).isTrue();
  }

  @Test
  void cancelProgress_unknownToken_routesWithoutFailure() {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new WorkDoneProgressCancelParams(Either.<String, Integer>forLeft("unknown"));

    assertThatCode(() -> server.cancelProgress(params)).doesNotThrowAnyException();
    server.shutdown().join();
  }
}
