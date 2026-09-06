package io.github.aglibs.lathe.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import java.util.List;
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
  static final String RESOURCE_REFRESH_COMMAND = "lathe.resource.refresh";
  static final String DEBUG_TEST_COMMAND = "lathe.debug.test";
  static final String DEBUG_MAIN_COMMAND = "lathe.debug.main";
  static final String INSTANTIATIONS_COMMAND = "lathe.instantiations";
  static final String CREATE_TYPE_COMMAND = "lathe.createType";
  static final String MODULES_COMMAND = "lathe.modules";
  static final String PACKAGES_COMMAND = "lathe.packages";
  static final String RESOLVE_CONTEXT_COMMAND = "lathe.resolveContext";

  private static final Gson GSON = new Gson();

  private final LatheTextDocumentService textDocumentService;

  LatheWorkspaceService(final LatheTextDocumentService textDocumentService) {
    this.textDocumentService = textDocumentService;
  }

  @Override
  public CompletableFuture<
          Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
      symbol(final WorkspaceSymbolParams params) {
    return textDocumentService.workspaceSymbolFuture(params.getQuery()).thenApply(Either::forLeft);
  }

  @Override
  public void didChangeConfiguration(final DidChangeConfigurationParams params) {}

  // No-op: detection is a server-side scan (WorkspaceWatcher), not a client watch. Lathe registers
  // no file watchers, so this never fires; implemented only because WorkspaceService requires it.
  // See docs/planned/lathe-external-change-detection.md.
  @Override
  public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {}

  @Override
  public CompletableFuture<Object> executeCommand(final ExecuteCommandParams params) {
    return switch (params.getCommand()) {
      case RUN_TEST_COMMAND -> runTest(params);
      case RUN_MAIN_COMMAND -> runMain(params);
      case CANCEL_TEST_COMMAND -> cancelTest(params);
      case LIST_RUNNABLES_COMMAND -> listRunnables(params);
      case RESOURCE_REFRESH_COMMAND -> refreshResource(params);
      case DEBUG_TEST_COMMAND -> debugTest(params);
      case DEBUG_MAIN_COMMAND -> debugMain(params);
      case INSTANTIATIONS_COMMAND -> instantiations(params);
      case CREATE_TYPE_COMMAND -> createType(params);
      case MODULES_COMMAND -> modules();
      case PACKAGES_COMMAND -> packages(params);
      case RESOLVE_CONTEXT_COMMAND -> resolveContext(params);
      default -> CompletableFuture.completedFuture(null);
    };
  }

  private CompletableFuture<Object> debugTest(final ExecuteCommandParams params) {
    final var argument = parseRunTestArgument(params.getArguments().getFirst());
    return textDocumentService
        .debugTestFuture(argument.moduleRel(), argument.selections(), argument.token())
        .thenApply(result -> result);
  }

  private CompletableFuture<Object> debugMain(final ExecuteCommandParams params) {
    final var argument = parseRunMainArgument(params.getArguments().getFirst());
    return textDocumentService
        .debugMainFuture(argument.moduleRel(), argument.mainClass(), argument.token())
        .thenApply(result -> result);
  }

  private CompletableFuture<Object> runTest(final ExecuteCommandParams params) {
    final var argument = parseRunTestArgument(params.getArguments().getFirst());
    return textDocumentService
        .runTestFuture(argument.moduleRel(), argument.selections(), argument.token())
        .thenApply(outcome -> outcome);
  }

  private CompletableFuture<Object> runMain(final ExecuteCommandParams params) {
    final var argument = parseRunMainArgument(params.getArguments().getFirst());
    return textDocumentService
        .runMainFuture(argument.moduleRel(), argument.mainClass(), argument.token())
        .thenApply(outcome -> outcome);
  }

  private CompletableFuture<Object> cancelTest(final ExecuteCommandParams params) {
    final String token = parseCancelArgument(params.getArguments().getFirst());
    return textDocumentService.cancelRunFuture(token);
  }

  private CompletableFuture<Object> listRunnables(final ExecuteCommandParams params) {
    final String uri = parseListRunnablesArgument(params.getArguments().getFirst());
    return textDocumentService.runnablesFuture(uri).thenApply(targets -> targets);
  }

  private CompletableFuture<Object> refreshResource(final ExecuteCommandParams params) {
    final String uri = parseListRunnablesArgument(params.getArguments().getFirst());
    return textDocumentService.refreshResourceFuture(uri).thenApply(dest -> dest);
  }

  private CompletableFuture<Object> instantiations(final ExecuteCommandParams params) {
    // The argument is the client's make_position_params() -- a standard TextDocumentPositionParams,
    // so deserialize into that type rather than hand-parsing fields.
    final var at =
        GSON.fromJson(
            (JsonElement) params.getArguments().getFirst(), TextDocumentPositionParams.class);
    return textDocumentService
        .instantiationsFuture(at.getTextDocument().getUri(), at.getPosition())
        .thenApply(locations -> locations);
  }

  private CompletableFuture<Object> createType(final ExecuteCommandParams params) {
    final CreateTypeArgs args;
    try {
      args = createTypeArgs(params);
    } catch (final IllegalArgumentException e) {
      return CompletableFuture.failedFuture(invalidParams(e));
    }

    return textDocumentService
        .createTypeFuture(args)
        .thenApply(result -> (Object) result)
        .exceptionally(LatheWorkspaceService::rethrowInvalidParams);
  }

  private static CreateTypeArgs createTypeArgs(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().getFirst();
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
    final Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
    if (cause instanceof IllegalArgumentException iae) {
      throw invalidParams(iae);
    }

    throw (e instanceof RuntimeException re) ? re : new CompletionException(e);
  }

  private static ResponseErrorException invalidParams(final IllegalArgumentException e) {
    return new ResponseErrorException(
        new ResponseError(ResponseErrorCode.InvalidParams, e.getMessage(), null));
  }

  private CompletableFuture<Object> modules() {
    return textDocumentService.modulesFuture().thenApply(modules -> modules);
  }

  private CompletableFuture<Object> packages(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().getFirst();
    return textDocumentService
        .packagesFuture(json.get("moduleRel").getAsString())
        .thenApply(packages -> packages);
  }

  private CompletableFuture<Object> resolveContext(final ExecuteCommandParams params) {
    final var json = (JsonObject) params.getArguments().getFirst();
    return textDocumentService
        .resolveContextFuture(json.get("uri").getAsString())
        .thenApply(context -> context);
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

  private static String parseListRunnablesArgument(final Object argument) {
    final var json = (JsonObject) argument;
    return json.get("uri").getAsString();
  }
}
