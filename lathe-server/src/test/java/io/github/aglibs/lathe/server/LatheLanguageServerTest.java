package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonParser;
import java.util.stream.Stream;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LatheLanguageServerTest {

  @Test
  void createCapabilities_supportedFeatures_advertisesProviders() {
    final var capabilities = LatheLanguageServer.createCapabilities(false);

    assertThat(capabilities.getTextDocumentSync().getLeft()).isEqualTo(TextDocumentSyncKind.Full);
    assertThat(capabilities.getCompletionProvider()).isNotNull();
    assertThat(capabilities.getHoverProvider().getLeft()).isTrue();
    assertThat(capabilities.getSignatureHelpProvider()).isNotNull();
    assertThat(capabilities.getSemanticTokensProvider()).isNotNull();
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
  void createCapabilities_formattingDisabled_omitsFormattingProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(false);

    assertThat(capabilities.getDocumentFormattingProvider()).isNull();
  }

  @Test
  void createCapabilities_formattingEnabled_advertisesFormattingProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(true);

    assertThat(capabilities.getDocumentFormattingProvider().getLeft()).isTrue();
  }

  @Test
  void createCapabilities_includesCallHierarchyProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(false);

    assertThat(capabilities.getCallHierarchyProvider().getLeft()).isTrue();
  }

  @Test
  void createCapabilities_includesExecuteCommandProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(false);

    assertThat(capabilities.getExecuteCommandProvider().getCommands())
        .containsExactlyInAnyOrder(
            LatheWorkspaceService.RUN_TEST_COMMAND,
            LatheWorkspaceService.RUN_MAIN_COMMAND,
            LatheWorkspaceService.CANCEL_TEST_COMMAND,
            LatheWorkspaceService.LIST_RUNNABLES_COMMAND,
            LatheWorkspaceService.RESOURCE_REFRESH_COMMAND);
  }

  @Test
  void initialize_formatterGoogle_advertisesFormatting() throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new InitializeParams();
    params.setInitializationOptions(
        JsonParser.parseString("{\"lathe\":{\"formatter\":\"google\"}}").getAsJsonObject());

    final var capabilities = server.initialize(params).get().getCapabilities();

    assertThat(capabilities.getDocumentFormattingProvider().getLeft()).isTrue();
    server.shutdown().join();
  }

  static Stream<Arguments> initialize_nonGoogleFormatter_cases() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(JsonParser.parseString("{}").getAsJsonObject()),
        Arguments.of(JsonParser.parseString("{\"lathe\":{}}").getAsJsonObject()),
        Arguments.of(
            JsonParser.parseString("{\"lathe\":{\"formatter\":\"eclipse\"}}").getAsJsonObject()));
  }

  @ParameterizedTest
  @MethodSource("initialize_nonGoogleFormatter_cases")
  void initialize_formatterNotGoogle_omitsFormatting(final Object initOptions) throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new InitializeParams();
    params.setInitializationOptions(initOptions);

    final var capabilities = server.initialize(params).get().getCapabilities();

    assertThat(capabilities.getDocumentFormattingProvider()).isNull();
    server.shutdown().join();
  }

  @Test
  void cancelProgress_unknownToken_routesWithoutFailure() {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new WorkDoneProgressCancelParams(Either.forLeft("unknown"));

    assertThatCode(() -> server.cancelProgress(params)).doesNotThrowAnyException();
    server.shutdown().join();
  }
}
