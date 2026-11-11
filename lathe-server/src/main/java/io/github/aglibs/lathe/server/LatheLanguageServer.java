package io.github.aglibs.lathe.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.analysis.ExtractKinds;
import io.github.aglibs.lathe.analysis.TokenScanner;
import io.github.aglibs.lathe.core.LatheBuildInfo;
import io.github.aglibs.lathe.core.LatheCommands;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.server.format.FormatEngine;
import io.github.aglibs.lathe.server.format.FormatEngines;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DocumentRangeFormattingOptions;
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

  private final SessionHost host = new SessionHost();
  private final LatheTextDocumentService textDocumentService = new LatheTextDocumentService(host);

  // Loaded in initialized(), not initialize(): the load's progress is a server→client request the
  // spec forbids before the handshake (clients reject it as unhandled).
  private Path workspaceRoot;

  @Override
  public void connect(final LanguageClient client) {
    // Every client is a LatheLanguageClient: LatheServer's launcher and the in-process engine.
    host.connect((LatheLanguageClient) client);
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

    host.setWorkDoneProgressSupported(workDoneProgressSupported(params));
    textDocumentService.setFileRenameSupported(fileRenameSupported(params));
    textDocumentService.setCodeActionResolveSupported(codeActionResolveSupported(params));
    // An editor's formatting answers no edits without a formatter; only the agent asks why.
    host.execute(session -> session.formats().use(formatEngine, false));
    workspaceRoot = rootPath;
    if (rootPath == null) {
      LOG.warning(() -> "[initialize] no rootUri — module registry not available");
    }

    final var capabilities = createCapabilities(formatEngine);
    final var result = new InitializeResult(capabilities);
    result.setServerInfo(serverInfo());
    return CompletableFuture.completedFuture(result);
  }

  // Version comes from the jar manifest (Implementation-Version); outside a built jar (dev runs,
  // tests) it is absent, which is acceptable since the standalone-client protocol handshake keys
  // off a separate constant, not this version string.
  static ServerInfo serverInfo() {
    return new ServerInfo(
        SERVER_NAME, LatheBuildInfo.version(LatheLanguageServer.class).orElse(null));
  }

  static ServerCapabilities createCapabilities(final FormatEngine formatEngine) {
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
    if (formatEngine != null) {
      capabilities.setDocumentFormattingProvider(true);
    }

    if (formatEngine != null && formatEngine.formatsRanges()) {
      final var rangeFormatting = new DocumentRangeFormattingOptions();
      rangeFormatting.setRangesSupport(true);
      capabilities.setDocumentRangeFormattingProvider(rangeFormatting);
    }

    capabilities.setDefinitionProvider(true);
    capabilities.setDeclarationProvider(true);
    capabilities.setTypeDefinitionProvider(true);
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
    codeActionKinds.addAll(ExtractKinds.KINDS);
    final var codeActionOptions = new CodeActionOptions(codeActionKinds);
    codeActionOptions.setResolveProvider(true);
    capabilities.setCodeActionProvider(codeActionOptions);
    capabilities.setWorkspaceSymbolProvider(true);
    capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(LatheCommands.ALL));
    capabilities.setExperimental(Map.of(LatheFlags.PROTOCOL_CAPABILITY, LatheFlags.LATHE_PROTOCOL));
    return capabilities;
  }

  @Override
  public void initialized(final InitializedParams params) {
    LOG.info(() -> "[initialized] handshake complete");
    // Load now (deferred from initialize): its progress create is a server→client request clients
    // only accept post-handshake.
    if (workspaceRoot != null) {
      host.initialize(workspaceRoot);
    }
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    LOG.info(() -> "[shutdown] shutdown requested");
    host.close();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    LOG.info(() -> "[exit] exiting");
    System.exit(0);
  }

  @Override
  public void cancelProgress(final WorkDoneProgressCancelParams params) {
    host.cancelProgress(params);
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocumentService;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return new LatheWorkspaceService(host);
  }

  private static String rootUri(final InitializeParams params) {
    final var folders = params.getWorkspaceFolders();
    if (folders != null && !folders.isEmpty()) {
      return folders.get(0).getUri();
    }

    return params.getRootUri();
  }

  // The workspace style file wins when it declares a formatter; otherwise the client's
  // initializationOptions.lathe.style.formatter. "none", unknown, or absent disables formatting.
  private FormatEngine resolveFormatEngine(final InitializeParams params, final Path workingDir) {
    final FormatterSpec spec = FormatEngines.workspaceSpec(workingDir);
    if (spec != null) {
      return FormatEngines.forSpec(spec, workingDir);
    }

    return initOptionFormatEngine(params, workingDir);
  }

  // The global-default formatter from initializationOptions.lathe.style.formatter, same {engine,
  // command} shape as the file, so it flows through FormatEngines.forSpec too.
  private FormatEngine initOptionFormatEngine(
      final InitializeParams params, final Path workingDir) {
    final JsonObject formatter = initOptionFormatter(params);
    if (formatter == null) {
      return null;
    }

    final JsonElement engine = formatter.get(LatheFlags.FORMATTER_ENGINE_OPTION);
    if (engine == null || !engine.isJsonPrimitive() || engine.getAsString().isBlank()) {
      return null;
    }

    return FormatEngines.forSpec(
        new FormatterSpec(engine.getAsString(), commandOf(formatter)), workingDir);
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

  // The client resolves a code action's edit later, keeping the data it was listed with: what lets
  // an action be compile-checked only when picked (CompileCheckedActions).
  private static boolean codeActionResolveSupported(final InitializeParams params) {
    final var capabilities = params.getCapabilities();
    if (capabilities == null
        || capabilities.getTextDocument() == null
        || capabilities.getTextDocument().getCodeAction() == null) {
      return false;
    }

    final var codeAction = capabilities.getTextDocument().getCodeAction();
    return Boolean.TRUE.equals(codeAction.getDataSupport())
        && codeAction.getResolveSupport() != null
        && codeAction.getResolveSupport().getProperties().contains("edit");
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
