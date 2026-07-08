package io.github.aglibs.lathe.server;

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

  private static final String RUN_TEST_COMMAND = "lathe.run.test";

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
    if (!RUN_TEST_COMMAND.equals(params.getCommand())) {
      return CompletableFuture.completedFuture(null);
    }

    final var argument = parseRunTestArgument(params.getArguments().get(0));
    return textDocumentService
        .runTestFuture(argument.moduleRel(), argument.selection())
        .thenApply(outcome -> outcome);
  }

  private record RunTestArgument(String moduleRel, TestSelection selection) {}

  private static RunTestArgument parseRunTestArgument(final Object argument) {
    final var json = (JsonObject) argument;
    final var selection =
        new TestSelection(
            TestSelectionKind.valueOf(json.get("selectorKind").getAsString()),
            json.get("selectorValue").getAsString());
    return new RunTestArgument(json.get("moduleRel").getAsString(), selection);
  }
}
