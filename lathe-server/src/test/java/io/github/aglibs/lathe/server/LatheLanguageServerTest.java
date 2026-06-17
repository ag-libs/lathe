package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.lsp4j.TextDocumentSyncKind;
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
    assertThat(capabilities.getReferencesProvider().getLeft()).isTrue();
    assertThat(capabilities.getDocumentSymbolProvider().getLeft()).isTrue();
    assertThat(capabilities.getFoldingRangeProvider().getLeft()).isTrue();
    assertThat(capabilities.getCodeActionProvider().getRight().getCodeActionKinds()).isNotEmpty();
    assertThat(capabilities.getWorkspaceSymbolProvider().getLeft()).isTrue();
  }
}
