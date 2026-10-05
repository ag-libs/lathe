package io.github.aglibs.lathe.server;

import com.google.googlejavaformat.java.JavaFormatterOptions.Style;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.WorkspaceStyle;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.server.analysis.ExtractionSupport;
import io.github.aglibs.lathe.server.analysis.TokenScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
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

  static final String SERVER_NAME = "lathe";

  private final LatheTextDocumentService textDocumentService = new LatheTextDocumentService();

  @Override
  public void connect(final LanguageClient client) {
    textDocumentService.connect(client);
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(final InitializeParams params) {
    final var rootUri = rootUri(params);
    final Path rootPath = rootUri != null ? LatheUri.toPath(rootUri) : null;
    final FormatEngine formatEngine = resolveFormatEngine(params, rootPath);
    final boolean formattingEnabled = formatEngine != null;
    LOG.fine(
        () ->
            "[initialize] rootUri=%s client=%s formatting=%s"
                .formatted(rootUri, params.getClientInfo(), formattingEnabled));

    textDocumentService.setWorkDoneProgressSupported(workDoneProgressSupported(params));
    textDocumentService.setFileRenameSupported(fileRenameSupported(params));
    textDocumentService.setFormatEngine(formatEngine);
    if (rootPath != null) {
      textDocumentService.initialize(rootPath);
    } else {
      LOG.warning(() -> "[initialize] no rootUri — module registry not available");
    }

    final var capabilities = createCapabilities(formattingEnabled);
    final var result = new InitializeResult(capabilities);
    result.setServerInfo(serverInfo());
    return CompletableFuture.completedFuture(result);
  }

  // Version comes from the jar manifest (Implementation-Version); it is null outside a built jar
  // (dev runs, tests), which lsp4j serializes as absent — acceptable, since the standalone-client
  // protocol handshake keys off a separate constant, not this version string.
  static ServerInfo serverInfo() {
    final String version = LatheLanguageServer.class.getPackage().getImplementationVersion();
    return new ServerInfo(SERVER_NAME, version);
  }

  static ServerCapabilities createCapabilities(final boolean formattingEnabled) {
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
    if (formattingEnabled) {
      capabilities.setDocumentFormattingProvider(true);
    }

    capabilities.setDefinitionProvider(true);
    capabilities.setDeclarationProvider(true);
    capabilities.setImplementationProvider(true);
    capabilities.setTypeHierarchyProvider(true);
    capabilities.setCallHierarchyProvider(true);
    capabilities.setReferencesProvider(true);
    capabilities.setRenameProvider(new RenameOptions(true));
    capabilities.setDocumentHighlightProvider(true);
    capabilities.setDocumentSymbolProvider(true);
    capabilities.setFoldingRangeProvider(true);
    final var codeActionKinds = new ArrayList<String>();
    codeActionKinds.add(CodeActionKind.QuickFix);
    codeActionKinds.add(CodeActionKind.Refactor);
    codeActionKinds.add(CodeActionKind.RefactorExtract);
    codeActionKinds.add(CodeActionKind.RefactorRewrite);
    codeActionKinds.addAll(ExtractionSupport.KINDS);
    capabilities.setCodeActionProvider(new CodeActionOptions(codeActionKinds));
    capabilities.setWorkspaceSymbolProvider(true);
    capabilities.setExecuteCommandProvider(
        new ExecuteCommandOptions(
            List.of(
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
                LatheWorkspaceService.MISSING_IMPORTS_COMMAND)));
    capabilities.setExperimental(Map.of(LatheFlags.PROTOCOL_CAPABILITY, LatheFlags.LATHE_PROTOCOL));
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

  // The workspace style file wins when it declares a formatter; otherwise the client's
  // initializationOptions.lathe.formatter. "none"/unknown or absent means formatting is disabled.
  private static FormatEngine resolveFormatEngine(
      final InitializeParams params, final Path workingDir) {
    final FormatterSpec spec = workspaceFormatterSpec(workingDir);
    if (spec != null) {
      return engineFor(spec, workingDir);
    }

    return initOptionFormatEngine(params, workingDir);
  }

  private static FormatterSpec workspaceFormatterSpec(final Path workingDir) {
    if (workingDir == null) {
      return null;
    }

    try {
      final WorkspaceStyleData style = WorkspaceStyle.read(workingDir);
      return style != null ? style.formatter() : null;
    } catch (final IOException e) {
      LOG.warning(() -> "[initialize] malformed style file: %s".formatted(e.getMessage()));
      return null;
    }
  }

  private static FormatEngine engineFor(final FormatterSpec spec, final Path workingDir) {
    return switch (spec.engine()) {
      case LatheFlags.FORMATTER_GOOGLE -> new GoogleFormatEngine(Style.GOOGLE);
      case LatheFlags.FORMATTER_AOSP -> new GoogleFormatEngine(Style.AOSP);
      case LatheFlags.FORMATTER_COMMAND -> commandEngine(spec.command(), workingDir);
      case LatheFlags.FORMATTER_COMMAND_FILE -> fileCommandEngine(spec.command(), workingDir);
      default -> null;
    };
  }

  private static FormatEngine commandEngine(final List<String> command, final Path workingDir) {
    if (command.isEmpty()) {
      return null;
    }

    return new ExternalCommandFormatEngine(
        command, ExternalCommandFormatEngine.DEFAULT_TIMEOUT, workingDir);
  }

  private static FormatEngine fileCommandEngine(final List<String> command, final Path workingDir) {
    if (command.isEmpty() || workingDir == null) {
      return null;
    }

    return new FileCommandFormatEngine(
        command, workingDir, FileCommandFormatEngine.DEFAULT_TIMEOUT);
  }

  // The global-default formatter from initializationOptions.lathe.style.formatter, same {engine,
  // command} shape as the file, so it flows through engineFor too.
  private static FormatEngine initOptionFormatEngine(
      final InitializeParams params, final Path workingDir) {
    final JsonObject formatter = initOptionFormatter(params);
    if (formatter == null) {
      return null;
    }

    final JsonElement engine = formatter.get(LatheFlags.FORMATTER_ENGINE_OPTION);
    if (engine == null || !engine.isJsonPrimitive() || engine.getAsString().isBlank()) {
      return null;
    }

    return engineFor(new FormatterSpec(engine.getAsString(), commandOf(formatter)), workingDir);
  }

  private static JsonObject initOptionFormatter(final InitializeParams params) {
    if (!(params.getInitializationOptions() instanceof JsonObject options)) {
      return null;
    }

    final JsonElement lathe = options.get(LatheFlags.INIT_OPTIONS_KEY);
    if (lathe == null || !lathe.isJsonObject()) {
      return null;
    }

    final JsonElement style = lathe.getAsJsonObject().get(LatheFlags.STYLE_OPTION);
    if (style == null || !style.isJsonObject()) {
      return null;
    }

    final JsonElement formatter = style.getAsJsonObject().get(LatheFlags.FORMATTER_OPTION);
    return formatter != null && formatter.isJsonObject() ? formatter.getAsJsonObject() : null;
  }

  private static List<String> commandOf(final JsonObject formatter) {
    final JsonElement command = formatter.get(LatheFlags.FORMATTER_COMMAND_OPTION);
    if (command == null || !command.isJsonArray()) {
      return List.of();
    }

    return command.getAsJsonArray().asList().stream()
        .filter(JsonElement::isJsonPrimitive)
        .map(JsonElement::getAsString)
        .toList();
  }

  private static boolean workDoneProgressSupported(final InitializeParams params) {
    return params.getCapabilities() != null
        && params.getCapabilities().getWindow() != null
        && Boolean.TRUE.equals(params.getCapabilities().getWindow().getWorkDoneProgress());
  }

  // The client can apply a RenameFile resource operation — required to rename a public top-level
  // type, whose declaring .java file must move.
  private static boolean fileRenameSupported(final InitializeParams params) {
    final var capabilities = params.getCapabilities();
    if (capabilities == null
        || capabilities.getWorkspace() == null
        || capabilities.getWorkspace().getWorkspaceEdit() == null) {
      return false;
    }

    final var resourceOperations =
        capabilities.getWorkspace().getWorkspaceEdit().getResourceOperations();
    return resourceOperations != null && resourceOperations.contains(ResourceOperationKind.Rename);
  }
}
