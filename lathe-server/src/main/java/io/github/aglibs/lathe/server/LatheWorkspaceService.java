package io.github.aglibs.lathe.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

final class LatheWorkspaceService implements WorkspaceService {

  static final String RUN_TEST_COMMAND = "lathe.run.test";
  static final String LIST_RUNNABLES_COMMAND = "lathe.runnables.list";

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

  @Override
  public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {
    params.getChanges().stream()
        .filter(event -> event.getType() == FileChangeType.Deleted)
        .map(FileEvent::getUri)
        .forEach(textDocumentService::didDeleteWatchedFile);
  }

  @Override
  public CompletableFuture<Object> executeCommand(final ExecuteCommandParams params) {
    return switch (params.getCommand()) {
      case RUN_TEST_COMMAND -> runTest(params);
      case LIST_RUNNABLES_COMMAND -> listRunnables(params);
      default -> CompletableFuture.completedFuture(null);
    };
  }

  private CompletableFuture<Object> runTest(final ExecuteCommandParams params) {
    final var argument = parseRunTestArgument(params.getArguments().getFirst());
    return textDocumentService
        .runTestFuture(argument.moduleRel(), argument.selections(), argument.token())
        .thenApply(outcome -> outcome);
  }

  private CompletableFuture<Object> listRunnables(final ExecuteCommandParams params) {
    final String uri = parseListRunnablesArgument(params.getArguments().getFirst());
    return textDocumentService.runnablesFuture(uri).thenApply(targets -> targets);
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
