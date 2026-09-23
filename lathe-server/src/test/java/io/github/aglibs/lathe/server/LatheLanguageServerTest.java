package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonParser;
import io.github.aglibs.lathe.core.LatheFlags;
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
            LatheWorkspaceService.RUN_NAMED_COMMAND,
            LatheWorkspaceService.LIST_RUN_CONFIGS_COMMAND,
            LatheWorkspaceService.SAVE_RUN_CONFIG_COMMAND,
            LatheWorkspaceService.CANCEL_TEST_COMMAND,
            LatheWorkspaceService.LIST_RUNNABLES_COMMAND,
            LatheWorkspaceService.DIR_RUNNABLES_COMMAND,
            LatheWorkspaceService.TEST_SOURCES_COMMAND,
            LatheWorkspaceService.RESOURCE_REFRESH_COMMAND,
            LatheWorkspaceService.INSTANTIATIONS_COMMAND,
            LatheWorkspaceService.TYPE_HIERARCHY_COMMAND,
            LatheWorkspaceService.CREATE_TYPE_COMMAND,
            LatheWorkspaceService.MODULES_COMMAND,
            LatheWorkspaceService.PACKAGES_COMMAND,
            LatheWorkspaceService.RESOLVE_CONTEXT_COMMAND,
            LatheWorkspaceService.MISSING_IMPORTS_COMMAND);
  }

  @Test
  void createCapabilities_always_advertisesLatheProtocol() {
    final var capabilities = LatheLanguageServer.createCapabilities(false);

    assertThat(capabilities.getExperimental())
        .asInstanceOf(map(String.class, Object.class))
        .containsEntry(LatheFlags.PROTOCOL_CAPABILITY, LatheFlags.LATHE_PROTOCOL);
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
  void initialize_always_advertisesServerInfoName() throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));

    final var serverInfo = server.initialize(new InitializeParams()).get().getServerInfo();

    assertThat(serverInfo).isNotNull();
    assertThat(serverInfo.getName()).isEqualTo(LatheLanguageServer.SERVER_NAME);
    server.shutdown().join();
  }

  @Test
  void serverInfo_outsideBuiltJar_omitsVersion() {
    final var serverInfo = LatheLanguageServer.serverInfo();

    assertThat(serverInfo.getName()).isEqualTo("lathe");
    assertThat(serverInfo.getVersion()).isNull();
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
