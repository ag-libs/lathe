package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonParser;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LatheLanguageServerTest {

  @TempDir private Path root;

  @Test
  void createCapabilities_supportedFeatures_advertisesProviders() {
    final var capabilities = LatheLanguageServer.createCapabilities(null);

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
    final var capabilities = LatheLanguageServer.createCapabilities(null);

    assertThat(capabilities.getDocumentFormattingProvider()).isNull();
  }

  @Test
  void createCapabilities_inProcessEngine_advertisesWholeAndRangeFormatting() {
    final var capabilities =
        LatheLanguageServer.createCapabilities(FormatterFixtures.googleJavaFormat());

    assertThat(capabilities.getDocumentFormattingProvider().getLeft()).isTrue();
    assertThat(capabilities.getDocumentRangeFormattingProvider().getRight().getRangesSupport())
        .isTrue();
  }

  @Test
  void createCapabilities_wholeFileEngine_omitsRangeFormatting() {
    final var capabilities =
        LatheLanguageServer.createCapabilities(
            new ExternalCommandFormatEngine(
                List.of("cat"), ExternalCommandFormatEngine.DEFAULT_TIMEOUT, root));

    assertThat(capabilities.getDocumentFormattingProvider().getLeft()).isTrue();
    assertThat(capabilities.getDocumentRangeFormattingProvider()).isNull();
  }

  @Test
  void createCapabilities_includesCallHierarchyProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(null);

    assertThat(capabilities.getCallHierarchyProvider().getLeft()).isTrue();
  }

  @Test
  void createCapabilities_includesExecuteCommandProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(null);

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
            LatheWorkspaceService.RESOURCES_COMMAND,
            LatheWorkspaceService.RESOURCE_OPEN_COMMAND,
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
    final var capabilities = LatheLanguageServer.createCapabilities(null);

    assertThat(capabilities.getExperimental())
        .asInstanceOf(map(String.class, Object.class))
        .containsEntry(LatheFlags.PROTOCOL_CAPABILITY, LatheFlags.LATHE_PROTOCOL);
  }

  @Test
  void initialize_optionFormatterCommand_advertisesFormatting() throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new InitializeParams();
    params.setInitializationOptions(
        JsonParser.parseString(
                "{\"lathe\":{\"style\":{\"formatter\":{\"engine\":\"command\",\"command\":[\"cat\"]}}}}")
            .getAsJsonObject());

    final var capabilities = server.initialize(params).get().getCapabilities();

    assertThat(capabilities.getDocumentFormattingProvider().getLeft()).isTrue();
    server.shutdown().join();
  }

  static Stream<Arguments> initialize_optionNoFormatter_cases() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(JsonParser.parseString("{}").getAsJsonObject()),
        Arguments.of(JsonParser.parseString("{\"lathe\":{}}").getAsJsonObject()),
        Arguments.of(JsonParser.parseString("{\"lathe\":{\"style\":{}}}").getAsJsonObject()),
        Arguments.of(
            JsonParser.parseString(
                    "{\"lathe\":{\"style\":{\"formatter\":{\"engine\":\"eclipse\"}}}}")
                .getAsJsonObject()),
        Arguments.of(
            JsonParser.parseString("{\"lathe\":{\"style\":{\"formatter\":{}}}}").getAsJsonObject()),
        // google without a resolved classpath: no formatter is bundled, so nothing can run it.
        Arguments.of(
            JsonParser.parseString(
                    "{\"lathe\":{\"style\":{\"formatter\":{\"engine\":\"google\"}}}}")
                .getAsJsonObject()),
        Arguments.of(
            JsonParser.parseString(
                    "{\"lathe\":{\"style\":{\"formatter\":{\"engine\":\"command\",\"command\":[]}}}}")
                .getAsJsonObject()));
  }

  @ParameterizedTest
  @MethodSource("initialize_optionNoFormatter_cases")
  void initialize_optionNoUsableFormatter_omitsFormatting(final Object initOptions)
      throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new InitializeParams();
    params.setInitializationOptions(initOptions);

    final var capabilities = server.initialize(params).get().getCapabilities();

    assertThat(capabilities.getDocumentFormattingProvider()).isNull();
    server.shutdown().join();
  }

  @ParameterizedTest
  @ValueSource(strings = {"google", "aosp", "palantir"})
  void initialize_styleFileInProcessEngine_advertisesFormatting(final String engine)
      throws Exception {
    writeStyle(
        "{\"formatter\":{\"engine\":\"%s\",\"classpath\":[\"/pinned/formatter.jar\"]}}"
            .formatted(engine));

    assertThat(initializeWithRoot(null).getDocumentFormattingProvider().getLeft()).isTrue();
  }

  @Test
  void initialize_styleFileWithoutClasspath_omitsFormatting() throws Exception {
    writeStyle("{\"formatter\":{\"engine\":\"google\"}}");

    assertThat(initializeWithRoot(null).getDocumentFormattingProvider()).isNull();
  }

  @Test
  void initialize_styleFileNone_overridesClientGoogleAndOmitsFormatting() throws Exception {
    writeStyle("{\"formatter\":{\"engine\":\"none\"}}");
    final var clientGoogle =
        JsonParser.parseString("{\"lathe\":{\"formatter\":\"google\"}}").getAsJsonObject();

    assertThat(initializeWithRoot(clientGoogle).getDocumentFormattingProvider()).isNull();
  }

  private void writeStyle(final String json) throws Exception {
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(latheDir.resolve(LatheLayout.STYLE_FILE), json);
  }

  private ServerCapabilities initializeWithRoot(final Object initOptions) throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LanguageClient.class));
    final var params = new InitializeParams();
    params.setRootUri(root.toUri().toString());
    params.setInitializationOptions(initOptions);

    final var capabilities = server.initialize(params).get().getCapabilities();
    server.shutdown().join();
    return capabilities;
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
