package io.github.aglibs.lathe.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.core.ExceptionUtil;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.run.RunKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.WorkspaceService;

final class LatheWorkspaceService implements WorkspaceService {

  static final String RUN_TEST_COMMAND = "lathe.run.test";
  static final String RUN_MAIN_COMMAND = "lathe.run.main";
  static final String CANCEL_TEST_COMMAND = "lathe.run.cancel";
  static final String LIST_RUNNABLES_COMMAND = "lathe.runnables.list";
  static final String DIR_RUNNABLES_COMMAND = "lathe.runnables.dir";
  static final String TEST_SOURCES_COMMAND = "lathe.testSources";
  static final String TEST_COUNTERPARTS_COMMAND = "lathe.testCounterparts";
  static final String TEST_MODULES_COMMAND = "lathe.tests.modules";
  static final String DISCOVER_TESTS_COMMAND = "lathe.tests.discover";
  static final String RESOURCES_COMMAND = "lathe.resources";
  static final String RESOURCE_OPEN_COMMAND = "lathe.resourceOpen";
  static final String RESOURCE_REFRESH_COMMAND = "lathe.resource.refresh";
  static final String DEBUG_TEST_COMMAND = "lathe.debug.test";
  static final String DEBUG_MAIN_COMMAND = "lathe.debug.main";
  static final String RUN_NAMED_COMMAND = "lathe.run.named";
  static final String DEBUG_NAMED_COMMAND = "lathe.debug.named";
  static final String LIST_RUN_CONFIGS_COMMAND = "lathe.runconfigs.list";
  static final String SAVE_RUN_CONFIG_COMMAND = "lathe.runconfig.save";
  static final String INSTANTIATIONS_COMMAND = "lathe.instantiations";
  static final String TYPE_HIERARCHY_COMMAND = "lathe.typeHierarchy";
  static final String CREATE_TYPE_COMMAND = "lathe.createType";
  static final String MODULES_COMMAND = "lathe.modules";
  static final String PACKAGES_COMMAND = "lathe.packages";
  static final String RESOLVE_CONTEXT_COMMAND = "lathe.resolveContext";
  static final String MISSING_IMPORTS_COMMAND = "lathe.missingImports";
  static final String IMPORT_EDITS_COMMAND = "lathe.importEdits";

  private static final Gson GSON = new Gson();

  private static final Set<String> VALIDATING_COMMANDS =
      Set.of(RUN_NAMED_COMMAND, DEBUG_NAMED_COMMAND, SAVE_RUN_CONFIG_COMMAND, CREATE_TYPE_COMMAND);

  private final SessionHost host;

  LatheWorkspaceService(final SessionHost host) {
    this.host = host;
  }

  @Override
  public CompletableFuture<
          Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
      symbol(final WorkspaceSymbolParams params) {
    return host.onLoop(session -> session.features().workspaceSymbol(params.getQuery(), false))
        .thenApply(Either::forLeft);
  }

  @Override
  public void didChangeConfiguration(final DidChangeConfigurationParams params) {}

  // No-op: detection is a server-side scan (WorkspaceWatcher), not a client watch. Lathe registers
  // no file watchers, so this never fires; implemented only because WorkspaceService requires it.
  // See docs/planned/lathe-external-change-detection.md.
  @Override
  public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {}

  // Arguments a command cannot read are InvalidParams with the reason, not lsp4j's generic
  // "Internal error"; so is a value the work of a command that validates its input rejects (an
  // IllegalArgumentException). Elsewhere such an exception is a server bug, and answers as one.
  @Override
  public CompletableFuture<Object> executeCommand(final ExecuteCommandParams params) {
    final CompletableFuture<?> answer;
    try {
      answer = dispatch(params);
    } catch (final RuntimeException e) {
      return CompletableFuture.failedFuture(invalidParams(params.getCommand(), e));
    }

    final CompletableFuture<Object> result = answer.thenApply(value -> value);
    if (!VALIDATING_COMMANDS.contains(params.getCommand())) {
      return result;
    }

    return result.exceptionally(LatheWorkspaceService::rethrowInvalidParams);
  }

  private CompletableFuture<?> dispatch(final ExecuteCommandParams params) {
    return switch (params.getCommand()) {
      case RUN_TEST_COMMAND -> runTest(params);
      case RUN_MAIN_COMMAND -> runMain(params);
      case CANCEL_TEST_COMMAND -> cancelTest(params);
      case LIST_RUNNABLES_COMMAND -> listRunnables(params);
      case DIR_RUNNABLES_COMMAND -> dirRunnables(params);
      case TEST_SOURCES_COMMAND -> testSources(params);
      case TEST_COUNTERPARTS_COMMAND -> testCounterparts(params);
      case TEST_MODULES_COMMAND -> host.onLoop(session -> session.layout().testModules());
      case DISCOVER_TESTS_COMMAND -> discoverTests(params);
      case RESOURCES_COMMAND -> host.onLoop(session -> session.queries().resources());
      case RESOURCE_OPEN_COMMAND -> resourceOpen(params);
      case RESOURCE_REFRESH_COMMAND -> refreshResource(params);
      case DEBUG_TEST_COMMAND -> debugTest(params);
      case DEBUG_MAIN_COMMAND -> debugMain(params);
      case RUN_NAMED_COMMAND -> runNamed(params);
      case DEBUG_NAMED_COMMAND -> debugNamed(params);
      case LIST_RUN_CONFIGS_COMMAND -> host.onLoop(session -> session.runs().listRunConfigs());
      case SAVE_RUN_CONFIG_COMMAND -> saveRunConfig(params);
      case INSTANTIATIONS_COMMAND -> instantiations(params);
      case TYPE_HIERARCHY_COMMAND -> typeHierarchy(params);
      case CREATE_TYPE_COMMAND -> createType(params);
      case MODULES_COMMAND -> modules();
      case PACKAGES_COMMAND -> packages(params);
      case RESOLVE_CONTEXT_COMMAND -> resolveContext(params);
      case MISSING_IMPORTS_COMMAND -> missingImports(params);
      case IMPORT_EDITS_COMMAND -> importEdits(params);
      default -> CompletableFuture.completedFuture(null);
    };
  }

  private CompletableFuture<?> debugTest(final ExecuteCommandParams params) {
    final var argument = parseRunTestArgument(params.getArguments().get(0));
    return host.onLoopFuture(
        session ->
            session
                .runs()
                .debugTestFuture(argument.moduleRel(), argument.selections(), argument.token()));
  }

  private CompletableFuture<?> debugMain(final ExecuteCommandParams params) {
    final var argument = parseRunMainArgument(params.getArguments().get(0));
    return host.onLoopFuture(
        session ->
            session
                .runs()
                .debugMainFuture(argument.moduleRel(), argument.mainClass(), argument.token()));
  }

  private CompletableFuture<?> runTest(final ExecuteCommandParams params) {
    final var argument = parseRunTestArgument(params.getArguments().get(0));
    return host.onLoopFuture(
        session ->
            session
                .runs()
                .runTestFuture(argument.moduleRel(), argument.selections(), argument.token()));
  }

  private CompletableFuture<?> runMain(final ExecuteCommandParams params) {
    final var argument = parseRunMainArgument(params.getArguments().get(0));
    return host.onLoopFuture(
        session ->
            session
                .runs()
                .runMainFuture(argument.moduleRel(), argument.mainClass(), argument.token()));
  }

  private CompletableFuture<?> runNamed(final ExecuteCommandParams params) {
    final var argument = parseNamedArgument(params.getArguments().get(0));
    return host.onLoopFuture(
        session -> session.runs().runNamedFuture(argument.name(), argument.token()));
  }

  private CompletableFuture<?> debugNamed(final ExecuteCommandParams params) {
    final var argument = parseNamedArgument(params.getArguments().get(0));
    return host.onLoopFuture(
        session -> session.runs().debugNamedFuture(argument.name(), argument.token()));
  }

  private CompletableFuture<?> saveRunConfig(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().get(0);
    final String name = optionalString(json, "name");
    final RunKind kind = RunKind.valueOf(json.get("kind").getAsString());
    final List<TestSelection> selectors =
        json.has("selectors") ? parseSelections(json.getAsJsonArray("selectors")) : List.of();
    return host.onLoop(
        session ->
            session
                .runs()
                .saveRunConfig(
                    name,
                    json.get("moduleRel").getAsString(),
                    kind,
                    optionalString(json, "mainClass"),
                    selectors,
                    json.has("overwrite") && json.get("overwrite").getAsBoolean()));
  }

  private CompletableFuture<?> cancelTest(final ExecuteCommandParams params) {
    final String token = parseCancelArgument(params.getArguments().get(0));
    return host.onLoop(
        session -> {
          session.runs().cancelRun(token);
          return null;
        });
  }

  private CompletableFuture<?> listRunnables(final ExecuteCommandParams params) {
    final String uri = uriArgument(params.getArguments().get(0));
    return host.onLoopFuture(session -> session.features().runnablesFuture(uri));
  }

  private CompletableFuture<?> refreshResource(final ExecuteCommandParams params) {
    final String uri = uriArgument(params.getArguments().get(0));
    return host.onLoop(
        session -> session.resourceMirror().refresh(uri).map(Path::toString).orElse(null));
  }

  private CompletableFuture<?> instantiations(final ExecuteCommandParams params) {
    // The argument is the client's make_position_params() -- a standard TextDocumentPositionParams,
    // so deserialize into that type rather than hand-parsing fields.
    final var at =
        GSON.fromJson((JsonElement) params.getArguments().get(0), TextDocumentPositionParams.class);
    final String uri = LatheUri.canonical(at.getTextDocument().getUri());
    return host.onLoopCompose(
        null,
        (session, cancelChecker, progress) ->
            session.search().instantiationsFuture(uri, at.getPosition(), cancelChecker, progress));
  }

  private CompletableFuture<?> typeHierarchy(final ExecuteCommandParams params) {
    final var at =
        GSON.fromJson((JsonElement) params.getArguments().get(0), TextDocumentPositionParams.class);
    return host.onLoopFuture(
        session ->
            session
                .features()
                .typeHierarchyExplorerFuture(
                    LatheUri.canonical(at.getTextDocument().getUri()), at.getPosition()));
  }

  private CompletableFuture<?> createType(final ExecuteCommandParams params) {
    final CreateTypeArgs args = createTypeArgs(params);
    return host.onLoop(session -> session.queries().createType(args));
  }

  private static CreateTypeArgs createTypeArgs(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().get(0);
    return new CreateTypeArgs(
        json.get("moduleRel").getAsString(),
        SourceScope.fromWire(json.get("kind").getAsString()),
        json.get("pkg").getAsString(),
        TypeKind.fromWire(json.get("type").getAsString()),
        json.get("name").getAsString());
  }

  // Map a rejected name/scope/flavour (an IllegalArgumentException, sync from arg parsing or async
  // from the server) to an InvalidParams response so the editor shows the reason, not the generic
  // lsp4j "Internal error." wrapping.
  private static Object rethrowInvalidParams(final Throwable e) {
    final Throwable cause = ExceptionUtil.completionCause(e);
    if (cause instanceof final IllegalArgumentException iae) {
      throw invalidParams(iae.getMessage());
    }

    throw (e instanceof RuntimeException re) ? re : new CompletionException(e);
  }

  private static ResponseErrorException invalidParams(final String message) {
    return new ResponseErrorException(
        new ResponseError(ResponseErrorCode.InvalidParams, message, null));
  }

  // Arguments the command could not read: a missing or mistyped field.
  private static ResponseErrorException invalidParams(
      final String command, final RuntimeException e) {
    return invalidParams(
        e instanceof IllegalArgumentException
            ? e.getMessage()
            : "invalid arguments for %s: %s".formatted(command, e));
  }

  private CompletableFuture<?> modules() {
    return host.onLoop(session -> session.layout().modules()).thenApply(modules -> modules);
  }

  private CompletableFuture<?> packages(final ExecuteCommandParams params) {
    final String moduleRel = moduleRelArgument(params);
    return host.onLoop(session -> session.layout().packages(moduleRel));
  }

  private CompletableFuture<?> resolveContext(final ExecuteCommandParams params) {
    final Path file = LatheUri.toPath(uriArgument(params.getArguments().get(0)));
    return host.onLoop(session -> session.layout().resolveContext(file));
  }

  private CompletableFuture<?> dirRunnables(final ExecuteCommandParams params) {
    final Path dir = LatheUri.toPath(uriArgument(params.getArguments().get(0)));
    return host.onLoop(session -> session.layout().dirRun(dir));
  }

  private CompletableFuture<?> discoverTests(final ExecuteCommandParams params) {
    final String moduleRel = moduleRelArgument(params);
    return host.onLoopFuture(session -> session.runs().discoverTestsFuture(moduleRel));
  }

  private CompletableFuture<?> testSources(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().get(0);
    final List<String> classNames =
        json.getAsJsonArray("classNames").asList().stream().map(JsonElement::getAsString).toList();
    final String moduleRel = json.get("moduleRel").getAsString();
    return host.onLoop(session -> session.layout().testSources(moduleRel, classNames));
  }

  private CompletableFuture<?> testCounterparts(final ExecuteCommandParams params) {
    final String uri = uriArgument(params.getArguments().get(0));
    return host.onLoop(session -> session.features().testCounterparts(uri));
  }

  private CompletableFuture<?> resourceOpen(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().get(0);
    final String jar = json.get("jar").getAsString();
    final String entry = json.get("entry").getAsString();
    return host.onLoop(session -> session.queries().resourceOpen(jar, entry));
  }

  private CompletableFuture<?> missingImports(final ExecuteCommandParams params) {
    final String uri = uriArgument(params.getArguments().get(0));
    return host.onLoopFuture(session -> session.features().missingImportsFuture(uri));
  }

  // The edits for the imports a client chose from lathe.missingImports, placed by the server so
  // each goes to its sorted place in its group.
  private CompletableFuture<?> importEdits(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().get(0);
    final List<String> chosen =
        json.getAsJsonArray("imports").asList().stream().map(JsonElement::getAsString).toList();
    final String uri = uriArgument(json);
    return host.onLoopFuture(session -> session.features().missingImportsFuture(uri))
        .thenApply(result -> result.edits(chosen));
  }

  private static String parseCancelArgument(final Object argument) {
    final var json = (JsonObject) argument;
    return json.has("token") ? json.get("token").getAsString() : "";
  }

  private record RunTestArgument(String moduleRel, List<TestSelection> selections, String token) {}

  private static RunTestArgument parseRunTestArgument(final Object argument) {
    final var json = (JsonObject) argument;
    final List<TestSelection> selections =
        json.getAsJsonArray("selections").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .map(LatheWorkspaceService::parseSelection)
            .toList();
    final String token = json.has("token") ? json.get("token").getAsString() : "";
    return new RunTestArgument(json.get("moduleRel").getAsString(), selections, token);
  }

  private record RunMainArgument(String moduleRel, String mainClass, String token) {}

  private static RunMainArgument parseRunMainArgument(final Object argument) {
    final var json = (JsonObject) argument;
    final String token = json.has("token") ? json.get("token").getAsString() : "";
    return new RunMainArgument(
        json.get("moduleRel").getAsString(), json.get("mainClass").getAsString(), token);
  }

  private static TestSelection parseSelection(final JsonObject json) {
    return new TestSelection(
        TestSelectionKind.valueOf(json.get("selectorKind").getAsString()),
        json.get("selectorValue").getAsString());
  }

  private static List<TestSelection> parseSelections(final JsonArray array) {
    return array.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .map(LatheWorkspaceService::parseSelection)
        .toList();
  }

  private record NamedArgument(String name, String token) {}

  private static NamedArgument parseNamedArgument(final Object argument) {
    final var json = (JsonObject) argument;
    final String token = json.has("token") ? json.get("token").getAsString() : "";
    return new NamedArgument(json.get("name").getAsString(), token);
  }

  private static String optionalString(final JsonObject json, final String key) {
    return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
  }

  private static String moduleRelArgument(final ExecuteCommandParams params) {
    return ((JsonObject) params.getArguments().get(0)).get("moduleRel").getAsString();
  }

  // A command's document URI, canonical like every URI that enters the server (LatheUri).
  private static String uriArgument(final Object argument) {
    return LatheUri.canonical(((JsonObject) argument).get("uri").getAsString());
  }
}
