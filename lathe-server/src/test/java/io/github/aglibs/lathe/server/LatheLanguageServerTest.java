package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonParser;
import io.github.aglibs.lathe.core.LatheCommands;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.format.ExternalCommandFormatEngine;
import io.github.aglibs.lathe.server.format.FormatterFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
    assertThat(capabilities.getCodeActionProvider().getRight().getResolveProvider()).isTrue();
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
  void createCapabilities_includesTypeDefinitionProvider() {
    final var capabilities = LatheLanguageServer.createCapabilities(null);

    assertThat(capabilities.getTypeDefinitionProvider().getLeft()).isTrue();
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
        .containsExactlyElementsOf(LatheCommands.ALL)
        .contains(LatheCommands.RUN_TEST, LatheCommands.STACK_RESOLVE);
  }

  @Test
  void createCapabilities_always_advertisesLatheProtocol() {
    final var capabilities = LatheLanguageServer.createCapabilities(null);

    assertThat(capabilities.getExperimental())
        .asInstanceOf(map(String.class, Object.class))
        .containsEntry(LatheFlags.PROTOCOL_CAPABILITY, LatheFlags.LATHE_PROTOCOL);
  }

  static Stream<Arguments> initialize_optionFormatter_cases() {
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
                .getAsJsonObject()),
        // An editor-wide default formatter is not read: formatting comes only from the project.
        Arguments.of(
            JsonParser.parseString(
                    "{\"lathe\":{\"style\":{\"formatter\":{\"engine\":\"command\",\"command\":[\"cat\"]}}}}")
                .getAsJsonObject()));
  }

  @ParameterizedTest
  @MethodSource("initialize_optionFormatter_cases")
  void initialize_optionFormatter_omitsFormatting(final Object initOptions) throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LatheLanguageClient.class));
    final var params = new InitializeParams();
    params.setInitializationOptions(initOptions);

    final var capabilities = server.initialize(params).get().getCapabilities();

    assertThat(capabilities.getDocumentFormattingProvider()).isNull();
    server.shutdown().join();
  }

  @ParameterizedTest
  @ValueSource(strings = {"google", "aosp", "palantir", "eclipse"})
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

  // A committed declaration runs from the jars the sync resolved for it.
  @Test
  void initialize_declaredFormatterResolvedBySync_advertisesFormatting() throws Exception {
    writeStyle(
        "{\"formatter\":{\"engine\":\"google\",\"version\":\"1.28.0\",\"classpath\":[\"/pinned/gjf.jar\"]}}");
    Files.writeString(
        root.resolve(LatheLayout.STYLE_SHARED_FILE), "{\"formatter\":{\"engine\":\"google\"}}");

    assertThat(initializeWithRoot(null).getDocumentFormattingProvider().getLeft()).isTrue();
  }

  private void writeStyle(final String json) throws Exception {
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(latheDir.resolve(LatheLayout.STYLE_FILE), json);
  }

  private ServerCapabilities initializeWithRoot(final Object initOptions) throws Exception {
    final var server = new LatheLanguageServer();
    server.connect(mock(LatheLanguageClient.class));
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
    server.connect(mock(LatheLanguageClient.class));

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
    server.connect(mock(LatheLanguageClient.class));
    final var params = new WorkDoneProgressCancelParams(Either.forLeft("unknown"));

    assertThatCode(() -> server.cancelProgress(params)).doesNotThrowAnyException();
    server.shutdown().join();
  }
}
